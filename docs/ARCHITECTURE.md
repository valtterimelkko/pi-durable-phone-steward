# Architecture

## Components

```
┌────────────────────────────┐         HTTPS (bearer token)        ┌──────────────────────────────┐
│ Android app (Kotlin)       │  ── facts, polls, results, chat ──▶  │ Server (Node 24, TypeScript) │
│ com.example.phonesteward   │  ◀─ jobs, approvals, SSE stream ──  │ Pi Durable agent on SQLite   │
└────────────────────────────┘                                      └──────────────┬───────────────┘
        the phone's "door": facts, executions,                          │  │  │
        confirmations, notifications                                    │  │  └─ model provider API
                                                                        │  └──── external orchestration seam
                                                                        └─────── owner notify seam
```

- **`server/`** — one Node process owning a SQLite database through
  [Pi Durable](https://pi.dev) (durable tasks, documents, conversations). It exposes:
  - the **device API** (`/v1/*`): pairing, jobs, proposals and decisions, rules, chat
    (SSE stream + history), device status, agent settings, voice, the app-release
    endpoint;
  - an **admin Unix socket** (`<stateDir>/admin.sock`) that only `bin/steward` uses;
  - the **model conversation**: the steward agent, its system prompt, and its tool set
    (phone tools queue jobs; read-only server tools; the web tools; memory/session read
    tools when orchestration is on).
- **`app/`** — a Kotlin + Jetpack Compose app. It polls for jobs, gathers facts
  (storage, app usage, media index, files), executes approved proposals under Android's
  own confirmations, runs the phone-use sessions (accessibility service), and shows the
  chat, proposals and rules screens.
- **`bin/`** — `bin/steward` (the CLI: pair, status, chat, proposals, backup, …),
  `bin/steward-serve` (the systemd `ExecStart`), `bin/ship-apk` (release publishing),
  `bin/testlab-run` (optional device-farm runs).

## Data flow (the life of a clean-up)

1. **Facts.** A weekly check (or you, in Chat) makes the agent call a phone tool such as
   `phone_storage_overview`. The tool *queues a job* in SQLite and returns at once; the
   phone picks the job up on its next poll, runs it, and posts the result. The result
   enters the conversation as a compact summary; the full payload stays in the jobs
   document and the agent can page through it with `phone_result`.
2. **Proposal.** The agent writes a proposal with `propose_cleanup` (media to trash,
   caches to clear, apps to uninstall, files to delete, advice). Every target is
   completed and checked by the server from the phone's own reported index — the model
   cannot invent a target.
3. **Approval.** The proposal appears in the app. The owner ticks items and approves;
   the server records the decision and issues a decision id valid for 7 days.
4. **Authority check.** Before executing, the app re-checks the decision with the server
   (`GET /v1/decisions/{id}`) and re-checks each item against the live phone row
   (unchanged size/date, not now protected).
5. **Execution.** The app performs each item under Android's own dialogs (trash dialog,
   uninstall dialog, one clear-all-caches confirmation), media going to Android's trash
   first and files to a `.StewardTrash/` folder kept for 30 days.
6. **Report + review.** The app posts an execution report; the server records per-item
   outcomes in the decisions ledger, runs a deterministic review (estimated bytes against
   bytes actually freed), and the app's screens and the chat update.

Nothing executes without a live, server-issued decision; nothing is proposed about a
protected item; and the model never holds an execution capability at all (its tools only
queue and read).

## State on disk

Everything lives under `PHONE_STEWARD_STATE_DIR` (default `/var/lib/phone-steward`):

| Path | What |
|---|---|
| `steward.sqlite` | The whole durable state: conversations, jobs, proposals, decisions, rules, device records, notifications ledger, the outbox. |
| `worklog/*.jsonl` | The append-only event mirror (one file per conversation, `docs/protocol.md` §10). |
| `notifications.jsonl` | The notify stub's record of pings (when no notify command is configured). |
| `memory/project.md` | Your project brief the state brief includes (optional). |
| `backups/` | Nightly SQLite online backups (newest seven) from the backup timer. |
| `releases/` | `bin/ship-apk` output: the current APK, `release.json`, `archive/` of older ones. |
| `admin.sock` | The admin Unix socket for the CLI. |

The repository itself holds no state; `.gitignore` excludes state, builds, keystores and
local SDK configuration.

## Durability and restarts

- **One writer.** Only the server process opens the SQLite file for writing; the CLI
  reads through the admin socket, or directly (read-only) when the service is stopped.
- **Queues are durable.** A phone job stays `queued` until its result is accepted;
  delivery is at-least-once and results are idempotent (`requestId` + canonical JSON
  equality, conflicts refused). A queued job that is never reported expires after 7 days.
- **Tasks resume.** Pi Durable tasks (jobs, notifications, compaction, schedules, the
  outbox pump) are journaled; `harness.resume()` at start-up continues whatever a crash
  interrupted. Crash tests SIGKILL the process at every gate and prove the recovery.
- **At-most-once notifications.** The durable mark lands before the send, so a crash
  never duplicates a ping.
- **External effects ride an outbox.** Orchestration calls are enqueued inside the commit
  that caused the event and delivered by a background task with backoff; "external tool
  down" means entries wait, never a failed run.
- **Graceful shutdown.** SIGTERM closes streams and connections, stops schedulers and
  exits within 5 s; systemd restarts on failure.
- **Compaction, not hoarding.** The conversation compacts at a share of the model's
  window; a fresh start before each weekly check rebuilds context from the server's
  records (proposals, decisions, snapshots, rules, the project memory file), so the
  agent's memory is its records, not a chain of summaries.
