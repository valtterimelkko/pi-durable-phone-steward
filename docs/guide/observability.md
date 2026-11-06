# Observability and debugging runbook

How to answer "what is happening on the server and the phone" without touching the state
directory of a running service. Everything here is read-only unless marked **acts**; this
is the command-by-command reference.

## Point the CLI at an instance first

```bash
export PHONE_STEWARD_STATE_DIR=/var/lib/phone-steward   # production (service running)
export PHONE_STEWARD_STATE_DIR=/tmp/…/state             # a disposable instance
```

- A **running** instance is reached only through its admin Unix socket
  (`<stateDir>/admin.sock`); the CLI never opens the SQLite file while the service owns it.
- A **stopped** instance is read by opening the SQLite file directly — only ever with an
  explicit `PHONE_STEWARD_STATE_DIR` (the CLI refuses without one, so it can never guess
  its way onto production). A direct-state reader never resumes the stopped service's tasks.
- Every `--json` answer except `logs` names the path taken in `via`: `admin-socket` or
  `direct-state`. Check it before you trust an answer. (`logs` reads the journal, not the
  instance, and answers `{"command":"logs","lines":[…]}`.)
- On this host the built-in default is `/var/lib/phone-steward` (production). With no
  `PHONE_STEWARD_STATE_DIR` set and production running, the CLI answers through
  production's admin socket.

## The decision guide

| You notice… | Run first | Follow up with |
|---|---|---|
| "The app crashed" | `bin/steward crashes` | `journalctl -u phone-steward -n 200` if the server also looks unwell |
| "A button did nothing" (no crash; the app reports swallowed exceptions, A5 `severity: "error"`) | `bin/steward crashes --errors` | the `site` label names where in the app it happened (a button such as `status.battery_button`, or a code path such as `poll.loop` or `exec.storage_overview.apps`) |
| "The app behaves oddly" (permissions, polling, background work) | `bin/steward diag` | `bin/steward job <jobId>` for the full stored result |
| "Why did the agent say / propose / do X?" | `bin/steward chat --last 100` | `bin/steward job <jobId>` for the phone's full answer |
| "What is on the phone / how full is it?" | `bin/steward snapshot` | `bin/steward proposals --all` for what was proposed from it |
| "How much is the agent using?" | `bin/steward usage` (model tokens per day; money is not measured) | — |
| "The server looks unwell" | `bin/steward doctor` | `bin/steward logs --lines 200`; `bin/steward status` |
| "Is the phone still paired and polling?" | `bin/steward status` (`device.lastSeenMs`) | `bin/steward jobs` for jobs stuck in `queued` |
| "What rules are standing?" | `bin/steward rules` | — |

## Command reference

Flags are exactly as `bin/steward` prints in its usage text. Plain output for humans;
`--json` for scripts. With no argument or `help` it prints usage and exits 2.

### Reads (safe against a running instance)

The shapes below are what a **running** instance answers (`via: admin-socket`). A stopped
instance read in direct-state mode answers less: `status` gives only `devicePaired`,
`pendingJobs` and `openProposals` (no device, last-seen time or permissions), and `jobs`
omits `params`. To diagnose the phone, read a running instance.

- **`status`** — one overview: server time, the paired device with its last-seen time and
  permissions, pending jobs, open proposals, the latest snapshot time, the model.
  `"device":{"deviceId":"dev_…","lastSeenMs":…,"permissions":{"usageAccess":true,"allFiles":true,"mediaRead":true,"manageMedia":false,"batteryUnrestricted":true}}`
- **`jobs`** — every job: `jobId`, `kind`, `params`, `createdAtMs`, `expiresAtMs`, `status`.
- **`job <jobId>`** — one job's params, status and the phone's full stored result (truncated
  only in `chat`, never here). Unknown id → `[steward] unknown job …`, exit 1.
- **`proposals [--all]`** — open proposals (or all with `--all`): `proposalId`, `status`,
  `title`, `bytesEstimate`.
- **`rules`** — the standing rules document: `ruleVersion`, `photoWindow`
  (`enabled`, `reminderDays`, `cutoffDays`, `autoMode`, `folders`, `keepMarker`),
  `batchesRunOneTap`.
- **`chat [--last N]`** — the conversation in order, oldest last, newest N lines
  (default 20). `lines` are typed objects: `message` (`role` owner/agent, `text`),
  `tool_call` (`name`, `arguments` truncated at 300 chars), `tool_result` (`name`,
  `isError`), `phone_result` (`jobId`, `kind`, `status`, `data` truncated at 400 chars),
  `phone_expired`. Thinking is omitted. Diagnostics results never appear here (A6).
- **`snapshot`** — the latest storage snapshot, summarised: total/free bytes, top apps by
  size and by cache, media categories. `{"status":"no-snapshot"}` before the first weekly check.
