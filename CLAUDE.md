# AGENTS.md — pi-durable-phone-steward

> `AGENTS.md` and `CLAUDE.md` are byte-identical. Edit `AGENTS.md`, then `cp AGENTS.md CLAUDE.md`.

## Purpose

Phone Steward: a durable agent on a server (`server/`, Node 24 TypeScript on
[Pi Durable](https://pi.dev) + SQLite) talks to a small Android app (`app/`, Kotlin +
Compose). The app gathers storage, usage and media facts; the agent proposes clean-ups;
the owner approves each one on the phone; media always goes to Android's trash first.
Safety is structural, not prompt-based: see `docs/SAFETY.md`. Docs index: `docs/README.md`.

## Binding rules

1. **Safety is structural.** The model is never offered a tool that deletes, moves,
   uninstalls, disables or writes files, or runs shell text. The phone executes a
   destructive action only for an approved proposal, through Android's own confirmation,
   with media trashed first. The exact tool allowlist is enforced by a test; do not
   weaken it.
2. **The contract is `docs/protocol.md`.** Change it only by adding a numbered amendment;
   both halves are built and tested against it.
3. **TDD for every behaviour change** (RED, then GREEN, then regressions). Live
   validation happens on a disposable instance (temporary port and state dir), never on
   a production state directory; recipe in `docs/guide/disposable-instance.md`.
4. **Never commit:** secrets, tokens or keystores; SQLite files or state directories;
   raw device dumps, photo metadata or screenshots with personal content. `local.properties`,
   `node_modules/`, `build/`, `.gradle/`, APKs are gitignored.
5. **Durability rules:** phone tools are `replay: "safe"` and keyed by the tool call's
   task id; results return as follow-up inputs with `requestId: "phonejob:<jobId>"`;
   scheduled inputs use dated request ids; one process owns the SQLite storage; the CLI
   reads a running instance only through its admin socket.
6. **External seams stay off by default.** Orchestration (`PHONE_STEWARD_ORCHESTRATION`),
   the notify command, voice keys and schedules are opt-in configuration. The local stubs
   (event mirror under the state dir, notifications JSONL, `memory/project.md`) always work.
7. **Model rules.** One model at a time, no automatic fallback. Keys come from the
   environment or a key file, never committed, never logged. No real model calls in
   tests — the faux model covers behaviour.
8. **Dependencies.** Pin exact versions; `npm ci` (with `env -u NODE_ENV` if your shell
   exports it); never patch a dependency or symlink `node_modules`.
9. **Git.** Commit on the current branch with plain messages; stage explicit paths, never
   a dirty tree wholesale; no `git stash`.

## Start here

| If you need … | Read … |
|---|---|
| To stand the system up: requirements, config, pairing, HTTPS, seams | `docs/ADOPTING.md` |
| Every function and its "never" lines | `docs/FEATURES.md` |
| The shape of the system: components, data flow, state on disk, durability | `docs/ARCHITECTURE.md` |
| The safety model: what each control stops and does not | `docs/SAFETY.md` |
| The phone/server contract (routes, jobs, proposals, tools, mirror) | `docs/protocol.md` |
| Where a source file lives | `docs/guide/code-map.md` |
| CLI commands and debugging | `docs/guide/observability.md` |
| A throwaway instance for a probe | `docs/guide/disposable-instance.md` |
| Optional Test Lab runs | `docs/guide/firebase.md` |
| The docs index | `docs/README.md` |

## Validate & commit

- Server: `cd server && npm ci && npm test && npm run typecheck` (all must exit 0).
- App: `cd app && ANDROID_HOME=$ANDROID_HOME ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug`
  (a cached `UP-TO-DATE` run proves nothing — force with `cleanTestDebugUnitTest` when it matters).
- Durability or safety changes: also run `bash server/scripts/bite-proofs.sh` (mutation probes).
- Commit only what the task owns, with plain messages. Never push anywhere without being
  asked.

## Current state

This repository is the public copy of a personal project: fresh history, no deployment
wiring. Start from `README.md` and `docs/ADOPTING.md`.
