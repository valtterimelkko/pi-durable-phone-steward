/**
 * A7 : the release record and the two app-update routes. A real server on a
 * temporary port and state dir, with a TEMPORARY release dir per test (never the real
 * artifacts folder).
 */
import { connect } from "node:net";
import { createHash } from "node:crypto";
import { mkdtemp, mkdir, readFile, rm, writeFile, utimes } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { loadConfig } from "../src/config.ts";
import { openStewardHarness } from "../src/steward.ts";
import { httpRequestLogLine, routeTemplateFor, startStewardServers, type StewardServers } from "../src/http.ts";
import { ReleaseStore } from "../src/release.ts";

let stateDir: string;
let releaseDir: string;
let port: number;
let servers: StewardServers;
let closeHarness: () => Promise<void>;
let token: string;

const sha = (data: Buffer | string) => createHash("sha256").update(data).digest("hex");

async function admin(method: "GET" | "POST", path: string): Promise<any> {
	return new Promise((resolve, reject) => {
		const socket = connect(join(stateDir, "admin.sock"), () => {
			socket.write(`${method} ${path} HTTP/1.1\r\nhost: admin\r\ncontent-length: 0\r\nconnection: close\r\n\r\n`);
		});
		let raw = "";
		socket.on("data", (chunk: Buffer) => {
			raw += chunk.toString("utf8");
		});
		socket.on("error", reject);
		socket.on("close", () => resolve(JSON.parse(raw.slice(raw.indexOf("\r\n\r\n") + 4))));
	});
}

function get(path: string, withToken = true): Promise<Response> {
	return fetch(`http://127.0.0.1:${port}${path}`, {
		headers: withToken ? { authorization: `Bearer ${token}` } : {},
	});
}

type Shipped = { versionName: string; versionCode: number; file: string; bytes: Buffer; sha256: string };

/** Write an APK-like file plus a release.json for it into the release dir. */
async function ship(overrides: Partial<Shipped> & { record?: Record<string, unknown> } = {}): Promise<Shipped> {
	const bytes = overrides.bytes ?? Buffer.from("PK\u0003\u0004 fake apk bytes for tests");
	const shipped: Shipped = {
		versionName: overrides.versionName ?? "0.2.3",
		versionCode: overrides.versionCode ?? 5,
		file: overrides.file ?? "phone-steward-v0.2.3-abc1234.apk",
		bytes,
		sha256: overrides.sha256 ?? sha(bytes),
	};
	await mkdir(releaseDir, { recursive: true });
	await writeFile(join(releaseDir, shipped.file), bytes);
	await writeFile(
		join(releaseDir, "release.json"),
		JSON.stringify({
			versionName: shipped.versionName,
			versionCode: shipped.versionCode,
			file: shipped.file,
			sha256: shipped.sha256,
			sizeBytes: bytes.length,
			publishedAtMs: 1_791_300_000_000,
			...(overrides.record ?? {}),
		}),
	);
	return shipped;
}

beforeAll(async () => {
	stateDir = await mkdtemp(join(tmpdir(), "a7-state-"));
	releaseDir = join(await mkdtemp(join(tmpdir(), "a7-release-")), "artifacts");
	const config = loadConfig({ stateDir, port: 0, notify: {}, schedules: { enabled: false }, releaseDir });
	const opened = await openStewardHarness({
		dbPath: join(stateDir, "steward.sqlite"),
		fauxPlan: { hang: false },
		model: { provider: "zai", modelId: "glm-5.3-flash" },
	});
	closeHarness = () => opened.close();
	servers = startStewardServers({ config, harness: opened.harness, root: opened.root });
	await new Promise<void>((resolve) => servers.tcp.listen(0, "127.0.0.1", resolve));
	port = (servers.tcp.address() as { port: number }).port;
	const { code } = await admin("POST", "/admin/pair");
	const paired = await fetch(`http://127.0.0.1:${port}/v1/pair`, {
		method: "POST",
		headers: { "content-type": "application/json" },
		body: JSON.stringify({ code, deviceName: "suite" }),
	});
	token = ((await paired.json()) as { token: string }).token;
});

afterAll(async () => {
	await servers.close();
	await closeHarness();
	await rm(stateDir, { recursive: true, force: true });
	await rm(join(releaseDir, ".."), { recursive: true, force: true });
});

beforeEach(async () => {
	await rm(releaseDir, { recursive: true, force: true });
});

