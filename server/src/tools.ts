/**
 * The model's tools — exactly the nine of docs/protocol.md §8 — the system prompt
 * sections , and the guard hook that blocks anything outside the allowlist.
 * The model cannot act: phone tools only queue jobs, and the server-only tools read
 * documents or write proposals. A test enumerates what the agent resolves and fails if
 * anything else is offered .
 */
import { defineExtension, defineTool, hook, section, ToolTask } from "@earendil-works/pi-durable";
import { arr, bool, lit, num, obj, opt, str, union, unknown as anyValue, type Schema } from "@earendil-works/pi-schema";
import type { Conversation, ConversationId, JsonObject, Tx } from "@earendil-works/pi-durable";
import type { Context } from "@earendil-works/chord";
import { queueJob } from "./jobs.ts";
import {
	Decisions,
	RulesDoc,
	Snapshots,
	storageTrendFromTx,
	type DecisionLedgerEntry,
} from "./documents.ts";
import { createProposal, listProposalsForSteward, mergeClearAppCacheItems, type ProposalItemInput } from "./proposals.ts";
import { ORCHESTRATION_TOOL_NAMES, orchestrationToolList } from "./orchestration-tools.ts";
import { WEB_TOOL_NAMES, webToolList } from "./web-tools.ts";
import { phoneResultTool } from "./phone-result.ts";
import {
	MAX_REQUEST_TASK_CHARS,
	MAX_REQUEST_WHY_CHARS,
	createRequestTx,
} from "./live/requests.ts";
import { currentTurnOrigin, turnOriginText, type TurnOrigin } from "./turn-origin.ts";
import { newId } from "./ids.ts";
import { CAPABILITIES_TEXT, HANDOVER_TEXT, MEMORY_TEXT, PHONE_USE_TEXT, REACHABILITY_TEXT, SAFETY_TEXT } from "./prompt-posture.ts";

/**
 * The protocol §8 tool list as amended by A10.7, in offer order. The safety test reconciles
 * against this. `phone_files`, `phone_shell_info` and `phone_force_stop` are the files
 * and shell tools (A10.1, A10.2); the screen tools (A10.3) exist only in a live-session turn and are
 * not in this list.
 */
export const PROTOCOL_TOOL_NAMES = [
	"phone_storage_overview",
	"phone_app_usage",
	"phone_media_index",
	"phone_files",
	"phone_shell_info",
	"phone_force_stop",
	"storage_trend",
	"phone_snapshot",
	"phone_result",
	"decisions",
	"proposals",
	"rules",
	"propose_cleanup",
	"phone_use_request",
	...WEB_TOOL_NAMES,
	...ORCHESTRATION_TOOL_NAMES,
] as const;

export type ProtocolToolName = (typeof PROTOCOL_TOOL_NAMES)[number];

/** The allowlist the guard enforces; identical content to PROTOCOL_TOOL_NAMES. */
export function allowedToolNames(): readonly string[] {
	return [...PROTOCOL_TOOL_NAMES];
}

/** The guard's decision: a block reason for any tool outside the allowlist. */
export function guardBlock(toolName: string): string | undefined {
	if ((PROTOCOL_TOOL_NAMES as readonly string[]).includes(toolName)) return undefined;
	return `tool "${toolName}" is not allowed: the steward model can only use the tools of docs/protocol.md §8 and A10.7; it never acts by itself on anything that removes data`;
}

/** Queue a phone job for one tool call (replay-safe: find-before-create on the call's task id). */
export async function queuePhoneJobForCall(
	tx: Tx,
	conversationId: Parameters<typeof queueJob>[1],
	input: { callTaskId: string; kind: Parameters<typeof queueJob>[2]["kind"]; params: JsonObject },
	nowMs: number,
): Promise<{ status: "queued"; jobId: string }> {
	const { jobId } = await queueJob(tx, conversationId, {
		dedupeKey: input.callTaskId,
		kind: input.kind,
		params: input.params,
		nowMs,
	});
	return { status: "queued", jobId };
}

function phoneTool(
	name: "phone_storage_overview" | "phone_app_usage" | "phone_media_index",
	description: string,
	parameters: Schema,
) {
	return defineTool({
		name,
		description,
		parameters,
		replay: "safe",
		execute: async (args, api, callContext) => {
			const params = JSON.parse(JSON.stringify(args)) as JsonObject;
			// Test scaffolding gate: a kill between intent and job finds the job on replay.
			const { crashPoint } = await import("./crash-gate.ts");
			await crashPoint(`phone_tool:${name}`);
			const result = await api.commit(
				(tx) =>
					queuePhoneJobForCall(
						tx,
						api.conversationId,
						{ callTaskId: `${api.taskId}`, kind: name.slice("phone_".length) as "storage_overview" | "app_usage" | "media_index", params },
						Date.now(),
					),
				callContext,
			);
			await crashPoint(`phone_tool:${name}:after-job`);
			return { output: [{ type: "text", text: JSON.stringify(result) }] };
		},
	});
}

