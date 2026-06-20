package tech.caen.pimanager.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Résultat d'un enrôlement : le résultat d'exécution de la passe de bootstrap +
 * l'empreinte SHA256 de la clé d'hôte (TOFU) vue pendant la connexion. [hostKeyChanged]
 * signale un refus pour cause de clé d'hôte modifiée (MITM possible).
 */
data class ProvisionResult(
    val exec: ExecResult,
    val hostKeyFingerprint: String?,
    val hostKeyChanged: Boolean = false,
)

/**
 * Provisioning / enrôlement initial d'un Pi vierge : c'est la SEULE opération qui
 * s'authentifie par mot de passe (via sshj, pas le binaire `ssh`). En une passe de
 * bootstrap idempotente, elle :
 *  1. pose la clé publique globale dans `~/.ssh/authorized_keys` (sans dupliquer) ;
 *  2. installe un drop-in sudoers NOPASSWD (`/opt/pi-manager/setup.sh`, `update.sh`,
 *     systemctl, reboot, shutdown) — VALIDÉ par `visudo` AVANT d'être mis en place — et crée
 *     `/opt/pi-manager` (donné au compte de déploiement) pour que le Pi soit ensuite pilotable
 *     sans mot de passe sudo et que la phase de setup y dépose les scripts ;
 *  3. dépose le fichier unique `pi-swarm.json` (marqueur d'enrôlement).
 *
 * La clé d'hôte du Pi est vérifiée en mode TOFU ([TofuHostKeyVerifier]) : mémorisée
 * à la 1re vue dans le `known_hosts` de l'app, refusée si elle change ensuite.
 *
 * Le mot de passe n'est NI journalisé NI conservé. Il ne sert qu'à (a) ouvrir la
 * session SSH et (b) l'unique écriture privilégiée, pipée à `sudo -S`. Ensuite, tout
 * repasse par la clé (cf. [SshService]).
 */
