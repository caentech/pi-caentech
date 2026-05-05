# pi-caentech

Lanceur de mode kiosque sur Raspberry Pi pour [caen.tech](https://caen.tech).

Transforme un Pi en écran mural qui affiche la page `/interstice/` — la vue
« ce qu'il se passe en ce moment » de caen.tech, déclinée par salle — avec
de la musique de fond optionnelle sur la sortie audio HDMI.

Le site lui-même n'est **pas construit ici** : `pi.sh build` se contente de
mirroir le site déjà publié sur `https://caen.tech` via `wget --mirror`. Ce
dépôt est autonome — il ne dépend pas d'un clone du dépôt source de
`caen.tech`.

---

## Préparation de la carte SD

Avant de brancher le Pi, il faut flasher une carte SD avec Raspberry Pi OS
Lite à l'aide de l'outil officiel Raspberry Pi Imager.

1. **Installer Raspberry Pi Imager** depuis
   <https://www.raspberrypi.com/software/> et le lancer avec la carte SD
   insérée.
2. **Choisir l'OS.** Sélectionner *Raspberry Pi OS (other)* → **Raspberry
   Pi OS Lite** (64-bit). L'image Lite n'a pas d'environnement de bureau,
   ce qui correspond exactement à notre besoin — `cage` + `chromium`
   fournissent la seule interface dont le Pi a besoin.
3. **Choisir le stockage** — la carte SD que vous venez d'insérer.
4. **Modifier les réglages de l'OS** quand l'invite s'affiche
   (*« Voulez-vous appliquer des paramètres de personnalisation de l'OS ? »*
   → **Modifier les réglages**) :
   - Onglet **Général** :
     - **Définir le nom d'hôte** (optionnel mais recommandé, par exemple
       `caentech-conference`).
     - **Définir le nom d'utilisateur et le mot de passe** — le nom
       d'utilisateur **doit** être `caentech` (le chemin d'auto-démarrage
       dans `~/.bash_profile` est codé en dur sur
       `/home/caentech/caen.tech/pi.sh`).
     - **Configurer le LAN sans fil** — SSID, mot de passe et pays (`FR`) - **si disponible**.
   - Onglet **Services** :
     - **Activer SSH** → *Authentification par mot de passe*( mot de passe : caentech)
5. **Écrire** l'image, attendre la fin, éjecter la carte.
6. Insérer la carte dans le Pi, brancher HDMI + alimentation et patienter
   ~30 secondes le temps qu'il rejoigne le WiFi ou se connecter en RJ45. Se connecter en SSH avec
   `caentech@<nom-d-hôte>.local` (ou par IP) et continuer avec le
   **Démarrage rapide** ci-dessous.

---

## Démarrage rapide

Sur un Raspberry Pi neuf (Raspberry Pi OS, avec accès au réseau), passer la
salle dans laquelle ce Pi sera installé en argument à l'installeur :


```bash
# Connexion en SSH au raspberry (mdp: caentech)
ssh caentech.local -l caentech 
# Setup du raspberry, donnant les informations de la salle "conference" 
curl -fsSL https://raw.githubusercontent.com/caentech/pi-caentech/main/install.sh | bash -s -- conference
exit
# Téléchargement manuel de la musique
open https://drive.google.com/file/d/1e7qNFh4UBeUcFaJxY5tYTEF84e4YjeBr/view?usp=sharing
# Téléchagement de la musique sur le raspberry
scp ~/Downloads/background.mp3 caentech@caentech.local:~/caen.tech/music/
```

Remplacer `conference` par `amphitheatre` (alias : `auditorium`) ou `tv`
selon l'emplacement.

L'installeur va :

1. Installer `git` s'il manque.
2. Cloner ce dépôt (`pi-caentech`) dans `~/caen.tech`.
3. Lancer `pi.sh setup` (installe `cage`, `mpv`, `chromium`, `wget`,
   définit le fuseau horaire `Europe/Paris`, force la sortie audio sur
   HDMI).
4. Lancer `pi.sh build` (miroir du site publié vers `~/caen.tech/dist`).
5. Lancer `pi.sh enable-autostart <salle>` (configure l'auto-login sur
   tty1 et ajoute le lanceur du kiosque à `~/.bash_profile`).

Puis redémarrer pour lancer le kiosque :

```bash
sudo reboot
```

