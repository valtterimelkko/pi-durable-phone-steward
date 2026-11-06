/**
 * A15.5 server half — the dictation provider chain (OpenRouter whisper → local Parakeet →
 * OpenAI gpt-transcribe) and the light clean-up pass. Fakes only: every provider is a
 * stubbed `fetch`; no key, endpoint or model is ever real.
 */
import { describe, expect, it } from "vitest";
import {
	VoiceFailedError,
	VoiceUnavailableError,
	formatForContentType,
	transcribeAudio,
} from "../src/voice/stt.ts";
import { cleanTranscript } from "../src/voice/cleanup.ts";
import type { FetchLike, VoiceDeps } from "../src/voice/deps.ts";

const OR_KEY = "or-invented-key-aaaa";
const OA_KEY = "oa-invented-key-bbbb";

type Call = { url: string; init: RequestInit | undefined };

function deps(fetchImpl: FetchLike, overrides: Partial<VoiceDeps> = {}): VoiceDeps {
	return {
		fetch: fetchImpl,
		openrouterApiKey: OR_KEY,
		openaiApiKey: OA_KEY,
		localAsrUrl: "http://127.0.0.1:9000/asr",
		...overrides,
	};
}

function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
	return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });
}

/** A fetch that records calls and answers from a queue of handlers, in order. */
function recorder(handlers: Array<(url: string, init: RequestInit | undefined) => Response | Promise<Response>>) {
	const calls: Call[] = [];
	let index = 0;
	const impl: FetchLike = async (input, init) => {
		const url = typeof input === "string" ? input : String(input);
		calls.push({ url, init });
		const handler = handlers[index++];
		if (!handler) throw new Error(`unexpected fetch #${index} to ${url}`);
		return handler(url, init);
	};
	return { impl, calls };
}

function bodyOf(init: RequestInit | undefined): Record<string, unknown> {
	return JSON.parse(String(init?.body)) as Record<string, unknown>;
}

function headersOf(init: RequestInit | undefined): Record<string, string> {
	return (init?.headers ?? {}) as Record<string, string>;
}

const OGG = () => Buffer.from([0x4f, 0x67, 0x67, 0x53, 0x00, 0x01, 0x02]);

describe("formatForContentType (A15.5)", () => {
	it("maps audio/ogg to ogg and audio/mp4 to mp4, ignoring parameters", () => {
		expect(formatForContentType("audio/ogg")).toBe("ogg");
		expect(formatForContentType("audio/ogg; codecs=opus")).toBe("ogg");
		expect(formatForContentType("audio/mp4")).toBe("mp4");
		expect(formatForContentType("audio/mp4; codecs=mp4a.40.2")).toBe("mp4");
	});
	it("refuses anything else", () => {
		expect(formatForContentType("audio/webm")).toBeUndefined();
		expect(formatForContentType("application/json")).toBeUndefined();
		expect(formatForContentType("")).toBeUndefined();
	});
});

