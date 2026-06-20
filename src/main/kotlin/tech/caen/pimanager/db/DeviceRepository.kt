package tech.caen.pimanager.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import tech.caen.pimanager.nowMillis
import java.util.UUID

data class DeviceRecord(
    val id: String,
    val name: String,
    val host: String,
    val sshUser: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastState: String?,
    val lastStatusJson: String?,
    val lastCheckedAt: Long?,
    val lastError: String?,
)

class DeviceRepository {
    // SQLite n'a qu'un writer : on sérialise les écritures via le verrou partagé
    // (cf. dbWriteLock) pour éviter SQLITE_BUSY quand le poller met à jour plusieurs
    // devices et insère des métriques en parallèle.
    private val writeLock = dbWriteLock

    private fun toRecord(r: ResultRow) = DeviceRecord(
        id = r[DevicesTable.id],
        name = r[DevicesTable.name],
        host = r[DevicesTable.host],
        sshUser = r[DevicesTable.sshUser],
        createdAt = r[DevicesTable.createdAt],
        updatedAt = r[DevicesTable.updatedAt],
        lastState = r[DevicesTable.lastState],
        lastStatusJson = r[DevicesTable.lastStatusJson],
        lastCheckedAt = r[DevicesTable.lastCheckedAt],
        lastError = r[DevicesTable.lastError],
    )

    suspend fun list(): List<DeviceRecord> = newSuspendedTransaction(Dispatchers.IO) {
        DevicesTable.selectAll().orderBy(DevicesTable.createdAt).map(::toRecord)
    }

    suspend fun get(id: String): DeviceRecord? = newSuspendedTransaction(Dispatchers.IO) {
        DevicesTable.select { DevicesTable.id eq id }.map(::toRecord).singleOrNull()
    }

    suspend fun create(name: String, host: String, sshUser: String?): DeviceRecord = writeLock.withLock {
        val now = nowMillis()
        val newId = UUID.randomUUID().toString()
        newSuspendedTransaction(Dispatchers.IO) {
            DevicesTable.insert {
                it[id] = newId
                it[DevicesTable.name] = name
                it[DevicesTable.host] = host
                it[DevicesTable.sshUser] = sshUser
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        get(newId)!!
    }

    suspend fun update(id: String, name: String?, host: String?, sshUser: String?, clearSshUser: Boolean): DeviceRecord? {
        writeLock.withLock {
            newSuspendedTransaction(Dispatchers.IO) {
                DevicesTable.update({ DevicesTable.id eq id }) {
                    if (name != null) it[DevicesTable.name] = name
                    if (host != null) it[DevicesTable.host] = host
                    if (clearSshUser) it[DevicesTable.sshUser] = null
                    else if (sshUser != null) it[DevicesTable.sshUser] = sshUser
                    it[updatedAt] = nowMillis()
                }
            }
        }
        return get(id)
    }

    suspend fun delete(id: String): Boolean = writeLock.withLock {
        newSuspendedTransaction(Dispatchers.IO) {
            DevicesTable.deleteWhere { DevicesTable.id eq id } > 0
        }
    }

    suspend fun updateStatus(id: String, state: String, statusJson: String?, checkedAt: Long, error: String?) {
        writeLock.withLock {
            newSuspendedTransaction(Dispatchers.IO) {
                DevicesTable.update({ DevicesTable.id eq id }) {
                    it[lastState] = state
                    it[lastStatusJson] = statusJson
                    it[lastCheckedAt] = checkedAt
                    it[lastError] = error
                }
            }
        }
    }
}
