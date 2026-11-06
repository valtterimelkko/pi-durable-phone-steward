/**
 * Tool-schema parity across a Pi Durable move .
 *
 * Every tool the steward installs is characterised here by the canonical JSON (keys sorted) of
 * its `parameters` — the exact JSON Schema the model is offered. The fixture was produced from
 * the 1.4.0 build (TypeBox `Type.*`) BEFORE the 1.5.0 pins moved, so this test proves that the
 * migration to `@earendil-works/pi-schema` builders changed no tool and no parameter: nothing
 * gained, nothing lost, nothing retyped.
 *
 * Allowed differences (the equivalence the migration intends, and the only ones permitted):
 *
 *   1. `additionalProperties` — every builder is written `obj({…}, { open: true })`, which omits
 *      the keyword, exactly as TypeBox's `Type.Object` did; an object schema that ever emitted
 *      `additionalProperties: false` would be a real change (it would refuse extra arguments), so
 *      a `false` value is NOT normalised away.
 *   2. `required: []` — an empty required list means the same as none; pi-schema omits it, and so
 *      does TypeBox, but a builder that emitted it would still be equivalent.
 *   3. a literal's `type` — TypeBox emits `{ const: "tap", type: "string" }`; pi-schema's `lit()`
 *      emits `{ const: "tap" }`. The `type` keyword is redundant beside `const` for validation.
 *
 * Everything else — names, descriptions, property names, ordering (canonicalised), types, bounds,
 * unions, optionals — must match byte for byte.
 */
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { beforeAll, describe, expect, it } from "vitest";
import { canonicalJson } from "../src/canonical.ts";
import { stewardRegistry } from "../src/steward.ts";

const here = dirname(fileURLToPath(import.meta.url));
const FIXTURE = join(here, "fixtures", "tool-schemas.json");

/** A JSON Schema as the fixture and the registry hold it. */
type Json = null | boolean | number | string | Json[] | { [key: string]: Json };

/** One entry of the fixture: the tool's own canonical schema, and the extension that installs it. */
type FixtureEntry = { extension: string; schema: string };

/**
 * The equivalence of the three allowed differences above, applied to both sides before the
 * comparison, so a difference that is NOT one of them still fails loudly.
 */
function normalise(value: Json): Json {
	if (Array.isArray(value)) return value.map(normalise);
	if (value === null || typeof value !== "object") return value;
	const source = value as { [key: string]: Json };
	const out: { [key: string]: Json } = {};
	for (const [key, entry] of Object.entries(source)) {
		if (key === "additionalProperties" && entry === true) continue; // allowed difference 1
		if (key === "required" && Array.isArray(entry) && entry.length === 0) continue; // allowed difference 2
		out[key] = normalise(entry);
	}
	// Allowed difference 3: a literal keeps its redundant `type` on one side only.
	if (source.const !== undefined && out.type === undefined) {
		const kind = typeof source.const;
		if (kind === "string" || kind === "number" || kind === "boolean") out.type = kind;
		if (source.const === null) out.type = "null";
	}
	return out;
}

/** Every installed tool with its extension, in install order, as canonical JSON. */
function installedSchemas(): Record<string, FixtureEntry> {
	const out: Record<string, FixtureEntry> = {};
	for (const { extension, tool } of stewardRegistry().snapshot().tools()) {
		expect(out[tool.name], `tool ${tool.name} is installed twice`).toBeUndefined();
		out[tool.name] = {
			extension: extension.name,
			schema: canonicalJson(normalise(JSON.parse(JSON.stringify(tool.parameters)) as Json)),
		};
	}
	return out;
}

/** The JSON Pointer paths at which two canonical schema values differ. */
function diffPaths(before: Json, after: Json, path = ""): string[] {
	if (canonicalJson(before) === canonicalJson(after)) return [];
	if (Array.isArray(before) && Array.isArray(after)) {
		const out: string[] = [];
		const length = Math.max(before.length, after.length);
		for (let index = 0; index < length; index++) out.push(...diffPaths(before[index] ?? null, after[index] ?? null, `${path}/${String(index)}`));
		return out;
	}
	if (before !== null && after !== null && typeof before === "object" && typeof after === "object" && !Array.isArray(before) && !Array.isArray(after)) {
		const a = before as { [key: string]: Json };
		const b = after as { [key: string]: Json };
		const out: string[] = [];
		for (const key of new Set([...Object.keys(a), ...Object.keys(b)])) {
			if (a[key] === undefined || b[key] === undefined) {
				out.push(`${path}/${key}`);
				continue;
			}
			out.push(...diffPaths(a[key]!, b[key]!, `${path}/${key}`));
		}
		return out;
	}
	return [path];
}

