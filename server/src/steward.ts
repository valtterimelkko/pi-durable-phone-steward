/**
 * The steward harness: opens Pi Durable on a SQLite file with the steward extensions
 * installed. One process owns the storage; `harness.resume()` at start-up
 * continues any run the last process left unfinished.
 *
 * The integrations seam (`server/src/integrations.ts`): the caller may pass
 * `integrations` — its extensions install after the core ones, in the given order;
 * `onHarnessOpen` runs after `resume()`; `close()` runs before the storage closes. The
 * default is `noIntegrations`.
 */
import { homedir } from "node:os";
import { join } from "node:path";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import type { Context } from "@earendil-works/chord";
import {
	CompactionTask,
	createRegistry,
	defineExtension,
	hook,
	type HarnessSettings,
	type Registry,
	Harness,
	type Conversation,
	type ConversationId,
} from "@earendil-works/pi-durable";
import { openNodeSqliteStorage } from "@earendil-works/pi-durable/storage/sqlite/node";
import { createModels, type MutableModels } from "@earendil-works/pi-ai/models";
import { InMemoryCredentialStore, type CredentialStore } from "@earendil-works/pi-ai";
import type { FauxProviderHandle } from "@earendil-works/pi-ai/providers/faux";
import { noIntegrations, type StewardIntegrations } from "./integrations.ts";
import { spikePhoneExtension } from "./phone-tool.ts";
import { setActiveIntegrations } from "./steward-events.ts";
import { jobsExtension } from "./jobs.ts";
import { filesShizukuExtension } from "./files-shizuku-tools.ts";
import {
	orchestrationToolsExtension,
	phoneToolsExtension,
	serverToolsExtension,
	webToolsExtension,
	guardExtension,
	stewardSections,
} from "./tools.ts";
import { setOrchestrationReads } from "./orchestration-tools.ts";
import { BatchResultTask, WeeklyCheckTask, PhotoWindowTask } from "./rule-tasks.ts";
import { NotifyTask } from "./notify.ts";
import { liveScreenExtension, stewardHarnessSettings } from "./live/tools.ts";
import { createHarnessReporter, durableReportNotifier } from "./harness-reports.ts";

import { ContextControl, setActiveContextControl } from "./context-control.ts";
import { ModelCatalogue, type ProblemSource } from "./model-catalogue.ts";
import { authMtimeMsOf, liveModelProblemsTx, setActiveModels } from "./model-failure.ts";
import { buildHostModels, type HostModels } from "./pi-registry.ts";
import { installStewardFauxPlan, type FauxPlan } from "./faux-steward.ts";
export type { FauxPlan };

/** The default Pi agent dir the steward reads model registry and auth from. */
const DEFAULT_PI_AGENT_DIR = join(homedir(), ".pi", "agent");

export type OpenStewardOptions = {
	dbPath: string;
	/** TEST-ONLY registry override (the crash tests' spike registry). */
	registry?: Registry;
	/**
	 * Resume interrupted work at start-up (default true). Direct-state CLI readers pass
	 * `resume: false`: the service is stopped, and its pending tasks belong to the service
	 * process, not to a read (correction 01 item 1).
	 */
	resume?: boolean;
	/**
	 * Scripted faux model for tests and disposable instances (no model cost). Omit for a
	 * harness wired for the real provider; the server start-up installs Z.ai from config.
	 */
	fauxPlan?: FauxPlan;
	/** Provider/model the conversation's agent settings start with. */
	model?: { provider: string; modelId: string };
	/** The orchestration integration; default `noIntegrations`. */
	integrations?: StewardIntegrations;
	/**
	 * The model registry and credentials. Production reads Pi's own registry and auth
	 * (`piAgentDir`); tests pass a scratch one. With `fauxPlan`, the faux provider is added to it.
	 */
	hostModels?: HostModels;
	/** The Pi agent dir for the production registry; default `~/.pi/agent`. */
	piAgentDir?: string;
	/** The fraction of the model's window at which background compaction starts (default 0.75). */
	compactAt?: number;
	/** How long a fresh start waits for its pre-compaction. */
	freshCompactionTimeoutMs?: number;
	/** The project memory for the state brief ("" = none). */
	projectMemory?: () => Promise<string>;
	/** The orchestration configuration (and runner, in tests) for the read tools. */
	orchestrationReads?: Parameters<typeof setOrchestrationReads>[0];
};

