package tech.caen.pimanager.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
) {
    val success: Boolean get() = exitCode == 0 && !timedOut
}

/**
 * Encapsule TOUT l'accès aux Pi : on shelle vers les binaires système `ssh`/`scp`,
 * mais pi-manager n'utilise QUE sa propre clé — jamais le `~/.ssh` de l'utilisateur :
 *
 * - `-F /dev/null`         : ignore `~/.ssh/config` (alias, IdentityFile, etc.).
 * - `IdentitiesOnly=yes`   : n'offre que les clés passées via `-i` (ni agent, ni clés
 *   par défaut `~/.ssh/id_*`).
 * - BatchMode=yes          : pas de prompt interactif (échec net si pas de clé).
 * - ConnectTimeout         : coupe vite les hôtes injoignables.
 *
 * `identityFile` est la clé privée globale posée par la Configuration : on l'offre
 * via `-i` dès qu'elle existe. Tant qu'elle n'est pas posée, aucune identité n'est
 * proposée et `BatchMode=yes` provoque l'échec net `Permission denied` qui signale
 * un Pi en ligne mais sans notre clé (état `new`).
 */
class SshService(private val timeoutSeconds: Long, private val identityFile: String? = null) {

    private val log = LoggerFactory.getLogger(SshService::class.java)

    fun target(host: String, user: String?): String =
        if (user.isNullOrBlank()) host else "$user@$host"

    /** Isole pi-manager de `~/.ssh` : ni config, ni clés/agent de l'utilisateur. */
    private fun isolationArgs(): List<String> = listOf(
        "-F", "/dev/null",
        "-o", "IdentitiesOnly=yes",
    )

    /** `-i <clé>` uniquement si la clé globale de l'app a déjà été générée. */
    private fun identityArgs(): List<String> =
        identityFile?.takeIf { File(it).exists() }?.let { listOf("-i", it) } ?: emptyList()

    private fun sshArgs(): List<String> = listOf(
        "ssh",
        "-o", "BatchMode=yes",
        "-o", "StrictHostKeyChecking=accept-new",
        "-o", "ConnectTimeout=$timeoutSeconds",
    ) + isolationArgs() + identityArgs()

    private fun scpArgs(): List<String> = listOf(
        "scp",
        "-o", "BatchMode=yes",
        "-o", "StrictHostKeyChecking=accept-new",
        "-o", "ConnectTimeout=$timeoutSeconds",
        "-p",
    ) + isolationArgs() + identityArgs()

    /**
     * Commande `ssh` lisible (affichée dans l'UI / copiée dans le presse-papier).
     * Pointe vers la clé de l'app via `-i <chemin absolu>` dès qu'elle existe, pour
     * que l'utilisateur se connecte avec la même identité que pi-manager.
     */
    fun command(host: String, user: String?): String {
        val key = identityFile?.let { File(it) }?.takeIf { it.exists() }?.absolutePath
        val identity = if (key != null) "-i $key " else ""
        return "ssh $identity${target(host, user)}"
    }

    /** Teste la connexion SSH (commande triviale). */
    suspend fun testConnection(host: String, user: String?): ExecResult =
        run(sshArgs() + target(host, user) + "true", timeoutSeconds + 2)

    /** Lit un fichier distant via `cat`. `~` est expansé par le shell distant. */
    suspend fun readFile(host: String, user: String?, remotePath: String): ExecResult =
        run(sshArgs() + target(host, user) + "cat $remotePath", timeoutSeconds + 2)

    /** Exécute une commande arbitraire sur le Pi. */
    suspend fun exec(host: String, user: String?, remoteCommand: String): ExecResult =
        run(sshArgs() + target(host, user) + remoteCommand, timeoutSeconds + 2)

    /** Copie un fichier local vers le Pi (scp). */
    suspend fun scpTo(host: String, user: String?, localPath: String, remotePath: String): ExecResult {
        val tgt = target(host, user)
        return run(scpArgs() + localPath + "$tgt:$remotePath", timeoutSeconds + 60)
    }

    /** Ouvre un terminal SSH dans Terminal.app (macOS) via osascript. Fire-and-forget. */
    fun openTerminal(host: String, user: String?): Boolean {
        val sshCmd = command(host, user)
        val script = """
            tell application "Terminal"
                do script "$sshCmd"
                activate
            end tell
        """.trimIndent()
        return try {
            ProcessBuilder("osascript", "-e", script)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            true
        } catch (e: Exception) {
            log.warn("Impossible d'ouvrir le terminal: {}", e.message)
            false
        }
    }

    private suspend fun run(command: List<String>, timeout: Long): ExecResult = withContext(Dispatchers.IO) {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(false).start()
        } catch (e: Exception) {
            return@withContext ExecResult(-1, "", "Échec du lancement de '${command.firstOrNull()}': ${e.message}")
        }
        process.outputStream.close()
        coroutineScope {
            val stdout = async { process.inputStream.bufferedReader().readText() }
            val stderr = async { process.errorStream.bufferedReader().readText() }
            val finished = process.waitFor(timeout, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                val so = runCatching { stdout.await() }.getOrDefault("")
                val se = runCatching { stderr.await() }.getOrDefault("")
                ExecResult(-1, so, (se + "\nCommande expirée après ${timeout}s").trim(), timedOut = true)
            } else {
                ExecResult(process.exitValue(), stdout.await(), stderr.await())
            }
        }
    }
}
