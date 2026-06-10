package tech.caen.pimanager.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import tech.caen.pimanager.appJson
import tech.caen.pimanager.badRequest
import tech.caen.pimanager.db.DeviceRecord
import tech.caen.pimanager.db.DeviceRepository
import tech.caen.pimanager.model.ActionResult
import tech.caen.pimanager.model.CheckResult
import tech.caen.pimanager.model.CreateDeviceRequest
import tech.caen.pimanager.model.DeferredControl
import tech.caen.pimanager.model.DeviceDetail
import tech.caen.pimanager.model.DeviceOverview
import tech.caen.pimanager.model.DeviceState
import tech.caen.pimanager.model.LogsResponse
import tech.caen.pimanager.model.PiStatus
import tech.caen.pimanager.model.StreamEvent
import tech.caen.pimanager.model.Summary
import tech.caen.pimanager.model.UpdateDeviceRequest
import tech.caen.pimanager.notFound
import tech.caen.pimanager.nowIso
import tech.caen.pimanager.nowMillis
import tech.caen.pimanager.ssh.SshService
import tech.caen.pimanager.toIso
import java.io.File

/**
 * Orchestration : registre (repository), SSH-pull (état), setup, actions,
 * fichiers et logs. C'est ici qu'on dérive l'état et qu'on publie les
 * changements sur le bus temps réel.
 */
