/**
 * The agent settings routes' logic (protocol §7 as amended by A10.4 and A10.9): model
 * catalogue, the model choice with its busy / unavailable / metered rules, usage per model,
 * and the `context` block. Kept out of `http.ts`, which only registers the routes.
 *
 * The choice lives in the conversation's agent settings (`pi.agent`), so it survives a
 * restart and applies from the next turn: Pi Durable resolves the agent for each request.
 */
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import type { Conversation, Harness } from "@earendil-works/pi-durable";
import type { Models } from "@earendil-works/pi-ai";
import type { ContextControl } from "./context-control.ts";
import { ApiError, badRequest, conflict } from "./errors.ts";
import type { ModelCatalogue, ModelRef } from "./model-catalogue.ts";

export type AgentSettingsDeps = {
	harness: Harness;
	root: Conversation;
	models: Models;
	catalogue: ModelCatalogue;
	context: ContextControl;
	conversationId: string;
	/** The configured default model (used only when the conversation has none stored). */
	defaultModel: { provider: string; modelId: string };
};

async function currentModel(deps: AgentSettingsDeps): Promise<ModelRef> {
	const agent = await deps.root.agent(BACKGROUND_CONTEXT);
	return {
		provider: agent.model?.provider ?? deps.defaultModel.provider,
		id: agent.model?.modelId ?? deps.defaultModel.modelId,
	};
}

/** The conversation's current model, for the A15.3 failure note (provider + id). */
export async function currentModelRef(deps: AgentSettingsDeps): Promise<{ provider: string; id: string }> {
	return currentModel(deps);
}

/** `GET /v1/agent/models`. */
export async function modelsResponse(deps: AgentSettingsDeps, filter: { availableOnly: boolean }): Promise<unknown> {
	const list = await deps.catalogue.list(await currentModel(deps));
	return {
		current: list.current,
		models: filter.availableOnly ? list.models.filter((model) => model.available) : list.models,
	};
}

type UsageBucket = { input?: number; output?: number };

/** Usage per model key `provider/id`, then the current model's tokens in the original shape. */
async function usageResponse(deps: AgentSettingsDeps, current: ModelRef): Promise<{
	usage: { inputTokens: number; outputTokens: number };
	usageByModel: Array<{ provider: string; id: string; inputTokens: number; outputTokens: number }>;
}> {
	const total = await deps.harness.usage(BACKGROUND_CONTEXT);
	const buckets = (total.models ?? {}) as Record<string, UsageBucket>;
	const usageByModel = Object.entries(buckets)
		.map(([key, bucket]) => {
			const slash = key.indexOf("/");
			return {
				provider: slash < 0 ? "" : key.slice(0, slash),
				id: slash < 0 ? key : key.slice(slash + 1),
				inputTokens: bucket.input ?? 0,
				outputTokens: bucket.output ?? 0,
			};
		})
		.sort((a, b) => b.inputTokens + b.outputTokens - (a.inputTokens + a.outputTokens));
	const mine = usageByModel.find((entry) => entry.provider === current.provider && entry.id === current.id);
	return {
		usage: { inputTokens: mine?.inputTokens ?? 0, outputTokens: mine?.outputTokens ?? 0 },
		usageByModel,
	};
}

/** `GET /v1/agent/settings` (A10.4 usage per model, A10.9 context). */
export async function settingsResponse(deps: AgentSettingsDeps): Promise<unknown> {
	const current = await currentModel(deps);
	const usage = await usageResponse(deps, current);
	const status = await deps.context.status();
	return {
		conversationId: deps.conversationId,
		model: current,
		// The MVP's allowlist field, kept for clients that predate the catalogue (A10.4): the
		// current model, with its billing.
		allowlist: [{ provider: current.provider, id: current.id, metered: ((await deps.catalogue.find(current))?.billing ?? "unknown") !== "subscription" }],
		...usage,
		context: status,
	};
}

/**
 * `PUT /v1/agent/settings` model choice. Order of refusals: unknown model (404), a run in
 * progress (409 AGENT_BUSY), no credentials on the server (409 MODEL_UNAVAILABLE),
 * pay-as-you-go or unclassified billing without `confirmMetered` (400 METERED_NEEDS_CONFIRM).
 * `unknown` billing counts as metered: a wrong guess must never cost the owner money.
 */
export async function chooseModel(
	deps: AgentSettingsDeps,
	body: Record<string, unknown>,
): Promise<Record<string, unknown>> {
	const model = body.model;
	if (model === null || typeof model !== "object" || Array.isArray(model)) throw badRequest("model is required: {provider, id}");
	const { provider, id } = model as { provider?: unknown; id?: unknown };
	if (typeof provider !== "string" || provider === "" || typeof id !== "string" || id === "") {
		throw badRequest("model.provider and model.id are required");
	}
	if (body.confirmMetered !== undefined && typeof body.confirmMetered !== "boolean") {
		throw badRequest("confirmMetered must be true or false");
	}
	const confirmed = body.confirmMetered === true;
	const entry = await deps.catalogue.find({ provider, id });
	if (entry === undefined) throw new ApiError(404, "UNKNOWN_MODEL", "no such model in the steward's catalogue");
	if (await deps.context.isBusy()) throw conflict("AGENT_BUSY", "the steward is working; change the model when it is idle");
	if (!entry.available) {
		throw conflict("MODEL_UNAVAILABLE", "this server has no credentials for that provider, so the model cannot be used");
	}
	if (entry.billing !== "subscription" && !confirmed) {
		throw new ApiError(
			400,
			"METERED_NEEDS_CONFIRM",
			entry.billing === "metered"
				? "This model is pay-as-you-go: each message costs money. Send confirmMetered: true to use it."
				: "This model's billing is not known to the steward; it may be pay-as-you-go. Send confirmMetered: true to use it.",
		);
	}
	await deps.root.configure({ model: { provider, modelId: id } }, BACKGROUND_CONTEXT);
	// The compaction policy is measured against the new model's window (A10.9).
	deps.context.setModel({ provider, modelId: id }, entry.contextWindow);
	return { model: { provider, id }, billing: entry.billing, contextWindow: entry.contextWindow };
}
