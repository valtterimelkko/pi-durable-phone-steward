/**
 * S9 (protocol A9.1): the server holds the phone's full MediaRefs in its reported
 * `media_index` job results, so a `trash_media` proposal item is completed from there in
 * code and never from what the model typed. The phone re-reads the live MediaStore row and
 * refuses unless `relativePath`, `displayName` and `sizeBytes` match the target (lesson
 * L27: the model ignores prompt-only rules), so a target missing them can never execute.
 */
import type { ConversationId, Tx } from "@earendil-works/pi-durable";
import { badRequest } from "./errors.ts";
import { restoredMediaIds } from "./documents.ts";
import { Jobs, type MediaRef } from "./jobs.ts";
import { Proposals, type ProposalItemInput } from "./proposals.ts";

function isMediaRow(value: unknown): value is MediaRef {
	return value !== null && typeof value === "object" && !Array.isArray(value) && typeof (value as { mediaId?: unknown }).mediaId === "string";
}

/** True when a target already carries what the phone's identity check needs. */
export function isCompleteMediaTarget(target: unknown): boolean {
	const row = target as { relativePath?: unknown; displayName?: unknown; sizeBytes?: unknown } | null | undefined;
	return (
		row !== null && typeof row === "object" &&
		typeof row.relativePath === "string" && typeof row.displayName === "string" &&
		typeof row.sizeBytes === "number" && Number.isFinite(row.sizeBytes)
	);
}

/**
 * Every MediaRef the phone has reported, keyed by `mediaId`, the newest report winning.
 * Rows come from `largest` (summary) and `items` (rule_candidates / folder runs) of each
 * reported `media_index` job whose result is `ok` or `partial`. Returns copies.
 */
export async function reportedMediaIndex(tx: Tx, conversationId: ConversationId): Promise<Map<string, MediaRef>> {
	const doc = await tx.doc(Jobs, conversationId);
	const reported = Object.values(doc.jobs)
		.filter((job) => job.kind === "media_index" && job.result !== undefined && (job.result.status === "ok" || job.result.status === "partial"))
		.sort((a, b) => (a.result?.reportedAtMs ?? 0) - (b.result?.reportedAtMs ?? 0));
	const rows = new Map<string, MediaRef>();
	for (const job of reported) {
		const data = job.result?.data as { largest?: unknown; items?: unknown } | null | undefined;
		for (const list of [data?.largest, data?.items]) {
			if (!Array.isArray(list)) continue;
			for (const row of list) {
				if (isMediaRow(row)) rows.set(row.mediaId, JSON.parse(JSON.stringify(row)) as MediaRef);
			}
		}
	}
	return rows;
}

/**
 * Complete the `trash_media` items of a proposal about to be written (inside the caller's
 * commit). Each target becomes the full MediaRef of the newest reported row with that
 * `mediaId`; fields the model supplied that disagree lose to the index, and a missing
 * `bytesEstimate` becomes the row's `sizeBytes`. Other kinds pass through.
 *
 * `strict` (the model's proposals, source `agent`): an item whose mediaId is in no reported
 * index is refused with an error naming it, and so is a protected row (favourite, Keep
 * folder, or restored by the owner). Not strict (a rule batch, whose refs already come from
 * an index): complete when found, otherwise keep as given.
 */
export async function completeTrashItems(
	tx: Tx,
	conversationId: ConversationId,
	items: ProposalItemInput[],
	strict: boolean,
): Promise<ProposalItemInput[]> {
	if (!items.some((item) => item.kind === "trash_media")) return items;
	const index = await reportedMediaIndex(tx, conversationId);
	const restored = strict ? await restoredMediaIds(tx, conversationId) : new Set<string>();
	return items.map((item) => {
		if (item.kind !== "trash_media") return item;
		const mediaId = (item.target as { mediaId?: unknown } | null | undefined)?.mediaId;
		if (typeof mediaId !== "string") return item;
		const row = index.get(mediaId);
		if (row === undefined) {
			if (!strict) return item;
			throw badRequest(
				`trash_media item ${mediaId} is in no media index the phone has reported, so the phone could not verify it. Run phone_media_index and propose from the items it returns.`,
			);
		}
		if (strict && (row.isFavourite === true || row.inKeep === true || restored.has(mediaId))) {
			throw badRequest(
				`trash_media item ${mediaId} is protected (favourite, in a Keep folder or restored by the owner) and must not be proposed.`,
			);
		}
		const bytesEstimate =
			typeof item.bytesEstimate === "number" ? item.bytesEstimate : typeof row.sizeBytes === "number" ? row.sizeBytes : undefined;
		return { ...item, target: row, ...(bytesEstimate === undefined ? {} : { bytesEstimate }) };
	});
}

/**
 * Repair, once and deterministically, the OPEN proposals stored before S9 whose
 * `trash_media` targets lack relativePath, displayName or sizeBytes: the target becomes the
 * full MediaRef from the newest reported index, a missing `bytesEstimate` becomes its
 * `sizeBytes`, and the proposal total is recomputed. An item whose mediaId is in no index is
 * left as it is. Idempotent (complete targets are skipped); returns how many items changed.
 * Called inside a commit at server start.
 */
export async function repairOpenTrashTargets(tx: Tx, conversationId: ConversationId): Promise<number> {
	const doc = await tx.doc(Proposals, conversationId);
	let index: Map<string, MediaRef> | undefined;
	let repaired = 0;
	for (const proposal of Object.values(doc.proposals)) {
		if (proposal.status !== "open") continue;
		let changed = false;
		for (const item of proposal.items) {
			if (item.kind !== "trash_media" || isCompleteMediaTarget(item.target)) continue;
			const mediaId = (item.target as { mediaId?: unknown } | null)?.mediaId;
			if (typeof mediaId !== "string") continue;
			index ??= await reportedMediaIndex(tx, conversationId);
			const row = index.get(mediaId);
			if (row === undefined) continue;
			item.target = JSON.parse(JSON.stringify(row)) as typeof item.target;
			if (!(typeof item.bytesEstimate === "number" && item.bytesEstimate > 0) && typeof row.sizeBytes === "number") {
				item.bytesEstimate = row.sizeBytes;
			}
			repaired += 1;
			changed = true;
		}
		if (changed) {
			proposal.bytesEstimate = proposal.items.reduce((sum, item) => sum + (Number.isFinite(item.bytesEstimate) ? item.bytesEstimate : 0), 0);
		}
	}
	return repaired;
}
