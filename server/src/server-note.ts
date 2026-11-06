/**
 * S9 (protocol A9.2): the marker that separates the owner's typed text from the server note
 * in the model's copy of an owner message. Kept apart from `owner-note.ts` so the chat
 * bridge and the CLI formatting can cut the note off without loading the proposal code.
 */

/** The line that separates the owner's text from the server's note in the model's copy. */
export const SERVER_NOTE_MARKER = "\n\n[server note — not typed by the owner and not shown to them]\n";

export function withServerNote(text: string, note: string): string {
	return `${text}${SERVER_NOTE_MARKER}${note}`;
}

/** The owner's own text: everything before the note marker (the text itself when there is none). */
export function stripServerNote(text: string): string {
	const at = text.indexOf(SERVER_NOTE_MARKER);
	return at < 0 ? text : text.slice(0, at);
}
