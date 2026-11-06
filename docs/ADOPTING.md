# Adopting Phone Steward

Everything you need to stand the system up on your own server and phone, and to bend it
to your host. Read [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) once for the shape of the
thing; this file is the working manual.

## Requirements

- **Server:** Node 24+, a SQLite-capable filesystem, ~300 MB RAM for the server plus
  ~400 MB if you keep the web-browse tool (it runs a headless Chromium). Any small
  always-on machine works; the phone is never the agent's host.
- **Phone:** Android 13 (API 33) or newer. The app is a standard debug-signed APK; no
  root, no custom ROM. Shizuku (optional) unlocks exact app sizes and one-tap cache
  trimming.
- **A model provider API key.** The default configuration uses the `zai` provider with
  `glm-5.3-flash`; any provider the Pi coding agent's registry knows works (see "The
  model key" below).
- **Android SDK 35** to build the app.

## Choosing your package id

The app ships as a debug-signed APK (the same signing key across updates, which the
self-update flow checks; see `docs/SAFETY.md`).

Its id starts as `com.example.phonesteward` (its optional end-to-end helpers are
`com.example.e2ehelper.*`). Before a real deployment, pick your own application id and
rename it everywhere:

```bash
grep -rl "com.example.phonesteward" app server | xargs sed -i 's/com\.example\.phonesteward/your.own.id/g'
```

That covers the Kotlin packages (directory tree `app/src/*/java/com/example/phonesteward/`
— move it to match), the `namespace`/`applicationId` in `app/build.gradle.kts`, the
manifests, the server's `STEWARD_PACKAGE` constants (`server/src/shizuku.ts`,
`server/src/live/state.ts`), and the test sources. Keep the rename mechanical and
all-at-once; the server and app must agree on the id.

## Building and installing the APK

```bash
cd app
./gradlew testDebugUnitTest lintDebug assembleDebug
# app/build/outputs/apk/debug/phone-steward-debug.apk
```

Install it on the phone (`adb install` or sideload the file). The app asks for its
permissions as you walk through its onboarding: usage access, all-files access, media
read, unrestricted battery. Optional extras: Shizuku (for exact sizes and cache
trimming), and "Install unknown apps" only if you enable the self-update flow.

The unit-test and lint gate is the app's real gate:

