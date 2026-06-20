package tech.caen.pimanager.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import tech.caen.pimanager.model.MetricSample
import tech.caen.pimanager.toIso

/**
 * Persistance de la série temporelle des ressources (mémoire + CPU) par device.
 * Les écritures passent par le verrou SQLite partagé ([dbWriteLock]).
 */
class MetricsRepository {

    /**
     * Insère un échantillon pour [deviceId] puis purge ceux antérieurs à la fenêtre
     * de rétention ([retentionMillis]) — la table reste ainsi bornée sans tâche dédiée.
     */
    suspend fun record(deviceId: String, atMillis: Long, sample: MetricSample, retentionMillis: Long) {
        dbWriteLock.withLock {
            newSuspendedTransaction(Dispatchers.IO) {
                MetricsTable.insert {
                    it[MetricsTable.deviceId] = deviceId
                    it[MetricsTable.atMillis] = atMillis
                    it[memUsedPercent] = sample.memUsedPercent
                    it[memUsedMb] = sample.memUsedMb
                    it[memTotalMb] = sample.memTotalMb
                    it[cpuPercent] = sample.cpuPercent
                    it[load1] = sample.load1
                }
                val cutoff = atMillis - retentionMillis
                MetricsTable.deleteWhere { (MetricsTable.deviceId eq deviceId) and (MetricsTable.atMillis less cutoff) }
            }
        }
    }

    /** Échantillons de [deviceId] depuis [sinceMillis], du plus ancien au plus récent. */
    suspend fun since(deviceId: String, sinceMillis: Long): List<MetricSample> =
        newSuspendedTransaction(Dispatchers.IO) {
            MetricsTable
                .select { (MetricsTable.deviceId eq deviceId) and (MetricsTable.atMillis greaterEq sinceMillis) }
                .orderBy(MetricsTable.atMillis)
                .map {
                    MetricSample(
                        at = it[MetricsTable.atMillis].toIso(),
                        memUsedPercent = it[MetricsTable.memUsedPercent],
                        memUsedMb = it[MetricsTable.memUsedMb],
                        memTotalMb = it[MetricsTable.memTotalMb],
                        cpuPercent = it[MetricsTable.cpuPercent],
                        load1 = it[MetricsTable.load1],
                    )
                }
        }

    /** Supprime toute la série d'un device (appelé à la suppression du device). */
    suspend fun deleteForDevice(deviceId: String) {
        dbWriteLock.withLock {
            newSuspendedTransaction(Dispatchers.IO) {
                MetricsTable.deleteWhere { MetricsTable.deviceId eq deviceId }
            }
        }
    }
}
