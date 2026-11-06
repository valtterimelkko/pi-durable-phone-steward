# Phone Steward

A durable AI agent on a small server that keeps an Android phone tidy: the phone app gathers
storage, usage and media facts, the agent turns them into clean-up proposals, and every
proposal waits for the owner's approval on the phone before anything happens — the one
exception being an opt-in "auto mode" standing rule for the rolling media window. Media always
goes to Android's trash first, so it can be restored. Deletion is age-based only — a rolling
media window — nothing is judged for quality, and no duplicates are deleted.

## Project story

When Pi Durable reached its 1.0 release, I got quite excited. I had been a long-term user of the Pi coding agent, and Pi Durable was the piece I had been missing: an always-on agent, much like the bots people run (Grok Bot, Hermes Agent and the like), but one whose behaviour I fully control.

Permanent roles were not a new idea for me. Little by little I had been working out how permanent roles could live on the VPS I use for all my AI agent workflows. I wanted agents that keep the system tidy within a set of boundary rules, and I wanted more control than any of the bot ecosystems offered. Pi Durable turned out to be the perfect tool for that.

Now I have a system that keeps the server quite clean. The roles keep the disk tidy, keep packages and software up to date, check that no repository is left with uncommitted changes, and keep repository documentation current. When they notice something harder, they can raise a card for me, which I can hand to another agent (that part is optional).

They also keep my phone clean. My Android phone is an old one, so the agent does not run on the phone: it runs on the server and just has a door to the phone.

Thank you to the Pi Durable team for all the hard work.

*(Phone Steward is the "door to the phone" half of that system. The server-side permanent roles live in [pi-durable-permanent-roles](https://github.com/valtterimelkko/pi-durable-permanent-roles).)*

## What it does

- **A server half** (`server/`, TypeScript on [Pi Durable](https://pi.dev)): a durable agent
  process that owns a SQLite state file, talks to a model provider, and exposes a small
  HTTPS API for the phone.
- **A phone half** (`app/`, Kotlin + Jetpack Compose): a small Android app that pairs with
  the server, gathers storage/usage/media facts, runs the approved clean-up steps on the
  phone, and shows the chat.

The agent chats with you about your phone, proposes clean-ups (trash old media, clear
caches, uninstall apps you never open, delete leftovers from your download folder), and
carries out exactly what you approved — nothing more.

## Use cases

- **Keeping an old phone tidy.** The phone is too small to run a model; the agent runs on
  any always-on machine you already have and reaches the phone through the app.
- **Age-based photo clean-up with a safety net.** A rolling window (for example "photos
  older than four months") with protected items excluded: anything in a folder or album
  named Keep, anything marked favourite, anything you restored. Media goes to Android's
  trash first, and a second Steward trash keeps file deletions for 30 days.
- **Finding unused apps.** Usage facts surface the apps you never open; uninstalling them
  is a proposal, and apps on a never-touch list are refused.
- **An agent you can chat to about your phone.** Ask what is filling the phone, what
  changed, what is safe to remove; every answer comes from the phone's own facts, and
  every removal still needs your approval.

## How it fits with Pi Durable

The server is a [Pi Durable](https://pi.dev) application: it uses Pi Durable's durable
tasks, documents and conversations on SQLite, so a crash or restart never loses a queued
job, an approval or a chat message. Everything external — the memory/orchestration tooling
behind the agent, owner notifications, the model key — sits behind small, documented seams
(`docs/ADOPTING.md`, "Bring your own orchestration and memory"), all off by default.

## Quick start

```bash
# Server (Node 24)
cd server && npm ci && npm test
PHONE_STEWARD_STATE_DIR=/tmp/steward-state PHONE_STEWARD_PORT=7461 \
  ZAI_API_KEY=YOUR_ZAI_API_KEY node --experimental-strip-types src/main.ts

# Android app (Android SDK 35)
cd app && ./gradlew assembleDebug        # install the APK on your phone and pair
```

Full walk-through: [`docs/ADOPTING.md`](docs/ADOPTING.md).

## Documentation

- [`docs/ADOPTING.md`](docs/ADOPTING.md) — requirements, configuration, pairing, HTTPS, seams
- [`docs/FEATURES.md`](docs/FEATURES.md) — every function, and what each never does
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — components, data flow, durability
- [`docs/SAFETY.md`](docs/SAFETY.md) — the honest safety model
- [`docs/protocol.md`](docs/protocol.md) — the phone/server contract
- [`docs/README.md`](docs/README.md) — the full docs index

## Status

Personal project, shared as is. It runs one phone and one server; the docs assume a
reader who can run a Node process and build an Android app. No support is promised, but
the architecture and the contract docs are written to be adopted.

## Licence

[MIT](LICENSE) — the copyright holder is named in the licence file.
