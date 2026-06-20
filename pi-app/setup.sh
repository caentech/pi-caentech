#!/usr/bin/env bash
#
# Script de SETUP de l'application déployée sur le Pi (Raspberry Pi OS Lite).
# Lancé par pi-manager à la fin de la phase de setup (après décompression du zip
# dans /opt/pi-manager). Met en place le démarrage automatique au boot de
# l'application d'affichage LÖVE (signage Caen.tech) :
#
#   - service systemd `caentech-signage` lançant `love /opt/pi-manager/love`
#     directement sur le framebuffer KMS/DRM (tty1), sans environnement de bureau ;
#   - écran plein, relance auto en cas de crash, blanking console désactivé ;
#   - démarrage différé : le service attend qu'un écran HDMI soit détecté avant
#     de lancer LÖVE (cf. run-signage.sh). On peut donc provisionner un Pi sans
#     écran et brancher / allumer l'HDMI plus tard : l'affichage démarre tout seul,
#     sans reboot ni crash-loop tant qu'aucun écran n'est présent.
#
# L'app LÖVE affiche la page https://caen.tech/interstice rendue en PNG par un
# navigateur headless (chromium) tournant en tâche de fond ; ce script installe
# donc aussi chromium si nécessaire.
#
# Le script est lancé sans privilège par pi-manager (`bash /opt/pi-manager/setup.sh`)
# puis s'auto-élève via le chemin autorisé NOPASSWD du sudoers (cf. /etc/sudoers.d/pi-manager).
# Idempotent : re-jouable à chaque (re)déploiement, il recharge le service.
#
set -euo pipefail

APP_DIR="/opt/pi-manager"
LOVE_DIR="$APP_DIR/love"
RUNNER="$APP_DIR/run-signage.sh"
SERVICE="caentech-signage"
UNIT="/etc/systemd/system/${SERVICE}.service"

log() { echo "[setup] $*"; }

# --- Phase non privilégiée : auto-élévation via le chemin sudoers NOPASSWD. ---
if [ "$(id -u)" -ne 0 ]; then
    log "élévation des privilèges (sudo $APP_DIR/setup.sh)…"
    exec sudo -n "$APP_DIR/setup.sh"
fi

# --- À partir d'ici : exécution en root. ---
TARGET_USER="${SUDO_USER:-$(logname 2>/dev/null || echo pi)}"
log "utilisateur d'affichage : $TARGET_USER"

# 1. Pré-requis : binaire love (installé depuis apt si absent) + dossier de l'app.
find_love() {
    local bin
    bin="$(command -v love || true)"
    if [ -z "$bin" ]; then
        for c in /usr/bin/love /usr/games/love /usr/local/bin/love; do
            [ -x "$c" ] && bin="$c" && break
        done
    fi
    printf '%s' "$bin"
}

LOVE_BIN="$(find_love)"
if [ -z "$LOVE_BIN" ]; then
    log "love absent — installation depuis le dépôt apt (paquet 'love')…"
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y love
    LOVE_BIN="$(find_love)"
fi
if [ -z "$LOVE_BIN" ]; then
    log "ERREUR : installation de LÖVE échouée — paquet 'love' introuvable via apt."
    exit 1
fi
if [ ! -f "$LOVE_DIR/main.lua" ]; then
    log "ERREUR : $LOVE_DIR/main.lua absent — l'app LÖVE n'a pas été déployée."
    exit 1
fi
log "love : $LOVE_BIN  ·  app : $LOVE_DIR"

# 1bis. Navigateur headless : l'app LÖVE rend la page https://caen.tech/interstice
#       en PNG (capture chromium en arrière-plan, cf. render-interstice.sh) pour
#       l'afficher en plein écran. On installe chromium depuis apt si absent.
find_browser() {
    local c bin=""
    for c in chromium-browser chromium; do
        bin="$(command -v "$c" || true)"
        [ -n "$bin" ] && break
    done
    printf '%s' "$bin"
}