class SshProvisioner(
    private val identityFile: String,
    private val knownHostsFile: String,
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
     * Se connecte par mot de passe (TOFU sur la clé d'hôte) et exécute la passe de
     * bootstrap idempotente : clé publique -> sudoers NOPASSWD (validé visudo) ->
     * `pi-swarm.json`. `swarmPath` peut contenir `~` (expansé par le shell distant).
     *
     * En cas d'échec d'une étape, on s'arrête et on renvoie le résultat de l'étape
     * fautive (stderr préfixé du contexte) : pas d'état à moitié enrôlé — `pi-swarm.json`
     * (le marqueur) n'est écrit qu'en toute dernière étape, une fois clé + sudoers en place.
     */
    suspend fun configure(
        host: String,
        user: String,
        password: String,
        publicKey: String,
        swarmJson: String,
        swarmPath: String,
    ): ProvisionResult = withContext(Dispatchers.IO) {
        val knownHosts = File(knownHostsFile)
        knownHosts.absoluteFile.parentFile?.mkdirs()
        val verifier = TofuHostKeyVerifier(knownHosts)

        val client = SSHClient()
        client.addHostKeyVerifier(verifier)
        client.connectTimeout = (timeoutSeconds * 1000).toInt()
        client.timeout = ((timeoutSeconds + 30) * 1000).toInt()
        try {
            client.connect(host)
            client.authPassword(user, password)
            log.info(
                "Enrôlement de {} : empreinte clé d'hôte {} (mémorisée dans {})",
                host, verifier.lastFingerprint, knownHosts.absolutePath,
            )

            val pubB64 = b64(publicKey)
            val sudoersB64 = b64(sudoersContent(user))
            val swarmB64 = b64(swarmJson)
            val swarmDir = swarmPath.substringBeforeLast('/', "")

            // a) Clé publique (idempotent, sans duplication) + écriture du sudoers candidat en /tmp.
            val keysScript = buildString {
                append("set -e; umask 077; ")
                append("mkdir -p ~/.ssh; touch ~/.ssh/authorized_keys; ")
                append("chmod 700 ~/.ssh; chmod 600 ~/.ssh/authorized_keys; ")
                append("KEY=\$(printf '%s' '$pubB64' | base64 -d); ")
                append("grep -qxF \"\$KEY\" ~/.ssh/authorized_keys || printf '%s\\n' \"\$KEY\" >> ~/.ssh/authorized_keys; ")
                append("printf '%s' '$sudoersB64' | base64 -d > $SUDOERS_TMP; chmod 600 $SUDOERS_TMP")
            }
            val keys = exec(client, keysScript)
            if (!keys.success) {
                return@withContext fail(keys, "Échec de la pose de la clé publique / préparation du sudoers", verifier)
            }

            // b) Validation visudo PUIS installation privilégiée (unique usage de `sudo -S`).
            //    Tout est dans un seul `sudo sh -c` : on n'installe le drop-in que si visudo valide.
            //    `/opt/pi-manager` est créé puis donné au compte de déploiement pour que la phase
            //    de setup (par clé, sans mot de passe) puisse y déposer setup.sh / update.sh.
            val sudoersScript = "sudo -S -p '' sh -c '" +
                "/usr/sbin/visudo -cf $SUDOERS_TMP && " +
                "install -o root -g root -m 0440 $SUDOERS_TMP $SUDOERS_DROPIN && " +
                "install -d -o $user -m 0755 $OPT_DIR && " +
                "rm -f $SUDOERS_TMP'"
            val sudoers = exec(client, sudoersScript, sudoPassword = password)
            if (!sudoers.success) {
                return@withContext fail(
                    sudoers,
                    "Échec de l'installation du sudoers NOPASSWD (mot de passe sudo incorrect ou validation visudo échouée)",
                    verifier,
                )
            }

            // c) Marqueur d'enrôlement : `pi-swarm.json` (écrit en dernier, une fois clé + sudoers OK).
            val swarmScript = buildString {
                append("set -e; ")
                if (swarmDir.isNotBlank()) append("mkdir -p $swarmDir; ")
                append("printf '%s' '$swarmB64' | base64 -d > $swarmPath")
            }
            val swarm = exec(client, swarmScript)
            if (!swarm.success) {
                return@withContext fail(swarm, "Clé et sudoers posés mais écriture de pi-swarm.json échouée", verifier)
            }

            ProvisionResult(swarm, verifier.lastFingerprint)
        } catch (e: Exception) {
            val changed = verifier.hostKeyChanged
            val msg = if (changed) {
                "Clé d'hôte du Pi modifiée depuis le 1er enrôlement : connexion refusée (protection MITM). " +
                    "Vérifiez le Pi ; si le changement est légitime, supprimez son entrée dans ${knownHosts.absolutePath}."
            } else {
                e.message ?: "Connexion/authentification SSH échouée"
            }
            log.warn("Configuration SSH de {} échouée: {}", host, msg)
            ProvisionResult(ExecResult(-1, "", msg), verifier.lastFingerprint, changed)
        } finally {
            runCatching { client.disconnect() }
        }
    }

    /** Exécute un script dans une session ; pour `sudo -S`, pipe le mot de passe sur stdin puis EOF. */
    private fun exec(client: SSHClient, script: String, sudoPassword: String? = null): ExecResult {
        val session = client.startSession()
        return try {
            val cmd = session.exec(script)
            if (sudoPassword != null) {
                cmd.outputStream.use {
                    it.write((sudoPassword + "\n").toByteArray(Charsets.UTF_8))
                    it.flush()
                }
            }
            val stdout = IOUtils.readFully(cmd.inputStream).toString()
            val stderr = IOUtils.readFully(cmd.errorStream).toString()
            cmd.join(timeoutSeconds + 30, TimeUnit.SECONDS)
            ExecResult(cmd.exitStatus ?: -1, stdout, stderr)
        } finally {
            runCatching { session.close() }
        }
    }

    /** Drop-in sudoers NOPASSWD pour le user du Pi (commandes pilotées par l'app, chemins absolus). */
    private fun sudoersContent(user: String): String =
        "$user ALL=(ALL) NOPASSWD: /opt/pi-manager/setup.sh, /opt/pi-manager/update.sh, " +
            "/usr/bin/systemctl, /sbin/reboot, /sbin/shutdown\n"

    private fun b64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun fail(exec: ExecResult, context: String, verifier: TofuHostKeyVerifier): ProvisionResult {
        val detail = listOf(context, exec.stderr.trim()).filter { it.isNotBlank() }.joinToString(" — ")
        return ProvisionResult(exec.copy(stderr = detail), verifier.lastFingerprint)
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

    companion object {
        /** Drop-in sudoers final (root:root, 440). */
        private const val SUDOERS_DROPIN = "/etc/sudoers.d/pi-manager"

        /** Fichier sudoers candidat (chemin absolu, lisible par root pour la validation/installation). */
        private const val SUDOERS_TMP = "/tmp/pi-manager-sudoers"

        /** Répertoire applicatif (`setup.sh` / `update.sh`), créé root puis donné au compte de déploiement. */
        private const val OPT_DIR = "/opt/pi-manager"
    }
}