class DeviceService(
    private val repo: DeviceRepository,
    private val ssh: SshService,
    private val files: FileStore,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val remoteStatusPath: String,
    private val remoteFilesDir: String,
) {
    private val log = LoggerFactory.getLogger(DeviceService::class.java)

    // --- Registre ---

    suspend fun requireDevice(id: String): DeviceRecord =
        repo.get(id) ?: throw notFound("Device '$id' introuvable")

    suspend fun overview(stateFilter: String?, query: String?): List<DeviceOverview> {
        var devices = repo.list().map(::toOverview)
        if (stateFilter != null) {
            val state = DeviceState.fromSlug(stateFilter)
                ?: throw badRequest("État inconnu: '$stateFilter'")
            devices = devices.filter { it.state == state }
        }
        if (!query.isNullOrBlank()) {
            val q = query.trim().lowercase()
            devices = devices.filter {
                it.name.lowercase().contains(q) || it.host.lowercase().contains(q) ||
                    (it.sshUser?.lowercase()?.contains(q) ?: false)
            }
        }
        return devices
    }

    suspend fun detail(id: String): DeviceDetail = toDetail(requireDevice(id))

    suspend fun create(req: CreateDeviceRequest): DeviceOverview {
        if (req.name.isBlank()) throw badRequest("Le nom est obligatoire")
        if (req.host.isBlank()) throw badRequest("Le host/IP est obligatoire")
        val record = repo.create(req.name.trim(), req.host.trim(), req.sshUser?.trim()?.ifBlank { null })
        // Première vérification en tâche de fond : l'état se peuplera vite (poller + WS).
        scope.launch { runCatching { check(record) }.onFailure { log.warn("Check initial échoué", it) } }
        return toOverview(record)
    }

    suspend fun update(id: String, req: UpdateDeviceRequest): DeviceOverview {
        requireDevice(id)
        if (req.name != null && req.name.isBlank()) throw badRequest("Le nom ne peut pas être vide")
        if (req.host != null && req.host.isBlank()) throw badRequest("Le host ne peut pas être vide")
        val clearSshUser = req.sshUser != null && req.sshUser.isBlank()
        val updated = repo.update(
            id = id,
            name = req.name?.trim(),
            host = req.host?.trim(),
            sshUser = req.sshUser?.trim()?.ifBlank { null },
            clearSshUser = clearSshUser,
        ) ?: throw notFound("Device '$id' introuvable")
        return toOverview(updated)
    }

    suspend fun delete(id: String) {
        if (!repo.delete(id)) throw notFound("Device '$id' introuvable")
    }

    suspend fun summary(): Summary {
        val states = repo.list().map { stateOf(it) }
        return Summary(
            total = states.size,
            ready = states.count { it == DeviceState.READY },
            setupInProgress = states.count { it == DeviceState.SETUP_IN_PROGRESS },
            new = states.count { it == DeviceState.NEW },
            notConnected = states.count { it == DeviceState.NOT_CONNECTED },
        )
    }

    // --- SSH-pull : détection d'état ---

    /** Force une vérification SSH immédiate, persiste, et publie si l'état change. */
    suspend fun check(record: DeviceRecord): CheckResult {
        val previous = parseState(record.lastState)
        val pull = pull(record)
        repo.updateStatus(record.id, pull.state.name, pull.rawStatus, nowMillis(), pull.error)
        val updated = repo.get(record.id) ?: record
        val overview = toOverview(updated)
        val changed = previous != pull.state
        if (changed) {
            eventBus.publish(
                StreamEvent(
                    type = "device.state.changed",
                    at = nowIso(),
                    deviceId = record.id,
                    state = pull.state,
                    message = pull.error,
                    device = overview,
                )
            )
            log.info("Device {} ({}): {} -> {}", record.name, record.host, previous, pull.state)
        }
        return CheckResult(overview, changed, previous)
    }

    suspend fun checkById(id: String): CheckResult = check(requireDevice(id))

    private data class PullResult(val state: DeviceState, val rawStatus: String?, val error: String?)

    private suspend fun pull(record: DeviceRecord): PullResult {
        // 1. Connexion SSH impossible -> not connected
        val conn = ssh.testConnection(record.host, record.sshUser)
        if (!conn.success) {
            val err = conn.stderr.trim().ifBlank {
                if (conn.timedOut) "Timeout de connexion SSH" else "Connexion SSH impossible"
            }
            return PullResult(DeviceState.NOT_CONNECTED, null, err)
        }
        // 2. SSH OK, pas de fichier de statut -> new
        val read = ssh.readFile(record.host, record.sshUser, remoteStatusPath)
        if (!read.success) {
            return PullResult(DeviceState.NEW, null, null)
        }
        // 3. SSH OK, fichier présent -> parser et lire `state`
        val raw = read.stdout.trim()
        val parsed = runCatching { appJson.decodeFromString<PiStatus>(raw) }.getOrNull()
        return when {
            parsed?.state == null ->
                PullResult(DeviceState.SETUP_IN_PROGRESS, raw.ifBlank { null }, "Fichier de statut présent mais illisible (JSON invalide ou champ 'state' absent)")
            parsed.state.contains("ready", ignoreCase = true) ->
                PullResult(DeviceState.READY, raw, null)
            else ->
                PullResult(DeviceState.SETUP_IN_PROGRESS, raw, null)
        }
    }

    // --- Setup ---

    /** Installe le fichier de statut sur le Pi (mkdir + scp). Rien d'autre. */
    suspend fun setup(record: DeviceRecord): ActionResult {
        val seed = appJson.encodeToString(
            PiStatus.serializer(),
            PiStatus(
                state = "setup in progress",
                message = "Fichier de statut installé par pi-manager. En attente de l'appli d'affichage.",
                updatedAt = nowIso(),
            )
        )
        val tmp = File.createTempFile("pi-status", ".json")
        try {
            tmp.writeText(seed)
            val dir = remoteStatusPath.substringBeforeLast('/', "")
            if (dir.isNotBlank()) ssh.exec(record.host, record.sshUser, "mkdir -p $dir")
            val res = ssh.scpTo(record.host, record.sshUser, tmp.absolutePath, remoteStatusPath)
            if (res.success) {
                // Re-checke pour refléter le nouvel état tout de suite.
                runCatching { check(record) }
                eventBus.publish(StreamEvent("device.setup", nowIso(), record.id, message = "Statut installé"))
            }
            return ActionResult(
                ok = res.success,
                action = "setup",
                exitCode = res.exitCode,
                output = res.stdout.trim().ifBlank { null },
                error = res.stderr.trim().ifBlank { null },
                message = if (res.success) "Fichier de statut installé dans $remoteStatusPath" else "Échec de l'installation du fichier de statut",
            )
        } finally {
            tmp.delete()
        }
    }

    // --- Actions device (SSH) ---

    suspend fun reboot(record: DeviceRecord): ActionResult =
        powerAction(record, "reboot", "sudo -n reboot", "Redémarrage demandé")

    suspend fun shutdown(record: DeviceRecord): ActionResult =
        powerAction(record, "shutdown", "sudo -n shutdown -h now", "Extinction demandée")

    private suspend fun powerAction(record: DeviceRecord, action: String, cmd: String, okMessage: String): ActionResult {
        val res = ssh.exec(record.host, record.sshUser, cmd)
        // La connexion peut tomber pendant reboot/shutdown : exit 255 = succès probable.
        val ok = res.success || res.exitCode == 255
        if (ok) {
            eventBus.publish(StreamEvent("device.action", nowIso(), record.id, message = "$action: $okMessage"))
        }
        return ActionResult(
            ok = ok,
            action = action,
            exitCode = res.exitCode,
            output = res.stdout.trim().ifBlank { null },
            error = res.stderr.trim().ifBlank { null },
            message = if (ok) okMessage else "Échec de l'action $action",
        )
    }

    // --- SSH (accès rapide) ---

    fun sshCommand(record: DeviceRecord): String = "ssh ${ssh.target(record.host, record.sshUser)}"

    fun sshTarget(record: DeviceRecord): String = ssh.target(record.host, record.sshUser)

    fun openTerminal(record: DeviceRecord): Boolean = ssh.openTerminal(record.host, record.sshUser)

    // --- Fichiers ---

    fun listFiles(deviceId: String) = files.list(deviceId)

    fun saveFile(deviceId: String, name: String, bytes: ByteArray) = files.save(deviceId, name, bytes)

    fun deleteFile(deviceId: String, fileId: String) {
        if (!files.delete(deviceId, fileId)) throw notFound("Fichier '$fileId' introuvable")
    }

    suspend fun pushFile(record: DeviceRecord, fileId: String): ActionResult {
        val file = files.find(record.id, fileId) ?: throw notFound("Fichier '$fileId' introuvable")
        val originalName = file.name.substringAfter("__", file.name)
        val remotePath = "$remoteFilesDir/$originalName"
        ssh.exec(record.host, record.sshUser, "mkdir -p $remoteFilesDir")
        val res = ssh.scpTo(record.host, record.sshUser, file.absolutePath, remotePath)
        if (res.success) {
            eventBus.publish(StreamEvent("device.file.pushed", nowIso(), record.id, message = originalName))
        }
        return ActionResult(
            ok = res.success,
            action = "push-file",
            exitCode = res.exitCode,
            output = res.stdout.trim().ifBlank { null },
            error = res.stderr.trim().ifBlank { null },
            message = if (res.success) "Fichier déployé dans $remotePath" else "Échec du déploiement",
        )
    }

    // --- Logs ---

    suspend fun logs(record: DeviceRecord, lines: Int, unit: String?, filePath: String?): LogsResponse {
        val command = when {
            filePath != null -> "tail -n $lines $filePath"
            unit != null -> "journalctl --no-pager -u $unit -n $lines"
            else -> tech.caen.pimanager.Config.logCommand.replace("{lines}", lines.toString())
        }
        val res = ssh.exec(record.host, record.sshUser, command)
        return LogsResponse(
            deviceId = record.id,
            lines = lines,
            command = command,
            ok = res.success,
            output = res.stdout.ifBlank { res.stderr },
            error = if (!res.success) res.stderr.trim().ifBlank { "Lecture des logs échouée" } else null,
        )
    }

    // --- Mapping ---

    private fun parseState(name: String?): DeviceState? =
        name?.let { runCatching { DeviceState.valueOf(it) }.getOrNull() }

    private fun stateOf(record: DeviceRecord): DeviceState =
        parseState(record.lastState) ?: DeviceState.NOT_CONNECTED

    private fun parseStatus(json: String?): PiStatus? =
        json?.let { runCatching { appJson.decodeFromString<PiStatus>(it) }.getOrNull() }

    private fun toOverview(record: DeviceRecord): DeviceOverview {
        val status = parseStatus(record.lastStatusJson)
        return DeviceOverview(
            id = record.id,
            name = record.name,
            host = record.host,
            sshUser = record.sshUser,
            state = stateOf(record),
            displayType = status?.displayType,
            appVersion = status?.appVersion,
            sshCommand = sshCommand(record),
            status = status,
            lastCheckedAt = record.lastCheckedAt?.toIso(),
            lastError = record.lastError,
        )
    }

    private fun toDetail(record: DeviceRecord): DeviceDetail = DeviceDetail(
        id = record.id,
        name = record.name,
        host = record.host,
        sshUser = record.sshUser,
        state = stateOf(record),
        sshCommand = sshCommand(record),
        status = parseStatus(record.lastStatusJson),
        files = files.list(record.id),
        deferredControls = DEFERRED_CONTROLS,
        createdAt = record.createdAt.toIso(),
        updatedAt = record.updatedAt.toIso(),
        lastCheckedAt = record.lastCheckedAt?.toIso(),
        lastError = record.lastError,
    )

    companion object {
        /**
         * Contrôles liés à l'appli d'affichage : AFFICHÉS dans l'UI mais non
         * pilotables tant que l'appli n'existe pas. Les endpoints associés
         * renvoient 501 Not Implemented.
         */
        val DEFERRED_CONTROLS = listOf(
            DeferredControl("music", "Musique on/off", false, "Nécessite l'appli d'affichage (à venir)"),
            DeferredControl("slide", "Changer de slide", false, "Nécessite l'appli d'affichage (à venir)"),
            DeferredControl("cache", "Vider le cache de page", false, "Nécessite l'appli d'affichage (à venir)"),
            DeferredControl("displayType", "Type d'affichage", false, "Nécessite l'appli d'affichage (à venir)"),
            DeferredControl("restart", "Redémarrer l'appli", false, "Nécessite l'appli d'affichage (à venir)"),
            DeferredControl("update", "Mise à jour applicative", false, "Nécessite l'appli d'affichage (à venir)"),
        )
    }
}
