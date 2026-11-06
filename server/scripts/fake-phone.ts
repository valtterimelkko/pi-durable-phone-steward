/**
 * The scripted fake phone : pairs, long-polls the job queue, answers every job
 * kind with canned protocol-shaped data (no real file names — IMG_0001.jpg-style), decides
 * proposals and reports executions. Used by the API integration tests and the live run.
 *
 *     node scripts/fake-phone.ts --base-url http://127.0.0.1:PORT --code XXXX-XXXX [--approve] [--one-shot]
 */
import { readFileSync } from "node:fs";

type Args = {
	baseUrl: string;
	code: string | undefined;
	approve: boolean;
	oneShot: boolean;
	decision: "approve" | "reject";
	tokenFile: string | undefined;
};

function parseArgs(argv: string[]): Args {
	const args: Args = {
		baseUrl: "http://127.0.0.1:7461",
		code: undefined,
		approve: false,
		oneShot: false,
		decision: "approve",
		tokenFile: undefined,
	};
	for (let i = 0; i < argv.length; i++) {
		const value = argv[i]!;
		if (value === "--base-url") args.baseUrl = argv[++i] ?? args.baseUrl;
		else if (value === "--code") args.code = argv[++i];
		else if (value === "--approve") args.approve = true;
		else if (value === "--reject") args.decision = "reject";
		else if (value === "--one-shot") args.oneShot = true;
		else if (value === "--token-file") args.tokenFile = argv[++i];
	}
	return args;
}

function mediaRef(
	index: number,
	ageDays: number,
	folder = "DCIM/Camera/",
	protectedRef: Partial<MediaRefOut> = {},
): MediaRefOut {
	const taken = Date.now() - ageDays * 86_400_000;
	return {
		mediaId: `external_primary:images:${10_000 + index}`,
		relativePath: folder,
		displayName: `IMG_2026${String(100 + index).slice(-3)}_1010${index}.jpg`,
		mimeType: "image/jpeg",
		sizeBytes: 3_500_000 + index * 150_000,
		dateTakenMs: taken,
		dateAddedMs: taken,
		isFavourite: protectedRef.isFavourite ?? false,
		inKeep: protectedRef.inKeep ?? false,
	};
}

type MediaRefOut = {
	mediaId: string;
	relativePath: string;
	displayName: string;
	mimeType: string;
	sizeBytes: number;
	dateTakenMs: number;
	dateAddedMs: number;
	isFavourite: boolean;
	inKeep: boolean;
};

const SCOPE = [
	"DCIM/Camera/",
	"Pictures/Screenshots/",
	"Android/media/com.whatsapp/",
	"Android/media/org.telegram.messenger/",
	"Download/",
];

