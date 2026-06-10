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

    @SerialName("ready")
    READY,

    @SerialName("setup in progress")
    SETUP_IN_PROGRESS;

    companion object {
        /** Parse une valeur de filtre (?state=) de façon tolérante. */
        fun fromSlug(value: String): DeviceState? = when (value.trim().lowercase()) {
            "not connected", "not_connected", "notconnected", "offline", "disconnected" -> NOT_CONNECTED
            "new" -> NEW
            "ready" -> READY
            "setup in progress", "setup_in_progress", "setup", "in progress", "in_progress" -> SETUP_IN_PROGRESS
            else -> null
        }
    }
}

/**
 * Contenu du fichier de statut lu sur le Pi. Tous les champs sont optionnels
 * et tolérants : seul `state` pilote l'état. Les autres champs sont AFFICHÉS
 * en lecture seule (ils décrivent l'appli d'affichage, non encore pilotable).
 */
@Serializable
data class PiStatus(
    val state: String? = null,
    val displayType: String? = null,
    val appVersion: String? = null,
    val siteUrl: String? = null,
    val hostname: String? = null,
    val message: String? = null,
    val updatedAt: String? = null,
    val music: MusicStatus? = null,
    val slide: SlideStatus? = null,
    val pageCache: PageCacheStatus? = null,
)

@Serializable
data class MusicStatus(val enabled: Boolean? = null, val track: String? = null)

@Serializable
data class SlideStatus(val current: Int? = null, val total: Int? = null)

@Serializable
data class PageCacheStatus(val lastClearedAt: String? = null)

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
    val createdAt: String,
    val updatedAt: String,
    val lastCheckedAt: String? = null,
    val lastError: String? = null,
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
    val setupInProgress: Int,
    val new: Int,
    val notConnected: Int,
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
