/**
 * A15.3: model failures are said plainly. Unit tests for the classifier and the copy;
 * the end-to-end behaviour (one assistant line in Chat, the catalogue's `problem` field,
 * auth.json invalidation) is covered in agent-api.test.ts and live-session.test.ts.
 */
import { afterEach, describe, expect, it } from "vitest";
import {
	classifyModelFailure,
	failureLine,
	plainModelFailure,
	configureModelFailure,
	modelFieldFor,
	resetModelFailureConfig,
	setActiveModels,
	type ModelFailureKind,
} from "../src/model-failure.ts";

describe("classifyModelFailure (A15.3)", () => {
	it("calls a provider auth failure what it is", () => {
		// A real Codex failure: the plan fell back to Free and the
		// stored token expired. The text pi-ai surfaces carries the provider's words.
		expect(classifyModelFailure("401: {\"error\":{\"message\":\"Your authentication token has expired\"}}")).toBe("auth");
		expect(classifyModelFailure("Provider (403): invalid_api_key: the key was rejected")).toBe("auth");
		expect(classifyModelFailure("Incorrect API key provided: sk-***. You can find your key at")).toBe("auth");
		expect(classifyModelFailure("OAuth token has expired; sign in again")).toBe("auth");
		expect(classifyModelFailure("unauthorized")).toBe("auth");
	});

	it("calls a plan or quota failure what it is", () => {
		expect(classifyModelFailure("429 FreeUsageLimitError: free plan usage limit reached")).toBe("plan");
		expect(classifyModelFailure("insufficient_quota: You exceeded your current quota, please check your plan and billing details")).toBe("plan");
		expect(classifyModelFailure("Monthly usage limit reached. Enable available balance to continue")).toBe("plan");
		expect(classifyModelFailure("subscription_sharing_usage_limit_exceeded")).toBe("plan");
		expect(classifyModelFailure("You have run out of budget for this model")).toBe("plan");
		expect(classifyModelFailure("quota exceeded for this project")).toBe("plan");
	});

	it("leaves transient and ordinary failures alone (today's behaviour)", () => {
		const transient: string[] = [
			"429 Too Many Requests: rate limit exceeded, retry later",
			"503 service unavailable",
			"fetch failed",
			"socket hang up",
			"getaddrinfo ENOTFOUND api.example.invalid",
			"overloaded_error: the server is busy",
		];
		for (const text of transient) expect(classifyModelFailure(text)).toBeUndefined();
		const ordinary: string[] = [
			"Model returned an unusable response",
			"Context overflow",
			"",
		];
		for (const text of ordinary) expect(classifyModelFailure(text)).toBeUndefined();
	});

	it("prefers the plan reading when a message carries both a status and a limit error", () => {
		expect(classifyModelFailure("429: {\"error\":{\"type\":\"FreeUsageLimitError\"}}")).toBe("plan");
	});
});

describe("the plain line (A15.3)", () => {
	it("names the model label and the reason, and points at Settings", () => {
		expect(failureLine("GLM-5.3 Flash", "auth")).toBe(
			"GLM-5.3 Flash could not run: its sign-in on the server has expired. Pick another model in Settings.",
		);
		expect(failureLine("GPT-6.1 Sol", "plan")).toBe(
			"GPT-6.1 Sol could not run: its plan on the server has no use left for it right now. Pick another model in Settings.",
		);
	});

	it("the plain words carry no provider text, status codes or URLs", () => {
		for (const kind of ["auth", "plan"] as const satisfies readonly ModelFailureKind[]) {
			const words = plainModelFailure(kind);
			expect(words).not.toMatch(/https?:\/\/|\d{3}:|token|api[_ ]?key|stack|Exception/i);
		}
	});
});

// ————— Correction 1: the model field a phone session's `started` event carries —————

describe("modelFieldFor (Correction 1, A15.10/A13.3)", () => {
	afterEach(() => setActiveModels(undefined));

	it("carries the same three keys a steward message carries, with the model's display name", () => {
		setActiveModels({ getModel: (_provider, id) => (id === "model-1" ? { name: "Faux Model" } : undefined) });
		expect(modelFieldFor({ provider: "faux", modelId: "model-1" })).toEqual({ provider: "faux", id: "model-1", label: "Faux Model" });
	});

	it("uses the catalogue's label for an alias id, so the session's first line says what it follows", () => {
		setActiveModels({ getModel: () => ({ name: "Glm Fixture Overlay" }) });
		expect(modelFieldFor({ provider: "zai", modelId: "glm-fixture-overlay-latest" })).toEqual({
			provider: "zai",
			id: "glm-fixture-overlay-latest",
			label: "Glm Fixture Overlay (follows the newest Glm Fixture Overlay)",
		});
	});

	it("degrades to provider/id when the registry does not know the model", () => {
		setActiveModels(undefined);
		expect(modelFieldFor({ provider: "zai", modelId: "gone" })).toEqual({ provider: "zai", id: "gone", label: "zai/gone" });
	});
});

describe("configureModelFailure (test hook)", () => {
	afterEach(() => resetModelFailureConfig());

	it("shrinks the marker's time-to-live for tests", () => {
		expect(configureModelFailure({ ttlMs: 50 }).ttlMs).toBe(50);
		expect(configureModelFailure({}).ttlMs).toBe(50);
		resetModelFailureConfig();
		expect(configureModelFailure({}).ttlMs).toBe(60 * 60_000);
	});
});