Après le redémarrage, le Pi démarre directement dans le kiosque pour la
salle choisie — sans clavier, sans SSH, sans `pi.sh run` manuel. Le
redémarrage est aussi nécessaire pour que les changements de fuseau et
d'audio appliqués par `setup` prennent effet.

---

## Commandes

```bash
./pi.sh setup                       # installe cage, mpv, chromium, wget ; règle TZ ; force audio HDMI
./pi.sh build                       # miroir caen.tech vers ./dist (et /interstice/ explicitement)
./pi.sh run <salle>                 # démarre le kiosque (voir ci-dessous)
./pi.sh update                      # git pull + nouveau miroir + redémarre le kiosque actif
./pi.sh enable-autostart <salle>    # auto-login sur tty1 + lance le kiosque au boot
./pi.sh disable-autostart           # supprime le bloc d'auto-démarrage, restaure l'invite de login
./pi.sh help                        # affiche l'aide
```

### Salles

| Argument                                | Résolu en      | Utilisation                  |
|-----------------------------------------|----------------|------------------------------|
| `conference`                            | `conference`   | Salle de conférence          |
| `amphitheatre` / `amphi` / `auditorium` | `auditorium`   | Salle secondaire (amphi)     |
| `tv`                                    | `tv`           | TV du hall / vue équilibrée  |

Le nom de la salle est passé à la page via `?salle=<salle>`.

### Variables d'environnement

| Variable             | Défaut                | Rôle                             |
|----------------------|-----------------------|----------------------------------|
| `CAENTECH_HTTP_PORT` | `4321`                | Port du serveur HTTP local       |
| `CAENTECH_SITE_URL`  | `https://caen.tech`   | Site à miroirer                  |

---

## Auto-démarrage au boot

Pour un écran mural sans surveillance, le kiosque doit se lancer tout seul
quand le Pi est mis sous tension — sans clavier, sans SSH, sans `pi.sh run`
manuel.

Le démarrage rapide via `install.sh` active l'auto-démarrage
automatiquement — cette section sert à changer la salle plus tard ou à
réactiver l'auto-démarrage après un `disable-autostart`.

### Activation

```bash
~/caen.tech/pi.sh enable-autostart conference   # ou amphitheatre / tv
sudo reboot
```

Après le redémarrage, le Pi démarre directement dans le kiosque de la
salle choisie. Il jouera également les `.mp3` présents dans `music/`.

## Musique de fond

Déposez des fichiers `.mp3` dans `music/`. Ils seront joués en boucle
mélangée sur la sortie audio HDMI tant qu'un kiosque tourne. Si le
répertoire est vide, le kiosque démarre en silence — pas d'erreur.

```bash
open https://drive.google.com/file/d/1e7qNFh4UBeUcFaJxY5tYTEF84e4YjeBr/view?usp=sharing
scp ~/Downloads/background.mp3 caentech@caentech.local:~/caen.tech/music/
```

---

### Comment ça marche concrètement

`pi.sh enable-autostart <salle>` fait deux choses simples — pas de service
systemd, pas de display manager :

1. **Auto-login console sur tty1.** Lance `raspi-config nonint
   do_boot_behaviour B2`, qui demande au Pi de connecter automatiquement
   l'utilisateur par défaut sur la première console texte (tty1) au
   démarrage.
2. **Un bloc gardé dans `~/.bash_profile`.** Ajoute ceci entre deux
   marqueurs :

   ```bash
   # >>> caentech-kiosk auto-start >>>
   if [ "$(tty)" = "/dev/tty1" ] && [ -z "${WAYLAND_DISPLAY:-}" ]; then
     exec '/home/caentech/caen.tech/pi.sh' run 'conference'
   fi
   # <<< caentech-kiosk auto-start <<<
   ```

   Comme l'auto-login se fait sur tty1 et exécute le shell de connexion
   de l'utilisateur, `~/.bash_profile` est sourcé, le garde correspond et
   `exec` remplace le shell par le kiosque. `cage` prend ensuite le
   contrôle du framebuffer.

Le garde est important : les sessions SSH, les autres ttys ou un shell
lancé depuis une session Wayland existante échoueront tous au test
`tty` / `WAYLAND_DISPLAY` et tomberont sur une invite normale. Vous pouvez
donc toujours vous connecter en SSH au Pi pour l'administrer sans buter
sur le kiosque.

