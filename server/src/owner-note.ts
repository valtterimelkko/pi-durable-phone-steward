/**
 * S9 (protocol A9.2): the server note on every owner chat turn.
 *
 * Why in code: the model (GLM 5.3 Flash) answered "Are you here?" from its own old messages,
 * calling an executed proposal "still waiting" and the decisions ledger "empty". Phone-result
 * and weekly turns already carry the server's open list (S7/S8); an owner turn now carries it
 * too, plus the latest outcome of each recently decided proposal. The note is appended to the
 * model's copy of the owner's message after a marker line, and the chat bridge cuts it off
 * again, so the app's chat log and stream show exactly what the owner typed.
 */
import type { Context } from "@earendil-works/chord";
import type { ConversationId, Harness } from "@earendil-works/pi-durable";
import { Decisions, type DecisionLedgerEntry } from "./documents.ts";
import { PhoneUseRequests, declinedRequest, type PhoneUseRequest } from "./live/requests.ts";
import { Proposals, type Proposal } from "./proposals.ts";
import { withServerNote } from "./server-note.ts";
import { stewardDate } from "./time.ts";
import { formatBytes, openListText, openProposalLines } from "./weekly-cache.ts";

/** How far back a decided proposal's outcome is reported. */
export const DECIDED_WINDOW_MS = 14 * 24 * 60 * 60_000;
/** The most outcome lines one note carries. */
const MAX_OUTCOME_LINES = 20;
/** The most restored paths one note names. */
const MAX_RESTORED_LINES = 20;

type ReportOutcome = {
	atMs: number;
	done: number;
	cancelled: number;
	failed: number;
	skippedProtected: number;
	freedBytes: number;
	/** A10.1: bytes moved to the Steward trash (space returns when it is emptied). */
	trashedBytes: number;
	/** S12: items the owner left unticked in the latest decision. */
	kept: number;
};

/**
 * S12: the items the owner left unticked. The decision writes one `kept` row per item; an
 * approved item's row carries the "approved; awaiting execution report" note, an unticked
 * one has none. The newest decision's rows share one `atMs`.
 */
function untickedCount(entries: readonly DecisionLedgerEntry[]): number {
	const rows = entries.filter((entry) => entry.type === "kept" && entry.note === undefined);
	if (rows.length === 0) return 0;
	const atMs = Math.max(...rows.map((row) => row.atMs));
	return rows.filter((row) => row.atMs === atMs).length;
}

/** The latest execution report of a proposal: the ledger rows of that report share one `atMs`. */
function latestReport(entries: readonly DecisionLedgerEntry[]): ReportOutcome | undefined {
	const rows = entries.filter(
		(entry) => entry.type === "executed" || entry.type === "cancelled" || entry.type === "failed" || entry.type === "skipped_protected",
	);
	if (rows.length === 0) return undefined;
	const atMs = Math.max(...rows.map((row) => row.atMs));
	const outcome: ReportOutcome = {
		atMs,
		done: 0,
		cancelled: 0,
		failed: 0,
		skippedProtected: 0,
		freedBytes: 0,
		trashedBytes: 0,
		kept: untickedCount(entries),
	};
	for (const row of rows.filter((candidate) => candidate.atMs === atMs)) {
		if (row.type === "executed") outcome.done += 1;
		else if (row.type === "cancelled") outcome.cancelled += 1;
		else if (row.type === "failed") outcome.failed += 1;
		else outcome.skippedProtected += 1;
		if (typeof row.bytesFreed === "number" && row.bytesFreed > 0) outcome.freedBytes += row.bytesFreed;
		if (typeof row.bytesTrashed === "number" && row.bytesTrashed > 0) outcome.trashedBytes += row.bytesTrashed;
	}
	return outcome;
}

