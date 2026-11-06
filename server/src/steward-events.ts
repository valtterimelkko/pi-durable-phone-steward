/**
 * The core side of the integrations seam (server/src/integrations.ts, parent-owned).
 *
 * Core code calls `emitStewardEvent(tx, conversationId, event)` inside the commit that
 * causes the event. The holder is set by `openStewardHarness` for the process's one
 * harness (production) or per test; a throwing integration is caught and logged — external
 * is never on the critical path .
 */
import type { ConversationId, Tx } from "@earendil-works/pi-durable";
import { noIntegrations, type StewardEvent, type StewardIntegrations } from "./integrations.ts";

let active: StewardIntegrations = noIntegrations;

/** Set the process's active integrations. Called by `openStewardHarness` (and tests). */
export function setActiveIntegrations(integrations: StewardIntegrations): void {
	active = integrations;
}

export function activeIntegrations(): StewardIntegrations {
	return active;
}

/**
 * Emit one steward event to the active integration, inside the caller's commit. A throw is
 * logged and swallowed: a broken integration cannot fail the run or the commit.
 */
export async function emitStewardEvent(
	tx: Tx,
	conversationId: ConversationId,
	event: StewardEvent,
): Promise<void> {
	const integrations = active;
	if (integrations.stewardEvent === undefined) return;
	try {
		await integrations.stewardEvent(tx, conversationId, event);
	} catch (error) {
		const detail = error instanceof Error ? error.message : String(error);
		console.error(`[steward] integration stewardEvent failed (ignored): ${detail}`);
	}
}