```bash
ANDROID_HOME=/path/to/sdk ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

`app/local.properties` (your SDK path), `build/`, `.gradle/`, APKs and keystores are
gitignored and never committed.

## Running the server

```bash
cd server && npm ci          # Node 24; installs pinned dependencies
npm test && npm run typecheck
```

With everything external off (the default), a first run needs only a state directory.
Export it once, so the server and every `bin/steward` CLI call see the same value (the CLI
refuses to guess a default):

```bash
export PHONE_STEWARD_STATE_DIR=/var/lib/phone-steward
PHONE_STEWARD_PORT=7461 node --experimental-strip-types src/main.ts
```

For a machine that restarts, use the systemd template in `systemd/`: copy
`phone-steward.service` to `/etc/systemd/system/`, set your checkout path in
`WorkingDirectory`/`ExecStart`, review the `Environment=` lines, then
`systemctl daemon-reload && systemctl enable --now phone-steward`. A nightly SQLite
backup unit (`phone-steward-backup.{service,timer}`) sits beside it.

## Pairing the phone

1. On the server, with `PHONE_STEWARD_STATE_DIR` exported to the same value the service
   runs with, run `bin/steward pair`; it prints a code like `K7Q4-9MZD` (valid ten
   minutes, single use). Without the variable the CLI refuses, to avoid touching the
   wrong state directory.
2. On the phone, enter the code on the app's Pair screen.
3. The app gets a bearer token (stored in Android's Keystore-backed storage) and starts
   polling for jobs. `bin/steward status` shows the paired device and its permissions.

One device is paired at a time; pairing a new one revokes the old token. `bin/steward
revoke` un-pairs.

## The model key

The default provider is `zai` with model `glm-5.3-flash`; change both with
`PHONE_STEWARD_MODEL_PROVIDER` and `PHONE_STEWARD_MODEL_ID`. Other providers work if the
Pi coding agent's registry on the server host knows them (the server reads Pi's registry
read-only from `~/.pi/agent`, configurable with `PHONE_STEWARD_PI_DIR`).

The key reaches the server in one of three ways (checked in this order):

1. `PHONE_STEWARD_MODEL_KEY` — the key directly, in the service environment.
2. `<PROVIDER>_API_KEY` — for example `ZAI_API_KEY` (the variable the provider client
   reads).
3. `PHONE_STEWARD_KEY_FILE` — a file whose first line is the key.

`bin/steward-serve` (the systemd unit's `ExecStart`) resolves the key at start into its
own process environment and never writes it to a file, a log or the unit. Voice
(dictation and read-aloud) has its own optional keys: `OPENROUTER_API_KEY`,
`OPENAI_API_KEY`, `LOCAL_ASR_URL`.

## Every configuration value

| Name | Default | Meaning |
|---|---|---|
| `PHONE_STEWARD_STATE_DIR` | `/var/lib/phone-steward` | The state directory: SQLite file, event mirror, backups, notifications stub, releases. |
| `PHONE_STEWARD_HOST` | `127.0.0.1` | Bind address of the device API. |
| `PHONE_STEWARD_PORT` | `7461` | Port of the device API. |
| `PHONE_STEWARD_MODEL_PROVIDER` | `zai` | Model provider id (Pi's registry). |
| `PHONE_STEWARD_MODEL_ID` | `glm-5.3-flash` | Model id the conversation starts with. |
| `PHONE_STEWARD_MODEL_KEY` | — | Provider key (see above). |
| `PHONE_STEWARD_KEY_FILE` | — | File holding the provider key. |
| `PHONE_STEWARD_PI_DIR` | `~/.pi/agent` | Pi agent dir whose model registry/auth the server reads read-only. |
| `PHONE_STEWARD_COMPACT_AT` | `0.75` | Fraction of the model's window where background compaction starts (0.5–0.9). |
| `PHONE_STEWARD_TIME_ZONE` | `UTC` | IANA time zone for the calendar-date rules (photo ages, weekly run ids): `Europe/London`, `Pacific/Auckland`, … |
| `PHONE_STEWARD_PYTHON` | `python3` | Python interpreter for the web-tool helpers (`web_browse.py`, `ddgs_search.py`). |
| `PHONE_STEWARD_JOURNALCTL` | `journalctl` | `journalctl` binary the CLI's `logs` command shells out to. |
| `PHONE_STEWARD_DEFAULT_STATE_DIR` | `/var/lib/phone-steward` | What the CLI falls back to when `PHONE_STEWARD_STATE_DIR` is unset (read only by `bin/steward`; the CLI normally refuses to guess). |
| `PHONE_STEWARD_AAPT2` | — | Full path to `aapt2` for `bin/ship-apk`; otherwise found under `$ANDROID_HOME/build-tools`. |
| `PHONE_STEWARD_CRASH_GATES` | — | **Test-only.** Lets a test process arm the crash-test gates; never set it in production. |
| `PHONE_STEWARD_SCHEDULES` | `off` | Weekly check and photo-window schedules. |
| `PHONE_STEWARD_TRUSTED_PROXIES` | — | Comma-separated proxy ranges whose `X-Forwarded-For` the pairing rate limit trusts (loopback always). |
| `PHONE_STEWARD_RELEASE_DIR` | `<stateDir>/releases` | Where `bin/ship-apk` puts the APK + `release.json` for the self-update flow. |
| `PHONE_STEWARD_NOTIFY_CMD` | — | Owner-notification command (`<cmd> milestone <title> <body>`). Unset: the JSONL stub (below). |
| `PHONE_STEWARD_ORCHESTRATION` | `off` | The external orchestration seam (presence, memory, sessions). |
| `PHONE_STEWARD_ORCHESTRATION_CMD` | — | The orchestration executable (see "Bring your own orchestration"). |
| `PHONE_STEWARD_ORCHESTRATION_MEMORY_ROOT` | — | Passed to it as `STEWARD_MEMORY_ROOT`. |
| `PHONE_STEWARD_ORCHESTRATION_BOARD_DIR` | — | Passed to it as `STEWARD_BOARD_DIR`. |
| `PHONE_STEWARD_WORKLOG_DIR` | `<stateDir>/worklog` | The local JSONL event mirror directory. |
| `OPENROUTER_API_KEY`, `OPENAI_API_KEY`, `LOCAL_ASR_URL` | — | Voice provider keys; the routes answer 503 without them. |

## What to edit, and what to delete if you do not need it

- **Web tools** (`web_search`, `web_read`, `web_browse`): `web_search` needs the `ddgs`
  package (`python3 -m pip install ddgs` or your package manager); `web_browse` needs
  Python 3 with Playwright and a Chromium (`python3 -m pip install playwright` then
  `python3 -m playwright install chromium`). Without them the other two tools still work;
  `web_browse` reports that its helper is missing. Point `PHONE_STEWARD_PYTHON` at the
  interpreter that has them.
- **Voice** (dictation and read aloud): leave the keys unset and the Chat microphone
  never appears; nothing else changes. To remove it outright, delete `server/src/voice/`,
  `app/src/main/java/.../voice/` and their tests, and drop the routes from
  `server/src/http.ts`.
- **Self-update** (the app updating itself from the server): don't run `bin/ship-apk`
  and nothing is served; the app's Update section stays quiet. To remove it, see
  `app/src/main/java/.../update/` and `server/src/release.ts`.
- **Phone use** (the steward operating the screen): it only starts when the owner allows
  a request in Chat, and needs the app's Accessibility service switched on. Ignore it and
  nothing runs. The guard lists live in the app's Rules screen.
- **Schedules** (weekly check, photo window): `PHONE_STEWARD_SCHEDULES=off` keeps the
  agent purely conversational; proposals then exist only when you ask.
- **Firebase Test Lab**: optional; see `docs/guide/firebase.md`.

## Bring your own orchestration and memory

The steward integrates with an external "orchestration" tool — a presence board, a memory
store, other agents' session records — through one seam, off by default. With it off, the
server still runs everything: the seam only adds cross-agent awareness.

**The local stub (always on):** the steward mirrors every conversation into append-only
JSONL files under `<stateDir>/worklog/` (line shapes in `docs/protocol.md` §10), appends
owner notifications to `<stateDir>/notifications.jsonl` when no notify command is set,
and reads a project-memory brief you can write to `<stateDir>/memory/project.md` (plain
markdown, capped at 20 000 characters).

**The external seam:** set `PHONE_STEWARD_ORCHESTRATION=on` and
`PHONE_STEWARD_ORCHESTRATION_CMD=/path/to/your-tool`. The server then calls your tool,
never with a shell, always with fixed argument shapes:

- Presence (start/end of every agent run): `your-tool presence update --step "<one line>"
  --status working|waiting --name phone-steward --join-session steward-main`
- Capture (a durable outcome worth remembering, idempotent):
  `your-tool memory capture --note "<text>" --idempotency-key <16 hex>`
- Read tools the model may call (all read-only, output labelled untrusted):
  - `your-tool memory recall "<query>" --no-log`
  - `your-tool memory find "<question>" --limit 8`
  - `your-tool memory survey overview|identity|projects|work`
  - `your-tool sessions find --words "<query>" --limit 12`
  - `your-tool sessions handoff <ref> --chars 4000`
  - `your-tool sessions log <ref> [--item N | --grep Q] --chars 6000`
  - `your-tool presence board`

Every call carries `STEWARD_MEMORY_ROOT` and `STEWARD_BOARD_DIR` from the configuration,
and a 10 s timeout (20 s for the read tools). A failed or timed-out write-side call is
queued in a durable outbox and retried with backoff; a run never fails because the
external tool is down. Free text lands only in value slots; the argument shapes are
frozen and re-checked before every call.

**Files to edit if you want a richer integration:** the seam types live in
`server/src/integrations.ts` (`StewardIntegrations`, `StewardEvent`), the external caller
in `server/src/orchestration/client.ts`, the read tools in
`server/src/orchestration-tools.ts`, and the wiring in `server/src/wiring.ts`. The
agent-facing tool names are generic (`memory_recall`, `session_handoff`, `presence_board`)
and the steward's system prompt names them in `server/src/prompt-posture.ts`.

## Putting the server behind HTTPS

The API is plain HTTP on loopback by default; the phone should reach it only over HTTPS.
A reverse proxy that terminates TLS is enough:

```
# Caddy example — a valid certificate automatically (edit the domain)
steward.example.com {
    reverse_proxy 127.0.0.1:7461
}
```

```
# nginx example — bring your own certificate
server {
    listen 443 ssl;
    server_name steward.example.com;
    ssl_certificate     /etc/letsencrypt/live/steward.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/steward.example.com/privkey.pem;
    location / {
        proxy_pass http://127.0.0.1:7461;
        proxy_set_header X-Forwarded-For $remote_addr;
    }
}
```

Then set `PHONE_STEWARD_TRUSTED_PROXIES` to the proxy's addresses so the pairing rate
limit reads the real client address from `X-Forwarded-For` (loopback is always trusted).
Point the app at `https://steward.example.com` when you pair (the app refuses cleartext
to anything but loopback and the Android emulator host `10.0.2.2`).

