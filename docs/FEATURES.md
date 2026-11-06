# Features — what every function does, and what it never does

The product's promises, one section per function. "Never" lines are enforced in code, not
in prompts (see `docs/SAFETY.md` for the full model).

## Storage facts

- **What:** `phone_storage_overview` reports total/free space, per-app sizes (app, data,
  cache; system apps marked) and media categories; `storage_trend` shows the movement
  between dated snapshots; `phone_snapshot` stores a full dated snapshot; the Status
  screen shows the latest reading.
- **Never:** changes anything; runs more often than the schedules allow; records anything
  beyond sizes, counts and package names.

## Media window and trash

- **What:** `phone_media_index` reports media folders with age buckets and the largest
  items; the photo-window rule proposes trashing photos/videos older than the configured
  cutoff in chosen folders; trashing uses Android's system trash (about 30 days to
  restore); the app's Steward-trash screen restores single items or whole batches.
- **Never:** judges quality or content; detects duplicates; touches anything in a folder
  or album named Keep, anything favourited, or anything you restored (protected on both
  sides); deletes — trashing is always reversible until Android empties its trash.

## Unused apps

- **What:** `phone_app_usage` reports last-used times; the agent proposes uninstalling
  apps you never open; the uninstall runs through Android's own dialog; a report that an
  app is already gone settles its proposal quietly.
- **Never:** uninstalls without your approval; touches system apps, the never-touch list
  (launcher, system UI, phone, settings, Play services, your keyboard, this app), or an
  app you use.

## Cache clearing

- **What:** per-app `clear_app_cache` (the app opens that app's storage page), and
  `clear_all_caches` — one Android confirmation that clears every app's cache; with
  Shizuku running, a `trim_caches` proposal clears all caches with no dialog at all; the
  weekly check builds one cache batch itself from the snapshot, deterministically.
- **Never:** clears an app's *data* or *storage* (Clear storage is refused outright in
  phone-use sessions and never proposed); runs without the weekly-batch budget checks
  (small totals are skipped).

## Files

- **What:** `phone_files` lists, finds and measures any folder on shared storage;
  `delete_files` proposals move files/folders into `.StewardTrash/` (restore from the
  app; emptied after 30 days); restores are recorded and protect the paths from future
  proposals.
- **Never:** deletes anything directly; touches `Android/`, the Steward trash itself, any
  `Keep` path, or the top-level `DCIM/` and `Pictures/` folders (media goes through the
  trash flow); accepts a path the phone has not reported.

## Chat with the agent

- **What:** one conversation (`main`) you type into from the app; the stream shows tool
  activity, proposals and phone results; `/compact` and `/new` manage context; voice
  dictation and read-aloud when voice keys are configured.
- **Never:** sends your message anywhere but the configured model provider; shows you a
  server-injected note (the model sees one, cut off before display); executes anything
  because you typed it — typed requests still produce proposals.

## Proposals and approvals

- **What:** every removal is a proposal with per-item reasons and size estimates; you
  approve or reject item-by-item in the app; decisions expire in 7 days; a batch where
  nothing actually executed returns to `open`; a newer proposal supersedes an older open
  one of the same kind.
- **Never:** lets the model approve, execute or re-ask around a rejection; executes a
  superseded or expired decision; hides what was done — the decisions ledger records
  every item, kept or executed.
- **Auto mode (opt-in):** a standing rule in Rules that trashes matching window media
  with no per-item dialog (the app asks for the "Manage media" permission when you turn
  it on). It still excludes favourites and Keep, still checks the rule version before
  each batch, and still uses Android's trash. Every other proposal kind always waits
  for your per-item approval.

## Schedules

- **What:** the weekly check (storage, usage, media scan, one cache batch, a summary in
  chat) and the photo window (age-based batches) run on the server's clock when
  `PHONE_STEWARD_SCHEDULES` is on; each run starts from a fresh context built from the
  server's records.
- **Never:** runs with everything external off (the default); executes anything — a
  schedule produces proposals exactly like a chat request does.

## Voice

- **What:** dictation (microphone → text into the chat input, you edit and send) and
  read-aloud of the steward's rounds, through your configured providers.
- **Never:** sends audio anywhere without keys you configured; auto-sends a
  transcription; records without your tap.

## Phone use (optional, guarded)

- **What:** in a chat turn you started, the agent may ask to operate the phone's screen
  for one named task (`phone_use_request`); you allow or decline in Chat; while allowed,
  a bounded session (40 steps on the phone, 15 minutes) drives the screen through the accessibility
  service and narrates its steps into the chat.
- **Never:** starts on its own (schedules and phone results can never ask); reads
  password fields; presses Clear storage/Clear data; leaves its allow-list without asking
  you; outlives Stop.

## Diagnostics

- **What:** `bin/steward diag` asks the app for a one-shot health report (permissions,
  worker state, its in-app event log); crash and error reports arrive automatically and
  are listed by `bin/steward crashes`; the notify seam pings once per exception class.
- **Never:** includes file names, media metadata, tokens or personal content in the
  report details (enforced by redaction); pings more than once per exception class and
  version; sends crash reports anywhere but your server.
