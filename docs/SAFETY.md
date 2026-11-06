# Safety model — what this system does and does not stop

Read this before you adopt the system. The short version: **the agents here are
deliberately not sandboxed.** A sandboxed agent cannot do real upkeep — it cannot run the
build, read the state it must maintain, or restart a failing service. What you get instead
is a set of structural controls around the dangerous parts, and honesty about what each
one stops and what it does not.

## The phone side (the destructive side)

| Control | What it stops | What it does not stop |
|---|---|---|
| In the normal tool set, the model has no destructive tool. Its phone tools only queue read-only fact jobs; `propose_cleanup` writes a proposal; a guard rejects any tool outside the frozen allowlist. | The model deleting, moving, uninstalling or executing anything by itself through that tool set. Not an absolute: the two bounded exceptions below (auto mode and phone use) exist, and a maintainer could add a destructive tool later — the exact-set test fails if you do. |
| Server-side target completion. Every proposal target (media ref, file path, package) is completed and checked against the phone's own reported index before the proposal is stored. | The model inventing or hallucinating a deletion target; targets that do not exist or whose identity has changed. | Content-level judgement: an index-consistent wrong proposal (the model proposing the wrong album) is still possible — that is what your approval is for. |
| Protection rules, enforced on both sides. Anything in a folder or album named Keep, anything favourited, anything you restored, `Android/`, and a never-touch app list is excluded from proposals; the app re-checks protection on the live row before executing. | Protected items being proposed or trashed, even if the model tries. | New protection needs you might have: protection is structural (Keep, favourites, restored), not a learned model of what you care about. |
| Your approval, on the phone — for everything except opt-in auto mode. Every proposal executes only after a decision you made in the app; the decision id is re-checked with the server right before execution and expires in 7 days. | Anything executing without a per-proposal approval while auto mode is off. | Approving inattentively (read the sizes and items), and auto mode: once you switch it on, matching media is trashed with no per-item dialog (see the next row). |
| Android's own dialogs. Uninstall and cache clearing run through the system UI; media goes to Android's trash first; files go to a Steward trash kept for 30 days, restorable from the app. | Silent or irreversible loss in the normal path. | Nothing in the normal path; but "Empty Steward trash" is a real delete (one in-app confirmation), and Android may empty its own trash per its policy. |
| Age-based only. The photo rule proposes by age in chosen folders, never by "importance"; no duplicate detection, no quality judging. | The system quietly deciding a photo is "worthless". | Nothing — but it also will not find that one bad duplicate for you. |
| Auto mode is opt-in and still guarded. `Rules → auto mode` lets a standing rule trash matching media from the rolling window with no per-item dialog (the phone needs the "Manage media" permission for it; the server verifies the rule version before each batch, protected items are still excluded, and media still goes to Android's trash for about 30 days). | Nothing on its own — it is off until you switch it on, and the server refuses batches when the rules changed. | Per-item approval: while auto mode is on, a matching photo is trashed without asking you each time. It only ever matches the configured folders and age; it never touches favourites or Keep. |
| Phone-use sessions are bounded and guarded. When you let the steward operate the screen: at most 40 steps on the phone (the server's backstop cap is 60) and 15 minutes, an allow/deny list of apps, a confirm gate on committing words, Clear storage/Clear data never, password fields never read, Stop always works, and it starts only from your answer in Chat. | Unbounded or unattended screen driving, and committing actions you have not allowed. | Everything inside an app you allowed: the guard is word- and package-based, not semantic. |

## The server side

| Control | What it stops | What it does not stop |
|---|---|---|
| One writer on SQLite; the CLI reaches a running instance only through its admin socket. | State corruption from concurrent writers; tools guessing their way onto a production state directory (the CLI refuses without an explicit state dir). | A maintainer running raw SQLite against the live file anyway. |
| Request-id idempotency on every mutating route; canonical-JSON conflict detection on results. | Double execution from retries and replays. | A genuinely new duplicate action you approve twice on purpose. |
| The pair rate limit and token storage (only a SHA-256 of the token is kept). | Token theft from the server; brute-forced pairing. | A phone that is itself compromised — the token lives on it. |
| Read-only external integrations. The memory/session tools are fixed subcommands, output capped and labelled UNTRUSTED; nothing external is injected into turns automatically. | Prompt-injection from an external store turning into actions: the prompt says read results are data, never orders, and nothing read can approve anything. | A determined multi-step injection that talks the model into proposing something harmful — which then still needs your approval. |
| Web tools are read-only, budgeted, and told never to send personal data in queries. | The model exfiltrating file names or contacts to a search engine in the normal path. | The general "the model browses the web with your server's address" exposure. |
| Shizuku runs only fixed commands. When you enable it, the app holds shell rights via Shizuku and executes a fixed template per action (`pm trim-caches`, `am force-stop`, the restricted-settings allow) with package names validated against the installed list. | The model writing or sending arbitrary shell text — it never can. | The elevation itself: with Shizuku enabled, the app (and a compromised server telling it to run an allowed action) can act on packages beyond what a normal app may touch. Disable Shizuku if you do not want that surface. |
| The self-update serves one signed APK. `GET /v1/app/apk` serves exactly the release file described by `release.json` (hash-checked by the server; no path or name from the request), and the app verifies its SHA-256, package, version and signing certificate against the installed app before installing. | The server handing the phone a different file, and a wrong-signer APK installing in place. | A compromised server that also holds your real signing key: it could ship a signed malicious update. Keep the signing key off the server. |
| The model key and voice keys live in the service environment, never in a file in the repo, never logged. | Accidental key commits and leaks into chat. | Keys being readable by anything that can read the service environment (on a single-user box, that is root only). |
| The server reads the Pi agent dir read-only. Model credentials come from `PHONE_STEWARD_PI_DIR`'s `auth.json`/`models.json`, opened read-only; tokens are never refreshed, written, logged or sent to the app. | The steward mutating your Pi CLI's logins. | Credential exposure: every provider credential in that Pi agent dir is readable by the service's user. Use a dedicated agent dir (set `PHONE_STEWARD_PI_DIR`) if that bothers you. |
| Backups: a nightly SQLite online backup under the state directory, newest seven. | Losing the ledger and chat to a crash. | A hardware loss of the whole disk — copy the state directory off-box yourself. |

## What is deliberately out of scope

- **No per-user auth or multi-tenancy.** This is a single-owner system: one paired phone,
  one conversation, one admin socket. Do not expose it to other people.
- **No audit of the model provider.** Chat content (including your messages) goes to the
  model provider you configure. That is inherent to the product.
- **No malware analysis of files on the phone.** "Delete files" proposals are age/size
  facts, not security verdicts.
- **No host hardening.** The server runs as a normal systemd service with
  `NoNewPrivileges`, `ProtectSystem=full` and memory/CPU caps, but the agent process can
  read what its user can read. Treat the box accordingly.

## The honest summary

The design bet is: **deletion is rare, loud, and always yours.** Everything the agent
does on its own is read-only or queued fact-gathering; everything destructive is a
proposal you approve on the phone with Android's own dialogs and two trash layers under
it. The residual risks — an inattentive approval, a plausible-but-wrong proposal, a
compromised phone — are yours to manage, and no configuration changes that.