### Changer de salle

Il suffit de relancer `enable-autostart` avec la nouvelle salle — le bloc
est encadré par des marqueurs et est remplacé en place :

```bash
~/caen.tech/pi.sh enable-autostart tv
```

Le changement s'applique au prochain redémarrage. Si le kiosque tourne
déjà et que vous voulez basculer immédiatement :

```bash
kill -TERM "$(cat "${XDG_RUNTIME_DIR:-/tmp}/caentech-kiosk/run.pid")"
sudo reboot   # ou simplement attendre le prochain redémarrage
```

### Désactivation

```bash
~/caen.tech/pi.sh disable-autostart
```

Cette commande retire le bloc de `~/.bash_profile` et lance `raspi-config
nonint do_boot_behaviour B1` pour rétablir l'invite de login normale sur
tty1. Elle **n'arrête pas** un kiosque déjà en cours — voir la FAQ
ci-dessous.

### Mettre à jour pendant que l'auto-démarrage est actif

`pi.sh update` est conscient de l'auto-démarrage : il met à jour les
scripts, refait le miroir du site, puis envoie `SIGTERM` au kiosque actif
via le fichier PID `$XDG_RUNTIME_DIR/caentech-kiosk/run.pid`. Quand le
kiosque se termine, le `exec` dans `~/.bash_profile` est aussi terminé —
vous vous retrouvez sur un shell connecté sur tty1. **Le kiosque ne
redémarre pas tout seul** : l'auto-démarrage ne se déclenche que lors
d'une nouvelle connexion sur tty1. Pour le relancer, il faut soit
redémarrer, soit se déconnecter (`exit`) afin que l'auto-login se
redéclenche.

Si vous voulez un cycle « tuer et redémarrer maintenant » depuis SSH :

```bash
~/caen.tech/pi.sh update          # miroir + tue le kiosque
sudo systemctl restart getty@tty1   # force une nouvelle connexion tty1 → relance l'auto-démarrage
```

### Débogage

```bash
# L'auto-login s'est-il déclenché ?
who                                          # doit afficher l'utilisateur sur tty1

# Le garde de .bash_profile s'est-il déclenché ?
journalctl --user -b                         # la sortie de cage / chromium atterrit ici

# Le kiosque tourne-t-il ?
ls "${XDG_RUNTIME_DIR:-/tmp}/caentech-kiosk/run.pid"
ps -ef | grep -E 'pi\.sh run|cage|chromium'
```

Si le Pi démarre sur une invite de login au lieu du kiosque, les causes
les plus fréquentes sont : `raspi-config` n'a pas basculé sur `B2`
(relancer la commande avec `sudo`), ou bien le bloc dans `~/.bash_profile`
est placé après un `exit` / `return` précoce d'un hook shell, et n'est
donc jamais atteint.

---

## Mettre à jour les pages affichées

Le Pi ne construit jamais le site lui-même — il fait simplement un miroir
de ce qui est publié sur `https://caen.tech`. Pour récupérer la dernière
version des pages :

```bash
~/caen.tech/pi.sh update
```

Cette commande fait trois choses :

