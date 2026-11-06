/**
 * The orchestration client (docs/protocol.md §8): the only code that invokes the external
 * orchestration command — the small program an adopter points `PHONE_STEWARD_ORCHESTRATION_CMD`
 * at to plug in their own presence board and memory store.
 *
 * Allowlist, enforced twice. Structurally: the argv for each call is built by a pure
 * function whose fixed arguments are literals — free text can only ever land in the
 * `--step` / `--note` value slot, and `execFile` passes argv entries without a shell, so
 * crafted text cannot split into extra arguments. Verifying: every argv is re-checked
 * against the frozen allowlist (`assertAllowlisted`) immediately before the call, so a
 * future refactor that splices input into the fixed region fails tests instead of
 * calling the command.
 *
 * Every presence update carries the fixed `--join-session steward-main`, so a restarted
 * steward updates the same card, and the only statuses are `working` and `waiting`.
 * Every capture carries `--idempotency-key <16 lowercase hex>` — the capture token — so a
 * replayed delivery returns the existing capture instead of writing a duplicate.
 *
 * Every call carries `STEWARD_MEMORY_ROOT` and `STEWARD_BOARD_DIR` from the config — tests
 * and disposable instances point them at scratch directories — and the configured
 * timeout (10 s by default).
 */
import { execFile } from "node:child_process";
import type { OrchestrationConfig } from "../integrations.ts";

export type BoardStatus = "working" | "waiting";

export type OrchestrationCallResult = { ok: true; stdout: string } | { ok: false; error: string };

export type OrchestrationRunOptions = {
	/** The executable to run; the argv holds its arguments only. */
	command: string;
	env: NodeJS.ProcessEnv;
	timeoutMs: number;
};

export type OrchestrationRunner = (argv: readonly string[], options: OrchestrationRunOptions) => Promise<OrchestrationCallResult>;

export type OrchestrationClient = {
	presenceUpdate(step: string, status: BoardStatus): Promise<OrchestrationCallResult>;
	/** The capture token doubles as the command's idempotency key. */
	capture(note: string, idempotencyKey: string): Promise<OrchestrationCallResult>;
};

export type OrchestrationClientDeps = {
	/** Injectable command runner; the default spawns the configured command with `execFile`. */
	runCommand?: OrchestrationRunner;
};

const BOARD_STATUSES: readonly BoardStatus[] = ["working", "waiting"];

/** One presence update: `<command> presence update --step <step> --status <status> …`. */
const FIXED_PRESENCE = ["presence", "update"] as const;

/** The fixed identity of the steward's presence card, so an adopter's tool knows who calls. */
const FIXED_PRESENCE_IDENTITY = ["--name", "phone-steward"] as const;

/** The stable join session, appended to every presence update. */
const JOIN_SESSION = ["--join-session", "steward-main"] as const;

/** One capture: `<command> memory capture --note <note> --idempotency-key <16 hex>`. */
const FIXED_CAPTURE_HEAD = ["memory", "capture", "--note"] as const;

const IDEMPOTENCY_KEY_SHAPE = /^[0-9a-f]{16}$/;

/** Control characters (newlines included) become spaces; runs collapse; capped at 200. */
export function sanitizeSingleLine(text: string, maxLength = 200): string {
	const stripped = text.replace(/\p{C}/gu, " ").replace(/ {2,}/g, " ").trim();
	return stripped.length <= maxLength ? stripped : `${stripped.slice(0, maxLength - 1)}…`;
}

/** Notes may keep line breaks; carriage returns and other controls are stripped; capped. */
export function sanitizeNote(text: string, maxLength = 2_000): string {
	const stripped = text.replace(/\r/g, "").replace(/[^\P{C}\n]/gu, " ").trim();
	return stripped.length <= maxLength ? stripped : `${stripped.slice(0, maxLength - 1)}…`;
}

function assertStatus(status: BoardStatus): BoardStatus {
	if (!BOARD_STATUSES.includes(status)) {
		throw new Error(`orchestration client: presence status must be one of ${BOARD_STATUSES.join("|")}, got ${JSON.stringify(status)}`);
	}
	return status;
}

/** The full argv for one presence update: fixed arguments are literals. */
export function presenceUpdateArgv(step: string, status: BoardStatus): string[] {
	assertStatus(status);
	return [
		...FIXED_PRESENCE,
		"--step",
		sanitizeSingleLine(step),
		"--status",
		status,
		...FIXED_PRESENCE_IDENTITY,
		...JOIN_SESSION,
	];
}

