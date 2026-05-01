#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_DIR="$SCRIPT_DIR/dist"
MUSIC_DIR="$SCRIPT_DIR/music"
HTTP_PORT="${CAENTECH_HTTP_PORT:-4321}"
SITE_URL="${CAENTECH_SITE_URL:-https://caen.tech}"
RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp}/caentech-kiosk"
PID_FILE="$RUNTIME_DIR/run.pid"

log()  { printf "\033[1;34m[caentech]\033[0m %s\n" "$*"; }
warn() { printf "\033[1;33m[caentech]\033[0m %s\n" "$*" >&2; }
fail() { printf "\033[1;31m[caentech]\033[0m %s\n" "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
Usage: pi.sh <command> [args]

Commands:
  setup                       Install cage, mpv, chromium, wget, set timezone
                              to Europe/Paris, force HDMI audio output.
  build                       Mirror the latest published site from caen.tech
                              into ./dist (next to this script).
  run <salle>                 Start the interstice kiosk for a given room:
                                conference   - main conference room
                                amphitheatre - secondary room (alias: auditorium)
                                tv           - balanced view (no room highlighted)
                              Plays mp3 files from ./music/ in loop on HDMI audio.
  update                      git pull (scripts) + re-mirror site + restart the kiosk.
  enable-autostart <salle>    Auto-login on tty1 + launch kiosk at boot for <salle>.
  disable-autostart           Remove the auto-start block and restore login prompt.

Environment:
  CAENTECH_HTTP_PORT   port for the local http server (default: 4321)
  CAENTECH_SITE_URL    site to mirror (default: https://caen.tech)
EOF
}

resolve_salle() {
  case "$1" in
    conference|tv) echo "$1" ;;
    amphi|amphitheatre|auditorium) echo "auditorium" ;;
    *) fail "Unknown salle: $1 (expected: conference, amphitheatre, tv)" ;;
  esac
}

cmd_setup() {
  log "Updating apt..."
  sudo apt-get update

  log "Installing system packages..."
  sudo apt-get install -y \
    cage mpv git curl wget ca-certificates alsa-utils python3 \
    fonts-noto-core fonts-noto-color-emoji

  if ! sudo apt-get install -y chromium; then
    sudo apt-get install -y chromium-browser
  fi

  log "Setting timezone to Europe/Paris and enabling NTP..."
  sudo timedatectl set-timezone Europe/Paris
  sudo timedatectl set-ntp true

  log "Forcing audio output to HDMI..."
  if command -v raspi-config >/dev/null 2>&1; then
    sudo raspi-config nonint do_audio 2 || warn "raspi-config: could not force HDMI audio"
  fi
  amixer cset numid=3 2 >/dev/null 2>&1 || true

  local cfg
  for cfg in /boot/firmware/config.txt /boot/config.txt; do
    if [[ -f "$cfg" ]]; then
      if ! grep -qE '^[[:space:]]*dtparam=audio=on' "$cfg"; then
        log "Enabling audio in $cfg..."
        echo 'dtparam=audio=on' | sudo tee -a "$cfg" >/dev/null
      fi
      break
    fi
  done

  log "Setup complete. A reboot is recommended before first run."
}

cmd_build() {
  command -v wget >/dev/null 2>&1 || fail "wget not found — run: pi.sh setup"

  log "Mirroring published site from $SITE_URL into $DIST_DIR ..."
  mkdir -p "$DIST_DIR"

  # --mirror gives -r -N -l inf (timestamping = only re-fetch changed files).
  # /interstice/ is not in the sitemap, so we pass it explicitly.
  ( cd "$DIST_DIR" && wget \
      --mirror \
      --no-host-directories \
      --no-parent \
      --page-requisites \
      --adjust-extension \
      --execute robots=off \
      --no-verbose \
      --tries=3 \
      --timeout=30 \
      "$SITE_URL/" \
      "$SITE_URL/interstice/" )

  [[ -f "$DIST_DIR/interstice/index.html" ]] \
    || fail "Mirror finished but $DIST_DIR/interstice/index.html is missing."
  log "Site mirrored into $DIST_DIR"
}

start_http_server() {
  ( cd "$DIST_DIR" && exec python3 -m http.server "$HTTP_PORT" --bind 127.0.0.1 ) \
    >/dev/null 2>&1 &
  echo $!
}

