# pi-caentech

Raspberry Pi kiosk runner for [caen.tech](https://caen.tech).

Turns a Pi into a wall-mounted display that shows the `/interstice/` page — the
per-room "what's happening now" view of caen.tech — with optional background
music on HDMI audio.

The site itself is **not built here**: `pi.sh build` mirrors the already-published
site from `https://caen.tech` with `wget --mirror`. This repo is self-contained
— it does not depend on a clone of the `caen.tech` source repository.

---

## Quick start

On a fresh Raspberry Pi (Raspberry Pi OS, with network access):

```bash
curl -fsSL https://raw.githubusercontent.com/caentech/pi-caentech/main/install.sh | bash
```

This will:

1. Install `git` if missing.
2. Clone this repository (`pi-caentech`) into `~/caen.tech`.
3. Run `pi.sh setup` (installs `cage`, `mpv`, `chromium`, `wget`, sets timezone
   to `Europe/Paris`, forces HDMI audio).
4. Run `pi.sh build` (mirrors the published site into `~/caen.tech/dist`).

Then start the kiosk for the room this Pi is wall-mounted in:

```bash
~/caen.tech/pi.sh run conference     # main conference room
~/caen.tech/pi.sh run amphitheatre   # secondary room (alias: auditorium)
~/caen.tech/pi.sh run tv             # balanced view (lobby TV)
```

A reboot after `setup` is recommended before the first `run`, so the audio and
timezone changes take effect.

---

## Manual install

If you'd rather not pipe a script into bash, do it by hand:

```bash
sudo apt-get update && sudo apt-get install -y git
git clone https://github.com/caentech/pi-caentech.git ~/caen.tech
cd ~/caen.tech
./pi.sh setup
./pi.sh build
./pi.sh run conference
```

---

## Commands

```bash
./pi.sh setup                       # install cage, mpv, chromium, wget; set TZ; force HDMI audio
./pi.sh build                       # mirror caen.tech into ./dist (and /interstice/ explicitly)
./pi.sh run <salle>                 # start the kiosk (see below)
./pi.sh update                      # git pull + re-mirror + restart the running kiosk
./pi.sh enable-autostart <salle>    # auto-login on tty1 + launch kiosk at boot
./pi.sh disable-autostart           # remove the auto-start block, restore login prompt
./pi.sh help                        # print usage
```

### Rooms

| Argument                                | Resolves to    | Use for                       |
|-----------------------------------------|----------------|-------------------------------|
| `conference`                            | `conference`   | Main conference room          |
| `amphitheatre` / `amphi` / `auditorium` | `auditorium`   | Secondary room (amphitheatre) |
| `tv`                                    | `tv`           | Lobby TV / balanced view      |

The room name is passed to the page as `?salle=<salle>`.

### Environment variables

| Variable             | Default               | Purpose                          |
|----------------------|-----------------------|----------------------------------|
| `CAENTECH_HTTP_PORT` | `4321`                | Local HTTP server port           |
| `CAENTECH_SITE_URL`  | `https://caen.tech`   | Site to mirror                   |

---

## Background music (optional)

Drop `.mp3` files into `music/`. They will be played in shuffled loop on
HDMI audio whenever a kiosk is running. If the directory is empty, the kiosk
starts silently — no error.

```bash
cp /path/to/*.mp3 ~/caen.tech/music/
```

---

## Auto-start at boot

For an unattended wall-mounted display, the kiosk should come up by itself
when the Pi is powered on — no keyboard, no SSH, no manual `pi.sh run`.

### Enabling it

```bash
~/caen.tech/pi.sh enable-autostart conference   # or amphitheatre / tv
sudo reboot
```

After the reboot, the Pi will boot straight into the kiosk for the room you
chose. It will also play whatever `.mp3` files live in `music/`.

### How it actually works

`pi.sh enable-autostart <salle>` does two simple things — no systemd
service, no display manager:

1. **Console auto-login on tty1.** Runs `raspi-config nonint
   do_boot_behaviour B2`, which tells the Pi to log the default user in
   automatically on the first text console (tty1) at boot.
2. **A guarded block in `~/.bash_profile`.** Appends this between two
   markers:

   ```bash
   # >>> caentech-kiosk auto-start >>>
   if [ "$(tty)" = "/dev/tty1" ] && [ -z "${WAYLAND_DISPLAY:-}" ]; then
     exec '/home/caentech/caen.tech/pi.sh' run 'conference'
   fi
   # <<< caentech-kiosk auto-start <<<
   ```

   Because the auto-login lands on tty1 and runs the user's login shell,
   `~/.bash_profile` is sourced, the guard matches, and `exec` replaces the
   shell with the kiosk. `cage` then takes over the framebuffer.

The guard matters: SSH sessions, other ttys, or a shell launched from
inside an existing Wayland session will all fail the `tty` / `WAYLAND_DISPLAY`
check and drop you to a normal prompt. So you can always `ssh` into the Pi
to administer it without bumping into the kiosk.

### Switching to a different room

Just re-run `enable-autostart` with the new room — the block is bracketed
by markers and gets replaced in place:

```bash
~/caen.tech/pi.sh enable-autostart tv
```

The change applies on the next boot. If the kiosk is already running and
you want it to switch immediately:

```bash
kill -TERM "$(cat "${XDG_RUNTIME_DIR:-/tmp}/caentech-kiosk/run.pid")"
sudo reboot   # or just wait for the next reboot
```

### Disabling it

```bash
~/caen.tech/pi.sh disable-autostart
```

This removes the block from `~/.bash_profile` and runs `raspi-config
nonint do_boot_behaviour B1` to restore the normal login prompt on tty1.
It does **not** stop a kiosk currently running — see the FAQ below.

### Updating while autostart is active

`pi.sh update` is autostart-aware: it pulls scripts, re-mirrors the site,
then sends `SIGTERM` to the running kiosk via the PID file at
`$XDG_RUNTIME_DIR/caentech-kiosk/run.pid`. When the kiosk exits, the
`exec` in `~/.bash_profile` is gone too — you're back at a logged-in
shell on tty1. **The kiosk does not auto-restart**: it only fires on
fresh tty1 logins. To bring it back up you need either a reboot or to
log out (`exit`) so auto-login fires again.

If you want a "kill and restart now" cycle from SSH:

```bash
~/caen.tech/pi.sh update          # mirrors + kills the kiosk
sudo systemctl restart getty@tty1   # forces a fresh tty1 login → re-runs auto-start
```

### Debugging

```bash
# Did the auto-login actually trigger?
who                                          # should show the user on tty1

# Did the .bash_profile guard fire?
journalctl --user -b                         # cage / chromium output ends up here

# Is the kiosk running?
ls "${XDG_RUNTIME_DIR:-/tmp}/caentech-kiosk/run.pid"
ps -ef | grep -E 'pi\.sh run|cage|chromium'
```

If the Pi boots straight to a login prompt instead of the kiosk, the most
common causes are: `raspi-config` did not flip `B2` (re-run the command
with `sudo`), or the block in `~/.bash_profile` is below an early `exit` /
`return` from a shell hook, so it never gets reached.

---

## Updating the displayed pages

The Pi never builds the site itself — it mirrors what's already published at
`https://caen.tech`. To pull the latest version of the pages:

```bash
~/caen.tech/pi.sh update
```

This does three things:

1. `git pull --ff-only` on this `pi-caentech` repo (picks up new scripts / music
   if any).
2. Re-runs `pi.sh build` to re-mirror the live site (only changed files are
   downloaded, thanks to `wget --mirror` timestamping).
3. If a kiosk is currently running on this Pi, sends it `SIGTERM` so the
   supervisor (systemd, or whatever you set up) restarts it on the fresh
   mirror.

If you only want to refresh the pages **without** updating the scripts:

```bash
~/caen.tech/pi.sh build
```

---

## FAQ

### How do I exit kiosk mode?

The kiosk runs inside `cage` (a minimal Wayland compositor) with Chromium in
fullscreen. To leave it:

- **From a keyboard plugged into the Pi:** press `Ctrl + Alt + Backspace`.
  Cage exits, which kills Chromium and the trap in `pi.sh` cleans up the
  HTTP server and music process.
- **Over SSH:**
  ```bash
  pkill -TERM -f 'pi.sh run'
  ```
  This sends `SIGTERM` to the `pi.sh run` process; the trap cleans everything
  up. If that's not enough, `pkill cage` will tear down the Wayland session.

### How do I start the kiosk?

Run it manually:

```bash
~/caen.tech/pi.sh run conference
```

For unattended boot-time launch (no keyboard, no SSH), see the
**Auto-start at boot** section above.

### How do I stop the kiosk?

If you started it manually in a terminal, `Ctrl + C` is enough — the trap
in `pi.sh` kills the HTTP server and music process before exiting.

If it was started by `enable-autostart`, kill the running process from an
SSH session:

```bash
kill -TERM "$(cat "${XDG_RUNTIME_DIR:-/tmp}/caentech-kiosk/run.pid")"
```

It will not restart by itself until the next reboot (auto-start only
fires on tty1 login). To also prevent it from coming back at the next
boot, run `pi.sh disable-autostart`.

### How do I get the latest version of the service?

```bash
~/caen.tech/pi.sh update
```

This pulls the latest scripts **and** re-mirrors the site. If you only want
the latest scripts (no page refresh):

```bash
git -C ~/caen.tech pull --ff-only
```

If you only want the latest pages (no script update):

```bash
~/caen.tech/pi.sh build
```

### How do I test it without a TV?

You can run every step except the actual kiosk on any Linux machine:

```bash
./pi.sh build                   # mirror the site
( cd dist && python3 -m http.server 4321 --bind 127.0.0.1 ) &
xdg-open 'http://localhost:4321/interstice/?salle=conference'
```

To test the full kiosk flow on the Pi without a permanent install, just run
`./pi.sh run <salle>` from an SSH session — Cage will take over the HDMI
output. `Ctrl + C` in the SSH session ends the test cleanly.

To verify a specific room renders correctly, change the `?salle=` query
parameter:

- `?salle=conference`
- `?salle=auditorium`
- `?salle=tv`

### The kiosk shows a blank page / "connection refused"

The local HTTP server didn't come up in time, or the mirror is missing. Check:

```bash
ls ~/caen.tech/dist/interstice/index.html   # must exist
curl -I http://localhost:4321/interstice/     # must return 200
```

Re-run `./pi.sh build` if `index.html` is missing.

### There's no sound

`pi.sh setup` forces audio to HDMI and enables `dtparam=audio=on`, but a
reboot is needed before that takes effect. Also check:

- `music/` actually contains `.mp3` files (an empty directory is silently
  skipped).
- The TV/display is not muted and is on the HDMI input the Pi is wired to.
- `amixer` shows a sane volume: `amixer sget 'Master'`.

### How do I change which room a Pi displays?

If you're running it manually, just `Ctrl + C` and re-launch with a
different argument:

```bash
~/caen.tech/pi.sh run tv
```

If auto-start is enabled, re-run `enable-autostart` with the new room and
reboot:

```bash
~/caen.tech/pi.sh enable-autostart tv
sudo reboot
```

The bracketed block in `~/.bash_profile` is replaced in place — no manual
editing needed.

---

## Repository layout

```
pi-caentech/
├── install.sh        # one-shot bootstrap (clones this repo, runs setup + build)
├── pi.sh             # the actual kiosk runner (setup / build / run / update)
├── music/            # drop *.mp3 here for background audio
├── dist/             # generated by `pi.sh build` (mirrored site, gitignored)
└── README.md         # this file
```

`pi.sh build` writes the mirrored site to `./dist` (next to this script).
