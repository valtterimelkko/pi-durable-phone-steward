/**
 * The chat SSE stream on Pi Durable 1.5.0 : `Harness.documentState` is
 * gone, so the route acquires a document watch instead. Two things matter to the phone and
 * the owner, and neither is visible from the route's code alone:
 *
 *  - an event committed after the client connects arrives exactly once (the first watch value
 *    also closes the gap between the store replay and the subscription); and
 *  - the watch is stopped when the client disconnects: an unstopped document watch lives until
 *    the Session ends, so every phone reconnect would leak one. The Session's own counter of
 *    pinned document incarnations (`documentCacheSizes.attached`) proves it: a live watch pins
 *    its document, a stopped one unpins it.
 */
import { mkdtemp, rm } from "node:fs/promises";
import { connect } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { loadConfig } from "../src/config.ts";
import { startStewardServers, type StewardServers } from "../src/http.ts";
import { openStewardHarness, type StewardHarness } from "../src/steward.ts";
import { appendChatEvent } from "../src/chat-events.ts";

const context = BACKGROUND_CONTEXT;

let dir: string;
let port: number;
let servers: StewardServers;
let opened: StewardHarness;
let token: string;

async function adminPost(path: string): Promise<Record<string, unknown>> {
	return new Promise((resolve, reject) => {
		const socket = connect(join(dir, "admin.sock"), () => {
			socket.write(`POST ${path} HTTP/1.1\r\nhost: admin\r\ncontent-length: 0\r\nconnection: close\r\n\r\n`);
		});
		let raw = "";
		socket.on("data", (chunk: Buffer) => (raw += chunk.toString("utf8")));
		socket.on("error", reject);
		socket.on("close", () => resolve(JSON.parse(raw.slice(raw.indexOf("\r\n\r\n") + 4)) as Record<string, unknown>));
	});
}

beforeAll(async () => {
	dir = await mkdtemp(join(tmpdir(), "chat-stream-watch-"));
	const config = loadConfig({ stateDir: dir, port: 0, notify: {}, schedules: { enabled: false } });
	opened = await openStewardHarness({ dbPath: join(dir, "steward.sqlite"), fauxPlan: { hang: false } });
	servers = startStewardServers({ config, harness: opened.harness, root: opened.root });
	await new Promise<void>((resolve) => servers.tcp.listen(0, "127.0.0.1", resolve));
	port = (servers.tcp.address() as { port: number }).port;
	const code = ((await adminPost("/admin/pair")) as { code: string }).code;
	const response = await fetch(`http://127.0.0.1:${port}/v1/pair`, {
		method: "POST",
		headers: { "content-type": "application/json" },
		body: JSON.stringify({ code, deviceName: "stream test phone" }),
	});
	if (!response.ok) throw new Error(`pairing failed: ${response.status}`);
	token = ((await response.json()) as { token: string }).token;
});

afterAll(async () => {
	await servers.close();
	await opened.close();
	await rm(dir, { recursive: true, force: true });
});

/**
 * How many document incarnations the Session holds pinned. Pi Durable exposes this as an
 * internal-for-tests getter on the Session (`documentCacheSizes`), which is not part of the
 * public `Harness` type; a live document watch pins its document, a stopped one unpins it, so
 * this is how the test sees the route's `watch.stop()`. Read structurally, with no cast.
 */
function attachedDocuments(harness: object): number | undefined {
	const stats: unknown = Reflect.get(harness, "documentCacheSizes");
	if (typeof stats !== "object" || stats === null) return undefined;
	const attached: unknown = Reflect.get(stats, "attached");
	return typeof attached === "number" ? attached : undefined;
}

/** Poll `check` until it is true; fails the test rather than hanging. */
async function until(check: () => boolean, what: string, timeoutMs = 10_000): Promise<void> {
	const deadline = Date.now() + timeoutMs;
	while (Date.now() < deadline) {
		if (check()) return;
		await new Promise((resolve) => setTimeout(resolve, 20));
	}
	throw new Error(`timed out waiting for ${what}`);
}

describe("the chat SSE stream", () => {
	it("sends an event committed after connect exactly once, and stops its watch when the client leaves", async () => {
		// Warm the store the way the route's replay does, so `attached` counts watches, not reads.
		await opened.harness.commit(
			(tx) => appendChatEvent(tx, "message", { role: "user", text: "before connect", atMs: Date.now() }, Date.now()),
			context,
		);
		expect(attachedDocuments(opened.harness)).toBe(0);

		const controller = new AbortController();
		const response = await fetch(`http://127.0.0.1:${port}/v1/chat/stream?after=0`, {
			headers: { authorization: `Bearer ${token}` },
			signal: controller.signal,
		});
		expect(response.status).toBe(200);
		expect(response.headers.get("content-type")).toContain("text/event-stream");
		const reader = response.body!.getReader();
		// The route acquires its watch after the store replay; the watch pins the document.
		await until(() => attachedDocuments(opened.harness) === 1, "the route's document watch");

		// One event after connect: it must appear on the stream, and only once.
		await opened.harness.commit(
			(tx) => appendChatEvent(tx, "message", { role: "assistant", text: "a line after connect", atMs: Date.now() }, Date.now()),
			context,
		);
		const decoder = new TextDecoder();
		let text = "";
		// Read until the event arrives.
		const deadline = Date.now() + 10_000;
		while (!text.includes("a line after connect") && Date.now() < deadline) {
			const { value, done } = await reader.read();
			if (done) break;
			text += decoder.decode(value);
		}
		expect(text).toContain("a line after connect");
		// Exactly once: the frame is written by one `send()`.
		expect(text.split("a line after connect")).toHaveLength(2);
		// And no second copy arrives while connected.
		await opened.harness.commit(
			(tx) => appendChatEvent(tx, "message", { role: "assistant", text: "a second line", atMs: Date.now() }, Date.now()),
			context,
		);
		const secondDeadline = Date.now() + 10_000;
		while (!text.includes("a second line") && Date.now() < secondDeadline) {
			const { value, done } = await reader.read();
			if (done) break;
			text += decoder.decode(value);
		}
		expect(text.split("a line after connect")).toHaveLength(2);
		expect(text.split("a second line")).toHaveLength(2);

		// Disconnect: the route's close handler stops the watch, which unpins the document.
		controller.abort();
		await reader.cancel().catch(() => undefined);
		await until(() => attachedDocuments(opened.harness) === 0, "the stopped watch");
	});
});