- **`usage`** — model tokens per day: `days:[{"day":"2026-10-07","inputTokens":…,"outputTokens":…,"cacheReadTokens":…,"calls":…}]`.
- **`crashes [--errors|--crashes]`** — the phone's crash and error reports, newest first
  (the server keeps the newest 50): `reportId`, `receivedAtMs`, `appVersion`,
  `crashedAtMs`, `thread`, `exception`, `stackTrace`, `severity` (`crash` default),
  `site` (errors only), `device` (`model`, `sdk`). The flags narrow to one severity;
  without a flag both are listed.
- **`doctor`** — health checks. Running instance: `ok: true` and a note. Stopped: `ok`
  computed from `checks` (`stateDirExists`, `storageOpened`, `devicePaired`, `ruleVersion`,
  `photoWindowEnabled`, `jobCount`, `openProposals`).
- **`logs [--lines N]`** — the unit journal read-only (`journalctl -u phone-steward -n N
  --no-pager`; default 200). Works with the instance running or stopped, and never opens
  the state directory. `--json` wraps the lines: `{"command":"logs","lines":[…]}` (no `via`).

### Actions (these act on the instance you point at)

Every command in this section refuses to run unless `PHONE_STEWARD_STATE_DIR` is set explicitly, so it can never act on production by default. For production, set `PHONE_STEWARD_STATE_DIR=/var/lib/phone-steward` deliberately.

- **`pair`** **(acts)** — prints a one-time pairing code (10 minutes): `code`, `expiresAtMs`.
  The owner types it into the app.
- **`revoke`** **(acts)** — revokes the paired device's token: `revoked: dev_…`. The phone
  must pair again.
- **`selftest`** **(acts)** — queues a `phone_selftest` job: `queued: true`, `jobId`. The
  phone runs its scripted self-checks on its next poll.
- **`diag [--wait S]`** **(acts)** — queues `phone_diagnostics` and polls until the phone
  reports or the wait ends (default 60 s). Printed job: `jobId`, `kind`, `params: {}`,
  `status`, and `result` — app version and versionCode, permissions as the app sees them,
  WorkManager state (`ENQUEUED`/`RUNNING`/…/`NONE`), outbox counts, and up to 200 in-app
  log lines (`atMs`, `level`, `event`, `detail` ≤ 200 chars; no file names, media metadata
  or tokens — the server keeps only the A6 fields and redacts path-like, file-name-like and
  token-like detail). If the phone has not answered in time:
  `[steward] no diagnostics result within Ns (job pj-…); try bin/steward job pj-… later`,
  exit 1.
- **`say "<text>"`** **(acts)** — posts an owner chat message to the running instance through
  `POST /admin/chat`, the same path as the phone's `POST /v1/chat`. The server prefixes the
  text with `[via server] `, so `chat` shows it did not come from the phone. Prints
  `accepted: true`, `requestId` (`say-<uuid>`); the steward answers like any owner message.
  Needs a running instance (a stopped one gets `[steward] the server is not running …`,
  exit 1); a missing text prints usage, exit 2. It wakes the real model, so use it
  deliberately, and on a live instance only when you mean it.
- **`run weekly-check|photo-window`** **(acts)** — runs a rule task now on a running
  instance: `queued: true`, `runId`. Refused against a stopped instance.

- **`compact`** **(acts)** — A10.9: compacts the steward's model context now, with the
  steward's summary instructions. Admin route `POST /admin/compact`; prints `accepted: true`.
  Needs a running instance and a quiet steward (a run in progress: exit 1,
  `the server refused 'compact' (HTTP 409): … idle`). The summary is a model call: on
  a live instance only when you mean it. The chat log is untouched.
- **`fresh`** **(acts)** — A10.9: starts the model context fresh from a state brief built in
  code from the server's records (compacts first when it can, so the owner's stated preferences and
  open threads carry over). Same rules as `compact`. Also happens automatically before each
  weekly check. The app draws a divider (`context` chat event); the chat log keeps every
  message.
- **`backup`** **(acts, writes only under `<state>/backups/`)** — S3: SQLite online backup of
  `steward.sqlite` into `<state>/backups/steward-YYYYMMDD-HHMMSS.sqlite` (UTC), keeping the
  newest seven. Safe while the service runs (read-only source, the backup API), and needs no
  running instance. Prints `path`, `bytes`, `kept`, `pruned`. The nightly timer
  (`systemd/phone-steward-backup.timer`, 03:30) runs the same command. To restore: stop the
  service, copy the chosen file over `steward.sqlite` (remove any `steward.sqlite-wal` and
  `-shm`), start the service.

### Exit codes worth knowing

| Situation | Output | Exit |
|---|---|---|
| No argument, `help`, or unknown command | usage text | 2 |
| Running-only command (`diag`, `run`, `selftest`, `say`, `compact`, `fresh`) against a stopped instance | `[steward] the server is not running (no <admin.sock>); '<cmd>' needs the running instance` | 1 |
| Stopped instance reached without an explicit `PHONE_STEWARD_STATE_DIR` | `[steward] refusing to guess the state: set PHONE_STEWARD_STATE_DIR explicitly for direct-state mode, …` | 1 |
| `diag` wait elapsed | `[steward] no diagnostics result within Ns (job pj-…); try bin/steward job pj-… later` | 1 |
| Unknown `job <id>` | `[steward] unknown job pj-…` | 1 |

