import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import type { Context } from "@earendil-works/chord";
import { type JsonObject, Harness, MemoryStorage, type SessionDocToken, type Tx } from "@earendil-works/pi-durable";
import { createModels, type MutableModels } from "@earendil-works/pi-ai/models";
import { installStewardFauxPlan, type FauxPlan } from "../src/faux-steward.ts";
import type { FauxProviderHandle } from "@earendil-works/pi-ai/providers/faux";
import { openStewardHarness } from "../src/steward.ts";

const context: Context = BACKGROUND_CONTEXT;

/**
 * Test helper: open a steward harness over MemoryStorage (or a file) with the faux model,
 * for unit tests that need real commits and documents but no HTTP server. `commit` and
 * `snapshot` bind the background context so tests stay readable.
 */
export type TestHarness = {
	harness: Harness;
	root: Awaited<ReturnType<Harness["root"]>>;
	faux?: FauxProviderHandle;
	commit<T>(change: (tx: Tx) => T | Promise<T>): Promise<T>;
	snapshot<T extends JsonObject>(token: SessionDocToken<T>): Promise<T | undefined>;
	/** Poll `getTask` until the task reaches a terminal state (waitForTask can resolve early). */
	waitTaskTerminal(taskId: number | import("@earendil-works/pi-durable").TaskId): Promise<"terminal" | "completing">;
	close(): Promise<void>;
};

async function waitTaskTerminalImpl(
	harness: Harness,
	taskId: number | import("@earendil-works/pi-durable").TaskId,
): Promise<"terminal" | "completing"> {
	for (let i = 0; i < 1200; i++) {
		const record = await harness.getTask(taskId as never, context);
		if (!record || record.state.status === "terminal" || record.state.status === "completing") {
			// A retired task reads as undefined: it settled before we looked.
			return record?.state.status === "completing" ? "completing" : "terminal";
		}
		await new Promise((resolve) => setTimeout(resolve, 25));
	}
	throw new Error(`task ${String(taskId)} never reached a terminal state`);
}

async function wrap(
	harness: Harness,
	root: Awaited<ReturnType<Harness["root"]>>,
	faux: FauxProviderHandle | undefined,
	closeImpl: () => Promise<void>,
): Promise<TestHarness> {
	return {
		harness,
		root,
		faux,
		commit: (change) => harness.commit(change, context),
		snapshot: (token) => harness.snapshot(token, context) as Promise<never> as never,
		waitTaskTerminal: (taskId) => waitTaskTerminalImpl(harness, taskId),
		close: closeImpl,
	};
}

export async function openTestHarness(options?: {
	dbPath?: string;
	fauxPlan?: FauxPlan;
}): Promise<TestHarness> {
	if (options?.dbPath !== undefined) {
		const opened = await openStewardHarness({ dbPath: options.dbPath, fauxPlan: options.fauxPlan });
		return wrap(opened.harness, opened.root, opened.faux, () => opened.close());
	}
	const models: MutableModels = createModels();
	const faux = installStewardFauxPlan(models, options?.fauxPlan ?? { hang: false });
	const { stewardRegistry } = await import("../src/steward.ts");
	const registry = stewardRegistry();
	const { stewardHarnessSettings } = await import("../src/live/tools.ts");
	const harness = await Harness.open(new MemoryStorage(), { models, registry, settings: stewardHarnessSettings(registry) }, context);
	const root = await harness.root(context);
	harness.resume();
	return wrap(harness, root, faux, () => harness.close(context));
}
