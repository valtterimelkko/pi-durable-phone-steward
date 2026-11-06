/**
 * The durable, deduplicated notify task behind every owner ping: only a reminder, a ready
 * batch or a notable proposal notifies, through `steward.notify`.
 *
 * Delivery is at-most-once by design — one duplicated ping is worse than one lost in a
 * crash window. The `send` phase marks the key sent in storage BEFORE calling the sender;
 * a crash after the mark replays to "already sent" and never sends twice. Crash gates
 * `notify:after-mark` (between mark and send) and `notify:after-send` (between send and
 * the terminal commit) let crash tests SIGKILL on both sides of the send.
 *
 * The sender is injected per process: production wires the command from
 * `PHONE_STEWARD_NOTIFY_CMD` when it is set, and the JSONL stub otherwise (see
 * `fileSender`); tests set a recording sender, so a test can never send for real.
 */
import { existsSync, appendFileSync, mkdirSync } from "node:fs";
import { spawn } from "node:child_process";
import { defineDoc, defineTask, type TaskId, type Tx } from "@earendil-works/pi-durable";
import { crashPoint } from "./crash-gate.ts";
import { newId } from "./ids.ts";
import { periodicCheckpoint } from "./checkpoint.ts";
import { join } from "node:path";

export type NotifyCategory = "reminder" | "batch" | "proposal" | "crash";

export type NotifyInput = { dedupeKey: string; category: NotifyCategory; title: string; body: string };

export const Notifications = defineDoc<{
	/** Sent notifications by dedupe key — the durable de-duplication record. */
	sent: Record<string, { sentAtMs: number; category: string }>;
	/** Queued task ids by dedupe key, so queueNotify is idempotent. */
	queued: Record<string, string>;
}>({
	kind: "steward.notifications",
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: periodicCheckpoint(),
	scope: "session",
	initial: () => ({ sent: {}, queued: {} }),
});

export type NotifySender = (title: string, body: string) => Promise<void>;

let sender: NotifySender | undefined;

/**
 * Set the process's notify sender. `undefined` resets to the no-op default, which records
 * nothing and sends nothing — the default that tests and disposable instances keep.
 */
export function setNotifySender(next: NotifySender | undefined): void {
	sender = next;
}

export function currentNotifySender(): NotifySender | undefined {
	return sender;
}

/** Queue (or find) the notify task for a dedupe key, inside the caller's commit. */
export async function queueNotify(
	tx: Tx,
	conversationId: Parameters<Tx["doc"]>[1] extends never ? never : import("@earendil-works/pi-durable").ConversationId,
	input: NotifyInput,
	nowMs: number,
): Promise<TaskId<null>> {
	const doc = await tx.doc(Notifications);
	const existing = doc.queued[input.dedupeKey];
	if (existing !== undefined) return existing as unknown as TaskId<null>;
	const taskId = await tx.createTask(
		NotifyTask,
		input,
		{ ownership: { kind: "conversation" }, conversationId, background: true },
	);
	doc.queued[input.dedupeKey] = `${taskId}`;
	void nowMs;
	return taskId;
}

type NotifyState = { phase: "send" };

export const NotifyTask = defineTask<NotifyInput, NotifyState, null>({
	name: "steward.notify",
	version: 1,
	initial: () => ({ phase: "send" }),
	phases: {
		send: async (task, runtime, context) => {
			const { dedupeKey, title, body } = task.input;
			await crashPoint("notify:before-mark");
			const seen = await runtime.snapshot(Notifications, context);
			if (seen?.sent[dedupeKey] !== undefined) {
				// Sent by an earlier attempt of this task (or a twin): never send twice.
				await runtime.commit(() => ({ status: "terminal", outcome: { status: "completed", result: null } }), context);
				return;
			}
			// The durable mark lands BEFORE the send: a crash after it replays to the
			// already-sent check above, so one ping is never sent twice. The commit returns
			// undefined: the phase state is unchanged, so the phase is not re-entered.
			await runtime.commit(
				async (tx) => {
					const doc = await tx.doc(Notifications);
					doc.sent[dedupeKey] = { sentAtMs: Date.now(), category: task.input.category };
					return undefined;
				},
				context,
			);
			// Between the durable mark and the actual send: a crash here loses one ping and
			// never duplicates it — the accepted at-most-once trade-off.
			await crashPoint("notify:after-mark");
			const deliver = sender ?? (async () => undefined);
			await deliver(title, body);
			// Between the send and the terminal commit: a crash here replays to the
			// already-sent check above, so the ping is never sent twice.
			await crashPoint("notify:after-send");
			await runtime.commit(() => ({ status: "terminal", outcome: { status: "completed", result: null } }), context);
		},
	},
	abort: async (_task, runtime, context) => {
		await runtime.commit(() => ({ status: "terminal", outcome: { status: "aborted" } }), context);
	},
});

/**
 * The configured sender: the adopter's own notification command, run detached as
 * `<command> milestone <title> <body>`. Returns a promise that resolves when the command
 * exits; failures are logged, never thrown (a lost ping is acceptable, a crashed task is
 * not).
 */
export function commandSender(command: string): NotifySender {
	return (title, body) =>
		new Promise((resolve) => {
			if (!existsSync(command)) {
				console.error(`[steward] notify command missing: ${command} (notification dropped)`);
				resolve();
				return;
			}
			const child = spawn(command, ["milestone", title, body], {
				stdio: ["ignore", "ignore", "ignore"],
				detached: true,
			});
			child.on("error", (error) => console.error(`[steward] notify failed: ${String(error)}`));
			child.on("close", () => resolve());
		});
}

/**
 * The default stub sender: append one JSON line per notification to
 * `<stateDir>/notifications.jsonl`, so an adoption works with no external system and the
 * pings the server would have sent stay inspectable on disk.
 */
export function fileSender(stateDir: string): NotifySender {
	const path = join(stateDir, "notifications.jsonl");
	return (title, body) =>
		new Promise((resolve) => {
			try {
				mkdirSync(stateDir, { recursive: true });
				appendFileSync(path, `${JSON.stringify({ atMs: Date.now(), title, body })}\n`);
			} catch (error) {
				console.error(`[steward] notify file write failed: ${String(error)}`);
			}
			resolve();
		});
}

/** The sender for a config: the command when one is configured, the JSONL stub otherwise. */
export function notifySenderFromConfig(stateDir: string, command?: string): NotifySender {
	return command !== undefined && command !== "" ? commandSender(command) : fileSender(stateDir);
}

/** A short unique id for a notification record (used by nothing durable — kept for tests). */
export function newNotifyRecordId(): string {
	return newId("nt");
}
