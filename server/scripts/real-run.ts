/**
 * A single real-model run (GLM 5.3 Flash on the Z.ai plan), through the phone-job pattern.
 *
 *     ZAI_API_KEY="..." node scripts/real-run.ts
 *
 * The credential is read from the environment into this one process; the script never
 * writes it anywhere. Runs on a temporary SQLite file and removes it after.
 */
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { Harness } from "@earendil-works/pi-durable";
import { openNodeSqliteStorage } from "@earendil-works/pi-durable/storage/sqlite/node";
import { createModels } from "@earendil-works/pi-ai/models";
import { zaiProvider } from "@earendil-works/pi-ai/providers/zai";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { JsonObject } from "@earendil-works/pi-durable";
import { messageText } from "../src/faux-steward.ts";
import { Jobs, reportPhoneResult } from "../src/jobs.ts";
import { stewardRegistry } from "../src/steward.ts";

const context = BACKGROUND_CONTEXT;

/** Confirmed against pi-ai's own model list;  forbids any other route. */
const PROVIDER_ID = "zai";
const MODEL_ID = "glm-5.3-flash";

const INSTRUCTIONS = [
	"You are Phone Steward, the owner's phone maintenance agent.",
	"To answer anything that needs the owner's phone, call the phone_echo tool once with a short payload describing what to check; the phone reports later.",
	"A phone report arrives as a later message that starts with [phonejob. When one arrives, summarise the result for the owner in one or two sentences.",
].join(" ");

/** A scripted phone fact pack, in the shape P2's real endpoint will report. */
const FAKE_PHONE_RESULT = JSON.stringify({
	freeBytes: 6_212_458_496,
	totalBytes: 128_359_673_856,
	topApps: [
		{ app: "WhatsApp", dataBytes: 4_310_000_000, cacheBytes: 412_000_000 },
		{ app: "Instagram", dataBytes: 1_820_000_000, cacheBytes: 906_000_000 },
		{ app: "Chrome", dataBytes: 640_000_000, cacheBytes: 388_000_000 },
	],
});

async function main(): Promise<void> {
	if (process.env.ZAI_API_KEY === undefined) {
		throw new Error("ZAI_API_KEY must be set in the environment; never write it to a file");
	}
	const dir = await mkdtemp(join(tmpdir(), "phone-steward-real-"));
	try {
		const models = createModels();
		models.setProvider(zaiProvider()); // reads ZAI_API_KEY from the environment
		const storage = await openNodeSqliteStorage(join(dir, "steward.sqlite"));
		const harness = await Harness.open(storage, { models, registry: stewardRegistry() }, context);
		const root = await harness.root(context, {
			agent: { model: { provider: PROVIDER_ID, modelId: MODEL_ID }, instructions: INSTRUCTIONS },
		});

		const startedAt = Date.now();
		const submission = await root.submit(
			{ type: "input", content: "What is eating my phone's storage? Please check." },
			context,
		);
		const first = await submission.wait(context);
		if (first.status !== "done") throw new Error(`first run did not finish: ${first.status}`);

		// The scripted "phone" reports every queued job; the reporter submits the result as
		// a follow-up input with requestId "phonejob:<jobId>".
		const stored = await harness.snapshot(Jobs, root.id, context);
		const queued = Object.values(stored?.jobs ?? {}).filter((job) => job.status === "queued");
		for (const job of queued) {
			await reportPhoneResult(
				harness,
				root.id,
				job.jobId,
				{ status: "ok", data: JSON.parse(FAKE_PHONE_RESULT) as JsonObject, error: null },
				context,
			);
		}
		await root.waitForIdle(context);
		await root.waitForIdle(context);
		const wallMs = Date.now() - startedAt;

		// Everything below is read back from the committed storage.
		const view = await root.context(context);
		let phoneEchoCalls = 0;
		let phonejobDeliveries = 0;
		const assistantTexts: string[] = [];
		let served: { provider?: string; model?: string } = {};
		for (const record of view.entries) {
			const message = record.model?.[0];
			if (message === undefined) continue;
			if (message.role === "user" && messageText(message).startsWith("[phonejob")) {
				phonejobDeliveries += 1;
			} else if (message.role === "assistant") {
				const text = messageText(message);
				if (text !== "") assistantTexts.push(text);
				for (const part of message.content) {
					if (part.type === "toolCall" && part.name === "phone_echo") phoneEchoCalls += 1;
				}
				served = { provider: message.provider, model: message.model };
			}
		}
		const usage = await harness.usage(context);
		console.log(
			JSON.stringify(
				{
					providerId: PROVIDER_ID,
					modelId: MODEL_ID,
					served,
					wallMs,
					phoneEchoCalls,
					phonejobDeliveries,
					usage,
					assistantTexts,
				},
				null,
				2,
			),
		);
		await harness.close(context);
	} finally {
		await rm(dir, { recursive: true, force: true });
	}
}

main().catch((error) => {
	console.error(String(error));
	process.exit(1);
});
