/** A10.4: "the choice is a steward event" — the worklog mirror writes a model_change line when the model changes. */
import { readdirSync, readFileSync } from "node:fs";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { createOrchestrationIntegrations } from "../src/orchestration/index.ts";
import { chooseModel } from "../src/agent-settings.ts";
import { openStewardHarness } from "../src/steward.ts";

const ctx = BACKGROUND_CONTEXT;
let dir: string;
beforeEach(async () => {
	dir = await mkdtemp(join(tmpdir(), "p7-mirror-"));
});
afterEach(async () => {
	await rm(dir, { recursive: true, force: true });
});

function modelChanges(): Array<{ provider: string; modelId: string }> {
	const files = readdirSync(join(dir, "worklog")).filter((name) => name.endsWith(".jsonl"));
	return files.flatMap((name) =>
		readFileSync(join(dir, "worklog", name), "utf8")
			.split("\n")
			.filter((line) => line.includes('"model_change"'))
			.map((line) => JSON.parse(line) as { provider: string; modelId: string }),
	);
}

describe("a model choice reaches the worklog", () => {
	it("writes a model_change line for the new model", async () => {
		const integrations = createOrchestrationIntegrations({ enabled: false, command: "/none", worklogDir: join(dir, "worklog"), timeoutMs: 1000 });
		const opened = await openStewardHarness({
			dbPath: join(dir, "s.sqlite"),
			fauxPlan: { hang: false, models: [{ id: "faux-a" }, { id: "faux-b" }] },
			integrations,
		});
		try {
			await opened.harness.commit((tx) => import("../src/steward-events.ts").then((m) => m.emitStewardEvent(tx, opened.root.id, { kind: "run_start", atMs: Date.now(), data: { reason: "owner" } })), ctx);
			await chooseModel(
				{ harness: opened.harness, root: opened.root, models: opened.models, catalogue: opened.catalogue, context: opened.context, conversationId: "main", defaultModel: { provider: "zai", modelId: "glm-5.3-flash" } },
				{ model: { provider: "faux", id: "faux-b" }, confirmMetered: true },
			);
			const deadline = Date.now() + 10_000;
			while (Date.now() < deadline && !modelChanges().some((change) => change.modelId === "faux-b")) await new Promise((resolve) => setTimeout(resolve, 100));
			expect(modelChanges().map((change) => change.modelId)).toEqual(["faux-a", "faux-b"]);
		} finally {
			await opened.close();
		}
	});
});
