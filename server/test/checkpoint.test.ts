import { describe, expect, it } from "vitest";
import { CHECKPOINT_EVERY, neverCheckpoint, periodicCheckpoint } from "../src/checkpoint.ts";

/**
 * Pi Durable 1.4.0 requires every `defineDoc`/`defineDocFamily` definition to carry its own
 * `checkpointWhen` policy (there is no default). These are the two policies the steward uses:
 * ordinary small documents keep every change as a delta, and documents that grow keep their
 * replay bounded by writing a base every `CHECKPOINT_EVERY` deltas.
 */
describe("document checkpoint policy", () => {
	it("never writes a base for an ordinary change", () => {
		const policy = neverCheckpoint();
		expect(policy({}, [], { deltasSinceBase: 0 })).toBe(false);
		expect(policy({}, [], { deltasSinceBase: 10_000 })).toBe(false);
	});

	it("writes a base once the newest base has CHECKPOINT_EVERY deltas behind it", () => {
		const policy = periodicCheckpoint();
		expect(CHECKPOINT_EVERY).toBeGreaterThan(1);
		expect(policy({}, [], { deltasSinceBase: 0 })).toBe(false);
		expect(policy({}, [], { deltasSinceBase: CHECKPOINT_EVERY - 1 })).toBe(false);
		expect(policy({}, [], { deltasSinceBase: CHECKPOINT_EVERY })).toBe(true);
		expect(policy({}, [], { deltasSinceBase: CHECKPOINT_EVERY * 2 + 3 })).toBe(true);
	});

	it("takes an explicit interval for a document with a different growth rate", () => {
		expect(periodicCheckpoint(4)({}, [], { deltasSinceBase: 3 })).toBe(false);
		expect(periodicCheckpoint(4)({}, [], { deltasSinceBase: 4 })).toBe(true);
	});

	it("keeps a base frequency that bounds a growing ledger's replay", () => {
		// The decisions ledger keeps up to 1000 entries; replaying a base plus CHECKPOINT_EVERY
		// appends must stay far below that, and the interval must be a usable number.
		expect(Number.isInteger(CHECKPOINT_EVERY)).toBe(true);
		expect(CHECKPOINT_EVERY).toBeLessThanOrEqual(100);
	});
});

/**
 * The per-document choice, pinned: a document that gains deltas at a rate the owner does not
 * control must fold them into a base, or the revision rows and the replay on every load grow
 * without bound. `Device` is written continuously — `touchDeviceSeen` on each phone poll (at
 * most once a minute per device) and `recordDeviceStatus` on each status report — so it uses
 * the periodic policy even though its value is overwritten in place.
 */
describe("the policy each document actually carries", () => {
	it("folds a continuously written document into a base", async () => {
		const { Device, RulesDoc } = await import("../src/documents.ts");
		const { StewardSessionInfo } = await import("../src/worklog/docs.ts");
		const at = (deltasSinceBase: number) => ({ deltasSinceBase });
		expect(Device.definition.checkpointWhen({}, [], at(CHECKPOINT_EVERY))).toBe(true);
		expect(Device.definition.checkpointWhen({}, [], at(0))).toBe(false);
		// The two documents written once (or only by the owner) stay pure deltas.
		expect(RulesDoc.definition.checkpointWhen({} as never, [], at(10_000))).toBe(false);
		expect(StewardSessionInfo.definition.checkpointWhen({} as never, [], at(10_000))).toBe(false);
	});
});
