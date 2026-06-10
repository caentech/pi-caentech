package tech.caen.pimanager.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Provisioning initial d'un Pi vierge : c'est la SEULE opération qui s'authentifie
 * par mot de passe (via sshj, pas le binaire `ssh`). Elle pose la clé publique
 * globale dans `~/.ssh/authorized_keys` et dépose `pi-swarm.json`. Ensuite, tout
 * repasse par la clé (cf. [SshService]).
 *
 * Le mot de passe n'est ni journalisé ni conservé : il ne sert qu'à ouvrir la session.
 */
class SshProvisioner(
    private val identityFile: String,
    private val timeoutSeconds: Long,
) {
    private val log = LoggerFactory.getLogger(SshProvisioner::class.java)

    /**
     * Garantit l'existence de la paire de clés globale (génère un ed25519 sans
     * passphrase si absente) et renvoie le contenu de la clé publique.
     */
    fun ensurePublicKey(): String {
        val priv = File(identityFile)
        val pub = File("$identityFile.pub")
        if (!priv.exists() || !pub.exists()) {
            priv.absoluteFile.parentFile?.mkdirs()
            // On régénère proprement pour éviter une paire dépareillée.
            priv.delete(); pub.delete()
            val res = runProcess(
                listOf("ssh-keygen", "-t", "ed25519", "-N", "", "-C", "pi-swarm", "-f", priv.absolutePath),
                timeoutSeconds + 5,
            )
            if (!res.success || !pub.exists()) {
                throw IllegalStateException(
                    "Échec de génération de la clé SSH (ssh-keygen): ${res.stderr.trim().ifBlank { res.stdout.trim() }}"
                )
            }
        }
        return pub.readText().trim()
    }

    /**
     * Se connecte par mot de passe et, en une seule session shell : pose la clé
     * publique (idempotent) puis écrit `pi-swarm.json` (transféré en base64 pour
     * éviter tout souci de quoting). `swarmPath` peut contenir `~` (expansé par le
     * shell distant).
     */
    suspend fun configure(
        host: String,
        user: String,
        password: String,
        publicKey: String,
        swarmJson: String,
        swarmPath: String,
    ): ExecResult = withContext(Dispatchers.IO) {
        val client = SSHClient()
        client.addHostKeyVerifier(PromiscuousVerifier())
        client.connectTimeout = (timeoutSeconds * 1000).toInt()
        client.timeout = ((timeoutSeconds + 30) * 1000).toInt()
        try {
            client.connect(host)
            client.authPassword(user, password)

            val b64 = Base64.getEncoder().encodeToString(swarmJson.toByteArray(Charsets.UTF_8))
            val swarmDir = swarmPath.substringBeforeLast('/', "")
            // Clé publique entre apostrophes (jamais d'apostrophe dans une clé OpenSSH) ;
            // chemins distants NON quotés pour laisser le shell expanser `~`.
            val script = buildString {
                append("set -e; ")
                append("mkdir -p ~/.ssh && touch ~/.ssh/authorized_keys && chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys; ")
                append("grep -qxF '$publicKey' ~/.ssh/authorized_keys || echo '$publicKey' >> ~/.ssh/authorized_keys; ")
                if (swarmDir.isNotBlank()) append("mkdir -p $swarmDir; ")
                append("echo '$b64' | base64 -d > $swarmPath")
            }

            val session = client.startSession()
            try {
                val cmd = session.exec(script)
                val stdout = IOUtils.readFully(cmd.inputStream).toString()
                val stderr = IOUtils.readFully(cmd.errorStream).toString()
                cmd.join(timeoutSeconds + 30, TimeUnit.SECONDS)
                ExecResult(cmd.exitStatus ?: -1, stdout, stderr)
            } finally {
                runCatching { session.close() }
            }
        } catch (e: Exception) {
            log.warn("Configuration SSH de {} échouée: {}", host, e.message)
            ExecResult(-1, "", e.message ?: "Connexion/authentification SSH échouée")
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private fun runProcess(command: List<String>, timeout: Long): ExecResult {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(false).start()
        } catch (e: Exception) {
            return ExecResult(-1, "", "Échec du lancement de '${command.firstOrNull()}': ${e.message}")
        }
        process.outputStream.close()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val finished = process.waitFor(timeout, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return ExecResult(-1, stdout, (stderr + "\nCommande expirée après ${timeout}s").trim(), timedOut = true)
        }
        return ExecResult(process.exitValue(), stdout, stderr)
    }
}
