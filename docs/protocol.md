# Phone Steward — protocol reference

This is the contract between the server and the Android app: the HTTP API, the server-side
model tools, the external orchestration calls and the event mirror. Both halves of the code
are built and tested against this file. Changes are recorded as numbered amendments at the
bottom, and older sections keep their original wording.

## 1. Transport

- JSON over HTTPS, UTF-8, base path `/v1`.
  - A typical deployment is `https://steward.example.com`, through a reverse proxy to `127.0.0.1:7461`.
  - Tests and disposable instances use plain HTTP on loopback, with a temporary port.
- Every request except `POST /v1/pair` and `POST /v1/crash` carries `Authorization: Bearer <deviceToken>`.
- Request bodies are at most **2 MiB**. A bigger body gets `413 BODY_TOO_LARGE`.
- Every error has one shape:

  ```json
  {"error": {"code": "UPPER_SNAKE", "message": "human text", "retryAfterSeconds": 30}}
  ```

  `retryAfterSeconds` is present only with `429` and `503`. Those responses also send a `Retry-After` header.
- Times are epoch milliseconds (`…Ms` fields). Sizes are bytes (`…Bytes` fields). Ids are opaque strings: the app never parses them.
- Every mutating `POST` carries a client `requestId` (1–128 characters). A repeat with the same `requestId` and body returns the first response with `"duplicate": true`. A repeat with the same `requestId` and a different body gets `409 REQUEST_ID_CONFLICT`.

Error codes used everywhere:

| Code | Status |
|---|---|
| `UNAUTHORIZED` | 401 |
| `TOKEN_REVOKED` | 401 |
| `NOT_FOUND` | 404 |
| `RATE_LIMITED` | 429 |
| `BAD_REQUEST` | 400 (the `message` names the field) |
| `NOT_IMPLEMENTED` | 501 |

## 2. Pairing and auth

`POST /v1/pair` takes `{"code": "K7Q4-9MZD", "deviceName": "Pixel 8"}`. It answers `200 {"deviceId": "dev_…", "token": "<43 chars base64url>"}`.

**Codes:**
- `bin/steward pair` prints a code: 8 characters from `ABCDEFGHJKMNPQRSTUVWXYZ23456789`, shown as `XXXX-XXXX`. The server ignores case and hyphens.
- A code is single use and valid for 10 minutes.
- An expired, used or wrong code gets `400 PAIRING_CODE_INVALID`. The response does not say which.
- Rate limit: 5 failed attempts per 10 minutes per client address, then `429 RATE_LIMITED`.

**Tokens:**
- A token is 32 random bytes in base64url. The server stores only its SHA-256.
- The app keeps the token in Keystore-backed storage.
- One paired device in the MVP: pairing a new device revokes the old token.
- `bin/steward revoke` revokes the current token. Its next request gets `401 TOKEN_REVOKED`, and the app returns to its pairing screen.

## 3. Jobs (phone work)

### 3.1 Fetching

`GET /v1/jobs?wait=25` returns every job in `queued` state for this device, oldest first: `{"jobs": [Job, …]}`.

- If none are queued, the server holds the request up to `wait` seconds (0–30; default 0), then answers with `{"jobs": []}`.

```json
{
  "jobId": "pj-…",
  "kind": "storage_overview",
  "params": {},
  "createdAtMs": 1791300000000,
  "expiresAtMs": 1791904800000
}
```

**Delivery is at least once.**
- A job stays `queued` until a result is accepted, so the app sees it again on every fetch.
- The app keeps a local set of in-flight and finished `jobId`s:
  - it never starts a second execution of a job it is already running;
  - for a job it already finished, it re-posts the stored result.
- A job not reported by `expiresAtMs` (7 days) becomes `expired`.
  - The server delivers it no more.
  - It tells the conversation once, in the same follow-up way as a result.

**Kinds in the MVP:**

| `kind` | Queued by | `params` |
|---|---|---|
| `storage_overview` | tool `phone_storage_overview`; `WeeklyCheckTask` | `{}` |
| `app_usage` | tool `phone_app_usage`; `WeeklyCheckTask` | `{}` |
| `media_index` | tool `phone_media_index`; `WeeklyCheckTask`; `PhotoWindowTask` | `{"mode": "summary" \| "rule_candidates", "folders": ["DCIM/Camera/", …], "olderThanDays": 90}`. `olderThanDays` applies only with `rule_candidates` |
| `phone_selftest` | `bin/steward selftest` (the owner or parent asks) | `{"checks": ["trash_dialog", "manage_media_skip", "trash_restorable", "clear_cache_intent", "workmanager_ran"]}` |
| `rule_batch` | `PhotoWindowTask` in **auto mode only** | `{"batchId": "rb-…", "ruleVersion": 3, "items": [MediaRef, …]}` |

Reserved for later:
- kinds starting `live_` (§9);
- `uninstall_app` and `clear_app_cache` (these run from proposals, §4, never as jobs).

The app answers an unknown kind with an `error` result whose code is `UNKNOWN_KIND`.

### 3.2 Reporting

`POST /v1/jobs/{jobId}/result`:

```json
{
  "requestId": "res-<jobId>",
  "status": "ok",
  "data": {},
  "error": null,
  "deviceTimeMs": 1791300100000,
  "appVersion": "0.2.0"
}
```

- `status` is `ok`, `error` or `partial`. With `error` or `partial`, `error` is `{"code": "…", "message": "…"}`.
- The answer is `200 {"accepted": true, "duplicate": false}`.

**One job, one result. The first accepted report wins.** (This settles the AR1 question.)
- A later report with the **same** `status` and `data` gets `200 {"accepted": true, "duplicate": true}`. Nothing changes.
- A later report with a **different** `status` or `data` gets `409 RESULT_CONFLICT`. Nothing changes.
  - The server increments `conflictingReports` on the job and logs one line.
  - No second follow-up ever reaches the conversation.
- The app treats `409 RESULT_CONFLICT` as final: it drops the result from its retry queue.
- "Same" means canonical JSON equality: keys sorted, no whitespace.
- An unknown job, or a job of another device, gets `404 NOT_FOUND`. An `expired` job gets `409 JOB_EXPIRED`.
- Each job has exactly one reporter task (`reporterTaskId`). The follow-up into the conversation uses `requestId: "phonejob:<jobId>"`.

### 3.3 Result `data` by kind

**`storage_overview`:**

```json
{"totalBytes": 0, "freeBytes": 0,
 "apps": [{"packageName": "com.whatsapp", "label": "WhatsApp", "appBytes": 0, "dataBytes": 0, "cacheBytes": 0, "isSystem": false}],
 "categories": {"imagesBytes": 0, "videoBytes": 0, "audioBytes": 0, "documentsBytes": 0, "appsBytes": 0, "systemBytes": 0, "otherBytes": 0}}
```

**`app_usage`:**

```json
{"apps": [{"packageName": "…", "label": "…", "lastUsedMs": 0, "installedMs": 0, "isSystem": false}]}
```

- `lastUsedMs` is `null` when Android has no record.

**`media_index`, mode `summary`:**

```json
{"scope": ["DCIM/Camera/", "Pictures/Screenshots/", "Android/media/com.whatsapp/", "Android/media/org.telegram.messenger/", "Download/"],
 "folders": [{"folderKey": "DCIM/Camera", "bytes": 0, "count": 0,
              "buckets": {"under3m": {"count": 0, "bytes": 0}, "m3to4": {"count": 0, "bytes": 0}, "over4m": {"count": 0, "bytes": 0}, "unknown": {"count": 0, "bytes": 0}}}],
 "largest": [MediaRef, …],
 "topLevelFiles": [{"name": "…", "sizeBytes": 0, "modifiedMs": 0}],
 "oldApks": [{"name": "…", "sizeBytes": 0, "modifiedMs": 0}]}
```

- `folders` covers **all** of shared storage.
- Age buckets are counted **only for folders inside `scope`**, which is the photo rule's folders. Folders outside it have `buckets: null`.
- `largest` lists at most 50 items. `topLevelFiles` lists every file directly in the root of shared storage.

**`media_index`, mode `rule_candidates`:** `{"scope": […], "items": [MediaRef, …]}`.
- It holds every image and video in `scope` older than `olderThanDays`, protected ones included, each marked.

**`MediaRef`, the metadata of one item:**

```json
{"mediaId": "external_primary:images:12345", "relativePath": "DCIM/Camera/", "displayName": "IMG_20260601_101010.jpg",
 "mimeType": "image/jpeg", "sizeBytes": 0, "dateTakenMs": 0, "dateAddedMs": 0,
 "isFavourite": false, "inKeep": false}
```

- `mediaId` is `<volume>:<images|video|files>:<MediaStore _ID>`. It never contains pixels or a hash.
- `inKeep` is true when any path segment of `relativePath` equals `Keep`, ignoring case, or the item sits in an album named Keep.
- **The phone always reports `isFavourite` and `inKeep`.** The server decides protection.
- Protection, enforced on **both** sides: an item is protected when `inKeep` is true, or `isFavourite` is true, or it was restored from an earlier batch (decisions ledger).
  - The server never puts a protected item into a batch.
  - The app re-checks `inKeep` and `isFavourite` on the live row before it executes anything.
  - If the item is now protected, its outcome is `skipped_protected`.

