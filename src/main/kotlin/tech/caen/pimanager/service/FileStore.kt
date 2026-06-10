package tech.caen.pimanager.service

import tech.caen.pimanager.model.DeviceFile
import tech.caen.pimanager.toIso
import java.io.File
import java.util.UUID

/**
 * Stockage local "dropbox" par device. Les fichiers sont rangés dans
 * <baseDir>/<deviceId>/ et nommés "<fileId>__<nomOriginal>" afin d'exposer
 * un id stable tout en conservant le nom d'origine.
 */
class FileStore(baseDir: String) {

    private val base = File(baseDir)

    private fun deviceDir(deviceId: String): File =
        File(base, deviceId).apply { mkdirs() }

    private fun sanitize(name: String): String =
        File(name).name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "fichier" }

    private fun toMeta(file: File): DeviceFile {
        val raw = file.name
        val idx = raw.indexOf("__")
        val (id, name) = if (idx > 0) raw.substring(0, idx) to raw.substring(idx + 2) else raw to raw
        return DeviceFile(id = id, name = name, size = file.length(), modifiedAt = file.lastModified().toIso())
    }

    fun list(deviceId: String): List<DeviceFile> =
        deviceDir(deviceId).listFiles()?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.map(::toMeta) ?: emptyList()

    fun save(deviceId: String, originalName: String, bytes: ByteArray): DeviceFile {
        val id = UUID.randomUUID().toString()
        val file = File(deviceDir(deviceId), "${id}__${sanitize(originalName)}")
        file.writeBytes(bytes)
        return toMeta(file)
    }

    fun find(deviceId: String, fileId: String): File? =
        deviceDir(deviceId).listFiles()?.firstOrNull { it.isFile && it.name.startsWith("${fileId}__") }

    fun delete(deviceId: String, fileId: String): Boolean =
        find(deviceId, fileId)?.delete() ?: false
}
