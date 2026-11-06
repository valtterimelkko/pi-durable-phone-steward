import { describe, expect, it } from "vitest";
import { SERVER_NOTE_MARKER } from "../src/server-note.ts";
import { originOfEntry } from "../src/turn-origin.ts";

// P10-S closure review N2: a fresh start writes root.reset(SERVER_NOTE_MARKER + brief), so classifying a
// reset entry by its text read it as an owner turn. A reset is never the owner's own input: fail closed.
describe("originOfEntry (integration fix, review N2)", () => {
	it("classifies a reset entry as a fresh start whatever its text", () => {
		expect(originOfEntry("pi.reset", `${SERVER_NOTE_MARKER}brief text`)).toBe("fresh_start");
		expect(originOfEntry("pi.reset", "")).toBe("fresh_start");
		expect(originOfEntry("pi.reset", "please clear one cache")).toBe("fresh_start");
	});
	it("classifies a user entry by its marker, as before", () => {
		expect(originOfEntry("pi.user", "[schedule weekly_check 2026-10-09] go")).toBe("weekly_check");
		expect(originOfEntry("pi.user", "please clear one cache")).toBe("owner");
	});
});
