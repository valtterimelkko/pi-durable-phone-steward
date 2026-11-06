/**
 * The steward's own documents : rule settings, dated storage snapshots with the
 * trend calculation, the decisions ledger, and the device status.
 */
import { defineDoc, type ConversationId, type Tx } from "@earendil-works/pi-durable";
import { ApiError, conflict } from "./errors.ts";
import { normaliseFolderScopes } from "./folder-scope.ts";
import { emitStewardEvent } from "./steward-events.ts";
import { neverCheckpoint, periodicCheckpoint } from "./checkpoint.ts";

// ————— rules (docs/protocol.md §5) —————

export const PROTOCOL_FOLDERS = [
	"DCIM/Camera/",
	"Pictures/Screenshots/",
	"Android/media/com.whatsapp/",
	"Android/media/org.telegram.messenger/",
	"Download/",
];

export type PhotoWindowRules = {
	enabled: boolean;
	reminderDays: number;
	cutoffDays: number;
	autoMode: boolean;
	folders: string[];
	keepMarker: string;
};

export type Rules = {
	ruleVersion: number;
	photoWindow: PhotoWindowRules;
	batchesRunOneTap: number;
};

export function defaultRules(): Rules {
	return {
		ruleVersion: 3,
		photoWindow: {
			enabled: false,
			reminderDays: 90,
			cutoffDays: 120,
			autoMode: false,
			folders: [...PROTOCOL_FOLDERS],
			keepMarker: "Keep",
		},
		batchesRunOneTap: 0,
	};
}

export const RulesDoc = defineDoc<Rules>({
	kind: "steward.rules",
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: neverCheckpoint(),
	scope: "conversation",
	history: "latest",
	fork: "initial",
	initial: defaultRules,
});

export type RulesUpdate = { ruleVersion: number; photoWindow: Partial<PhotoWindowRules> };

/**
 * Optimistic rule update (only the app can change rules; no model tool reaches this).
 * Validation: `reminderDays < cutoffDays`, non-empty `folders` and `keepMarker`; a stale
 * `ruleVersion` is `409 RULE_VERSION_CONFLICT`; `autoMode: true` while
 * `batchesRunOneTap === 0` is `409 AUTO_MODE_NOT_YET` .
 */
export async function updateRules(tx: Tx, conversationId: ConversationId, update: RulesUpdate): Promise<Rules> {
	const doc = await tx.doc(RulesDoc, conversationId);
	if (update.ruleVersion !== doc.ruleVersion) {
		throw conflict("RULE_VERSION_CONFLICT", `ruleVersion ${update.ruleVersion} is stale; current is ${doc.ruleVersion}`);
	}
	const next = update.photoWindow;
	const merged: PhotoWindowRules = { ...doc.photoWindow, ...next };
	if (!(merged.reminderDays < merged.cutoffDays)) {
		throw new ApiError(400, "BAD_REQUEST", "reminderDays must be smaller than cutoffDays");
	}
	if (!Array.isArray(merged.folders) || merged.folders.length === 0) {
		throw new ApiError(400, "BAD_REQUEST", "folders must be a non-empty list");
	}
	// A6: scopes are subtrees and the trailing slash is optional — accept both shapes and
	// store them normalised, so every later comparison is one prefix match.
	if (!merged.folders.every((folder) => typeof folder === "string" && folder.trim().length > 0)) {
		throw new ApiError(400, "BAD_REQUEST", "folders must be a list of non-empty strings");
	}
	merged.folders = normaliseFolderScopes(merged.folders);
	// A4: keepMarker is fixed at "Keep" in the MVP (the app matches it case-insensitively);
	// a configurable marker is a later decision.
	if (merged.keepMarker !== "Keep") {
		throw new ApiError(400, "BAD_REQUEST", "keepMarker is fixed at Keep in the MVP");
	}
	if (merged.autoMode && doc.batchesRunOneTap === 0) {
		throw conflict("AUTO_MODE_NOT_YET", "auto mode unlocks after the first one-tap batch has run");
	}
	doc.ruleVersion = doc.ruleVersion + 1;
	doc.photoWindow = merged;
	return JSON.parse(JSON.stringify({ ...doc })) as Rules;
}

// ————— snapshots  —————

