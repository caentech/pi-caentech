#!/usr/bin/env bash
set -euo pipefail

REPO_URL="${CAENTECH_REPO_URL:-https://github.com/caentech/pi-caentech.git}"
DEST="${CAENTECH_INSTALL_DIR:-$HOME/caen.tech}"
SALLE="${1:-}"

log()  { printf "\033[1;34m[caentech-install]\033[0m %s\n" "$*"; }
fail() { printf "\033[1;31m[caentech-install]\033[0m %s\n" "$*" >&2; exit 1; }

if [[ -z "$SALLE" ]]; then
  fail "Usage: install.sh <salle>  (conference | amphitheatre | tv)"
fi

case "$SALLE" in
  conference|tv|amphi|amphitheatre|auditorium) ;;
  *) fail "Unknown salle: $SALLE (expected: conference, amphitheatre, tv)" ;;
esac

if ! command -v git >/dev/null 2>&1; then
  log "Installing git..."
  sudo apt-get update
  sudo apt-get install -y git
fi

if [[ -d "$DEST/.git" ]]; then
  log "Repo already present at $DEST — pulling latest..."
  git -C "$DEST" pull --ff-only
else
  log "Cloning $REPO_URL into $DEST..."
  git clone "$REPO_URL" "$DEST"
fi

chmod +x "$DEST/pi.sh" "$DEST/install.sh"

"$DEST/pi.sh" setup
"$DEST/pi.sh" build
"$DEST/pi.sh" enable-autostart "$SALLE"

cat <<EOF

Installation complete — auto-start enabled for salle=$SALLE.

Reboot now to launch the kiosk automatically:

  sudo reboot

After the reboot, the Pi will boot straight into the kiosk for the room
you selected. Drop mp3 files into $DEST/music/ to play background music
in loop on HDMI audio.

To update later:
  $DEST/pi.sh update

To change room or disable auto-start:
  $DEST/pi.sh enable-autostart <salle>
  $DEST/pi.sh disable-autostart
EOF
