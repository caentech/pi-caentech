package tech.caen.pimanager.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.sql.Connection

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

object Db {
    fun init(dbPath: String) {
        File(dbPath).absoluteFile.parentFile?.mkdirs()
        Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
        // SQLite : un seul writer ; SERIALIZABLE évite les surprises de cohérence.
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
        transaction { SchemaUtils.create(DevicesTable) }
    }
}