const phoneStorageOverviewTool = phoneTool(
	"phone_storage_overview",
	"Ask the phone for total and free space, per-app sizes (app/data/cache) and category totals. The result arrives as a later message.",
	obj({}, { open: true }),
);

const phoneAppUsageTool = phoneTool(
	"phone_app_usage",
	"Ask the phone for user-installed apps with last-used and install times. The result arrives as a later message.",
	obj({}, { open: true }),
);

const phoneMediaIndexTool = phoneTool(
	"phone_media_index",
	"Ask the phone for a media index: folder sizes and counts, age buckets inside the photo-rule folders, largest items, top-level files and old APKs. mode 'summary' gives the overview; mode 'rule_candidates' lists items older than olderThanDays (protected ones included, marked). Each folder in `folders` is a subtree: it covers the folder itself and every subfolder, and the trailing slash is optional (e.g. 'DCIM/Camera' and 'Android/media/com.whatsapp/' both work). The result arrives as a later message.",
	obj(
		{
			mode: union([lit("summary"), lit("rule_candidates")]),
			folders: opt(arr(str())),
			olderThanDays: opt(num()),
		},
		{ open: true },
	),
);

const storageTrendTool = defineTool({
	name: "storage_trend",
	description:
		"Differences between the two newest stored storage snapshots: what grew or shrank, by how much, per app and category. Deterministic, no phone round-trip.",
	parameters: obj({}, { open: true }),
	execute: async (_args, api, callContext) => {
		const trend = await api.commit((tx) => storageTrendFromTx(tx, api.conversationId), callContext);
		if (trend === undefined) {
			return { output: [{ type: "text", text: JSON.stringify({ status: "not-enough-snapshots" }) }] };
		}
		return { output: [{ type: "text", text: JSON.stringify({ status: "ok", trend }) }] };
	},
});

const phoneSnapshotTool = defineTool({
	name: "phone_snapshot",
	description: "The latest stored phone facts and their age, with no phone round-trip.",
	parameters: obj({}, { open: true }),
	execute: async (_args, api, callContext) => {
		const snapshot = await api.commit(async (tx) => {
			const doc = await tx.doc(Snapshots, api.conversationId);
			const latest = doc.snapshots[doc.snapshots.length - 1];
			return latest === undefined ? undefined : (JSON.parse(JSON.stringify(latest)) as typeof latest);
		}, callContext);
		if (snapshot === undefined) {
			return { output: [{ type: "text", text: JSON.stringify({ status: "no-snapshot" }) }] };
		}
		const ageMs = Date.now() - snapshot.capturedAtMs;
		return { output: [{ type: "text", text: JSON.stringify({ status: "ok", ageMs, snapshot }) }] };
	},
});

const decisionsTool = defineTool({
	name: "decisions",
	description:
		"The keep/reject/restore/execute ledger. Consult it before proposing anything, so you never propose what the owner already kept, rejected or restored. `restored` rows with kind `delete_files` are files the owner put back from the Steward trash: leave those paths alone unless the owner asks. Screen taps from phone sessions (`screen_action`) are left out unless you pass includeScreenActions: true; `limit` counts what is shown.",
	parameters: obj({ limit: opt(num()), includeScreenActions: opt(bool()) }, { open: true }),
	execute: async (args, api, callContext) => {
		const limit = typeof args.limit === "number" ? Math.max(1, Math.min(args.limit, 200)) : 50;
		const includeScreenActions = args.includeScreenActions === true;
		const entries = await api.commit(async (tx) => {
			const doc = await tx.doc(Decisions, api.conversationId);
			const shown = includeScreenActions ? doc.entries : doc.entries.filter((entry) => entry.type !== "screen_action");
			return JSON.parse(JSON.stringify(shown.slice(-limit))) as DecisionLedgerEntry[];
		}, callContext);
		return { output: [{ type: "text", text: JSON.stringify({ status: "ok", entries }) }] };
	},
});

