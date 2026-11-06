/**
 * S11 (protocol A10.8): the server notices apps the owner removed outside the app.
 *
 * An `app_usage` report lists the packages installed now. Every OPEN proposal's
 * `uninstall_app` item whose package is absent from a good report is settled: the ledger
 * gets a `removed_outside` row (not `executed`: the owner's approval did not remove it), the
 * item leaves the proposal, the proposal's estimate is recomputed, and a proposal left with
 * no item closes (status `expired`, `closedReason: "removed_outside"`). The A9.2 note says so.
 *
 * Only a report with status `ok` and a non-empty `apps` list counts; a partial, empty or
 * malformed list could drop items for apps that are still installed. Approved proposals are
 * left to their execution report.
 */
import type { ConversationId, Tx } from "@earendil-works/pi-durable";
import { recordDecision } from "./documents.ts";
import { Proposals } from "./proposals.ts";

/** The packages an `app_usage` result names, or undefined when it cannot be trusted. */
export function installedPackages(data: unknown): Set<string> | undefined {
	const apps = (data as { apps?: unknown } | null | undefined)?.apps;
	if (!Array.isArray(apps) || apps.length === 0) return undefined;
	const names = new Set<string>();
	for (const app of apps) {
		const name = (app as { packageName?: unknown } | null)?.packageName;
		if (typeof name === "string" && name.length > 0) names.add(name);
	}
	return names.size === 0 ? undefined : names;
}

/**
 * Settle open uninstall items against a good `app_usage` result, inside the caller's commit.
 * Returns how many items were dropped.
 */
export async function settleRemovedApps(
	tx: Tx,
	conversationId: ConversationId,
	result: { status: string; data: unknown },
	nowMs: number,
): Promise<number> {
	if (result.status !== "ok") return 0;
	const installed = installedPackages(result.data);
	if (installed === undefined) return 0;
	const doc = await tx.doc(Proposals, conversationId);
	let dropped = 0;
	for (const proposal of Object.values(doc.proposals)) {
		if (proposal.status !== "open") continue;
		const gone = proposal.items.filter((item) => {
			if (item.kind !== "uninstall_app") return false;
			const name = (item.target as { packageName?: unknown } | null)?.packageName;
			return typeof name === "string" && !installed.has(name);
		});
		if (gone.length === 0) continue;
		for (const item of gone) {
			const target = item.target as { packageName?: string; label?: unknown };
			await recordDecision(tx, conversationId, {
				atMs: nowMs,
				type: "removed_outside",
				proposalId: proposal.proposalId,
				itemId: item.itemId,
				kind: item.kind,
				packageName: target.packageName,
				bytesEstimate: item.bytesEstimate,
				note: typeof target.label === "string" ? target.label : target.packageName,
			});
			dropped += 1;
		}
		const goneIds = new Set(gone.map((item) => item.itemId));
		proposal.items = proposal.items.filter((item) => !goneIds.has(item.itemId));
		proposal.bytesEstimate = proposal.items.reduce((sum, item) => sum + (Number.isFinite(item.bytesEstimate) ? item.bytesEstimate : 0), 0);
		if (proposal.items.length === 0) {
			proposal.status = "expired";
			proposal.closedReason = "removed_outside";
			proposal.statusChangedAtMs = nowMs;
		}
	}
	return dropped;
}