/** Canned answers per job kind (protocol §3.3). */
function answerFor(kind: string, params: Record<string, unknown>): { status: "ok" | "error"; data: unknown; error: { code: string; message: string } | null } {
	if (kind === "storage_overview") {
		return {
			status: "ok",
			data: {
				totalBytes: 128_359_673_856,
				freeBytes: 6_212_458_496,
				apps: [
					{ packageName: "com.whatsapp", label: "WhatsApp", appBytes: 210_000_000, dataBytes: 4_310_000_000, cacheBytes: 412_000_000, isSystem: false },
					{ packageName: "com.instagram.android", label: "Instagram", appBytes: 180_000_000, dataBytes: 1_820_000_000, cacheBytes: 906_000_000, isSystem: false },
					{ packageName: "com.android.chrome", label: "Chrome", appBytes: 250_000_000, dataBytes: 640_000_000, cacheBytes: 388_000_000, isSystem: false },
					{ packageName: "com.google.android.gms", label: "Google Play services", appBytes: 300_000_000, dataBytes: 900_000_000, cacheBytes: 120_000_000, isSystem: true },
				],
				categories: { imagesBytes: 9_000_000_000, videoBytes: 14_000_000_000, audioBytes: 1_000_000_000, documentsBytes: 500_000_000, appsBytes: 21_000_000_000, systemBytes: 18_000_000_000, otherBytes: 2_000_000_000 },
			},
			error: null,
		};
	}
	if (kind === "app_usage") {
		const now = Date.now();
		return {
			status: "ok",
			data: {
				apps: [
					{ packageName: "com.whatsapp", label: "WhatsApp", lastUsedMs: now - 3_600_000, installedMs: now - 900 * 86_400_000, isSystem: false },
					{ packageName: "com.instagram.android", label: "Instagram", lastUsedMs: now - 86_400_000, installedMs: now - 700 * 86_400_000, isSystem: false },
					{ packageName: "com.spotify.music", label: "Spotify", lastUsedMs: now - 45 * 86_400_000, installedMs: now - 500 * 86_400_000, isSystem: false },
					{ packageName: "com.google.android.gms", label: "Google Play services", lastUsedMs: now - 600_000, installedMs: now - 900 * 86_400_000, isSystem: true },
				],
			},
			error: null,
		};
	}
	if (kind === "media_index") {
		const mode = (params as { mode?: string }).mode ?? "summary";
		if (mode === "rule_candidates") {
			const olderThanDays = Number((params as { olderThanDays?: number }).olderThanDays ?? 90);
			// Two items past the cut-off (one previously reminded), one in the reminder
			// window, one protected, one fresh.
			const items = [
				mediaRef(1, olderThanDays + 35),
				mediaRef(2, olderThanDays + 10),
				mediaRef(3, olderThanDays - 5),
				mediaRef(4, olderThanDays + 40, "DCIM/Camera/", { inKeep: true }),
				mediaRef(5, olderThanDays + 40, "DCIM/Camera/", { isFavourite: true }),
				mediaRef(6, 3),
			];
			return { status: "ok", data: { scope: SCOPE, items }, error: null };
		}
		return {
			status: "ok",
			data: {
				scope: SCOPE,
				folders: [
					{ folderKey: "DCIM/Camera", bytes: 9_200_000_000, count: 3_412, buckets: { under3m: { count: 412, bytes: 1_100_000_000 }, m3to4: { count: 210, bytes: 560_000_000 }, over4m: { count: 2_790, bytes: 7_540_000_000 }, unknown: { count: 0, bytes: 0 } } },
					{ folderKey: "Pictures/Screenshots", bytes: 1_100_000_000, count: 890, buckets: { under3m: { count: 220, bytes: 270_000_000 }, m3to4: { count: 90, bytes: 110_000_000 }, over4m: { count: 580, bytes: 720_000_000 }, unknown: { count: 0, bytes: 0 } } },
					{ folderKey: "Android/media/com.whatsapp", bytes: 4_300_000_000, count: 1_844, buckets: null },
					{ folderKey: "Android/media/org.telegram.messenger", bytes: 2_100_000_000, count: 640, buckets: null },
					{ folderKey: "Download", bytes: 3_400_000_000, count: 96, buckets: null },
				],
				largest: [mediaRef(1, 200), mediaRef(2, 150)],
				topLevelFiles: [],
				// A4: oldApks are .apk files anywhere in shared storage, the 50 largest
				// (canned data: three).
				oldApks: [
					{ name: "whatsapp-old.apk", sizeBytes: 32_000_000, modifiedMs: Date.now() - 300 * 86_400_000 },
					{ name: "Download/mapsme-old.apk", sizeBytes: 68_000_000, modifiedMs: Date.now() - 210 * 86_400_000 },
					{ name: "Download/vlc-legacy.apk", sizeBytes: 24_000_000, modifiedMs: Date.now() - 150 * 86_400_000 },
				],
			},
			error: null,
		};
	}
	if (kind === "phone_selftest") {
		return {
			status: "ok",
			data: {
				checks: [
					{ id: "trash_dialog", outcome: "pass", resultCode: -1, detail: "one dialog for 2 items", atMs: Date.now() },
					{ id: "manage_media_skip", outcome: "skipped", resultCode: null, detail: "MANAGE_MEDIA not granted", atMs: Date.now() },
					{ id: "trash_restorable", outcome: "pass", resultCode: -1, detail: "trashed test item restorable", atMs: Date.now() },
					{ id: "clear_cache_intent", outcome: "pass", resultCode: 0, detail: "intent returned", atMs: Date.now() },
					{ id: "workmanager_ran", outcome: "pass", resultCode: null, detail: "last run 12 min ago", atMs: Date.now() },
				],
			},
			error: null,
		};
	}
	return { status: "error", data: null, error: { code: "UNKNOWN_KIND", message: `the fake phone does not know kind ${kind}` } };
}

async function api<T>(baseUrl: string, path: string, init: { method?: string; token?: string; body?: unknown } = {}): Promise<{ status: number; body: T }> {
	const response = await fetch(`${baseUrl}${path}`, {
		method: init.method ?? "GET",
		headers: {
			"content-type": "application/json",
			...(init.token === undefined ? {} : { authorization: `Bearer ${init.token}` }),
		},
		body: init.body === undefined ? undefined : JSON.stringify(init.body),
	});
	const text = await response.text();
	let body: unknown = text;
	try {
		body = JSON.parse(text);
	} catch {
		// keep text
	}
	return { status: response.status, body: body as T };
}