describe("transcribeAudio — the provider chain (A15.5)", () => {
	it("uses OpenRouter first, pinned to DeepInfra with no fallbacks, with base64 audio and the format", async () => {
		const { impl, calls } = recorder([() => jsonResponse({ text: "hello there" })]);
		const result = await transcribeAudio(OGG(), "ogg", deps(impl));
		expect(result).toEqual({ text: "hello there", model: "openai/whisper-large-v3-turbo", usedFallback: false });
		expect(calls).toHaveLength(1);
		expect(calls[0]!.url).toBe("https://openrouter.ai/api/v1/audio/transcriptions");
		expect(headersOf(calls[0]!.init).Authorization).toBe(`Bearer ${OR_KEY}`);
		expect(bodyOf(calls[0]!.init)).toEqual({
			model: "openai/whisper-large-v3-turbo",
			input_audio: { data: OGG().toString("base64"), format: "ogg" },
			provider: { order: ["DeepInfra"], allow_fallbacks: false },
		});
	});

	it("falls to the local Parakeet ASR as multipart with language=en and a matching file name", async () => {
		const { impl, calls } = recorder([
			() => new Response("boom", { status: 500 }),
			() => jsonResponse({ text: "local answer" }),
		]);
		const result = await transcribeAudio(OGG(), "ogg", deps(impl));
		expect(result).toEqual({ text: "local answer", model: "parakeet-v3-local", usedFallback: true });
		expect(calls).toHaveLength(2);
		expect(calls[1]!.url).toBe("http://127.0.0.1:9000/asr");
		const form = calls[1]!.init?.body as FormData;
		expect(form.get("language")).toBe("en");
		const file = form.get("audio_file") as File;
		expect(file.name).toBe("audio.ogg");
	});

	it("falls to OpenAI gpt-transcribe last, as multipart with the model", async () => {
		const { impl, calls } = recorder([
			() => new Response("no", { status: 502 }),
			() => new Response("no", { status: 500 }),
			() => new Response("openai answer", { status: 200, headers: { "content-type": "text/plain" } }),
		]);
		const result = await transcribeAudio(OGG(), "mp4", deps(impl));
		expect(result).toEqual({ text: "openai answer", model: "gpt-transcribe", usedFallback: true });
		expect(calls[2]!.url).toBe("https://api.openai.com/v1/audio/transcriptions");
		expect(headersOf(calls[2]!.init).Authorization).toBe(`Bearer ${OA_KEY}`);
		const form = calls[2]!.init?.body as FormData;
		expect(form.get("model")).toBe("gpt-transcribe");
		expect((form.get("file") as File).name).toBe("audio.mp4");
	});

	it("skips OpenRouter when no key is configured and uses the local ASR", async () => {
		const { impl, calls } = recorder([() => jsonResponse({ text: "local only" })]);
		const result = await transcribeAudio(OGG(), "ogg", deps(impl, { openrouterApiKey: "" }));
		expect(result.text).toBe("local only");
		expect(calls).toHaveLength(1);
		expect(calls[0]!.url).toBe("http://127.0.0.1:9000/asr");
	});

	it("treats an empty provider transcript as a failure and moves on", async () => {
		const { impl, calls } = recorder([
			() => jsonResponse({ text: "   " }),
			() => jsonResponse({ text: "recovered" }),
		]);
		expect((await transcribeAudio(OGG(), "ogg", deps(impl))).text).toBe("recovered");
		expect(calls).toHaveLength(2);
	});

	it("VOICE_FAILED when a key is configured but every leg fails", async () => {
		const { impl } = recorder([
			() => new Response("no", { status: 500 }),
			() => new Response("no", { status: 500 }),
			() => new Response("no", { status: 500 }),
		]);
		await expect(transcribeAudio(OGG(), "ogg", deps(impl))).rejects.toBeInstanceOf(VoiceFailedError);
	});

	it("VOICE_UNAVAILABLE when no provider key is configured and the local ASR does not answer", async () => {
		const { impl } = recorder([() => new Response("no", { status: 503 })]);
		await expect(
			transcribeAudio(OGG(), "ogg", deps(impl, { openrouterApiKey: "", openaiApiKey: "" })),
		).rejects.toBeInstanceOf(VoiceUnavailableError);
	});

	it("never puts a key in a thrown message", async () => {
		const { impl } = recorder([
			() => new Response(`key ${OR_KEY} is bad`, { status: 401 }),
			() => new Response(`key ${OA_KEY} is bad`, { status: 401 }),
			() => new Response(`key ${OA_KEY} is bad`, { status: 401 }),
		]);
		try {
			await transcribeAudio(OGG(), "ogg", deps(impl));
			expect.unreachable("should have thrown");
		} catch (error) {
			const text = `${(error as Error).message} ${JSON.stringify(error)}`;
			expect(text).not.toContain(OR_KEY);
			expect(text).not.toContain(OA_KEY);
		}
	});
});

