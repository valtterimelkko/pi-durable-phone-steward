/**
 * Which kind of turn a tool call is running in (correction 1, review finding 2).
 *
 * The app-level run mirror (`Runs`) is a lagging record: a phone result's run stays open
 * across later owner turns, and an owner run can be left open by a lost close, so the
 * oldest-open-run reason attributes the wrong turn in both directions. The reliable seam
 * is the turn's own input: every server-initiated input's content starts with a marker
 * this server writes itself, and the owner's turns are everything else. A tool reads the
 * user entry that started its turn (the nearest `pi.user` entry below its own assistant
 * entry) and classifies it here. Fail closed: no user entry, no owner turn.
 */
import type { ConversationId, TaskId, Tx } from "@earendil-works/pi-durable";

export type TurnOrigin = "owner" | "weekly_check" | "phone_result" | "photo_window" | "phone_use" | "fresh_start" | "unknown";

/** The markers, longest-prefix first where they overlap. Kept in one place so a new server turn kind adds its line here. */
const SERVER_TURN_MARKERS: ReadonlyArray<readonly [prefix: string, origin: TurnOrigin]> = [
	["[phone result ", "phone_result"],
	["[phone expired ", "phone_result"],
	["[schedule weekly_check ", "weekly_check"],
	["[schedule photo_window ", "photo_window"],
	["[phone use ended] ", "phone_use"],
	["[fresh start", "fresh_start"],
];

/** The origin of one turn input: a server turn when it carries a server marker, an owner turn otherwise. */
export function originOfInputText(text: string): TurnOrigin {
	const trimmed = text.trimStart();
	for (const [prefix, origin] of SERVER_TURN_MARKERS) {
		if (trimmed.startsWith(prefix)) return origin;
	}
	return "owner";
}

/**
 * The origin of one input entry. A reset entry is never the owner's own input (a fresh start writes the
 * server-note marker before its brief), so it is a fresh start whatever its text: fail closed.
 */
export function originOfEntry(kind: string, text: string): TurnOrigin {
	if (kind === "pi.reset") return "fresh_start";
	return originOfInputText(text);
}

const WALK_BACK_LIMIT = 500;

/**
 * The origin of the turn a tool call is running in: the nearest user entry below the
 * call's assistant entry, classified by its leading marker. `"unknown"` (fail closed)
 * when the call's task record or the input entry cannot be found.
 */
export async function currentTurnOrigin(tx: Tx, conversationId: ConversationId, taskId: TaskId): Promise<TurnOrigin> {
	const taskRecord = await tx.task(taskId);
	const assistant = (taskRecord?.input as { assistant?: number } | undefined)?.assistant;
	if (typeof assistant !== "number" || !Number.isFinite(assistant)) return "unknown";
	let cursor: import("@earendil-works/pi-durable").Cursor | undefined;
	let scanned = 0;
	while (scanned < WALK_BACK_LIMIT) {
		// Exclusive of the assistant entry itself: the walk starts just below it.
		const maxEntryId = cursor === undefined ? (assistant - 1) as never : (cursor as never);
		const page = await tx.scanEntries({ conversationId, maxEntryId }, 100, undefined);
		for (const entry of page.items) {
			scanned += 1;
			// A fresh-start brief can sit on a reset entry's handoff text.
			if (entry.kind !== "pi.user" && entry.kind !== "pi.reset") continue;
			const message = entry.model?.[0];
			const text = typeof (message as { content?: unknown } | undefined)?.content === "string" ? ((message as { content: string }).content) : "";
			return originOfEntry(entry.kind, text);
		}
		if (page.next === undefined || page.items.length === 0) return "unknown";
		cursor = page.next;
	}
	return "unknown";
}

/** The refusal's human text for a non-owner origin (correction 1 item 7: never "a idle"). */
export function turnOriginText(origin: TurnOrigin): string {
	switch (origin) {
		case "weekly_check":
			return "the scheduled weekly check";
		case "phone_result":
			return "a phone result";
		case "photo_window":
			return "the photo window";
		case "phone_use":
			return "the phone session's hand-back";
		case "fresh_start":
			return "a fresh start";
		default:
			return "an unrecognised source";
	}
}
