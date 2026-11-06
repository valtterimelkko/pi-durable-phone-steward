import { describe, expect, it } from "vitest";
import { canonicalJson } from "../src/canonical.ts";

describe("canonicalJson", () => {
	it("sorts keys recursively and drops whitespace", () => {
		expect(canonicalJson({ b: 1, a: { d: 2, c: [3, { z: 1, y: 2 }] } })).toBe(
			`{"a":{"c":[3,{"y":2,"z":1}],"d":2},"b":1}`,
		);
	});

	it("gives equal output for differently ordered but equal objects", () => {
		const a = { status: "ok", data: { x: 1, y: 2 }, error: null };
		const b = { error: null, data: { y: 2, x: 1 }, status: "ok" };
		expect(canonicalJson(a)).toBe(canonicalJson(b));
	});

	it("distinguishes different payloads", () => {
		expect(canonicalJson({ status: "ok", data: {} })).not.toBe(canonicalJson({ status: "error", data: {} }));
	});

	it("handles nested arrays, nulls, booleans and unicode without escaping changes", () => {
		expect(canonicalJson({ list: [null, true, false, "café"], n: 0 })).toBe(
			`{"list":[null,true,false,"café"],"n":0}`,
		);
	});
});
