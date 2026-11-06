/**
 * Proposals and decisions (docs/protocol.md §4): the model's `propose_cleanup` and the
 * photo rule's one-tap batches create proposals; only the owner's decision through the API
 * approves them; the phone reports what actually happened; the deterministic post-batch
 * review  writes the review line.
 */
import type { Context, JsonValue } from "@earendil-works/chord";
import { defineDoc, type ConversationId, type Harness, type Tx } from "@earendil-works/pi-durable";
import { ApiError, badRequest, conflict, notFound } from "./errors.ts";
import { newId } from "./ids.ts";
import { emitStewardEvent } from "./steward-events.ts";
import { appendChatEvent } from "./chat-events.ts";
import { queueNotify } from "./notify.ts";
import { RulesDoc, recordDecision, type DecisionLedgerEntry } from "./documents.ts";
import { recordRequest } from "./requests.ts";
import { completeTrashItems } from "./media-index.ts";
import { completeDeleteFiles } from "./files-query.ts";
import { isNeverTouch, neverTouchMessage, validatePackageName } from "./shizuku.ts";
import { periodicCheckpoint } from "./checkpoint.ts";

export type ProposalItemKind =
	| "trash_media"
	| "uninstall_app"
	| "clear_app_cache"
	| "clear_all_caches"
	| "advice"
	/** A10.1: move files or folders to the Steward trash. */
	| "delete_files"
	/** A10.2: one `pm trim-caches` through Shizuku, no Android dialog. */
	| "trim_caches"
	| "disable_app"
	| "enable_app";

export type ProposalItem = {
	itemId: string;
	kind: ProposalItemKind;
	target: JsonValue;
	bytesEstimate: number;
	reason: string;
};

export type Proposal = {
	proposalId: string;
	createdAtMs: number;
	source: "agent" | "rule_batch";
	title: string;
	reason: string;
	bytesEstimate: number;
	status: "open" | "approved" | "rejected" | "executed" | "expired" | "superseded";
	items: ProposalItem[];
	/** When `status` last changed (decision, execution report, supersede); absent on older proposals. */
	statusChangedAtMs?: number;
	/** S6 (A6.2): set with `status: "superseded"` — the newer agent proposal that replaced this one. */
	supersededBy?: string;
	supersededAtMs?: number;
	/** Set on rule batches. */
	batchId?: string;
	ruleVersion?: number;
	/** S7 (A6.3): set when the server itself built the proposal (not the model), for the record. */
	builtBy?: "weekly_check";
	/** S11 (A10.8): set when the proposal closed because every item was already removed outside the app. */
	closedReason?: "removed_outside";
};

export type ProposalsState = { proposals: Record<string, Proposal> };

export const Proposals = defineDoc<ProposalsState>({
	kind: "steward.proposals",
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: periodicCheckpoint(),
	scope: "conversation",
	history: "latest",
	fork: "initial",
	initial: () => ({ proposals: {} }),
});

/** Approved decisions, for the phone's authority check (`GET /v1/decisions/{id}`). */
export type DecisionRecord = {
	decisionId: string;
	proposalId: string;
	approvedItemIds: string[];
	expiresAtMs: number;
	decidedAtMs: number;
	status: "approved" | "rejected" | "used";
};

export const DecisionsIndex = defineDoc<{ decisions: Record<string, DecisionRecord> }>({
	kind: "steward.decisions-index",
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: periodicCheckpoint(),
	scope: "conversation",
	history: "latest",
	fork: "initial",
	initial: () => ({ decisions: {} }),
});

export const DECISION_TTL_MS = 7 * 24 * 60 * 60_000;

export type CreateProposalInput = {
	source: "agent" | "rule_batch";
	title: string;
	reason: string;
	items: Array<{ kind: ProposalItemKind; target: unknown; bytesEstimate?: number; reason?: string }>;
	batchId?: string;
	ruleVersion?: number;
	builtBy?: "weekly_check";
};

