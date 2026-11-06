/**
 * S7 (A6.3): the weekly check builds its cache batch itself, and its steward message carries
 * the server's own list of open proposals. Both are pure functions here; `rule-tasks.ts`
 * does the durable work around them.
 *
 * Why in code: the model (GLM 5.3 Flash) ignored the S6 prompt rule to re-propose from each
 * fresh scan, called no tools in a weekly check and quoted a closed proposal as pending from
 * its own old messages. A deterministic batch and an authoritative list do not depend on it.
 */
import type { ProposalItemKind } from "./proposals.ts";
import type { SnapshotApp } from "./documents.ts";
import type { JsonValue } from "@earendil-works/chord";
import type { JobKind } from "./jobs.ts";
import { summarisePhoneResult } from "./phone-summary.ts";

const MIB = 1024 * 1024;
/** An app's cache is listed in the batch from this size up. */
export const CACHE_APP_MIN_BYTES = 10 * MIB;
/** No batch is created when the listed caches total less than this. */
export const CACHE_BATCH_MIN_BYTES = 100 * MIB;

export type CacheBatch = {
	title: string;
	reason: string;
	apps: Array<{ packageName: string; label: string; cacheBytes: number }>;
	bytesEstimate: number;
};

/** Decimal units, as the rest of the product uses: "3.3 GB", "420 MB". */
export function formatBytes(bytes: number): string {
	if (bytes >= 1_000_000_000) return `${(bytes / 1_000_000_000).toFixed(1)} GB`;
	return `${Math.round(bytes / 1_000_000)} MB`;
}

/**
 * The cache batch for a fresh snapshot's per-app list (`Snapshot.apps`, every app the phone
 * reported): apps with `cacheBytes` >= 10 MiB, biggest first, in the A6 target shape. Returns
 * undefined when their total is under 100 MiB.
 */
export function buildCacheBatch(
	apps: readonly SnapshotApp[],
	date: string,
	options: { viaShizuku?: boolean } = {},
): CacheBatch | undefined {
	const listed = apps
		.filter(
			(entry) =>
				entry !== null && typeof entry === "object" &&
				typeof entry.packageName === "string" && typeof entry.label === "string" &&
				typeof entry.cacheBytes === "number" && Number.isFinite(entry.cacheBytes) &&
				entry.cacheBytes >= CACHE_APP_MIN_BYTES,
		)
		.map((entry) => ({ packageName: entry.packageName, label: entry.label, cacheBytes: entry.cacheBytes }))
		.sort((a, b) => b.cacheBytes - a.cacheBytes);
	const bytesEstimate = listed.reduce((sum, entry) => sum + entry.cacheBytes, 0);
	if (listed.length === 0 || bytesEstimate < CACHE_BATCH_MIN_BYTES) return undefined;
	const top = listed
		.slice(0, 3)
		.map((entry) => `${entry.label} (${formatBytes(entry.cacheBytes)})`)
		.join(", ");
	return {
		title: `Clear app caches (~${formatBytes(bytesEstimate)})`,
		reason:
			options.viaShizuku === true
				? `Weekly scan ${date}: ${listed.length} apps hold about ${formatBytes(bytesEstimate)} of cache; the biggest are ${top}. Shizuku is ready, so one step clears every app's cache with no Android dialog, and apps rebuild what they need.`
				: `Weekly scan ${date}: ${listed.length} apps hold about ${formatBytes(bytesEstimate)} of cache; the biggest are ${top}. One Android confirmation clears every app's cache, and apps rebuild what they need.`,
		apps: listed,
		bytesEstimate,
	};
}

export type OpenProposalLine = {
	proposalId: string;
	title: string;
	itemKinds: ProposalItemKind[];
	bytesEstimate: number;
};

/** The server's open proposals as list lines, oldest first (from the committed record). */
export function openProposalLines(
	proposals: Iterable<{
		proposalId: string;
		status: string;
		title: string;
		createdAtMs: number;
		bytesEstimate: number;
		items: ReadonlyArray<{ kind: ProposalItemKind }>;
	}>,
): OpenProposalLine[] {
	return [...proposals]
		.filter((proposal) => proposal.status === "open")
		.sort((a, b) => a.createdAtMs - b.createdAtMs)
		.map((proposal) => ({
			proposalId: proposal.proposalId,
			title: proposal.title,
			itemKinds: [...new Set(proposal.items.map((item) => item.kind))],
			bytesEstimate: proposal.bytesEstimate,
		}));
}