**`phone_selftest`:**

```json
{"checks": [{"id": "trash_dialog", "outcome": "pass", "resultCode": -1, "detail": "one dialog for 1 item", "atMs": 0}]}
```

- `outcome` is `pass`, `fail`, `cancelled` or `skipped`. `resultCode` is the Android activity result or `null`. `detail` is at most 200 characters.
- Every check acts **only on the app's own test images** (this app's own package, `Pictures/PhoneStewardTest/`, name prefix `phone-steward-test-`).

| Check | It passes when |
|---|---|
| `trash_dialog` | one system dialog was shown for a 2-item trash request and the result was OK |
| `manage_media_skip` | with `MANAGE_MEDIA` granted, a trash request returned OK with no dialog. It reports `skipped` if not granted |
| `trash_restorable` | the trashed test item is queryable with `QUERY_ARG_MATCH_TRASHED`, and an untrash request brings it back |
| `clear_cache_intent` | records what `ACTION_CLEAR_APP_CACHE` returned; informational, so it passes if it returned at all |
| `workmanager_ran` | a periodic worker has run at least once since install; `detail` gives the last run time |

**`rule_batch`:** `{"batchId": "rb-…", "items": [{"mediaId": "…", "outcome": "trashed" | "skipped_protected" | "missing" | "failed", "resultCode": null}]}`.

- Before running, the app checks `GET /v1/rules`. It must see `photoWindow.enabled`, `autoMode` and the same `ruleVersion`.
- Otherwise it reports `status: "error"` with code `RULE_CHANGED` and acts on nothing.

## 4. Proposals and decisions

`GET /v1/proposals?status=open|all` answers `{"proposals": [Proposal, …]}`:

```json
{"proposalId": "pr-…", "createdAtMs": 0, "source": "agent", "title": "…", "reason": "…",
 "bytesEstimate": 0, "status": "open",
 "items": [{"itemId": "it-…", "kind": "trash_media", "target": {}, "bytesEstimate": 0, "reason": "…"}]}
```

