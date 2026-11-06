/**
 * Pi Durable 1.2+: any Storage error fails the whole Session, which closes itself; every later
 * call rejects with SessionFailed. Without a watcher the process stays up looking healthy and
 * answers nothing, so a failed close ends the process and systemd restarts it (plan: Pi Durable
 * 1.5 migration). A close on purpose (shutdown) is left alone.
 *
 * 1.5.0 tags the end by `kind` (`{ kind: "closed" }` or `{ kind: "failed", error }`); the input
 * is the installed `SessionEnd` itself, so a change of shape is a typecheck error here rather
 * than a host that silently never exits.
 */
import type { SessionEnd } from "@earendil-works/pi-durable";

export type { SessionEnd };

export type HarnessFailureDeps = {
	log(line: string): void;
	exit(code: number): void;
};

export async function exitOnHarnessFailure(closed: Promise<SessionEnd>, deps: HarnessFailureDeps): Promise<void> {
	const end = await closed;
	if (end.kind !== "failed") return;
	const error = end.error;
	deps.log(`[steward] the harness failed (storage error): ${error instanceof Error ? (error.stack ?? error.message) : String(error)}; exiting so the service restarts`);
	deps.exit(1);
}
