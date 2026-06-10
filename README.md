# pi-manager — backend de gestion de flotte Raspberry Pi

Backend de pilotage d'une flotte de Raspberry Pi (à terme : digital signage pour
la conférence **Caen.tech**). Cette première version se concentre **uniquement**
sur la gestion des Pi.

L'app tourne **localement sur un Mac**. Elle ne déploie aucun agent sur les Pi :
elle shelle vers les binaires système `ssh` / `scp` et réutilise donc directement
votre configuration `~/.ssh` (clés, alias d'hôte, `~/.ssh/config`).

## Stack

Kotlin · Ktor (Netty) · kotlinx.serialization · coroutines · Exposed + SQLite.

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

| Condition SSH-pull                          | État renvoyé        |
|---------------------------------------------|---------------------|
| Connexion SSH impossible                    | `not connected`     |
| SSH OK, **pas** de fichier de statut        | `new`               |
| SSH OK, fichier présent, `state` = `ready`  | `ready`             |
| SSH OK, fichier présent, autre `state`      | `setup in progress` |

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

### Setup (minimal)

`POST /api/devices/{id}/setup` se limite à **installer le fichier de statut** sur
le Pi (`mkdir -p` + `scp`). Rien d'autre. Les polls suivants verront l'état évoluer.

## Configuration (variables d'environnement)

Toutes surchargeables ; les défauts conviennent à un usage local.

| Variable                            | Défaut                        | Rôle                                            |
|-------------------------------------|-------------------------------|-------------------------------------------------|
| `PI_MANAGER_PORT`                   | `10028`                       | Port HTTP                                       |
| `PI_MANAGER_DB_PATH`                | `data/pi-manager.db`          | Fichier SQLite (registre)                       |
| `PI_MANAGER_FILES_DIR`              | `data/files`                  | Stockage local des fichiers (dropbox)           |
| `PI_MANAGER_STATUS_PATH`            | `~/.pi-manager/status.json`   | Chemin **distant** du fichier de statut         |
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

### Setup
| Méthode | Chemin                         | Description                          |
|---------|--------------------------------|--------------------------------------|
| POST    | `/api/devices/{id}/setup`      | Installer le fichier de statut (scp) |

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
├── ssh/SshService.kt       # encapsule ssh/scp : test, lecture, exec, transfert, terminal
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
