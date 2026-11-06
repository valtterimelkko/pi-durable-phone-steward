/**
 * Gap fill for Pi Durable 1.5.0 conversation views .
 *
 * `ConversationView.entries` is a window, not the whole transcript: a watch starts with the
 * newest 50 entries and, once it would hold more than 100, drops the oldest in one splice down
 * to 50 (`ConversationView.before` then names the entry just before the window). A listener that
 * awaits work inside its callback — both chat bridges do — can therefore miss entries entirely
 * when a backlog builds up.
 *
 * The bridges keep the newest entry id they have handled; when the window starts after it, the
 * missing range is paged back out of the conversation's fork-aware history (`entries()`, newest
 * first, bounded by the window's own start) before the window itself is diffed. Ids are compared
 * as numbers and never constructed, so no brand cast is involved.
 */
import type { Context } from "@earendil-works/chord";
import type { Conversation, ConversationView, Cursor, EntryRecord } from "@earendil-works/pi-durable";

/** One `entries()` page; comfortably above the window's own 100 so a backlog pages in one or two calls. */
export const GAP_PAGE_LIMIT = 100;

/**
 * Entries the window skipped between `newestSeen` and the window's start, oldest first (empty
 * when the window continues what was already seen).
 */
export async function missedEntries(
	conversation: Conversation,
	newestSeen: number | undefined,
	view: Readonly<ConversationView>,
	context: Context,
): Promise<EntryRecord[]> {
	if (newestSeen === undefined) return [];
	const before = view.before;
	if (before === undefined || Number(before) <= newestSeen) return [];
	const first = view.entries[0];
	if (first !== undefined && Number(first.id) <= newestSeen + 1) return [];
	const missing: EntryRecord[] = [];
	let cursor: Cursor | undefined;
	for (;;) {
		const page = await conversation.entries({ maxEntryId: before, order: "descending" }, GAP_PAGE_LIMIT, cursor, context);
		for (const entry of page.items) {
			if (Number(entry.id) <= newestSeen) return missing.reverse();
			missing.push(entry);
		}
		if (page.next === undefined) return missing.reverse();
		cursor = page.next;
	}
}
