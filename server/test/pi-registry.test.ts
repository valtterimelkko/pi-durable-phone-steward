/**
 * A10.4: the steward reads Pi's own model registry and auth the way the `pi` CLI does on
 * this host (built-in catalogue + `models.json` + `auth.json`), read-only. Everything here
 * uses a scratch agent dir with invented keys.
 */
import { mkdtemp, readFile, rm, writeFile, stat, utimes } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { builtinModels } from "@earendil-works/pi-ai/providers/all";
import { ReadOnlyAuthFileStore, buildHostModels } from "../src/pi-registry.ts";

let dir: string;

beforeEach(async () => {
	dir = await mkdtemp(join(tmpdir(), "p7-registry-"));
});
afterEach(async () => {
	await rm(dir, { recursive: true, force: true });
});

const KEY_OPENROUTER = "sk-or-test-invented-0001";
const KEY_CUSTOM = "custom-secret-invented-0002";

async function writeAuth(data: unknown): Promise<string> {
	const path = join(dir, "auth.json");
	await writeFile(path, JSON.stringify(data), { mode: 0o600 });
	return path;
}

describe("ReadOnlyAuthFileStore", () => {
	it("reads credentials from the file and lists only provider ids and types", async () => {
		const path = await writeAuth({
			openrouter: { type: "api_key", key: KEY_OPENROUTER },
			"openai-codex": { type: "oauth", access: "a", refresh: "r", expires: Date.now() + 3_600_000 },
		});
		const store = new ReadOnlyAuthFileStore(path);
		expect(await store.read("openrouter")).toMatchObject({ type: "api_key", key: KEY_OPENROUTER });
		expect(await store.read("missing")).toBeUndefined();
		const listed = await store.list();
		expect([...listed].sort((a, b) => a.providerId.localeCompare(b.providerId))).toEqual([
			{ providerId: "openai-codex", type: "oauth" },
			{ providerId: "openrouter", type: "api_key" },
		]);
		expect(JSON.stringify(listed)).not.toContain(KEY_OPENROUTER);
	});

	it("treats a missing or unreadable file as no credentials", async () => {
		const store = new ReadOnlyAuthFileStore(join(dir, "nope.json"));
		expect(await store.read("openrouter")).toBeUndefined();
		expect(await store.list()).toEqual([]);
		await writeFile(join(dir, "bad.json"), "{ not json");
		expect(await new ReadOnlyAuthFileStore(join(dir, "bad.json")).list()).toEqual([]);
	});

	it("never writes: modify and delete leave the file byte-identical", async () => {
		const path = await writeAuth({ openrouter: { type: "api_key", key: KEY_OPENROUTER } });
		const before = await readFile(path);
		const mtimeBefore = (await stat(path)).mtimeMs;
		const store = new ReadOnlyAuthFileStore(path);
		await store.modify("openrouter", async () => ({ type: "api_key", key: "other" }));
		await store.delete("openrouter");
		expect((await readFile(path)).equals(before)).toBe(true);
		expect((await stat(path)).mtimeMs).toBe(mtimeBefore);
	});

	it("refuses to refresh an OAuth token that is expired or about to expire (a rotated refresh token would break the pi CLI's login)", async () => {
		const path = await writeAuth({
			"openai-codex": { type: "oauth", access: "a", refresh: "r", expires: Date.now() + 60_000 },
		});
		const store = new ReadOnlyAuthFileStore(path);
		let ran = false;
		await expect(
			store.modify("openai-codex", async () => {
				ran = true;
				return undefined;
			}),
		).rejects.toThrow(/refresh/i);
		expect(ran).toBe(false);
	});

	it("runs a no-op modify for a healthy OAuth token", async () => {
		const path = await writeAuth({
			"openai-codex": { type: "oauth", access: "a", refresh: "r", expires: Date.now() + 3_600_000 },
		});
		const store = new ReadOnlyAuthFileStore(path);
		let ran = false;
		const result = await store.modify("openai-codex", async () => {
			ran = true;
			return undefined;
		});
		expect(ran).toBe(true);
		expect(result).toMatchObject({ type: "oauth" });
	});

	it("sees a change to the file on the next read", async () => {
		const path = await writeAuth({});
		const store = new ReadOnlyAuthFileStore(path);
		expect(await store.read("openrouter")).toBeUndefined();
		await new Promise((resolve) => setTimeout(resolve, 15));
		await writeFile(path, JSON.stringify({ openrouter: { type: "api_key", key: KEY_OPENROUTER } }));
		expect(await store.read("openrouter")).toMatchObject({ key: KEY_OPENROUTER });
	});
});

