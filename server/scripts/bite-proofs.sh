#!/usr/bin/env bash
 # Bite proofs: each probe mutates a SCRATCH COPY of server/
# (never the worktree), runs the named test with --no-cache there, and expects it to FAIL
# with the mutation and PASS after the revert. The real node_modules is referenced
# read-only through a symlink created inside the scratch copy (never in the repo).
# Exit code: 0 iff every probe bit and every revert recovered; non-zero otherwise.
set -u
# The checkout this script belongs to (any worktree can run its own probes; the runner
# never writes into the worktree — every mutation lands in the scratch copy).
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SERVER="$ROOT/server"
PROBES="$SERVER/scripts/probes"

SCRATCH="$(mktemp -d /tmp/p2-bite-XXXXXX)"
FAILED=0
trap 'rm -rf "$SCRATCH"' EXIT

mkdir -p "$SCRATCH/server"
(tar -C "$SERVER" --exclude=node_modules -cf - .) | (tar -C "$SCRATCH/server" -xf -)
chmod -R u+w "$SCRATCH/server"
ln -s "$SERVER/node_modules" "$SCRATCH/server/node_modules"
export HOME="$SCRATCH/home"
mkdir -p "$HOME"

probe() {
	local name="$1" test="$2" file="$3" patch="$PROBES/$4"
	local scratch_server="$SCRATCH/server"
	local out="/tmp/probe-out-$(echo "$name" | tr ' /:' '__').txt"
	# Optional: PROBE_ONLY='<regex>' runs only the probes whose name matches (a quick re-run).
	if [[ -n "${PROBE_ONLY:-}" && ! "$name" =~ $PROBE_ONLY ]]; then return; fi

	if ! python3 "$PROBES/mutate.py" "$scratch_server/$file" "$patch" > /dev/null; then
		echo "BITE $name: FAIL — mutation could not be applied (anchor drift)"
		FAILED=1
		return
	fi
	if ! git -C "$ROOT" diff --quiet -- "$file"; then
		echo "BITE $name: FAIL — worktree file was modified (runner leak)"
		git -C "$ROOT" checkout -- "$file"
		FAILED=1
		return
	fi

	# Run vitest from INSIDE the scratch server with its own (symlinked) vitest binary.
	if (cd "$scratch_server" && timeout 240 env -u NODE_ENV ./node_modules/.bin/vitest run --no-cache "$test" > "$out" 2>&1); then
		echo "BITE $name: FAIL — mutation did not make the test fail (does not bite)"
		FAILED=1
		return
	fi

	python3 "$PROBES/mutate.py" --reverse "$scratch_server/$file" "$patch"
	if (cd "$scratch_server" && timeout 240 env -u NODE_ENV ./node_modules/.bin/vitest run --no-cache "$test" > "$out" 2>&1); then
		echo "BITE $name: OK — mutation → test failed; revert → passed"
	else
		echo "BITE $name: FAIL — revert did not recover the test"
		FAILED=1
	fi

	if ! git -C "$ROOT" diff --quiet -- "$file"; then
		echo "BITE $name: FAIL — worktree file was modified (runner leak)"
		git -C "$ROOT" checkout -- "$file"
		FAILED=1
	fi
}

probe "find-before-create (queueJob dedupe)" \
	"test/jobs.test.ts" "src/jobs.ts" "find-before-create.json"
probe "result-conflict check" \
	"test/jobs.test.ts" "src/jobs.ts" "result-conflict.json"
probe "04.9 raced conflict counter" \
	"test/jobs.test.ts" "src/jobs.ts" "raced-counter.json"
probe "protection filter (photo batch)" \
	"test/photo-window-logic.test.ts" "src/photo-window-logic.ts" "protection-filter.json"
probe "reminder-before-cutoff (batch only from earlier reminders)" \
	"test/photo-window-logic.test.ts" "src/photo-window-logic.ts" "reminder-before-cutoff.json"
probe "notify dedupe (crash replay after the send)" \
	"test/crash-w2.test.ts" "src/notify.ts" "notify-dedupe.json"
probe "guard hook (beforeTool allowlist)" \
	"test/tools.test.ts" "src/tools.ts" "guard-hook.json"
probe "04.1 CLI silent production default" \
	"test/cli.test.ts" "src/cli.ts" "cli-default.json"
probe "04.2 stale candidates (both guards off)" \
	"test/stale-candidates.test.ts" "src/rule-tasks.ts" "stale-candidates.json"
probe "04.4 decision ledger shares the commit" \
	"test/crash-w2.test.ts" "src/proposals.ts" "decision-ledger.json"
probe "04.5 rule_batch full MediaRefs" \
	"test/rule-batch-media.test.ts" "src/rule-tasks.ts" "media-refs.json"
probe "05.1 reporter checkpoint-before-submit (pre-submit gap)" \
	"test/crash-w2.test.ts" "src/jobs.ts" "05-1-checkpoint-first.json"
probe "05.1 reporter randomised requestId (post-submit gap)" \
	"test/crash-w2.test.ts" "src/jobs.ts" "05-1-random-request-id.json"
