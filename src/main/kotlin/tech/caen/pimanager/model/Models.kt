package tech.caen.pimanager.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * État d'un device, TOUJOURS dérivé du SSH-pull (jamais poussé par le Pi).
 * Les valeurs sérialisées correspondent à ce que consomme l'UI.
 */
@Serializable
enum class DeviceState {
    @SerialName("not connected")
    NOT_CONNECTED,

    @SerialName("new")
    NEW,

    @SerialName("to be configured")
    TO_BE_CONFIGURED,

    @SerialName("ready")
    READY,

    @SerialName("setup")
    SETUP;

    companion object {
        /** Parse une valeur de filtre (?state=) de façon tolérante. */
        fun fromSlug(value: String): DeviceState? = when (value.trim().lowercase()) {
            "not connected", "not_connected", "notconnected", "offline", "disconnected" -> NOT_CONNECTED
            "new", "no key", "no_key" -> NEW
            "to be configured", "to_be_configured", "tobeconfigured", "connection setup", "connect" -> TO_BE_CONFIGURED
            "ready" -> READY
            "setup", "setup in progress", "setup_in_progress", "in progress", "in_progress" -> SETUP
            else -> null
        }
    }
}

/**
 * Fichier UNIQUE déposé/lu sur le Pi (chemin par défaut `~/.pi-manager/pi-swarm.json`).
 * Il fusionne l'enrôlement (identité du device + marqueur `managedBy`) ET l'état
 * applicatif (`state`, infos d'affichage) : c'est le seul fichier que pi-manager
 * écrit (Configuration / Setup) et que l'appli d'affichage met à jour (à venir).
 *
 * Pilotage de l'état côté manager :
 *  - `managedBy` == `pi-manager` => le Pi est enrôlé (fichier valide) ; sinon `new` ;
 *  - `state` (`setup` à l'enrôlement, `ready` quand l'appli tourne) départage ensuite.
 *
 * Tous les champs sont optionnels et tolérants ; les champs d'affichage sont
 * AFFICHÉS en lecture seule (ils décrivent l'appli d'affichage, non encore pilotable).
 */
@Serializable
data class PiStatus(
    // --- Enrôlement (posé à la Configuration / au Setup) ---
    val deviceId: String? = null,
    val name: String? = null,
    val host: String? = null,
    val sshUser: String? = null,
    /** Marqueur d'enrôlement : `pi-manager` si le fichier a bien été déposé par nous. */
    val managedBy: String? = null,
    val configuredAt: String? = null,
    // --- État applicatif ---
    /** `setup` à l'enrôlement, `ready` quand l'appli d'affichage tourne. */
    val state: String? = null,
    val displayType: String? = null,
    val appVersion: String? = null,
    val siteUrl: String? = null,
    val hostname: String? = null,
    val ip: String? = null,
    val mac: String? = null,
    val uptime: String? = null,
    val message: String? = null,
    val updatedAt: String? = null,
    // Progression du setup (état "setup"), affichée en lecture.
    val progress: Int? = null,
    val step: String? = null,
    val music: MusicStatus? = null,
    val slide: SlideStatus? = null,
    val pageCache: PageCacheStatus? = null,
) {
    companion object {
        /** Marqueur d'enrôlement écrit dans le fichier déposé sur le Pi. */
        const val MANAGED_BY = "pi-manager"
    }
}

@Serializable
data class MusicStatus(val enabled: Boolean? = null, val track: String? = null)

@Serializable
data class SlideStatus(
    val current: Int? = null,
    val total: Int? = null,
    val title: String? = null,
    val dwell: Int? = null,
)

@Serializable
data class PageCacheStatus(
    val lastClearedAt: String? = null,
    /** "ok" (à jour) ou "stale" (périmé). */
    val status: String? = null,
)

// --- Requêtes ---

@Serializable
data class CreateDeviceRequest(
    val name: String,
    val host: String,
    val sshUser: String? = null,
)

@Serializable
data class UpdateDeviceRequest(
    val name: String? = null,
    val host: String? = null,
    val sshUser: String? = null,
)

/** Mot de passe du compte SSH, utilisé une seule fois pour la configuration (jamais stocké). */
@Serializable
data class ConfigureRequest(
    val password: String,
)

// --- Réponses ---

