/**
 * The model catalogue behind `GET /v1/agent/models` (protocol A10.4): every chat model in
 * the Pi registry on this host, whether Pi's auth has credentials for its provider, and how
 * it is billed.
 *
 * Billing classification (documented here, in code, as the brief requires). It is by
 * provider, because that is what the owner's plans are tied to, with one refinement:
 * - `subscription`: a flat-rate plan the owner already pays for. zai (Z.AI coding plan),
 *   zai-coding-cn, openai-codex (ChatGPT plan), github-copilot, kimi-coding, and Anthropic
 *   when the stored login is an OAuth subscription login.
 * - `metered`: pay-as-you-go per token. openrouter, openai (API), google (API), deepseek
 *   (API), mistral, xai, groq, together, fireworks, cerebras, and Anthropic with an API key.
 * - `unknown`: anything else, including custom `models.json` providers. The app treats
 *   unknown like metered: it asks once before switching.
 * The table is small on purpose. A wrong `unknown` costs one extra confirmation; a wrong
 * `subscription` could cost money, so a provider is only listed there when it is certain.
 */
import type { CredentialStore, Models } from "@earendil-works/pi-ai";

export type Billing = "subscription" | "metered" | "unknown";

export const BILLING_TABLE: { subscription: readonly string[]; metered: readonly string[] } = {
	subscription: ["zai", "zai-coding-cn", "openai-codex", "github-copilot", "kimi-coding"],
	metered: ["openrouter", "openai", "google", "google-vertex", "deepseek", "mistral", "xai", "groq", "together", "fireworks", "cerebras"],
};

/** Anthropic is a subscription with an OAuth login and metered with an API key. */
export function billingFor(provider: string, credentialType?: "api_key" | "oauth"): Billing {
	if (provider === "anthropic") return credentialType === "oauth" ? "subscription" : "metered";
	if (BILLING_TABLE.subscription.includes(provider)) return "subscription";
	if (BILLING_TABLE.metered.includes(provider)) return "metered";
	return "unknown";
}

export type ModelRef = { provider: string; id: string };

export type CatalogueModel = {
	provider: string;
	id: string;
	label: string;
	billing: Billing;
	available: boolean;
	reasoning: boolean;
	contextWindow: number;
	/** A15.3: the plain reason a recent turn on this model failed, while the marker is live. */
	problem?: string;
	/** A15.10: true for an alias id (starts with `~` or ends in `-latest`); the label says what it follows. */
	alias?: true;
};

/**
 * A15.10: the model an alias id follows, humanised ("~anthropic/claude-haiku-latest" →
 * "Claude Haiku"), or undefined for a concrete model id.
 */
export function aliasFolloweeOf(id: string): string | undefined {
	if (!(id.startsWith("~") || id.endsWith("-latest"))) return undefined;
	let base = id.endsWith("-latest") ? id.slice(0, -"-latest".length) : id;
	if (base.startsWith("~")) base = base.slice(1);
	base = base.split("/").pop() ?? base;
	return base
		.split("-")
		.filter((word) => word !== "")
		.map((word) => word[0]!.toUpperCase() + word.slice(1))
		.join(" ");
}

/**
 * The catalogue's label for a model: its display name, and for an alias id what it follows
 * ("Glm Flash (follows the newest Glm)"). Correction 1 (A15.10/A13.3) reuses this for the
 * model field a phone session's `started` event carries, so the session's first line in
 * Chat reads exactly as the picker does.
 */
export function catalogueLabelOf(name: string, id: string): string {
	const followee = aliasFolloweeOf(id);
	return followee === undefined ? name : `${name} (follows the newest ${followee})`;
}

/** The live `problem` markers, keyed `provider/id` (A15.3); wired to the markers doc. */
export type ProblemSource = () => Promise<Map<string, string>>;

export class ModelCatalogue {
	readonly #models: Models;
	readonly #credentials: CredentialStore;
	readonly #problems: ProblemSource;

	constructor(models: Models, credentials: CredentialStore, problems: ProblemSource = () => Promise.resolve(new Map())) {
		this.#models = models;
		this.#credentials = credentials;
		this.#problems = problems;
	}

	async #anthropicType(): Promise<"api_key" | "oauth" | undefined> {
		return (await this.#credentials.read("anthropic"))?.type;
	}

	async #availableSet(): Promise<Set<string>> {
		const available = await this.#models.getAvailable().catch(() => []);
		return new Set(available.map((model) => `${model.provider}/${model.id}`));
	}

	async #problemSet(): Promise<Map<string, string>> {
		return this.#problems().catch(() => new Map());
	}

	/** The catalogue's view of one registry model, with the A15.3 problem and A15.10 alias fields. */
	#view(model: { provider: string; id: string; name: string; reasoning: boolean; contextWindow: number }, available: Set<string>, problems: Map<string, string>, anthropicType: "api_key" | "oauth" | undefined): CatalogueModel {
		const problem = problems.get(`${model.provider}/${model.id}`);
		const followee = aliasFolloweeOf(model.id);
		return {
			provider: model.provider,
			id: model.id,
			label: catalogueLabelOf(model.name, model.id),
			billing: billingFor(model.provider, model.provider === "anthropic" ? anthropicType : undefined),
			available: available.has(`${model.provider}/${model.id}`),
			reasoning: model.reasoning,
			contextWindow: model.contextWindow,
			...(problem === undefined ? {} : { problem }),
			...(followee === undefined ? {} : { alias: true as const }),
		};
	}

	/** Every chat model: the current one first, then the available ones, then the rest. */
	async list(current: ModelRef): Promise<{ current: ModelRef; models: CatalogueModel[] }> {
		const available = await this.#availableSet();
		const problems = await this.#problemSet();
		const anthropicType = await this.#anthropicType();
		const models: CatalogueModel[] = this.#models.getModels().map((model) => this.#view(model, available, problems, anthropicType));
		const rank = (m: CatalogueModel): number => (m.provider === current.provider && m.id === current.id ? 0 : m.available ? 1 : 2);
		models.sort(
			(a, b) => rank(a) - rank(b) || a.provider.localeCompare(b.provider) || a.label.localeCompare(b.label),
		);
		return { current, models };
	}

	async find(ref: ModelRef): Promise<CatalogueModel | undefined> {
		const model = this.#models.getModel(ref.provider, ref.id);
		if (model === undefined) return undefined;
		const available = await this.#availableSet();
		const problems = await this.#problemSet();
		return this.#view(model, available, problems, await this.#anthropicType());
	}
}
