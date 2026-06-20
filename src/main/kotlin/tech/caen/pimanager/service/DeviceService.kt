package tech.caen.pimanager.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import tech.caen.pimanager.appJson
import tech.caen.pimanager.badRequest
import tech.caen.pimanager.db.DeviceRecord
import tech.caen.pimanager.db.DeviceRepository
import tech.caen.pimanager.db.MetricsRepository
import tech.caen.pimanager.model.ActionResult
import tech.caen.pimanager.model.CheckResult
import tech.caen.pimanager.model.CreateDeviceRequest
import tech.caen.pimanager.model.DeferredControl
import tech.caen.pimanager.model.DeviceDetail
import tech.caen.pimanager.model.DeviceOverview
import tech.caen.pimanager.model.DeviceState
import tech.caen.pimanager.model.LogsResponse
import tech.caen.pimanager.model.MetricsResponse
import tech.caen.pimanager.model.PiStatus
import tech.caen.pimanager.model.RemoteLocation
import tech.caen.pimanager.model.StreamEvent
import tech.caen.pimanager.model.Summary
import tech.caen.pimanager.model.UpdateDeviceRequest
import tech.caen.pimanager.notFound
import tech.caen.pimanager.nowIso
import tech.caen.pimanager.nowMillis
import tech.caen.pimanager.ssh.ExecResult
import tech.caen.pimanager.ssh.MetricsParser
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
    private val metricsRepo: MetricsRepository,
    private val ssh: SshService,
    private val provisioner: SshProvisioner,
    private val files: FileStore,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val remoteStatePath: String,
    private val remoteFilesDir: String,
    private val appDir: String,
    private val remoteAppDir: String,
    private val setupTimeoutSeconds: Long,
    private val metricsRetentionHours: Long,
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
        runCatching { metricsRepo.deleteForDevice(id) }
            .onFailure { log.warn("Purge des métriques de {} échouée: {}", id, it.message) }
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
        // Relève des ressources (mémoire + CPU) quand le Pi est joignable par clé.
        collectMetrics(record, pull.state)
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

    // --- Métriques (mémoire + CPU, tendance) ---

    /**
     * Relève les ressources du Pi et persiste un échantillon. Best-effort : silencieux
     * si le Pi n'est pas joignable par clé (états `not connected` / `new`) ou si la
     * commande / le parsing échoue — la dérivation d'état ne doit jamais en pâtir.
     * Publie l'échantillon en temps réel (`device.metrics`) pour alimenter le graphe.
     */
    private suspend fun collectMetrics(record: DeviceRecord, state: DeviceState) {
        if (state == DeviceState.NOT_CONNECTED || state == DeviceState.NEW) return
        val res = runCatching { ssh.readMetrics(record.host, record.sshUser) }.getOrNull() ?: return
        if (!res.success) return
        val sample = MetricsParser.parse(res.stdout) ?: return
        runCatching {
            metricsRepo.record(record.id, nowMillis(), sample, metricsRetentionHours * 3_600_000L)
            eventBus.publish(StreamEvent("device.metrics", sample.at, record.id, metric = sample))
        }.onFailure { log.warn("Métriques {} non enregistrées: {}", record.host, it.message) }
    }

    /** Série temporelle des ressources d'un device sur les [windowMinutes] dernières minutes. */
    suspend fun metrics(id: String, windowMinutes: Int): MetricsResponse {
        requireDevice(id)
        val since = nowMillis() - windowMinutes * 60_000L
        return MetricsResponse(
            deviceId = id,
            windowMinutes = windowMinutes,
            samples = metricsRepo.since(id, since),
        )
    }

    // --- Setup (déploiement applicatif) ---

    /**
     * Phase de setup d'un Pi enrôlé (état `setup`) : zippe le répertoire applicatif
     * local, l'envoie sur le Pi, le décompresse dans `/opt/pi-manager` puis lance `setup.sh`.
     * En cas de succès, bascule l'état du fichier pi-swarm.json sur le Pi en `ready` (le
     * marqueur `managedBy` est préservé) et re-checke pour refléter `ready` immédiatement.
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
            // Zip déposé en /tmp (le compte ne peut pas écrire dans /opt) ; décompressé ensuite
            // dans $remoteAppDir (/opt/pi-manager), créé et donné au compte à l'enrôlement.
            val remoteZip = "/tmp/pi-manager-app.zip"

            // 1. Copie du zip dans le staging /tmp.
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

            // 2. Décompression dans /opt/pi-manager (`unzip -o` écrase setup.sh / update.sh aux
            //    chemins autorisés par le sudoers NOPASSWD) puis lancement de setup.sh.
            val deployCmd = "mkdir -p $remoteAppDir && " +
                "unzip -o $remoteZip -d $remoteAppDir && rm -f $remoteZip && " +
                "chmod +x $remoteAppDir/setup.sh $remoteAppDir/update.sh && bash $remoteAppDir/setup.sh"
            // Timeout généreux : au premier passage, setup.sh installe LÖVE via apt.
            val run = ssh.exec(record.host, record.sshUser, deployCmd, timeout = setupTimeoutSeconds)
            if (!run.success) {
                return ActionResult(
                    ok = false,
                    action = "setup",
                    exitCode = run.exitCode,
                    output = run.stdout.trim().ifBlank { null },
                    error = run.stderr.trim().ifBlank { null },
                    message = "Échec du déploiement / lancement de setup.sh",
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
     * Configure un Pi en ligne mais non enrôlé (passe de bootstrap par mot de passe,
     * une seule fois) : vérifie la clé d'hôte en TOFU, pose la clé publique globale
     * (générée au besoin), installe le sudoers NOPASSWD (validé par visudo) puis dépose
     * le fichier pi-manager (état `setup`, marqueur `managedBy`). Le mot de passe n'est
     * jamais conservé. Après succès, la clé suffit (pilotage sans mot de passe sudo) :
     * un re-check fait passer le device en `setup`. L'empreinte SHA256 de la clé d'hôte
     * est exposée pour vérification par l'opérateur.
     */
    suspend fun configure(record: DeviceRecord, password: String): ActionResult {
        if (password.isBlank()) throw badRequest("Le mot de passe SSH est obligatoire")
        val user = record.sshUser?.ifBlank { null } ?: "pi"
        val publicKey = runCatching { provisioner.ensurePublicKey() }
            .getOrElse { return ActionResult(false, "configure", error = it.message, message = "Échec de préparation de la clé SSH") }

        val stateJson = appJson.encodeToString(PiStatus.serializer(), seedStatus(record))

        val res = provisioner.configure(record.host, user, password, publicKey, stateJson, remoteStatePath)
        val fingerprint = res.hostKeyFingerprint
        if (fingerprint != null) {
            log.info("Device {} ({}): empreinte clé d'hôte (SHA256) {}", record.name, record.host, fingerprint)
        }
        if (res.exec.success) {
            // La clé + le sudoers fonctionnent désormais : on reflète le nouvel état tout de suite.
            runCatching { check(record) }
            eventBus.publish(StreamEvent("device.configured", nowIso(), record.id, message = "Pi enrôlé (clé + sudoers NOPASSWD + fichier pi-manager)"))
        }
        val fpNote = fingerprint?.let { "Empreinte clé d'hôte (SHA256) : $it" }
        val output = listOfNotNull(res.exec.stdout.trim().ifBlank { null }, fpNote)
            .joinToString("\n").ifBlank { null }
        return ActionResult(
            ok = res.exec.success,
            action = "configure",
            exitCode = res.exec.exitCode,
            output = output,
            error = res.exec.stderr.trim().ifBlank { null },
            message = if (res.exec.success) {
                "Enrôlement réussi : clé SSH posée, sudoers NOPASSWD installé et fichier pi-manager déposé"
            } else if (res.hostKeyChanged) {
                "Enrôlement refusé : la clé d'hôte du Pi a changé (protection MITM)"
            } else {
                "Échec de l'enrôlement"
            },
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

    /**
     * Redémarre l'unité systemd d'affichage ([SIGNAGE_SERVICE]) — `systemctl restart`,
     * autorisé sans mot de passe par le sudoers NOPASSWD. Relance l'appli LÖVE (qui relit
     * son cache et son programme) sans rebooter le Pi : quelques secondes d'écran noir.
     */
    suspend fun restartApp(record: DeviceRecord): ActionResult {
        val res = ssh.exec(record.host, record.sshUser, "sudo -n systemctl restart $SIGNAGE_SERVICE")
        if (res.success) {
            eventBus.publish(StreamEvent("device.action", nowIso(), record.id, message = "restart: affichage redémarré"))
        }
        return ActionResult(
            ok = res.success,
            action = "restart",
            exitCode = res.exitCode,
            output = res.stdout.trim().ifBlank { null },
            error = res.stderr.trim().ifBlank { null },
            message = if (res.success) "Affichage redémarré" else "Échec du redémarrage de l'affichage",
        )
    }

    /**
     * Vide le cache d'assets de l'appli d'affichage (logos, photos, programme téléchargés)
     * puis redémarre l'affichage pour forcer un re-téléchargement. Le cache vit dans le
     * save dir LÖVE du compte qui lance le service (identité LÖVE = [SIGNAGE_SERVICE], et ce
     * compte est aussi le compte SSH) ; on ne supprime que le sous-dossier `cache/` afin de
     * préserver les réglages persistés (URL, mode, heure) écrits à la racine du save dir.
     *
     * Attention : un vidage hors ligne laisse l'affichage sans visuels jusqu'au retour du
     * réseau — c'est une action délibérée de l'opérateur.
     */
    suspend fun clearCache(record: DeviceRecord): ActionResult {
        // Save dir LÖVE sous Linux : $XDG_DATA_HOME/love/<identité> (défaut ~/.local/share/...).
        val cacheDir = "\${XDG_DATA_HOME:-\$HOME/.local/share}/love/$SIGNAGE_SERVICE/cache"
        val cmd = "rm -rf \"$cacheDir\" && sudo -n systemctl restart $SIGNAGE_SERVICE"
        val res = ssh.exec(record.host, record.sshUser, cmd)
        if (res.success) {
            eventBus.publish(StreamEvent("device.action", nowIso(), record.id, message = "cache: cache vidé, affichage redémarré"))
        }
        return ActionResult(
            ok = res.success,
            action = "cache",
            exitCode = res.exitCode,
            output = res.stdout.trim().ifBlank { null },
            error = res.stderr.trim().ifBlank { null },
            message = if (res.success) "Cache vidé — affichage redémarré (re-téléchargement)" else "Échec du vidage du cache",
        )
    }

    // --- SSH (accès rapide) ---

    fun sshCommand(record: DeviceRecord): String = ssh.command(record.host, record.sshUser)

    fun sshTarget(record: DeviceRecord): String = ssh.target(record.host, record.sshUser)

    fun openTerminal(record: DeviceRecord, cd: String? = null): Boolean =
        ssh.openTerminal(record.host, record.sshUser, cd)

    /**
     * Emplacements notables sur le Pi (aide-mémoire du détail device) : application,
     * app d'affichage, fichier d'état, unité systemd, sudoers. Dérivés des chemins
     * configurés ([remoteAppDir], [remoteStatePath]) + constantes d'enrôlement.
     */
    private fun remoteLocations(): List<RemoteLocation> {
        val stateDir = remoteStatePath.substringBeforeLast('/', "").ifBlank { "~" }
        return listOf(
            RemoteLocation(
                label = "Application déployée",
                path = remoteAppDir,
                dir = remoteAppDir,
                hint = "setup.sh, update.sh — scripts pilotés par pi-manager (sudo NOPASSWD)",
            ),
            RemoteLocation(
                label = "App d'affichage (LÖVE)",
                path = "$remoteAppDir/love",
                dir = "$remoteAppDir/love",
                hint = "main.lua, conf.lua — le signage Caen.tech",
            ),
            RemoteLocation(
                label = "État / enrôlement",
                path = remoteStatePath,
                dir = stateDir,
                hint = "pi-swarm.json — identité, marqueur managedBy, état (setup/ready)",
            ),
            RemoteLocation(
                label = "Service systemd",
                path = "$SYSTEMD_DIR/$SIGNAGE_SERVICE.service",
                dir = SYSTEMD_DIR,
                hint = "$SIGNAGE_SERVICE.service — lance LÖVE sur tty1 au boot",
            ),
            RemoteLocation(
                label = "Sudoers",
                path = "$SUDOERS_DIR/pi-manager",
                dir = SUDOERS_DIR,
                hint = "drop-in NOPASSWD : setup.sh/update.sh, systemctl, reboot, shutdown",
            ),
        )
    }

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
        remoteLocations = remoteLocations(),
        createdAt = record.createdAt.toIso(),
        updatedAt = record.updatedAt.toIso(),
        lastCheckedAt = record.lastCheckedAt?.toIso(),
        lastError = record.lastError,
    )

    companion object {
        /** Répertoire des unités systemd où vit le service d'affichage (cf. pi-app/setup.sh). */
        private const val SYSTEMD_DIR = "/etc/systemd/system"

        /** Nom du service systemd d'affichage installé par pi-app/setup.sh. */
        private const val SIGNAGE_SERVICE = "caentech-signage"

        /** Répertoire des drop-ins sudoers (cf. SshProvisioner, enrôlement). */
        private const val SUDOERS_DIR = "/etc/sudoers.d"

        /**
         * Contrôles liés à l'appli d'affichage : AFFICHÉS dans l'UI mais non
         * pilotables tant que l'appli n'existe pas. Les endpoints associés
         * renvoient 501 Not Implemented.
         */
        val DEFERRED_CONTROLS = listOf(
            DeferredControl("music", "Musique on/off", false, "Nécessite l'appli d'affichage (à venir)"),
            DeferredControl("slide", "Changer de slide", false, "Nécessite l'appli d'affichage (à venir)"),
            DeferredControl("displayType", "Type d'affichage", false, "Nécessite l'appli d'affichage (à venir)"),
            DeferredControl("update", "Mise à jour applicative", false, "Nécessite l'appli d'affichage (à venir)"),
        )
    }
}