/** The capture dedupe key is exactly the 16-lowercase-hex capture token. */
export function assertIdempotencyKey(key: string): string {
	if (!IDEMPOTENCY_KEY_SHAPE.test(key)) {
		throw new Error(`orchestration client: idempotency key must be 16 lowercase hex, got ${JSON.stringify(key)}`);
	}
	return key;
}

/** The full argv for one capture call: fixed arguments are literals. */
export function captureArgv(note: string, idempotencyKey: string): string[] {
	assertIdempotencyKey(idempotencyKey);
	return [
		...FIXED_CAPTURE_HEAD,
		sanitizeNote(note),
		"--idempotency-key",
		idempotencyKey,
	];
}

/**
 * The frozen allowlist, checked immediately before every call. Anything but the two
 * fixed shapes — another verb, a changed fixed argument, an extra flag — throws.
 */
export function assertAllowlisted(argv: readonly string[]): void {
	const okPresence =
		equals(argv.slice(0, FIXED_PRESENCE.length), FIXED_PRESENCE) && matchesPresenceTail(argv.slice(FIXED_PRESENCE.length));
	if (!okPresence) {
		const okCapture = equals(argv.slice(0, FIXED_CAPTURE_HEAD.length), FIXED_CAPTURE_HEAD) && matchesCaptureTail(argv.slice(FIXED_CAPTURE_HEAD.length));
		if (!okCapture) throw new Error(`orchestration client: argv not allowlisted: ${JSON.stringify(argv)}`);
	}
}

function equals(a: readonly string[], b: readonly string[]): boolean {
	return a.length === b.length && a.every((part, index) => part === b[index]);
}

function matchesPresenceTail(tail: readonly string[]): boolean {
	// ["--step", <value>, "--status", <enum>, "--name", "phone-steward",
	//  "--join-session", "steward-main"], nothing else.
	if (tail.length !== 8) return false;
	return (
		tail[0] === "--step" &&
		typeof tail[1] === "string" &&
		tail[2] === "--status" &&
		BOARD_STATUSES.includes(tail[3] as BoardStatus) &&
		tail[4] === "--name" &&
		tail[5] === FIXED_PRESENCE_IDENTITY[1] &&
		tail[6] === "--join-session" &&
		tail[7] === JOIN_SESSION[1]
	);
}

function matchesCaptureTail(tail: readonly string[]): boolean {
	// [<note value>, "--idempotency-key", <16 lowercase hex>], nothing else.
	if (tail.length !== 3) return false;
	return (
		typeof tail[0] === "string" &&
		tail[1] === "--idempotency-key" &&
		typeof tail[2] === "string" &&
		IDEMPOTENCY_KEY_SHAPE.test(tail[2])
	);
}

/** The default runner: execFile of the configured command, no shell; failure comes back as a result, not a throw. */
export const execFileRunner: OrchestrationRunner = async (argv, options) => {
	try {
		const { stdout } = await new Promise<{ stdout: string }>((resolve, reject) => {
			execFile(
				options.command,
				[...argv],
				{
					env: options.env,
					timeout: options.timeoutMs,
					killSignal: "SIGKILL",
					encoding: "utf8",
					maxBuffer: 4 * 1024 * 1024,
				},
				(error, stdout) => (error === undefined || error === null ? resolve({ stdout: String(stdout) }) : reject(error)),
			);
		});
		return { ok: true, stdout };
	} catch (error) {
		const message = error instanceof Error ? error.message : String(error);
		return { ok: false, error: message };
	}
};

export function createOrchestrationClient(config: OrchestrationConfig, deps: OrchestrationClientDeps = {}): OrchestrationClient {
	const runner = deps.runCommand ?? execFileRunner;
	const env: NodeJS.ProcessEnv = {
		...process.env,
		...(config.memoryRoot === undefined ? {} : { STEWARD_MEMORY_ROOT: config.memoryRoot }),
		...(config.boardDir === undefined ? {} : { STEWARD_BOARD_DIR: config.boardDir }),
	};
	const run = async (argv: string[]): Promise<OrchestrationCallResult> => {
		// Disabled orchestration: every call is a no-op (the local event mirror still writes).
		if (!config.enabled) return { ok: true, stdout: "" };
		assertAllowlisted(argv);
		return runner(argv, { command: config.command ?? "", env, timeoutMs: config.timeoutMs });
	};
	return {
		async presenceUpdate(step, status) {
			return run(presenceUpdateArgv(step, status));
		},
		async capture(note, idempotencyKey) {
			return run(captureArgv(note, idempotencyKey));
		},
	};
}