@Serializable
data class DeviceOverview(
    val id: String,
    val name: String,
    val host: String,
    val sshUser: String? = null,
    val state: DeviceState,
    val displayType: String? = null,
    val appVersion: String? = null,
    val sshCommand: String,
    val status: PiStatus? = null,
    val lastCheckedAt: String? = null,
    val lastError: String? = null,
)

@Serializable
data class DeviceDetail(
    val id: String,
    val name: String,
    val host: String,
    val sshUser: String? = null,
    val state: DeviceState,
    val sshCommand: String,
    val status: PiStatus? = null,
    val files: List<DeviceFile> = emptyList(),
    val deferredControls: List<DeferredControl> = emptyList(),
    val remoteLocations: List<RemoteLocation> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
    val lastCheckedAt: String? = null,
    val lastError: String? = null,
)

/**
 * Emplacement notable sur le Pi (aide-mémoire affiché dans le détail device).
 * Un clic ouvre une session SSH directement dans [dir].
 */
@Serializable
data class RemoteLocation(
    /** Libellé court (ex. « Application déployée »). */
    val label: String,
    /** Chemin mis en avant — peut désigner un fichier précis (ex. le fichier d'état). */
    val path: String,
    /** Répertoire où ouvrir la session SSH (parent de [path] quand celui-ci est un fichier). */
    val dir: String,
    /** Ce qu'on trouve à cet emplacement. */
    val hint: String,
)

@Serializable
data class DeviceFile(
    val id: String,
    val name: String,
    val size: Long,
    val modifiedAt: String,
)

/** Contrôle de l'appli d'affichage : affiché dans l'UI mais non pilotable (à venir). */
@Serializable
data class DeferredControl(
    val key: String,
    val label: String,
    val available: Boolean = false,
    val note: String,
)

@Serializable
data class Summary(
    val total: Int,
    val ready: Int,
    val setup: Int,
    val toBeConfigured: Int,
    val new: Int,
    val notConnected: Int,
)

/**
 * Échantillon de ressources d'un Pi, relevé à chaque SSH-pull (quand le Pi est joignable).
 * Volontairement grossier : sert à VISUALISER une TENDANCE (fuite mémoire, charge CPU),
 * pas à mesurer finement. Mémoire et CPU sont lus depuis `/proc` côté Pi.
 */
@Serializable
data class MetricSample(
    /** Horodatage du relevé (ISO-8601). */
    val at: String,
    /** Mémoire utilisée en % (100 × (MemTotal − MemAvailable) / MemTotal). */
    val memUsedPercent: Double,
    /** Mémoire utilisée en Mo. */
    val memUsedMb: Long,
    /** Mémoire totale en Mo. */
    val memTotalMb: Long,
    /** Charge CPU en % (delta /proc/stat sur ~1 s, tous cœurs confondus). */
    val cpuPercent: Double,
    /** Charge moyenne sur 1 min (/proc/loadavg). */
    val load1: Double,
)

/** Série temporelle des ressources d'un device sur la fenêtre de rétention. */
@Serializable
data class MetricsResponse(
    val deviceId: String,
    val windowMinutes: Int,
    val samples: List<MetricSample> = emptyList(),
)

@Serializable
data class CheckResult(
    val device: DeviceOverview,
    val changed: Boolean,
    val previousState: DeviceState? = null,
)

@Serializable
data class ActionResult(
    val ok: Boolean,
    val action: String,
    val exitCode: Int? = null,
    val output: String? = null,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class SshCommandResponse(
    val command: String,
    val target: String,
)

@Serializable
data class LogsResponse(
    val deviceId: String,
    val lines: Int,
    val command: String,
    val ok: Boolean,
    val output: String,
    val error: String? = null,
)

/** Événement temps réel publié par le poller / les actions (WS + SSE). */
@Serializable
data class StreamEvent(
    val type: String,
    val at: String,
    val deviceId: String? = null,
    val state: DeviceState? = null,
    val message: String? = null,
    val device: DeviceOverview? = null,
    /** Présent sur les événements `device.metrics` : le dernier échantillon de ressources. */
    val metric: MetricSample? = null,
)

@Serializable
data class ApiError(
    val error: String,
    val details: String? = null,
)

@Serializable
data class HealthResponse(
    val status: String,
    val devices: Int,
    val pollIntervalSeconds: Long,
    val uptimeSeconds: Long,
)
