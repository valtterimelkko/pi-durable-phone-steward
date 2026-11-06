import type { CheckpointInfo, JsonObject } from "@earendil-works/pi-durable";

/**
 * The predicate pi-durable 1.4.0 requires on every `defineDoc()`/`defineDocFamily()`
 * definition: return `true` to store this ordinary change as a complete base instead of a
 * delta. There is no default in 1.4.0, so every steward document names its policy here.
 */
export type CheckpointWhen<T extends JsonObject> = (
	value: Readonly<T>,
	ops: readonly unknown[],
	info: CheckpointInfo,
) => boolean;

/**
 * How many deltas a growing document keeps behind its newest base. A base plus up to this
 * many deltas is what a mutation replays, so 50 keeps the cost flat without writing a base
 * on every second commit.
 */
export const CHECKPOINT_EVERY = 50;

/**
 * Policy for a document whose changes stay small and in place (a settings record, the device
 * status, a per-run budget counter): every change stays a delta, so a mutation replays the
 * newest base plus the deltas since it. Nothing here grows without bound, so there is no
 * reason to pay for a base.
 */
export function neverCheckpoint<T extends JsonObject>(): CheckpointWhen<T> {
	return () => false;
}

/**
 * Policy for a document that grows — a ledger, an append log, a keyed map of jobs, requests,
 * proposals or sessions. Once `every` deltas sit behind the newest base, the next ordinary
 * change is written as a base, which bounds the replay of every later mutation.
 */
export function periodicCheckpoint<T extends JsonObject>(every: number = CHECKPOINT_EVERY): CheckpointWhen<T> {
	if (!Number.isInteger(every) || every < 1) {
		throw new RangeError(`checkpoint interval must be a positive integer, got ${String(every)}`);
	}
	return (_value, _ops, info) => info.deltasSinceBase >= every;
}
