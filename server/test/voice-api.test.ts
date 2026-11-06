/**
 * A15.5/A15.6 over HTTP: the two voice routes through the real server (bearer auth, body
 * limits, error codes, audio content types). The providers are stubbed `fetch`es — fakes
 * only — so no key, endpoint or model is ever real.
 */
import { connect } from "node:net";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { loadConfig } from "../src/config.ts";
import { startStewardServers, type StewardServers } from "../src/http.ts";
import { openStewardHarness } from "../src/steward.ts";
import type { FetchLike } from "../src/voice/deps.ts";

const OR_KEY = "test-key-not-real";
const OA_KEY = "test-key-not-real";
const PCM = Buffer.from([1, 2, 3, 4, 5, 6, 7, 8]);

let dir: string;
let port: number;
let servers: StewardServers;
let opened: Awaited<ReturnType<typeof openStewardHarness>>;
let token: string;
let currentFetch: FetchLike;
const calls: Array<{ url: string; init: RequestInit | undefined }> = [];

const voiceFetch: FetchLike = async (input, init) => {
	calls.push({ url: typeof input === "string" ? input : String(input), init });
	return currentFetch(input, init);
};

const transcribeOk: FetchLike = async () => new Response(JSON.stringify({ text: "transcribed words" }), { status: 200 });
const speakOk: FetchLike = async () => new Response(new Uint8Array(PCM), { status: 200, headers: { "content-type": "audio/pcm;rate=24000;channels=1" } });
const failEvery: FetchLike = async () => new Response("no", { status: 500 });

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

async function raw(
	path: string,
	init: { method?: string; token?: string | null; body?: string | Buffer; contentType?: string } = {},
): Promise<{ status: number; headers: Headers; bodyText: string; bodyBytes: Buffer }> {
	const bearer = init.token === undefined ? token : init.token;
	const response = await fetch(`http://127.0.0.1:${port}${path}`, {
		method: init.method ?? "GET",
		headers: {
			...(init.contentType === undefined ? {} : { "content-type": init.contentType }),
			...(bearer === null ? {} : { authorization: `Bearer ${bearer}` }),
		},
		body: init.body,
	});
	const bodyBytes = Buffer.from(await response.arrayBuffer());
	return { status: response.status, headers: response.headers, bodyText: bodyBytes.toString("utf8"), bodyBytes };
}

beforeAll(async () => {
	dir = await mkdtemp(join(tmpdir(), "p13-voice-api-"));
	currentFetch = transcribeOk;
	const config = loadConfig({
		stateDir: dir,
		port: 0,
		notify: {},
		schedules: { enabled: false },
		voice: { openrouterApiKey: OR_KEY, openaiApiKey: OA_KEY, localAsrUrl: "http://127.0.0.1:9000/asr" },
	});
	opened = await openStewardHarness({ dbPath: join(dir, "steward.sqlite") });
	servers = startStewardServers({ config, harness: opened.harness, root: opened.root, voiceFetch });
	await new Promise<void>((resolve) => servers.tcp.listen(0, "127.0.0.1", resolve));
	port = (servers.tcp.address() as { port: number }).port;
	const code = ((await adminPost("/admin/pair")) as { code: string }).code;
	const paired = await raw("/v1/pair", { method: "POST", token: null, body: JSON.stringify({ code, deviceName: "voice-suite" }), contentType: "application/json" });
	token = (JSON.parse(paired.bodyText) as { token: string }).token;
});

beforeEach(() => {
	currentFetch = transcribeOk;
	calls.length = 0;
});

afterAll(async () => {
	await servers.close();
	await opened.close();
	await rm(dir, { recursive: true, force: true });
});