export type SnapshotApp = {
	packageName: string;
	label: string;
	appBytes: number;
	dataBytes: number;
	cacheBytes: number;
	isSystem: boolean;
};

export type SnapshotCategories = {
	imagesBytes: number;
	videoBytes: number;
	audioBytes: number;
	documentsBytes: number;
	appsBytes: number;
	systemBytes: number;
	otherBytes: number;
};

export type Snapshot = {
	capturedAtMs: number;
	totalBytes: number;
	freeBytes: number;
	apps: SnapshotApp[];
	categories: SnapshotCategories;
};

export const SNAPSHOT_KEEP = 52;

export const Snapshots = defineDoc<{ snapshots: Snapshot[] }>({
	kind: "steward.snapshots",
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: periodicCheckpoint(),
	scope: "conversation",
	history: "latest",
	fork: "initial",
	initial: () => ({ snapshots: [] }),
});

/** Append a dated snapshot; history is kept (bounded to the newest SNAPSHOT_KEEP). */
export async function storeSnapshot(tx: Tx, conversationId: ConversationId, snapshot: Snapshot): Promise<void> {
	const doc = await tx.doc(Snapshots, conversationId);
	doc.snapshots.push(JSON.parse(JSON.stringify(snapshot)) as Snapshot);
	if (doc.snapshots.length > SNAPSHOT_KEEP) {
		doc.snapshots = doc.snapshots.slice(doc.snapshots.length - SNAPSHOT_KEEP);
	}
}

export type SnapshotTrend = {
	fromCapturedAtMs: number;
	toCapturedAtMs: number;
	freeBytesDelta: number;
	totalBytesDelta: number;
	apps: Array<{
		packageName: string;
		label: string;
		appBytesDelta: number;
		dataBytesDelta: number;
		cacheBytesDelta: number;
	}>;
	categories: Array<{ category: string; delta: number }>;
};

/** Deterministic diff of the two newest snapshots (what grew or shrank, and by how much). */
export function trendBetween(from: Snapshot, to: Snapshot): SnapshotTrend {
	const before = new Map(from.apps.map((app) => [app.packageName, app]));
	const apps: SnapshotTrend["apps"] = [];
	for (const app of to.apps) {
		const was = before.get(app.packageName);
		apps.push({
			packageName: app.packageName,
			label: app.label,
			appBytesDelta: app.appBytes - (was?.appBytes ?? 0),
			dataBytesDelta: app.dataBytes - (was?.dataBytes ?? 0),
			cacheBytesDelta: app.cacheBytes - (was?.cacheBytes ?? 0),
		});
	}
	for (const [packageName, was] of before) {
		if (!to.apps.some((app) => app.packageName === packageName)) {
			apps.push({
				packageName,
				label: was.label,
				appBytesDelta: -was.appBytes,
				dataBytesDelta: -was.dataBytes,
				cacheBytesDelta: -was.cacheBytes,
			});
		}
	}
	apps.sort((a, b) => Math.abs(b.dataBytesDelta + b.cacheBytesDelta) - Math.abs(a.dataBytesDelta + a.cacheBytesDelta));
	const categories = Object.entries(to.categories)
		.map(([category, value]) => ({
			category,
			delta: value - ((from.categories as Record<string, number>)[category] ?? 0),
		}))
		.filter((entry) => entry.delta !== 0)
		.sort((a, b) => Math.abs(b.delta) - Math.abs(a.delta));
	return {
		fromCapturedAtMs: from.capturedAtMs,
		toCapturedAtMs: to.capturedAtMs,
		freeBytesDelta: to.freeBytes - from.freeBytes,
		totalBytesDelta: to.totalBytes - from.totalBytes,
		apps,
		categories,
	};
}

/** The deterministic diff of the two newest stored snapshots, or undefined with fewer than two. */
export async function storageTrendFromTx(tx: Tx, conversationId: ConversationId): Promise<SnapshotTrend | undefined> {
	const doc = await tx.doc(Snapshots, conversationId);
	const list = doc.snapshots;
	if (list.length < 2) return undefined;
	return trendBetween(list[list.length - 2]!, list[list.length - 1]!);
}

// ————— decisions ledger (§3.4, §3.8) —————