function reportParts(report: ReportOutcome, includeDone: boolean): string[] {
	const parts: string[] = [];
	if (includeDone && report.done > 0) {
		const detail: string[] = [];
		if (report.freedBytes > 0) detail.push(`${formatBytes(report.freedBytes)} freed`);
		if (report.trashedBytes > 0) detail.push(`${formatBytes(report.trashedBytes)} moved to the Steward trash; the space returns when it is emptied`);
		parts.push(`${report.done} done${detail.length > 0 ? ` (${detail.join(", ")})` : ""}`);
	}
	if (report.kept > 0) parts.push(`${report.kept} kept by the owner`);
	if (report.cancelled > 0) parts.push(`${report.cancelled} cancelled on the phone`);
	if (report.failed > 0) parts.push(`${report.failed} failed on the phone`);
	if (report.skippedProtected > 0) parts.push(`${report.skippedProtected} skipped as protected`);
	return parts;
}

function closedAtMsOf(proposal: Proposal): number {
	return proposal.statusChangedAtMs ?? proposal.supersededAtMs ?? proposal.createdAtMs;
}

/** One line per proposal decided inside the window, newest first; undecided open ones are not here. */
function decidedLines(proposals: readonly Proposal[], ledger: readonly DecisionLedgerEntry[], nowMs: number): string[] {
	const byProposal = new Map<string, DecisionLedgerEntry[]>();
	for (const entry of ledger) {
		if (entry.proposalId === undefined) continue;
		const list = byProposal.get(entry.proposalId) ?? [];
		list.push(entry);
		byProposal.set(entry.proposalId, list);
	}
	const lines: Array<{ atMs: number; text: string }> = [];
	for (const proposal of proposals) {
		const report = latestReport(byProposal.get(proposal.proposalId) ?? []);
		let atMs: number;
		let outcome: string;
		switch (proposal.status) {
			case "open": {
				if (report === undefined) continue; // never decided: it is on the open list
				atMs = report.atMs;
				const parts = reportParts(report, true);
				outcome = `open again: ${parts.length > 0 ? parts.join(", ") : "nothing was done"}`;
				break;
			}
			case "executed": {
				atMs = report?.atMs ?? closedAtMsOf(proposal);
				const parts = report === undefined ? [] : reportParts(report, true);
				outcome = `executed${parts.length > 0 ? `: ${parts.join(", ")}` : ""}`;
				break;
			}
			case "approved":
				atMs = closedAtMsOf(proposal);
				outcome = "approved by the owner; the phone has not reported back yet";
				break;
			case "rejected":
				atMs = closedAtMsOf(proposal);
				outcome = "rejected by the owner";
				break;
			case "superseded":
				atMs = closedAtMsOf(proposal);
				outcome = `superseded by ${proposal.supersededBy ?? "a newer proposal"} (not an owner decision)`;
				break;
			default:
				atMs = closedAtMsOf(proposal);
				outcome =
					proposal.closedReason === "removed_outside"
						? "closed: every app in it was already removed outside the app (not an owner decision)"
						: "expired";
		}
		if (nowMs - atMs > DECIDED_WINDOW_MS) continue;
		lines.push({ atMs, text: `- ${proposal.proposalId} "${proposal.title}": ${outcome} (${stewardDate(atMs)})` });
	}
	return lines
		.sort((a, b) => b.atMs - a.atMs)
		.slice(0, MAX_OUTCOME_LINES)
		.map((line) => line.text);
}

/**
 * S11 (A10.8): apps found removed outside the app in the last 14 days, from the ledger's
 * `removed_outside` rows (the label is in `note`). Undefined when there are none.
 */
function removedOutsideLines(ledger: readonly DecisionLedgerEntry[], nowMs: number): string | undefined {
	const rows = ledger.filter((entry) => entry.type === "removed_outside" && nowMs - entry.atMs <= DECIDED_WINDOW_MS);
	if (rows.length === 0) return undefined;
	const names = [...new Set(rows.map((row) => row.note ?? row.packageName ?? "an app"))];
	return `Apps removed outside the app in the last 14 days (found missing by the phone's app list, dropped from the open proposals; they were not removed by an approval): ${names.join(", ")}.`;
}

