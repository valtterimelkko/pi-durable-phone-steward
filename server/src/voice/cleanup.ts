/**
 * A15.5: the light clean-up pass after dictation, ported from Pi Web UI's
 * `server/src/dictation/cleanup.ts` (OpenAI `gpt-5-nano`). It only ever tidies a
 * transcript; on any failure it returns the raw text unchanged, so a clean-up problem can
 * never lose the owner's words.
 */
import { CLEANUP_MODEL, openaiBaseUrl, timeoutsFor, type VoiceDeps } from "./deps.ts";

const SYSTEM_PROMPT = `You are a transcription editor. Clean up voice transcripts with a LIGHT touch:
1. Fix spelling and grammar mistakes only when they're clearly wrong
2. Convert American spellings to British (colour, organise, etc.)
3. Remove filler words (um, uh, mmm, ooh, aah, etc.)
4. Fix obvious transcription errors
5. Preserve the original language (do not translate)
6. Keep the speaker's authentic voice, quirks and natural speech patterns
   - Do NOT remove sentences or restructure the flow
   - Do NOT replace words just to make it sound more 'proper'
   - Preserve non-native speaker expressions and authentic word choices
   - Keep fragmented sentences if that is how the person speaks
Return ONLY the cleaned text, nothing else.`;

/** Returns the clean-up text, or the raw text when there is no key or the request fails. */
export async function cleanTranscript(rawText: string, deps: VoiceDeps): Promise<string> {
	if (deps.openaiApiKey === "") return rawText;
	try {
		const response = await deps.fetch(`${openaiBaseUrl(deps)}/chat/completions`, {
			method: "POST",
			headers: {
				Authorization: `Bearer ${deps.openaiApiKey}`,
				"Content-Type": "application/json",
			},
			body: JSON.stringify({
				model: deps.cleanupModel ?? CLEANUP_MODEL,
				messages: [
					{ role: "system", content: SYSTEM_PROMPT },
					{ role: "user", content: `Clean up this transcript:\n\n${rawText}` },
				],
				temperature: 0.3,
			}),
			signal: AbortSignal.timeout(timeoutsFor(deps).cleanupMs),
		});
		if (!response.ok) return rawText;
		const payload = (await response.json()) as { choices?: Array<{ message?: { content?: string } }> };
		const cleaned = payload.choices?.[0]?.message?.content ?? "";
		return cleaned.trim() === "" ? rawText : cleaned;
	} catch {
		return rawText;
	}
}