1. `git pull --ff-only` sur ce dépôt `pi-caentech` (récupère les nouveaux
   scripts / musiques s'il y en a).
2. Relance `pi.sh build` pour refaire un miroir du site en ligne (seuls
   les fichiers modifiés sont téléchargés grâce au timestamp de
   `wget --mirror`).
3. Si un kiosque tourne actuellement sur ce Pi, lui envoie `SIGTERM` pour
   que le superviseur (systemd, ou ce que vous avez mis en place) le
   relance avec le miroir frais.

Si vous voulez seulement rafraîchir les pages **sans** mettre à jour les
scripts :

```bash
~/caen.tech/pi.sh build
```

---

## FAQ

### Comment quitter le mode kiosque ?

Le kiosque tourne dans `cage` (un compositeur Wayland minimal) avec
Chromium en plein écran. Pour en sortir :

- **Avec un clavier branché sur le Pi :** appuyer sur
  `Ctrl + Alt + Backspace`. `cage` se termine, ce qui tue Chromium, et le
  trap dans `pi.sh` nettoie le serveur HTTP et le processus de musique.
- **En SSH :**
  ```bash
  pkill -TERM -f 'pi.sh run'
  ```
  Cela envoie `SIGTERM` au processus `pi.sh run` ; le trap nettoie tout.
  Si ça ne suffit pas, `pkill cage` démontera la session Wayland.

### Comment démarrer le kiosque ?

Lancer manuellement :

```bash
~/caen.tech/pi.sh run conference
```

Pour un lancement automatique au boot (sans clavier, sans SSH), voir la
section **Auto-démarrage au boot** plus haut.

### Comment arrêter le kiosque ?

Si vous l'avez lancé manuellement dans un terminal, `Ctrl + C` suffit —
le trap dans `pi.sh` tue le serveur HTTP et le processus de musique avant
de quitter.

S'il a été lancé par `enable-autostart`, tuer le processus depuis une
session SSH :

```bash
kill -TERM "$(cat "${XDG_RUNTIME_DIR:-/tmp}/caentech-kiosk/run.pid")"
```

Il ne redémarrera pas tout seul avant le prochain redémarrage
(l'auto-démarrage ne se déclenche que lors d'une connexion tty1). Pour
empêcher également son retour au prochain boot, lancer
`pi.sh disable-autostart`.

### Comment récupérer la dernière version du service ?

```bash
~/caen.tech/pi.sh update
```

Cela récupère les derniers scripts **et** refait le miroir du site. Si
vous voulez seulement les derniers scripts (sans rafraîchir les pages) :

```bash
git -C ~/caen.tech pull --ff-only
```

Si vous voulez seulement les dernières pages (sans mettre à jour les
scripts) :

```bash
~/caen.tech/pi.sh build
```

### Comment tester sans TV ?

Vous pouvez exécuter toutes les étapes sauf le kiosque proprement dit sur
n'importe quelle machine Linux :

```bash
./pi.sh build                   # miroir du site
( cd dist && python3 -m http.server 4321 --bind 127.0.0.1 ) &
xdg-open 'http://localhost:4321/interstice/?salle=conference'
```

Pour tester le flux complet du kiosque sur le Pi sans installation
permanente, lancer simplement `./pi.sh run <salle>` depuis une session
SSH — `cage` prendra le contrôle de la sortie HDMI. `Ctrl + C` dans la
session SSH termine le test proprement.

Pour vérifier qu'une salle donnée s'affiche correctement, changer le
paramètre de requête `?salle=` :

- `?salle=conference`
- `?salle=auditorium`
- `?salle=tv`

### Le kiosque affiche une page blanche / « connection refused »

Le serveur HTTP local n'a pas démarré à temps, ou le miroir est manquant.
Vérifier :

```bash
ls ~/caen.tech/dist/interstice/index.html   # doit exister
curl -I http://localhost:4321/interstice/     # doit renvoyer 200
```

Relancer `./pi.sh build` si `index.html` est absent.

### Il n'y a pas de son

`pi.sh setup` force la sortie audio sur HDMI et active `dtparam=audio=on`,
mais un redémarrage est nécessaire pour que ça prenne effet. Vérifier
aussi :

- Que `music/` contient bien des fichiers `.mp3` (un répertoire vide est
  silencieusement ignoré).
- Que la TV / l'écran n'est pas en sourdine et est sur la bonne entrée
  HDMI.
- Que `amixer` indique un volume correct : `amixer sget 'Master'`.

### Comment changer la salle affichée par un Pi ?

Si vous le lancez manuellement, faire `Ctrl + C` et le relancer avec un
autre argument :

```bash
~/caen.tech/pi.sh run tv
```

Si l'auto-démarrage est actif, relancer `enable-autostart` avec la
nouvelle salle et redémarrer :

```bash
~/caen.tech/pi.sh enable-autostart tv
sudo reboot
```

Le bloc encadré dans `~/.bash_profile` est remplacé en place — pas
d'édition manuelle nécessaire.

---

## Structure du dépôt

```
pi-caentech/
├── install.sh        # bootstrap one-shot (clone ce dépôt, lance setup + build)
├── pi.sh             # le lanceur du kiosque (setup / build / run / update)
├── music/            # déposer ici les *.mp3 pour la musique de fond
├── dist/             # généré par `pi.sh build` (site mirroré, gitignoré)
└── README.md         # ce fichier
```

`pi.sh build` écrit le site mirroré dans `./dist` (à côté du script).
