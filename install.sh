#!/usr/bin/env bash
set -euo pipefail

REPO_URL="${CAENTECH_REPO_URL:-https://github.com/caentech/caen.tech.git}"
DEST="${1:-$HOME/caen.tech}"

log() { printf "\033[1;34m[caentech-install]\033[0m %s\n" "$*"; }

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

chmod +x "$DEST/pi/pi.sh" "$DEST/pi/install.sh"

"$DEST/pi/pi.sh" setup
"$DEST/pi/pi.sh" build

cat <<EOF

Installation complete.

Next steps:
  $DEST/pi/pi.sh run conference     # main room kiosk
  $DEST/pi/pi.sh run amphitheatre   # secondary room kiosk
  $DEST/pi/pi.sh run tv             # balanced view (e.g. lobby TV)

Drop mp3 files into $DEST/pi/music/ to play background music in loop on HDMI.

To update later:
  $DEST/pi/pi.sh update
EOF
