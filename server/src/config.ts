/**
 * Server configuration: everything from the environment, with safe defaults. A disposable
 * instance needs only a temporary state dir and port.
 */
import { homedir } from "node:os";
import { join } from "node:path";
import { parseTrustedProxies, type TrustedRange } from "./rate-limit-key.ts";
import type { OrchestrationConfig } from "./integrations.ts";
import { COMPACT_AT_DEFAULT, clampCompactAt } from "./context-policy.ts";

export type StewardConfig = {
	stateDir: string;
	host: string;
	port: number;
	/** The model the `main` conversation's agent settings start with. */
	model: { provider: string; modelId: string };
	/** The conversation id (protocol §6: one conversation, `main`). */
	conversationId: "main";
	/**
	 * Owner notifications. `command` runs per notification
	 * (`<command> milestone <title> <body>`); unset means the JSONL stub under the state
	 * directory (see `notify.ts`).
	 */
	notify: { command?: string };
	/** Weekly and photo-window schedules; OFF by default. */
	schedules: { enabled: boolean };
	/** The external orchestration seam (presence board, memory); off by default. */
	orchestration: OrchestrationConfig;
	/** Reverse proxies whose X-Forwarded-For the pairing rate limit trusts (loopback always). */
	trustedProxies: TrustedRange[];
	/** Where `release.json` and the shipped APK live (the artifacts folder). */
	releaseDir: string;
	/** The fraction of the model's window at which background compaction starts (0.5–0.9, default 0.75). */
	compactAt: number;
	/** The Pi agent dir, whose model registry and auth the steward reads (read-only). */
	piAgentDir: string;
	/** The keys and endpoints the voice chains use (never logged or returned). */
	voice: { openrouterApiKey: string; openaiApiKey: string; localAsrUrl: string };
	/** IANA time zone for calendar-date rules (photo ages, weekly run ids); default UTC. */
	timeZone: string;
};

function env(name: string): string | undefined {
	const value = process.env[name];
	return value === undefined || value === "" ? undefined : value;
}

function bool(name: string, fallback: boolean): boolean {
	const value = env(name);
	if (value === undefined) return fallback;
	return value === "1" || value.toLowerCase() === "true" || value.toLowerCase() === "on";
}

export function loadConfig(overrides?: Partial<StewardConfig>): StewardConfig {
	const stateDir = overrides?.stateDir ?? env("PHONE_STEWARD_STATE_DIR") ?? "/var/lib/phone-steward";
	const port = overrides?.port ?? (env("PHONE_STEWARD_PORT") !== undefined ? Number(env("PHONE_STEWARD_PORT")) : 7461);
	const host = overrides?.host ?? env("PHONE_STEWARD_HOST") ?? "127.0.0.1";
	const model = overrides?.model ?? {
		provider: env("PHONE_STEWARD_MODEL_PROVIDER") ?? "zai",
		modelId: env("PHONE_STEWARD_MODEL_ID") ?? "glm-5.3-flash",
	};
	return {
		stateDir,
		host,
		port,
		model,
		conversationId: "main",
		compactAt: clampCompactAt(
			overrides?.compactAt ??
				(env("PHONE_STEWARD_COMPACT_AT") !== undefined ? Number(env("PHONE_STEWARD_COMPACT_AT")) : COMPACT_AT_DEFAULT),
		),
		piAgentDir: overrides?.piAgentDir ?? env("PHONE_STEWARD_PI_DIR") ?? join(homedir(), ".pi", "agent"),
		voice: overrides?.voice ?? {
			openrouterApiKey: env("OPENROUTER_API_KEY") ?? "",
			openaiApiKey: env("OPENAI_API_KEY") ?? "",
			localAsrUrl: env("LOCAL_ASR_URL") ?? "http://127.0.0.1:9000/asr",
		},
		notify: { command: overrides?.notify?.command ?? env("PHONE_STEWARD_NOTIFY_CMD") },
		timeZone: overrides?.timeZone ?? env("PHONE_STEWARD_TIME_ZONE") ?? "UTC",
		releaseDir: overrides?.releaseDir ?? env("PHONE_STEWARD_RELEASE_DIR") ?? join(stateDir, "releases"),
		trustedProxies: overrides?.trustedProxies ?? parseTrustedProxies(env("PHONE_STEWARD_TRUSTED_PROXIES")),
		schedules: { enabled: overrides?.schedules?.enabled ?? bool("PHONE_STEWARD_SCHEDULES", false) },
		orchestration: {
			enabled: overrides?.orchestration?.enabled ?? bool("PHONE_STEWARD_ORCHESTRATION", false),
			command: overrides?.orchestration?.command ?? env("PHONE_STEWARD_ORCHESTRATION_CMD"),
			memoryRoot: overrides?.orchestration?.memoryRoot ?? env("PHONE_STEWARD_ORCHESTRATION_MEMORY_ROOT"),
			boardDir: overrides?.orchestration?.boardDir ?? env("PHONE_STEWARD_ORCHESTRATION_BOARD_DIR"),
			worklogDir: overrides?.orchestration?.worklogDir ?? env("PHONE_STEWARD_WORKLOG_DIR") ?? `${stateDir}/worklog`,
			timeoutMs: overrides?.orchestration?.timeoutMs ?? 10_000,
		},
	};
}
