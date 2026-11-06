/**
 * Pi Durable 1.5.0 reports what went wrong through `HarnessOptions.onReport(report)`: one
 * `Report`, tagged by `kind`, with the error inside. Four of its kinds mean the steward stopped
 * doing something the owner asked for, or stopped serving at all, and they reach the owner
 * through the existing durable notify path (`queueNotify` → `NotifyTask` → `Notifications.sent`),
 * which already refuses a second send for a dedupe key — so a restart loop cannot turn into a
 * ping storm.
 *
 * 1.5.0 no longer parks a restart-limited task: `task_parked` is gone, `session_failed` is
 * `storage_failed`, and a task that crashes past `settings.restartLimit` ends instead, reported
 * as `task_faulted` (a task of an extension's kind) or `task_interrupted` (a tool call, a
 * compaction, a generation, or a run stopped while the model was answering). Both mean the work
 * stopped, so both ping the owner.
 *
 * Everything else (`task_error`, `task_outdated`, `extension_error`, `host_error`,
 * `listener_error`) is logged here and is not an owner alarm.
 */
import type { ConversationId, Harness, Report, ReportTask } from "@earendil-works/pi-durable";
import type { Context } from "@earendil-works/chord";
import { currentNotifySender, queueNotify, type NotifyInput } from "./notify.ts";

export type { NotifyInput };

/** The report kinds that mean "something the owner asked for did not happen". */
export const HARNESS_REPORT_KINDS = ["task_faulted", "task_interrupted", "migration_failed", "storage_failed"] as const;

export type HarnessReportKind = (typeof HARNESS_REPORT_KINDS)[number];

/** Bound on the error text copied into a ping: enough to identify the fault, never a stack. */
export const REPORT_TEXT_CHARS = 200;
/** Bound on the in-process de-duplication memory. */
export const REPORT_SEEN_LIMIT = 200;
/** Bound on the reports held while the harness opens: a failed migration is one line, never a flood. */
export const HELD_REPORT_LIMIT = 20;

export type HarnessReporterDeps = {
	/** Process log; never receives a stack trace it did not get from the caller. */
	log(line: string): void;
	/**
	 * Is the durable notify path usable yet? Pi Durable raises `migration_failed` *inside*
	 * `Harness.open`, before the steward has a conversation to queue into; a report that arrives
	 * then is held (bounded) and delivered by `flush()`. Default `() => true`.
	 */
	ready?(): boolean;
	/**
	 * Queue one owner notification through the durable path. Resolves `true` once the task is
	 * queued (or was already queued), `false` when no durable path exists; may reject.
	 */
	notify(input: NotifyInput): Promise<boolean>;
	/**
	 * Deliver a message on the process-level path, which needs no open Session. A failed Session
	 * rejects every commit, so `storage_failed` can only go this way. Default: the installed
	 * sender (`currentNotifySender()`, `notify.sh` in production); no sender is a logged failure.
	 */
	send?(title: string, body: string): Promise<void>;
};

/** The steward's `onReport`, plus the flush for what was held while the harness was opening. */
export type HarnessReports = ((report: Report) => void) & {
	/** Deliver the notifications held while the harness was still opening; call once the target exists. */
	flush(): Promise<void>;
};

function isReportKind(kind: string): kind is HarnessReportKind {
	return (HARNESS_REPORT_KINDS as readonly string[]).includes(kind);
}

/** One line of error text: whitespace collapsed, bounded, no stack. */
function errorText(error: unknown): string {
	const raw = error instanceof Error ? error.message : String(error);
	const text = raw.replace(/\s+/g, " ").trim();
	return text.length > REPORT_TEXT_CHARS ? `${text.slice(0, REPORT_TEXT_CHARS)}…` : text;
}

/** The task a report is about, when it has one. */
function reportTask(report: Report): ReportTask | undefined {
	return "task" in report ? report.task : undefined;
}

/** Why the work stopped, in the owner's words. */
function stoppedBecause(report: Report & { kind: "task_interrupted" }): string {
	return report.reason === "restart_limit"
		? `it was cut off ${report.restarts} time(s) in a row while the steward was restarting`
		: "the steward's host kept stopping while the model was answering";
}

/** The owner's ping for one report kind: a title, a bounded body and the durable dedupe key. */
export function harnessReportNotification(report: Report): (NotifyInput & { category: "crash" }) | undefined {
	if (!isReportKind(report.kind)) return undefined;
	const kind = reportTask(report)?.kind ?? "";
	const text = errorText(report.error);
	const dedupeKey = `harness:${report.kind}:${kind}:${text}`;
	switch (report.kind) {
		case "task_faulted": {
			const why = report.reason === "restart_limit" ? "its abort handler was cut off repeatedly" : "its phase threw";
			return {
				dedupeKey,
				category: "crash",
				title: "Phone Steward task failed",
				body: `The ${kind} task failed: ${why} — ${text}. See bin/steward status.`,
			};
		}
		case "task_interrupted":
			return {
				dedupeKey,
				category: "crash",
				title: "Phone Steward work stopped",
				body:
					`The ${kind} task ended: ${stoppedBecause(report)} — ${text}. ` +
					"That work stopped for good, so it runs again at its next slot; ask in Chat if you want it now.",
			};
		case "migration_failed":
			return {
				dedupeKey,
				category: "crash",
				title: "Phone Steward task cannot migrate",
				body: `A stored ${kind} task cannot be migrated to its current definition and is blocked — ${text}.`,
			};
		case "storage_failed":
			return {
				dedupeKey,
				category: "crash",
				title: "Phone Steward harness failed",
				body: `The harness closed after a storage error, so the service exits and restarts — ${text}.`,
			};
	}
}