/** Whether one differing path is one of the three allowed differences. */
function allowedDifference(path: string, before: Json, after: Json): boolean {
	const leaf = path.slice(path.lastIndexOf("/") + 1);
	if (leaf === "additionalProperties") {
		// Only an omitted (open) keyword may differ; a `false` here would close the object.
		const value = atPath(before, path) ?? atPath(after, path);
		return value === undefined || value === true;
	}
	if (leaf === "required") {
		const value = atPath(before, path) ?? atPath(after, path);
		return Array.isArray(value) && value.length === 0;
	}
	if (leaf === "type") {
		const parentPath = path.slice(0, path.lastIndexOf("/"));
		const parent = atPath(before, parentPath) ?? atPath(after, parentPath);
		return parent !== null && typeof parent === "object" && !Array.isArray(parent) && (parent as { const?: Json }).const !== undefined;
	}
	return false;
}

function atPath(value: Json, path: string): Json | undefined {
	let cursor: Json | undefined = value;
	for (const part of path.split("/").slice(1)) {
		if (cursor === null || typeof cursor !== "object") return undefined;
		cursor = Array.isArray(cursor) ? cursor[Number(part)] : (cursor as { [key: string]: Json })[part];
	}
	return cursor;
}

/**
 * Seed the fixture from the build that is installed: `SEED_TOOL_SCHEMAS=1 npx vitest run test/tool-schemas.test.ts`.
 * Run once, on the 1.4.0 pins, and committed before the 1.5.0 bump.
 */
beforeAll(() => {
	if (process.env.SEED_TOOL_SCHEMAS !== "1") return;
	const packages = JSON.parse(readFileSync(join(here, "..", "package.json"), "utf8")) as { dependencies: Record<string, string> };
	const version = packages.dependencies["@earendil-works/pi-durable"];
	writeFileSync(
		FIXTURE,
		`${JSON.stringify(
			{ source: `@earendil-works/pi-durable ${String(version)} (TypeBox schemas), generated before the 1.5.0 pin bump`, tools: installedSchemas() },
			null,
			"\t",
		)}\n`,
	);
});

describe("tool schema parity (the model's parameters, 1.4.0 fixture)", () => {
	it("characterises the same tool names as the 1.4.0 build, and offers every one of them", () => {
		const fixture = JSON.parse(readFileSync(FIXTURE, "utf8")) as {
			source: string;
			tools: Record<string, FixtureEntry>;
		};
		expect(fixture.source).toContain("1.4.0");
		const current = Object.keys(installedSchemas()).sort();
		// An item-level enumeration: the names themselves, reconciled against the protocol list
		// (test/tools.test.ts) and the live-session set (test/live-session.test.ts).
		expect(current).toEqual(Object.keys(fixture.tools).sort());
		expect(current).toHaveLength(28);
		expect(current).toContain("propose_cleanup");
		expect(current).toContain("phone_result");
		expect(current).toContain("screen_act");
	});

	it("matches the 1.4.0 schema of every tool, after the three declared equivalences", () => {
		const fixture = JSON.parse(readFileSync(FIXTURE, "utf8")) as {
			source: string;
			tools: Record<string, FixtureEntry>;
		};
		const current = installedSchemas();
		const findings: string[] = [];
		for (const [name, entry] of Object.entries(fixture.tools)) {
			const now = current[name];
			if (now === undefined) {
				findings.push(`${name}: missing from the registry`);
				continue;
			}
			if (now.extension !== entry.extension) findings.push(`${name}: installed by ${now.extension}, was ${entry.extension}`);
			const before = JSON.parse(entry.schema) as Json;
			const after = JSON.parse(now.schema) as Json;
			const differing = diffPaths(before, after);
			const unexpected = differing.filter((path) => !allowedDifference(path, before, after));
			if (unexpected.length > 0) findings.push(`${name}: ${unexpected.join(", ")}`);
		}
		expect(findings).toEqual([]);
	});

	it("keeps every object schema open, as TypeBox's Type.Object was", () => {
		// Allowed difference 1 exists because `obj(…, { open: true })` omits `additionalProperties`;
		// a closed object would refuse arguments the model sends today. Pin the intent directly too.
		const closed: string[] = [];
		const walk = (value: Json, path: string): void => {
			if (Array.isArray(value)) {
				value.forEach((entry, index) => walk(entry, `${path}/${String(index)}`));
				return;
			}
			if (value === null || typeof value !== "object") return;
			const source = value as { [key: string]: Json };
			if (source.type === "object" && source.additionalProperties === false) closed.push(path);
			for (const [key, entry] of Object.entries(source)) walk(entry, `${path}/${key}`);
		};
		for (const [name, entry] of Object.entries(installedSchemas())) walk(JSON.parse(entry.schema) as Json, name);
		expect(closed).toEqual([]);
	});
});