describe("buildHostModels", () => {
	const noAmbient = { env: async () => undefined, fileExists: async () => false };

	it("offers the built-in catalogue and marks a provider available only when auth.json (or a custom key) holds credentials", async () => {
		await writeAuth({ openrouter: { type: "api_key", key: KEY_OPENROUTER } });
		const { models } = await buildHostModels({ agentDir: dir, authContext: noAmbient });
		const all = models.getModels();
		expect(all.some((m) => m.provider === "zai" && m.id === "glm-5.3-flash")).toBe(true);
		const available = await models.getAvailable();
		expect(available.some((m) => m.provider === "openrouter")).toBe(true);
		expect(available.some((m) => m.provider === "zai")).toBe(false);
		expect(available.some((m) => m.provider === "openai")).toBe(false);
	});

	it("loads models.json custom providers (key literal or env-var name; never a shell command) and overrides", async () => {
		await writeFile(
			join(dir, "models.json"),
			JSON.stringify({
				providers: {
					acme: {
						name: "Acme",
						baseUrl: "https://acme.invalid/v1",
						api: "openai-completions",
						apiKey: KEY_CUSTOM,
						models: [{ id: "acme-1", name: "Acme One", reasoning: true, contextWindow: 64000, maxTokens: 4096 }],
					},
					viaenv: {
						baseUrl: "https://viaenv.invalid/v1",
						api: "openai-completions",
						apiKey: "VIAENV_KEY",
						models: [{ id: "env-1" }],
					},
					shelly: {
						baseUrl: "https://shelly.invalid/v1",
						api: "openai-completions",
						apiKey: "!echo should-never-run",
						models: [{ id: "shell-1" }],
					},
					zai: { modelOverrides: { "glm-5.3-flash": { contextWindow: 200000 } } },
				},
			}),
		);
		await writeAuth({ zai: { type: "api_key", key: "zai-invented-key" } });
		const { models } = await buildHostModels({
			agentDir: dir,
			authContext: { env: async (n) => (n === "VIAENV_KEY" ? "env-secret-invented" : undefined), fileExists: async () => false },
		});
		const acme = models.getModel("acme", "acme-1");
		expect(acme).toMatchObject({ name: "Acme One", reasoning: true, contextWindow: 64000 });
		expect(models.getModel("zai", "glm-5.3-flash")?.contextWindow).toBe(200000);
		const available = await models.getAvailable();
		const ids = available.map((m) => `${m.provider}/${m.id}`);
		expect(ids).toContain("acme/acme-1");
		expect(ids).toContain("viaenv/env-1");
		expect(ids).not.toContain("shelly/shell-1");
		expect(ids).toContain("zai/glm-5.3-flash");
		// The listing carries no credential value.
		expect(JSON.stringify(models.getModels())).not.toContain(KEY_CUSTOM);
	});

	it("survives a broken models.json: the built-in catalogue still loads and the problem is reported", async () => {
		await writeFile(join(dir, "models.json"), "{ nope");
		const built = await buildHostModels({ agentDir: dir, authContext: noAmbient });
		expect(built.models.getModels().length).toBeGreaterThan(100);
		expect(built.problems.join(" ")).toMatch(/models\.json/);
	});

	it("works with no pi directory at all", async () => {
		const built = await buildHostModels({ agentDir: join(dir, "absent"), authContext: noAmbient });
		expect(built.models.getModel("zai", "glm-5.3-flash")).toBeDefined();
		expect(built.problems).toEqual([]);
	});

	// ————— A15.4: the remotely refreshed catalogue overlay —————

	/** A real builtin zai model, cloned as the raw shape models-store.json stores. */
	function zaiFixtureModel(): Record<string, unknown> {
		const base = builtinModels().getModels("zai")[0]!;
		return JSON.parse(JSON.stringify(base)) as Record<string, unknown>;
	}

	async function writeStore(entry: unknown): Promise<string> {
		const path = join(dir, "models-store.json");
		await writeFile(path, JSON.stringify(entry));
		return path;
	}

	it("offers a model that exists only in models-store.json, merged the way pi merges it", async () => {
		const overlay = { ...zaiFixtureModel(), id: "glm-fixture-9", name: "GLM Fixture 9", contextWindow: 111_000 };
		await writeStore({ zai: { models: [overlay], checkedAt: Date.now(), lastModified: Date.now(), etag: 'W/"fixture"' } });
		const { models } = await buildHostModels({ agentDir: dir, authContext: noAmbient });
		const model = models.getModel("zai", "glm-fixture-9");
		expect(model).toMatchObject({ name: "GLM Fixture 9", contextWindow: 111_000, provider: "zai" });
	});

	it("an overlay entry older than the built-in catalogue is ignored (pi's freshness gate)", async () => {
		const overlay = { ...zaiFixtureModel(), id: "glm-fixture-old" };
		await writeStore({ zai: { models: [overlay], checkedAt: Date.now(), lastModified: 1_000, etag: 'W/"fixture"' } });
		const { models } = await buildHostModels({ agentDir: dir, authContext: noAmbient });
		expect(models.getModel("zai", "glm-fixture-old")).toBeUndefined();
	});

	it("an overlay model replaces the built-in model of the same id; models.json overrides still apply on top", async () => {
		const builtinId = String(zaiFixtureModel()["id"]);
		const overlay = { ...zaiFixtureModel(), contextWindow: 123_456 };
		await writeStore({ zai: { models: [overlay], checkedAt: Date.now(), lastModified: Date.now(), etag: 'W/"fixture"' } });
		await writeFile(
			join(dir, "models.json"),
			JSON.stringify({ providers: { zai: { modelOverrides: { [builtinId]: { maxTokens: 7_000 } } } } }),
		);
		await writeAuth({ zai: { type: "api_key", key: "zai-invented-key" } });
		const { models } = await buildHostModels({ agentDir: dir, authContext: noAmbient });
		const model = models.getModel("zai", builtinId);
		expect(model?.contextWindow).toBe(123_456);
		expect(model?.maxTokens).toBe(7_000);
	});

	it("drops stored models of another provider and non-chat types from the chat listing", async () => {
		const foreign = { ...zaiFixtureModel(), id: "foreign-model", provider: "somewhere-else" };
		const classifier = { ...zaiFixtureModel(), id: "fixture-classifier", type: "classifier" };
		await writeStore({ zai: { models: [foreign, classifier], checkedAt: Date.now(), lastModified: Date.now(), etag: 'W/"fixture"' } });
		const { models } = await buildHostModels({ agentDir: dir, authContext: noAmbient });
		expect(models.getModel("zai", "foreign-model")).toBeUndefined();
		expect(models.getModel("zai", "fixture-classifier")).toBeUndefined();
	});

	it("a broken or malformed overlay degrades to the built-in catalogue with a reported problem", async () => {
		await writeFile(join(dir, "models-store.json"), "{ not json");
		const broken = await buildHostModels({ agentDir: dir, authContext: noAmbient });
		expect(broken.models.getModel("zai", "glm-5.3-flash")).toBeDefined();
		expect(broken.problems.join(" ")).toMatch(/models-store\.json/);
		await writeStore({ zai: { models: "not-a-list", checkedAt: Date.now(), lastModified: Date.now() } });
		const malformed = await buildHostModels({ agentDir: dir, authContext: noAmbient });
		expect(malformed.models.getModel("zai", "glm-5.3-flash")).toBeDefined();
		expect(malformed.problems.join(" ")).toMatch(/models-store\.json/);
	});

	it("never writes the overlay and re-reads it when the file changes", async () => {
		const path = await writeStore({
			zai: { models: [{ ...zaiFixtureModel(), id: "glm-fixture-a" }], checkedAt: Date.now(), lastModified: Date.now(), etag: 'W/"a"' },
		});
		const before = await readFile(path);
		const mtimeBefore = (await stat(path)).mtimeMs;
		const { models } = await buildHostModels({ agentDir: dir, authContext: noAmbient });
		expect(models.getModel("zai", "glm-fixture-a")).toBeDefined();
		// The owner's pi CLI refreshes the catalogue: the steward sees the change without a restart.
		await writeFile(path, JSON.stringify({
			zai: { models: [{ ...zaiFixtureModel(), id: "glm-fixture-b" }], checkedAt: Date.now(), lastModified: Date.now(), etag: 'W/"b"' },
		}));
		await utimes(path, new Date(mtimeBefore + 5_000), new Date(mtimeBefore + 5_000));
		expect(models.getModel("zai", "glm-fixture-b")).toBeDefined();
		// Read-only by construction: the file is byte-identical.
		expect((await readFile(path)).equals(before)).toBe(false); // the rewrite is the fixture's own
		const after = await readFile(path);
		models.getModels();
		await new Promise((resolve) => setTimeout(resolve, 10));
		// Read-only by construction: several getModels calls later the file is byte-identical.
		expect((await readFile(path)).equals(after)).toBe(true);
	});
});
