/**
 * The screen tree of protocol A10.3 (`ScreenTree`) as the server accepts and shows it.
 *
 * The app serialises and caps the tree first; the server enforces its own limits again
 * (never trust the wire): password fields are dropped, the node count and the text are
 * capped, malformed nodes are skipped. The model sees a compact text rendering, never raw
 * JSON, and everything on screen is labelled as untrusted data by the live prompt.
 */
import { badRequest } from "../errors.ts";

export const MAX_TREE_NODES = 150;
export const MAX_NODE_TEXT = 160;
export const MAX_NODE_DESC = 120;
export const MAX_DETAIL = 500;

export type ScreenNode = {
	id: string;
	role: string;
	text?: string;
	desc?: string;
	clickable: boolean;
	editable: boolean;
	/** Additive: the node scrolls, so scroll with its nodeId. */
	scrollable?: boolean;
	bounds: [number, number, number, number];
};

export type ScreenTree = {
	package: string;
	activity?: string;
	nodes: ScreenNode[];
	truncated: boolean;
};

// S13 (P7 review): the same rule as the app's TreeSerialiser.redactCodes, applied again here so
// a one-time code is never stored, rendered or returned whichever way it reached the server.
const CODE_WORDS = /\b(code|otp|passcode|one-time|verification|verify|pin|tunnus|vahvistus|koodi|kod|code de|sms)\b/i;
const DIGIT_RUN = /\b\d{4,8}\b/g;

/** Mask a 4 to 8 digit run when the same text talks about a code. */
export function redactCodes(text: string): string {
	return CODE_WORDS.test(text) ? text.replace(DIGIT_RUN, "\u2022\u2022\u2022\u2022") : text;
}

function clip(value: unknown, max: number): string | undefined {
	if (typeof value !== "string") return undefined;
	const trimmed = redactCodes(value.replace(/\s+/g, " ").trim());
	if (trimmed === "") return undefined;
	return trimmed.length > max ? trimmed.slice(0, max) : trimmed;
}

function cleanNode(raw: unknown): ScreenNode | undefined {
	if (raw === null || typeof raw !== "object" || Array.isArray(raw)) return undefined;
	const o = raw as Record<string, unknown>;
	if (typeof o.id !== "string" || o.id === "" || o.id.length > 32) return undefined;
	const role = typeof o.role === "string" ? o.role.slice(0, 40) : "view";
	// Password fields never reach the model, whichever way the phone marks them.
	if (role.toLowerCase().includes("password") || o.password === true) return undefined;
	const bounds = Array.isArray(o.bounds) && o.bounds.length === 4 && o.bounds.every((n) => typeof n === "number" && Number.isFinite(n))
		? ([o.bounds[0], o.bounds[1], o.bounds[2], o.bounds[3]] as [number, number, number, number])
		: ([0, 0, 0, 0] as [number, number, number, number]);
	const text = clip(o.text, MAX_NODE_TEXT);
	const desc = clip(o.desc, MAX_NODE_DESC);
	return {
		id: o.id,
		role,
		...(text === undefined ? {} : { text }),
		...(desc === undefined ? {} : { desc }),
		clickable: o.clickable === true,
		editable: o.editable === true,
		...(o.scrollable === true ? { scrollable: true } : {}),
		bounds,
	};
}

/** Validate and clamp a tree from the phone. Throws 400 for a tree that is not a tree at all. */
export function sanitiseTree(raw: unknown): ScreenTree {
	if (raw === null || typeof raw !== "object" || Array.isArray(raw)) throw badRequest("tree must be an object");
	const o = raw as Record<string, unknown>;
	if (typeof o.package !== "string" || o.package === "") throw badRequest("tree.package is required");
	if (!Array.isArray(o.nodes)) throw badRequest("tree.nodes must be an array");
	const nodes: ScreenNode[] = [];
	let truncated = o.truncated === true;
	for (const item of o.nodes) {
		const cleaned = cleanNode(item);
		if (cleaned === undefined) continue;
		if (nodes.length >= MAX_TREE_NODES) {
			truncated = true;
			break;
		}
		nodes.push(cleaned);
	}
	const activity = clip(o.activity, 120);
	return { package: o.package.slice(0, 200), ...(activity === undefined ? {} : { activity }), nodes, truncated };
}

/** The model's view of a tree: one short line per node. */
export function renderTree(tree: ScreenTree): string {
	const lines = [`package: ${tree.package}`];
	if (tree.activity !== undefined) lines.push(`screen: ${tree.activity}`);
	for (const node of tree.nodes) {
		const flags = [node.clickable ? "tap" : undefined, node.editable ? "type" : undefined, node.scrollable === true ? "scroll" : undefined].filter((f) => f !== undefined);
		const label = [node.text === undefined ? undefined : `"${node.text}"`, node.desc === undefined ? undefined : `(${node.desc})`].filter((s) => s !== undefined).join(" ");
		lines.push(`${node.id} ${node.role}${label === "" ? "" : ` ${label}`}${flags.length === 0 ? "" : ` [${flags.join(",")}]`}`);
	}
	if (tree.truncated) lines.push("(the tree was cut short: scroll or act, then observe again)");
	return lines.join("\n");
}
