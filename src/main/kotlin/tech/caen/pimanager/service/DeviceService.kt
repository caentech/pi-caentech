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
import tech.caen.pimanager.ssh.ExecResult
import tech.caen.pimanager.ssh.SshProvisioner
import tech.caen.pimanager.ssh.SshService
import tech.caen.pimanager.toIso
import tech.caen.pimanager.zipDirectoryContents
import java.io.File

/**
 * Orchestration : registre (repository), SSH-pull (état), setup, actions,
 * fichiers et logs. C'est ici qu'on dérive l'état et qu'on publie les
 * changements sur le bus temps réel.
 */
class DeviceService(
    private val repo: DeviceRepository,
    private val ssh: SshService,
    private val provisioner: SshProvisioner,
    private val files: FileStore,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val remoteStatePath: String,
    private val remoteFilesDir: String,
    private val appDir: String,
    private val remoteAppDir: String,
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
            setup = states.count { it == DeviceState.SETUP },
            toBeConfigured = states.count { it == DeviceState.TO_BE_CONFIGURED },
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
        // 1. Connexion SSH (avec NOTRE clé uniquement).
        val conn = ssh.testConnection(record.host, record.sshUser)
        if (!conn.success) {
            // `Permission denied` = hôte EN LIGNE mais notre clé n'est pas (encore) posée :
            // le Pi vient d'être ajouté, connexion SSH non établie -> `new`. Tout autre échec
            // (timeout, route, refus) = réellement injoignable -> `not connected`.
            if (conn.stderr.contains("permission denied", ignoreCase = true)) {
                return PullResult(DeviceState.NEW, null, null)
            }
            val err = conn.stderr.trim().ifBlank {
                if (conn.timedOut) "Timeout de connexion SSH" else "Connexion SSH impossible"
            }
            return PullResult(DeviceState.NOT_CONNECTED, null, err)
        }
        // 2. SSH OK (clé acceptée) : on lit le fichier UNIQUE pi-manager (enrôlement + état).
        //    Absent, illisible ou non marqué `managedBy = pi-manager` -> la clé fonctionne mais
        //    le Pi n'est pas (correctement) enrôlé -> `to be configured`. C'est ce fichier qui
        //    matérialise l'enrôlement.
        val read = ssh.readFile(record.host, record.sshUser, remoteStatePath)
        if (!read.success) {
            return PullResult(DeviceState.TO_BE_CONFIGURED, null, null)
        }
        val raw = read.stdout.trim()
        val parsed = runCatching { appJson.decodeFromString<PiStatus>(raw) }.getOrNull()
        if (parsed == null || parsed.managedBy != PiStatus.MANAGED_BY) {
            return PullResult(
                DeviceState.TO_BE_CONFIGURED,
                raw.ifBlank { null },
                "Fichier de configuration présent mais invalide (JSON illisible ou non enrôlé par pi-manager)",
            )
        }
        // 3. Enrôlé : `state` départage ready vs setup.
        return when {
            parsed.state?.contains("ready", ignoreCase = true) == true ->
                PullResult(DeviceState.READY, raw, null)
            else ->
                PullResult(DeviceState.SETUP, raw, null)
        }
    }

    // --- Setup (déploiement applicatif) ---

    /**
     * Phase de setup d'un Pi enrôlé (état `setup`) : zippe le répertoire applicatif
     * local, l'envoie sur le Pi, le décompresse puis lance `main.sh`. En cas de succès,
     * bascule l'état du fichier pi-swarm.json sur le Pi en `ready` (le marqueur
     * `managedBy` est préservé) et re-checke pour refléter `ready` immédiatement.
     */
    suspend fun setup(record: DeviceRecord): ActionResult {
        val source = File(appDir)
        if (!source.isDirectory) {
            return ActionResult(
                ok = false,
                action = "setup",
                error = "Répertoire applicatif introuvable: ${source.absolutePath}",
                message = "Répertoire applicatif introuvable",
            )
        }
        val zip = File.createTempFile("pi-app", ".zip")
        try {
            zipDirectoryContents(source, zip)
            val remoteZip = "$remoteAppDir.zip"

            // 1. Copie du zip (le dossier parent est créé au besoin).
            val parent = remoteAppDir.substringBeforeLast('/', "")
            if (parent.isNotBlank()) ssh.exec(record.host, record.sshUser, "mkdir -p $parent")
            val sent = ssh.scpTo(record.host, record.sshUser, zip.absolutePath, remoteZip)
            if (!sent.success) {
                return ActionResult(
                    ok = false,
                    action = "setup",
                    exitCode = sent.exitCode,
                    error = sent.stderr.trim().ifBlank { null },
                    message = "Échec de la copie de l'application sur le Pi",
                )
            }

            // 2. Décompression (dossier remis à zéro) puis lancement de main.sh.
            val deployCmd = "rm -rf $remoteAppDir && mkdir -p $remoteAppDir && " +
                "unzip -o $remoteZip -d $remoteAppDir && rm -f $remoteZip && " +
                "chmod +x $remoteAppDir/main.sh && bash $remoteAppDir/main.sh"
            val run = ssh.exec(record.host, record.sshUser, deployCmd)
            if (!run.success) {
                return ActionResult(
                    ok = false,
                    action = "setup",
                    exitCode = run.exitCode,
                    output = run.stdout.trim().ifBlank { null },
                    error = run.stderr.trim().ifBlank { null },
                    message = "Échec du déploiement / lancement de main.sh",
                )
            }

            // 3. Bascule l'état du Pi en `ready`.
            val ready = markReady(record)
            if (!ready.success) {
                return ActionResult(
                    ok = false,
                    action = "setup",
                    exitCode = ready.exitCode,
                    output = run.stdout.trim().ifBlank { null },
                    error = ready.stderr.trim().ifBlank { null },
                    message = "Application déployée mais bascule en `ready` échouée",
                )
            }

            // 4. Re-check pour refléter `ready` tout de suite + événement temps réel.
            runCatching { check(record) }
            eventBus.publish(StreamEvent("device.setup", nowIso(), record.id, message = "Setup terminé : application déployée et lancée"))
            return ActionResult(
                ok = true,
                action = "setup",
                exitCode = run.exitCode,
                output = run.stdout.trim().ifBlank { null },
                message = "Application déployée et lancée — device prêt (ready)",
            )
        } finally {
            zip.delete()
        }
    }

    /**
     * Réécrit le fichier pi-swarm.json du Pi avec `state = ready`. On relit d'abord le
     * fichier existant pour préserver ses champs (notamment `managedBy`) ; à défaut on
     * repart du seed.
     */
    private suspend fun markReady(record: DeviceRecord): ExecResult {
        val read = ssh.readFile(record.host, record.sshUser, remoteStatePath)
        val current = if (read.success) {
            runCatching { appJson.decodeFromString<PiStatus>(read.stdout.trim()) }.getOrNull()
        } else {
            null
        }
        val base = current ?: seedStatus(record)
        val ready = base.copy(
            managedBy = PiStatus.MANAGED_BY,
            state = "ready",
            message = "Setup terminé par pi-manager : application déployée et lancée.",
            updatedAt = nowIso(),
        )
        val tmp = File.createTempFile("pi-state", ".json")
        return try {
            tmp.writeText(appJson.encodeToString(PiStatus.serializer(), ready))
            val dir = remoteStatePath.substringBeforeLast('/', "")
            if (dir.isNotBlank()) ssh.exec(record.host, record.sshUser, "mkdir -p $dir")
            ssh.scpTo(record.host, record.sshUser, tmp.absolutePath, remoteStatePath)
        } finally {
            tmp.delete()
        }
    }

    /** Contenu initial du fichier pi-manager : identité + marqueur d'enrôlement + état `setup`. */
    private fun seedStatus(record: DeviceRecord): PiStatus = PiStatus(
        deviceId = record.id,
        name = record.name,
        host = record.host,
        sshUser = record.sshUser,
        managedBy = PiStatus.MANAGED_BY,
        configuredAt = nowIso(),
        state = "setup",
        message = "Fichier installé par pi-manager. En attente de l'appli d'affichage.",
        updatedAt = nowIso(),
    )

    // --- Configuration (enrôlement d'un Pi vierge) ---

    /**
     * Configure un Pi en ligne mais non enrôlé : pose la clé publique globale
     * (générée au besoin) via le mot de passe fourni, puis dépose le fichier
     * pi-manager (état `setup`, marqueur `managedBy`). Le mot de passe n'est jamais
     * conservé. Après succès, la clé suffit : un re-check fait passer le device en `setup`.
     */
    suspend fun configure(record: DeviceRecord, password: String): ActionResult {
        if (password.isBlank()) throw badRequest("Le mot de passe SSH est obligatoire")
        val user = record.sshUser?.ifBlank { null } ?: "pi"
        val publicKey = runCatching { provisioner.ensurePublicKey() }
            .getOrElse { return ActionResult(false, "configure", error = it.message, message = "Échec de préparation de la clé SSH") }

        val stateJson = appJson.encodeToString(PiStatus.serializer(), seedStatus(record))

        val res = provisioner.configure(record.host, user, password, publicKey, stateJson, remoteStatePath)
        if (res.success) {
            // La clé fonctionne désormais : on reflète le nouvel état tout de suite.
            runCatching { check(record) }
            eventBus.publish(StreamEvent("device.configured", nowIso(), record.id, message = "Pi configuré (clé + fichier pi-manager)"))
        }
        return ActionResult(
            ok = res.success,
            action = "configure",
            exitCode = res.exitCode,
            output = res.stdout.trim().ifBlank { null },
            error = res.stderr.trim().ifBlank { null },
            message = if (res.success) "Configuration réussie : clé SSH posée et fichier pi-manager déposé" else "Échec de la configuration",
        )
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

    fun sshCommand(record: DeviceRecord): String = ssh.command(record.host, record.sshUser)

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
