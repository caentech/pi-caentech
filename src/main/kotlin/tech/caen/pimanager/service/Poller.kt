package tech.caen.pimanager.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import tech.caen.pimanager.db.DeviceRepository

/**
 * Poller interne : interroge chaque device enregistré à intervalle régulier.
 * L'état dérive TOUJOURS de ce SSH-pull ; les changements sont publiés par
 * DeviceService.check() sur le bus temps réel.
 */
class Poller(
    private val scope: CoroutineScope,
    private val repo: DeviceRepository,
    private val service: DeviceService,
    private val intervalSeconds: Long,
) {
    private val log = LoggerFactory.getLogger(Poller::class.java)

    // Borne la concurrence SSH pour ne pas saturer la machine.
    private val gate = Semaphore(8)

    fun start(): Job = scope.launch {
        log.info("Poller démarré (intervalle = {}s)", intervalSeconds)
        while (isActive) {
            runCatching { pollOnce() }.onFailure { log.warn("Cycle de poll échoué: {}", it.message) }
            delay(intervalSeconds * 1000)
        }
    }

    private suspend fun pollOnce() {
        val devices = repo.list()
        if (devices.isEmpty()) return
        coroutineScope {
            devices.map { record ->
                async {
                    gate.withPermit {
                        runCatching { service.check(record) }
                            .onFailure { log.warn("Check {} échoué: {}", record.host, it.message) }
                    }
                }
            }.awaitAll()
        }
    }
}