export type StewardHarness = {
	harness: Harness;
	root: Conversation;
	model: { provider: string; modelId: string };
	/** Present only when a faux provider was installed. */
	faux?: FauxProviderHandle;
	integrations: StewardIntegrations;
	/** The registry the harness runs on and the catalogue built from it. */
	models: MutableModels;
	catalogue: ModelCatalogue;
	/** Compaction policy, manual compaction and fresh starts. */
	context: ContextControl;
	/** The harness settings in force (the compaction policy is a live getter). */
	settings: HarnessSettings;
	/** Closes the integration, then the harness and its storage. */
	close(): Promise<void>;
};

/**
 * The system prompt sections and tools offered to the model, plus the guard. This
 * extension set is what the conversation's agent selects; the tool list is exactly
 * docs/protocol.md §8 (asserted by the safety enumeration test).
 */
export function stewardCoreExtensions() {
	return [stewardSections(), phoneToolsExtension, filesShizukuExtension, serverToolsExtension, webToolsExtension, orchestrationToolsExtension, guardExtension];
}

/** The rule-task bundle, registered by every registry below. */
function ruleTasksExtension() {
	return defineExtension({
		name: "steward-rule-tasks",
		tasks: [WeeklyCheckTask, PhotoWindowTask, BatchResultTask, NotifyTask],
	});
}

/**
 * Threshold compactions are summarised with the steward's own instructions (Pi
 * Durable's passes none). Any failure falls back to Pi Durable's default summary.
 */
function compactionExtension(control: ContextControl) {
	return defineExtension({
		name: "steward-compaction",
		hooks: [
			hook(CompactionTask, {
				beforeCompact: async (compaction) => {
					// A manual compaction carries the instructions already; leave it to the default path.
					if (compaction.instructions !== undefined) return undefined;
					const summary = await control.thresholdSummary(compaction.messages);
					return summary === undefined ? undefined : { summary };
				},
			}),
		],
	});
}

/** The extensions this process installs; a restart must install the same ones again. */
export function stewardRegistry(integrations: StewardIntegrations = noIntegrations, context?: ContextControl): Registry {
	const registry = createRegistry();
	for (const extension of stewardCoreExtensions()) registry.install(extension);
	if (context !== undefined) registry.install(compactionExtension(context));
	registry.install(jobsExtension);
	// The screen tools install here but are left out of the default selection
	// (stewardHarnessSettings); only a live session's conversation selects them.
	registry.install(liveScreenExtension);
	// The integrations' extensions install after the core ones, in the given order.
	for (const extension of integrations.extensions) registry.install(extension);
	registry.install(ruleTasksExtension());
	return registry;
}

/**
 * TEST-ONLY registry for the S1 crash tests: the core tools plus the spike `phone_echo`
 * tool, WITHOUT the guard extension (the spike tool is not on the protocol §8 allowlist).
 * Never used by the server; the safety enumeration test pins the production registry.
 */
export function spikeTestRegistry(): Registry {
	const registry = createRegistry();
	for (const extension of stewardCoreExtensions()) registry.install(extension);
	registry.install(jobsExtension);
	registry.uninstall(guardExtension);
	registry.install(spikePhoneExtension);
	registry.install(ruleTasksExtension());
	return registry;
}

/** A registry with no providers and no credentials: for readers that never call a model (the direct-state CLI). */
export function emptyHostModels(): HostModels {
	const credentials = new InMemoryCredentialStore();
	return { models: createModels({ credentials }), credentials, problems: [] };
}