const ITEM_KINDS: readonly ProposalItemKind[] = [
	"trash_media",
	"uninstall_app",
	"clear_app_cache",
	"clear_all_caches",
	"advice",
	"delete_files",
	"trim_caches",
	"disable_app",
	"enable_app",
];

/** A6: a clear_all_caches target lists the biggest caches, shown to the owner for information. */
function isValidClearAllCachesTarget(target: unknown): boolean {
	const apps = (target as { apps?: unknown } | null | undefined)?.apps;
	if (!Array.isArray(apps) || apps.length === 0) return false;
	return apps.every(
		(app) =>
			app !== null && typeof app === "object" && !Array.isArray(app) &&
			typeof (app as { packageName?: unknown }).packageName === "string" &&
			typeof (app as { label?: unknown }).label === "string" &&
			typeof (app as { cacheBytes?: unknown }).cacheBytes === "number" &&
			Number.isFinite((app as { cacheBytes?: unknown }).cacheBytes),
	);
}

export type ProposalItemInput = CreateProposalInput["items"][number];

/**
 * A6.1 (S5): two or more `clear_app_cache` items become ONE `clear_all_caches` item
 * (target `{apps: [{packageName, label, cacheBytes}]}`, estimate the sum), placed where the
 * first cache item stood. A single cache item, other kinds, and a list with any malformed
 * cache item (left for `createProposal` to reject) are untouched. `merged` is how many
 * cache items were folded in (0 = nothing changed).
 */
export function mergeClearAppCacheItems(items: ProposalItemInput[]): { items: ProposalItemInput[]; merged: number } {
	const cacheItems = items.filter((item) => item.kind === "clear_app_cache");
	if (cacheItems.length < 2) return { items, merged: 0 };
	const apps: Array<{ packageName: string; label: string; cacheBytes: number }> = [];
	for (const item of cacheItems) {
		const target = item.target as { packageName?: unknown; label?: unknown } | null | undefined;
		if (typeof target?.packageName !== "string" || typeof target?.label !== "string") return { items, merged: 0 };
		const bytes = typeof item.bytesEstimate === "number" && Number.isFinite(item.bytesEstimate) ? item.bytesEstimate : 0;
		apps.push({ packageName: target.packageName, label: target.label, cacheBytes: bytes });
	}
	const merged: ProposalItemInput = {
		kind: "clear_all_caches",
		target: { apps },
		bytesEstimate: apps.reduce((sum, app) => sum + app.cacheBytes, 0),
		reason: `Clear the caches of ${apps.length} apps in one confirmation`,
	};
	const out: ProposalItemInput[] = [];
	let placed = false;
	for (const item of items) {
		if (item.kind !== "clear_app_cache") out.push(item);
		else if (!placed) {
			out.push(merged);
			placed = true;
		}
	}
	return { items: out, merged: cacheItems.length };
}

/** S6 (A6.2): the kind families supersede compares. `advice` has none. */
export type KindFamily = "cache" | "uninstall" | "media" | "files" | "app_state";

function familyOf(kind: ProposalItemKind): KindFamily | undefined {
	switch (kind) {
		case "clear_app_cache":
		case "clear_all_caches":
		case "trim_caches":
			return "cache";
		case "uninstall_app":
			return "uninstall";
		case "trash_media":
			return "media";
		case "delete_files":
			return "files";
		case "disable_app":
		case "enable_app":
			return "app_state";
		default:
			return undefined;
	}
}

export function kindFamilies(items: Array<{ kind: ProposalItemKind }>): Set<KindFamily> {
	const out = new Set<KindFamily>();
	for (const item of items) {
		const family = familyOf(item.kind);
		if (family !== undefined) out.add(family);
	}
	return out;
}

/** What `createProposal` returns: the stored proposal plus the ids it superseded in the same commit. */
export type CreatedProposal = Proposal & { superseded: string[] };