const proposalsTool = defineTool({
	name: "proposals",
	description:
		"What is open right now, from the server's own record: every open and approved proposal (id, title, status, createdAt, item count, item kinds, bytesEstimate, source), then the 10 most recent closed ones (executed, rejected, expired or superseded, with status, closedAt and, for superseded, the newer proposal). Read-only, deterministic. Call it before you tell the owner what is open or pending, and before proposing: only a proposal this tool shows as `open` is waiting for the owner.",
	parameters: obj({}, { open: true }),
	replay: "safe",
	execute: async (_args, api, callContext) => {
		const view = await api.commit((tx) => listProposalsForSteward(tx, api.conversationId), callContext);
		return { output: [{ type: "text", text: JSON.stringify({ status: "ok", ...view }) }] };
	},
});

const rulesTool = defineTool({
	name: "rules",
	description: "Read-only view of the owner's standing rules (the photo window and its folders). The model cannot change rules.",
	parameters: obj({}, { open: true }),
	execute: async (_args, api, callContext) => {
		const rules = await api.commit(async (tx) => {
			const doc = await tx.doc(RulesDoc, api.conversationId);
			return JSON.parse(JSON.stringify({ ...doc })) as typeof doc;
		}, callContext);
		return { output: [{ type: "text", text: JSON.stringify({ status: "ok", rules }) }] };
	},
});

const proposeCleanupTool = defineTool({
	name: "propose_cleanup",
	description:
		"Write a cleanup proposal for the owner to approve in the app. It executes nothing. Item kinds: trash_media (target: {mediaId} of an item from a phone_media_index result; the server fills in the rest of the MediaRef from that index, and refuses a mediaId no reported index holds, or a protected one), uninstall_app and clear_app_cache (target: {packageName, label}), clear_all_caches (target: {apps: [{packageName, label, cacheBytes}]}; Android then clears EVERY app's cache after ONE confirmation, so prefer it over many clear_app_cache items; two or more clear_app_cache items are merged into one clear_all_caches automatically), delete_files (target: {path} of a path a phone_files result returned; moved to the Steward trash, never the root, Android/, Keep paths, DCIM/ or Pictures/), trim_caches (same target as clear_all_caches; needs Shizuku, no Android dialog), disable_app and enable_app (target: {packageName, label}; need Shizuku; the never-touch list is refused), advice (target: {text}). Consult `decisions` first; never propose protected items (Keep folder, favourites, restored). Few taps: the owner approves at most once, so propose one batch rather than many single-item proposals.",
	parameters: obj(
		{
			title: str(),
			reason: str(),
			items: arr(
				obj(
					{
						kind: union([
							lit("trash_media"),
							lit("uninstall_app"),
							lit("clear_app_cache"),
							lit("clear_all_caches"),
							lit("delete_files"),
							lit("trim_caches"),
							lit("disable_app"),
							lit("enable_app"),
							lit("advice"),
						]),
						target: anyValue(),
						bytesEstimate: opt(num()),
						reason: opt(str()),
					},
					{ open: true },
				),
			),
		},
		{ open: true },
	),
	execute: async (args, api, callContext) => {
		const text = await api.commit(
			(tx) => proposeCleanup(tx, api.conversationId, args, Date.now()),
			callContext,
		);
		return { output: [{ type: "text", text }] };
	},
});

/**
 * The body of `propose_cleanup` (A6.1, S5): two or more `clear_app_cache` items are merged
 * into one `clear_all_caches` item in code, and the result says so, so the model knows
 * what the owner will see. Returns the tool result text.
 */
export async function proposeCleanup(
	tx: Tx,
	conversationId: ConversationId,
	args: { title: string; reason: string; items: unknown[] },
	nowMs: number,
): Promise<string> {
	const { items, merged } = mergeClearAppCacheItems(args.items as ProposalItemInput[]);
	const proposal = await createProposal(
		tx,
		conversationId,
		{ source: "agent", title: args.title, reason: args.reason, items },
		nowMs,
	);
	return JSON.stringify({
		proposalId: proposal.proposalId,
		...(proposal.superseded.length > 0
			? {
					superseded: proposal.superseded,
					note: `This proposal replaces the earlier open proposal(s) ${proposal.superseded.join(", ")} of the same kind; they are closed as superseded and no longer wait for the owner.`,
				}
			: {}),
		...(merged > 0
			? {
					note: `${merged} clear_app_cache items were merged into one clear_all_caches item (Android clears every app's cache after one confirmation; the owner sees the ${merged} apps listed). Prefer clear_all_caches directly next time.`,
				}
			: {}),
	});
}

