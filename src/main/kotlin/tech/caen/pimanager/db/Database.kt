package tech.caen.pimanager.db

import kotlinx.coroutines.sync.Mutex
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.sql.Connection

/**
 * Verrou d'écriture global. SQLite n'a qu'un seul writer : on sérialise TOUTES les
 * écritures (registre des devices ET séries de métriques) pour éviter `SQLITE_BUSY`
 * quand le poller met à jour plusieurs devices et insère des métriques en parallèle.
 */
internal val dbWriteLock = Mutex()

object DevicesTable : Table("devices") {
    val id = varchar("id", 36)
    val name = varchar("name", 255)
    val host = varchar("host", 255)
    val sshUser = varchar("ssh_user", 255).nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val lastState = varchar("last_state", 32).nullable()
    val lastStatusJson = text("last_status_json").nullable()
    val lastCheckedAt = long("last_checked_at").nullable()
    val lastError = text("last_error").nullable()
    override val primaryKey = PrimaryKey(id)
}

/**
 * Série temporelle des ressources par device (mémoire + CPU), alimentée à chaque
 * SSH-pull. Bornée par rétention (cf. [tech.caen.pimanager.Config.metricsRetentionHours]) :
 * les vieux échantillons sont purgés à chaque insertion.
 */
object MetricsTable : Table("device_metrics") {
    val id = long("id").autoIncrement()
    val deviceId = varchar("device_id", 36)
    val atMillis = long("at_millis")
    val memUsedPercent = double("mem_used_percent")
    val memUsedMb = long("mem_used_mb")
    val memTotalMb = long("mem_total_mb")
    val cpuPercent = double("cpu_percent")
    val load1 = double("load1")
    override val primaryKey = PrimaryKey(id)

    init {
        // Lecture par device sur une fenêtre de temps + purge des vieux échantillons.
        index(false, deviceId, atMillis)
    }
}

object Db {
    fun init(dbPath: String) {
        File(dbPath).absoluteFile.parentFile?.mkdirs()
        Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
        // SQLite : un seul writer ; SERIALIZABLE évite les surprises de cohérence.
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
        transaction { SchemaUtils.create(DevicesTable, MetricsTable) }
    }
}
