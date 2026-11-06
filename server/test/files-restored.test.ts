/**
 * A11.3 server half: `POST /v1/files/restored`. The app has put files back from the Steward
 * trash; the server records one `restored` decisions-ledger entry per item, so the steward
 * does not propose those paths again without the owner asking. Deduped by `requestId` like
 * every other device route.
 */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { createPairingCode, pairRequest } from "../src/auth.ts";
import { loadConfig } from "../src/config.ts";
import { Decisions, type DecisionLedgerEntry } from "../src/documents.ts";
import { startStewardServers, type StewardServers } from "../src/http.ts";
import { ownerChatNote } from "../src/owner-note.ts";
import { openTestHarness, type TestHarness } from "./helpers.ts";

const context = BACKGROUND_CONTEXT;

let t: TestHarness;
let servers: StewardServers;
let port: number;
let token: string;
let seq = 0;

beforeEach(async () => {
	t = await openTestHarness();
	const code = await t.commit((tx) => createPairingCode(tx, Date.now()));
	const paired = await pairRequest((change) => t.commit(change as never), { code: code.code, deviceName: "test", address: "127.0.0.1", nowMs: Date.now() });
	token = paired.token;
	const config = loadConfig({ stateDir: ":memory:", port: 0, notify: {}, schedules: { enabled: false } });
	servers = startStewardServers({ config, harness: t.harness, root: t.root });
	await new Promise<void>((resolve) => servers.tcp.listen(0, "127.0.0.1", resolve));
	port = (servers.tcp.address() as { port: number }).port;
});

afterEach(async () => {
	await servers.close();
	await t.close();
});

function post(body: unknown, withToken = true): Promise<{ status: number; body: any }> {
	return fetch(`http://127.0.0.1:${port}/v1/files/restored`, {
		method: "POST",
		headers: { "content-type": "application/json", ...(withToken ? { authorization: `Bearer ${token}` } : {}) },
		body: JSON.stringify(body),
	}).then(async (response) => ({ status: response.status, body: await response.json() }));
}

const restored = (path: string, bytes = 10) => ({ path, bytes });

async function ledger(): Promise<DecisionLedgerEntry[]> {
	return (await t.harness.snapshot(Decisions, t.root.id, context))?.entries ?? [];
}

describe("POST /v1/files/restored", () => {
	it("records one restored ledger entry per item and answers {recorded: n}", async () => {
		const response = await post({
			requestId: `r-${++seq}`,
			proposalId: "pr-abc123",
			items: [restored("Download/big.iso", 4_000), restored("MapsWithMe/230606")],
		});
		expect(response.status).toBe(200);
		expect(response.body).toEqual({ recorded: 2 });
		const entries = await ledger();
		expect(entries).toHaveLength(2);
		expect(entries[0]).toMatchObject({ type: "restored", kind: "delete_files", path: "Download/big.iso", bytes: 4_000, proposalId: "pr-abc123" });
		expect(entries[1]).toMatchObject({ type: "restored", kind: "delete_files", path: "MapsWithMe/230606", bytes: 10 });
		expect(entries.every((entry) => typeof entry.atMs === "number")).toBe(true);
	});

	it("records a proposal id the server does not know", async () => {
		const response = await post({ requestId: `r-${++seq}`, proposalId: "pr-nope", items: [restored("Download/x.bin", 1)] });
		expect(response.status).toBe(200);
		expect((await ledger())[0]).toMatchObject({ type: "restored", proposalId: "pr-nope", path: "Download/x.bin" });
	});

	it("is idempotent: a repeat of the same requestId and body changes nothing", async () => {
		const body = { requestId: `r-${++seq}`, proposalId: "pr-abc123", items: [restored("Download/a.bin", 2)] };
		expect((await post(body)).body).toEqual({ recorded: 1 });
		const repeat = await post(body);
		expect(repeat.status).toBe(200);
		expect(repeat.body).toEqual({ recorded: 1, duplicate: true });
		expect(await ledger()).toHaveLength(1);
	});

	it("needs a device token", async () => {
		const response = await post({ requestId: `r-${++seq}`, proposalId: "pr-abc123", items: [restored("Download/a.bin")] }, false);
		expect(response.status).toBe(401);
	});

	it("validates paths like delete_files targets", async () => {
		for (const path of ["/etc/passwd", "Download/../secret", "Android/data/x", ".StewardTrash/2026-01-01/pr-x/a", "Keep/a.bin", "DCIM/Camera/a.jpg", "Pictures/a.png", ""]) {
			const response = await post({ requestId: `r-${++seq}`, proposalId: "pr-abc123", items: [restored(path)] });
			expect(response.status, `path ${JSON.stringify(path)}`).toBe(400);
		}
		expect(await ledger()).toHaveLength(0);
	});

	it("validates the envelope: at most 500 items, a proposal id, numeric bytes", async () => {
		const many = Array.from({ length: 501 }, (_, index) => restored(`Download/f${index}`));
		expect((await post({ requestId: `r-${++seq}`, proposalId: "pr-abc123", items: many })).status).toBe(400);
		expect((await post({ requestId: `r-${++seq}`, items: [restored("Download/a.bin")] })).status).toBe(400);
		expect((await post({ requestId: `r-${++seq}`, proposalId: "pr-abc123", items: [{ path: "Download/a.bin" }] })).status).toBe(400);
		expect((await post({ requestId: `r-${++seq}`, proposalId: "pr-abc123", items: [{ path: "Download/a.bin", bytes: -5 }] })).status).toBe(400);
		expect((await post({ requestId: `r-${++seq}`, proposalId: "pr-abc123", items: [] })).status).toBe(400);
		expect(await ledger()).toHaveLength(0);
	});

	it("normalises a path the same way delete_files does", async () => {
		const response = await post({ requestId: `r-${++seq}`, proposalId: "pr-abc123", items: [restored("Download//sub/./a.bin", 3)] });
		expect(response.status).toBe(200);
		expect((await ledger())[0]).toMatchObject({ path: "Download/sub/a.bin" });
	});
});

describe("the A9.2 server note", () => {
	it("names the files the owner restored from the Steward trash", async () => {
		const nowMs = Date.now();
		const note = ownerChatNote({
			proposals: [],
			ledger: [
				{ atMs: nowMs, type: "restored", kind: "delete_files", path: "Download/old.iso", bytes: 100, proposalId: "pr-1" },
				{ atMs: nowMs - 20 * 24 * 60 * 60_000, type: "restored", kind: "delete_files", path: "Download/ancient.iso", bytes: 1, proposalId: "pr-0" },
			],
			nowMs,
		});
		expect(note).toContain("Download/old.iso");
		expect(note).not.toContain("Download/ancient.iso");
		expect(note).toMatch(/restored/i);
	});
});
