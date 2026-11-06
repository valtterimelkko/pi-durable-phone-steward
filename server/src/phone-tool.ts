/**
 * `phone_echo` — the S1 spike tool, kept ONLY for the S1 crash tests (plan  lineage).
 * It is not installed by the production registry and is never offered to the model; the
 * safety enumeration test fails if it ever appears. The S1 child process installs it
 * explicitly.
 */
import { defineExtension, defineTool } from "@earendil-works/pi-durable";
import { obj, str } from "@earendil-works/pi-schema";
import { crashPoint } from "./crash-gate.ts";
import { queueJob } from "./jobs.ts";

export const phoneEchoTool = defineTool({
	name: "phone_echo",
	description: "Ask the owner's phone to do something; the result arrives later.",
	parameters: obj({ payload: str() }, { open: true }),
	replay: "safe",
	execute: async (args, api, callContext) => {
		// Test scaffolding: blocks between the intent commit and the job creation when the
		// child process arms a gate for this step. A no-op in production.
		await crashPoint("phone_echo");
		const result = await api.commit(
			(tx) =>
				queueJob(
					tx,
					api.conversationId,
					{
						dedupeKey: `${api.taskId}`,
						kind: "storage_overview",
						params: { payload: args.payload },
						nowMs: Date.now(),
					},
				),
			callContext,
		);
		// Test scaffolding: catches a kill after the job-creating commit and before the tool
		// result — the window where a replay must find the job, not create a second one.
		await crashPoint("phone_echo:after-job");
		return { output: [{ type: "text", text: `queued ${result.jobId}` }] };
	},
});

/** Test-only: the S1 crash-test child installs this extension explicitly. */
export const spikePhoneExtension = defineExtension({
	name: "spike-phone",
	tools: [phoneEchoTool],
});
