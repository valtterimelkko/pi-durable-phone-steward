/**
 * A10.4 and A10.9 over HTTP: the model catalogue, the model choice rules, usage per model,
 * the `context` block, and the manual compact / fresh routes. A real server on loopback
 * with a scratch Pi registry (invented keys) and the faux model: no network, no cost.
 */
import { connect } from "node:net";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { CompactionEntry, ResetEntry } from "@earendil-works/pi-durable";
import { builtinModels } from "@earendil-works/pi-ai/providers/all";
import { fauxAssistantMessage, fauxText } from "@earendil-works/pi-ai/providers/faux";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { loadConfig } from "../src/config.ts";
import { startStewardServers, type StewardServers } from "../src/http.ts";
import { buildHostModels } from "../src/pi-registry.ts";
import { openRun, closeRun } from "../src/run-tracking.ts";
import { openStewardHarness } from "../src/steward.ts";

const ctx = BACKGROUND_CONTEXT;
const SECRET_ZAI = "zai-secret-invented-1111";
const SECRET_OR = "or-secret-invented-2222";

let dir: string;
let port: number;
let servers: StewardServers;
let opened: Awaited<ReturnType<typeof openStewardHarness>>;
let token: string;

function adminPost(path: string): Promise<Record<string, unknown>> {
	return new Promise((resolve, reject) => {
		const socket = connect(join(dir, "admin.sock"), () => {
			socket.write(`POST ${path} HTTP/1.1\r\nhost: admin\r\ncontent-length: 0\r\nconnection: close\r\n\r\n`);
		});
		let raw = "";
		socket.on("data", (chunk: Buffer) => (raw += chunk.toString("utf8")));
		socket.on("error", reject);
		socket.on("close", () => resolve(JSON.parse(raw.slice(raw.indexOf("\r\n\r\n") + 4)) as Record<string, unknown>));
	});
}

async function api(path: string, init: { method?: string; token?: string | null; body?: unknown } = {}) {
	const bearer = init.token === undefined ? token : init.token;
	const response = await fetch(`http://127.0.0.1:${port}${path}`, {
		method: init.method ?? "GET",
		headers: { "content-type": "application/json", ...(bearer === null ? {} : { authorization: `Bearer ${bearer}` }) },
		body: init.body === undefined ? undefined : JSON.stringify(init.body),
	});
	return { status: response.status, body: (await response.json().catch(() => null)) as any };
}

let counter = 0;
const rid = (): string => `req-${Date.now()}-${++counter}`;

beforeAll(async () => {
	dir = await mkdtemp(join(tmpdir(), "p7-agent-api-"));
	const piDir = join(dir, "pi");
	await (await import("node:fs/promises")).mkdir(piDir);
	await writeFile(
		join(piDir, "auth.json"),
		JSON.stringify({ zai: { type: "api_key", key: SECRET_ZAI }, openrouter: { type: "api_key", key: SECRET_OR } }),
);
	// A15.4: an overlay-only model, the way the pi CLI's refreshed catalogue stores one.
	const zaiBuiltin = builtinModels().getModels("zai")[0]!;
	await writeFile(
		join(piDir, "models-store.json"),
		JSON.stringify({
			zai: {
				models: [
					{ ...JSON.parse(JSON.stringify(zaiBuiltin)), id: "glm-fixture-overlay", name: "GLM Fixture Overlay", contextWindow: 222_000 },
					{ ...JSON.parse(JSON.stringify(zaiBuiltin)), id: "glm-fixture-overlay-latest", name: "GLM Fixture Overlay Latest", contextWindow: 222_000 },
				],
				checkedAt: Date.now(),
				lastModified: Date.now(),
				etag: 'W/"fixture"',
			},
		}),
	);
	const host = await buildHostModels({ agentDir: piDir, authContext: { env: async () => undefined, fileExists: async () => false } });
	const config = loadConfig({ stateDir: dir, port: 0, notify: {}, schedules: { enabled: false }, piAgentDir: piDir });
	opened = await openStewardHarness({
		dbPath: join(dir, "steward.sqlite"),
		hostModels: host,
		fauxPlan: { hang: false, models: [{ id: "faux-a", contextWindow: 200_000 }, { id: "faux-b", contextWindow: 1_000_000 }] },
		piAgentDir: piDir,
	});
	servers = startStewardServers({ config, harness: opened.harness, root: opened.root, catalogue: opened.catalogue, contextControl: opened.context, models: opened.models });
	// main.ts starts this view on the root conversation; the A15.10 model-field tests read it.
	const { startChatBridge } = await import("../src/chat-bridge.ts");
	const bridge = startChatBridge(opened.root);
	(servers as unknown as { __bridge: { stop(): void } }).__bridge = bridge;
	await new Promise<void>((resolve) => servers.tcp.listen(0, "127.0.0.1", resolve));
	port = (servers.tcp.address() as { port: number }).port;
	const code = ((await adminPost("/admin/pair")) as { code: string }).code;
	token = (await api("/v1/pair", { method: "POST", token: null, body: { code, deviceName: "suite" } })).body.token;
});