/**
 * S6 (A6.2): when a new `agent` proposal is created, every earlier `open` agent proposal whose
 * kind families are all covered by the new one's becomes `superseded`. `approved` proposals
 * (they may be executing), rule batches and advice-only proposals are never touched. Each
 * superseded proposal gets a ledger row; the chat `proposal` event of the new proposal is what
 * makes the app reload its list.
 */
async function supersedeEarlier(
	tx: Tx,
	conversationId: ConversationId,
	proposals: Record<string, Proposal>,
	fresh: Proposal,
	nowMs: number,
): Promise<string[]> {
	if (fresh.source !== "agent") return [];
	const newFamilies = kindFamilies(fresh.items);
	if (newFamilies.size === 0) return [];
	const superseded: string[] = [];
	const earlier = Object.values(proposals)
		.filter((candidate) => candidate.proposalId !== fresh.proposalId)
		.sort((a, b) => a.createdAtMs - b.createdAtMs);
	for (const candidate of earlier) {
		if (candidate.source !== "agent" || candidate.status !== "open") continue;
		const families = kindFamilies(candidate.items);
		if (families.size === 0 || ![...families].every((family) => newFamilies.has(family))) continue;
		candidate.status = "superseded";
		candidate.supersededBy = fresh.proposalId;
		candidate.supersededAtMs = nowMs;
		candidate.statusChangedAtMs = nowMs;
		await recordDecision(tx, conversationId, {
			atMs: nowMs,
			type: "superseded",
			proposalId: candidate.proposalId,
			bytesEstimate: candidate.bytesEstimate,
			note: `replaced by the newer proposal ${fresh.proposalId} (not an owner decision)`,
		});
		superseded.push(candidate.proposalId);
	}
	return superseded;
}