describe("POST /v1/voice/transcribe (A15.5)", () => {
	it("needs a device token", async () => {
		const response = await raw("/v1/voice/transcribe", { method: "POST", token: null, body: Buffer.from("x"), contentType: "audio/ogg" });
		expect(response.status).toBe(401);
	});

	it("200 with the transcribed text for audio/ogg", async () => {
		const response = await raw("/v1/voice/transcribe", { method: "POST", body: Buffer.from("audio-bytes"), contentType: "audio/ogg" });
		expect(response.status).toBe(200);
		expect(JSON.parse(response.bodyText)).toEqual({ text: "transcribed words" });
	});

	it("200 for audio/mp4 too, and passes the format to OpenRouter", async () => {
		await raw("/v1/voice/transcribe", { method: "POST", body: Buffer.from("mp4-bytes"), contentType: "audio/mp4" });
		const body = JSON.parse(String(calls[0]!.init?.body)) as { input_audio: { format: string } };
		expect(body.input_audio.format).toBe("mp4");
		expect(calls[0]!.url).toBe("https://openrouter.ai/api/v1/audio/transcriptions");
	});

	it("400 for an unsupported content type and for an empty body", async () => {
		expect((await raw("/v1/voice/transcribe", { method: "POST", body: Buffer.from("x"), contentType: "audio/webm" })).status).toBe(400);
		expect((await raw("/v1/voice/transcribe", { method: "POST", body: Buffer.alloc(0), contentType: "audio/ogg" })).status).toBe(400);
	});

	it("413 BODY_TOO_LARGE above 10 MB", async () => {
		const response = await raw("/v1/voice/transcribe", { method: "POST", body: Buffer.alloc(10 * 1024 * 1024 + 1), contentType: "audio/ogg" });
		expect(response.status).toBe(413);
		expect((JSON.parse(response.bodyText) as { error: { code: string } }).error.code).toBe("BODY_TOO_LARGE");
	});

	it("502 VOICE_FAILED when every leg fails with a key configured, and never leaks the key", async () => {
		currentFetch = failEvery;
		const response = await raw("/v1/voice/transcribe", { method: "POST", body: Buffer.from("audio"), contentType: "audio/ogg" });
		expect(response.status).toBe(502);
		expect((JSON.parse(response.bodyText) as { error: { code: string } }).error.code).toBe("VOICE_FAILED");
		expect(response.bodyText).not.toContain(OR_KEY);
		expect(response.bodyText).not.toContain(OA_KEY);
	});

	it("200 never contains a provider key", async () => {
		const response = await raw("/v1/voice/transcribe", { method: "POST", body: Buffer.from("audio"), contentType: "audio/ogg" });
		expect(response.bodyText).not.toContain(OR_KEY);
		expect(response.bodyText).not.toContain(OA_KEY);
	});
});

describe("POST /v1/voice/speak (A15.6)", () => {
	it("needs a token", async () => {
		const response = await raw("/v1/voice/speak", { method: "POST", token: null, body: JSON.stringify({ text: "Hi." }), contentType: "application/json" });
		expect(response.status).toBe(401);
	});

	it("400 when the text is missing, empty, too long or not a string", async () => {
		const post = (body: unknown) => raw("/v1/voice/speak", { method: "POST", body: JSON.stringify(body), contentType: "application/json" });
		expect((await post({})).status).toBe(400);
		expect((await post({ text: "" })).status).toBe(400);
		expect((await post({ text: "x".repeat(4001) })).status).toBe(400);
		expect((await post({ text: 42 })).status).toBe(400);
	});

	it("200 audio/wav with a RIFF body from the PCM leg", async () => {
		currentFetch = speakOk;
		const response = await raw("/v1/voice/speak", { method: "POST", body: JSON.stringify({ text: "Hello there." }), contentType: "application/json" });
		expect(response.status).toBe(200);
		expect(response.headers.get("content-type")).toBe("audio/wav");
		expect(response.bodyBytes.subarray(0, 4).toString("ascii")).toBe("RIFF");
		expect(response.bodyBytes.subarray(44)).toEqual(PCM);
		expect(response.bodyText).not.toContain(OR_KEY);
	});

	it("502 VOICE_FAILED when every leg fails with a key configured", async () => {
		currentFetch = failEvery;
		const response = await raw("/v1/voice/speak", { method: "POST", body: JSON.stringify({ text: "Hello." }), contentType: "application/json" });
		expect(response.status).toBe(502);
		expect((JSON.parse(response.bodyText) as { error: { code: string } }).error.code).toBe("VOICE_FAILED");
	});
});