describe("loadConfig releaseDir", () => {
	it("defaults to the artifacts folder and reads PHONE_STEWARD_RELEASE_DIR", () => {
		const previous = process.env.PHONE_STEWARD_RELEASE_DIR;
		try {
			delete process.env.PHONE_STEWARD_RELEASE_DIR;
			expect(loadConfig({ stateDir: "/tmp/x" }).releaseDir).toBe("/tmp/x/releases");
			process.env.PHONE_STEWARD_RELEASE_DIR = "/tmp/elsewhere";
			expect(loadConfig({ stateDir: "/tmp/x" }).releaseDir).toBe("/tmp/elsewhere");
		} finally {
			if (previous === undefined) delete process.env.PHONE_STEWARD_RELEASE_DIR;
			else process.env.PHONE_STEWARD_RELEASE_DIR = previous;
		}
	});
});

describe("GET /v1/app/latest (A7)", () => {
	it("needs the device bearer token", async () => {
		await ship();
		expect((await get("/v1/app/latest", false)).status).toBe(401);
		expect((await get("/v1/app/apk?versionCode=5", false)).status).toBe(401);
	});

	it("answers 404 NO_RELEASE when the folder or release.json is missing", async () => {
		const response = await get("/v1/app/latest");
		expect(response.status).toBe(404);
		expect(((await response.json()) as any).error.code).toBe("NO_RELEASE");
		expect((await get("/v1/app/apk?versionCode=5")).status).toBe(404);
	});

	it("answers 404 NO_RELEASE for an unreadable or malformed release.json, or a missing APK file", async () => {
		await mkdir(releaseDir, { recursive: true });
		await writeFile(join(releaseDir, "release.json"), "{not json");
		expect(((await (await get("/v1/app/latest")).json()) as any).error.code).toBe("NO_RELEASE");
		await ship({ record: { versionCode: "five" } });
		expect((await get("/v1/app/latest")).status).toBe(404);
		await ship({ record: { sha256: "xyz" } });
		expect((await get("/v1/app/latest")).status).toBe(404);
		// A valid record whose APK is not on disk is no release either.
		await ship();
		await rm(join(releaseDir, "phone-steward-v0.2.3-abc1234.apk"));
		expect((await get("/v1/app/latest")).status).toBe(404);
	});

	it("never follows a path in release.json outside the release folder", async () => {
		const outside = join(releaseDir, "..", "outside.apk");
		await mkdir(releaseDir, { recursive: true });
		await writeFile(outside, "secret");
		for (const file of ["../outside.apk", "/etc/passwd", "sub/dir.apk", "..", ""]) {
			await ship({ file: "phone-steward-v0.2.3-abc1234.apk", record: { file, sha256: sha("secret"), sizeBytes: 6 } });
			expect((await get("/v1/app/latest")).status, `file=${file}`).toBe(404);
			expect((await get("/v1/app/apk?versionCode=5")).status, `file=${file}`).toBe(404);
		}
	});

	it("answers the release without the file name or any path", async () => {
		const shipped = await ship();
		const response = await get("/v1/app/latest");
		expect(response.status).toBe(200);
		expect(await response.json()).toEqual({
			versionName: "0.2.3",
			versionCode: 5,
			sha256: shipped.sha256,
			sizeBytes: shipped.bytes.length,
			publishedAtMs: 1_791_300_000_000,
		});
	});

	it("answers 500 RELEASE_CORRUPT when the file does not match release.json (hash or size)", async () => {
		await ship({ sha256: sha("something else") });
		let response = await get("/v1/app/latest");
		expect(response.status).toBe(500);
		expect(((await response.json()) as any).error.code).toBe("RELEASE_CORRUPT");
		expect((await get("/v1/app/apk?versionCode=5")).status).toBe(500);

		const bytes = Buffer.from("twelve bytes");
		await ship({ bytes, record: { sizeBytes: 999 } });
		response = await get("/v1/app/latest");
		expect(response.status).toBe(500);
		expect(((await response.json()) as any).error.code).toBe("RELEASE_CORRUPT");
	});

	it("follows a newly shipped release without a restart", async () => {
		await ship();
		expect(((await (await get("/v1/app/latest")).json()) as any).versionCode).toBe(5);
		await ship({ versionName: "0.2.4", versionCode: 6, file: "phone-steward-v0.2.4-def5678.apk", bytes: Buffer.from("newer apk") });
		const latest = (await (await get("/v1/app/latest")).json()) as any;
		expect(latest).toMatchObject({ versionName: "0.2.4", versionCode: 6 });
	});
});

