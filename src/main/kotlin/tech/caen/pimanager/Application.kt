package tech.caen.pimanager

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import tech.caen.pimanager.db.Db
import tech.caen.pimanager.db.DeviceRepository
import tech.caen.pimanager.model.ApiError
import tech.caen.pimanager.service.DeviceService
import tech.caen.pimanager.service.EventBus
import tech.caen.pimanager.service.FileStore
import tech.caen.pimanager.service.Poller
import tech.caen.pimanager.ssh.SshProvisioner
import tech.caen.pimanager.ssh.SshService

private val log = LoggerFactory.getLogger("tech.caen.pimanager.Application")

fun main() {
    Db.init(Config.dbPath)
    log.info("pi-manager démarre sur le port {} (DB={})", Config.port, Config.dbPath)
    embeddedServer(Netty, port = Config.port, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

fun Application.module() {
    val startedAt = nowMillis()
    val repo = DeviceRepository()
    val ssh = SshService(Config.sshTimeoutSeconds, Config.identityFile)
    val provisioner = SshProvisioner(Config.identityFile, Config.sshTimeoutSeconds)
    val fileStore = FileStore(Config.localFilesDir)
    val eventBus = EventBus()
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val service = DeviceService(
        repo = repo,
        ssh = ssh,
        provisioner = provisioner,
        files = fileStore,
        eventBus = eventBus,
        scope = appScope,
        remoteStatePath = Config.remoteStatePath,
        remoteFilesDir = Config.remoteFilesDir,
    )

    install(ContentNegotiation) { json(appJson) }

    install(CORS) {
        if (Config.corsHosts.trim() == "*") {
            anyHost()
        } else {
            Config.corsHosts.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach {
                allowHost(it, schemes = listOf("http", "https"))
            }
        }
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowNonSimpleContentTypes = true
    }

    install(CallLogging) {
        level = Level.INFO
        filter { it.request.local.uri.startsWith("/api") }
    }

    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ApiError(cause.message))
        }
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiError("Requête invalide", cause.message))
        }
        exception<Throwable> { call, cause ->
            log.error("Erreur non gérée", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("Erreur interne", cause.message))
        }
    }

    configureRouting(service, eventBus, startedAt, Config.pollIntervalSeconds)

    Poller(appScope, repo, service, Config.pollIntervalSeconds).start()

    environment.monitor.subscribe(ApplicationStopped) {
        log.info("Arrêt: annulation du scope applicatif")
        appScope.cancel()
    }
}