- `source` is `agent` or `rule_batch`. A one-tap photo batch is a proposal with `source: "rule_batch"`.
- `status` is `open`, `approved`, `rejected`, `executed`, `expired` or `superseded` (A6.2). A superseded proposal also carries `supersededBy` (the newer proposal's id) and `supersededAtMs`.
- `GET /v1/proposals` answers `open` ones by default; `?status=superseded` or `?status=all` show the others.

Item `kind` and its `target`:

| `kind` | `target` |
|---|---|
| `trash_media` | `MediaRef` |
| `uninstall_app` | `{"packageName": "…", "label": "…"}` |
| `clear_app_cache` | `{"packageName": "…", "label": "…"}`. The app opens that app's storage page in Settings |
| `advice` | `{"text": "…"}`. Nothing to execute |

**Decision:** `POST /v1/proposals/{id}/decision` with `{"requestId": "…", "decision": "approve" | "reject", "itemIds": ["it-…"]}`.
- `itemIds` is optional. If it is missing, the decision covers every item. Items the owner unticked are recorded as `kept` in the decisions ledger.
- Approve answers `200 {"decisionId": "dc-…", "approvedItemIds": […], "expiresAtMs": 0}`. A decision is valid for 7 days or until executed.
- Reject answers `200 {"decisionId": "dc-…", "approvedItemIds": []}`.
- A proposal that is not `open` gets `409 PROPOSAL_NOT_OPEN`. For a superseded one the message names the newer proposal.

**Authority check:** before acting, the app calls `GET /v1/decisions/{decisionId}`. The answer is `{"valid": true, "proposalId": "…", "approvedItemIds": […], "expiresAtMs": 0}`.
- The app acts only when `valid` is true, and only on `approvedItemIds`.
- An executor called without a valid decision id refuses.

**Execution report:** `POST /v1/proposals/{id}/execution` with:

```json
{"requestId": "…", "decisionId": "dc-…",
 "items": [{"itemId": "it-…", "outcome": "done" | "cancelled" | "failed" | "skipped_protected", "resultCode": null, "bytesFreed": null}]}
```

- It answers `200 {"accepted": true}`. The proposal becomes `executed`.
- The server's deterministic post-batch review (§3.8) compares `bytesFreed` with `bytesEstimate`.
- An owner restore found later in a scan is recorded in the ledger as `restored`. A restored item is protected from then on.

## 5. Rules

`GET /v1/rules` returns:

```json
{"ruleVersion": 3,
 "photoWindow": {"enabled": false, "reminderDays": 90, "cutoffDays": 120, "autoMode": false,
                 "folders": ["DCIM/Camera/", "Pictures/Screenshots/", "Android/media/com.whatsapp/", "Android/media/org.telegram.messenger/", "Download/"],
                 "keepMarker": "Keep"},
 "batchesRunOneTap": 0}
```

`PUT /v1/rules` takes `{"requestId": "…", "ruleVersion": 3, "photoWindow": {…}}`.
- It is optimistic: a stale `ruleVersion` gets `409 RULE_VERSION_CONFLICT`. Success answers with the new body and `ruleVersion + 1`.
- Validation: `reminderDays < cutoffDays`, `folders` is non-empty, `keepMarker` is non-empty. A failure gets `400 BAD_REQUEST`.
- `autoMode: true` while `batchesRunOneTap` is 0 gets `409 AUTO_MODE_NOT_YET`.
- Only the app can change rules. No tool offered to the model reaches this route.

## 6. Chat

**Conversation:** the MVP has one conversation, id `main`. The owner's messages, the weekly check and phone results all go into it.

**Send:** `POST /v1/chat` with `{"requestId": "…", "text": "…"}`. It answers `202 {"accepted": true, "conversationId": "main"}`.
- The `requestId` doubles as the Pi Durable input `requestId`, so a retried send never submits twice.

**Stream:** `GET /v1/chat/stream?after=<seq>` is Server-Sent Events. Every event has `id: <seq>`, a server-wide increasing integer.
- A reconnect sends `Last-Event-ID` or `after`, and missed events are replayed from the server's store.

| Event | `data` |
|---|---|
| `message` | `{"role": "user" \| "assistant", "text": "…", "atMs": 0}` |
| `tool` | `{"name": "…", "status": "started" \| "queued" \| "done" \| "error"}` |
| `proposal` | `{"proposalId": "…", "title": "…"}` |
| `run` | `{"status": "started" \| "ended", "reason": "owner" \| "weekly_check" \| "phone_result" \| "photo_window"}` |
| `heartbeat` | `{}`, every 20 s |

**History:** `GET /v1/chat/history?limit=50&before=<seq>` returns the same events as a JSON array.

## 7. Device status and agent settings

**Device status:** `POST /v1/device/status`, sent by the app on open and from its periodic worker:

```json
{"requestId": "…", "appVersion": "0.2.0",
 "permissions": {"usageAccess": true, "allFiles": true, "mediaRead": true, "manageMedia": false, "batteryUnrestricted": true},
 "lastWorkerRunMs": 0}
```

- It answers `200 {"accepted": true}`.
- The Status screen and `bin/steward status` show the last one.

**Status:** `GET /v1/status` returns:

```json
{"serverTimeMs": 0, "device": {"deviceId": "…", "lastSeenMs": 0, "permissions": {}},
 "pendingJobs": 0, "openProposals": 0, "lastSnapshotMs": 0,
 "agent": {"model": {"provider": "zai", "id": "glm-5.3-flash"}}}
```

**Agent settings:** `GET /v1/agent/settings` returns:

```json
{"conversationId": "main", "model": {"provider": "zai", "id": "glm-5.3-flash"},
 "allowlist": [{"provider": "zai", "id": "glm-5.3-flash", "metered": false}],
 "usage": {"inputTokens": 0, "outputTokens": 0}}
```

- The model lives in the conversation's agent settings, never in a global constant.
- `PUT /v1/agent/settings` is **reserved for the live-session work** (see §9). Before that it answers `501 NOT_IMPLEMENTED`.

## 8. Server-side tools and orchestration calls (not HTTP, but the same contract)

**Tools offered to the model** (§3.2), exactly these:
- `phone_storage_overview`, `phone_app_usage`, `phone_media_index`;
- `storage_trend`, `phone_snapshot`, `decisions`, `proposals` (A6.2), `rules`;
- `propose_cleanup`.

A test fails if any other tool is offered. Phone tools return `{"status": "queued", "jobId": "…"}` at once.
Amendment A10.7 grows this list (files, shell, web, memory and session tools); it stays an exact set.

**Orchestration calls** (the external seam, off by default; the subcommands below are the
contract a custom orchestration command implements — see docs/ADOPTING.md):
- **Presence:**
  - Run start: `<command> presence update --step "<one line>" --status working --name phone-steward --join-session steward-main`.
  - Run end: the same with `--status waiting` and the step `idle`. When a proposal waits for the owner, the step is `waiting for owner approval of <proposalId>`.
  - Re-sending the same `--name` with the same `--join-session steward-main` updates the card in place, across process restarts.
- **Capture:** `<command> memory capture --note "<text>" --idempotency-key <16 hex>`.
  - A repeat with the same key is a no-op: it creates no new evidence, and reports the existing capture. This makes an outbox replay after a crash harmless.
  - The capture builder drops file names, chat text and contact names. A redaction test covers it.
- **Read tools** (A10.6, as amended): `memory_recall`, `memory_find`, `memory_survey`, `session_find`, `session_handoff`, `session_log`, `presence_board` — fixed read-only subcommands of the same command, output capped and labelled as untrusted data. Off until `PHONE_STEWARD_ORCHESTRATION` is switched on; off, they answer that they are disabled.
- **Test isolation:** every call runs with `STEWARD_MEMORY_ROOT` and `STEWARD_BOARD_DIR` taken from the steward's config. Disposable instances and tests point them at scratch directories, **never a live store**.
- **Failure handling:** a call that fails or times out (10 s) stays in the outbox and is retried with backoff. A run never fails because the orchestration tool is down.
- **Local stub:** with orchestration off, the event mirror below still writes, and the steward records nothing externally.

## 9. Live session channel

- The path prefix `/v1/live/` and job kinds starting `live_` carry the phone-use session channel (long-poll on the same route and token).
- The phone-use session, its screen tools and the `phone_use_request` flow are specified in amendments A10.3 and A13.

## 10. Event mirror (the steward's JSONL session log)

The steward mirrors each conversation into an append-only JSONL file under the state
directory (`<stateDir>/worklog/` by default). An external orchestration tool can read it as
runtime `steward`; with none configured it is still a plain, inspectable log of what the
steward did.

**Location:**
- `<worklogDir>/<YYYY-MM-DDTHH-mm-ss-SSSZ>_<sessionUuid>.jsonl`, flat, with no sub-folders. Production `worklogDir` is `/var/lib/phone-steward/worklog/`.
- The mirror's directory comes from `PHONE_STEWARD_WORKLOG_DIR`, defaulting to that path. Tests override it.
- `sessionUuid` is a lowercase UUID that the steward mints once per conversation and stores in the conversation's own document, so a restart keeps it.
- The timestamp in the name is the session start.
- A reader's short ref for the session is `steward:<first 8 hex of sessionUuid>`.
- The file mode is `0600`.

**Lines:**
- One JSON object per line, ending in `\n`.
- Each line has a short unique `id` (8 lowercase hex) and a `parentId` (the previous line's `id`; the first entry after the header has `null`).

| Order | Line |
|---|---|
| 1 (header) | `{"type":"session","version":3,"id":"<sessionUuid>","timestamp":"<ISO>","cwd":"/opt/phone-steward","runtime":"steward"}` |
| at start and on every model change | `{"type":"model_change","id":"…","parentId":…,"timestamp":"<ISO>","provider":"zai","modelId":"glm-5.3-flash"}` |
| owner or system input | `{"type":"message","id":"…","parentId":"…","timestamp":"<ISO>","message":{"role":"user","content":[{"type":"text","text":"…"}],"timestamp":<ms>}}` |
| model output | `{"type":"message",…,"message":{"role":"assistant","content":[{"type":"text","text":"…"},{"type":"toolCall","id":"…","name":"phone_media_index","arguments":{…}}],"provider":"zai","model":"glm-5.3-flash","stopReason":"toolUse","timestamp":<ms>}}` |
| tool result | `{"type":"message",…,"message":{"role":"toolResult","toolCallId":"…","toolName":"…","content":[{"type":"text","text":"…"}],"isError":false,"timestamp":<ms>}}` |
| steward events | `{"type":"custom","customType":"steward-event","id":"…","parentId":"…","timestamp":"<ISO>","data":{"kind":"run_start" \| "run_end" \| "job_queued" \| "job_reported" \| "proposal_created" \| "decision" \| "rule_batch" \| "selftest", …}}` |

**What the mirror carries:**
- Phone results reach the conversation as `role: "user"` messages whose text starts `[phone result <jobId>]`. A scheduled input starts `[schedule weekly_check <date>]`.
- Thinking content is **not** mirrored.

**Writer rules (P2):**
- Lines are written after the harness has committed the entry.
- The writer remembers the last written entry, so a resumed process never writes an entry twice. A crash test covers this, with gates on both sides of the write.
- A truncated last line from a crash is repaired, and the line rewritten, on the next start.

**Reader rules:**
- The line shapes follow the Pi session format, so a Pi-compatible reader can parse the file.
- Skip unknown `type` and `customType` values without failing.

**The fixture:** `docs/protocol-fixtures/steward-worklog.jsonl` is the contract fixture; the
server's writer test reproduces it line for line for the same flow.

## Amendments

Each amendment states what changed and names the sections it touches.

- **A1** (changes §8, Presence):
  - **Stable join key.** A board may refuse a same-name re-register from a new process unless the join key matches, so every presence update carries the fixed `--join-session steward-main`, and restarts update the same card.
  - **No `idle` status.** The status list the seam sends is `working | waiting` only. Run end therefore uses `--status waiting` with the step `idle`, or with the proposal step.
- **A2** (changes §8, Capture):
  - `memory capture` carries `--idempotency-key <16 lowercase hex>`. The external store keeps the key with the capture evidence.
  - A later capture with the same key returns the existing capture id and writes nothing.
  - The steward always passes the capture token as the key.
- **A3** (changes §10, steward events):
  - The `data` of a `steward-event` line is allowlisted per kind before it is persisted. It holds ids in the steward id shapes (`pr-`, `pj-`, `rb-`, `dc-`, `it-`, `dev_`), enum outcomes, numbers and booleans only. Anything else is dropped.
  - Ids that look like file names or phone numbers never reach the worklog or a capture.
- **A4** (changes §3.3, §5, §6):
  - **§6 history:** `GET /v1/chat/history` answers `{"events": [{"id": <seq>, "type": "message" | "tool" | "proposal" | "run", "data": {…}}]}`. The events are oldest first and are the same objects the stream sends; `heartbeat` events are not stored.
  - **§3.3 `oldApks`:** `.apk` files anywhere in shared storage, the 50 largest.
  - **§3.3 and §5 `keepMarker`:** fixed at `Keep` in the MVP. `PUT /v1/rules` with any other value gets `400 BAD_REQUEST`. The app tests `inKeep` against the literal `Keep`, ignoring case. A configurable marker is a later decision.
- **A5** (adds a route to §1 and a CLI command):
  - **`POST /v1/crash`**: the app reports its own uncaught exception, on the next start after the crash.
    - **No token required:** a crash can come before pairing. A valid bearer attributes the report to the device. A missing, unknown or revoked one is ignored, not refused, so a report is never lost to auth state.
    - Body, at most **64 KiB** (else `413 BODY_TOO_LARGE`):

      ```json
      {"requestId": "crash-…", "appVersion": "0.2.1", "crashedAtMs": 0, "thread": "main",
       "exception": "java.lang.StackOverflowError", "stackTrace": "…",
       "device": {"model": "IN2023", "sdk": 33}}
      ```

    - Limits: `stackTrace` up to 32,768 characters (the app keeps the head when it truncates), `exception` 512, `thread` 128, `appVersion` 64, `device.model` 64, `device.sdk` an integer. A field over its limit gets `400 BAD_REQUEST` naming it. Unknown fields are dropped.
    - It answers `200 {"accepted": true, "reportId": "cr-…"}` and follows the §1 `requestId` rule.
    - **Rate limits:** 10 accepted reports per hour per client address (the pairing limit's address rule), and 60 per hour in total. Over either gets `429 RATE_LIMITED`.
  - **Storage:** the server keeps the newest 50 reports. `bin/steward crashes` lists them, newest first.
  - **Owner ping:** one notification per exception class and app version (through the notify seam, §8). It names only the class and the version, never the stack trace or the message.
  - Crash reports never reach the event mirror or the model.
- **A6** (changes §3.1, §3.3, §4, §5 and A5):
  - **Folder scopes are subtrees** (§3.1 `media_index` `folders`, §5 `photoWindow.folders`).
    - A folder entry matches the items directly in it **and in every subfolder**.
    - The trailing `/` is optional: both sides are normalised to end with `/` before a prefix comparison. Case is compared as stored.
    - So `Android/media/com.whatsapp/` covers `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/`, and `DCIM/Camera` matches `DCIM/Camera/`.
    - (Exact matching was the original behaviour; it missed media in subfolders, so subtree matching replaced it.)
  - **New item kind `clear_all_caches`** (§4), one Android confirmation clearing every app's cache:
    - `target` is `{"apps": [{"packageName": "…", "label": "…", "cacheBytes": 0}]}`: the biggest caches, shown to the owner for information.
    - The app runs Android's own **clear-temporary-files** request (`StorageManager.ACTION_CLEAR_APP_CACHE`) once: one Android confirmation, which clears **every** app's cache. The app's approval screen says so plainly.
    - The execution report is one item: `done` if Android returned RESULT_OK, otherwise `cancelled` or `failed` with the result code. `bytesFreed` is the sum of all apps' `cacheBytes` before, minus after (null when unreadable).
    - It needs all-files access. Without it the outcome is `failed`, with `resultCode` null and a short reason.
    - The agent proposes `clear_all_caches` whenever two or more caches are worth clearing. `clear_app_cache` stays for a single app.
  - **A5 gains a `severity` field** for non-fatal errors:
    - `"severity": "crash"` (the default when absent) or `"error"`. An `error` report also carries `"site"`, the place in the app (1–128 characters, required for `error`), and uses the same body, limits and `requestId` rule.
    - The app sends at most one `error` report per site, exception class and app version per day.
    - Only `crash` reports ping the owner on Telegram. `bin/steward crashes` lists both, with the severity shown; `--errors` and `--crashes` filter.
  - **New job kind `phone_diagnostics`** (§3.1 table): queued by `bin/steward diag` (the admin route `POST /admin/diagnostics`), `params` `{}`. Result `data`:

    ```json
    {"appVersion": "0.2.2", "versionCode": 4,
     "permissions": {"usageAccess": true, "allFiles": true, "mediaRead": true, "manageMedia": false, "batteryUnrestricted": true},
     "worker": {"state": "ENQUEUED", "lastRunMs": 0, "runAttemptCount": 0},
     "outbox": {"crashes": 0, "errors": 0},
     "log": [{"atMs": 0, "level": "info", "event": "jobs_polled", "detail": "…"}]}
    ```

    - `worker.state` is the WorkManager state of the unique periodic work (`ENQUEUED`, `RUNNING`, `BLOCKED`, `CANCELLED`, `FAILED`, `SUCCEEDED`) or `NONE`.
    - `log` is the app's in-memory event log, newest last, at most 200 entries. `event` is a short snake_case name and `detail` is at most 200 characters.
    - `detail` never carries file names, media metadata, tokens or other personal data, only codes, counts, ids and screen names.
    - `bin/steward diag` waits up to `--wait` seconds (default 60) for the result and prints it. `bin/steward job <jobId>` prints any job's stored result.
- **A6.1** (changes §4 and §7):
  - **No-op execution report (S4).** A `POST /v1/proposals/{id}/execution` report in which **no item is `done`** (all `cancelled`, `failed` or `skipped_protected`) returns the proposal to `open` instead of `executed`.
    - The report's decision is spent (`used`): it cannot execute anything again, and `GET /v1/decisions/{id}` answers 404. The owner approves again, which makes a new decision.
    - The per-item ledger rows are still written, a rule batch's one-tap count does not advance, and the `review_line` event carries `nothingDone: true` and a plain line saying nothing was done.
    - A report with at least one `done` item is unchanged: the proposal becomes `executed`. A replayed report (same `requestId` and body) is answered from the §1 request log with `duplicate: true` and changes nothing.
  - **One cache batch in code (S5).** When a proposal holds two or more `clear_app_cache` items, `propose_cleanup` replaces them with **one** `clear_all_caches` item.
    - The target is the A6 shape `{"apps": [{"packageName", "label", "cacheBytes"}]}`, `cacheBytes` taken from each merged item's `bytesEstimate` (0 when absent), and the item's `bytesEstimate` is their sum. It sits where the first cache item stood.
    - Other item kinds are untouched; a single `clear_app_cache` stays as it is. The tool result tells the model the merge happened.
  - **`lastSeenMs` on job polls (N9, §7).** An authenticated `GET /v1/jobs` also refreshes the device's `lastSeenMs`, writing at most once per 60 s. Permissions and app version are not touched.
- **A6.2** (changes §4 and §8):
  - **`proposals` tool (§8).** A read-only, deterministic, replay-safe tool for the model. It lists every `open` and `approved` proposal (id, title, status, `createdAt`, item count, item kinds, `bytesEstimate`, `source`), then the 10 most recent closed ones (`executed`, `rejected`, `expired`, `superseded`, with `closedAt` and, for superseded, `supersededBy`). The model offer is now nine tools.
  - **New status `superseded` (§4).** When `propose_cleanup` creates an `agent` proposal, every earlier proposal with `source: "agent"` and status `open` whose item kind families are all covered by the new proposal's becomes `superseded`, with `supersededBy` and `supersededAtMs`, in the same commit as the new proposal.
    - Families: cache (`clear_app_cache`, `clear_all_caches`), uninstall (`uninstall_app`), media (`trash_media`). `advice` items have no family: a proposal of only advice is never superseded and supersedes nothing.
    - `approved` proposals (they may be executing), `rejected`, `executed` and `rule_batch` proposals are never superseded, so an execution report for an approved proposal always lands.
    - The decisions ledger gets one `superseded` row per replaced proposal (a record, not an owner decision). No new worklog event kind: the new proposal's `proposal_created` event and its chat `proposal` event (which makes the app reload its list) are unchanged. The `propose_cleanup` result lists the superseded ids.
  - **Race.** `POST /v1/proposals/{id}/decision` on a superseded proposal answers `409 PROPOSAL_NOT_OPEN` with a message naming the newer proposal.
  - **Visibility.** The device's `GET /v1/proposals` keeps answering open proposals by default, so superseded ones leave the app's list on the next refresh. `bin/steward proposals --all` shows them with `supersededBy`.
  - **Prompt.** Each review re-proposes from the fresh scan, one batch per kind; a new proposal replaces the earlier open one of the same kind; the model calls `proposals` before saying what is open and never calls a proposal pending unless `proposals` says it is `open`.
- **A6.3** (changes §4 and §8, and the admin channel):
  - **Why.** A model can ignore a prompt rule to re-propose from each fresh scan and quote a closed proposal as pending from its own old messages. The behaviour is now enforced in code.
  - **The weekly check builds its cache batch itself (§4).** In the `settle` phase, in the same commit that stores the fresh snapshot and the run marker, the server creates **one** `clear_all_caches` proposal from the snapshot's full per-app list (`Snapshot.apps`, every app the phone reported, not only the top ten).
    - Apps with `cacheBytes` of at least 10 MiB are listed, biggest first, in the A6 target shape `{"apps": [{"packageName", "label", "cacheBytes"}]}`. The item's `bytesEstimate`, and the proposal's, is their sum.
    - Nothing is created when the listed caches total less than 100 MiB, or when the `storage_overview` job expired without a result.
    - The title reads like `Clear app caches (~3.3 GB)`; the reason names the date and the three biggest apps.
    - `source` is `agent`, so A6.2 supersedes last week's open cache proposal in that same commit.
    - **`builtBy`.** The proposal carries the optional field `"builtBy": "weekly_check"`, for the record: it marks a proposal the server built, not the model. It appears in the device's `GET /v1/proposals` and the admin view. The app decodes with `ignoreUnknownKeys = true` (`ProtocolJson`), so APK 0.2.2 ignores it.
    - **Idempotent.** The batch shares the commit with the run marker, and the run's batch id is recorded beside it, so a crash and replay of the settle phase, or a second settle continuation for the same run, never creates a second proposal.
  - **The weekly-check message carries the server's truth (§8).** The `[schedule weekly_check <date>]` input now lists every `open` proposal from the server's own record (id, title, item kinds, size), names the cache batch as just created by the server, states that the list is authoritative and that no other proposal is waiting for the owner. When the server built no batch, the message says so. The model is told not to propose another cache batch that week. The submit is still deduplicated by `requestId` `weekly-check:<date>`.
  - **`POST /admin/chat`** (admin Unix socket only, like every `/admin/*` route). Body `{"requestId": "…", "text": "…"}`. It takes the same path as the phone's `POST /v1/chat` (the §1 `requestId` rule, the 32,000-character limit, a run record, the conversation input, answer `202 {"accepted": true, "conversationId": "…"}`), but the server prefixes the text with `[via server] `, so the record shows the message did not come from the phone. `bin/steward say "<text>"` is its CLI.
- **A7** (one-tap self-update; adds §7.1):
  - **Release record.** The server serves at most one release: the newest APK shipped with `bin/ship-apk`. Its directory is `PHONE_STEWARD_RELEASE_DIR` (default `<stateDir>/releases`, the folder `bin/ship-apk --dest` fills). The shipping step writes `release.json` there beside the APK: `{"versionName": "0.2.3", "versionCode": 5, "file": "phone-steward-v0.2.3-<sha7>.apk", "sha256": "<64 hex>", "sizeBytes": <int>, "publishedAtMs": <int>}`. The server re-hashes the file when `release.json` or the file changes (cached by size and mtime) and serves nothing if the hash differs.
  - **`GET /v1/app/latest`** (device bearer token). `200 {"versionName", "versionCode", "sha256", "sizeBytes", "publishedAtMs"}`, or `404 {"error": {"code": "NO_RELEASE"}}` when there is no valid release. `500 RELEASE_CORRUPT` when the file does not match `release.json`.
  - **`GET /v1/app/apk?versionCode=<n>`** (device bearer token). Streams the APK as `application/vnd.android.package-archive` with `Content-Length` and `X-Sha256`. `409 {"error": {"code": "RELEASE_CHANGED"}}` when `n` is not the current release; `404 NO_RELEASE` as above. The server never serves any other file, and no path or file name comes from the request.
  - **The app.** Status shows "Update available: <versionName>" when `latest.versionCode` is greater than its own. **Update** downloads the APK over the paired client into `cacheDir/updates/` (resumable not required; at most 100 MiB), then checks, refusing on any mismatch: the SHA-256 against `latest.sha256`; the archive's package name; its `versionCode` against `latest`; and its signing certificate against the installed app's (`PackageManager.getPackageArchiveInfo` with `GET_SIGNING_CERTIFICATES`). It installs **in place** through a `PackageInstaller` session (`MODE_FULL_INSTALL`, the app's own package name), so the pairing, the outbox and the caches survive. Android shows its one install confirmation (`STATUS_PENDING_USER_ACTION`). The very first update also needs Android's "Install unknown apps" permission for this app; the app sends the owner straight to that one switch (`ACTION_MANAGE_UNKNOWN_APP_SOURCES` with the package URI) and resumes afterwards. Manifest: `REQUEST_INSTALL_PACKAGES`.
  - **Diagnostics.** The app logs `update_check`, `update_download`, `update_verified`, `update_refused <reason>` and `update_install <status>` in its event log (A6); a refusal also sends an error report (`site` `update.*`). Reasons never carry paths or file names.
  - **Shipping.** `bin/ship-apk` writes `release.json` (with the SHA-256 already computed for the `.sha256` file) and archives older APKs to `<dest>/archive/`. The first APK that contains this feature is installed by hand once; every later version uses the button.
- **A8** (Chat verbosity; changes §6 `tool`, `run` and `proposal` events):
  - **`tool` events** gain optional keys, all built by the server and never by the model: `callId` (the tool call's id, at most 64 characters of `[A-Za-z0-9_-]`; both events of one call carry it), `argsSummary` and `resultSummary` (one line each, **at most 300 characters**), `durationMs` (on the closing event: from the call being seen to its result being seen, so approximate) and `atMs`. The `started` event carries `argsSummary`; the `done` or `error` event carries `argsSummary` again, `resultSummary` and `durationMs`.
  - **What a summary may hold.** Counts, sizes, ids in the steward id shapes, kinds and statuses only: for example `mode summary, 2 folders, older than 120 days`, `3 items: 2 trash_media, 1 advice`, `queued job pj-1a2b3c`, `snapshot 3 h old`, `no entries yet`. **Never** a file path, a folder name, a media or app name, a proposal title, a token or any model-written text. A tool the server has no summary for gets its argument names (short identifiers only) or `status <word>` or `<n> characters`. An error summary is `error: ` plus the message with path-like, token-like and file-name-like runs replaced by `[redacted]` (the `diagnostics.ts` rules). Enforced in `server/src/chat-tool-summary.ts` and tested there.
  - **`run` events** gain optional `atMs`; the `ended` event also gains `durationMs` (the run's length).
  - **`proposal` events** gain optional `atMs`.
  - **Old apps** ignore the unknown keys (`ignoreUnknownKeys = true`); **new apps** treat every key as optional, so an old server's events still render.
  - **Not new, but now relied on:** a phone job's result reaches the conversation as a `user` message starting `[phone result <jobId>] kind=… status=…` (or `[phone expired <jobId>] …`), and the weekly check as `[schedule weekly_check <date>] …`. The app recognises these prefixes and shows them as system rows (High verbosity) instead of owner bubbles.
- **A7.1** (details settled while implementing A7; no change for existing clients):
  - `GET /v1/app/apk` with a missing or non-integer `versionCode` answers `400 BAD_REQUEST`.
  - A `release.json` that is invalid, names something other than a plain file name, or names a missing file is `404 NO_RELEASE`; a size or SHA-256 mismatch is `500 RELEASE_CORRUPT`.
  - The shipping step (`bin/ship-apk --dest <dir> <apk>`) writes `release.json` atomically **before** it archives the older APK, so the server never points at a file that has gone.
  - Test builds may override the version with `-PstewardVersionCode` and `-PstewardVersionName`; `bin/ship-apk` refuses an APK whose `aapt2` version disagrees with `app/build.gradle.kts`, so an override build cannot ship.
- **A9** (live defects found in use; changes §4 and §8, the admin channel and the chat bridge; no new route, no change for the app):
  - **Why.** The steward model wrote a `trash_media` proposal whose targets were `{"mediaId", "name"}`. The app re-reads the live MediaStore row and refuses unless `relativePath`, `displayName` and `sizeBytes` match the target, so every item failed at once, twice, with no dialog. Separately, the owner's "Are you here?" was answered from the model's own old messages (an executed proposal "still waiting", the decisions ledger "empty"). Both are now enforced in code, not in the prompt.
  - **A9.1 Trash targets come from the phone's reported index (§4).**
    - When a proposal with `trash_media` items is written, each target becomes the full `MediaRef` (§3.3: `mediaId`, `relativePath`, `displayName`, `mimeType`, `sizeBytes`, dates, `isFavourite`, `inKeep`) of the newest reported `media_index` row with that `mediaId`. Rows come from `largest` (mode `summary`) and `items` (mode `rule_candidates` and folder runs) of every job whose result is `ok` or `partial`. The newest report wins; a field the model supplied that disagrees with the index loses to the index, and fields the index lacks are not kept.
    - An `agent` proposal (the `propose_cleanup` tool) is **refused** with a plain tool error when an item's `mediaId` is in no reported index (the error names it and says to run `phone_media_index`), or when the row is protected (`isFavourite`, `inKeep`, or restored by the owner). Nothing is stored. A `rule_batch` proposal, whose refs already come from an index, is completed when found and otherwise kept as given.
    - A missing `bytesEstimate` becomes the row's `sizeBytes`; a given one is kept.
    - **Repair.** At server start, once, in one commit, every `open` proposal whose `trash_media` target lacks `relativePath`, `displayName` or `sizeBytes` is completed from the same index (target, item `bytesEstimate` when it was 0 or missing, proposal total). An item whose `mediaId` is in no index is left as it is. Idempotent: complete targets are skipped, so a second run changes nothing. The start-up log line names the count. Closed and `approved` proposals are never touched.
  - **A9.2 Every owner chat turn carries a server note (§6, §8).**
    - `POST /v1/chat` and `POST /admin/chat` (`bin/steward say`) submit the model a copy of the owner's message with a server note after the line `[server note — not typed by the owner and not shown to them]`. The note holds (a) the authoritative open list (the S7/S8 text), (b) the size of the decisions ledger, and (c) the latest outcome of each proposal decided in the last 14 days: id, title, status, the day, and the counts of the latest execution report, for example `executed: 1 done (3.3 GB freed)`, `open again: 15 cancelled on the phone`, `open again: 12 failed on the phone`, `rejected by the owner`, `superseded by pr-… (not an owner decision)`. A proposal that is open and was never decided appears only on the open list.
    - **The owner never sees it.** The chat bridge cuts everything from the marker line off before it writes the `message` event, so `GET /v1/chat/history` and `GET /v1/chat/stream` carry exactly the text the owner typed. The §1 `requestId` rule is unchanged (it compares the typed text). `bin/steward chat` (`GET /admin/chat`) also shows the typed text only; an owner line the model received with a note carries `"serverNote": true`.
    - The system prompt tells the model that every owner message ends with this note and that it overrides its earlier messages.
  - **Tests.** `server/test/trash-targets.test.ts` (A9.1), `server/test/owner-chat-note.test.ts` and one case in `server/test/api.test.ts` (A9.2).
- **A10 ("full agent"; every deletion still goes through Proposals).** Makes the steward a real agent: direct read tools, the web, files, shell-level actions, screen use, model choice and context management. Additive: every existing client keeps working.
  - **A10.1 Files (Tier 1, all-files access).**
    - Job kind `files_query`, tool `phone_files` (phone job, `replay: "safe"`). Params, one of:
      - `{"op": "list", "path": "MapsWithMe", "depth": 1}` (depth 1–3);
      - `{"op": "find", "root": "", "namePattern": "*.mwm", "type": "file|dir|any", "minBytes": 0, "olderThanDays": null, "limit": 200}` (glob on the name, case-insensitive);
      - `{"op": "du", "paths": ["MapsWithMe", "Download"]}`.
    - Paths are relative to the primary shared storage root (`/storage/emulated/0`; `""` is the root). Absolute paths, `..` and symlink escapes are refused (`status: "failed"`, `error.code: "BAD_PATH"`). `Android/data` and `Android/obb` are not readable on Android 13: they appear with `"unreadable": true`.
    - Result `data`: list → `{"path": "…", "entries": [FileEntry], "truncated": false}`; find → `{"items": [FileEntry], "truncated": false}`; du → `{"paths": [{"path": "…", "type": "file|dir", "bytes": 0, "files": 0, "dirs": 0, "modifiedMs": 0, "unreadable": false}]}` (`type` and `modifiedMs` so a `delete_files` target completed from a `du` row passes the app's identity check). A path the server refuses is refused at the tool call too (`BAD_PATH`, nothing queued). `FileEntry` = `{"path": "MapsWithMe/230606", "type": "file|dir", "sizeBytes": 0, "modifiedMs": 0, "fileCount": 0}` (`sizeBytes` and `fileCount` of a dir are recursive totals; `fileCount` is absent for a file). Caps: 500 entries per answer, 25 s per job; `truncated: true` when a cap cut it.
    - Proposal item kind **`delete_files`**, target = a `FileEntry`. As in A9.1, the server completes and checks every target from the newest reported `files_query` result that contains that `path` (any op); an `agent` proposal naming a path in no report is refused with a tool error. Refused always: the root, `Android/` and anything under it, `.StewardTrash/` and anything under it, any path with a segment named `Keep` (any case), and the media folders `DCIM/` and `Pictures/` (photos and videos go through `trash_media`).
    - Execution (app): one in-app confirmation for the whole approval (there is no Android dialog for files). The app re-stats each path; a missing path or a changed `sizeBytes`/`fileCount`/`modifiedMs` (dir: the totals) is `failed` with no action (the A6 identity rule). Otherwise the item is **moved** (a rename on the same volume, instant) to `.StewardTrash/<yyyy-MM-dd>/<proposalId>/<original path>` and reported `done` with `bytesFreed: 0` and `trashedBytes: <size>`. Space returns when the Steward trash is emptied: automatically for folders older than 30 days (the periodic worker), or at once with **Empty Steward trash** on Status (one in-app confirmation; an owner action, so no proposal). Device status gains `"stewardTrash": {"bytes": 0, "oldestMs": null}`.
  - **A10.2 Shizuku (Tier 2).** Shell-user rights through Shizuku (`dev.rikka.shizuku`), set up by the owner once and again after each reboot. The model never sends shell text: every command below is a fixed template in the app, with arguments validated (package names by regex and against the installed list).
    - Device status `permissions.shizuku`: `"absent" | "not_running" | "no_permission" | "ready"`.
    - Job kind `shell_info`, tool `phone_shell_info` (read-only): `{"op": "packages"}` (every package with `enabled`, `system`, `installer`) or `{"op": "app_sizes"}` (exact code/data/cache per package). Without Shizuku `ready` the job answers `status: "failed"`, `error.code: "SHIZUKU_NOT_READY"`.
    - Job kind `force_stop`, tool `phone_force_stop {packageName}`: not destructive, so it runs without a proposal; refused for the steward app itself and for system packages in the deny list.
    - Proposal item kinds: `trim_caches` (target `{"apps": [{packageName, label, cacheBytes}]}`; executed as ONE `pm trim-caches 999G`, clearing every app's cache with no Android dialog; outcome measured as in A6 `clear_all_caches`), `disable_app` and `enable_app` (target `{packageName, label}`; `pm disable-user --user 0` / `pm enable`). `disable_app` is refused for packages on the never-touch list (§3.7 risk: launcher, system UI, phone, settings, Google Play services, the keyboard in use, the steward). Without Shizuku at execution: `failed` with the reason shown on the card. When Shizuku is ready, the server prefers `trim_caches` over `clear_all_caches` in the weekly batch.
  - **A10.3 Operate the screen.** Fills the reserved §9.
    - The owner starts it in the app ("Let the steward use the phone", with a task). `POST /v1/live/sessions {"requestId", "task"}` → `{"sessionId": "ls-…"}`; only a device token can start one, never a schedule, rule or chat turn.
    - The server runs a steward turn whose tool set is ONLY `screen_observe {}`, `screen_act {"action": "tap|scroll|type|back|home|open_app", "nodeId"?, "text"?, "direction"?, "packageName"?}` and `screen_end {"summary"}` (no web, memory/session, files or proposal tools in that turn, and the screen tools exist in no other turn).
    - Transport: the app's foreground service long-polls `GET /v1/live/sessions/{id}/next?wait=25` → `{"step": {"stepId", "tool", "args"}}` or `{"ended": true}`, and answers `POST /v1/live/sessions/{id}/steps/{stepId} {"requestId", "outcome": "ok|refused|failed", "tree"?: ScreenTree, "detail"?}`. `POST /v1/live/sessions/{id}/stop` ends it (owner Stop). A step is never resent after a crash: the turn gets `interrupted` and must observe again.
    - `ScreenTree` = `{"package", "activity"?, "nodes": [{"id", "role", "text"?, "desc"?, "clickable", "editable", "bounds": [l,t,r,b]}], "truncated"}`; password fields are dropped, size-capped.
    - The guards are enforced in the app (allow and deny lists editable in Rules; confirm gate on committing words; Clear storage/Clear data never; no secrets; 40 steps and 10 minutes; Stop). Every action is a ledger entry `screen_action` with the node text and outcome.
  - **A10.4 Model catalogue.** `GET /v1/agent/models` → `{"current": {"provider", "id"}, "models": [{"provider", "id", "label", "billing": "subscription|metered|unknown", "available": true, "reasoning": false, "contextWindow": 0}]}`: every model in the Pi coding agent's own registry (the catalogue the `pi` CLI offers on this host), `available` when Pi's auth on this host has credentials for its provider. `PUT /v1/agent/settings {"requestId", "model": {"provider", "id"}, "confirmMetered": false}` → `200 {"model": {…}}`; `409 AGENT_BUSY` during a run; `400 METERED_NEEDS_CONFIRM` for a `metered` model without `confirmMetered: true` (the app asks once: "This model is pay-as-you-go: each message costs money"); `404 UNKNOWN_MODEL`. The choice applies from the next turn, survives restarts, is a steward event, and `GET /v1/agent/settings` reports usage per model. Credentials are read by the server process only, never logged or sent to the app.
  - **A10.5 Web.** Tools `web_search {query, limit?}` (DDGS metasearch, no API key), `web_read {url}` (readable text, capped) and `web_browse {url, steps}` (headless Chromium, fresh context, no logins, no downloads, no form submission). Results are labelled untrusted web content. Budgets per run.
  - **A10.6 Memory and session read tools, and hand-over.** Tools `memory_recall {query}`, `memory_find {question}`, `memory_survey {scope?}`, `session_find {query}`, `session_handoff {ref}`, `session_log {ref, item?|grep?}`, `presence_board {}`: fixed read-only subcommands of the orchestration command (§8), 20 s timeout, output capped and labelled as untrusted data. Use: "see what agent X worked on and do it on the phone". Nothing is injected into turns automatically. Captures stay as A2. Other agents can read the steward's sessions through the event mirror (§10).
  - **A10.7 Tool sets.** §8's list becomes: the A6.2 list plus `phone_files`, `phone_shell_info`, `phone_force_stop`, `phone_result` (A11.2), `phone_use_request` (A13.1), the web tools and the memory/session read tools; the screen tools only in a live-session turn (A10.3). The exact-set test is updated, not removed.
  - **A10.8 Waiting fixes folded in.** An `app_usage` report settles open `uninstall_app` items whose package is gone (ledger type `removed_outside`, item dropped, proposal closed when empty, mentioned in the A9.2 note); `recordExecution` ignores items not approved in that decision, which stay `kept`, and the app stops reporting unticked items; SIGTERM closes streams and connections, stops schedulers and exits within 5 s; `bin/steward backup` makes a SQLite online backup to `/var/lib/phone-steward/backups/`, 7 kept (`systemd/phone-steward-backup.{service,timer}` nightly); the app shows whole MB below 1 GB, matching the server; one status post after pairing; the card shows "Already removed" for gone packages, unticked, and a proposal whose apps are all gone settles quietly. A closed-for-that-reason proposal uses the existing status `expired` with `closedReason: "removed_outside"`, so older apps need no change.
  - **A10.9 Context: compaction, manual compaction and fresh starts.** Rationale: the default compaction policy blocks only near the end of the model's window, and a large-window model would let the context grow for months before anything happened.
    - **Superseded by A11.1.** ~~A fixed working budget~~: any fixed working budget is withdrawn; compaction starts at 75 % of the model's own window (A11.1). The summary instructions below still apply: keep the owner's stated preferences, decisions and open threads; drop numbers the tools can re-read.
    - **Not an endless chain of summaries.** The steward's memory is its documents (proposals, the decisions ledger, snapshots, rules, the project memory file), not its transcript. A **fresh start** resets the model context (Pi Durable `ResetEntry`) and seeds it with a **state brief built in code** from those documents: the A9.2 open list and recent outcomes, the rules, the latest snapshot summary, the project memory, the maintainer's standing notes, and the open-threads part of the latest compaction summary. A fresh start happens automatically before each weekly check, so a summary chain lives at most a week, and on demand.
    - **Manual.** `POST /v1/chat/compact {"requestId"}` and `POST /v1/chat/fresh {"requestId"}` → `202 {"accepted": true}`; `409 AGENT_BUSY` during a run. In the app: Chat's menu has **Compact now** and **Start fresh** (one in-app confirmation for Start fresh), and typing `/compact` or `/new` in the chat box does the same. Admin: `bin/steward compact` and `bin/steward fresh`.
    - **Visible.** `GET /v1/agent/settings` adds `"context": {"tokens": 0, "budget": <the model's window, A11.1>, "lastCompactionMs": null, "lastFreshStartMs": null}`; the Chat footer shows the model and "Context 42 %". The app's chat history keeps every message; a fresh start draws a divider: "Fresh start: the steward now works from its records, not this conversation."
    - **Tests:** the policy mapping for 1 000 000- and 128 000-token windows; a threshold compaction fires at 75 % of the budget (faux model); the state brief is built from documents only and is under its own cap; the weekly check starts fresh first; manual routes refuse while busy; the chat log is untouched by either.

- **A11: compaction at 75 % of the model's window, phone results summarised in the conversation, Steward trash restore.**
  - **A11.1 Compaction follows the model's window (amends A10.9's working budget).** The fixed 128 000-token working budget is withdrawn, with `PHONE_STEWARD_WORKING_BUDGET`. For a model with window `W` (from the catalogue; a model with an unknown window keeps Pi Durable's defaults): `reserveTokens = max(16 384, round(0.10 × W))` (the block point is `W − reserve`, 90 % of a 1 000 000 window), `backgroundTokens = (W − reserveTokens) − round(0.75 × W)` (background compaction starts at 75 % of `W`), `keepRecentTokens = max(20 000, round(0.05 × W))`. Windows so small that 75 % is at or above the block point (`W ≤ 2 × 16 384` and the like) keep today's small-window rule. `PHONE_STEWARD_COMPACT_AT` (a fraction, 0.5–0.9, default 0.75) overrides the 75 %. A model change recomputes it (A10.4). `GET /v1/agent/settings` `context.budget` becomes the model's window `W`, and `context` adds `"compactAtTokens"` (the background threshold), so the footer's "Context NN %" is the share of the window and compaction starts at the configured percentage. The weekly fresh start, manual Compact now and Start fresh stay as A10.9.
  - **A11.2 Phone results enter the conversation as summaries.** Raw phone results pasted whole can fill a context window in days. Change: the follow-up text of an `ok` result is `[phone result <jobId>] kind=<kind> status=ok summary=<canonical JSON> (full result: phone_result {"jobId":"<jobId>"})`, where `summary` is a deterministic per-kind digest of at most 4 000 characters: totals and counts always; the largest items first (for example storage top folders, the 25 largest apps plus the never-used ones, media folder totals, the first 60 `files_query` rows); a generic fallback that keeps scalars and cuts arrays to their first 20 items with an `"<array>Count"`. Error, expiry and partial texts are unchanged; a result whose canonical JSON is already at most 4 000 characters is sent whole as today (`data=`). The full result stays in the jobs document as now. New tool **`phone_result {jobId, path?, offset?, limit?}`** (server-only, read-only): returns the stored full `data` of a job of this conversation, or with `path` (a dotted key into it, for example `apps` or `folders`) that sub-value, arrays paged by `offset`/`limit` (default 50, at most 200), each answer capped at 20 000 characters with `"truncated": true` and the next offset. The A10.7 tool set gains `phone_result` (23 names). Every reader of the old `data=` text (the chat-log `phone_result` line, observability, the server note, tests) is checked and kept working: the chat log keeps its `data` field as the full result for the app, only the model's text changes. The prompt tells the model to use `phone_result` before it relies on a detail the summary leaves out, and that every proposal target is still completed and checked by the server (A9.1, A10.1).
  - **A11.3 Restore from the Steward trash.** App: Status → Steward trash card gains **Open Steward trash**, a screen listing the trash by batch (the dated folder and proposal id, the proposal's title when the app still has it, the size) with the items of each batch (their original paths and sizes), a **Restore** button per item and **Restore all** per batch. A restore is an owner action with no confirmation question (it puts things back; nothing is lost) and shows a result line. The rule is "merge-descend": for each entry under `<date>/<proposalId>/`, if nothing exists at the original path the entry is moved back (parents recreated, a same-volume rename); if a folder exists there and the entry is a folder, it descends and applies the same rule to the children; anything else (a file where a file or folder now is) is refused for that entry with a plain reason and left in the trash. Empty folders left behind in the trash are removed. The same descent, run without moving, lists the items for the screen, so batches moved before A11 (no manifest) show correctly. After a restore the app posts **`POST /v1/files/restored {"requestId", "proposalId", "items": [{"path", "bytes"}]}`** → `200 {"recorded": n}` (deduped by `requestId`; paths validated like `delete_files` targets, at most 500 items; a proposal id the server does not know is still recorded); the server writes one decisions-ledger entry per item, `{"type": "restored", "kind": "delete_files", "path", "bytes", "proposalId"}`, and the A9.2 note and the `decisions` tool show it, so the steward does not propose those paths again without the owner asking. The device status `stewardTrash` follows on the next post.
  - **A11.4 The footer stays current.** The app reloads `GET /v1/agent/settings` when a steward turn ends (the chat stream's end-of-turn event), after Compact now / Start fresh, and when Chat comes back to the foreground, so "Context NN %" never shows a reading from before a compaction.
- **A12: allow restricted settings through Shizuku.** App only; no server or route change. On several phones, after Android's "Restricted setting" block, App info has no three-dot menu, so "Allow restricted settings" cannot be reached and the Accessibility service (A10.3) cannot be switched on. Shizuku (A10.2) offers a way through.
  - **A12.1 The button.** On the Phone use card, while the Accessibility service is off and the phone runs Android 13 or later (API 33+), an **Allow with Shizuku** button runs ONE fixed command through the Shizuku shell: `appops set com.example.phonesteward ACCESS_RESTRICTED_SETTINGS allow` (an argument array; the package is the app's own `packageName`, nothing else is ever substituted; no server job, no model input). It then reads back with `appops get <own package> ACCESS_RESTRICTED_SETTINGS` and shows a plain result line: allowed ("Allowed. Now switch Phone Steward on in Accessibility." with the Accessibility button next to it), or failed with the reason (exit code and the first line of the error, no raw dump). When Shizuku is not `ready`, the card says what to do instead (start Shizuku, or allow the steward in Shizuku) and offers the existing way to open Shizuku; no dead button. Below API 33 the button is absent.
  - **A12.2 The words.** The steps are put in the order that works: first switch it on in Accessibility (Android may answer "Restricted setting"), then allow restricted settings (App info, three-dot menu), and a plain line that some phones have no three-dot menu, so "Allow with Shizuku" is the way. British English, short, no developer terms on screen.
  - **A12.3 Not proved on Test Lab.** Firebase Test Lab installs over ADB, so its devices are never restricted and have no Shizuku; the command's effect is proved on a real device after shipping. Tests cover the decision logic, the exact argument array, the read-back parsing and the card states with fakes.
- **A13 (amends A10.3): phone use from Chat.** The steward can ask to operate the screen from a chat turn, the owner answers in Chat, and the session's progress and result appear in the conversation. The guards of A10.3 are unchanged except where named here; a phone session is still its own conversation whose tool set is ONLY the screen tools (no web, files, memory/session or proposal tools in it), and it still starts only with the owner's answer.
  - **A13.1 The steward asks (server).** New tool **`phone_use_request {"task", "why"}`** in the main conversation (task 1–2 000 characters, why 1–500). It is refused (tool error, nothing stored) unless the current run is an owner run (`RunReason` `owner`): the weekly check, photo window, phone results, schedules and rules never ask. It stores one record in a new document `PhoneUseRequests`: `{"id": "pu-…", "task", "why", "status": "pending" | "allowed" | "declined" | "expired" | "superseded", "createdAtMs", "answeredAtMs"?, "sessionId"?}`; a new request supersedes an older pending one; a pending request expires after 10 minutes (the S16 clock sweep may serve). It appends the chat event `phone_use` `{"kind": "request", "requestId", "task", "why", "atMs"}` and returns at once: "Asked the owner in Chat. Wait for their answer; do not ask again in this turn." A ledger row `phone_use_request` records the request and later its answer. `PROTOCOL_TOOL_NAMES` gains `phone_use_request` (24 names); it is never in a live conversation.
  - **A13.2 The owner answers (app → server).** `POST /v1/live/requests/{id}/answer {"requestId", "allow": true | false}` (device token; deduped by `requestId`) → `200 {"status": "allowed", "sessionId": "ls-…"}` or `200 {"status": "declined"}`; `409 REQUEST_NOT_PENDING` (body carries the current status) for an answered, expired or superseded request; `409 LIVE_SESSION_ACTIVE` as A10.3. Allow starts the live session exactly like `POST /v1/live/sessions` with the request's task (the tap is the owner's start). The chat event `phone_use` `{"kind": "answer", "requestId", "allowed", "sessionId"?, "atMs"}` follows. A decline starts no run; the next server note (A9.2) tells the model the owner declined. `POST /v1/live/sessions` stays for older apps. `GET /v1/live/requests/pending` → `{"request": {…} | null}` lets the app redraw the card after a restart.
  - **A13.3 The session is shown in Chat (server).** While a session runs, the chat stream carries its activity as `phone_use` events with `sessionId`: `{"kind": "started", "task", "atMs"}`, `{"kind": "step", "tool", "summary", "outcome", "atMs"}` per answered screen step (summary like the A8 tool summaries: `tap "Clear cache"`, `open Settings`, `observe Settings (34 items)`; never the tree; one-time codes masked as S13), `{"kind": "say", "text"}` for the session model's own text, `{"kind": "ask", "question", "askId"}` (A13.4), and `{"kind": "ended", "reason", "summary"?, "steps", "atMs"}`. Sessions started before this change are not back-filled.
  - **A13.4 Back and forth (server).** New screen tool **`screen_ask {"question"}`** (live conversations only): the session waits up to 3 minutes for the owner's reply and returns it as the tool result (or "no answer"; the app's polling keeps the session alive meanwhile). While a session runs, `POST /v1/chat` from the owner goes to that session, not to the main conversation: the reply to a pending `screen_ask` answers it; any other message is queued and appended to the result of the session's next screen tool as `[owner says] …`. The response is `202 {"accepted": true, "conversationId": "live:<sessionId>"}` and the chat log shows the message as usual. The session time limit becomes **15 minutes** (steps stay 40); the confirm gate, deny list, Stop and every other guard are unchanged.
  - **A13.5 The result comes back (server).** When a session ends, the main conversation gets an input `[phone use ended] task=… reason=… steps=N summary=…` that starts a run with a new `RunReason` `phone_use`, so the steward continues the conversation with what happened (owner Stop included; the steward then only acknowledges).
  - **A13.6 The app.** Chat renders `phone_use` events: a request card (task, why, **Allow** and **Not now**; Allow is disabled with a one-line reason and a link to Status when the Accessibility service is off); while a session runs, a banner with **Stop** above the input and the step lines in the conversation (Low verbosity hides steps, Medium shows them collapsed, High shows each); `ask` as a highlighted steward question the owner answers by typing; the app's confirm-gate question (A10.3) also appears in Chat as a card with Allow/Deny (the notification and its Stop stay); the end as a short result line. Allow calls A13.2 and starts the foreground service on the returned `sessionId`. The Status → Phone use card keeps the service state, the A12 Shizuku help and the Rules link, and **loses the task box and "Let the steward use the phone"** (one line: "Ask the steward in Chat to use your phone."). Unknown chat event types and kinds are ignored, as before.

## A14 — Phone use rules: pick apps by name

People know apps by name (WhatsApp, Telegram), not by package id, so typing an id into the allow or deny
list does not work. App only; no server or protocol change; the stored lists stay package ids.

- **A14.1 Rows by name.** Each entry on both lists shows the app's name (its launcher label) with the package
  id in small text under it. An entry whose app is not installed shows the id and "not installed". Remove is
  unchanged.
- **A14.2 Add from a list.** The free-text field is replaced, on both lists, by an **Add an app** button that
  opens a picker: the phone's apps that have a launcher entry, sorted by name, each with its icon and name (id
  in small text), and a search box that filters by name or id. Apps already on that list are left out. One tap
  adds the app and closes the picker. Adding an app to one list removes it from the other (it cannot be both).
- **A14.3 Always denied stays.** Banking, wallet, authenticator and password-manager apps stay denied whatever
  the lists say. In the picker for the allow list such an app is shown with "always denied" and cannot
  be added there.
- **A14.4 Title.** The Rules tab's screen title becomes "Rules" (it holds the photo rules and the phone-use
  apps).
- **A14.5 Not changed.** The guard reads the same stored package ids as before; Reset both lists is unchanged.

## A15 — Persistence, the model catalogue, voice and copy

Phone-use sessions should keep going instead of ending early; the model catalogue follows the Pi coding
agent's own registry; Chat gains dictation, read aloud and copy; model failures are said plainly.

- **A15.1 Keep going (server).** The live-session prompt asks for persistence: explore before ending (scroll,
  open "More options"/overflow menus, tabs, the app's own search and settings search, an alternative route),
  `screen_end` only when the task is done, the app refused, the owner said stop, or the budget is nearly
  spent, after at least three distinct routes. Every step result starts with `step N of M`. `maxSteps`
  rises from 40 to 60 (15 minutes unchanged). The question rule stays: ask the owner only for a decision.
- **A15.2 Back in the app after a question (both).** Server: when a step result shows Phone Steward in front
  during a session, or after a `screen_ask` reply, the result adds one line: "Phone Steward is in front (the
  owner answered in Chat). Reopen <last other package> with open_app before acting." App: after the owner
  sends a reply to a session's question (or answers the confirm card) while the session runs, Chat moves its
  task to the back so the app the session was using returns to the front.
- **A15.3 Model failures are said plainly (both).** A steward turn that fails before any output with a provider
  auth or plan error ends with one assistant line in Chat: "<model label> could not run: <reason in plain
  words, e.g. its sign-in on the server has expired>. Pick another model in Settings." (no stack, no token).
  The catalogue marks such a model `"problem": "<short reason>"` (additive field) for one hour or until the
  auth file changes; the app's model picker shows the reason under it.
- **A15.4 The newest catalogue (server).** The steward's model registry matches the `pi` CLI on this host: the
  `@earendil-works` packages move to the version the installed Pi coding agent uses (1.1.0 at writing), and
  the catalogue also reads Pi's remotely refreshed overlay `<agentDir>/models-store.json` read-only, the way
  the `pi` CLI merges it. `GET /v1/agent/models` is otherwise unchanged; the app needs no change beyond
  A15.3's reason.
- **A15.5 Voice: dictation (both).** `POST /v1/voice/transcribe` (device token; body: the recorded audio,
  `Content-Type: audio/ogg` (Opus) or `audio/mp4` (AAC); at most 10 MB and 5 minutes) → `200 {"text"}`;
  `503 VOICE_UNAVAILABLE` when no provider key is configured; `502 VOICE_FAILED` when every provider fails.
  The chain (`server/src/voice/stt.ts`): OpenRouter `openai/whisper-large-v3-turbo`
  (DeepInfra, no fallbacks), then a local ASR service (`LOCAL_ASR_URL`, default
  `http://127.0.0.1:9000/asr`), then OpenAI `gpt-transcribe`; then a light clean-up pass
  (`server/src/voice/cleanup.ts`, OpenAI `gpt-5-nano`, British spelling, fillers out), skipped on failure. Keys come from the
  steward's environment (`OPENROUTER_API_KEY`, `OPENAI_API_KEY`), never logged or returned. App: a
  microphone button in the Chat input (asks for `RECORD_AUDIO` once); tap to record, tap again to stop;
  the text is inserted into the input for the owner to edit and send (never sent automatically); a
  recording shows its time and can be cancelled.
- **A15.6 Voice: read aloud (both).** `POST /v1/voice/speak {"text", "voice"?}` (device token; text 1–4000
  characters) → `200` audio (`audio/wav` from the primary, `audio/mpeg` from the fallback). Chain as Pi Web
  UI's `tts/speech.ts`: OpenRouter `google/gemini-3.8-flash-lite-tts` (PCM wrapped as WAV, default voice
  `Kore`), then OpenAI `gpt-4o-mini-tts` (mp3). App: a Read aloud button on every finished steward
  **round** (all the steward's text for one owner message, tool lines and thinking left out, code blocks
  read as "code block"); the app splits it into sentences of at most 280 characters, fetches the next while
  one plays, and the button turns into Stop. One round reads at a time.
- **A15.7 Copy (app).** A copy button on every code block (the code only), on every text block (that
  block's text), and on every finished steward round (the round's text as markdown, tool lines and
  thinking left out). A short "Copied" confirmation.
- **A15.8 N40 (app).** A request card whose Allow answers `409 REQUEST_NOT_PENDING` drops its buttons and
  shows "This request has expired."; `LIVE_SESSION_ACTIVE` keeps the card answerable after the running
  session ends. The notification → `answerById` wiring gets a test.
- **A15.10 The model is never a surprise (both).** Rationale: an alias model id (one that starts with `~` or ends in `-latest`) can resolve to a different model than the one that was picked, and a failing model id can silently change what answers. The server: every chat `message` event from the steward (and the history) carries an additive `"model": {"provider", "id", "label"}` naming the model that actually produced it (from the assistant message's own provider/model fields); the catalogue labels an alias model (an id that starts with `~` or ends in `-latest`) with `"alias": true` and its label says what it follows ("follows the newest Claude Haiku"); a turn never moves to another model on its own (if one ever does, its message carries the real model and the app warns). The app: each finished steward round shows the answering model's label in small muted text; when it differs from the model chosen in Settings, the round shows a warning line "Answered by <label>, not <chosen label>"; the model picker marks alias models "(alias)" with their label, and choosing a pay-as-you-go one keeps the existing confirmation. Phone sessions show their model on the session's first line in Chat.
- **A15.11 Allow an app for this task (both).** Rationale: a route can reach a package that is not on the allow list and has no launcher entry (Google Play services, for example), which the A14 picker cannot add. The app: when a step is refused only because the target package is not on the allow list (never an always-denied app, never a sensitive screen, never the steward itself), it asks the owner the same way as the confirm gate (the in-window card in Chat and the notification): "The steward wants to use <app label> for this task. Allow for this task / Not now". Allow adds the package to a list that lasts until the session ends and then runs the step; Not now (or no answer in 2 minutes) refuses as today. The step's `detail` says which happened. The Rules picker (A14.2) gains a "Show system apps" switch that also lists installed packages without a launcher entry (name from the package label), so the owner can add such an app permanently. The server: the live prompt says that an app outside the allow list may be offered to the owner by the phone, and that after "the owner said not now" it should try another route or end and say which app it needed.
- **A15.9 Not changed.** Phone-use safety rules, the guard, confirm gates and the 15-minute cap; the
  chat stream and history shapes (additive fields only).
