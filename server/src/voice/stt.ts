/**
 * A15.5: dictation without the clean-up pass. Ported from Pi Web UI's
 * `server/src/dictation/stt.ts` — OpenRouter `openai/whisper-large-v3-turbo` pinned to
 * DeepInfra (no fallbacks), then the host's local Parakeet ASR, then OpenAI `gpt-transcribe`.
 * A provider answer is used only when it holds text; an empty answer counts as a failure so
 * the next leg runs. Fakes only in tests: `fetch` is injected.
 */
import {
	STT_MODEL,
	STT_OPENAI_MODEL,
	openaiBaseUrl,
	openrouterSttUrl,
	timeoutsFor,
	type VoiceDeps,
} from "./deps.ts";

/** No provider is configured and the local ASR did not answer (HTTP 503 VOICE_UNAVAILABLE). */
export class VoiceUnavailableError extends Error {
	constructor(message = "voice providers are not configured") {
		super(message);
		this.name = "VoiceUnavailableError";
	}
}

/** A provider was available but every leg failed (HTTP 502 VOICE_FAILED). */
export class VoiceFailedError extends Error {
	constructor(message = "voice provider failed") {
		super(message);
		this.name = "VoiceFailedError";
	}
}

export type AudioFormat = "ogg" | "mp4" | "m4a";

/** `audio/ogg; codecs=opus` → `ogg`; `audio/mp4` → `mp4`; anything else → undefined. */
export function formatForContentType(contentType: string): AudioFormat | undefined {
	const mime = contentType.split(";")[0]!.trim().toLowerCase();
	if (mime === "audio/ogg") return "ogg";
	if (mime === "audio/mp4") return "mp4";
	if (mime === "audio/m4a" || mime === "audio/x-m4a") return "m4a";
	return undefined;
}

export interface TranscribeResult {
	text: string;
	model: string;
	usedFallback: boolean;
}

/** A provider body/answer failure that never carries the key (the caller adds the status). */
class ProviderError extends Error {
	constructor(message: string) {
		super(message);
		this.name = "ProviderError";
	}
}

async function openrouterTranscribe(audio: Buffer, format: AudioFormat, deps: VoiceDeps): Promise<TranscribeResult> {
	if (deps.openrouterApiKey === "") throw new VoiceUnavailableError("OPENROUTER_API_KEY is not configured");
	const response = await deps.fetch(openrouterSttUrl(deps), {
		method: "POST",
		headers: {
			Authorization: `Bearer ${deps.openrouterApiKey}`,
			"Content-Type": "application/json",
		},
		body: JSON.stringify({
			model: deps.sttModel ?? STT_MODEL,
			input_audio: { data: audio.toString("base64"), format },
			provider: { order: ["DeepInfra"], allow_fallbacks: false },
		}),
		signal: AbortSignal.timeout(timeoutsFor(deps).sttMs),
	});
	if (!response.ok) throw new ProviderError(`OpenRouter STT HTTP ${response.status}`);
	const payload = (await response.json()) as { text?: string };
	const text = (payload.text ?? "").trim();
	if (text === "") throw new ProviderError("OpenRouter STT returned an empty transcript");
	return { text, model: deps.sttModel ?? STT_MODEL, usedFallback: false };
}

async function localTranscribe(audio: Buffer, format: AudioFormat, deps: VoiceDeps): Promise<TranscribeResult> {
	const form = new FormData();
	form.append("language", "en");
	form.append("audio_file", new Blob([new Uint8Array(audio)], { type: `audio/${format}` }), `audio.${format}`);
	const response = await deps.fetch(deps.localAsrUrl, { method: "POST", body: form, signal: AbortSignal.timeout(timeoutsFor(deps).localAsrMs) });
	if (!response.ok) throw new ProviderError(`local ASR HTTP ${response.status}`);
	const raw = (await response.text()).trim();
	let text = raw;
	try {
		const parsed = JSON.parse(raw) as { text?: string };
		if (typeof parsed.text === "string") text = parsed.text.trim();
	} catch {
		// A plain-text answer is kept as-is.
	}
	if (text === "") throw new ProviderError("local ASR returned an empty transcript");
	return { text, model: "parakeet-v3-local", usedFallback: true };
}

async function openaiTranscribe(audio: Buffer, format: AudioFormat, deps: VoiceDeps): Promise<TranscribeResult> {
	if (deps.openaiApiKey === "") throw new VoiceUnavailableError("OPENAI_API_KEY is not configured");
	const form = new FormData();
	form.append("model", deps.sttOpenaiModel ?? STT_OPENAI_MODEL);
	form.append("response_format", "text");
	form.append("file", new Blob([new Uint8Array(audio)], { type: `audio/${format}` }), `audio.${format}`);
	const response = await deps.fetch(`${openaiBaseUrl(deps)}/audio/transcriptions`, {
		method: "POST",
		headers: { Authorization: `Bearer ${deps.openaiApiKey}` },
		body: form,
		signal: AbortSignal.timeout(timeoutsFor(deps).sttMs),
	});
	if (!response.ok) throw new ProviderError(`OpenAI transcription HTTP ${response.status}`);
	const text = (await response.text()).trim();
	if (text === "") throw new ProviderError("OpenAI transcription returned an empty transcript");
	return { text, model: deps.sttOpenaiModel ?? STT_OPENAI_MODEL, usedFallback: true };
}

/**
 * The chain, in order. Throws [VoiceUnavailableError] when neither key is configured and
 * the local ASR did not answer; [VoiceFailedError] when a configured provider failed.
 */
export async function transcribeAudio(audio: Buffer, format: AudioFormat, deps: VoiceDeps): Promise<TranscribeResult> {
	const legs: Array<() => Promise<TranscribeResult>> = [
		() => openrouterTranscribe(audio, format, deps),
		() => localTranscribe(audio, format, deps),
		() => openaiTranscribe(audio, format, deps),
	];
	let last: unknown;
	for (const leg of legs) {
		try {
			return await leg();
		} catch (error) {
			last = error;
		}
	}
	if (deps.openrouterApiKey === "" && deps.openaiApiKey === "") {
		throw new VoiceUnavailableError();
	}
	throw new VoiceFailedError(last instanceof Error ? last.message : "every transcription provider failed");
}
