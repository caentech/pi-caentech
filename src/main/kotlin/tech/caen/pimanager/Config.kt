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

    /** Dossier local des clés SSH générées par pi-manager (paire globale de la flotte). */
    val keysDir: String = env("PI_MANAGER_KEYS_DIR", "data/keys")

    /** Clé privée globale utilisée pour joindre les Pi configurés (`.pub` à côté). */
    val identityFile: String = env("PI_MANAGER_IDENTITY_FILE", "$keysDir/pi-swarm_ed25519")

    /**
     * Chemin DISTANT du fichier UNIQUE pi-manager sur le Pi (enrôlement + état).
     * Déposé à la Configuration / au Setup, lu à chaque SSH-pull.
     */
    val remoteStatePath: String = env("PI_MANAGER_STATE_PATH", "~/.pi-manager/pi-swarm.json")

    /** Dossier DISTANT où les fichiers sont déployés (scp). */
    val remoteFilesDir: String = env("PI_MANAGER_REMOTE_FILES_DIR", "~/.pi-manager/files")

    /**
     * Dossier LOCAL de l'application à déployer sur le Pi pendant la phase de setup.
     * Son contenu est zippé, copié puis décompressé côté Pi, et `main.sh` est lancé.
     */
    val appDir: String = env("PI_MANAGER_APP_DIR", "pi-app")

    /** Dossier DISTANT où l'application est décompressée et lancée (`<dir>/main.sh`). */
    val remoteAppDir: String = env("PI_MANAGER_REMOTE_APP_DIR", "~/.pi-manager/app")

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
