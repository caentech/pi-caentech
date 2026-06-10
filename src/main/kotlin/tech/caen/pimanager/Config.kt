package tech.caen.pimanager

/**
 * Configuration centralisée. Chaque valeur est surchargeable par variable
 * d'environnement (ou propriété système de même nom), avec un défaut sensé
 * pour un usage local sur Mac.
 */
object Config {
    private fun env(key: String, default: String): String =
        System.getenv(key) ?: System.getProperty(key) ?: default

    /** Port HTTP du serveur. */
    val port: Int = env("PI_MANAGER_PORT", "10028").toInt()

    /** Fichier SQLite (registre des devices). Le dossier parent est créé au besoin. */
    val dbPath: String = env("PI_MANAGER_DB_PATH", "data/pi-manager.db")

    /** Dossier local de stockage des fichiers "dropbox" par device. */
    val localFilesDir: String = env("PI_MANAGER_FILES_DIR", "data/files")

    /** Chemin DISTANT du fichier de statut sur le Pi (lu via SSH). */
    val remoteStatusPath: String = env("PI_MANAGER_STATUS_PATH", "~/.pi-manager/status.json")

    /** Dossier local des clés SSH générées par pi-manager (paire globale de la flotte). */
    val keysDir: String = env("PI_MANAGER_KEYS_DIR", "data/keys")

    /** Clé privée globale utilisée pour joindre les Pi configurés (`.pub` à côté). */
    val identityFile: String = env("PI_MANAGER_IDENTITY_FILE", "$keysDir/pi-swarm_ed25519")

    /** Chemin DISTANT du fichier d'enrôlement `pi-swarm.json` (déposé à la configuration). */
    val remoteSwarmPath: String = env("PI_MANAGER_SWARM_PATH", "~/.pi-manager/pi-swarm.json")

    /** Dossier DISTANT où les fichiers sont déployés (scp). */
    val remoteFilesDir: String = env("PI_MANAGER_REMOTE_FILES_DIR", "~/.pi-manager/files")

    /** Intervalle du poller interne (secondes). */
    val pollIntervalSeconds: Long = env("PI_MANAGER_POLL_INTERVAL_SECONDS", "30").toLong()

    /** Timeout des opérations SSH (secondes). */
    val sshTimeoutSeconds: Long = env("PI_MANAGER_SSH_TIMEOUT_SECONDS", "10").toLong()

    /**
     * Commande de lecture des logs sur le Pi. `{lines}` est remplacé par le
     * nombre de lignes demandé. Surchargeable par device via le query param.
     */
    val logCommand: String = env("PI_MANAGER_LOG_COMMAND", "journalctl --no-pager -n {lines}")

    /** Hôtes CORS autorisés, séparés par des virgules. `*` = tous (dev local). */
    val corsHosts: String = env("PI_MANAGER_CORS_HOSTS", "*")
}