export async function openStewardHarness(options: OpenStewardOptions): Promise<StewardHarness> {
	const integrations = options.integrations ?? noIntegrations;
	// Pi's registry and auth on a real run; a scratch registry with no credentials
	// for tests (the faux provider is added to whichever is in use).
	let credentials: CredentialStore = options.hostModels?.credentials ?? new InMemoryCredentialStore();
	let models: MutableModels = options.hostModels?.models ?? createModels({ credentials });
	let model: { provider: string; modelId: string };
	let faux: FauxProviderHandle | undefined;
	if (options.fauxPlan !== undefined) {
		faux = installFaux(models, options.fauxPlan);
		model = { provider: "faux", modelId: faux.getModel().id };
	} else {
		model = options.model ?? { provider: "zai", modelId: "glm-5.3-flash" };
		if (options.hostModels === undefined) {
			// A real run reads Pi's own registry and auth. The provider key may also come from
			// an environment variable (bin/steward-serve).
			const host = await buildHostModels({
				agentDir: options.piAgentDir ?? process.env.PHONE_STEWARD_PI_DIR ?? DEFAULT_PI_AGENT_DIR,
			});
			for (const problem of host.problems) console.error(`[steward] pi registry: ${problem}`);
			models = host.models;
			credentials = host.credentials;
		}
	}
	setActiveIntegrations(integrations);
	if (options.orchestrationReads !== undefined) setOrchestrationReads(options.orchestrationReads);
	const control = new ContextControl({
		...(options.compactAt === undefined ? {} : { compactAt: options.compactAt }),
		...(options.freshCompactionTimeoutMs === undefined ? {} : { freshCompactionTimeoutMs: options.freshCompactionTimeoutMs }),
		...(options.projectMemory === undefined ? {} : { projectMemory: options.projectMemory }),
	});
	const registry = options.registry ?? stewardRegistry(integrations, control);
	const storage = await openNodeSqliteStorage(options.dbPath);
	const context: Context = BACKGROUND_CONTEXT;
	// The owner-notification target for harness reports: filled once the root conversation exists,
	// because a report can fire during open (a migration or a resumed task).
	const reportTarget: { current?: { harness: Harness; conversationId: ConversationId } } = {};
	// The catalogue reads the model-problem markers from the durable doc, live while
	// they are within their hour and the auth file has not changed since.
	const agentDir = options.piAgentDir ?? process.env.PHONE_STEWARD_PI_DIR ?? DEFAULT_PI_AGENT_DIR;
	// The compaction policy is a getter: Pi Durable reads it at every resolution, so a model
	// change takes effect on the next turn. Screen tools are filtered out of the default
	// selection so only live sessions select them.
	const settings: HarnessSettings = {
		...stewardHarnessSettings(registry),
		get compaction() {
			return control.policy();
		},
	};
	const reports = createHarnessReporter({
		log: (line) => console.error(line),
		// A failed migration is reported INSIDE Harness.open, before there is a conversation to
		// queue into; that report is held and flushed once the target exists.
		ready: () => reportTarget.current !== undefined,
		notify: durableReportNotifier(() => reportTarget.current, context),
	});
	const harness = await Harness.open(
		storage,
		{
			models,
			registry,
			settings,
			// Pi Durable 1.5.0: a faulted or interrupted task (1.5.0 ends a restart-limited task
			// instead of parking it), a failed migration and a failed Session reach the owner through
			// the durable notify path (one ping per dedupe key, at most once); everything else is
			// logged.
			onReport: reports,
		},
		context,
	);
	// The catalogue reads the model-problem markers from the durable doc, live while
	// they are within their hour and the auth file has not changed since.
	const problems: ProblemSource = () =>
		harness.commit((tx) => liveModelProblemsTx(tx, Date.now(), authMtimeMsOf(agentDir)), context);
	const root = await harness.root(context, { agent: { model } });
	reportTarget.current = { harness, conversationId: root.id };
	// Anything reported during open (a failed migration) is delivered now, through the durable
	// path. Fire-and-forget: open must not wait on a ping.
	void reports.flush();
	// The model the conversation actually has (a persisted choice wins over the default).
	const stored = (await root.agent(context)).model ?? model;
	control.setModel(stored, models.getModel(stored.provider, stored.modelId)?.contextWindow ?? 0);
	control.attach({ harness, root, models });
	setActiveContextControl(control);
	setActiveModels(models);
	if (options.resume !== false) harness.resume();
	if (integrations.onHarnessOpen !== undefined) {
		await integrations.onHarnessOpen(harness, context);
	}
	return {
		harness,
		root,
		model,
		...(faux === undefined ? {} : { faux }),
		integrations,
		models,
		catalogue: new ModelCatalogue(models, credentials, problems),
		context: control,
		settings,
		close: async () => {
			setActiveContextControl(undefined);
			if (integrations.close !== undefined) await integrations.close();
			await harness.close(context);
		},
	};
}

function installFaux(models: MutableModels, plan: FauxPlan) {
	return installStewardFauxPlan(models, plan);
}