## A prompt for your coding agent

Paste this to your own coding agent to adapt the repo to your host:

```text
Work in this repository (Phone Steward: server/ is a Node 24 TypeScript durable agent on
Pi Durable; app/ is a Kotlin Android app). Read AGENTS.md, README.md and docs/ADOPTING.md
first; docs/protocol.md is the server↔phone contract.

Adapt it to my host:
1. Pick the application id <MY_APP_ID> and rename com.example.phonesteward everywhere it
   appears (Kotlin packages and directories, manifests, app/build.gradle.kts, the
   STEWARD_PACKAGE constants in server/src/shizuku.ts and server/src/live/state.ts, and
   tests), so server and app agree.
2. Configure for my host: state directory <MY_STATE_DIR>, bind <MY_HOST:PORT>, model
   provider <MY_PROVIDER> and model <MY_MODEL>, the key from <MY_KEY_ENV_OR_FILE>.
3. Wire my notification command <MY_NOTIFY_CMD> (it is called as
   `<cmd> milestone <title> <body>`); keep the JSONL stub as the fallback.
4. Decide with me whether to switch on PHONE_STEWARD_ORCHESTRATION; if I have no
   orchestration tool, leave it off.
5. Update systemd/phone-steward.service with my paths.
6. Validate: cd server && npm ci && npm test && npm run typecheck; cd ../app &&
   ANDROID_HOME=<MY_SDK> ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug.
   Both must be green before you call anything done. Commit with plain messages; do not
   push anywhere.
```

## Optional: Test Lab

`docs/guide/firebase.md` covers running the full instrumentation suite (screenshots,
phone-use flows, self-update) on real Android images, with your own project and bucket.
