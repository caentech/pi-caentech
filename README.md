# pi-manager — backend de gestion de flotte Raspberry Pi

Backend de pilotage d'une flotte de Raspberry Pi (à terme : digital signage pour
la conférence **Caen.tech**). Cette première version se concentre **uniquement**
sur la gestion des Pi.

L'app tourne **localement sur un Mac**. Elle ne déploie aucun agent sur les Pi :
elle shelle vers les binaires système `ssh` / `scp` et réutilise donc directement
votre configuration `~/.ssh` (clés, alias d'hôte, `~/.ssh/config`).

## Stack

Kotlin · Ktor (Netty) · kotlinx.serialization · coroutines · Exposed + SQLite · sshj
(auth par mot de passe pour la configuration initiale uniquement).

## Lancement local

Prérequis : un JDK 17+ et un accès SSH par clé aux Pi.

```bash
./gradlew run
```

Le serveur démarre sur **http://localhost:10028**. Il sert à la fois l'**API**
(`/api/...`) et le **frontend** (interface web à la racine) :

- Interface : <http://localhost:10028/>
- Santé : `curl http://localhost:10028/api/health`

> CORS est activé (toutes origines par défaut) pour un éventuel frontend servi
> séparément.

## Frontend

Une SPA légère (HTML/CSS/JS sans build) est servie par Ktor depuis
`src/main/resources/web/` et consomme l'API. Elle reprend le design Caen.tech
(jaune `#ffdd00`, Arimo / JetBrains Mono) :

- **Vue flotte** : grille de cartes par device, compteurs par état
  (ready / en setup / offline), filtres par état et par type d'affichage,
  recherche, ajout de device.
- **Vue détail** : identité technique, connexion SSH rapide (copier / ouvrir
  un terminal), dropbox (upload + déploiement scp + suppression), journal
  d'état (logs SSH), actions device (reboot / shutdown).
- **Temps réel** : la SPA s'abonne à **SSE** (`/api/stream`) et rafraîchit
  automatiquement à chaque changement d'état détecté par le poller.

Les **contrôles de l'appli d'affichage** (musique, type d'affichage, cache,
slide, durée, redémarrage de l'app) sont **affichés en lecture seule** : ils
reflètent le fichier de statut mais ne sont pas pilotables tant que l'appli
d'affichage n'est pas déployée (cliquer affiche une explication).

## Modèle : détection d'état par SSH-pull (aucun agent sur le Pi)

Un **poller interne** interroge chaque device enregistré à intervalle régulier.
L'état est **toujours dérivé du SSH-pull**, jamais poussé par le Pi :

| Condition SSH-pull                                      | État renvoyé        |
|---------------------------------------------------------|---------------------|
| Connexion SSH refusée (`Permission denied`) — Pi en ligne, **non configuré** | `new`               |
| Connexion SSH impossible (timeout, injoignable)         | `not connected`     |
| SSH OK, pas de `status.json` **ni** de `pi-swarm.json`  | `new`               |
| SSH OK, pas de `status.json` mais `pi-swarm.json` présent | `setup in progress` |
| SSH OK, `status.json` présent, `state` = `ready`        | `ready`             |
| SSH OK, `status.json` présent, autre `state`            | `setup in progress` |

> Un Pi fraîchement flashé refuse l'accès par clé (`BatchMode=yes` → `Permission denied`) :
> il est donc **en ligne mais non configuré** et apparaît en `new`, avec un bouton
> **« Configuration »** (voir ci-dessous).

Un refresh à la demande est aussi disponible : `POST /api/devices/{id}/check`.

### Format du fichier de statut

Fichier JSON lu sur le Pi (chemin par défaut `~/.pi-manager/status.json`).
Seul `state` pilote l'état ; les autres champs sont **affichés en lecture seule**
(ils décrivent l'appli d'affichage, non encore pilotable) :

```json
{
  "state": "ready",
  "displayType": "conference",
  "appVersion": "1.4.2",
  "siteUrl": "https://caen.tech",
  "music": { "enabled": true, "track": "ambient.mp3" },
  "slide": { "current": 2, "total": 5 },
  "pageCache": { "lastClearedAt": "2026-06-01T10:00:00Z" },
  "message": null,
  "updatedAt": "2026-06-10T08:00:00Z"
}
```

`state` vaut `ready` ou `setup in progress`. Le parsing est tolérant
(champs inconnus ignorés, champs manquants `null`).

### Configuration (enrôlement d'un Pi vierge)

`POST /api/devices/{id}/configure` (corps `{ "password": "..." }`) enrôle un Pi en ligne
mais non configuré. C'est la **seule** opération qui s'authentifie par **mot de passe**
(via sshj) ; le mot de passe **n'est jamais stocké**. Elle :

1. génère au besoin une **paire de clés globale** (`ssh-keygen ed25519`, stockée dans
   `data/keys/`, réutilisée pour toute la flotte) ;
2. ajoute la **clé publique** à `~/.ssh/authorized_keys` du Pi (idempotent) ;
3. dépose **`pi-swarm.json`** (cf. ci-dessous) avec `status: "setup"`.

Ensuite, la clé suffit : `ssh`/`scp` repassent par `-i data/keys/pi-swarm_ed25519` et le
device bascule en `setup in progress`.

#### Format de `pi-swarm.json`

Fichier d'enrôlement déposé sur le Pi (chemin par défaut `~/.pi-manager/pi-swarm.json`) :

```json
{
  "deviceId": "…",
  "name": "rpi-salle-elixir",
  "host": "rpi-salle-elixir.local",
  "sshUser": "pi",
  "displayType": null,
  "status": "setup",
  "managedBy": "pi-manager",
  "configuredAt": "2026-06-10T08:00:00Z"
}
```

### Setup (minimal, hérité)

`POST /api/devices/{id}/setup` se limite à **installer le fichier de statut** (`status.json`)
sur le Pi (`mkdir -p` + `scp`). Rien d'autre. L'enrôlement passe désormais par
`/configure` ; `/setup` reste disponible pour compat.

## Configuration (variables d'environnement)

Toutes surchargeables ; les défauts conviennent à un usage local.

| Variable                            | Défaut                        | Rôle                                            |
|-------------------------------------|-------------------------------|-------------------------------------------------|
| `PI_MANAGER_PORT`                   | `10028`                       | Port HTTP                                       |
| `PI_MANAGER_DB_PATH`                | `data/pi-manager.db`          | Fichier SQLite (registre)                       |
| `PI_MANAGER_FILES_DIR`              | `data/files`                  | Stockage local des fichiers (dropbox)           |
| `PI_MANAGER_STATUS_PATH`            | `~/.pi-manager/status.json`   | Chemin **distant** du fichier de statut         |
| `PI_MANAGER_KEYS_DIR`               | `data/keys`                   | Dossier local de la paire de clés globale       |
| `PI_MANAGER_IDENTITY_FILE`          | `data/keys/pi-swarm_ed25519`  | Clé privée globale (`.pub` à côté)              |
| `PI_MANAGER_SWARM_PATH`             | `~/.pi-manager/pi-swarm.json` | Chemin **distant** du fichier d'enrôlement      |
| `PI_MANAGER_REMOTE_FILES_DIR`       | `~/.pi-manager/files`         | Dossier **distant** de déploiement des fichiers |
| `PI_MANAGER_POLL_INTERVAL_SECONDS`  | `30`                          | Intervalle du poller                            |
| `PI_MANAGER_SSH_TIMEOUT_SECONDS`    | `10`                          | Timeout des opérations SSH                      |
| `PI_MANAGER_LOG_COMMAND`            | `journalctl --no-pager -n {lines}` | Commande de lecture des logs (`{lines}` substitué) |
| `PI_MANAGER_CORS_HOSTS`             | `*`                           | Hôtes CORS autorisés (séparés par virgules)     |

Exemple :

```bash
PI_MANAGER_PORT=8080 PI_MANAGER_POLL_INTERVAL_SECONDS=15 ./gradlew run
```

## Endpoints

### Flotte / devices
| Méthode | Chemin                  | Description                                   |
|---------|-------------------------|-----------------------------------------------|
| GET     | `/api/devices`          | Liste (overview), filtres `?state=` `?q=`     |
| POST    | `/api/devices`          | Enregistrer un device (`name`, `host`, `sshUser?`) |
| GET     | `/api/devices/{id}`     | Détail (identité, état, statut, fichiers)     |
| PATCH   | `/api/devices/{id}`     | Éditer le registre                            |
| DELETE  | `/api/devices/{id}`     | Retirer un device                             |
| GET     | `/api/summary`          | Compteurs par état                            |

### Statut (SSH-pull)
| Méthode | Chemin                         | Description                       |
|---------|--------------------------------|-----------------------------------|
| POST    | `/api/devices/{id}/check`      | Forcer une vérification immédiate |

(+ poller interne périodique — voir ci-dessus, ce n'est pas un endpoint.)

### Configuration / Setup
| Méthode | Chemin                         | Description                          |
|---------|--------------------------------|--------------------------------------|
| POST    | `/api/devices/{id}/configure`  | Enrôler un Pi vierge : clé SSH + `pi-swarm.json` (corps `{ "password" }`, mot de passe non stocké) |
| POST    | `/api/devices/{id}/setup`      | Installer le fichier de statut `status.json` (scp, hérité) |

### Actions device (SSH)
| Méthode | Chemin                                  | Description |
|---------|-----------------------------------------|-------------|
| POST    | `/api/devices/{id}/actions/reboot`      | Redémarrer  |
| POST    | `/api/devices/{id}/actions/shutdown`    | Éteindre    |

> `reboot`/`shutdown` utilisent `sudo -n` : le `sudo` sans mot de passe doit être
> autorisé sur le Pi. La chute de connexion pendant l'opération est attendue et
> traitée comme un succès.

### SSH (accès rapide)
| Méthode | Chemin                          | Description                             |
|---------|---------------------------------|-----------------------------------------|
| GET     | `/api/devices/{id}/ssh`         | Commande `ssh user@ip` prête à copier   |
| POST    | `/api/devices/{id}/ssh/open`    | Ouvre un terminal SSH (Terminal.app, macOS) |

### Dropbox / fichiers
| Méthode | Chemin                                      | Description                  |
|---------|---------------------------------------------|------------------------------|
| GET     | `/api/devices/{id}/files`                   | Liste (nom, taille, date)    |
| POST    | `/api/devices/{id}/files`                   | Upload `multipart/form-data` |
| DELETE  | `/api/devices/{id}/files/{fileId}`          | Supprimer                    |
| POST    | `/api/devices/{id}/files/{fileId}/push`     | Déployer sur le Pi (scp)     |

### Logs
| Méthode | Chemin                       | Description                                            |
|---------|------------------------------|--------------------------------------------------------|
| GET     | `/api/devices/{id}/logs`     | Via SSH. Query : `?lines=` `?unit=` (journalctl) `?file=` (tail) |

### Temps réel
| Type | Chemin         | Description                                          |
|------|----------------|------------------------------------------------------|
| SSE  | `/api/stream`  | Push des changements d'état et résultats d'action    |

> Le temps réel passe **uniquement par SSE** (Server-Sent Events) : flux
> unidirectionnel serveur → client, plus simple qu'un WebSocket pour ce besoin.

### Santé
| Méthode | Chemin         | Description |
|---------|----------------|-------------|
| GET     | `/api/health`  | Liveness    |

## Contrôles différés (non implémentés)

Ces contrôles dépendent de **l'appli d'affichage (inexistante)**. Ils sont
**affichés** dans l'UI (champs en lecture via le fichier de statut + entrée dans
`deferredControls` du détail device) mais **non pilotables** : les endpoints
associés renvoient `501 Not Implemented`.

- Musique on/off — `POST /api/devices/{id}/actions/music`
- Changement de slide — `POST /api/devices/{id}/actions/slide`
- Vidage du cache de page — `POST /api/devices/{id}/actions/cache/clear`
- Type d'affichage — `POST /api/devices/{id}/actions/display-type`
- Restart de l'appli — `POST /api/devices/{id}/actions/restart-app`
- Mise à jour applicative — `POST /api/devices/{id}/actions/update-app`

## Architecture

```
src/main/kotlin/tech/caen/pimanager/
├── Application.kt          # bootstrap Ktor (plugins, DI manuel, poller)
├── Routing.kt              # définition des routes
├── Config.kt               # configuration (env vars)
├── Errors.kt               # ApiException -> codes HTTP (StatusPages)
├── Json.kt                 # instance JSON partagée + helpers temps
├── model/Models.kt         # DTOs sérialisables, états, fichier de statut
├── db/                     # Exposed + SQLite (registre des devices)
│   ├── Database.kt
│   └── DeviceRepository.kt
├── ssh/
│   ├── SshService.kt       # encapsule ssh/scp : test, lecture, exec, transfert, terminal (clé)
│   └── SshProvisioner.kt   # configuration initiale via sshj (auth password) : pose clé + pi-swarm.json
└── service/
    ├── DeviceService.kt    # orchestration (état, setup, actions, fichiers, logs)
    ├── Poller.kt           # coroutine de SSH-pull périodique
    ├── EventBus.kt         # bus temps réel (SSE)
    └── FileStore.kt        # stockage local des fichiers par device

src/main/resources/web/    # frontend statique servi par Ktor
├── index.html
├── styles.css             # design tokens Caen.tech
└── app.js                 # vues flotte/détail, fetch API + EventSource (SSE)
```
