# Docs index

Start with the repository's [`README.md`](../README.md), then [`docs/ADOPTING.md`](ADOPTING.md)
to stand the system up. This page lists every document and says when to open it.

## Contract and fixtures

| Document | Open it when you need… |
|---|---|
| [`protocol.md`](protocol.md) | The phone/server contract: transport, pairing, jobs, proposals, rules, chat, device status, the server-side model tools, the orchestration calls, the event mirror, and the amendments (A1–A15) that shaped it. Change it only by adding a numbered amendment. |
| [`protocol-fixtures/steward-worklog.jsonl`](protocol-fixtures/steward-worklog.jsonl) | The event-mirror line shape the server's writer test reproduces. |

## Guides (runbooks)

| Document | Open it when you need… |
|---|---|
| [`ADOPTING.md`](ADOPTING.md) | To adopt the system: requirements, every configuration value, pairing, the model key, HTTPS, the integration seams, and a prompt you can hand your own coding agent. |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Components, data flow, what is on disk, and how durability and restarts work. |
| [`SAFETY.md`](SAFETY.md) | The honest safety model: what each control stops, and what it does not. |
| [`FEATURES.md`](FEATURES.md) | Every function: storage facts, the media window and trash, unused apps, cache clearing, chat, proposals and approvals, schedules, voice, diagnostics — and what each never does. |
| [`guide/firebase.md`](guide/firebase.md) | Optional: running the instrumentation suite on Firebase Test Lab devices. |
| [`guide/observability.md`](guide/observability.md) | To answer "what is happening on the server and the phone": pointing the CLI at an instance, the decision guide, every command and flag, crash reports, self-update diagnostics. |
| [`guide/disposable-instance.md`](guide/disposable-instance.md) | To run the server against throwaway state for a probe or contract test. |
| [`guide/code-map.md`](guide/code-map.md) | To find the right source file fast: one line per `server/src` area and per app package. |
