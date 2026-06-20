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
     * Fichier `known_hosts` géré par l'app (TOFU des clés d'hôte des Pi) — jamais le
     * `~/.ssh/known_hosts` de l'utilisateur. Alimenté à l'enrôlement, réutilisé par
     * `ssh`/`scp` en régime établi.
     */
    val knownHostsFile: String = env("PI_MANAGER_KNOWN_HOSTS_FILE", "$keysDir/known_hosts")

    /**
     * Chemin DISTANT du fichier UNIQUE pi-manager sur le Pi (enrôlement + état).
     * Déposé à la Configuration / au Setup, lu à chaque SSH-pull.
     */
    val remoteStatePath: String = env("PI_MANAGER_STATE_PATH", "~/.pi-manager/pi-swarm.json")

    /** Dossier DISTANT où les fichiers sont déployés (scp). */
    val remoteFilesDir: String = env("PI_MANAGER_REMOTE_FILES_DIR", "~/.pi-manager/files")

    /**
     * Dossier LOCAL de l'application à déployer sur le Pi pendant la phase de setup.
     * Son contenu est zippé, copié puis décompressé côté Pi, et `setup.sh` est lancé.
     */
    val appDir: String = env("PI_MANAGER_APP_DIR", "pi-app")

    /**
     * Dossier DISTANT où l'application est décompressée et lancée (`<dir>/setup.sh`).
     * `/opt/pi-manager` : les scripts atterrissent en `/opt/pi-manager/setup.sh` et
     * `/opt/pi-manager/update.sh`, exactement les chemins autorisés par le sudoers NOPASSWD.
     * Le dossier est créé à l'enrôlement et donné au compte de déploiement (écriture par clé).
     */
    val remoteAppDir: String = env("PI_MANAGER_REMOTE_APP_DIR", "/opt/pi-manager")

    /** Intervalle du poller interne (secondes). */
    val pollIntervalSeconds: Long = env("PI_MANAGER_POLL_INTERVAL_SECONDS", "30").toLong()

    /** Timeout des opérations SSH (secondes). */
    val sshTimeoutSeconds: Long = env("PI_MANAGER_SSH_TIMEOUT_SECONDS", "10").toLong()

    /**
     * Rétention de la série temporelle des ressources (mémoire + CPU), en heures.
     * Au-delà, les vieux échantillons sont purgés. À ~30 s d'intervalle, 24 h ≈ 2880
     * échantillons/device — largement suffisant pour visualiser une tendance.
     */
    val metricsRetentionHours: Long = env("PI_MANAGER_METRICS_RETENTION_HOURS", "24").toLong()

    /**
     * Timeout (secondes) du déploiement de setup : la commande lance `setup.sh`, qui peut
     * installer LÖVE via apt au premier passage — bien plus long que le timeout SSH standard.
     */
    val sshSetupTimeoutSeconds: Long = env("PI_MANAGER_SSH_SETUP_TIMEOUT_SECONDS", "300").toLong()

    /**
     * Commande de lecture des logs sur le Pi. `{lines}` est remplacé par le
     * nombre de lignes demandé. Surchargeable par device via le query param.
     */
    val logCommand: String = env("PI_MANAGER_LOG_COMMAND", "journalctl --no-pager -n {lines}")

    /** Hôtes CORS autorisés, séparés par des virgules. `*` = tous (dev local). */
    val corsHosts: String = env("PI_MANAGER_CORS_HOSTS", "*")
}