async function main(): Promise<void> {
	const args = parseArgs(process.argv.slice(2));
	if (args.code === undefined) {
		// Read the code from stdin (the live run pipes it from bin/steward pair).
		args.code = readFileSync(0, "utf8").trim();
	}

	const pair = await api<{ deviceId: string; token: string; error?: { code: string; message: string } }>(args.baseUrl, "/v1/pair", {
		method: "POST",
		body: { code: args.code, deviceName: "Fake Phone (scripted)" },
	});
	if (pair.status !== 200) throw new Error(`pair failed: ${JSON.stringify(pair.body)}`);
	const token = pair.body.token;
	if (args.tokenFile !== undefined) {
		const { writeFileSync } = await import("node:fs");
		writeFileSync(args.tokenFile, token, { mode: 0o600 });
	}
	console.log(JSON.stringify({ event: "paired", deviceId: pair.body.deviceId }));

	await api(args.baseUrl, "/v1/device/status", {
		method: "POST",
		token,
		body: {
			requestId: `fake-device-status-${Date.now()}`,
			appVersion: "0.2.0-fake",
			permissions: { usageAccess: true, allFiles: true, mediaRead: true, manageMedia: false, batteryUnrestricted: true },
			lastWorkerRunMs: Date.now() - 600_000,
		},
	});

	const finished = new Set<string>();
	const decisionsMade = new Set<string>();
	for (;;) {
		const jobs = await api<{ jobs: Array<{ jobId: string; kind: string; params: Record<string, unknown> }> }>(
			args.baseUrl,
			"/v1/jobs?wait=3",
			{ token },
		);
		for (const job of jobs.body.jobs ?? []) {
			if (finished.has(job.jobId)) continue;
			finished.add(job.jobId);
			const answer = answerFor(job.kind, job.params ?? {});
			console.log(JSON.stringify({ event: "answering", jobId: job.jobId, kind: job.kind, status: answer.status, deviceId: pair.body.deviceId }));
			// rule_batch: the fake phone re-checks the rules, then "trashes" and reports.
			if (job.kind === "rule_batch") {
				const rules = await api<{ ruleVersion: number; photoWindow: { enabled: boolean; autoMode: boolean } }>(
					args.baseUrl,
					"/v1/rules",
					{ token },
				);
				if (!rules.body.photoWindow.enabled) {
					await api(args.baseUrl, `/v1/jobs/${job.jobId}/result`, {
						method: "POST",
						token,
						body: { requestId: `res-${job.jobId}`, status: "error", data: null, error: { code: "RULE_CHANGED", message: "rules changed" }, deviceTimeMs: Date.now(), appVersion: "0.2.0-fake" },
					});
					continue;
				}
				const items = ((job.params as { items?: Array<{ mediaId: string }> }).items ?? []).map((item) => ({
					mediaId: item.mediaId,
					outcome: "trashed" as const,
					resultCode: null,
				}));
				await api(args.baseUrl, `/v1/jobs/${job.jobId}/result`, {
					method: "POST",
					token,
					body: {
						requestId: `res-${job.jobId}`,
						status: "ok",
						data: { batchId: (job.params as { batchId?: string }).batchId ?? "", items },
						error: null,
						deviceTimeMs: Date.now(),
						appVersion: "0.2.0-fake",
					},
				});
				continue;
			}
			const posted = await api(args.baseUrl, `/v1/jobs/${job.jobId}/result`, {
				method: "POST",
				token,
				body: { requestId: `res-${job.jobId}`, status: answer.status, data: answer.data, error: answer.error, deviceTimeMs: Date.now(), appVersion: "0.2.0-fake" },
			});
			console.log(JSON.stringify({ event: "posted", jobId: job.jobId, status: posted.status, body: posted.body }));
		}
		if (args.approve) {
			const proposals = await api<{ proposals: Array<{ proposalId: string; status: string; items: Array<{ itemId: string }> }> }>(
				args.baseUrl,
				"/v1/proposals?status=open",
				{ token },
			);
			for (const proposal of proposals.body.proposals ?? []) {
				if (decisionsMade.has(proposal.proposalId)) continue;
				decisionsMade.add(proposal.proposalId);
				const decision = await api<{ decisionId: string; approvedItemIds: string[] }>(
					args.baseUrl,
					`/v1/proposals/${proposal.proposalId}/decision`,
					{
						method: "POST",
						token,
						body: { requestId: `dec-${proposal.proposalId}`, decision: args.decision, itemIds: undefined },
					},
				);
				console.log(JSON.stringify({ event: "decided", proposalId: proposal.proposalId, decision: args.decision }));
				if (args.decision !== "approve") continue;
				const authority = await api<{ valid: boolean; approvedItemIds: string[] }>(
					args.baseUrl,
					`/v1/decisions/${decision.body.decisionId}`,
					{ token },
				);
				if (!authority.body.valid) continue;
				await api(args.baseUrl, `/v1/proposals/${proposal.proposalId}/execution`, {
					method: "POST",
					token,
					body: {
						requestId: `exe-${proposal.proposalId}`,
						decisionId: decision.body.decisionId,
						items: authority.body.approvedItemIds.map((itemId, index) => ({
							itemId,
							outcome: "done",
							resultCode: -1,
							bytesFreed: 3_500_000 + index * 150_000,
						})),
					},
				});
				console.log(JSON.stringify({ event: "executed", proposalId: proposal.proposalId }));
			}
		}
		if (args.oneShot && finished.size > 0) break;
		await new Promise((resolve) => setTimeout(resolve, 300));
	}
}

main().catch((error) => {
	console.error(`[fake-phone] ${error instanceof Error ? error.message : String(error)}`);
	process.exit(1);
});