/** Create a proposal inside the caller's commit; emits `proposal_created` and the SSE event. */
export async function createProposal(
	tx: Tx,
	conversationId: ConversationId,
	input: CreateProposalInput,
	nowMs: number,
): Promise<CreatedProposal> {
	if (input.title.trim().length === 0) throw badRequest("title is required");
	if (!Array.isArray(input.items) || input.items.length === 0) throw badRequest("items must be a non-empty list");
	for (const item of input.items) {
		if (!ITEM_KINDS.includes(item.kind)) throw badRequest(`unknown item kind: ${String(item.kind)}`);
		if (item.kind === "advice" && typeof (item.target as { text?: unknown })?.text !== "string") {
			throw badRequest("advice items need target.text");
		}
		if (item.kind === "uninstall_app" || item.kind === "clear_app_cache") {
			const target = item.target as { packageName?: unknown; label?: unknown };
			if (typeof target?.packageName !== "string" || typeof target?.label !== "string") {
				throw badRequest(`${item.kind} items need target.packageName and target.label`);
			}
		}
		if (item.kind === "trash_media") {
			const target = item.target as { mediaId?: unknown };
			if (typeof target?.mediaId !== "string") throw badRequest("trash_media items need target.mediaId");
		}
		if ((item.kind === "clear_all_caches" || item.kind === "trim_caches") && !isValidClearAllCachesTarget(item.target)) {
			throw badRequest(`${item.kind} items need target.apps: a non-empty list of {packageName, label, cacheBytes}`);
		}
		if (item.kind === "disable_app" || item.kind === "enable_app") {
			const target = item.target as { packageName?: unknown; label?: unknown } | null | undefined;
			if (typeof target?.packageName !== "string" || typeof target?.label !== "string") {
				throw badRequest(`${item.kind} items need target.packageName and target.label`);
			}
			validatePackageName(target.packageName);
			// A10.2: the never-touch list is enforced in code. Re-enabling is harmless.
			if (item.kind === "disable_app" && isNeverTouch(target.packageName)) {
				throw badRequest(neverTouchMessage(target.packageName, "disable_app"));
			}
		}
		if (item.kind === "delete_files" && typeof (item.target as { path?: unknown } | null | undefined)?.path !== "string") {
			throw badRequest("delete_files items need target.path");
		}
	}
	// S9 (A9.1): trash targets come from the phone's reported index, not from the model.
	// A10.1: so do delete_files targets, from the newest reported files_query result.
	const strict = input.source === "agent";
	const completed = await completeDeleteFiles(
		tx,
		conversationId,
		await completeTrashItems(tx, conversationId, input.items, strict),
		strict,
	);
	const doc = await tx.doc(Proposals, conversationId);
	const proposalId = newId("pr");
	const items: ProposalItem[] = completed.map((item, index) => ({
		itemId: `it-${proposalId.slice(3)}-${index}`,
		kind: item.kind,
		target: JSON.parse(JSON.stringify(item.target)) as JsonValue,
		bytesEstimate: typeof item.bytesEstimate === "number" ? item.bytesEstimate : 0,
		reason: typeof item.reason === "string" ? item.reason : "",
	}));
	const proposal: Proposal = {
		proposalId,
		createdAtMs: nowMs,
		source: input.source,
		title: input.title,
		reason: input.reason,
		bytesEstimate: items.reduce((sum, item) => sum + (Number.isFinite(item.bytesEstimate) ? item.bytesEstimate : 0), 0),
		status: "open",
		items,
		...(input.batchId === undefined ? {} : { batchId: input.batchId }),
		...(input.ruleVersion === undefined ? {} : { ruleVersion: input.ruleVersion }),
		...(input.builtBy === undefined ? {} : { builtBy: input.builtBy }),
	};
	doc.proposals[proposalId] = JSON.parse(JSON.stringify(proposal)) as Proposal;
	const superseded = await supersedeEarlier(tx, conversationId, doc.proposals, proposal, nowMs);
	await emitStewardEvent(tx, conversationId, {
		kind: "proposal_created",
		atMs: nowMs,
		data: { proposalId, source: input.source, itemCount: items.length, bytesEstimate: proposal.bytesEstimate },
	});
	await appendChatEvent(tx, "proposal", { proposalId, title: input.title, atMs: nowMs }, nowMs);
	return { ...(JSON.parse(JSON.stringify(proposal)) as Proposal), superseded };
}

export type DecisionInput = { decision: "approve" | "reject"; itemIds?: string[] };
export type DecisionOutput = {
	decisionId: string;
	approvedItemIds: string[];
	expiresAtMs: number;
};

/**
 * The owner's decision. Items the owner unticked are recorded `kept`; a reject records
 * `rejected` for every item. Emits the `decision` steward event inside the commit.
 */
