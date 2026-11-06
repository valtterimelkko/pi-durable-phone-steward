/**
 * A15.6: read aloud, ported from Pi Web UI's `server/src/tts/speech.ts`. OpenRouter
 * `google/gemini-3.8-flash-lite-tts` answers PCM (the Gemini provider rejects mp3); the
 * server wraps it in a 44-byte WAV header and serves `audio/wav`. The OpenAI
 * `gpt-4o-mini-tts` mp3 leg is the fallback, serving `audio/mpeg`.
 */
import { TTS_MODEL, TTS_OPENAI_MODEL, openaiBaseUrl, openrouterTtsUrl, timeoutsFor, type VoiceDeps } from "./deps.ts";
import { VoiceFailedError, VoiceUnavailableError } from "./stt.ts";

export { VoiceFailedError, VoiceUnavailableError } from "./stt.ts";

/** The 30 Gemini studio voices, in Pi Web UI's order. */
export const GEMINI_VOICES = [
	"Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede",
	"Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba",
	"Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar",
	"Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi",
	"Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafath",
] as const;

export type GeminiVoice = (typeof GEMINI_VOICES)[number];

export const DEFAULT_VOICE: GeminiVoice = "Kore";

/** Saved preferences carry the legacy OpenAI voice names; alias them onto Gemini voices. */
const LEGACY_VOICE_ALIASES: Record<string, GeminiVoice> = {
	alloy: "Kore", ash: "Charon", ballad: "Fenrir", coral: "Leda",
	echo: "Puck", fable: "Aoede", nova: "Sulafath", onyx: "Orus",
	sage: "Algieba", shimmer: "Zephyr", verse: "Iapetus",
	marin: "Laomedeia", cedar: "Enceladus",
};

/** An unknown or missing voice degrades to Kore (A15.6). */
export function resolveVoice(voice: unknown): GeminiVoice {
	if (typeof voice === "string") {
		const lower = voice.toLowerCase();
		const direct = GEMINI_VOICES.find((v) => v.toLowerCase() === lower);
		if (direct) return direct;
		const alias = LEGACY_VOICE_ALIASES[lower];
		if (alias) return alias;
	}
	return DEFAULT_VOICE;
}

/** Wrap raw s16le PCM in a 44-byte RIFF/WAVE header. */
export function pcmToWav(pcm: Buffer, sampleRate: number, channels: number): Buffer {
	const bitsPerSample = 16;
	const blockAlign = (channels * bitsPerSample) / 8;
	const byteRate = sampleRate * blockAlign;
	const header = Buffer.alloc(44);
	header.write("RIFF", 0, "ascii");
	header.writeUInt32LE(36 + pcm.length, 4);
	header.write("WAVE", 8, "ascii");
	header.write("fmt ", 12, "ascii");
	header.writeUInt32LE(16, 16);
	header.writeUInt16LE(1, 20);
	header.writeUInt16LE(channels, 22);
	header.writeUInt32LE(sampleRate, 24);
	header.writeUInt32LE(byteRate, 28);
	header.writeUInt16LE(blockAlign, 32);
	header.writeUInt16LE(bitsPerSample, 34);
	header.write("data", 36, "ascii");
	header.writeUInt32LE(pcm.length, 40);
	return Buffer.concat([header, pcm]);
}

/** `audio/pcm;rate=24000;channels=1` → { rate, channels } with sane defaults. */
export function parsePcmContentType(contentType: string | null): { rate: number; channels: number } {
	const ct = contentType ?? "";
	const rate = /rate=(\d+)/.exec(ct)?.[1];
	const channels = /channels=(\d+)/.exec(ct)?.[1];
	return { rate: rate ? Number(rate) : 24000, channels: channels ? Number(channels) : 1 };
}

export interface SpeechResult {
	audio: Buffer;
	contentType: string;
	model: string;
	usedFallback: boolean;
}

class ProviderError extends Error {
	constructor(message: string) {
		super(message);
		this.name = "ProviderError";
	}
}

async function openrouterSpeech(text: string, voice: GeminiVoice, deps: VoiceDeps): Promise<SpeechResult> {
	if (deps.openrouterApiKey === "") throw new VoiceUnavailableError("OPENROUTER_API_KEY is not configured");
	const response = await deps.fetch(openrouterTtsUrl(deps), {
		method: "POST",
		headers: {
			Authorization: `Bearer ${deps.openrouterApiKey}`,
			"Content-Type": "application/json",
		},
		body: JSON.stringify({
			model: deps.ttsModel ?? TTS_MODEL,
			input: text,
			voice,
			response_format: "pcm",
		}),
		signal: AbortSignal.timeout(timeoutsFor(deps).ttsMs),
	});
	if (!response.ok) throw new ProviderError(`OpenRouter TTS HTTP ${response.status}`);
	const pcm = Buffer.from(await response.arrayBuffer());
	if (pcm.length === 0) throw new ProviderError("OpenRouter TTS returned an empty audio body");
	const { rate, channels } = parsePcmContentType(response.headers.get("content-type"));
	return {
		audio: pcmToWav(pcm, rate, channels),
		contentType: "audio/wav",
		model: deps.ttsModel ?? TTS_MODEL,
		usedFallback: false,
	};
}

async function openaiSpeech(text: string, deps: VoiceDeps): Promise<SpeechResult> {
	if (deps.openaiApiKey === "") throw new VoiceUnavailableError("no TTS provider key is configured");
	const response = await deps.fetch(`${openaiBaseUrl(deps)}/audio/speech`, {
		method: "POST",
		headers: {
			Authorization: `Bearer ${deps.openaiApiKey}`,
			"Content-Type": "application/json",
		},
		body: JSON.stringify({
			model: deps.ttsOpenaiModel ?? TTS_OPENAI_MODEL,
			voice: "alloy",
			input: text,
			response_format: "mp3",
		}),
		signal: AbortSignal.timeout(timeoutsFor(deps).ttsMs),
	});
	if (!response.ok) throw new ProviderError(`OpenAI TTS HTTP ${response.status}`);
	const mp3 = Buffer.from(await response.arrayBuffer());
	if (mp3.length === 0) throw new ProviderError("OpenAI TTS returned an empty audio body");
	return { audio: mp3, contentType: "audio/mpeg", model: deps.ttsOpenaiModel ?? TTS_OPENAI_MODEL, usedFallback: true };
}

/** OpenRouter Gemini first, OpenAI mp3 second. */
export async function synthesizeSpeech(text: string, voice: unknown, deps: VoiceDeps): Promise<SpeechResult> {
	try {
		return await openrouterSpeech(text, resolveVoice(voice), deps);
	} catch (error) {
		if (error instanceof VoiceUnavailableError && deps.openaiApiKey === "") throw error;
		if (!(error instanceof VoiceUnavailableError) && deps.openaiApiKey === "") {
			throw new VoiceFailedError(error instanceof Error ? error.message : "read aloud failed");
		}
	}
	try {
		return await openaiSpeech(text, deps);
	} catch (error) {
		if (deps.openrouterApiKey === "" && deps.openaiApiKey === "") throw new VoiceUnavailableError();
		throw new VoiceFailedError(error instanceof Error ? error.message : "read aloud failed");
	}
}