probe "05.2 reminder scope (initial drops the stamp)" \
	"test/stale-candidates.test.ts" "src/rule-tasks.ts" "reminder-scope.json"
probe "A6 trailing-slash normalisation (DCIM/Camera matches DCIM/Camera/)" \
	"test/folder-scope.test.ts" "src/folder-scope.ts" "a6-slash-normalisation.json"
probe "A6 subtree prefix (WhatsApp deep folder)" \
	"test/folder-scope.test.ts" "src/folder-scope.ts" "a6-subtree-prefix.json"
probe "A6 clear_all_caches item kind" \
	"test/tools.test.ts" "src/proposals.ts" "a6-clear-all-caches-kind.json"
probe "A5/O5 an error report never pings the owner" \
	"test/crash-reports.test.ts" "src/crash-reports.ts" "a5-error-never-pings.json"
probe "O6 a diagnostics result never reaches the conversation" \
	"test/diagnostics.test.ts" "src/jobs.ts" "o6-diagnostics-never-reaches-conversation.json"
probe "correction 01-1 direct-state reads never resume" \
	"test/cli-resume.test.ts" "src/steward.ts" "c1-direct-state-never-resumes.json"
probe "correction 01-2 log lines carry route templates, not raw paths" \
	"test/cli-observability.test.ts" "src/http.ts" "c2-log-route-template.json"
probe "correction 01-3 diagnostics results are cleaned on the server" \
	"test/diagnostics.test.ts" "src/diagnostics.ts" "c3-diagnostics-cleaned.json"
probe "correction 01-4 logs --json emits lines" \
	"test/cli-observability.test.ts" "src/cli.ts" "c4-logs-json.json"
probe "S7 the weekly settle builds its cache batch once per run (replay)" \
	"test/weekly-check.test.ts" "src/rule-tasks.ts" "s7-weekly-batch-once.json"
probe "S8 a weekly check's phone results settle silently (no turn per result)" \
	"test/phone-result-turns.test.ts" "src/jobs.ts" "s8-weekly-results-silent.json"
probe "S8 a phone-result turn outside a review carries the open list" \
	"test/phone-result-turns.test.ts" "src/jobs.ts" "s8-open-list-on-results.json"
probe "the screen tools are left out of the default (normal) selection" \
	"test/live-session.test.ts" "src/live/tools.ts" "p7sc-live-excluded-from-default.json"
probe "the server drops password fields from a screen tree" \
	"test/live-tree.test.ts" "src/live/tree.ts" "p7sc-tree-drops-password.json"
probe "the server enforces its own step budget" \
	"test/live-session.test.ts" "src/live/state.ts" "p7sc-server-step-budget.json"

probe "FX-S S13 a one-time code in a tapped node or detail is masked before it is stored" \
	"test/p7-fx-s.test.ts" "src/live/sessions.ts" "fxs-s13-redact-answer.json"
probe "FX-S S13 a code in a screen tree's text is masked on the server too" \
	"test/p7-fx-s.test.ts" "src/live/tree.ts" "fxs-s13-redact-tree.json"
probe "FX-S S14 screen actions are evicted before owner decisions" \
	"test/p7-fx-s.test.ts" "src/documents.ts" "fxs-s14-screen-actions-first.json"
probe "FX-S S15 a late answer to a delivered step is recorded in the ledger" \
	"test/p7-fx-s.test.ts" "src/live/sessions.ts" "fxs-s15-late-recorded.json"
probe "FX-S S16 a session whose app stopped polling is ended by the clock" \
	"test/p7-fx-s.test.ts" "src/live/state.ts" "fxs-s16-idle-sweep.json"

probe "P7 disable_app refuses the never-touch list" \
	"test/shizuku.test.ts" "src/proposals.ts" "p7-disable-never-touch.json"
probe "P7 force_stop refuses the never-touch list" \
	"test/shizuku.test.ts" "src/shizuku.ts" "p7-force-stop-deny.json"
probe "P7 delete_files refuses root, Android/, Keep, DCIM/, Pictures/" \
	"test/files-query.test.ts" "src/files-query.ts" "p7-delete-refusals.json"
probe "P7 delete_files needs a path the phone reported" \
	"test/files-query.test.ts" "src/files-query.ts" "p7-delete-needs-report.json"
probe "S12 unticked items stay kept in the execution report" \
	"test/p7-sf-fixes.test.ts" "src/proposals.ts" "p7-s12-unticked-kept.json"
probe "S11 only open proposals are settled for removed apps" \
	"test/p7-sf-fixes.test.ts" "src/removed-apps.ts" "p7-s11-open-only.json"
probe "A10.2 the weekly batch is trim_caches when Shizuku is ready" \
	"test/weekly-check.test.ts" "src/rule-tasks.ts" "p7-weekly-trim-caches.json"
probe "P7 A10.5 web tools refuse private addresses" \
	"test/web-tools.test.ts" "src/web-safety.ts" "p7-web-private-addresses.json"
