/**
 * The tests' minimal steward harness (brief: "Tests build their own minimal harness with
 * a faux model. They install the core's existing StewardPhone, plus your extensions,
 * through createRegistry"). Mirrors `openStewardHarness` but takes the extensions the
 * caller passes, so integration wiring is exercised exactly as the real
 * start-up would wire it: core extensions first, then the integration's.
 */
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import {
	createRegistry,
	Harness,
	type Extension,
	type Registry,
} from "@earendil-works/pi-durable";
import { openNodeSqliteStorage } from "@earendil-works/pi-durable/storage/sqlite/node";
import { createModels } from "@earendil-works/pi-ai/models";
import type { FauxProviderHandle } from "@earendil-works/pi-ai/providers/faux";
import { installStewardFauxPlan, type FauxPlan } from "../../src/faux-steward.ts";
import { spikePhoneExtension as StewardPhone } from "../../src/phone-tool.ts";
import { PhotoWindowTask } from "../../src/photo-window.ts";

export type TestSteward = {
	harness: Harness;
	root: Awaited<ReturnType<Harness["root"]>>;
	model: { provider: string; modelId: string };
	faux?: FauxProviderHandle;
};

export function testRegistry(extensions: readonly Extension[]): Registry {
	const registry = createRegistry();
	// The core's own extensions, in the core's order.
	registry.install(StewardPhone);
	registry.install({ name: "steward-rules", tasks: [PhotoWindowTask] });
	for (const extension of extensions) registry.install(extension);
	return registry;
}

export async function openTestSteward(options: {
	dbPath: string;
	extensions?: readonly Extension[];
	fauxPlan?: FauxPlan;
}): Promise<TestSteward> {
	const models = createModels();
	let model: { provider: string; modelId: string };
	let faux: FauxProviderHandle | undefined;
	if (options.fauxPlan !== undefined) {
		faux = installStewardFauxPlan(models, options.fauxPlan);
		model = { provider: "faux", modelId: faux.getModel().id };
	} else {
		model = { provider: "unset", modelId: "unset" };
	}
	const storage = await openNodeSqliteStorage(options.dbPath);
	const registry = testRegistry(options.extensions ?? []);
	const harness = await Harness.open(storage, { models, registry }, BACKGROUND_CONTEXT);
	const root = await harness.root(BACKGROUND_CONTEXT, { agent: { model } });
	return { harness, root, model, faux };
}
