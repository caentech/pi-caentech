package tech.caen.pimanager

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.http.content.staticResources
import io.ktor.http.CacheControl
import kotlinx.coroutines.flow.collect
import tech.caen.pimanager.model.ApiError
import tech.caen.pimanager.model.ConfigureRequest
import tech.caen.pimanager.model.CreateDeviceRequest
import tech.caen.pimanager.model.DeferredControl
import tech.caen.pimanager.model.HealthResponse
import tech.caen.pimanager.model.SshCommandResponse
import tech.caen.pimanager.model.StreamEvent
import tech.caen.pimanager.model.UpdateDeviceRequest
import tech.caen.pimanager.service.DeviceService
import tech.caen.pimanager.service.EventBus

fun Application.configureRouting(service: DeviceService, eventBus: EventBus, startedAt: Long, pollIntervalSeconds: Long) {

    routing {

        // --- Santé ---
        get("/api/health") {
            val devices = service.overview(null, null).size
            call.respond(
                HealthResponse(
                    status = "ok",
                    devices = devices,
                    pollIntervalSeconds = pollIntervalSeconds,
                    uptimeSeconds = (nowMillis() - startedAt) / 1000,
                )
            )
        }

        // --- Compteurs par état ---
        get("/api/summary") {
            call.respond(service.summary())
        }

        route("/api/devices") {

            // Liste (overview) + filtres ?state= ?q=
            get {
                val state = call.request.queryParameters["state"]
                val q = call.request.queryParameters["q"]
                call.respond(service.overview(state, q))
            }

            // Enregistrer un device
            post {
                val req = call.receive<CreateDeviceRequest>()
                call.respond(HttpStatusCode.Created, service.create(req))
            }

            route("/{id}") {

                // Détail
                get {
                    call.respond(service.detail(call.id()))
                }

                // Éditer le registre
                patch {
                    val req = call.receive<UpdateDeviceRequest>()
                    call.respond(service.update(call.id(), req))
                }

                // Retirer un device
                delete {
                    service.delete(call.id())
                    call.respond(HttpStatusCode.NoContent)
                }

                // --- Statut : forcer une vérification SSH immédiate ---
                post("/check") {
                    call.respond(service.checkById(call.id()))
                }

                // --- Setup : installer le fichier de statut ---
                post("/setup") {
                    val device = service.requireDevice(call.id())
                    call.respond(service.setup(device))
                }

                // --- Configuration : poser la clé SSH + pi-swarm.json (auth mot de passe) ---
                post("/configure") {
                    val device = service.requireDevice(call.id())
                    val req = call.receive<ConfigureRequest>()
                    call.respond(service.configure(device, req.password))
                }

                // --- Actions device (SSH) ---
                route("/actions") {
                    post("/reboot") {
                        call.respond(service.reboot(service.requireDevice(call.id())))
                    }
                    post("/shutdown") {
                        call.respond(service.shutdown(service.requireDevice(call.id())))
                    }
                    // Pilotage de l'appli d'affichage (via SSH + systemctl NOPASSWD).
                    post("/restart-app") {
                        call.respond(service.restartApp(service.requireDevice(call.id())))
                    }
                    post("/cache/clear") {
                        call.respond(service.clearCache(service.requireDevice(call.id())))
                    }
                    // Actions DIFFÉRÉES (pas encore pilotables côté appli) -> 501.
                    post("/music") { call.notImplemented("music") }
                    post("/slide") { call.notImplemented("slide") }
                    post("/display-type") { call.notImplemented("displayType") }
                    post("/update-app") { call.notImplemented("update") }
                }

                // --- SSH (accès rapide) ---
                get("/ssh") {
                    val device = service.requireDevice(call.id())
                    call.respond(SshCommandResponse(service.sshCommand(device), service.sshTarget(device)))
                }
                post("/ssh/open") {
                    val device = service.requireDevice(call.id())
                    // `cd` optionnel : ouvre la session directement dans ce dossier (aide-mémoire).
                    val cd = call.request.queryParameters["cd"]
                    val opened = service.openTerminal(device, cd)
                    if (opened) {
                        call.respond(SshCommandResponse(service.sshCommand(device), service.sshTarget(device)))
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, ApiError("Impossible d'ouvrir le terminal"))
                    }
                }

                // --- Dropbox / fichiers ---
                route("/files") {
                    get {
                        val device = service.requireDevice(call.id())
                        call.respond(service.listFiles(device.id))
                    }
                    post {
                        val device = service.requireDevice(call.id())
                        val contentType = call.request.contentType()
                        if (!contentType.match(ContentType.MultiPart.FormData)) {
                            throw badRequest("Upload attendu en multipart/form-data (reçu: $contentType)")
                        }
                        val multipart = call.receiveMultipart()
                        val saved = mutableListOf<tech.caen.pimanager.model.DeviceFile>()
                        multipart.forEachPart { part ->
                            if (part is PartData.FileItem) {
                                val name = part.originalFileName ?: "upload"
                                val bytes = part.streamProvider().readBytes()
                                saved += service.saveFile(device.id, name, bytes)
                            }
                            part.dispose()
                        }
                        if (saved.isEmpty()) throw badRequest("Aucun fichier fourni (champ multipart de type fichier attendu)")
                        call.respond(HttpStatusCode.Created, saved)
                    }
                    delete("/{fileId}") {
                        val device = service.requireDevice(call.id())
                        service.deleteFile(device.id, call.parameters["fileId"]!!)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    post("/{fileId}/push") {
                        val device = service.requireDevice(call.id())
                        call.respond(service.pushFile(device, call.parameters["fileId"]!!))
                    }
                }

                // --- Logs (via SSH) ---
                get("/logs") {
                    val device = service.requireDevice(call.id())
                    val lines = call.request.queryParameters["lines"]?.toIntOrNull()?.coerceIn(1, 5000) ?: 200
                    val unit = call.request.queryParameters["unit"]
                    val file = call.request.queryParameters["file"]
                    call.respond(service.logs(device, lines, unit, file))
                }

                // --- Métriques (mémoire + CPU, tendance) ---
                get("/metrics") {
                    // Fenêtre par défaut : 3 h (couvre une dérive mémoire) ; bornée à 7 j.
                    val window = call.request.queryParameters["window"]?.toIntOrNull()?.coerceIn(5, 10080) ?: 180
                    call.respond(service.metrics(call.id(), window))
                }
            }
        }

        // --- Temps réel : SSE ---
        get("/api/stream") {
            call.response.cacheControl(CacheControl.NoCache(null))
            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                // Confirme l'ouverture du flux.
                write("event: open\ndata: {}\n\n")
                flush()
                eventBus.events.collect { event ->
                    write("data: ${appJson.encodeToString(StreamEvent.serializer(), event)}\n\n")
                    flush()
                }
            }
        }

        // --- Frontend statique (SPA) servi à la racine ---
        staticResources("/", "web", index = "index.html")
    }
}

private fun io.ktor.server.application.ApplicationCall.id(): String =
    parameters["id"] ?: throw badRequest("id manquant")

private suspend fun io.ktor.server.application.ApplicationCall.notImplemented(controlKey: String) {
    val control = DeviceService.DEFERRED_CONTROLS.firstOrNull { it.key == controlKey }
    respond(
        HttpStatusCode.NotImplemented,
        ApiError(
            error = "Contrôle non implémenté: $controlKey",
            details = control?.note ?: "Nécessite l'appli d'affichage (à venir)",
        ),
    )
}