export type DecisionLedgerEntry = {
	atMs: number;
	type:
		| "kept"
		| "rejected"
		| "restored"
		| "executed"
		| "review"
		| "failed"
		| "cancelled"
		| "skipped_protected"
		| "rule_changed"
		/** S6 (A6.2): an agent proposal replaced by a newer one; not an owner decision. */
		| "superseded"
		/** S11 (A10.8): an app the owner removed outside the app, found by an `app_usage` report. */
		| "removed_outside"
		/** (A10.3): one action the steward performed on the screen in an owner-started session. */
		| "screen_action"
		/** A13.1: the steward asked for phone use in Chat, or the owner answered the ask. */
		| "phone_use_request";
	proposalId?: string;
	itemId?: string;
	kind?: string;
	/** For media items. */
	mediaId?: string;
	packageName?: string;
	/** A10.1: for `delete_files` items, the path relative to the shared storage root. */
	path?: string;
	/** A11.3: bytes of a file the owner restored from the Steward trash. */
	bytes?: number;
	bytesEstimate?: number;
	bytesFreed?: number | null;
	/** A10.1: bytes moved to the Steward trash (space returns when it is emptied). */
	bytesTrashed?: number | null;
	note?: string;
	review?: {
		estimatedBytes: number;
		freedBytes: number | null;
		failures: number;
		cancellations: number;
		skippedProtected: number;
	};
};

export const DECISIONS_KEEP = 1000;

export const Decisions = defineDoc<{ entries: DecisionLedgerEntry[] }>({
	kind: "steward.decisions",
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default).
	checkpointWhen: periodicCheckpoint(),
	scope: "conversation",
	history: "latest",
	fork: "initial",
	initial: () => ({ entries: [] }),
});

/** S14 (P7 review): screen taps are chatty, so they never keep more than this many entries. */
export const SCREEN_ACTION_KEEP = 200;

/**
 * Trim the ring. `screen_action` entries go first: only the newest SCREEN_ACTION_KEEP stay in
 * any case, and over DECISIONS_KEEP the oldest screen actions are dropped before any owner
 * decision (keep/reject/restore, `restoredMediaIds`) is touched. Only when the other entries
 * alone exceed the cap do the oldest of them go, as before.
 */
export function trimDecisions(entries: DecisionLedgerEntry[]): DecisionLedgerEntry[] {
	let screenCount = 0;
	for (const entry of entries) if (entry.type === "screen_action") screenCount += 1;
	const dropScreen = Math.max(screenCount - SCREEN_ACTION_KEEP, Math.max(0, entries.length - DECISIONS_KEEP));
	let kept = entries;
	if (dropScreen > 0) {
		let toDrop = Math.min(dropScreen, screenCount);
		kept = entries.filter((entry) => {
			if (toDrop > 0 && entry.type === "screen_action") {
				toDrop -= 1;
				return false;
			}
			return true;
		});
	}
	return kept.length > DECISIONS_KEEP ? kept.slice(kept.length - DECISIONS_KEEP) : kept;
}

export async function recordDecision(tx: Tx, conversationId: ConversationId, entry: DecisionLedgerEntry): Promise<void> {
	const doc = await tx.doc(Decisions, conversationId);
	doc.entries.push(JSON.parse(JSON.stringify(entry)) as DecisionLedgerEntry);
	if (doc.entries.length > DECISIONS_KEEP || doc.entries.length > SCREEN_ACTION_KEEP) {
		const trimmed = trimDecisions(doc.entries);
		if (trimmed.length !== doc.entries.length) doc.entries = trimmed;
	}
}

/** Media ids the owner executed a trash for (an item seen again after that was restored). */
export async function executedTrashMediaIds(tx: Tx, conversationId: ConversationId): Promise<Set<string>> {
	const doc = await tx.doc(Decisions, conversationId);
	const ids = new Set<string>();
	for (const entry of doc.entries) {
		if (entry.type === "executed" && entry.kind === "trash_media" && entry.mediaId !== undefined) {
			ids.add(entry.mediaId);
		}
	}
	return ids;
}