export async function decideProposal(
	harness: Harness,
	conversationId: ConversationId,
	proposalId: string,
	input: DecisionInput,
	context: Context,
	request?: { requestId: string; route: string; body: unknown },
): Promise<DecisionOutput> {
	const conversation = await harness.conversation(conversationId, context);
	if (conversation === undefined) throw notFound(`no conversation ${conversationId}`);
	const nowMs = Date.now();
	return conversation.commit(async (tx) => {
		const doc = await tx.doc(Proposals, conversationId);
		const proposal = doc.proposals[proposalId];
		if (proposal === undefined) throw notFound(`unknown proposal ${proposalId}`);
		if (proposal.status === "superseded") {
			throw conflict(
				"PROPOSAL_NOT_OPEN",
				`proposal was superseded by the newer proposal ${proposal.supersededBy ?? "(unknown)"}, not open`,
			);
		}
		if (proposal.status !== "open") throw conflict("PROPOSAL_NOT_OPEN", `proposal is ${proposal.status}, not open`);
		const index = await tx.doc(DecisionsIndex, conversationId);
		const decisionId = newId("dc");
		let approvedItemIds: string[];
		if (input.decision === "reject") {
			approvedItemIds = [];
			proposal.status = "rejected";
		} else {
			const wanted = new Set(input.itemIds ?? proposal.items.map((item) => item.itemId));
			const unknown = [...wanted].filter((id) => !proposal.items.some((item) => item.itemId === id));
			if (unknown.length > 0) throw badRequest(`unknown itemIds: ${unknown.join(", ")}`);
			approvedItemIds = proposal.items.filter((item) => wanted.has(item.itemId)).map((item) => item.itemId);
			proposal.status = "approved";
		}
		proposal.statusChangedAtMs = nowMs;
		const approvedSet = new Set(approvedItemIds);
		const ledgerWrites: Array<Promise<void>> = [];
		for (const item of proposal.items) {
			const entry: DecisionLedgerEntry = {
				atMs: nowMs,
				type: input.decision === "reject" ? "rejected" : approvedSet.has(item.itemId) ? "executed" : "kept",
				proposalId,
				itemId: item.itemId,
				kind: item.kind,
				mediaId: item.kind === "trash_media" ? (item.target as { mediaId?: string }).mediaId : undefined,
				packageName: (item.target as { packageName?: string }).packageName,
				...(item.kind === "delete_files" ? { path: (item.target as { path?: string }).path } : {}),
				bytesEstimate: item.bytesEstimate,
			};
			if (input.decision === "approve" && approvedSet.has(item.itemId)) {
				// The ledger records the execution when the phone reports; approval alone is
				// recorded as part of the decision, with the type fixed up on the report.
				entry.type = "kept";
				entry.note = "approved; awaiting execution report";
			}
			ledgerWrites.push(recordDecision(tx, conversationId, entry));
		}
		const expiresAtMs = nowMs + DECISION_TTL_MS;
		index.decisions[decisionId] = {
			decisionId,
			proposalId,
			approvedItemIds,
			expiresAtMs,
			decidedAtMs: nowMs,
			status: input.decision === "reject" ? "rejected" : "approved",
		};
		await emitStewardEvent(tx, conversationId, {
			kind: "decision",
			atMs: nowMs,
			data: { proposalId, decisionId, decision: input.decision, approvedCount: approvedItemIds.length },
		});
		await Promise.all(ledgerWrites);
		const response = { decisionId, approvedItemIds, expiresAtMs };
		// item 4: the request ledger entry shares the domain commit, so a
		// crash before the HTTP response still lets the retry dedupe.
		if (request !== undefined) {
			await recordRequest(tx, {
				requestId: request.requestId,
				route: request.route,
				body: request.body,
				response: JSON.parse(JSON.stringify(response)) as never,
				nowMs,
			});
		}
		return { decisionId, approvedItemIds, expiresAtMs };
	}, context);
}

/** The authority check the phone makes before acting (§4). */
export async function decisionValid(
	harness: Harness,
	conversationId: ConversationId,
	decisionId: string,
	context: Context,
): Promise<DecisionRecord | undefined> {
	const stored = await harness.snapshot(DecisionsIndex, conversationId, context);
	const decision = stored?.decisions[decisionId];
	if (decision === undefined) return undefined;
	if (decision.status !== "approved") return undefined;
	if (Date.now() >= decision.expiresAtMs) return undefined;
	return JSON.parse(JSON.stringify(decision)) as DecisionRecord;
}

export type ExecutionReportItem = {
	itemId: string;
	outcome: "done" | "cancelled" | "failed" | "skipped_protected";
	resultCode: number | null;
	bytesFreed: number | null;
	/** A10.1: bytes moved to the Steward trash (a `delete_files` item; `bytesFreed` is 0 then). */
	trashedBytes?: number | null;
};

/**
 * The phone's execution report: the proposal becomes `executed` when at least one item was
 * `done`, otherwise it returns to `open` (S4) with its decision spent; the ledger records each
 * outcome, a rule batch's one-tap count advances, and the deterministic post-batch review
 * (§3.8) writes the `review_line` steward event — estimate against bytes freed, failures,
 * cancellations.
 */