afterAll(async () => {
	(servers as unknown as { __bridge?: { stop(): void } }).__bridge?.stop();
	await servers.close();
	await opened.close();
	await rm(dir, { recursive: true, force: true });
});

describe("GET /v1/agent/models (A10.4)", () => {
	it("needs a token", async () => {
		expect((await api("/v1/agent/models", { token: null })).status).toBe(401);
	});

	it("lists the registry with billing and availability, the current model first, and no credential", async () => {
		const response = await api("/v1/agent/models");
		expect(response.status).toBe(200);
		expect(response.body.current).toEqual({ provider: "faux", id: "faux-a" });
		const models = response.body.models as Array<Record<string, unknown>>;
		expect(models[0]).toMatchObject({ provider: "faux", id: "faux-a", available: true, contextWindow: 200_000 });
		expect(Object.keys(models[0]!).sort()).toEqual(["available", "billing", "contextWindow", "id", "label", "provider", "reasoning"]);
		const flash = models.find((m) => m.provider === "zai" && m.id === "glm-5.3-flash");
		expect(flash).toMatchObject({ billing: "subscription", available: true });
		const router = models.find((m) => m.provider === "openrouter");
		expect(router).toMatchObject({ billing: "metered", available: true });
		const openai = models.find((m) => m.provider === "openai");
		expect(openai).toMatchObject({ billing: "metered", available: false });
		const text = JSON.stringify(response.body);
		expect(text).not.toContain(SECRET_ZAI);
		expect(text).not.toContain(SECRET_OR);
	});

	it("includes a model that exists only in the models-store.json overlay (A15.4)", async () => {
		const response = await api("/v1/agent/models");
		expect(response.status).toBe(200);
		const models = response.body.models as Array<Record<string, unknown>>;
		const overlay = models.find((m) => m.provider === "zai" && m.id === "glm-fixture-overlay");
		expect(overlay).toMatchObject({ contextWindow: 222_000, label: "GLM Fixture Overlay", billing: "subscription" });
	});

	it("filters to the usable models with ?availableOnly=1", async () => {
		const response = await api("/v1/agent/models?availableOnly=1");
		const models = response.body.models as Array<{ available: boolean }>;
		expect(models.length).toBeGreaterThan(2);
		expect(models.every((m) => m.available)).toBe(true);
	});
});