/** Media ids recorded as restored — protected from then on (§4). */
export async function restoredMediaIds(tx: Tx, conversationId: ConversationId): Promise<Set<string>> {
	const doc = await tx.doc(Decisions, conversationId);
	const ids = new Set<string>();
	for (const entry of doc.entries) {
		if (entry.type === "restored" && entry.mediaId !== undefined) ids.add(entry.mediaId);
	}
	return ids;
}

// ————— device status (§7) —————

export type DevicePermissions = {
	usageAccess: boolean;
	allFiles: boolean;
	mediaRead: boolean;
	manageMedia: boolean;
	batteryUnrestricted: boolean;
	/** A10.2: Shizuku, as the app sees it. Absent from older apps. */
	shizuku?: "absent" | "not_running" | "no_permission" | "ready";
};

/** A10.1: what the Steward trash holds, from the device status. */
export type StewardTrashStatus = { bytes: number; oldestMs: number | null };

export type DeviceState = {
	deviceId?: string;
	lastSeenMs?: number;
	appVersion?: string;
	permissions?: DevicePermissions;
	lastWorkerRunMs?: number;
	stewardTrash?: StewardTrashStatus;
};

export const Device = defineDoc<DeviceState>({
	kind: "steward.device",
	version: 1,
	// Pi Durable 1.4.0 requires an explicit checkpoint policy (no default). The value is
	// overwritten in place, but the storage is not: `touchDeviceSeen` writes a delta on every
	// phone poll (at most once a minute) and `recordDeviceStatus` on every status report, so
	// without a base the revision rows and the replay on each load grow all day.
	checkpointWhen: periodicCheckpoint(),
	scope: "conversation",
	history: "latest",
	fork: "initial",
	initial: () => ({}),
});

/** Record one device status report; the single device overwrites its own last report. */
export async function recordDeviceStatus(
	tx: Tx,
	conversationId: ConversationId,
	status: {
		deviceId: string;
		appVersion?: string;
		permissions?: DevicePermissions;
		lastWorkerRunMs?: number;
		stewardTrash?: StewardTrashStatus;
		nowMs: number;
	},
): Promise<void> {
	const doc = await tx.doc(Device, conversationId);
	doc.deviceId = status.deviceId;
	doc.lastSeenMs = status.nowMs;
	if (status.appVersion !== undefined) doc.appVersion = status.appVersion;
	if (status.permissions !== undefined) doc.permissions = JSON.parse(JSON.stringify(status.permissions)) as DevicePermissions;
	if (status.lastWorkerRunMs !== undefined) doc.lastWorkerRunMs = status.lastWorkerRunMs;
	// A10.1: kept as reported; a malformed value is dropped and the last good one stays.
	const trash = status.stewardTrash as { bytes?: unknown; oldestMs?: unknown } | undefined;
	if (
		trash !== undefined && trash !== null && typeof trash === "object" &&
		typeof trash.bytes === "number" && Number.isFinite(trash.bytes) && trash.bytes >= 0 &&
		(trash.oldestMs === null || (typeof trash.oldestMs === "number" && Number.isFinite(trash.oldestMs)))
	) {
		doc.stewardTrash = { bytes: trash.bytes, oldestMs: trash.oldestMs };
	}
}

/** N9: a job poll refreshes `lastSeenMs` at most once per this long per device. */
export const DEVICE_SEEN_THROTTLE_MS = 60_000;

/**
 * Refresh `lastSeenMs` for an authenticated device poll, throttled so it writes at most once
 * per `DEVICE_SEEN_THROTTLE_MS`. Touches nothing else (permissions and version are kept).
 * Returns whether it wrote.
 */
export async function touchDeviceSeen(
	tx: Tx,
	conversationId: ConversationId,
	deviceId: string,
	nowMs: number,
): Promise<boolean> {
	const doc = await tx.doc(Device, conversationId);
	if (typeof doc.lastSeenMs === "number" && nowMs - doc.lastSeenMs >= 0 && nowMs - doc.lastSeenMs < DEVICE_SEEN_THROTTLE_MS) {
		return false;
	}
	doc.deviceId = deviceId;
	doc.lastSeenMs = nowMs;
	return true;
}

/**
 * Emit a steward event with a named helper so call sites stay short. Re-exported from
 * steward-events to keep the import surface of the documents module self-contained.
 */
export { emitStewardEvent };
