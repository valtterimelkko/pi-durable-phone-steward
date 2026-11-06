/**
 * S10 (protocol A10.8): SIGTERM closes streams and connections, stops the schedulers and
 * exits within 5 seconds. Before this, `servers.close()` waited for every open chat stream,
 * so a stream held by the phone could stall a systemd stop until its timeout.
 */
import { spawn, type ChildProcess } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdtemp, rm } from "node:fs/promises";
import { createServer as createNetServer, connect } from "node:net";
import { request } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { createShutdown } from "../src/shutdown.ts";

describe("createShutdown", () => {
	it("stops the scheduler and the bridge, closes the servers, then the harness, then exits 0 — once", async () => {
		const order: string[] = [];
		const exits: number[] = [];
		const shutdown = createShutdown({
			scheduler: { stop: () => order.push("scheduler") },
			bridge: { stop: () => order.push("bridge") },
			servers: { close: async () => void order.push("servers") },
			closeHarness: async () => void order.push("harness"),
			exit: (code) => void exits.push(code),
			log: () => undefined,
		});
		await shutdown("SIGTERM");
		await shutdown("SIGTERM");
		expect(order).toEqual(["scheduler", "bridge", "servers", "harness"]);
		expect(exits).toEqual([0]);
	});

	it("stops the live-session sweeper with the other timers (S16)", async () => {
		const order: string[] = [];
		const shutdown = createShutdown({
			scheduler: { stop: () => order.push("scheduler") },
			bridge: { stop: () => order.push("bridge") },
			liveSweeper: { stop: () => order.push("liveSweeper") },
			servers: { close: async () => void order.push("servers") },
			closeHarness: async () => void order.push("harness"),
			exit: () => undefined,
			log: () => undefined,
		});
		await shutdown("SIGTERM");
		expect(order).toEqual(["scheduler", "bridge", "liveSweeper", "servers", "harness"]);
	});

	it("forces the exit at the deadline when the servers refuse to close", async () => {
		const exits: number[] = [];
		const started = Date.now();
		const shutdown = createShutdown({
			scheduler: { stop: () => undefined },
			bridge: { stop: () => undefined },
			servers: { close: () => new Promise<void>(() => undefined) },
			closeHarness: async () => undefined,
			exit: (code) => void exits.push(code),
			log: () => undefined,
			deadlineMs: 300,
		});
		await shutdown("SIGTERM");
		expect(exits).toEqual([1]);
		expect(Date.now() - started).toBeLessThan(2_000);
	});
});

describe("SIGTERM on a running server (real process)", () => {
	let dir: string;
	let child: ChildProcess | undefined;
	beforeEach(async () => {
		dir = await mkdtemp(join(tmpdir(), "p7-sigterm-"));
	});
	afterEach(async () => {
		child?.kill("SIGKILL");
		await rm(dir, { recursive: true, force: true });
	});

	async function freePort(): Promise<number> {
		return new Promise((resolve) => {
			const probe = createNetServer().listen(0, "127.0.0.1", () => {
				const { port } = probe.address() as { port: number };
				probe.close(() => resolve(port));
			});
		});
	}

	function adminPost(path: string): Promise<Record<string, unknown>> {
		return new Promise((resolve, reject) => {
			const socket = connect(join(dir, "admin.sock"), () => socket.write(`POST ${path} HTTP/1.1\r\nhost: admin\r\ncontent-length: 0\r\nconnection: close\r\n\r\n`));
			let raw = "";
			socket.on("data", (chunk: Buffer) => (raw += chunk.toString("utf8")));
			socket.on("error", reject);
			socket.on("close", () => resolve(JSON.parse(raw.slice(raw.indexOf("\r\n\r\n") + 4)) as Record<string, unknown>));
		});
	}

	it("closes an open chat stream and an idle keep-alive connection and exits within 5 seconds", async () => {
		const port = await freePort();
		const main = fileURLToPath(new URL("../src/main.ts", import.meta.url));
		const env: NodeJS.ProcessEnv = {
			PATH: process.env.PATH ?? "",
			HOME: dir,
			PHONE_STEWARD_STATE_DIR: dir,
			PHONE_STEWARD_PORT: String(port),
			PHONE_STEWARD_HOST: "127.0.0.1",
			PHONE_STEWARD_TELEGRAM: "off",
			PHONE_STEWARD_SCHEDULES: "off",
			PHONE_STEWARD_AGENT_OS: "off",
			PHONE_STEWARD_PI_DIR: join(dir, "pi"),
			PHONE_STEWARD_RELEASE_DIR: join(dir, "releases"),
		};
		child = spawn(process.execPath, ["--experimental-strip-types", main], { env, stdio: ["ignore", "ignore", "pipe"] });
		let stderr = "";
		child.stderr!.on("data", (chunk: Buffer) => (stderr += chunk.toString("utf8")));
		const exited = new Promise<{ code: number | null; at: number }>((resolve) => child!.on("exit", (code) => resolve({ code, at: Date.now() })));
		const deadline = Date.now() + 20_000;
		while (!stderr.includes("listening on") && Date.now() < deadline) await new Promise((resolve) => setTimeout(resolve, 50));
		expect(stderr).toContain("listening on");

		const code = (await adminPost("/admin/pair")).code as string;
		const paired = await fetch(`http://127.0.0.1:${port}/v1/pair`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ code, deviceName: "t" }) });
		const token = ((await paired.json()) as { token: string }).token;

		// An open chat stream (the phone holds one) and an idle keep-alive connection.
		const streamEnded = new Promise<string>((resolve) => {
			const req = request({ host: "127.0.0.1", port, path: "/v1/chat/stream", headers: { authorization: `Bearer ${token}` } }, (res) => {
				res.on("data", () => undefined);
				res.on("end", () => resolve("end"));
				res.on("close", () => resolve("close"));
				res.on("error", () => resolve("error"));
			});
			req.on("error", () => resolve("error"));
			req.end();
		});
		const idle = connect(port, "127.0.0.1");
		await new Promise<void>((resolve) => idle.on("connect", () => resolve()));
		idle.write("GET /v1/status HTTP/1.1\r\nhost: x\r\n\r\n");
		await new Promise((resolve) => setTimeout(resolve, 500));

		const sentAt = Date.now();
		child.kill("SIGTERM");
		const result = await Promise.race([exited, new Promise<{ code: number | null; at: number }>((resolve) => setTimeout(() => resolve({ code: -1, at: Date.now() }), 8_000))]);
		expect(result.code).toBe(0);
		expect(result.at - sentAt).toBeLessThan(5_000);
		expect(["end", "close"]).toContain(await streamEnded);
		expect(existsSync(join(dir, "admin.sock"))).toBe(false);
		idle.destroy();
	}, 40_000);
});