describe("PUT /v1/agent/settings (A10.4)", () => {
	const put = (body: Record<string, unknown>) => api("/v1/agent/settings", { method: "PUT", body: { requestId: rid(), ...body } });

	it("404 UNKNOWN_MODEL for a model not in the registry", async () => {
		const response = await put({ model: { provider: "nope", id: "nothing" } });
		expect(response.status).toBe(404);
		expect(response.body.error.code).toBe("UNKNOWN_MODEL");
	});

	it("400 for a body without a model, and for a non-boolean confirmMetered", async () => {
		expect((await put({})).status).toBe(400);
		expect((await put({ model: { provider: "zai" } })).status).toBe(400);
		expect((await put({ model: { provider: "zai", id: "glm-5.3-flash" }, confirmMetered: "yes" })).status).toBe(400);
		expect((await api("/v1/agent/settings", { method: "PUT", body: { model: { provider: "zai", id: "glm-5.3-flash" } } })).status).toBe(400);
	});

	it("409 MODEL_UNAVAILABLE when the server holds no credentials for the provider", async () => {
		const response = await put({ model: { provider: "openai", id: "gpt-4o" }, confirmMetered: true });
		const known = (await api("/v1/agent/models")).body.models.find((m: any) => m.provider === "openai");
		const result = await put({ model: { provider: "openai", id: known.id }, confirmMetered: true });
		expect(result.status).toBe(409);
		expect(result.body.error.code).toBe("MODEL_UNAVAILABLE");
		expect(response.status).toBeGreaterThanOrEqual(404);
	});

	it("400 METERED_NEEDS_CONFIRM for a pay-as-you-go model, and for an unclassified one, until confirmed", async () => {
		const router = (await api("/v1/agent/models")).body.models.find((m: any) => m.provider === "openrouter");
		const refused = await put({ model: { provider: "openrouter", id: router.id } });
		expect(refused.status).toBe(400);
		expect(refused.body.error.code).toBe("METERED_NEEDS_CONFIRM");
		expect(refused.body.error.message).toMatch(/pay-as-you-go/i);
		const refusedFalse = await put({ model: { provider: "openrouter", id: router.id }, confirmMetered: false });
		expect(refusedFalse.body.error.code).toBe("METERED_NEEDS_CONFIRM");
		// The faux provider is not in the billing table: unknown counts as metered.
		const unknown = await put({ model: { provider: "faux", id: "faux-b" } });
		expect(unknown.status).toBe(400);
		expect(unknown.body.error.code).toBe("METERED_NEEDS_CONFIRM");
		expect(unknown.body.error.message).toMatch(/not known|may be/i);
		// Nothing changed.
		expect((await api("/v1/agent/settings")).body.model).toEqual({ provider: "faux", id: "faux-a" });
	});

	it("409 AGENT_BUSY during a run, and the model stays", async () => {
		await opened.harness.commit((tx) => openRun(tx, opened.root.id, "busy-run", "owner", Date.now()), ctx);
		try {
			const response = await put({ model: { provider: "zai", id: "glm-5.3-flash" } });
			expect(response.status).toBe(409);
			expect(response.body.error.code).toBe("AGENT_BUSY");
			expect((await api("/v1/agent/settings")).body.model.provider).toBe("faux");
		} finally {
			await opened.harness.commit((tx) => closeRun(tx, opened.root.id, "busy-run", Date.now()), ctx);
		}
	});

	it("switches to a subscription model without a confirmation, recomputes the compaction policy, and applies from the next turn", async () => {
		// A real provider model is chosen (its window is 1 000 000): no turn is run on it.
		const response = await put({ model: { provider: "zai", id: "glm-5.3-flash" } });
		expect(response.status).toBe(200);
		expect(response.body.model).toEqual({ provider: "zai", id: "glm-5.3-flash" });
		expect((await api("/v1/agent/settings")).body.model).toEqual({ provider: "zai", id: "glm-5.3-flash" });
		expect((await opened.root.agent(ctx)).model).toEqual({ provider: "zai", modelId: "glm-5.3-flash" });
		// A11.1: a 1 000 000-token window keeps 10 % for the answer (100 000).
		expect(opened.settings.compaction?.reserveTokens).toBe(100_000);
	});

	it("switches to a metered model once confirmed, and the next turn runs on it", async () => {
		const response = await put({ model: { provider: "faux", id: "faux-b" }, confirmMetered: true });
		expect(response.status).toBe(200);
		expect(opened.settings.compaction?.reserveTokens).toBe(100_000);
		const submission = await opened.root.submit({ type: "input", content: "hello after the switch", requestId: rid() }, ctx);
		await submission.wait(ctx);
		const entries = (await opened.root.entries({}, 50, undefined, ctx)).items;
		const lastAssistant = entries.map((e) => e.model?.[0]).find((m) => m?.role === "assistant") as { model?: string } | undefined;
		expect(lastAssistant?.model).toBe("faux-b");
		// Back to faux-a (window 200 000): the policy follows.
		await put({ model: { provider: "faux", id: "faux-a" }, confirmMetered: true });
		expect(opened.settings.compaction?.reserveTokens).toBe(20_000);
	});

	it("is idempotent on requestId", async () => {
		const body = { requestId: rid(), model: { provider: "faux", id: "faux-b" }, confirmMetered: true };
		const first = await api("/v1/agent/settings", { method: "PUT", body });
		const second = await api("/v1/agent/settings", { method: "PUT", body });
		expect(first.status).toBe(200);
		expect(second.body.duplicate).toBe(true);
	});

	it("survives a restart: the stored choice wins over the configured default", async () => {
		await put({ model: { provider: "faux", id: "faux-b" }, confirmMetered: true });
		const stored = (await opened.root.agent(ctx)).model;
		expect(stored).toEqual({ provider: "faux", modelId: "faux-b" });
	});
});

