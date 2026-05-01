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

# Generates a fully transparent XCursor theme so cage doesn't draw its
# default pointer at the center of the screen on boot. The page's own
# `cursor: none` CSS only kicks in once Chromium has loaded — until then
# the compositor's cursor is visible.
ensure_blank_cursor_theme() {
  # Must live under one of libXcursor's default search paths
  # (~/.icons:/usr/share/icons:/usr/share/pixmaps:~/.cursors). XDG paths
  # like ~/.local/share/icons are NOT searched, so the legacy ~/.icons
  # is the only user-writable choice that is guaranteed to be picked up.
  local theme_dir="$HOME/.icons/caentech-blank"
  local cursor_file="$theme_dir/cursors/default"

  # Clean up the previous (XDG) location used by older versions of pi.sh.
  local legacy_dir="${XDG_DATA_HOME:-$HOME/.local/share}/icons/caentech-blank"
  if [[ -d "$legacy_dir" && "$legacy_dir" != "$theme_dir" ]]; then
    rm -rf "$legacy_dir"
  fi

  if [[ -f "$cursor_file" ]]; then
    return 0
  fi

  command -v python3 >/dev/null 2>&1 || return 1

  log "Generating transparent cursor theme at $theme_dir ..."
  mkdir -p "$theme_dir/cursors"

  cat > "$theme_dir/index.theme" <<'EOF'
[Icon Theme]
Name=caentech-blank
Comment=Transparent cursor for caen.tech kiosk
EOF

  python3 - "$cursor_file" <<'PY' || return 1
import struct, sys
out_path = sys.argv[1]
size = 32
buf = bytearray(b'Xcur')
buf += struct.pack('<III', 16, 0x00010000, 1)            # header_size, version, ntoc
buf += struct.pack('<III', 0xfffd0002, size, 28)         # toc: type (image), nominal size, position
buf += struct.pack('<IIII', 36, 0xfffd0002, size, 1)     # image chunk header (header_size, type, subtype, version)
buf += struct.pack('<IIIII', size, size, 0, 0, 0)        # width, height, xhot, yhot, delay
buf += b'\x00' * (size * size * 4)                       # ARGB pixels, all transparent
with open(out_path, 'wb') as f:
    f.write(buf)
PY

  local name
  for name in \
      left_ptr arrow X_cursor top_left_arrow \
      text xterm ibeam crosshair cross plus \
      hand1 hand2 pointing_hand grab grabbing pointer \
      wait watch progress \
      fleur all-scroll move \
      sb_h_double_arrow sb_v_double_arrow \
      ew-resize ns-resize nesw-resize nwse-resize \
      n-resize s-resize e-resize w-resize \
      ne-resize nw-resize se-resize sw-resize \
      col-resize row-resize \
      top_side bottom_side left_side right_side \
      top_left_corner top_right_corner \
      bottom_left_corner bottom_right_corner \
      question_arrow help context-menu \
      not-allowed no-drop \
      copy alias cell vertical-text \
      zoom-in zoom-out; do
    ln -sf default "$theme_dir/cursors/$name"
  done
}

# Grants the kiosk user write access to /dev/uinput so we can synthesize
# a single pointer-motion event after cage starts. Without any input,
# Chromium never receives wl_pointer.enter and so never gets the chance
# to apply its `cursor: none` CSS — leaving cage's default cursor visible
# at the screen center. One nudge is enough to wake it up.
ensure_uinput_access() {
  local rule="/etc/udev/rules.d/60-caentech-uinput.rules"
  local rule_content='KERNEL=="uinput", GROUP="input", MODE="0660", OPTIONS+="static_node=uinput"'

  if [[ ! -f "$rule" ]] || ! grep -qF "$rule_content" "$rule"; then
    log "Granting input-group access to /dev/uinput..."
    echo "$rule_content" | sudo tee "$rule" >/dev/null
    sudo udevadm control --reload-rules
    sudo udevadm trigger /dev/uinput 2>/dev/null || true
  fi

  if ! id -nG "$USER" | tr ' ' '\n' | grep -qx input; then
    log "Adding $USER to 'input' group (reboot required to take effect)..."
    sudo usermod -aG input "$USER"
  fi
}