## The request log line (O4)

One structured line per HTTP request goes to stderr, so it lands in the systemd journal:

```text
[steward] http GET /v1/jobs 200 12ms device=dev_f069dd7601ab
```

Method, matched route template (never the raw path or query — path segments are
caller-controlled), status, duration, attributed device id or `-` (admin and unauthenticated
requests). Never a body, a token or a file name. Read it with
`journalctl -u phone-steward -n 200` or `bin/steward logs`.

## Crash and error reports (A5, O5)

- The app POSTs its own uncaught exception to `/v1/crash` on the next start. No token is
  required (a crash can precede pairing); a valid bearer only attributes the report.
- Limits: body 64 KiB, `stackTrace` 32,768 chars, `exception` 512, `thread` 128; the server
  keeps the newest 50 reports. Rate limits: 10 accepted reports per hour per client
  address, 60 per hour in total (`429 RATE_LIMITED` over either).
- A6 added `severity`: `"crash"` (default) or `"error"` — a non-fatal exception the app
  caught and hid, with a `site` label (`status.battery_button`, `stream.reconnect`, …).
  The app sends at most one error report per site, exception class and app version per day.
- The notify seam sends one ping per exception class and app version, for `crash`
  reports only, naming the class and version — never the stack trace or the message.
  Crash reports never reach the event mirror or the model.

## Self-update (A7, F1)

When "I pressed Update and nothing happened", read the phone's event log first:
`bin/steward diag` (the `log` list), then `bin/steward crashes --errors` for `update.*` sites.

| Event (A6 log) | Detail | Meaning |
|---|---|---|
| `update_check` | `available latest=5 installed=4`, `up_to_date …`, `no_release …`, `unreachable`, `failed <code>` | the app asked `GET /v1/app/latest` (on opening Status) |
| `update_download` | `start code=5 bytes=…`, `done bytes=…`, `interrupted`, `release_changed`, `failed <code>` | the APK fetch over the paired client |
| `update_verified` | `ok code=5` | SHA-256, package name, versionCode and signing certificate all matched |
| `update_refused` | `sha256_mismatch`, `wrong_package`, `wrong_version`, `signer_mismatch`, `unreadable`, `too_large` | a check failed; nothing was installed, the file was deleted |
| `update_install` | `needs_unknown_sources`, `committed`, `STATUS_PENDING_USER_ACTION`, `STATUS_FAILURE_ABORTED` (the owner pressed Cancel), `STATUS_FAILURE_*`, `success` | the install session and Android's answers; `success` is logged by the new version on its first start |

- Error reports (`bin/steward crashes --errors`): site `update.check` (server answered an error),
  `update.download`, `update.verify` (every refusal, message `update refused: <reason>`) and
  `update.install` (a commit failure or a `STATUS_FAILURE_*` other than the owner's Cancel).
  Offline checks and interrupted downloads are logged but not reported.
- Reasons never carry a path or file name. The server side shows as `GET /v1/app/latest` and
  `GET /v1/app/apk` in the request log (route templates only; the query is never logged).
- Server answers: `404 NO_RELEASE` (no valid `release.json` or APK in `PHONE_STEWARD_RELEASE_DIR`),
  `500 RELEASE_CORRUPT` (the APK's size or SHA-256 differs from `release.json`: re-run
  `bin/ship-apk`), `409 RELEASE_CHANGED` (the asked `versionCode` is not the current release).

## Reading production safely

1. `export PHONE_STEWARD_STATE_DIR=/var/lib/phone-steward`.
2. Run the read command you need (`status`, `jobs`, `job`, `proposals`, `rules`, `chat`,
   `snapshot`, `usage`, `crashes`, `doctor`, `logs`). With the service running, each answer
   comes through the admin socket; `--json …via` confirms `admin-socket`.
3. Never open the SQLite file yourself while the service runs (one process owns it).
   Direct state access is for a stopped instance only, and the CLI's direct-state mode
   never resumes tasks (`resume: false`).

## What never to do

- No writes to a live instance's state: no `sqlite3` writes against the open database, no
  schema pokes, no deletes. The CLI's acting commands are `pair`, `revoke`, `selftest`,
  `run`, `diag`, `say`, `compact`, `fresh` and `backup`; every one refuses the default
  state dir, and `backup` writes only under `<state>/backups/` — point them deliberately.
- No `systemctl restart phone-steward` while the service is in use without deciding it is
  safe first; the same care applies to reverse-proxy changes and APK distribution.
- No test, probe or disposable run against a directory a live service uses; disposable
  instances use a scratch state dir (see `disposable-instance.md` in this folder).
- `bin/steward logs` and `journalctl -u phone-steward` are always safe: read-only, and they
  never touch the state directory.