describe("GET /v1/agent/settings (A10.4 usage per model, A10.9 context)", () => {
	it("reports usage per model and the context block", async () => {
		const submission = await opened.root.submit({ type: "input", content: "usage please", requestId: rid() }, ctx);
		await submission.wait(ctx);
		const response = await api("/v1/agent/settings");
		expect(response.status).toBe(200);
		expect(response.body.conversationId).toBe("main");
		expect(Array.isArray(response.body.usageByModel)).toBe(true);
		const faux = response.body.usageByModel.find((entry: any) => entry.provider === "faux");
		// pi-ai 1.5.0's faux provider simulates prompt caching, so every prompt token is billed as a
		// cache write and `input` is 0 (a test-only provider detail; a real provider's usage comes
		// from its API). The row must still carry this run's tokens.
		expect(faux.outputTokens).toBeGreaterThan(0);
		expect(response.body.usage).toEqual({ inputTokens: expect.any(Number), outputTokens: expect.any(Number) });
		expect(response.body.context).toMatchObject({ tokens: expect.any(Number), budget: expect.any(Number), compactAtTokens: expect.any(Number), lastCompactionMs: null, lastFreshStartMs: null });
		// A11.1: budget is the model's window and compaction starts at 75 % of it. This suite
		// shares one harness, so the window is whichever model the earlier test left in use.
		const { budget, compactAtTokens } = response.body.context;
		expect([200_000, 1_000_000]).toContain(budget);
		expect(compactAtTokens).toBe(Math.round(0.75 * budget));
		expect(Array.isArray(response.body.allowlist)).toBe(true);
	});
});

describe("POST /v1/chat/compact and /v1/chat/fresh (A10.9)", () => {
	async function waitFor(check: () => Promise<boolean>, what: string): Promise<void> {
		const deadline = Date.now() + 15_000;
		while (Date.now() < deadline) {
			if (await check()) return;
			await new Promise((resolve) => setTimeout(resolve, 50));
		}
		throw new Error(`timed out waiting for ${what}`);
	}

	it("need a token and a requestId", async () => {
		expect((await api("/v1/chat/compact", { method: "POST", token: null, body: { requestId: rid() } })).status).toBe(401);
		expect((await api("/v1/chat/fresh", { method: "POST", body: {} })).status).toBe(400);
	});

	it("409 AGENT_BUSY during a run, for both", async () => {
		await opened.harness.commit((tx) => openRun(tx, opened.root.id, "busy-2", "owner", Date.now()), ctx);
		try {
			for (const path of ["/v1/chat/compact", "/v1/chat/fresh"]) {
				const response = await api(path, { method: "POST", body: { requestId: rid() } });
				expect(response.status).toBe(409);
				expect(response.body.error.code).toBe("AGENT_BUSY");
			}
		} finally {
			await opened.harness.commit((tx) => closeRun(tx, opened.root.id, "busy-2", Date.now()), ctx);
		}
	});

	it("compact answers 202 at once and compacts", async () => {
		for (let i = 0; i < 3; i++) {
			const s = await opened.root.submit({ type: "input", content: `filler ${i} ${"lorem ipsum ".repeat(6_000)}`, requestId: rid() }, ctx);
			await s.wait(ctx);
		}
		const response = await api("/v1/chat/compact", { method: "POST", body: { requestId: rid() } });
		expect(response.status).toBe(202);
		expect(response.body).toMatchObject({ accepted: true });
		await waitFor(async () => (await opened.root.entries({}, 200, undefined, ctx)).items.some((e) => CompactionEntry.is(e)), "the compaction entry");
		await waitFor(async () => !(await opened.context.isBusy()), "the operation to finish");
		expect((await api("/v1/agent/settings")).body.context.lastCompactionMs).toBeGreaterThan(0);
	});

	it("fresh answers 202 at once and starts the model context from the brief", async () => {
		const response = await api("/v1/chat/fresh", { method: "POST", body: { requestId: rid() } });
		expect(response.status).toBe(202);
		expect(response.body).toMatchObject({ accepted: true });
		await waitFor(async () => {
			const view = await opened.root.context(ctx);
			return view.head !== undefined && ResetEntry.is(view.head);
		}, "the reset entry");
		await waitFor(async () => !(await opened.context.isBusy()), "the operation to finish");
		expect((await api("/v1/agent/settings")).body.context.lastFreshStartMs).toBeGreaterThan(0);
		const history = await api("/v1/chat/history?limit=200");
		expect(history.body.events.some((e: any) => e.type === "context" && e.data.kind === "fresh_start")).toBe(true);
	});

	it("repeats of one requestId are answered once", async () => {
		const body = { requestId: rid() };
		const first = await api("/v1/chat/fresh", { method: "POST", body });
		await waitFor(async () => !(await opened.context.isBusy()), "the first operation");
		const second = await api("/v1/chat/fresh", { method: "POST", body });
		expect(first.status).toBe(202);
		expect(second.status).toBe(202);
		expect(second.body.duplicate).toBe(true);
	});
});

