# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`pi-manager` — a backend + single-page web UI to manage a fleet of Raspberry Pi (eventually digital signage for the **Caen.tech** conference). It runs **locally on a Mac**, deploys **no agent** on the Pi, and drives everything over SSH. Kotlin/Ktor backend; vanilla-JS SPA served as static resources.

Stack: Kotlin · Ktor (Netty) · kotlinx.serialization · coroutines · Exposed + SQLite · sshj. JDK 17.

## Commands

```bash
make run          # ./gradlew run — API + SPA on http://localhost:10028
make build        # ./gradlew build
make clean        # ./gradlew clean
./gradlew compileKotlin   # fast compile check (no tests exist in this repo)
```

There is **no test suite**. Validate changes by running the server and exercising the API / UI (a real Pi is typically on the LAN at `caentech.local`). Useful probes:

```bash
curl -s http://localhost:10028/api/health
curl -s http://localhost:10028/api/devices   # overview + derived state per device
curl -s http://localhost:10028/api/summary   # counts per state
```

**Server lifecycle gotcha:** `./gradlew run` forks a JVM that does **not** die when you stop the Gradle wrapper (or a background task). If a restart fails with exit 1 (port already bound), kill the orphan first:

```bash
pkill -f "tech.caen.pimanager.ApplicationKt"
lsof -ti :10028 | xargs kill -9
```

## Core model: state is always SSH-pulled, never pushed

A background `Poller` (every `PI_MANAGER_POLL_INTERVAL_SECONDS`, default 30s) plus on-demand `POST /api/devices/{id}/check` derive each device's state by SSHing into it. The Pi never calls back. State derivation lives entirely in `DeviceService.pull()`:

1. SSH unreachable (timeout/refused) → `not connected`
2. SSH refused with `Permission denied` (no key yet) → `connection setup`
3. SSH OK but the single config file is absent/invalid → `new`
4. config file valid (`managedBy == "pi-manager"`), `state` ≠ ready → `setup`
5. config file valid, `state == ready` → `ready`

The progression `connection setup → new → setup → ready` is the enrollment lifecycle. When changing states, the enum (`model/Models.kt` `DeviceState`, with `@SerialName` slugs), `pull()`, `Summary`, `fromSlug()`, and the **frontend** `STATE_KEY`/`STATE_META` maps must all stay in sync.

### One file on the Pi: `pi-swarm.json`

Enrollment identity **and** runtime state are merged into a **single** file at `~/.pi-manager/pi-swarm.json` (`PiStatus`). `managedBy == "pi-manager"` is the enrollment marker that makes a device count as configured; `state` distinguishes `setup` from `ready`. The display app (not built yet) is expected to update this file and **preserve `managedBy`**.

## SSH: two distinct paths, app-owned key only

- **`SshService`** — shells out to the system `ssh`/`scp` binaries for all routine access (check, exec, scp, logs). Authenticates **only** with pi-manager's own key. It deliberately ignores the user's `~/.ssh` via `-F /dev/null -o IdentitiesOnly=yes`, and adds `-i <key>` when the key exists. Host keys are centralized in the app's own `known_hosts` via `-o UserKnownHostsFile=<data/keys/known_hosts> -o StrictHostKeyChecking=accept-new` (never `~/.ssh/known_hosts`). Do not reintroduce reliance on `~/.ssh` keys/config/agent.
- **`SshProvisioner`** — uses the sshj library with **password** auth. This is the **only** password path, used once during `configure` for an idempotent bootstrap pass: (1) verify the Pi's host key in **TOFU** via `TofuHostKeyVerifier` (remember on first sight in the app's `known_hosts`, refuse if changed → MITM), (2) install the public key in `~/.ssh/authorized_keys` (no dup), (3) install the **NOPASSWD sudoers** drop-in `/etc/sudoers.d/pi-manager` (authorizing `/opt/pi-manager/setup.sh`, `/opt/pi-manager/update.sh`, systemctl, reboot, shutdown) — validated with `visudo -cf` **before** install, the single privileged write that pipes the password to `sudo -S` — plus create `/opt/pi-manager` owned by the deploying account (so the key-based `setup` phase can drop `setup.sh`/`update.sh` there, at the sudoers-authorized paths), (4) write `pi-swarm.json` **last** so a sudoers failure never leaves a half-enrolled device. The password is never logged or stored. After enrollment the Pi is driveable by key with passwordless sudo.

- **`TofuHostKeyVerifier`** — sshj `HostKeyVerifier` backing the app's OpenSSH-format `known_hosts` (`PI_MANAGER_KNOWN_HOSTS_FILE`, default `data/keys/known_hosts`), shared with `SshService`. Exposes the SHA256 fingerprint (`lastFingerprint`) surfaced at enrollment.

The global key pair (`data/keys/pi-swarm_ed25519`) is generated on demand by `SshProvisioner.ensurePublicKey()` and shared across the whole fleet. The `ssh ...` command shown in the UI / copied to clipboard (`SshService.command()`) includes `-i <absolute key path>` and the app's `UserKnownHostsFile` when the key exists, so a human connects with the same identity and host-key state as pi-manager.

## Layering

- `Application.kt` — wires everything (`module()`): builds `SshService`, `SshProvisioner`, `DeviceRepository`, `FileStore`, `EventBus`, `DeviceService`, installs Ktor plugins, starts the `Poller`.
- `Routing.kt` — all HTTP routes. Display-app controls (music/slide/cache/etc.) are stubs returning **501** (`DeviceService.DEFERRED_CONTROLS`) until the display app exists.
- `service/DeviceService.kt` — orchestration hub: registry CRUD, state derivation, configure/setup, device actions, files, logs. This is where state is derived and where real-time events are published.
- `service/EventBus.kt` + SSE — real-time is **SSE only** (`GET /api/stream`); there is no WebSocket. State changes and actions publish `StreamEvent`s the SPA consumes.
- `db/` — Exposed + SQLite (`data/pi-manager.db`); the repo persists the last derived state/status JSON per device.
- `Config.kt` — every setting is overridable via env var (or `-D` system property) with Mac-local defaults. Remote paths (`PI_MANAGER_STATE_PATH`, etc.) may contain `~` (expanded by the remote shell).

## Frontend

`src/main/resources/web/` (`index.html`, `app.js`, `styles.css`) — a dependency-free SPA served by Ktor `staticResources`. Editing it requires **restarting the server** (Gradle copies resources into `build/resources/main` at build time; the running JVM serves from there). `app.js` maps backend state slugs to its own keys (`STATE_KEY`) and renders per-state cards, detail panels, and SSE-driven live updates. Light/dark theming via CSS custom props (`--st-*` per state).

## Conventions

- French is used throughout for UI text, KDoc, comments, and commit messages. Commit style: `pi-manager : <description>` (see `git log`).
- This is a personal project — the global "Memo Bank" backend/frontend rules (Spring Boot, ticket-prefixed commits, etc.) do **not** apply here.
