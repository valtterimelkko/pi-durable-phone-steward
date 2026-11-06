/**
 * A10.4: the catalogue the app offers, the billing classification table, and the choice
 * rules (busy, metered, unknown). Invented keys, no network.
 */
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { BILLING_TABLE, billingFor, ModelCatalogue } from "../src/model-catalogue.ts";
import { buildHostModels } from "../src/pi-registry.ts";

let dir: string;
beforeEach(async () => {
	dir = await mkdtemp(join(tmpdir(), "p7-catalogue-"));
});
afterEach(async () => {
	await rm(dir, { recursive: true, force: true });
});

const noAmbient = { env: async () => undefined, fileExists: async () => false };
const SECRET = "sk-secret-invented-9999";

describe("billingFor (the documented classification table)", () => {
	it("classifies subscription providers", () => {
		for (const provider of ["zai", "zai-coding-cn", "openai-codex", "github-copilot", "kimi-coding"]) {
			expect(billingFor(provider)).toBe("subscription");
		}
	});

	it("classifies pay-as-you-go providers as metered", () => {
		for (const provider of ["openrouter", "openai", "google", "deepseek", "mistral", "xai", "groq"]) {
			expect(billingFor(provider)).toBe("metered");
		}
	});

	it("tells an Anthropic subscription login (OAuth) from an Anthropic API key", () => {
		expect(billingFor("anthropic", "oauth")).toBe("subscription");
		expect(billingFor("anthropic", "api_key")).toBe("metered");
		expect(billingFor("anthropic")).toBe("metered");
	});

	it("leaves everything else unknown", () => {
		expect(billingFor("some-new-provider")).toBe("unknown");
		expect(billingFor("clinepass")).toBe("unknown");
	});

	it("keeps the table small and in code (no provider is both)", () => {
		const sub = new Set(BILLING_TABLE.subscription);
		for (const provider of BILLING_TABLE.metered) expect(sub.has(provider)).toBe(false);
	});
});

describe("ModelCatalogue", () => {
	async function catalogue(auth: unknown = { openrouter: { type: "api_key", key: SECRET }, zai: { type: "api_key", key: SECRET } }) {
		await writeFile(join(dir, "auth.json"), JSON.stringify(auth));
		const host = await buildHostModels({ agentDir: dir, authContext: noAmbient });
		return new ModelCatalogue(host.models, host.credentials);
	}

	it("lists every model in the registry with the A10.4 fields", async () => {
		const cat = await catalogue();
		const list = await cat.list({ provider: "zai", id: "glm-5.3-flash" });
		expect(list.current).toEqual({ provider: "zai", id: "glm-5.3-flash" });
		const flash = list.models.find((m) => m.provider === "zai" && m.id === "glm-5.3-flash");
		expect(flash).toEqual({
			provider: "zai",
			id: "glm-5.3-flash",
			label: "GLM-5.3-Flash",
			billing: "subscription",
			available: true,
			reasoning: true,
			contextWindow: 1_000_000,
		});
		expect(list.models.length).toBeGreaterThan(500);
	});

	it("marks a model unavailable when Pi's auth holds no credentials for its provider", async () => {
		const cat = await catalogue();
		const list = await cat.list({ provider: "zai", id: "glm-5.3-flash" });
		const openai = list.models.find((m) => m.provider === "openai");
		expect(openai).toBeDefined();
		expect(openai!.available).toBe(false);
		expect(openai!.billing).toBe("metered");
		const router = list.models.find((m) => m.provider === "openrouter");
		expect(router!.available).toBe(true);
		expect(router!.billing).toBe("metered");
	});

	it("never carries a credential value", async () => {
		const cat = await catalogue();
		const list = await cat.list({ provider: "zai", id: "glm-5.3-flash" });
		expect(JSON.stringify(list)).not.toContain(SECRET);
	});

	it("orders the current model first, then available models, then the rest", async () => {
		const cat = await catalogue();
		const list = await cat.list({ provider: "zai", id: "glm-5.3-flash" });
		expect(list.models[0]).toMatchObject({ provider: "zai", id: "glm-5.3-flash" });
		const firstUnavailable = list.models.findIndex((m) => !m.available);
		const lastAvailable = list.models.findLastIndex((m) => m.available);
		expect(lastAvailable).toBeLessThan(firstUnavailable);
	});

	it("classifies an Anthropic login by the stored credential type", async () => {
		const cat = await catalogue({ anthropic: { type: "oauth", access: "a", refresh: "r", expires: Date.now() + 3_600_000 } });
		const list = await cat.list({ provider: "anthropic", id: "x" });
		const anthropic = list.models.find((m) => m.provider === "anthropic");
		expect(anthropic!.billing).toBe("subscription");
	});

	it("finds a model for a choice, and reports an unknown one as absent", async () => {
		const cat = await catalogue();
		expect(await cat.find({ provider: "openrouter", id: (await cat.list({ provider: "zai", id: "x" })).models.find((m) => m.provider === "openrouter")!.id })).toMatchObject({
			billing: "metered",
			available: true,
		});
		expect(await cat.find({ provider: "nope", id: "nope" })).toBeUndefined();
	});
});