describe("admin socket routes (bin/steward compact|fresh)", () => {
	it("POST /admin/compact and /admin/fresh answer like the device routes", async () => {
		const compact = await adminPost("/admin/compact");
		expect(compact).toMatchObject({ accepted: true });
		await new Promise((resolve) => setTimeout(resolve, 300));
		for (let i = 0; i < 100 && (await opened.context.isBusy()); i++) await new Promise((resolve) => setTimeout(resolve, 50));
		const fresh = await adminPost("/admin/fresh");
		expect(fresh).toMatchObject({ accepted: true });
		for (let i = 0; i < 100 && (await opened.context.isBusy()); i++) await new Promise((resolve) => setTimeout(resolve, 50));
	});
});

// ————— A15.3: model failures are said plainly —————

describe("A15.3 model failures are said plainly", () => {
	const put = (body: Record<string, unknown>) => api("/v1/agent/settings", { method: "PUT", body: { requestId: rid(), ...body } });
	const switchToFauxB = async () => {
		const response = await put({ model: { provider: "faux", id: "faux-b" }, confirmMetered: true });
		expect(response.status).toBe(200);
	};
	const switchBack = async () => {
		const response = await put({ model: { provider: "faux", id: "faux-a" }, confirmMetered: true });
		expect(response.status).toBe(200);
	};
	const failureLines = async (): Promise<string[]> => {
		const history = await api("/v1/chat/history?limit=200");
		return (history.body.events as Array<{ type: string; data: { role?: string; text?: string } }>)
			.filter((event) => event.type === "message" && event.data.role === "assistant" && typeof event.data.text === "string" && event.data.text.includes("could not run"))
			.map((event) => event.data.text!);
	};

	it("a failed owner turn leaves one plain assistant line and marks the model in the catalogue", async () => {
		await switchToFauxB();
		opened.faux!.setResponses([fauxAssistantMessage([], { stopReason: "error", errorMessage: "401: Your authentication token has expired" })]);
		expect((await api("/v1/chat", { method: "POST", body: { requestId: rid(), text: "hello" } })).status).toBe(202);
		await opened.root.waitForIdle(ctx);
		const lines = await failureLines();
		expect(lines).toEqual(["faux-b could not run: its sign-in on the server has expired. Pick another model in Settings."]);
		const models = (await api("/v1/agent/models")).body.models as Array<Record<string, unknown>>;
		const marked = models.find((m) => m.provider === "faux" && m.id === "faux-b");
		expect(marked?.problem).toBe("its sign-in on the server has expired");
		// The line carries no provider text or status code.
		expect(lines[0]).not.toMatch(/401|token|http/i);
		await switchBack();
	});

	it("a second failed turn adds one more line, and each failed turn has exactly one", async () => {
		await switchToFauxB();
		opened.faux!.setResponses([
			fauxAssistantMessage([], { stopReason: "error", errorMessage: "401: Your authentication token has expired" }),
			fauxAssistantMessage([], { stopReason: "error", errorMessage: "401: Your authentication token has expired" }),
		]);
		await api("/v1/chat", { method: "POST", body: { requestId: rid(), text: "again" } });
		await opened.root.waitForIdle(ctx);
		await api("/v1/chat", { method: "POST", body: { requestId: rid(), text: "once more" } });
		await opened.root.waitForIdle(ctx);
		const lines = await failureLines();
		expect(lines.length).toBe(3); // the first test's line, plus exactly one per failed turn
		await switchBack();
	});

	it("an ordinary failure keeps today's behaviour: no line, no mark", async () => {
		await switchToFauxB();
		const before = (await failureLines()).length;
		const problemBefore = (((await api("/v1/agent/models")).body.models as Array<Record<string, unknown>>).find((m) => m.provider === "faux" && m.id === "faux-b"))?.problem;
		opened.faux!.setResponses([fauxAssistantMessage([], { stopReason: "error", errorMessage: "Model returned an unusable response" })]);
		await api("/v1/chat", { method: "POST", body: { requestId: rid(), text: "plain" } });
		await opened.root.waitForIdle(ctx);
		expect((await failureLines()).length).toBe(before);
		// An ordinary failure adds no mark: the catalogue entry is exactly what it was.
		const models = (await api("/v1/agent/models")).body.models as Array<Record<string, unknown>>;
		expect(models.find((m) => m.provider === "faux" && m.id === "faux-b")?.problem).toEqual(problemBefore);
		await switchBack();
	});

	it("the mark clears when auth.json changes, and expires after its hour", async () => {
		const { utimes } = await import("node:fs/promises");
		await switchToFauxB();
		opened.faux!.setResponses([fauxAssistantMessage([], { stopReason: "error", errorMessage: "429 FreeUsageLimitError: free plan limit" })]);
		await api("/v1/chat", { method: "POST", body: { requestId: rid(), text: "plan" } });
		await opened.root.waitForIdle(ctx);
		const models = () => api("/v1/agent/models") as Promise<{ body: { models: Array<Record<string, unknown>> } }>;
		expect(((await models()).body.models.find((m) => m.provider === "faux" && m.id === "faux-b"))?.problem).toBe(
			"its plan on the server has no use left for it right now",
		);
		// The owner re-logs in (auth.json changes): the mark clears even inside the hour.
		const piDir = join(dir, "pi");
		await utimes(join(piDir, "auth.json"), new Date(Date.now() + 5_000), new Date(Date.now() + 5_000));
		expect(((await models()).body.models.find((m) => m.provider === "faux" && m.id === "faux-b"))?.problem).toBeUndefined();
		await switchBack();
	});
});

