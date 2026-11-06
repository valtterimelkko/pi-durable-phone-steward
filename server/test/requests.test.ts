import { describe, expect, it } from "vitest";
import { pruneRequestLog, Requests, resolveRequestId, recordRequest } from "../src/requests.ts";
import { openTestHarness } from "./helpers.ts";

describe("request-id idempotency", () => {
	it("returns the first response with duplicate: true for the same requestId and body", async () => {
		const t = await openTestHarness();
		try {
			const first = { accepted: true, decisionId: "dc-1" };
			const r1 = await t.commit((tx) =>
				resolveRequestId(tx, { requestId: "req-1", route: "/v1/proposals/pr-1/decision", body: { decision: "approve" } }),
			);
			expect(r1).toBeUndefined();
			await t.commit((tx) =>
				recordRequest(tx, {
					requestId: "req-1",
					route: "/v1/proposals/pr-1/decision",
					body: { decision: "approve" },
					response: first,
					nowMs: 1000,
				}),
			);
			const again = await t.commit((tx) =>
				resolveRequestId(tx, { requestId: "req-1", route: "/v1/proposals/pr-1/decision", body: { decision: "approve" } }),
			);
			expect(again).toEqual({ response: first, duplicate: true });
		} finally {
			await t.close();
		}
	});

	it("gives REQUEST_ID_CONFLICT for the same requestId with a different body or route", async () => {
		const t = await openTestHarness();
		try {
			await t.commit((tx) =>
				recordRequest(tx, {
					requestId: "req-2",
					route: "/v1/chat",
					body: { text: "hello" },
					response: { accepted: true },
					nowMs: 1000,
				}),
			);
			await expect(
				t.commit((tx) =>
					resolveRequestId(tx, { requestId: "req-2", route: "/v1/chat", body: { text: "different" } }),
				),
			).rejects.toMatchObject({ code: "REQUEST_ID_CONFLICT", status: 409 });
			await expect(
				t.commit((tx) =>
					resolveRequestId(tx, { requestId: "req-2", route: "/v1/device/status", body: { text: "hello" } }),
				),
			).rejects.toMatchObject({ code: "REQUEST_ID_CONFLICT", status: 409 });
		} finally {
			await t.close();
		}
	});

	it("ignores case in key order via canonical comparison and prunes the log to a bound", async () => {
		const t = await openTestHarness();
		try {
			const now = 1000;
			await t.commit((tx) =>
				recordRequest(tx, {
					requestId: "req-3",
					route: "/v1/chat",
					body: { a: 1, b: 2 },
					response: { ok: true },
					nowMs: now,
				}),
			);
			const same = await t.commit((tx) =>
				resolveRequestId(tx, { requestId: "req-3", route: "/v1/chat", body: { b: 2, a: 1 } }),
			);
			expect(same?.duplicate).toBe(true);

			for (let i = 0; i < 520; i++) {
				await t.commit((tx) =>
					recordRequest(tx, {
						requestId: `bulk-${i}`,
						route: "/v1/chat",
						body: { i },
						response: { ok: true },
						nowMs: now + i,
					}),
				);
			}
			const kept = await t.commit((tx) => pruneRequestLog(tx, now + 600));
			expect(kept).toBeLessThanOrEqual(500);
			const state = await t.snapshot(Requests);
			expect(Object.keys(state!.entries).length).toBe(kept);
		} finally {
			await t.close();
		}
	});
});
