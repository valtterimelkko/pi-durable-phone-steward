/**
 * S10 (protocol A10.8): an orderly, bounded SIGTERM. Stop the schedulers and the chat
 * bridge, close the listeners and every connection (open chat streams included), close the
 * harness and its SQLite storage, then exit 0. A deadline forces the exit if any step stalls,
 * so a systemd stop never waits for its own timeout on this process.
 */
export type ShutdownDeps = {
	scheduler: { stop(): void };
	bridge: { stop(): void };
	/** S16: the clock-driven live-session sweep (optional so older callers keep working). */
	liveSweeper?: { stop(): void };
	servers: { close(): Promise<void> };
	closeHarness(): Promise<void>;
	exit(code: number): void;
	log(line: string): void;
	/** Force the exit after this long (default 4 s, inside the 5 s budget). */
	deadlineMs?: number;
};

export function createShutdown(deps: ShutdownDeps): (signal: string) => Promise<void> {
	let started = false;
	const deadlineMs = deps.deadlineMs ?? 4_000;
	return async (signal) => {
		if (started) return;
		started = true;
		deps.log(`[steward] ${signal} received; closing`);
		let finished = false;
		const exitOnce = (code: number): void => {
			if (finished) return;
			finished = true;
			deps.exit(code);
		};
		const forced = new Promise<void>((resolve) => {
			const timer = setTimeout(() => {
				deps.log(`[steward] shutdown still running after ${deadlineMs} ms; forcing exit`);
				exitOnce(1);
				resolve();
			}, deadlineMs);
			timer.unref();
		});
		const orderly = (async () => {
			try {
				deps.scheduler.stop();
				deps.bridge.stop();
				deps.liveSweeper?.stop();
				await deps.servers.close();
			} catch (error) {
				deps.log(`[steward] closing the servers failed: ${error instanceof Error ? error.message : String(error)}`);
			}
			try {
				await deps.closeHarness();
			} catch (error) {
				deps.log(`[steward] closing the harness failed: ${error instanceof Error ? error.message : String(error)}`);
			}
			exitOnce(0);
		})();
		await Promise.race([orderly, forced]);
	};
}
