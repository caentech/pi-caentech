# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Raspberry Pi kiosk runner for [caen.tech](https://caen.tech). Two bash scripts that turn a Pi into a wall-mounted display showing the `/interstice/` page (a per-room "what's happening now" view), with optional background music on HDMI.

`install.sh` clones this repo (the `pi-caentech` repository, into `~/caen.tech` by default) and runs `pi.sh setup && pi.sh build`. The site itself is **not built here** — `pi.sh build` mirrors the already-published site from `https://caen.tech` with `wget --mirror`. The scripts have **no dependency** on a clone of the upstream `caen.tech` source repo.

## Commands

```bash
./pi.sh setup                       # install cage, mpv, chromium, set TZ, force HDMI audio
./pi.sh build                       # wget --mirror caen.tech into ./dist (+ /interstice/ explicitly)
./pi.sh run conference              # kiosk for the main conference room
./pi.sh run amphitheatre            # alias: auditorium (resolves to "auditorium")
./pi.sh run tv                      # balanced view (lobby TV, no room highlighted)
./pi.sh update                      # git pull + re-mirror + SIGTERM the running kiosk
./pi.sh enable-autostart <salle>    # tty1 auto-login + launch kiosk at boot
./pi.sh disable-autostart           # remove the auto-start block
```

Env overrides: `CAENTECH_HTTP_PORT` (default `4321`), `CAENTECH_SITE_URL` (default `https://caen.tech`).

## Architecture

`pi.sh run <salle>` is the only non-trivial command. It orchestrates three child processes inside a single trap-cleaned shell:

1. **HTTP server** — `python3 -m http.server` on `127.0.0.1:$HTTP_PORT`, serving `./dist` (the mirrored site, sibling of `pi.sh`).
2. **Background music** (optional) — `mpv --no-video --loop-playlist=inf --shuffle --ao=alsa` over `music/*.mp3`. Skipped silently if no mp3 files are present.
3. **Kiosk browser** — `cage -- chromium --kiosk` pointing at `http://localhost:$PORT/interstice/?salle=<salle>`. Runs in foreground; the trap kills the HTTP and music PIDs on exit.

The script writes its own PID to `$XDG_RUNTIME_DIR/caentech-kiosk/run.pid` so `cmd_update` can `kill -TERM` the running kiosk after a re-mirror — systemd or whatever supervises the kiosk is expected to restart it.

Translation prompts are killed via `--disable-translate` + `--disable-features=Translate,TranslateUI` and `--lang=fr-FR` / `--accept-lang=fr-FR,fr`. Cursor hiding is the responsibility of the `/interstice/` page itself (`cursor: none` in CSS) — `pi.sh` does not inject any styling.

### Boot autostart

`cmd_enable_autostart <salle>` does two things: enables console auto-login on tty1 via `raspi-config nonint do_boot_behaviour B2`, and appends a guarded block to `~/.bash_profile` that `exec`s `pi.sh run <salle>` when the shell is on `/dev/tty1` and `WAYLAND_DISPLAY` is unset. The block is bracketed by `# >>> caentech-kiosk auto-start >>>` / `# <<< caentech-kiosk auto-start <<<` markers so re-running the command (or `cmd_disable_autostart`) replaces it idempotently.

### Salle resolution

`resolve_salle()` is the only place where the room alias mapping lives: `amphi`/`amphitheatre`/`auditorium` all collapse to `auditorium` (the value the `/interstice/` page expects in its `?salle=` query param). `conference` and `tv` pass through.

### Mirror layout

`pi.sh build` writes to `$SCRIPT_DIR/dist` (i.e. **`./dist` next to `pi.sh`**). `/interstice/` is fetched explicitly because it is excluded from the sitemap. The build sanity-checks that `dist/interstice/index.html` exists before declaring success.

## Conventions for this repo

- **Self-contained git repository.** `pi.sh update` runs `git pull --ff-only` from `$SCRIPT_DIR` — that's the `pi-caentech` repo itself. There is no longer any dependency on a parent `caen.tech` source clone.
- **No frontend/backend conventions apply.** The global rules in `~/.claude/` for Memo Bank projects (commit format, module structure, etc.) are not relevant here. Use plain imperative commit messages if/when committing upstream.
- Shell scripts use `set -euo pipefail` and the `log`/`warn`/`fail` helpers — keep that pattern when adding commands.
- `cmd_*` functions are dispatched from `main()`'s case statement; add new commands by following the same shape and updating `usage()`.