export async function recordExecution(
	harness: Harness,
	conversationId: ConversationId,
	proposalId: string,
	report: { decisionId: string; items: ExecutionReportItem[] },
	context: Context,
	request?: { requestId: string; route: string; body: unknown },
): Promise<{ accepted: true }> {
	const conversation = await harness.conversation(conversationId, context);
	if (conversation === undefined) throw notFound(`no conversation ${conversationId}`);
	const nowMs = Date.now();
	await conversation.commit(async (tx) => {
		const doc = await tx.doc(Proposals, conversationId);
		const proposal = doc.proposals[proposalId];
		if (proposal === undefined) throw notFound(`unknown proposal ${proposalId}`);
		const index = await tx.doc(DecisionsIndex, conversationId);
		const decision = index.decisions[report.decisionId];
		if (decision === undefined || decision.proposalId !== proposalId) {
			throw badRequest("decisionId does not belong to this proposal");
		}
		if (decision.status !== "approved") throw badRequest("this decision is not an approval");
		if (Date.now() >= decision.expiresAtMs) throw badRequest("this decision has expired");
		if (proposal.status === "executed") return { accepted: true } as const;
		if (proposal.status !== "approved" && proposal.status !== "open") {
			throw conflict("PROPOSAL_NOT_OPEN", `proposal is ${proposal.status}, not executable`);
		}
		const byId = new Map(proposal.items.map((item) => [item.itemId, item]));
		const approvedIds = new Set(decision.approvedItemIds);
		let freedBytes = 0;
		let trashedBytes = 0;
		let anyFreed = false;
		let anyDone = false;
		let failures = 0;
		let cancellations = 0;
		let skippedProtected = 0;
		for (const reported of report.items) {
			const item = byId.get(reported.itemId);
			if (item === undefined) throw badRequest(`unknown itemId ${reported.itemId}`);
			// S12 (A10.8): an item the owner did not approve in this decision stays `kept`
			// (the decision already wrote that row); an older app reported it as `cancelled`.
			if (!approvedIds.has(item.itemId)) continue;
			const ledgerType: DecisionLedgerEntry["type"] =
				reported.outcome === "done"
					? "executed"
					: reported.outcome === "cancelled"
						? "cancelled"
						: reported.outcome === "failed"
							? "failed"
							: "skipped_protected";
			if (reported.outcome === "done") anyDone = true;
			if (reported.outcome === "failed") failures += 1;
			if (reported.outcome === "cancelled") cancellations += 1;
			if (reported.outcome === "skipped_protected") skippedProtected += 1;
			if (typeof reported.bytesFreed === "number" && reported.bytesFreed > 0) {
				freedBytes += reported.bytesFreed;
				anyFreed = true;
			}
			const trashed = reported.outcome === "done" && typeof reported.trashedBytes === "number" && reported.trashedBytes > 0 ? reported.trashedBytes : 0;
			trashedBytes += trashed;
			await recordDecision(tx, conversationId, {
				atMs: nowMs,
				type: ledgerType,
				proposalId,
				itemId: item.itemId,
				kind: item.kind,
				mediaId: item.kind === "trash_media" ? (item.target as { mediaId?: string }).mediaId : undefined,
				packageName: (item.target as { packageName?: string }).packageName,
				...(item.kind === "delete_files" ? { path: (item.target as { path?: string }).path } : {}),
				bytesEstimate: item.bytesEstimate,
				bytesFreed: reported.bytesFreed,
				...(trashed > 0 ? { bytesTrashed: trashed } : {}),
			});
		}
		// S4: the decision is spent either way (the owner approves again to retry), but a
		// report with no `done` item means nothing happened, so the proposal stays open.
		decision.status = "used";
		proposal.status = anyDone ? "executed" : "open";
		proposal.statusChangedAtMs = nowMs;
		if (anyDone && proposal.source === "rule_batch") {
			const rules = await tx.doc(RulesDoc, conversationId);
			rules.batchesRunOneTap += 1;
		}
		await emitStewardEvent(tx, conversationId, {
			kind: "review_line",
			atMs: nowMs,
			data: {
				proposalId,
				source: proposal.source,
				estimatedBytes: proposal.bytesEstimate,
				freedBytes: anyFreed ? freedBytes : null,
				...(trashedBytes > 0 ? { trashedBytes } : {}),
				failures,
				cancellations,
				skippedProtected,
				...(anyDone ? {} : { nothingDone: true, line: "Nothing was done; the proposal is open again for the owner to retry." }),
			},
		});
		await recordDecision(tx, conversationId, {
			atMs: nowMs,
			type: "review",
			proposalId,
			...(anyDone ? {} : { note: "Nothing was done; the proposal is open again." }),
			review: {
				estimatedBytes: proposal.bytesEstimate,
				freedBytes: anyFreed ? freedBytes : null,
				failures,
				cancellations,
				skippedProtected,
			},
		});
		// item 4: the ledger entry shares the execution commit.
		if (request !== undefined) {
			await recordRequest(tx, {
				requestId: request.requestId,
				route: request.route,
				body: request.body,
				response: { accepted: true },
				nowMs,
			});
		}
		return { accepted: true } as const;
	}, context);
	return { accepted: true };
}

