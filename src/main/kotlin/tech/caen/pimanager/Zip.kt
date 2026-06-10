package tech.caen.pimanager

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Compresse le CONTENU d'un répertoire dans un zip (les entrées sont relatives à
 * la racine du répertoire : `main.sh`, `sous-dossier/x`, etc., et non `app/...`).
 * Ainsi une décompression côté Pi avec `unzip -d <dir>` recrée directement
 * `<dir>/main.sh`. Le bit exécutable n'est pas porté par le zip : il est (re)posé
 * côté Pi (`chmod +x main.sh`) avant lancement.
 */
fun zipDirectoryContents(sourceDir: File, target: File) {
    val root = sourceDir.toPath()
    ZipOutputStream(target.outputStream().buffered()).use { zip ->
        sourceDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val entryName = root.relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
    }
}
