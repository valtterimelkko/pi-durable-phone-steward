# Disposable instance recipe

How to run the server against throwaway state — for manual probes and contract tests.
If you also run a standing instance (say `phone-steward.service` on
`/var/lib/phone-steward`, port 7461), leave it alone: never point any of this at it.

```bash
# 1. Scratch state and a free loopback port (never 7461)
W=$(mktemp -d /tmp/ps-check.XXXX); mkdir -p "$W/state"
PORT=17461   # pick a port nothing else listens on: ss -ltn | grep $PORT
# EVERY CLI call below must see this. Without it, bin/steward defaults to
# /var/lib/phone-steward and talks to a running service there.
export PHONE_STEWARD_STATE_DIR="$W/state"

# 2. Start the server from your checkout, everything external switched off
cd <your-checkout>/server
env -u NODE_ENV \
  PHONE_STEWARD_STATE_DIR="$W/state" PHONE_STEWARD_HOST=127.0.0.1 PHONE_STEWARD_PORT=$PORT \
  PHONE_STEWARD_SCHEDULES=off PHONE_STEWARD_ORCHESTRATION=off \
  node --experimental-strip-types src/main.ts > "$W/server.log" 2>&1 &
echo $! > "$W/spid"

# 3. Wait for it, then use it
for i in $(seq 30); do curl -s -o /dev/null "http://127.0.0.1:$PORT/v1/status" && break; sleep 1; done

# 4. Pair a scripted phone (no real device) if you need job traffic
CODE=$(PHONE_STEWARD_STATE_DIR="$W/state" <your-checkout>/bin/steward pair 2>&1 | grep -oE '[A-Z0-9]{4}-[A-Z0-9]{4}' | head -1)
env -u NODE_ENV node --experimental-strip-types scripts/fake-phone.ts \
  --base-url "http://127.0.0.1:$PORT" --code "$CODE" --token-file "$W/token" &

# 5. Always stop what you started, and delete the scratch dir
kill "$(cat "$W/spid")"; rm -rf "$W"
```

Notes:

- `PHONE_STEWARD_ORCHESTRATION=off` needs no external store. To exercise the outbox
  against a stub command, set `PHONE_STEWARD_ORCHESTRATION=on` plus
  `PHONE_STEWARD_ORCHESTRATION_CMD` pointing at a small script (see `docs/ADOPTING.md`).
- `env -u NODE_ENV` matters if your shell exports `NODE_ENV=production`, which can skip
  devDependencies on installs and change some behaviour.
- The CLI against a disposable instance needs the same `PHONE_STEWARD_STATE_DIR` you
  started it with; see `observability.md` for what each command then answers.
- Model calls need a real provider key (`ZAI_API_KEY` or the matching
  `<PROVIDER>_API_KEY`, read at runtime into one process's environment). Without a key
  the server still serves the API and the CLI.
- If you keep a standing instance, check before and after a run that its state directory
  holds exactly what it held before — nothing new should ever appear there.