/**
 * The authoritative open list, as steward-facing text. Used by the weekly-check message
 * (S7) and appended to every phone-result turn outside a review (S8), so the steward
 * never narrates a proposal from its own old messages.
 */
export function openListText(open: readonly OpenProposalLine[], builtCacheProposalId?: string): string {
	const lines = open.map((proposal) => {
		const built = proposal.proposalId === builtCacheProposalId ? " — just created by the server from this week's scan" : "";
		// A10.1: delete_files moves to the Steward trash; the size is not freed until it is emptied.
		const trash = proposal.itemKinds.includes("delete_files") ? " — files are moved to the Steward trash; the space returns when it is emptied" : "";
		return `- ${proposal.proposalId}: "${proposal.title}" [${proposal.itemKinds.join(", ")}] ${formatBytes(proposal.bytesEstimate)}${built}${trash}`;
	});
	return lines.length === 0
		? "Server record (authoritative, read just now): no proposal is waiting for the owner."
		: `Server record (authoritative, read just now): proposals waiting for the owner:\n${lines.join("\n")}\nNo other proposal is waiting for the owner. Never call any proposal that is not on this list pending.`;
}

/**
 * S8: the phone results a weekly check gathered, one line per job, for the single weekly
 * message (they no longer arrive as one steward turn each). The storage overview is already
 * the stored snapshot, so only its status is named. Every other `ok` result carries the same
 * deterministic per-kind summary the follow-up text uses (A11.2), with the `phone_result`
 * pointer for the full stored result: correction 1 extended A11.2 here so one weekly message
 * can no longer paste tens of thousands of raw characters into the conversation.
 */
export function weeklyResultLines(
	jobs: ReadonlyArray<{
		jobId: string;
		kind: string;
		status: string;
		result?: { status: string; data: unknown; error: { code: string; message: string } | null };
	}>,
	stringify: (value: unknown) => string,
): string[] {
	const order = ["storage_overview", "app_usage", "media_index"];
	return [...jobs]
		.sort((a, b) => order.indexOf(a.kind) - order.indexOf(b.kind))
		.map((job) => {
			if (job.status !== "reported" || job.result === undefined) return `[phone job ${job.jobId}] kind=${job.kind} status=expired (the phone did not answer)`;
			if (job.result.status !== "ok") {
				return `[phone job ${job.jobId}] kind=${job.kind} status=${job.result.status} error=${job.result.error?.code ?? "UNKNOWN"} ${job.result.error?.message ?? ""}`.trimEnd();
			}
			if (job.kind === "storage_overview") return `[phone job ${job.jobId}] kind=storage_overview status=ok (stored as this week's snapshot)`;
			const summary = stringify(summarisePhoneResult(job.kind as JobKind, (job.result.data ?? null) as JsonValue));
			return `[phone job ${job.jobId}] kind=${job.kind} status=ok summary=${summary} (full result: phone_result {"jobId":"${job.jobId}"})`;
		});
}

/**
 * The steward message that starts the weekly check. `open` is read from the server's own
 * proposal record just before the submit; the text says it is authoritative. `results` are
 * the phone results the run gathered (S8: they settle silently into the run).
 */
export function weeklyCheckMessage(input: {
	date: string;
	open: readonly OpenProposalLine[];
	builtCacheProposalId: string | undefined;
	results?: readonly string[];
}): string {
	const head = `[schedule weekly_check ${input.date}] Run this week's storage check: read the latest snapshot, use storage_trend, and tell the owner what changed and what to do about it.`;
	const list = openListText(input.open, input.builtCacheProposalId);
	const cache =
		input.builtCacheProposalId === undefined
			? "The server built no cache batch this week (no fresh scan, or the caches total under 100 MiB)."
			: "The server already built this week's cache batch; do not propose another cache batch.";
	const results =
		input.results === undefined || input.results.length === 0
			? ""
			: `\nPhone results gathered for this check (they did not arrive as separate messages):\n${input.results.join("\n")}`;
	return `${head}\n${list}\n${cache}${results}`;
}