describe("cleanTranscript — the light clean-up pass (A15.5)", () => {
	it("asks OpenAI gpt-5-nano with the British-spelling editor prompt and returns its text", async () => {
		const { impl, calls } = recorder([() => jsonResponse({ choices: [{ message: { content: "Cleaned text." } }] })]);
		const cleaned = await cleanTranscript("um so colour", deps(impl));
		expect(cleaned).toBe("Cleaned text.");
		expect(calls[0]!.url).toBe("https://api.openai.com/v1/chat/completions");
		const body = bodyOf(calls[0]!.init);
		expect(body.model).toBe("gpt-5-nano");
		const messages = body.messages as Array<{ role: string; content: string }>;
		expect(messages[0]!.role).toBe("system");
		expect(messages[0]!.content).toContain("British");
		expect(messages[1]!.content).toContain("colour");
	});

	it("returns the raw text unchanged when OpenAI is not configured", async () => {
		const { impl, calls } = recorder([]);
		expect(await cleanTranscript("raw text", deps(impl, { openaiApiKey: "" }))).toBe("raw text");
		expect(calls).toHaveLength(0);
	});

	it("returns the raw text when the clean-up request fails", async () => {
		const { impl } = recorder([() => new Response("nope", { status: 500 })]);
		expect(await cleanTranscript("raw text", deps(impl))).toBe("raw text");
	});

	it("returns the raw text when the model answers nothing", async () => {
		const { impl } = recorder([() => jsonResponse({ choices: [{ message: { content: "" } }] })]);
		expect(await cleanTranscript("raw text", deps(impl))).toBe("raw text");
	});
});

/** A fetch that never answers until its signal aborts, then rejects like a real aborted fetch. */
function hanging(init: RequestInit | undefined): Promise<Response> {
	return new Promise((_resolve, reject) => {
		const signal = init?.signal;
		if (!signal) return; // no signal: the chain would hang forever
		if (signal.aborted) return reject(signal.reason);
		signal.addEventListener("abort", () => reject(signal.reason), { once: true });
	});
}

describe("bounded provider timeouts (Correction 1)", () => {
	it("every transcription leg carries an abort signal", async () => {
		const { impl, calls } = recorder([(_url, init) => { expect(init?.signal).toBeInstanceOf(AbortSignal); return jsonResponse({ text: "ok" }); }]);
		await transcribeAudio(OGG(), "ogg", deps(impl));
		expect(calls[0]!.init?.signal).toBeInstanceOf(AbortSignal);
	});

	it("a hung OpenRouter leg times out and the chain moves to the local ASR", async () => {
		const { impl } = recorder([
			(_url, init) => hanging(init),
			() => jsonResponse({ text: "local answer" }),
		]);
		const result = await transcribeAudio(OGG(), "ogg", deps(impl, { timeouts: { sttMs: 20 } }));
		expect(result).toEqual({ text: "local answer", model: "parakeet-v3-local", usedFallback: true });
	});

	it("a hung local ASR leg times out and the chain moves to OpenAI", async () => {
		const { impl } = recorder([
			() => jsonResponse({ text: "" }),
			(_url, init) => hanging(init),
			() => new Response("openai answer", { status: 200, headers: { "content-type": "text/plain" } }),
		]);
		const result = await transcribeAudio(OGG(), "ogg", deps(impl, { timeouts: { localAsrMs: 20 } }));
		expect(result.model).toBe("gpt-transcribe");
	});

	it("a hung clean-up pass times out and the raw text is kept", async () => {
		const { impl } = recorder([(_url, init) => hanging(init)]);
		expect(await cleanTranscript("raw text", deps(impl, { timeouts: { cleanupMs: 20 } }))).toBe("raw text");
	});

	it("a timed-out leg never puts a key in the error", async () => {
		const { impl } = recorder([
			(_url, init) => hanging(init),
			(_url, init) => hanging(init),
			(_url, init) => hanging(init),
		]);
		try {
			await transcribeAudio(OGG(), "ogg", deps(impl, { timeouts: { sttMs: 20, localAsrMs: 20 } }));
			expect.unreachable("should have thrown");
		} catch (error) {
			const text = `${(error as Error).message} ${JSON.stringify(error)}`;
			expect(text).not.toContain(OR_KEY);
			expect(text).not.toContain(OA_KEY);
		}
	});
});
