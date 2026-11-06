/**
 * A11.2: the `phone_result` tool — server-only, read-only. The conversation carries a
 * summary of each phone result; this tool fetches the stored full result again (or one
 * dotted sub-value of it, with arrays paged), so the model can rely on a detail the summary
 * left out without pasting the whole result back into its context.
 *
 * Only jobs of the tool call's own conversation are visible: the jobs document is
 * conversation-scoped, so a job id from another conversation reads as `not_found`.
 */
import type { JsonValue } from "@earendil-works/chord";
import { defineTool } from "@earendil-works/pi-durable";
import { num, obj, opt, str } from "@earendil-works/pi-schema";
import { canonicalJson } from "./canonical.ts";
import { badRequest } from "./errors.ts";
import { Jobs, type PhoneJob } from "./jobs.ts";

/** The most one answer may carry, in characters of its canonical JSON. */
export const PHONE_RESULT_MAX_CHARS = 20_000;
export const PHONE_RESULT_DEFAULT_LIMIT = 50;
export const PHONE_RESULT_MAX_LIMIT = 200;

export type PhoneResultArgs = { jobId: string; path?: string; offset?: number; limit?: number };

function isRec(value: unknown): value is Record<string, JsonValue> {
	return value !== null && typeof value === "object" && !Array.isArray(value);
}

function intArg(value: unknown, name: string, min: number, max: number, fallback: number): number {
	if (value === undefined || value === null) return fallback;
	if (typeof value !== "number" || !Number.isInteger(value) || value < min || value > max) {
		throw badRequest(`${name} must be a whole number from ${min} to ${max}`);
	}
	return value;
}

function resolvePath(root: JsonValue, path: string): { found: true; value: JsonValue } | { found: false } {
	let current: JsonValue = root;
	for (const segment of path.split(".")) {
		if (isRec(current)) {
			if (!Object.prototype.hasOwnProperty.call(current, segment)) return { found: false };
			current = current[segment]!;
		} else if (Array.isArray(current)) {
			const index = Number(segment);
			if (!Number.isInteger(index) || index < 0 || index >= current.length) return { found: false };
			current = current[index]!;
		} else {
			return { found: false };
		}
	}
	return { found: true, value: current };
}

/**
 * The tool's answer for one job (the pure core, tested without a harness). `undefined` for a
 * job this conversation does not have.
 */
export function phoneResultAnswer(job: PhoneJob | undefined, args: PhoneResultArgs): JsonValue {
	if (job === undefined) {
		return { status: "not_found", jobId: args.jobId, message: "no job of this conversation has that id" };
	}
	if (job.result === undefined) {
		return { status: "no_result", jobId: args.jobId, kind: job.kind, jobStatus: job.status, message: "the phone has not reported this job yet" };
	}
	const data = (job.result.data ?? null) as JsonValue;
	// A junk offset or limit is a caller error, whether or not the value turns out to be an array.
	const offset = intArg(args.offset, "offset", 0, Number.MAX_SAFE_INTEGER, 0);
	const limit = intArg(args.limit, "limit", 1, PHONE_RESULT_MAX_LIMIT, PHONE_RESULT_DEFAULT_LIMIT);
	let value: JsonValue = data;
	let path: string | undefined;
	if (args.path !== undefined && args.path !== "") {
		const resolved = resolvePath(data, args.path);
		if (!resolved.found) return { status: "no_such_path", jobId: args.jobId, kind: job.kind, path: args.path };
		value = resolved.value;
		path = args.path;
	}
	const base: Record<string, JsonValue> = { status: "ok", jobId: args.jobId, kind: job.kind, ...(path === undefined ? {} : { path }) };
	if (Array.isArray(value)) {
		const count = value.length;
		const page = value.slice(offset, offset + limit);
		const answerFor = (shown: number): Record<string, JsonValue> => ({
			...base,
			items: page.slice(0, shown),
			offset,
			limit,
			count,
			...(offset + shown < count ? { nextOffset: offset + shown } : {}),
		});
		let shown = page.length;
		let truncated = false;
		if (canonicalJson(answerFor(shown)).length > PHONE_RESULT_MAX_CHARS) {
			let low = 0;
			let high = page.length;
			while (low < high) {
				const middle = Math.ceil((low + high) / 2);
				if (canonicalJson(answerFor(middle)).length <= PHONE_RESULT_MAX_CHARS) low = middle;
				else high = middle - 1;
			}
			shown = low;
			truncated = true;
		}
		return { ...answerFor(shown), ...(truncated ? { truncated: true } : {}) };
	}
	const answer: Record<string, JsonValue> = path === undefined ? { ...base, data: value } : { ...base, value };
	if (canonicalJson(answer).length <= PHONE_RESULT_MAX_CHARS) return answer;
	return { ...base, truncated: true, json: canonicalJson(value).slice(0, PHONE_RESULT_MAX_CHARS) };
}

export const phoneResultTool = defineTool({
	name: "phone_result",
	description:
		"Read the full stored result of a phone job of this conversation. The message that carried the result held only a summary; call this before you rely on a detail it left out. Pass `path` (a dotted key, for example apps or folders) for one sub-value; an array is paged with `offset` and `limit` (default 50, at most 200), and a long answer says truncated with the next offset. Read-only and deterministic.",
	parameters: obj({ jobId: str(), path: opt(str()), offset: opt(num()), limit: opt(num()) }, { open: true }),
	replay: "safe",
	execute: async (args, api, callContext) => {
		const jobId = args.jobId;
		if (typeof jobId !== "string" || jobId.length === 0 || jobId.length > 128) throw badRequest("jobId is required (the id from the [phone result] message)");
		const request: PhoneResultArgs = {
			jobId,
			...(args.path === undefined ? {} : { path: String(args.path) }),
			...(args.offset === undefined ? {} : { offset: args.offset }),
			...(args.limit === undefined ? {} : { limit: args.limit }),
		};
		const answer = await api.commit(async (tx) => {
			const doc = await tx.doc(Jobs, api.conversationId);
			return JSON.parse(JSON.stringify(phoneResultAnswer(doc.jobs[jobId], request))) as JsonValue;
		}, callContext);
		return { output: [{ type: "text", text: canonicalJson(answer) }] };
	},
});
