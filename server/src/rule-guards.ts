/**
 * Rule guards shared by the API and the rule tasks : auto mode unlocks only after at
 * least one monthly batch has run as one-tap approval.
 */
import { conflict } from "./errors.ts";
import type { PhotoWindowRules, Rules } from "./documents.ts";

/** Throws 409 AUTO_MODE_NOT_YET when auto mode is requested while `batchesRunOneTap` is 0. */
export function AUTO_MODE_NOT_YET(rules: Pick<PhotoWindowRules, "autoMode"> & Pick<Rules, "batchesRunOneTap">): void {
	if (rules.autoMode && rules.batchesRunOneTap === 0) {
		throw conflict("AUTO_MODE_NOT_YET", "auto mode unlocks after the first one-tap batch has run");
	}
}
