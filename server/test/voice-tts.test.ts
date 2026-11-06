/**
 * A15.6 server half — read aloud: OpenRouter Gemini TTS (PCM wrapped as WAV) with the
 * OpenAI mp3 fallback, the voice list and its legacy aliases. Fakes only.
 */
import { describe, expect, it } from "vitest";
import {
	GEMINI_VOICES,
	VoiceFailedError,
	VoiceUnavailableError,
	pcmToWav,
	resolveVoice,
	synthesizeSpeech,
} from "../src/voice/tts.ts";
import type { FetchLike, VoiceDeps } from "../src/voice/deps.ts";

const OR_KEY = "or-invented-key-cccc";
const OA_KEY = "oa-invented-key-dddd";

type Call = { url: string; init: RequestInit | undefined };

function deps(fetchImpl: FetchLike, overrides: Partial<VoiceDeps> = {}): VoiceDeps {
	return { fetch: fetchImpl, openrouterApiKey: OR_KEY, openaiApiKey: OA_KEY, localAsrUrl: "http://127.0.0.1:9000/asr", ...overrides };
}

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

const PCM = Buffer.from([0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08]);

describe("pcmToWav (A15.6)", () => {
	it("writes a 44-byte RIFF/WAVE header with the size, rate and channel fields", () => {
		const wav = pcmToWav(PCM, 24000, 1);
		expect(wav.length).toBe(44 + PCM.length);
		expect(wav.subarray(0, 4).toString("ascii")).toBe("RIFF");
		expect(wav.readUInt32LE(4)).toBe(36 + PCM.length);
		expect(wav.subarray(8, 12).toString("ascii")).toBe("WAVE");
		expect(wav.subarray(36, 40).toString("ascii")).toBe("data");
		expect(wav.readUInt32LE(40)).toBe(PCM.length);
		expect(wav.readUInt32LE(24)).toBe(24000);
		expect(wav.readUInt16LE(22)).toBe(1);
		expect(wav.readUInt16LE(34)).toBe(16);
		expect(wav.subarray(44)).toEqual(PCM);
	});
});

describe("resolveVoice (A15.6)", () => {
	it("defaults to Kore and matches the studio voices case-insensitively", () => {
		expect(resolveVoice(undefined)).toBe("Kore");
		expect(resolveVoice("")).toBe("Kore");
		expect(resolveVoice("nonsense")).toBe("Kore");
		expect(resolveVoice("puck")).toBe("Puck");
		expect(resolveVoice("ZEPHYR")).toBe("Zephyr");
	});
	it("aliases the legacy OpenAI voice names onto Gemini voices", () => {
		expect(resolveVoice("alloy")).toBe("Kore");
		expect(resolveVoice("nova")).toBe("Sulafath");
		expect(resolveVoice("onyx")).toBe("Orus");
	});
	it("exposes the studio voice list", () => {
		expect(GEMINI_VOICES).toContain("Kore");
		expect(GEMINI_VOICES.length).toBeGreaterThanOrEqual(30);
	});
});

describe("synthesizeSpeech (A15.6)", () => {
	it("uses OpenRouter Gemini, requests PCM and wraps it in a WAV using the declared rate", async () => {
		const { impl, calls } = recorder([
			() =>
				new Response(new Uint8Array(PCM), {
					status: 200,
					headers: { "content-type": "audio/pcm;rate=24000;channels=1" },
				}),
		]);
		const result = await synthesizeSpeech("Hello there.", "Kore", deps(impl));
		expect(result.contentType).toBe("audio/wav");
		expect(result.usedFallback).toBe(false);
		expect(result.model).toBe("google/gemini-3.8-flash-lite-tts");
		expect(result.audio.subarray(0, 4).toString("ascii")).toBe("RIFF");
		expect(result.audio.subarray(44)).toEqual(PCM);
		const body = JSON.parse(String(calls[0]!.init?.body)) as Record<string, unknown>;
		expect(body).toEqual({ model: "google/gemini-3.8-flash-lite-tts", input: "Hello there.", voice: "Kore", response_format: "pcm" });
		expect(calls[0]!.url).toBe("https://openrouter.ai/api/v1/audio/speech");
	});

	it("falls back to OpenAI mp3 when OpenRouter fails", async () => {
		const { impl, calls } = recorder([
			() => new Response("no", { status: 500 }),
			() => new Response(new Uint8Array([1, 2, 3]), { status: 200, headers: { "content-type": "audio/mpeg" } }),
		]);
		const result = await synthesizeSpeech("Hello.", "Kore", deps(impl));
		expect(result.contentType).toBe("audio/mpeg");
		expect(result.usedFallback).toBe(true);
		expect(result.model).toBe("gpt-4o-mini-tts");
		expect(result.audio).toEqual(Buffer.from([1, 2, 3]));
		const body = JSON.parse(String(calls[1]!.init?.body)) as Record<string, unknown>;
		expect(body.model).toBe("gpt-4o-mini-tts");
		expect(body.response_format).toBe("mp3");
		expect(calls[1]!.url).toBe("https://api.openai.com/v1/audio/speech");
	});

	it("VOICE_UNAVAILABLE when no provider key is configured", async () => {
		const { impl } = recorder([]);
		await expect(synthesizeSpeech("Hi.", "Kore", deps(impl, { openrouterApiKey: "", openaiApiKey: "" }))).rejects.toBeInstanceOf(
			VoiceUnavailableError,
		);
	});

	it("VOICE_FAILED when OpenRouter fails and there is no OpenAI key", async () => {
		const { impl } = recorder([() => new Response("no", { status: 500 })]);
		await expect(synthesizeSpeech("Hi.", "Kore", deps(impl, { openaiApiKey: "" }))).rejects.toBeInstanceOf(VoiceFailedError);
	});
});

/** A fetch that never answers until its signal aborts, then rejects like a real aborted fetch. */
function hanging(init: RequestInit | undefined): Promise<Response> {
	return new Promise((_resolve, reject) => {
		const signal = init?.signal;
		if (!signal) return;
		if (signal.aborted) return reject(signal.reason);
		signal.addEventListener("abort", () => reject(signal.reason), { once: true });
	});
}

describe("bounded provider timeouts (Correction 1)", () => {
	it("every speech leg carries an abort signal", async () => {
		const { impl, calls } = recorder([
			(_url, init) => { expect(init?.signal).toBeInstanceOf(AbortSignal); return new Response(new Uint8Array(PCM), { status: 200, headers: { "content-type": "audio/pcm;rate=24000;channels=1" } }); },
		]);
		await synthesizeSpeech("Hi.", "Kore", deps(impl));
		expect(calls[0]!.init?.signal).toBeInstanceOf(AbortSignal);
	});

	it("a hung OpenRouter TTS leg times out and the OpenAI fallback answers", async () => {
		const { impl } = recorder([
			(_url, init) => hanging(init),
			() => new Response(new Uint8Array([1, 2, 3]), { status: 200, headers: { "content-type": "audio/mpeg" } }),
		]);
		const result = await synthesizeSpeech("Hi.", "Kore", deps(impl, { timeouts: { ttsMs: 20 } }));
		expect(result.usedFallback).toBe(true);
		expect(result.contentType).toBe("audio/mpeg");
	});

	it("a hung OpenAI fallback times out into VOICE_FAILED", async () => {
		const { impl } = recorder([
			() => new Response("no", { status: 500 }),
			(_url, init) => hanging(init),
		]);
		await expect(synthesizeSpeech("Hi.", "Kore", deps(impl, { timeouts: { ttsMs: 20 } }))).rejects.toBeInstanceOf(VoiceFailedError);
	});
});