export type ProposalSummary = {
	proposalId: string;
	title: string;
	status: Proposal["status"];
	source: Proposal["source"];
	createdAt: string;
	itemCount: number;
	itemKinds: ProposalItemKind[];
	bytesEstimate: number;
	supersededBy?: string;
	/** Closed proposals only: when the status last changed (ISO), falling back to creation. */
	closedAt?: string;
};

export type ProposalsForSteward = { openAndApproved: ProposalSummary[]; recentClosed: ProposalSummary[] };

/** How many closed proposals the `proposals` tool shows. */
export const RECENT_CLOSED_LIMIT = 10;

function closedAtMsOf(proposal: Proposal): number {
	return proposal.statusChangedAtMs ?? proposal.supersededAtMs ?? proposal.createdAtMs;
}

function summarise(proposal: Proposal, closed: boolean): ProposalSummary {
	return {
		proposalId: proposal.proposalId,
		title: proposal.title,
		status: proposal.status,
		source: proposal.source,
		createdAt: new Date(proposal.createdAtMs).toISOString(),
		itemCount: proposal.items.length,
		itemKinds: [...new Set(proposal.items.map((item) => item.kind))],
		bytesEstimate: proposal.bytesEstimate,
		...(proposal.supersededBy === undefined ? {} : { supersededBy: proposal.supersededBy }),
		...(closed ? { closedAt: new Date(closedAtMsOf(proposal)).toISOString() } : {}),
	};
}

/**
 * S6 (A6.2): the read-only view behind the steward's `proposals` tool — every open and
 * approved proposal, then the 10 most recent closed ones (executed, rejected, expired,
 * superseded), newest first. Deterministic, no phone round-trip.
 */
export async function listProposalsForSteward(tx: Tx, conversationId: ConversationId): Promise<ProposalsForSteward> {
	const doc = await tx.doc(Proposals, conversationId);
	const all = Object.values(doc.proposals);
	const live = all
		.filter((proposal) => proposal.status === "open" || proposal.status === "approved")
		.sort((a, b) => a.createdAtMs - b.createdAtMs);
	const closed = all
		.filter((proposal) => proposal.status !== "open" && proposal.status !== "approved")
		.sort((a, b) => closedAtMsOf(b) - closedAtMsOf(a) || b.createdAtMs - a.createdAtMs)
		.slice(0, RECENT_CLOSED_LIMIT);
	return {
		openAndApproved: live.map((proposal) => summarise(proposal, false)),
		recentClosed: closed.map((proposal) => summarise(proposal, true)),
	};
}