/**
 * A11.3: files the owner put back from the Steward trash in the last 14 days, from the
 * ledger's `restored` `delete_files` rows. They are on the phone again, so the steward must
 * not propose them once more unless the owner asks. Undefined when there are none.
 */
function restoredFileLines(ledger: readonly DecisionLedgerEntry[], nowMs: number): string | undefined {
	const rows = ledger.filter(
		(entry) => entry.type === "restored" && entry.kind === "delete_files" && typeof entry.path === "string" && nowMs - entry.atMs <= DECIDED_WINDOW_MS,
	);
	if (rows.length === 0) return undefined;
	const paths = [...new Set(rows.map((row) => row.path!))].slice(0, MAX_RESTORED_LINES);
	return `Files the owner restored from the Steward trash in the last 14 days (put back on the phone; do not propose these paths again unless the owner asks): ${paths.join(", ")}.`;
}

/**
 * The note for one owner turn, from the server's own record: the authoritative open list,
 * the size of the decisions ledger, and the latest outcome of each proposal decided in the
 * last 14 days.
 */
/**
 * A13.2: the line that tells the model the owner declined its phone-use ask. The request
 * records come from the request document, newest answer first; within a day of the
 * decline.
 */
export function declinedPhoneUseLine(requests: Iterable<PhoneUseRequest>, nowMs: number): string | undefined {
	const declined = declinedRequest([...requests], nowMs, 24 * 60 * 60_000);
	if (declined === undefined) return undefined;
	return `The owner declined your phone-use ask ("${declined.task.slice(0, 120)}")${declined.why === "" ? "" : ` about ${declined.why.slice(0, 120)}`}. Do not ask again for the same thing; offer what you can do without the screen.`;
}

export function ownerChatNote(input: {
	proposals: Iterable<Proposal>;
	ledger: readonly DecisionLedgerEntry[];
	nowMs: number;
	/** A13.2: the phone-use request records, for the decline line. */
	phoneUseRequests?: Iterable<PhoneUseRequest>;
}): string {
	const proposals = [...input.proposals];
	const open = openListText(openProposalLines(proposals));
	const decided = decidedLines(proposals, input.ledger, input.nowMs);
	const removed = removedOutsideLines(input.ledger, input.nowMs);
	const restored = restoredFileLines(input.ledger, input.nowMs);
	const declined = input.phoneUseRequests === undefined ? undefined : declinedPhoneUseLine(input.phoneUseRequests, input.nowMs);
	const outcomes =
		decided.length === 0
			? "No proposal was decided in the last 14 days."
			: `Decided in the last 14 days (latest outcome of each; this overrides anything you said earlier about them):\n${decided.join("\n")}`;
	return [
		"The owner did not type this note; it is the server's own record, read just now. Answer from it, not from your earlier messages.",
		open,
		`Decisions ledger: ${input.ledger.length} entries (the \`decisions\` tool lists them; never call it empty without calling it).`,
		outcomes,
		...(removed === undefined ? [] : [removed]),
		...(restored === undefined ? [] : [restored]),
		...(declined === undefined ? [] : [declined]),
	].join("\n");
}

/**
 * The model's copy of an owner message: the typed text plus the server note, read from
 * committed state. Used by every path that submits an owner chat turn.
 */
export async function ownerTurnContent(
	harness: Harness,
	conversationId: ConversationId,
	text: string,
	context: Context,
	nowMs: number,
): Promise<string> {
	const proposals = await harness.snapshot(Proposals, conversationId, context);
	const decisions = await harness.snapshot(Decisions, conversationId, context);
	const phoneUse = await harness.snapshot(PhoneUseRequests, context);
	return withServerNote(
		text,
		ownerChatNote({
			proposals: Object.values(proposals?.proposals ?? {}),
			ledger: decisions?.entries ?? [],
			nowMs,
			phoneUseRequests: Object.values(phoneUse?.requests ?? {}),
		}),
	);
}

export { SERVER_NOTE_MARKER, stripServerNote, withServerNote } from "./server-note.ts";
