/**
 * A15.5/A15.6 HTTP surface: `POST /v1/voice/transcribe` and `POST /v1/voice/speak`.
 *
 * The handlers are thin: they read and bound the body, then hand it to the injected chain
 * (`stt.ts`, `cleanup.ts`, `tts.ts`) and map its errors onto the protocol's error shape.
 * They are called from `http.ts` after the bearer-token check, so a request that reaches
 * here is already authenticated as the paired device.
 */
import type { IncomingMessage, ServerResponse } from "node:http";
import { ApiError, badRequest } from "../errors.ts";
import type { StewardConfig } from "../config.ts";
import { DEFAULT_LOCAL_ASR_URL, type FetchLike, type VoiceDeps } from "./deps.ts";
import { VoiceFailedError, VoiceUnavailableError, formatForContentType, transcribeAudio } from "./stt.ts";
import { cleanTranscript } from "./cleanup.ts";
import { synthesizeSpeech } from "./tts.ts";

/** A15.5: the audio body is at most 10 MB. */
export const VOICE_MAX_AUDIO_BYTES = 10 * 1024 * 1024;
/** A15.6: `text` is 1–4000 characters. */
export const VOICE_MAX_SPEAK_CHARS = 4000;

export function voiceDepsFromConfig(config: StewardConfig, fetchImpl: FetchLike = fetch): VoiceDeps {
	return {
		fetch: fetchImpl,
		openrouterApiKey: config.voice.openrouterApiKey,
		openaiApiKey: config.voice.openaiApiKey,
		localAsrUrl: config.voice.localAsrUrl === "" ? DEFAULT_LOCAL_ASR_URL : config.voice.localAsrUrl,
	};
}

/** Reads the request body as raw bytes, refusing anything above `limitBytes` with 413. */
async function readBinaryBody(req: IncomingMessage, limitBytes: number): Promise<Buffer> {
	return new Promise((resolve, reject) => {
		let size = 0;
		const chunks: Buffer[] = [];
		req.on("data", (chunk: Buffer) => {
			size += chunk.length;
			if (size > limitBytes) {
				req.removeAllListeners("data");
				req.resume();
				reject(new ApiError(413, "BODY_TOO_LARGE", "recordings are at most 10 MiB"));
				return;
			}
			chunks.push(chunk);
		});
		req.on("end", () => resolve(Buffer.concat(chunks)));
		req.on("error", reject);
	});
}

async function readJsonBody(req: IncomingMessage, limitBytes: number): Promise<Record<string, unknown>> {
	const raw = (await readBinaryBody(req, limitBytes)).toString("utf8");
	if (raw.trim() === "") return {};
	try {
		const parsed = JSON.parse(raw) as unknown;
		if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("not an object");
		return parsed as Record<string, unknown>;
	} catch {
		throw badRequest("the body must be a JSON object");
	}
}

function sendJson(res: ServerResponse, status: number, body: unknown): void {
	const payload = JSON.stringify(body);
	res.writeHead(status, { "content-type": "application/json; charset=utf-8", "content-length": Buffer.byteLength(payload) });
	res.end(payload);
}

function sendAudio(res: ServerResponse, status: number, contentType: string, audio: Buffer): void {
	res.writeHead(status, { "content-type": contentType, "content-length": audio.length });
	res.end(audio);
}

/** A15.5: dictation — the audio body, the provider chain, then the light clean-up pass. */
export async function handleVoiceTranscribe(req: IncomingMessage, res: ServerResponse, deps: VoiceDeps): Promise<void> {
	const format = formatForContentType(String(req.headers["content-type"] ?? ""));
	if (format === undefined) throw badRequest("send the recording as audio/ogg or audio/mp4");
	const audio = await readBinaryBody(req, VOICE_MAX_AUDIO_BYTES);
	if (audio.length === 0) throw badRequest("the recording is empty");
	try {
		const result = await transcribeAudio(audio, format, deps);
		const text = await cleanTranscript(result.text, deps);
		sendJson(res, 200, { text });
	} catch (error) {
		if (error instanceof VoiceUnavailableError) {
			throw new ApiError(503, "VOICE_UNAVAILABLE", "dictation is not configured", 30);
		}
		if (error instanceof VoiceFailedError) throw new ApiError(502, "VOICE_FAILED", "dictation failed");
		throw error;
	}
}

/** A15.6: read aloud — 1–4000 characters, WAV from the primary and mp3 from the fallback. */
export async function handleVoiceSpeak(req: IncomingMessage, res: ServerResponse, deps: VoiceDeps): Promise<void> {
	const body = await readJsonBody(req, 64 * 1024);
	const text = body.text;
	if (typeof text !== "string" || text.length < 1 || text.length > VOICE_MAX_SPEAK_CHARS) {
		throw badRequest(`text must be 1–${VOICE_MAX_SPEAK_CHARS} characters`);
	}
	try {
		const result = await synthesizeSpeech(text, body.voice, deps);
		sendAudio(res, 200, result.contentType, result.audio);
	} catch (error) {
		if (error instanceof VoiceUnavailableError) {
			throw new ApiError(503, "VOICE_UNAVAILABLE", "read aloud is not configured", 30);
		}
		if (error instanceof VoiceFailedError) throw new ApiError(502, "VOICE_FAILED", "read aloud failed");
		throw error;
	}
}
