# Code map — what lives where in `server/src` and the app

A navigation aid, not a spec: one line per area so you can open the right file first.
The contract behind every area is `docs/protocol.md`.

## Server (`server/src`, Node 24 TypeScript, run directly — no build step)

| Area | Files | One line |
|---|---|---|
| Entry and wiring | `main.ts`, `wiring.ts`, `config.ts`, `shutdown.ts`, `steward.ts` | Process entry, composition root, settings, clean shutdown; `steward.ts` opens Pi Durable with the steward extensions. |
| Test model | `faux-steward.ts` | The scripted model for tests and local runs — no model cost. |
| Device HTTP + admin | `http.ts`, `auth.ts`, `rate-limit-key.ts`, `requests.ts`, `ids.ts`, `errors.ts`, `canonical.ts`, `time.ts` | The protocol's device API and the admin Unix socket; token auth, pairing rate limit, request-id idempotency, canonical JSON. |
| Jobs, proposals, rules | `jobs.ts`, `proposals.ts`, `rule-tasks.ts`, `rule-guards.ts`, `photo-window.ts`, `photo-window-logic.ts`, `weekly-cache.ts`, `schedules.ts`, `notify.ts`, `documents.ts`, `media-index.ts`, `removed-apps.ts` | The phone job store, proposals/decisions, the deterministic rule tasks, the notify seam, dated snapshots, the MediaRef index, outside-removal notices. |
| Steward conversation | `chat-bridge.ts`, `chat-events.ts`, `chat-tool-summary.ts`, `owner-note.ts`, `server-note.ts`, `state-brief.ts`, `prompt-posture.ts`, `context-policy.ts`, `context-control.ts`, `run-tracking.ts`, `model-failure.ts`, `agent-settings.ts`, `model-catalogue.ts`, `pi-registry.ts`, `turn-origin.ts`, `steward-events.ts`, `project-memory.ts` | Chat store and SSE stream, per-event summaries, the notes on each turn, the fresh-start state brief, compaction at a share of the model's window, the model catalogue, per-turn tool gating. |
| Phone tools (model-facing) | `tools.ts`, `phone-tool.ts`, `files-query.ts`, `files-shizuku-tools.ts`, `phone-result.ts`, `phone-summary.ts`, `folder-scope.ts`, `restored-files.ts` | The tool set (§8), file facts and Shizuku-tier tools (A10.1–A10.2), summarised phone results + `phone_result` lookup (A11.2), folder scopes (A6), trash-restore reports (A11.3). |
| Web tools | `web-tools.ts`, `web-reader.ts`, `web-safety.ts`, `web-text.ts` | Read-only web search/fetch (A10.5); never offered in a phone-use run. |
| Live (phone use) | `live/bridge.ts`, `live/http.ts`, `live/hub.ts`, `live/requests.ts`, `live/sessions.ts`, `live/state.ts`, `live/tools.ts`, `live/tree.ts` | The phone-use session channel (A10.3/A13): session docs, screen-tree transport, in-process wake-ups, the screen tools. |
| Voice | `voice/http.ts`, `voice/stt.ts`, `voice/tts.ts`, `voice/cleanup.ts`, `voice/deps.ts` | Dictation and read-aloud routes (A15.5–A15.6), provider chain and key handling. |
| Orchestration | `orchestration/client.ts`, `orchestration/outbox.ts`, `orchestration/capture.ts`, `orchestration/index.ts`, `orchestration-tools.ts`, `worklog/mirror.ts`, `worklog/lines.ts`, `worklog/docs.ts`, `worklog/event-data.ts`, `integrations.ts` | The outboxed, never-blocking external calls (§8) and the local event mirror; `integrations.ts` is the seam type. |
| Observability and ops | `observability.ts`, `diagnostics.ts`, `crash-reports.ts`, `crash-gate.ts`, `backup.ts`, `release.ts`, `cli.ts` | Shared CLI formatting, `phone_diagnostics`, crash/error intake (A5), test-only crash gates, online backup, the app-release record (A7), `bin/steward`'s implementation. |

Tests: `server/test/` (vitest, mirrors `src/`); `server/scripts/` holds `fake-phone.ts`,
`bite-proofs.sh` (mutation probes) and `probes/*.json` (one JSON per registered probe).

## App (`app/src/main/java/com/example/phonesteward`, Kotlin + Compose)

| Package | One line |
|---|---|
| `net/` | The protocol client: `StewardApi` + `Sse` (jobs, chat stream, events), request ids, crash client, paired-client rebinding, `ClientRelease`. |
| `logic/` | Pure-JVM decision code (unit-testable): pairing, rules form, onboarding, summaries, coalescers, guards. Subpackages: `chat/` (ChatLog → ChatRows, Markdown subset, verbosity), `context/` (context footer, foreground gate), `exec/` (proposal executors: cache clear, uninstall, file delete, planner, authority gate), `files/` (file tree/query, Steward trash, restore), `jobs/` (pump, tracker, device status), `models/` (picker), `obs/` (diagnostics), `selftest/`, `shizuku/` (shell, restricted settings). |
| `android/` | Android adapters behind `logic/`: `AppGraph` (DI — never call `get` during construction), Shizuku/camera/media/keystore/workers, `AndroidNeverTouch` (the never-touch list, mirrored on the server). |
| `screen/` | Phone use (A13): `StewardAccessibilityService` + `AndroidScreenDriver` + `TreeSerialiser` read the screen; `SessionRunner` runs a bounded session; `ConfirmBroker`/`ConfirmGate` and `GuardPolicy` enforce the guards; allow/deny lists (`PhoneUseRules`, `PrefsGuardListStore`), `BackToUsedApp`, `AllowForTask`. |
| `ui/` | Compose screens. `StewardApp` is the stateless shell; screens: Status, Pair, Proposals, Rules, Chat (+ `ui/chat/`), Steward trash, Shizuku card, update section; `theme/`, host-testable widgets. |
| `update/` | Self-update (A7): controller, sha256 verifier, in-place install flows. |
| `voice/` | Dictation and read aloud (A15.5–A15.6): controllers + buttons, recorder, speech chunker/player, `RoundText`. |

Entry points: `MainActivity.kt`, `PhoneStewardApplication.kt` (worker + crash capture set-up).

Test source sets: `app/src/test/` (JVM + Robolectric — includes `AppLaunchTest`, the launch
gate), `app/src/sharedTest/` (`ScreenFixtures`, `ChatChecks` shared by host and device),
`app/src/androidTest/` (`testlab/` fixtures and screenshots, `screen/` the phone-use proof,
`e2e/` the Flow* end-to-end suite against `FakeSteward` on the device's loopback). See
`docs/guide/firebase.md` for how these run on Test Lab.