start_music() {
  if [[ ! -d "$MUSIC_DIR" ]] || ! compgen -G "$MUSIC_DIR/*.mp3" >/dev/null; then
    warn "No mp3 found in $MUSIC_DIR — skipping music."
    return 0
  fi
  log "Starting background music on HDMI..."
  mpv --no-video --no-terminal \
      --loop-playlist=inf --shuffle \
      --ao=alsa \
      "$MUSIC_DIR"/*.mp3 \
      >/dev/null 2>&1 &
  echo $!
}

wait_for_http() {
  local i
  for ((i = 0; i < 50; i++)); do
    if curl -fsS "http://localhost:$HTTP_PORT/" >/dev/null 2>&1; then return 0; fi
    sleep 0.2
  done
  return 1
}

pick_browser() {
  local cand
  for cand in chromium chromium-browser; do
    if command -v "$cand" >/dev/null 2>&1; then echo "$cand"; return 0; fi
  done
  return 1
}

cmd_run() {
  local raw_salle="${1:-}"
  [[ -n "$raw_salle" ]] || fail "Usage: pi.sh run <salle> (conference|amphitheatre|tv)"
  local salle
  salle="$(resolve_salle "$raw_salle")"

  [[ -d "$DIST_DIR" ]] || fail "No build found at $DIST_DIR. Run: pi.sh build"

  local browser
  browser="$(pick_browser)" || fail "No browser found (install chromium)."

  mkdir -p "$RUNTIME_DIR"
  echo "$$" > "$PID_FILE"

  local children=()
  cleanup() {
    log "Shutting down kiosk..."
    local pid
    for pid in "${children[@]}"; do
      kill "$pid" 2>/dev/null || true
    done
    rm -f "$PID_FILE"
  }
  trap cleanup EXIT INT TERM

  log "Starting HTTP server on http://localhost:$HTTP_PORT ..."
  children+=("$(start_http_server)")

  if ! wait_for_http; then
    fail "HTTP server did not respond on port $HTTP_PORT"
  fi

  local music_pid
  music_pid="$(start_music || true)"
  [[ -n "${music_pid:-}" ]] && children+=("$music_pid")

  local url="http://localhost:$HTTP_PORT/interstice/?salle=$salle"
  log "Launching kiosk for salle=$salle"
  log "URL: $url"

  cage -- "$browser" \
    --kiosk \
    --noerrdialogs \
    --disable-infobars \
    --disable-translate \
    --disable-features=Translate,TranslateUI \
    --enable-features=UseOzonePlatform \
    --lang=fr-FR \
    --accept-lang=fr-FR,fr \
    --disable-pinch \
    --overscroll-history-navigation=0 \
    --autoplay-policy=no-user-gesture-required \
    --check-for-update-interval=31536000 \
    --ozone-platform=wayland \
    "$url" || true
}

cmd_update() {
  cd "$SCRIPT_DIR"
  log "Pulling latest changes..."
  git pull --ff-only
  cmd_build

  if [[ -f "$PID_FILE" ]]; then
    local pid
    pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      log "Restarting running kiosk (pid $pid)..."
      kill -TERM "$pid"
    fi
  fi
}

cmd_enable_autostart() {
  local raw_salle="${1:-}"
  [[ -n "$raw_salle" ]] || fail "Usage: pi.sh enable-autostart <salle>"
  resolve_salle "$raw_salle" >/dev/null  # validate

  log "Enabling console auto-login on TTY1..."
  if command -v raspi-config >/dev/null 2>&1; then
    sudo raspi-config nonint do_boot_behaviour B2 \
      || warn "raspi-config: could not enable console auto-login"
  else
    warn "raspi-config not found — enable console auto-login manually."
  fi

  local profile="$HOME/.bash_profile"
  local begin_marker="# >>> caentech-kiosk auto-start >>>"
  local end_marker="# <<< caentech-kiosk auto-start <<<"

  if [[ -f "$profile" ]] && grep -qF "$begin_marker" "$profile"; then
    log "Removing previous auto-start block from $profile..."
    sed -i.bak "\|$begin_marker|,\|$end_marker|d" "$profile"
  fi

  log "Adding auto-start block to $profile (salle=$raw_salle)..."
  {
    echo ""
    echo "$begin_marker"
    echo "if [ \"\$(tty)\" = \"/dev/tty1\" ] && [ -z \"\${WAYLAND_DISPLAY:-}\" ]; then"
    echo "  exec '$SCRIPT_DIR/pi.sh' run '$raw_salle'"
    echo "fi"
    echo "$end_marker"
  } >> "$profile"

  log "Auto-start configured. Reboot to test: sudo reboot"
}

cmd_disable_autostart() {
  local profile="$HOME/.bash_profile"
  local begin_marker="# >>> caentech-kiosk auto-start >>>"
  local end_marker="# <<< caentech-kiosk auto-start <<<"

  if [[ -f "$profile" ]] && grep -qF "$begin_marker" "$profile"; then
    log "Removing auto-start block from $profile..."
    sed -i.bak "\|$begin_marker|,\|$end_marker|d" "$profile"
  else
    warn "No auto-start block found in $profile."
  fi

  log "Disabling console auto-login (back to login prompt)..."
  if command -v raspi-config >/dev/null 2>&1; then
    sudo raspi-config nonint do_boot_behaviour B1 \
      || warn "raspi-config: could not restore console login"
  fi
}

main() {
  case "${1:-}" in
    setup)              shift; cmd_setup             "$@" ;;
    build)              shift; cmd_build             "$@" ;;
    run)                shift; cmd_run               "$@" ;;
    update)             shift; cmd_update            "$@" ;;
    enable-autostart)   shift; cmd_enable_autostart  "$@" ;;
    disable-autostart)  shift; cmd_disable_autostart "$@" ;;
    -h|--help|help|'')  usage ;;
    *)                  usage; exit 1 ;;
  esac
}

main "$@"