BROWSER_BIN="$(find_browser)"
if [ -z "$BROWSER_BIN" ]; then
    log "navigateur headless absent — installation de chromium depuis apt…"
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y chromium-browser || apt-get install -y chromium
    BROWSER_BIN="$(find_browser)"
fi
if [ -z "$BROWSER_BIN" ]; then
    log "ERREUR : installation de chromium échouée — paquet 'chromium-browser'/'chromium' introuvable via apt."
    exit 1
fi
log "navigateur headless : $BROWSER_BIN"

# Le script de rendu doit être exécutable (appelé via `bash`, mais on aligne les droits).
chmod 0755 "$LOVE_DIR/render-interstice.sh" 2>/dev/null || true

# 2. Accès au DRM / framebuffer / entrées pour le compte d'affichage.
#    (le seat logind accorde aussi /dev/dri via ACL, ceci est une ceinture+bretelles.)
for g in video render input; do
    if getent group "$g" >/dev/null 2>&1; then
        usermod -aG "$g" "$TARGET_USER" || true
    fi
done

# 3. Lanceur : attend qu'un écran HDMI soit détecté avant de lancer LÖVE.
#    SDL/kmsdrm échoue ("kmsdrm not available") si aucun connecteur DRM n'est
#    `connected` (écran absent, en veille ou pas encore branché). Plutôt que de
#    laisser systemd relancer LÖVE en boucle (spam de logs, sessions PAM en rafale),
#    on poll les connecteurs — lire le fichier `status` force un re-probe DRM — et
#    on n'exec love qu'une fois un écran présent. Le service reste donc `active`
#    (en attente) sans écran, et l'affichage démarre dès le branchement / allumage.
log "écriture de $RUNNER"
cat > "$RUNNER" <<EOF
#!/usr/bin/env bash
set -u
LOVE_BIN="$LOVE_BIN"
LOVE_DIR="$LOVE_DIR"

announced=0
# Lire les fichiers status force le noyau à re-sonder le hotplug HDMI.
until grep -qsx connected /sys/class/drm/card*-HDMI*/status; do
    [ "\$announced" = 0 ] && echo "[signage] aucun écran détecté — attente du branchement HDMI…"
    announced=1
    sleep 3
done
echo "[signage] écran détecté — démarrage de LÖVE."
exec "\$LOVE_BIN" "\$LOVE_DIR"
EOF
chmod 0755 "$RUNNER"

# 4. Unité systemd : le lanceur sur tty1 en KMS/DRM, plein écran, relance auto.
log "écriture de $UNIT"
cat > "$UNIT" <<EOF
[Unit]
Description=Caen.tech — affichage LÖVE (signage)
After=systemd-user-sessions.service getty@tty1.service
Conflicts=getty@tty1.service

[Service]
Type=simple
User=$TARGET_USER
# Session de login PAM -> seat logind -> accès /dev/dri (DRM master sur tty1).
PAMName=login
TTYPath=/dev/tty1
TTYReset=yes
TTYVHangup=yes
StandardInput=tty
StandardOutput=journal
StandardError=journal
# L'écran ne doit jamais s'éteindre ni afficher de curseur clignotant.
ExecStartPre=-/usr/bin/setterm --blank 0 --powerdown 0 --cursor off
Environment=SDL_VIDEODRIVER=kmsdrm
Environment=SDL_AUDIODRIVER=dummy
WorkingDirectory=$LOVE_DIR
ExecStart=$RUNNER
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

# 5. (Re)chargement et activation au boot + démarrage immédiat.
systemctl daemon-reload
systemctl reset-failed "$SERVICE" >/dev/null 2>&1 || true
systemctl enable "$SERVICE" >/dev/null 2>&1 || systemctl enable "$SERVICE"
systemctl restart "$SERVICE"

log "service '$SERVICE' activé au boot et démarré."
echo "ok"
