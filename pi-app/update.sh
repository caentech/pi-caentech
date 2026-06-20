#!/usr/bin/env bash
#
# Script de MISE À JOUR de l'application sur le Pi.
# Déployé avec setup.sh dans /opt/pi-manager. Rafraîchit l'application d'affichage
# (le nouveau code LÖVE ayant déjà été décompressé dans /opt/pi-manager/love par
# pi-manager) en relançant le service systemd, sans repasser par tout le setup.
# Autorisé en sudo NOPASSWD (cf. /etc/sudoers.d/pi-manager) : /opt/pi-manager/update.sh.
#
set -euo pipefail

APP_DIR="/opt/pi-manager"
SERVICE="caentech-signage"

log() { echo "[update] $*"; }

# Auto-élévation via le chemin sudoers NOPASSWD.
if [ "$(id -u)" -ne 0 ]; then
    log "élévation des privilèges (sudo $APP_DIR/update.sh)…"
    exec sudo -n "$APP_DIR/update.sh"
fi

if ! systemctl list-unit-files "${SERVICE}.service" >/dev/null 2>&1; then
    log "ERREUR : service '$SERVICE' absent — lancez d'abord le setup."
    exit 1
fi

systemctl restart "$SERVICE"
log "service '$SERVICE' redémarré."
echo "ok"
