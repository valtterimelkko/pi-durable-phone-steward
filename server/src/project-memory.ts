/**
 * The project memory for the state brief (docs/protocol.md §8 "Read"): one plain markdown
 * file under the state directory, `<stateDir>/memory/project.md`, read directly. Absent or
 * unreadable: empty, never an error. Write whatever you want the steward to know about the
 * phone and its upkeep there; the file is capped, so keep it short.
 */
import { readFile } from "node:fs/promises";
import { join } from "node:path";

const MAX_READ_CHARS = 20_000;

/** The path the brief reads, under the state directory. */
export function projectMemoryPath(stateDir: string): string {
	return join(stateDir, "memory", "project.md");
}

export async function readProjectMemory(stateDir: string): Promise<string> {
	try {
		const text = await readFile(projectMemoryPath(stateDir), "utf8");
		return text.length <= MAX_READ_CHARS ? text : text.slice(0, MAX_READ_CHARS);
	} catch {
		return "";
	}
}