probe "P7 A10.5 web_browse refuses committing clicks" \
	"test/web-tools.test.ts" "src/web-tools.ts" "p7-web-committing-click.json"
probe "P7 A10.5 web tools keep their per-run budget" \
	"test/web-tools.test.ts" "src/web-tools.ts" "p7-web-budget.json"
probe "P7 A10.7 the web tools are registered (exact tool set)" \
	"test/tools.test.ts" "src/steward.ts" "p7-web-tools-registered.json"
probe "memory_recall never logs a usage event (--no-log)" \
	"test/orchestration-tools.test.ts" "src/orchestration-tools.ts" "p7-memory-recall-no-log.json"
probe "P7 A10.4 a metered model needs confirmMetered" \
	"test/agent-api.test.ts" "src/agent-settings.ts" "p7-metered-confirm.json"
probe "P7 A10.4 a model change is refused during a run" \
	"test/agent-api.test.ts" "src/agent-settings.ts" "p7-model-busy.json"
probe "P7 A10.4 Pi's OAuth tokens are never refreshed by the steward" \
	"test/pi-registry.test.ts" "src/pi-registry.ts" "p7-auth-no-refresh.json"
probe "A11.1 background compaction starts at 75 % of the model's window" \
	"test/context-policy.test.ts" "src/context-policy.ts" "p7-background-75.json"
probe "A11.2 a big phone result enters the conversation as a summary, not whole" \
	"test/phone-result.test.ts" "src/jobs.ts" "a11-phone-result-summary.json"
probe "A11.3 a restore refuses the delete_files paths (Android/, Keep, DCIM/, Pictures/)" \
	"test/files-restored.test.ts" "src/restored-files.ts" "a11-restored-refusals.json"
probe "A11.3 the server note names the files the owner restored from the Steward trash" \
	"test/files-restored.test.ts" "src/owner-note.ts" "a11-restored-note.json"
probe "the 4 000-character summary cap is absolute for any JSON" \
	"test/phone-result.test.ts" "src/phone-summary.ts" "a11-summary-hard-cap.json"
probe "the S8 weekly message summarises its phone results" \
	"test/weekly-cache.test.ts" "src/weekly-cache.ts" "a11-weekly-summary.json"
probe "P7 A10.9 a fresh start precedes each weekly check" \
	"test/weekly-fresh.test.ts" "src/rule-tasks.ts" "p7-weekly-fresh.json"
probe "P7 S10 SIGTERM closes open streams and connections" \
	"test/shutdown.test.ts" "src/http.ts" "p7-shutdown-connections.json"
probe "P7 S3 the backup keeps only the newest seven" \
	"test/backup.test.ts" "src/backup.ts" "p7-backup-keeps-seven.json"

probe "P10 correction 1 the phone-use guard does not trust the turn origin (fail-open direction)" \
	"test/phone-use.test.ts" "src/tools.ts" "p10-ask-needs-owner-run.json"
probe "P10 correction 1 the turn-origin marker walk classifies owner turns (fail-closed direction)" \
	"test/phone-use.test.ts" "src/turn-origin.ts" "p10-turn-origin-marker.json"
probe "P10 A13.2 a request that is not pending can not be answered and starts no session" \
	"test/live-api.test.ts" "src/live/requests.ts" "p10-answer-needs-pending.json"
probe "P10 A13.1/A13.4 the live guard allows only the four screen tools in a phone session" \
	"test/phone-use.test.ts" "src/live/tools.ts" "p10-live-guard-allowlist.json"
probe "P10 A13.4 the owner's chat goes to the running session, never to the main conversation" \
	"test/live-api.test.ts" "src/http.ts" "p10-owner-chat-goes-to-session.json"
probe "P10 A13.3 a one-time code in the session model's say text is masked" \
	"test/phone-use.test.ts" "src/live/bridge.ts" "p10-say-masked.json"
probe "P10 A13.2 the server note tells the model the owner declined the ask" \
	"test/phone-use.test.ts" "src/owner-note.ts" "p10-decline-note.json"

probe "A15.5 a voice provider key never reaches a response body" \
	"test/voice-api.test.ts" "src/voice/http.ts" "a15-voice-key-never-leaks.json"

probe "A15.5/C1 a hung OpenRouter transcription leg carries a bounded timeout" \
	"test/voice-stt.test.ts" "src/voice/stt.ts" "a15-voice-stt-timeout.json"

probe "A15.1 the session prompt keeps every safety rule" \
	"test/live-session.test.ts" "src/live/tools.ts" "a151-safety-lines-kept.json"
probe "A15.2 the reopen line never fires for a non-steward package" \
	"test/live-session.test.ts" "src/live/tools.ts" "a152-reopen-only-for-steward.json"
probe "A15.9 screen_end still ends the session" \
	"test/live-session.test.ts" "src/live/tools.ts" "a159-screen-end-ends.json"
probe "Pi Durable 1.2 a failed harness ends the process" \
	"test/harness-failure.test.ts" "src/harness-failure.ts" "harness-failure-exit.json"
exit "$FAILED"
