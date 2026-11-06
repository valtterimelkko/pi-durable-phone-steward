/**
 * Worklog line construction (`docs/protocol.md` §10).
 *
 * The steward mirrors each conversation into an append-only JSONL file that the
 * configured orchestration tool (if any) can read as runtime `steward`; with nothing
 * reading it, it is still a plain, inspectable log of what the steward did. Every line's `id` is a deterministic 8-hex function of the
 * session uuid and the mirrored item, so a restarted process that rewrites a line it
 * already wrote produces the same id — the mirror skips ids already in the file, which
 * is what makes the mirror idempotent across crashes. Line ids, not a cursor, are the
 * cursor.
 */
import { createHash, randomUUID } from "node:crypto";
import type { EntryRecord } from "@earendil-works/pi-durable";
import type { Message } from "@earendil-works/pi-ai";
/** One JSONL mirror line, in the exact key order the fixture uses. */
export type StewardLine = {
	type: "session" | "model_change" | "message" | "custom";
	// The remaining keys differ per line type; see the builders below.
	[key: string]: unknown;
};

/**
 * 8 lowercase hex, deterministic in its inputs: a rewritten line keeps its id, so a
 * restart can tell "already written" from "lost in the crash".
 */
export function lineId(...parts: readonly string[]): string {
	return createHash("sha256").update(parts.join(":")).digest("hex").slice(0, 8);
}

export type SessionHeaderInput = {
	sessionUuid: string;
	/** Epoch ms of the session start; also the file-name timestamp. */
	startedAtMs: number;
	cwd?: string;
};

/** `{"type":"session",...}` — the first line of every mirror file. */
export function sessionHeaderLine(input: SessionHeaderInput): StewardLine {
	return {
		type: "session",
		version: 3,
		id: input.sessionUuid,
		timestamp: new Date(input.startedAtMs).toISOString(),
		cwd: input.cwd,
		runtime: "steward",
	};
}

export type ModelChangeInput = {
	sessionUuid: string;
	provider: string;
	modelId: string;
	atMs: number;
	parentId: string | null;
	/** How many model_change lines the file already holds; keeps ids unique across changes. */
	ordinal: number;
};

/** At start and on every model change. The first entry after the header has `parentId: null`. */
export function modelChangeLine(input: ModelChangeInput): StewardLine {
	return {
		type: "model_change",
		id: lineId(input.sessionUuid, "model-change", input.provider, input.modelId, String(input.ordinal)),
		parentId: input.parentId,
		timestamp: new Date(input.atMs).toISOString(),
		provider: input.provider,
		modelId: input.modelId,
	};
}

export type StewardEventLineInput = {
	sessionUuid: string;
	/** The event's drain sequence, recorded durably at stewardEvent time. */
	seq: number;
	kind: string;
	atMs: number;
	data: Record<string, string | number | boolean | null>;
	parentId: string | null;
};

/** The id of the steward-event line for `seq` — shared by the writer and the drain filter. */
export function stewardEventLineId(sessionUuid: string, seq: number): string {
	return lineId(sessionUuid, "event", String(seq));
}

/** `{"type":"custom","customType":"steward-event",...}` — one durable steward event. */
export function stewardEventLine(input: StewardEventLineInput): StewardLine {
	return {
		type: "custom",
		customType: "steward-event",
		id: lineId(input.sessionUuid, "event", String(input.seq)),
		parentId: input.parentId,
		timestamp: new Date(input.atMs).toISOString(),
		data: { kind: input.kind, ...input.data },
	};
}

type TextBlock = { type: "text"; text: string };
type ToolCallBlock = { type: "toolCall"; id: string; name: string; arguments: unknown };

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null;
}

/** Content blocks that the mirror carries: text and tool calls. Thinking is never mirrored. */
function mirrorableContent(content: unknown): Array<TextBlock | ToolCallBlock> {
	if (typeof content === "string") return [{ type: "text", text: content }];
	if (!Array.isArray(content)) return [];
	const out: Array<TextBlock | ToolCallBlock> = [];
	for (const block of content) {
		if (!isRecord(block)) continue;
		if (block.type === "text" && typeof block.text === "string") {
			out.push({ type: "text", text: block.text });
		} else if (block.type === "toolCall" && typeof block.id === "string" && typeof block.name === "string") {
			out.push({ type: "toolCall", id: block.id, name: block.name, arguments: block.arguments ?? {} });
		}
		// Image and thinking blocks are dropped: the mirror carries text and calls only.
	}
	return out;
}

export type MessageLineInput = {
	sessionUuid: string;
	entry: EntryRecord;
	parentId: string | null;
};

/**
 * The message line for one committed conversation entry, or `undefined` for entries the
 * mirror does not carry (bookkeeping kinds, or entries with no model message). One entry
 * becomes one line: all its parts share the line's content array, like the fixture.
 */
export function messageLineFromEntry(input: MessageLineInput): StewardLine | undefined {
	const kind = input.entry.kind;
	const message: Message | undefined = input.entry.model?.[0];
	if (message === undefined) return undefined;
	if (kind === "pi.user" && message.role === "user") {
		const atMs = message.timestamp;
		return {
			type: "message",
			id: lineId(input.sessionUuid, "entry", String(input.entry.id)),
			parentId: input.parentId,
			timestamp: new Date(atMs).toISOString(),
			message: {
				role: "user",
				content: mirrorableContent(message.content),
				timestamp: atMs,
			},
		};
	}
	if (kind === "pi.assistant" && message.role === "assistant") {
		const atMs = message.timestamp;
		return {
			type: "message",
			id: lineId(input.sessionUuid, "entry", String(input.entry.id)),
			parentId: input.parentId,
			timestamp: new Date(atMs).toISOString(),
			message: {
				role: "assistant",
				content: mirrorableContent(message.content),
				provider: message.provider,
				model: message.model,
				stopReason: message.stopReason,
				timestamp: atMs,
			},
		};
	}
	if (kind === "pi.tool-result" && message.role === "toolResult") {
		const atMs = message.timestamp;
		return {
			type: "message",
			id: lineId(input.sessionUuid, "entry", String(input.entry.id)),
			parentId: input.parentId,
			timestamp: new Date(atMs).toISOString(),
			message: {
				role: "toolResult",
				toolCallId: message.toolCallId,
				toolName: message.toolName,
				content: mirrorableContent(message.content),
				isError: message.isError === true,
				timestamp: atMs,
			},
		};
	}
	return undefined;
}

/** Mint the conversation's session uuid (kept in the conversation's own document). */
export function mintSessionUuid(): string {
	return randomUUID();
}