# Spawns a background pointer-nudge process: ~$1 seconds after invocation,
# create a virtual mouse via /dev/uinput, send some motion bursts to make
# Chromium apply its `cursor: none` CSS, then keep the device alive (so
# cage doesn't lose the pointer entity) until the kiosk shuts down.
# Logs to $RUNTIME_DIR/nudge.log. Echos the PID so the caller can clean it up.
start_pointer_nudge() {
  local delay="${1:-6}"
  local log_file="$RUNTIME_DIR/nudge.log"
  : >"$log_file" 2>/dev/null || true
  (
    sleep "$delay"
    exec python3 - >>"$log_file" 2>&1 <<'PY'
import os, signal, sys, time
print(f"[nudge] starting at {time.strftime('%H:%M:%S')}", flush=True)
try:
    st = os.stat('/dev/uinput')
    print(f"[nudge] /dev/uinput mode={oct(st.st_mode)} uid={st.st_uid} gid={st.st_gid}", flush=True)
except FileNotFoundError:
    print("[nudge] /dev/uinput missing", flush=True)
    sys.exit(1)
try:
    from evdev import UInput, ecodes as e
except ImportError as ex:
    print(f"[nudge] python3-evdev missing: {ex}", flush=True)
    sys.exit(1)
try:
    ui = UInput(
        {e.EV_REL: [e.REL_X, e.REL_Y], e.EV_KEY: [e.BTN_LEFT]},
        name="caentech-nudge",
    )
    print("[nudge] uinput device created", flush=True)
except (PermissionError, OSError) as ex:
    print(f"[nudge] cannot create uinput device: {ex}", flush=True)
    sys.exit(1)

def shutdown(*_):
    print("[nudge] shutting down", flush=True)
    try: ui.close()
    except Exception: pass
    sys.exit(0)
signal.signal(signal.SIGTERM, shutdown)
signal.signal(signal.SIGINT, shutdown)

time.sleep(1.0)  # give cage/libinput time to discover the new device
print("[nudge] sending initial motion bursts", flush=True)
for i in range(8):
    ui.write(e.EV_REL, e.REL_X, 80)
    ui.write(e.EV_REL, e.REL_Y, 80)
    ui.syn()
    time.sleep(0.15)
    ui.write(e.EV_REL, e.REL_X, -80)
    ui.write(e.EV_REL, e.REL_Y, -80)
    ui.syn()
    time.sleep(0.15)
print("[nudge] initial bursts done; entering keep-alive (1 motion every 30s)", flush=True)

# Keep the device registered so cage keeps a pointer entity. A small
# periodic motion also re-triggers wl_pointer.enter if the page reloads.
while True:
    time.sleep(30)
    ui.write(e.EV_REL, e.REL_X, 1); ui.write(e.EV_REL, e.REL_Y, 1); ui.syn()
    time.sleep(0.05)
    ui.write(e.EV_REL, e.REL_X, -1); ui.write(e.EV_REL, e.REL_Y, -1); ui.syn()
PY
  ) &
  echo $!
}

cmd_setup() {
  log "Updating apt..."
  sudo apt-get update

  log "Installing system packages..."
  sudo apt-get install -y \
    cage mpv git curl wget ca-certificates alsa-utils python3 python3-evdev \
    fonts-noto-core fonts-noto-color-emoji

  if ! sudo apt-get install -y chromium; then
    sudo apt-get install -y chromium-browser
  fi

  ensure_blank_cursor_theme || warn "Could not generate blank cursor theme — cursor may show at boot."
  ensure_uinput_access || warn "Could not configure /dev/uinput — pointer-nudge will be a no-op."

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

  ensure_blank_cursor_theme || warn "Cursor may be visible until Chromium loads."
  export XCURSOR_THEME=caentech-blank
  export XCURSOR_PATH="$HOME/.icons:/usr/share/icons:/usr/share/pixmaps"

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

  log "Scheduling pointer nudge to wake up cursor: none CSS..."
  children+=("$(start_pointer_nudge 6)")

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