describe("GET /v1/app/apk (A7)", () => {
	it("streams the APK with its type, length and X-Sha256", async () => {
		const shipped = await ship();
		const response = await get("/v1/app/apk?versionCode=5");
		expect(response.status).toBe(200);
		expect(response.headers.get("content-type")).toBe("application/vnd.android.package-archive");
		expect(response.headers.get("content-length")).toBe(String(shipped.bytes.length));
		expect(response.headers.get("x-sha256")).toBe(shipped.sha256);
		expect(Buffer.from(await response.arrayBuffer()).equals(shipped.bytes)).toBe(true);
	});

	it("answers 409 RELEASE_CHANGED for another versionCode and 400 for a missing or bad one", async () => {
		await ship();
		const changed = await get("/v1/app/apk?versionCode=4");
		expect(changed.status).toBe(409);
		expect(((await changed.json()) as any).error.code).toBe("RELEASE_CHANGED");
		expect((await get("/v1/app/apk")).status).toBe(400);
		expect((await get("/v1/app/apk?versionCode=five")).status).toBe(400);
	});

	it("serves only the release file, whatever else the request carries", async () => {
		const shipped = await ship();
		await writeFile(join(releaseDir, "other.apk"), "not the release");
		const response = await get("/v1/app/apk?versionCode=5&file=other.apk&path=/etc/passwd");
		expect(response.status).toBe(200);
		expect(Buffer.from(await response.arrayBuffer()).equals(shipped.bytes)).toBe(true);
		expect((await get("/v1/app/apk/other.apk?versionCode=5")).status).toBe(404);
	});

	it("streams a large APK intact (hashed on the client as it arrives)", async () => {
		const big = Buffer.alloc(40 * 1024 * 1024);
		for (let i = 0; i < big.length; i += 4096) big.writeUInt32LE(i / 4096, i);
		const shipped = await ship({ bytes: big, file: "phone-steward-v0.2.3-big.apk" });
		const response = await get("/v1/app/apk?versionCode=5");
		expect(response.status).toBe(200);
		const hash = createHash("sha256");
		let total = 0;
		const reader = response.body!.getReader();
		for (;;) {
			const { done, value } = await reader.read();
			if (done) break;
			hash.update(value);
			total += value.length;
		}
		expect(total).toBe(big.length);
		expect(hash.digest("hex")).toBe(shipped.sha256);
	}, 60_000);
});

describe("request log (O4)", () => {
	it("logs the route templates, never the query or a file name", () => {
		expect(routeTemplateFor("GET", "/v1/app/latest")).toBe("/v1/app/latest");
		expect(routeTemplateFor("GET", "/v1/app/apk")).toBe("/v1/app/apk");
		expect(routeTemplateFor("GET", "/v1/app/apk/x.apk")).toBe("unmatched");
		const lines: string[] = [];
		httpRequestLogLine({ method: "GET", route: routeTemplateFor("GET", "/v1/app/apk"), status: 200, ms: 3, device: "dev_1" }, (l) => lines.push(l));
		expect(lines[0]).toBe("[steward] http GET /v1/app/apk 200 3ms device=dev_1");
	});
});

describe("ReleaseStore hash cache", () => {
	it("re-hashes only when size or mtime changes", async () => {
		const dir = join(releaseDir, "unit");
		await mkdir(dir, { recursive: true });
		const bytes = Buffer.from("apk-one");
		const file = "phone-steward-v1.apk";
		await writeFile(join(dir, file), bytes);
		await writeFile(
			join(dir, "release.json"),
			JSON.stringify({ versionName: "1", versionCode: 1, file, sha256: sha(bytes), sizeBytes: bytes.length, publishedAtMs: 1 }),
		);
		let hashes = 0;
		const store = new ReleaseStore(dir, async (path) => {
			hashes += 1;
			return sha(await readFile(path));
		});
		expect((await store.current()).kind).toBe("ok");
		expect((await store.current()).kind).toBe("ok");
		expect(hashes).toBe(1);

		// Same size, different content, new mtime: re-hashed, and now corrupt.
		await writeFile(join(dir, file), Buffer.from("apk-two"));
		await utimes(join(dir, file), new Date(), new Date(Date.now() + 60_000));
		expect((await store.current()).kind).toBe("corrupt");
		expect(hashes).toBe(2);
	});
});
