package tech.caen.pimanager.ssh

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * Vérificateur de clé d'hôte TOFU (trust on first use), adossé à un fichier
 * `known_hosts` géré par l'APPLICATION (jamais celui de l'utilisateur).
 *
 * Sémantique « accept-new » :
 *  - hôte inconnu                       -> on mémorise l'empreinte et on accepte ;
 *  - hôte connu, clé identique          -> on accepte ;
 *  - hôte connu, clé DIFFÉRENTE         -> on REFUSE (protection MITM).
 *
 * Le fichier est au format OpenSSH (`hôte type-de-clé base64`), donc lisible aussi
 * par le binaire `ssh` du régime établi (cf. [SshService] qui pointe dessus via
 * `-o UserKnownHostsFile`). L'empreinte SHA256 de la dernière clé vue est exposée
 * ([lastFingerprint]) pour vérification par l'opérateur à l'enrôlement.
 */
class TofuHostKeyVerifier(private val knownHostsFile: File) : HostKeyVerifier {

    private val log = LoggerFactory.getLogger(TofuHostKeyVerifier::class.java)

    /** Empreinte SHA256 (format OpenSSH `SHA256:...`) de la dernière clé d'hôte vue. */
    @Volatile
    var lastFingerprint: String? = null
        private set

    /** `true` si la dernière vérification a échoué car la clé enregistrée a changé (MITM possible). */
    @Volatile
    var hostKeyChanged: Boolean = false
        private set

    private data class Entry(val host: String, val keyType: String, val base64: String)

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val keyType = KeyType.fromKey(key).toString()
        val base64 = encodeKey(key)
        lastFingerprint = sha256Fingerprint(key)
        val label = hostLabel(hostname, port)

        val known = readEntries().filter { it.host == label && it.keyType == keyType }
        if (known.isNotEmpty()) {
            val matches = known.any { it.base64 == base64 }
            if (!matches) {
                hostKeyChanged = true
                log.error(
                    "Clé d'hôte CHANGÉE pour {} ({}) — connexion REFUSÉE (protection MITM). " +
                        "Empreinte reçue {}. Si le changement est légitime, supprimez l'entrée dans {}.",
                    label, keyType, lastFingerprint, knownHostsFile.absolutePath,
                )
            }
            return matches
        }

        // Hôte inconnu : accept-new -> on mémorise puis on accepte.
        append(Entry(label, keyType, base64))
        log.info("Nouvelle clé d'hôte mémorisée pour {} ({}) — empreinte {}", label, keyType, lastFingerprint)
        return true
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
        val label = hostLabel(hostname, port)
        return readEntries().filter { it.host == label }.map { it.keyType }.distinct()
    }

    /** Label OpenSSH de l'hôte : `host` sur le port 22, `[host]:port` sinon. */
    private fun hostLabel(hostname: String, port: Int): String =
        if (port <= 0 || port == 22) hostname else "[$hostname]:$port"

    /** Encodage de la clé publique au format « blob SSH » (ce qui suit le type dans known_hosts). */
    private fun keyBlob(key: PublicKey): ByteArray {
        val buf = Buffer.PlainBuffer()
        KeyType.fromKey(key).putPubKeyIntoBuffer(key, buf)
        return buf.compactData
    }

    private fun encodeKey(key: PublicKey): String = Base64.getEncoder().encodeToString(keyBlob(key))

    private fun sha256Fingerprint(key: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBlob(key))
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private fun readEntries(): List<Entry> {
        if (!knownHostsFile.exists()) return emptyList()
        return knownHostsFile.readLines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size < 3) null else Entry(parts[0], parts[1], parts[2])
        }
    }

    @Synchronized
    private fun append(entry: Entry) {
        knownHostsFile.absoluteFile.parentFile?.mkdirs()
        knownHostsFile.appendText("${entry.host} ${entry.keyType} ${entry.base64}\n")
    }
}
