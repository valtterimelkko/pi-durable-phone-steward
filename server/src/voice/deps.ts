/**
 * A15.5/A15.6: the parts of Pi Web UI's voice chain the steward reuses, with every external
 * call injected. Nothing here reads the network directly, so the tests stay offline and no
 * credential can reach a response, a log line or an error.
 *
 * Provider order and models are Pi Web UI's (`server/src/dictation/stt.ts`,
 * `dictation/cleanup.ts`, `tts/speech.ts`); the keys are the steward's own
 * (`OPENROUTER_API_KEY`, `OPENAI_API_KEY`, `LOCAL_ASR_URL`).
 */
export type FetchLike = (input: string | URL, init?: RequestInit) => Promise<Response>;

export interface VoiceDeps {
	fetch: FetchLike;
	openrouterApiKey: string;
	openaiApiKey: string;
	localAsrUrl: string;
	openrouterSttUrl?: string;
	openrouterTtsUrl?: string;
	/** OpenAI API root, default `https://api.openai.com/v1`. */
	openaiBaseUrl?: string;
	sttModel?: string;
	sttOpenaiModel?: string;
	cleanupModel?: string;
	ttsModel?: string;
	ttsOpenaiModel?: string;
	/** Correction 1: bounded provider timeouts; the defaults are `DEFAULT_VOICE_TIMEOUTS`. */
	timeouts?: Partial<VoiceTimeouts>;
}

/** Correction 1: a hung provider must not hold the request open. */
export interface VoiceTimeouts {
	/** The OpenRouter and OpenAI transcription legs: a 5-minute recording. */
	sttMs: number;
	/** The host's local Parakeet ASR. */
	localAsrMs: number;
	/** The light clean-up pass. */
	cleanupMs: number;
	/** Both speech legs. */
	ttsMs: number;
}

export const DEFAULT_VOICE_TIMEOUTS: VoiceTimeouts = {
	sttMs: 90_000,
	localAsrMs: 60_000,
	cleanupMs: 20_000,
	ttsMs: 30_000,
};

export function timeoutsFor(deps: VoiceDeps): VoiceTimeouts {
	return { ...DEFAULT_VOICE_TIMEOUTS, ...(deps.timeouts ?? {}) };
}

export const OPENROUTER_STT_URL = "https://openrouter.ai/api/v1/audio/transcriptions";
export const OPENROUTER_TTS_URL = "https://openrouter.ai/api/v1/audio/speech";
export const OPENAI_BASE_URL = "https://api.openai.com/v1";
export const DEFAULT_LOCAL_ASR_URL = "http://127.0.0.1:9000/asr";

export const STT_MODEL = "openai/whisper-large-v3-turbo";
export const STT_OPENAI_MODEL = "gpt-transcribe";
export const CLEANUP_MODEL = "gpt-5-nano";
export const TTS_MODEL = "google/gemini-3.8-flash-lite-tts";
export const TTS_OPENAI_MODEL = "gpt-4o-mini-tts";

export function openrouterSttUrl(deps: VoiceDeps): string {
	return deps.openrouterSttUrl ?? OPENROUTER_STT_URL;
}
export function openrouterTtsUrl(deps: VoiceDeps): string {
	return deps.openrouterTtsUrl ?? OPENROUTER_TTS_URL;
}
export function openaiBaseUrl(deps: VoiceDeps): string {
	return (deps.openaiBaseUrl ?? OPENAI_BASE_URL).replace(/\/$/, "");
}