/**
 * The steward's durable owner-notification path for harness reports: queue the ping as a
 * `NotifyTask` inside a commit, so `Notifications.queued`/`sent` keep it to one send per
 * dedupe key across restarts. Resolves `false` while there is no harness or conversation yet
 * (start-up, a direct-state reader); a queuing error rejects and the reporter logs it.
 */
export function durableReportNotifier(
	target: () => { harness: Harness; conversationId: ConversationId } | undefined,
	context: Context,
): (input: NotifyInput) => Promise<boolean> {
	return async (input) => {
		const ready = target();
		if (ready === undefined) return false;
		await ready.harness.commit((tx) => queueNotify(tx, ready.conversationId, input, Date.now()), context);
		return true;
	};
}

/**
 * Build the steward's `onReport`. It never throws (`onReport` failures would only be reported
 * again), never pings the owner twice for the same report, and logs every report it sees.
 *
 * A report raised while the harness is still opening (`migration_failed`) is held and delivered
 * by `flush()`: dropping it would mean the owner never hears about a task that can never run.
 */
export function createHarnessReporter(deps: HarnessReporterDeps): HarnessReports {
	const claimed = new Set<string>();
	const held: Array<{ kind: HarnessReportKind; notification: NotifyInput }> = [];
	const ready = deps.ready ?? (() => true);
	const send =
		deps.send ??
		(async (title: string, body: string): Promise<void> => {
			const sender = currentNotifySender();
			if (sender === undefined) throw new Error("no notify sender is installed");
			await sender(title, body);
		});

	async function deliver(notification: NotifyInput, kind: string): Promise<void> {
		const queued = await deps.notify(notification);
		if (queued) return;
		claimed.delete(notification.dedupeKey);
		deps.log(`[steward] harness report ${kind}: no durable notify path yet; logged only`);
	}

	const report = (incoming: Report): void => {
		try {
			if (!isReportKind(incoming.kind)) {
				deps.log(`[steward] harness report ${incoming.kind}: ${errorText(incoming.error)}`);
				return;
			}
			const kind = incoming.kind;
			const notification = harnessReportNotification(incoming);
			if (notification === undefined) {
				deps.log(`[steward] harness report ${kind}: ${errorText(incoming.error)}`);
				return;
			}
			if (claimed.has(notification.dedupeKey)) {
				deps.log(`[steward] harness report ${kind}: ${errorText(incoming.error)} (already reported; owner not pinged again)`);
				return;
			}
			claimed.add(notification.dedupeKey);
			if (claimed.size > REPORT_SEEN_LIMIT) claimed.clear();
			if (kind === "storage_failed") {
				// Review F2: the Session failed and closed, so every commit on it rejects. This one
				// goes straight to the installed sender, fire-and-forget, before the process exits.
				deps.log(`[steward] harness report storage_failed: ${errorText(incoming.error)} (sending to the owner directly)`);
				void send(notification.title, notification.body).catch((sendError: unknown) => {
					claimed.delete(notification.dedupeKey);
					deps.log(`[steward] harness report storage_failed: could not send the owner notification: ${errorText(sendError)}`);
				});
				return;
			}
			if (!ready()) {
				// Raised inside Harness.open: no conversation to queue into yet. Keep the newest few only.
				held.push({ kind, notification });
				if (held.length > HELD_REPORT_LIMIT) held.shift();
				deps.log(`[steward] harness report ${kind}: ${errorText(incoming.error)} (held until the harness is open)`);
				return;
			}
			deps.log(`[steward] harness report ${kind}: ${errorText(incoming.error)} (pinging the owner)`);
			// Queue the ping now, on this turn; only its settlement is deferred.
			const queueing = deps.notify(notification);
			void Promise.resolve(queueing)
				.then((queued) => {
					if (queued) return;
					// No durable path (a reader, a read-only open): leave the key free so a later report retries.
					claimed.delete(notification.dedupeKey);
					deps.log(`[steward] harness report ${kind}: no durable notify path yet; logged only`);
				})
				.catch((notifyError: unknown) => {
					claimed.delete(notification.dedupeKey);
					deps.log(`[steward] harness report ${kind}: could not queue the owner notification: ${errorText(notifyError)}`);
				});
		} catch (reportError) {
			deps.log(`[steward] harness report could not be handled: ${errorText(reportError)}`);
		}
	};

	const flush = async (): Promise<void> => {
		const pending = held.splice(0);
		for (const { kind, notification } of pending) {
			deps.log(`[steward] harness report ${kind}: delivering what was held while opening`);
			try {
				await deliver(notification, kind);
			} catch (notifyError: unknown) {
				claimed.delete(notification.dedupeKey);
				deps.log(`[steward] harness report ${kind}: could not queue the owner notification: ${errorText(notifyError)}`);
			}
		}
	};

	return Object.assign(report, { flush });
}