/**
 * A13.1: ask the owner in Chat for a phone session. Refused (tool error, nothing stored)
 * unless the turn it runs in started from the owner (correction 1: the turn's own input
 * decides — its leading marker, not the stale run mirror); the weekly check, photo
 * window, phone results, schedules and rules never ask. Stores one pending request (a
 * newer one supersedes an older pending one; it expires after 10 minutes), appends the
 * `phone_use` request event, writes the ledger row, and returns at once: the owner's
 * answer arrives as a later turn.
 */
export function phoneUseRequestRefusal(args: { task?: unknown; why?: unknown }, origin: string): string {
	const task = typeof args.task === "string" ? args.task.trim() : "";
	const why = typeof args.why === "string" ? args.why.trim() : "";
	if (task.length < 1 || task.length > MAX_REQUEST_TASK_CHARS) {
		return `refused: task must be 1 to ${MAX_REQUEST_TASK_CHARS} characters; nothing was asked`;
	}
	if (why.length < 1 || why.length > MAX_REQUEST_WHY_CHARS) {
		return `refused: why must be 1 to ${MAX_REQUEST_WHY_CHARS} characters; nothing was asked`;
	}
	if (origin !== "owner") {
		const source = turnOriginText(origin as TurnOrigin);
		return `refused: phone use can only be requested in a conversation the owner started (this turn came from ${source}); nothing was asked`;
	}
	return "";
}

const phoneUseRequestTool = defineTool({
	name: "phone_use_request",
	description:
		"Ask the owner in Chat for permission to use their phone's screen for ONE task (A13). Only when they asked you for something that needs the screen and no other tool does it. The owner answers in Chat; their answer arrives as a later message. Never inside a schedule, a rule, a review or a phone-result turn.",
	parameters: obj({ task: str(), why: str() }, { open: true }),
	execute: async (args, api, callContext) => {
		const nowMs = Date.now();
		const outcome = await api.commit(
			async (tx) => {
				// the turn's own input decides (its leading marker), never the
				// run mirror; an origin that cannot be established is refused (fail closed).
				const origin = await currentTurnOrigin(tx, api.conversationId, api.taskId);
				const refusal = phoneUseRequestRefusal(args, origin);
				if (refusal !== "") return { refusal };
				const task = (typeof args.task === "string" ? args.task : "").trim();
				const why = (typeof args.why === "string" ? args.why : "").trim();
				const request = await createRequestTx(tx, api.conversationId, { id: newId("pu"), task, why }, nowMs);
				return { requestId: request.id };
			},
			callContext,
		);
		if (outcome.refusal !== undefined) return { output: [{ type: "text", text: outcome.refusal }] };
		return {
			output: [{ type: "text", text: `Asked the owner in Chat (request ${outcome.requestId}). Wait for their answer; do not ask again in this turn.` }],
		};
	},
});

/** The phone tools as one extension (the only tools that touch the jobs document). */
export const phoneToolsExtension = defineExtension({
	name: "steward-phone-tools",
	tools: [phoneStorageOverviewTool, phoneAppUsageTool, phoneMediaIndexTool],
});

/** The server-only tools as one extension. */
export const serverToolsExtension = defineExtension({
	name: "steward-server-tools",
	tools: [storageTrendTool, phoneSnapshotTool, phoneResultTool, decisionsTool, proposalsTool, rulesTool, proposeCleanupTool, phoneUseRequestTool],
});

/** A10.5: the web tools (read-only; fenced as untrusted content; budgets per run). */
export const webToolsExtension = defineExtension({
	name: "steward-web-tools",
	tools: webToolList,
});

/** The orchestration read tools (fixed read-only subcommands; output labelled untrusted). */
export const orchestrationToolsExtension = defineExtension({
	name: "steward-orchestration-tools",
	tools: orchestrationToolList,
});

/** The guard hook: beforeTool blocks anything outside the protocol §8 allowlist. */
export const guardExtension = defineExtension({
	name: "steward-guard",
	hooks: [hook(ToolTask, { beforeTool: (call) => {
		const block = guardBlock(call.name);
		return block === undefined ? undefined : { block };
	} })],
});

