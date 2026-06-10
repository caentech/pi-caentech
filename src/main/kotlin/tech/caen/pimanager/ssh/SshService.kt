package tech.caen.pimanager.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
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
 * Encapsule TOUT l'accès aux Pi : on shelle vers les binaires système `ssh`/`scp`
 * (réutilise donc ~/.ssh : clés, config, alias d'hôte). Aucun agent côté Pi.
 *
 * - BatchMode=yes  : pas de prompt interactif (échec net si pas de clé).
 * - ConnectTimeout : coupe vite les hôtes injoignables.
 */
class SshService(private val timeoutSeconds: Long) {

    private val log = LoggerFactory.getLogger(SshService::class.java)

    fun target(host: String, user: String?): String =
        if (user.isNullOrBlank()) host else "$user@$host"

    private fun sshArgs(): List<String> = listOf(
        "ssh",
        "-o", "BatchMode=yes",
        "-o", "StrictHostKeyChecking=accept-new",
        "-o", "ConnectTimeout=$timeoutSeconds",
    )

    private fun scpArgs(): List<String> = listOf(
        "scp",
        "-o", "BatchMode=yes",
        "-o", "StrictHostKeyChecking=accept-new",
        "-o", "ConnectTimeout=$timeoutSeconds",
        "-p",
    )

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
        val sshCmd = "ssh ${target(host, user)}"
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
