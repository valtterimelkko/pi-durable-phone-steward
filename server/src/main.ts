/**
 * The server entrypoint: one Node process owning the SQLite storage,
 * the device API (default 127.0.0.1:7461), the
 * admin socket for the CLI, the chat bridge, and —
 * only when config enables them — the schedules.
 *
 * Integrations (the orchestration seam) are wired statically in `wiring.ts`: the event mirror
 * always runs, and external calls go through the integration's outbox, which never blocks a
 * run when the external tool is down or disabled.
 */
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { loadConfig, type StewardConfig } from "./config.ts";
import { startChatBridge } from "./chat-bridge.ts";
import { loadIntegrations } from "./wiring.ts";
import { readProjectMemory } from "./project-memory.ts";
import { setTimeZone } from "./time.ts";
import { createShutdown } from "./shutdown.ts";
import { openStewardHarness } from "./steward.ts";
import { startSchedulers, type Scheduler } from "./schedules.ts";
import { startStewardServers, type StewardServers } from "./http.ts";
import { repairOpenTrashTargets } from "./media-index.ts";
import { Runs } from "./run-tracking.ts";
import { closeRun } from "./run-tracking.ts";
import { startLiveSweeper, sweepLiveSessions } from "./live/sessions.ts";
import { exitOnHarnessFailure } from "./harness-failure.ts";

/** Close runs a crash left open: their submissions are gone with the old process. */
async function sweepStaleRuns(harness: import("@earendil-works/pi-durable").Harness, root: import("@earendil-works/pi-durable").Conversation): Promise<void> {
	await harness.commit(
		async (tx) => {
			const runs = await tx.doc(Runs, root.id);
			for (const runKey of Object.keys(runs.open)) {
				await closeRun(tx, root.id, runKey, Date.now());
			}
			return undefined;
		},
		BACKGROUND_CONTEXT,
	);
}

async function main(): Promise<void> {
	const config: StewardConfig = loadConfig();
	mkdirSync(config.stateDir, { recursive: true });
	setTimeZone(config.timeZone);
	const integrations = await loadIntegrations(config.orchestration);
	const opened = await openStewardHarness({
		dbPath: join(config.stateDir, "steward.sqlite"),
		model: config.model,
		integrations,
		piAgentDir: config.piAgentDir,
		compactAt: config.compactAt,
		projectMemory: () => readProjectMemory(config.stateDir),
		orchestrationReads: { config: config.orchestration },
	});
	const { harness, root } = opened;
	// Pi Durable 1.2+: a storage error fails the whole harness; exit so systemd restarts the service.
	void exitOnHarnessFailure(harness.closed, { log: (line) => console.error(line), exit: (code) => process.exit(code) });
	harness.resume();
	await sweepStaleRuns(harness, root);
	// (A10.3): no phone session survives a restart; a step is never resent.
	const sweptLive = await sweepLiveSessions(harness, BACKGROUND_CONTEXT);
	if (sweptLive > 0) console.error(`[steward] ended ${sweptLive} phone session(s) left running by the last process`);
	// S16: the 10-minute limit and the "app stopped answering" rule run on a clock too.
	const liveSweeper = startLiveSweeper({ harness, context: BACKGROUND_CONTEXT });
	// S9 (A9.1): complete open trash targets stored before the server held the index rows.
	const repaired = await harness.commit((tx) => repairOpenTrashTargets(tx, root.id), BACKGROUND_CONTEXT);
	if (repaired > 0) console.error(`[steward] repaired ${repaired} trash target(s) in open proposals from the reported media index`);
	const bridge = startChatBridge(root);
	const servers: StewardServers = startStewardServers({
		config,
		harness,
		root,
		catalogue: opened.catalogue,
		models: opened.models,
		contextControl: opened.context,
	});
	await new Promise<void>((resolve) => servers.tcp.listen(config.port, config.host, resolve));
	console.error(`[steward] listening on ${config.host}:${config.port} (state: ${config.stateDir})`);
	const scheduler: Scheduler = startSchedulers({ harness, root, config });

	// S10: an orderly, bounded shutdown (streams and connections closed, schedulers stopped).
	const shutdown = createShutdown({
		scheduler,
		bridge,
		liveSweeper,
		servers,
		closeHarness: () => opened.close(),
		exit: (code) => process.exit(code),
		log: (line) => console.error(line),
	});
	process.on("SIGTERM", () => void shutdown("SIGTERM"));
	process.on("SIGINT", () => void shutdown("SIGINT"));
}

main().catch((error) => {
	console.error(`[steward] failed to start: ${error instanceof Error ? (error.stack ?? error.message) : String(error)}`);
	process.exit(1);
});
