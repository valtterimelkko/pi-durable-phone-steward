/**
 * Protocol A6: folder scopes are subtrees. A folder entry matches the items directly in
 * it and in every subfolder; the trailing `/` is optional because both sides are
 * normalised before a prefix comparison. Exact matching was the MVP behaviour and it
 * returned no candidates in the first live weekly check .
 */
import { describe, expect, it } from "vitest";
import { inFolderScope, normaliseFolderScope } from "../src/folder-scope.ts";
import { RulesDoc, updateRules } from "../src/documents.ts";
import { openTestHarness } from "./helpers.ts";

describe("normaliseFolderScope (A6)", () => {
	it("adds the trailing slash and keeps case as stored", () => {
		expect(normaliseFolderScope("DCIM/Camera")).toBe("DCIM/Camera/");
		expect(normaliseFolderScope("DCIM/Camera/")).toBe("DCIM/Camera/");
		expect(normaliseFolderScope("Android/media/com.whatsapp/")).toBe("Android/media/com.whatsapp/");
	});

	it("tolerates a leading slash and never returns an empty scope", () => {
		expect(normaliseFolderScope("/DCIM/Camera")).toBe("DCIM/Camera/");
		expect(normaliseFolderScope("/")).toBe("/");
	});
});

describe("inFolderScope (A6 subtree matching)", () => {
	it("DCIM/Camera matches DCIM/Camera/ (the live N5 failure)", () => {
		expect(inFolderScope("DCIM/Camera/", ["DCIM/Camera"])).toBe(true);
		expect(inFolderScope("DCIM/Camera", ["DCIM/Camera/"])).toBe(true);
	});

	it("Android/media/com.whatsapp/ covers a deep WhatsApp media folder", () => {
		expect(
			inFolderScope("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", ["Android/media/com.whatsapp/"]),
		).toBe(true);
		expect(
			inFolderScope("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/VID-1.mp4", [
				"Android/media/com.whatsapp/",
			]),
		).toBe(true);
	});

	it("keeps items outside every scope out, including prefix collisions between siblings", () => {
		expect(inFolderScope("Pictures/Screenshots/", ["DCIM/Camera/"])).toBe(false);
		// A sibling whose name extends the scope must not match.
		expect(inFolderScope("DCIM/Camera2/img.jpg", ["DCIM/Camera/"])).toBe(false);
		expect(inFolderScope("DCIM/", ["DCIM/Camera/"])).toBe(false);
	});

	it("matches any of several scopes", () => {
		expect(inFolderScope("Download/old.apk", ["DCIM/Camera/", "Download/"])).toBe(true);
	});
});

describe("PUT /v1/rules stores folders normalised (A6)", () => {
	it("accepts folders without the trailing slash and stores them with it", async () => {
		const t = await openTestHarness();
		try {
			const updated = await t.commit(async (tx) => {
				const current = await tx.doc(RulesDoc, t.root.id);
				return updateRules(tx, t.root.id, {
					ruleVersion: current.ruleVersion,
					photoWindow: { folders: ["DCIM/Camera", "Pictures/Screenshots/", "Download"] },
				});
			});
			expect(updated.photoWindow.folders).toEqual(["DCIM/Camera/", "Pictures/Screenshots/", "Download/"]);
		} finally {
			await t.close();
		}
	});
});
