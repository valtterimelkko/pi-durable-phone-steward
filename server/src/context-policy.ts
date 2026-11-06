/**
 * A11.1: the steward's compaction policy follows the model's window .
 *
 * The withdrawn A10.9 working budget (an artificial 128 000-token cap) is gone: the owner
 * compacts at 75 % of whatever window the current model offers. Pi Durable's
 * `CompactionPolicy` is expressed against the window, so this module maps:
 *
 *   reserveTokens    = max(16 384, round(0.10 x W))     (the block point is W - reserve)
 *   backgroundTokens = (W - reserve) - round(compactAt x W)
 *   keepRecentTokens = max(20 000, round(0.05 x W))
 *   compactAtTokens  = W - reserve - backgroundTokens = round(compactAt x W)
 *
 * A window so small that the reserve floor (16 384) would put 75 % at or above the block
 * point keeps the pre-A11.1 small-window rule (reserve = W / 4, background compaction at
 * 75 % of what is left), which is the only sane reading for such a window. An unknown
 * window (0) keeps Pi Durable's defaults.
 */
import type { CompactionPolicy } from "@earendil-works/pi-durable";

export const COMPACT_AT_DEFAULT = 0.75;
export const COMPACT_AT_MIN = 0.5;
export const COMPACT_AT_MAX = 0.9;
export const KEEP_RECENT_TOKENS = 20_000;
/** Room always kept free for the model's answer (Pi Durable's own default reserve). */
export const MIN_RESERVE_TOKENS = 16_384;
/** The pre-A11.1 small-window rule starts background compaction this far below the block. */
const BACKGROUND_FRACTION = 0.25;

/** `PHONE_STEWARD_COMPACT_AT`: a fraction in 0.5–0.9; anything else falls back to 0.75. */
export function clampCompactAt(value: unknown): number {
	if (typeof value !== "number" || !Number.isFinite(value) || value <= 0) return COMPACT_AT_DEFAULT;
	return Math.min(COMPACT_AT_MAX, Math.max(COMPACT_AT_MIN, value));
}

export function compactionPolicyFor(contextWindow: number, compactAt: number = COMPACT_AT_DEFAULT): CompactionPolicy {
	const window = Number.isFinite(contextWindow) && contextWindow > 0 ? Math.floor(contextWindow) : 0;
	if (window === 0) {
		// Unknown window: Pi Durable disables the threshold when the window is 0 anyway.
		return { enabled: true, reserveTokens: MIN_RESERVE_TOKENS, keepRecentTokens: KEEP_RECENT_TOKENS, backgroundTokens: 0 };
	}
	const fraction = clampCompactAt(compactAt);
	const reserve = Math.max(Math.round(0.10 * window), MIN_RESERVE_TOKENS);
	const block = window - reserve;
	const background = block - Math.round(fraction * window);
	// The general rule is degenerate (75 % at or above the block point) only on a window small
	// enough that the reserve floor dominates; there the pre-A11.1 small-window rule applies.
	if (background > 0 || reserve > MIN_RESERVE_TOKENS) {
		return {
			enabled: true,
			reserveTokens: reserve,
			keepRecentTokens: Math.max(KEEP_RECENT_TOKENS, Math.round(0.05 * window)),
			backgroundTokens: Math.max(0, background),
		};
	}
	const smallReserve = Math.floor(window / 4);
	const effective = Math.max(1, window - smallReserve);
	return {
		enabled: true,
		reserveTokens: smallReserve,
		keepRecentTokens: Math.max(1, Math.min(KEEP_RECENT_TOKENS, Math.floor(effective / 2))),
		backgroundTokens: Math.round(BACKGROUND_FRACTION * effective),
	};
}

/** The token count at which background compaction starts: the footer's compact-at marker. */
export function compactAtTokensFor(contextWindow: number, policy: CompactionPolicy): number {
	const window = Number.isFinite(contextWindow) && contextWindow > 0 ? Math.floor(contextWindow) : 0;
	if (window === 0) return 0;
	return Math.max(0, window - policy.reserveTokens - policy.backgroundTokens);
}

/** The headings the summary must use, so the state brief can lift exactly these parts. */
export const PREFERENCES_HEADING = "## Owner preferences and decisions";
export const THREADS_HEADING = "## Open threads";

/**
 * The summariser's steering text (the "Additional focus" of Pi Durable's summary prompt).
 * The steward is not a coding agent: what must survive is what the owner said and decided,
 * and what is unfinished. Numbers the tools can re-read are noise.
 */
export const STEWARD_SUMMARY_INSTRUCTIONS = [
	"This conversation is with the owner of an Android phone that the Phone Steward looks after.",
	"Keep what only this conversation knows: the owner's stated preferences, rules of thumb and decisions (what they keep, what they never want touched, how they like proposals batched), and the open threads (anything asked, promised or waiting on the owner or on the phone).",
	"Drop numbers and lists the tools can re-read (storage sizes, app lists, media indexes, proposal contents, ledger rows): say which tool to call instead.",
	"Never keep file names or contact names.",
	`Use these headings in addition to the usual ones, exactly: "${PREFERENCES_HEADING}" and "${THREADS_HEADING}".`,
].join(" ");

const KEPT_HEADING = /^#{1,4}\s*(owner preferences(?: and decisions)?|open threads)\b/i;
const ANY_HEADING = /^#{1,4}\s/;
/** The most of the last summary the state brief carries. */
export const SUMMARY_THREADS_MAX_CHARS = 3_000;

/**
 * The owner-preferences and open-threads parts of a compaction summary, and nothing else
 * from it. Empty when the summary has neither (the brief then simply omits the part).
 */
export function openThreadsFrom(summary: string | undefined): string {
	if (summary === undefined || summary.length === 0) return "";
	const lines = summary.split("\n");
	const kept: string[] = [];
	let inKept = false;
	for (const line of lines) {
		if (ANY_HEADING.test(line)) inKept = KEPT_HEADING.test(line);
		if (inKept) kept.push(line);
	}
	const text = kept.join("\n").trim();
	return text.length <= SUMMARY_THREADS_MAX_CHARS ? text : `${text.slice(0, SUMMARY_THREADS_MAX_CHARS)}…`;
}