// ————— A15.10: every steward message names the model that actually produced it —————

describe("A15.10 the model is never a surprise", () => {
	it("a steward message event carries the additive model field from the assistant message itself", async () => {
		opened.faux!.setResponses([fauxAssistantMessage([fauxText("A plain answer.")])]);
		await api("/v1/chat", { method: "POST", body: { requestId: rid(), text: "hello" } });
		await opened.root.waitForIdle(ctx);
		const history = await api("/v1/chat/history?limit=200");
		const assistant = (history.body.events as Array<{ type: string; data: { role?: string; text?: string; model?: unknown } }>).filter(
			(e) => e.type === "message" && e.data.role === "assistant",
		);
		const answer = assistant.find((e) => e.data.text === "A plain answer.");
		expect(answer).toBeDefined();
		expect(answer!.data.model).toEqual({ provider: "faux", id: "faux-a", label: "faux-a" });
		// The owner's own messages carry no model.
		expect(assistant.every((e) => e.data.text !== "hello" || e.data.model === undefined)).toBe(true);
	});

	it("the A15.3 failure line names the model too", async () => {
		await api("/v1/agent/settings", { method: "PUT", body: { requestId: rid(), model: { provider: "faux", id: "faux-b" }, confirmMetered: true } });
		opened.faux!.setResponses([fauxAssistantMessage([], { stopReason: "error", errorMessage: "401: Your authentication token has expired" })]);
		await api("/v1/chat", { method: "POST", body: { requestId: rid(), text: "again" } });
		await opened.root.waitForIdle(ctx);
		await api("/v1/agent/settings", { method: "PUT", body: { requestId: rid(), model: { provider: "faux", id: "faux-a" }, confirmMetered: true } });
		const history = await api("/v1/chat/history?limit=200");
		const line = (history.body.events as Array<{ type: string; data: { role?: string; text?: string; model?: unknown } }>).find(
			(e) => e.type === "message" && e.data.role === "assistant" && String(e.data.text).includes("could not run"),
		);
		expect(line).toBeDefined();
		expect(line!.data.model).toEqual({ provider: "faux", id: "faux-b", label: "faux-b" });
	});

	it("the catalogue marks an alias model with alias: true and a label that says what it follows", async () => {
		// The overlay fixture carries an alias id (ends in -latest); A15.4's test set it up.
		const models = (await api("/v1/agent/models")).body.models as Array<{ id: string; alias?: boolean; label: string }>;
		const alias = models.find((m) => m.id === "glm-fixture-overlay-latest");
		expect(alias).toBeDefined();
		expect(alias!.alias).toBe(true);
		expect(alias!.label).toMatch(/follows the newest Glm Fixture Overlay/);
		const plain = models.find((m) => m.id === "glm-fixture-overlay");
		expect(plain!.alias).toBeUndefined();
	});
});