/** The system prompt sections : role, safety, reachability tiers, owner rules. */
export function stewardSections() {
	return defineExtension({
		name: "steward-prompt",
		sections: [
			section("role", () =>
				[
					"You are Phone Steward, the owner's phone maintenance agent, running on your server.",
					"Your job: keep the owner's Android phone from filling up and explain clearly where the space goes.",
					"You work across days: you ask the phone for facts, the phone answers when it next checks in, you analyse and propose, the owner approves in the app, the phone executes, and you review what was actually freed.",
				].join(" "),
			),
			section("safety", () => SAFETY_TEXT),
			section("capabilities", () => CAPABILITIES_TEXT),
			section("reachability", () => REACHABILITY_TEXT),
			section("handover", () => HANDOVER_TEXT),
			section("memory", () => MEMORY_TEXT),
			section("phone-use", () => PHONE_USE_TEXT),
			section("owner-rules", () =>
				[
					"THE OWNER'S RULES AND PREFERENCES:",
					"- Storage clarity comes first: answer from stored snapshots and state which space is not reclaimable without root.",
					"- The photo policy is the rule configured in the app's Rules screen (folders, reminder and cut-off days); it is age-based only: favourites and anything in Keep are never touched; the phone's trash keeps items about 30 days.",
					"- Photos stay on the phone: you only ever see metadata (path, size, date, type), never pixels or hashes.",
					"- One Android phone. The phone polls; answers can take hours. A phone result arrives as a message starting with [phone result. A big result arrives as a summary (totals, counts and the largest entries); read the rest with phone_result before you rely on a detail it left out, and remember that every proposal target is still completed and checked by the server from the phone's own report.",
				].join("\n"),
			),
			section("phone-checks", () =>
				[
					"THE PHONE SELF-TEST (phone_selftest): report each outcome as it is. Do not guess a cause the result does not state; if a check failed and its detail does not say why, say the cause is unknown and that the maintainer will look at it.",
					"- trash_dialog: the app trashes 2 of its own test images through Android's one consent dialog; a pass needs a human-paced tap. Android may trash an app's own images without showing a dialog, so a very fast \"allowed\" answer is a limit of the test, not a fault on the phone.",
					"- manage_media_skip: runs only when Manage media is granted; \"skipped\" is normal.",
					"- trash_restorable: restores the trashed test image; skipped when trash_dialog did not pass.",
					"- clear_cache_intent: Android's clear-temporary-files screen can be opened.",
					"- workmanager_ran: the background check-in has run at least once.",
					"MANAGE MEDIA: the Manage media permission is only for auto mode (a standing rule that trashes without asking). Approved proposals trash media through Android's consent dialog and never need it. Never advise the owner to grant Manage media unless they choose to turn auto mode on.",
				].join("\n"),
			),
			section("few-taps", () =>
				[
					"CLEARING CACHES (few taps): whenever two or more caches are worth clearing, propose ONE clear_all_caches batch instead of many single-app clear_app_cache items.",
					"Android then clears EVERY app's cache after ONE confirmation; the proposal's target lists the biggest caches so the owner knows what to expect — say in the proposal that one confirmation covers every app.",
					"Use clear_app_cache only for a single app. Group anything else you can into one batch proposal, so the owner approves once.",
				].join(" "),
			),
			section("review-proposals", () =>
				[
					"EVERY REVIEW RE-PROPOSES FROM THE FRESH SCAN: when a review (weekly check or owner request) has refreshed the facts, propose again from them, one batch per kind (caches, uninstalls, media), instead of relying on what you proposed earlier.",
					"A new proposal replaces the earlier open one of the same kind: propose_cleanup closes it as superseded and lists the ids it replaced in its result. A proposal of only advice replaces nothing.",
					"ALWAYS call `proposals` before you tell the owner what is open or pending, and before you propose. Never call a proposal pending, open or waiting for approval unless `proposals` says its status is open; superseded, executed and rejected ones are not waiting.",
					"Every owner message ends with a server note (the open list and the latest outcomes), read from the server's own record just now; it is not the owner's text, and it beats anything you said earlier.",
					"The `decisions` ledger records what the owner decided and what the phone reported, plus a superseded row when a newer proposal replaced one; a superseded row is not an owner decision. If it looks empty, say you called it and what it returned; do not assume.",
				].join(" "),
			),
			section("favourites-note", () =>
				[
					"FAVOURITES AS A PROTECTION MARKER: favourites are read from the phone's media store; whether the owner's gallery app exposes its own favourites to other apps varies by vendor, so favourites may not be a reliable protection marker on some phones.",
					'The marker that works on every phone is the "Keep" folder or album. When advising the owner how to protect a photo, tell them to put it in Keep.',
				].join(" "),
			),
		],
	});
}

/** Resolve the tools the conversation's agent actually offers (the safety test's subject). */
export async function resolveAgentTools(root: Conversation, context: Context): Promise<string[]> {
	const agent = await root.agent(context);
	return agent.tools.map((tool) => tool.name);
}
