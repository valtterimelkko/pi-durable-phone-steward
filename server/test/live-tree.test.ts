import { describe, expect, it } from "vitest";
import { MAX_NODE_TEXT, MAX_TREE_NODES, renderTree, sanitiseTree } from "../src/live/tree.ts";

const node = (id: string, extra: Record<string, unknown> = {}) => ({
	id,
	role: "button",
	text: "Clear cache",
	clickable: true,
	editable: false,
	bounds: [0, 0, 100, 50],
	...extra,
});

describe("A10.3 ScreenTree: the server's own limits", () => {
	it("keeps a well-formed tree and renders one line per node", () => {
		const tree = sanitiseTree({ package: "com.android.settings", activity: "Settings", nodes: [node("n1"), node("n2", { text: "Storage", clickable: false })], truncated: false });
		expect(tree.package).toBe("com.android.settings");
		expect(tree.nodes).toHaveLength(2);
		const text = renderTree(tree);
		expect(text).toContain("package: com.android.settings");
		expect(text).toContain('n1 button "Clear cache" [tap]');
		expect(text).toContain('n2 button "Storage"');
	});

	it("shows which nodes scroll", () => {
		const tree = sanitiseTree({ package: "p", nodes: [node("n1", { role: "list", text: undefined, clickable: false, scrollable: true })], truncated: false });
		expect(renderTree(tree)).toContain("n1 list [scroll]");
	});

	it("drops password fields even if the phone sent them", () => {
		const tree = sanitiseTree({
			package: "p",
			nodes: [node("n1"), node("n2", { role: "password", text: "hunter2", editable: true }), node("n3", { password: true, text: "secret" })],
			truncated: false,
		});
		expect(tree.nodes.map((n) => n.id)).toEqual(["n1"]);
		expect(renderTree(tree)).not.toMatch(/hunter2|secret/);
	});

	it("caps the node count and the text length, and says it truncated", () => {
		const many = Array.from({ length: MAX_TREE_NODES + 40 }, (_, i) => node(`n${i}`, { text: "x".repeat(MAX_NODE_TEXT + 50) }));
		const tree = sanitiseTree({ package: "p", nodes: many, truncated: false });
		expect(tree.nodes).toHaveLength(MAX_TREE_NODES);
		expect(tree.truncated).toBe(true);
		expect(tree.nodes[0]!.text!.length).toBeLessThanOrEqual(MAX_NODE_TEXT);
	});

	it("rejects a malformed tree", () => {
		expect(() => sanitiseTree({ nodes: [] })).toThrow(/package/);
		expect(() => sanitiseTree({ package: "p", nodes: "x" })).toThrow(/nodes/);
		expect(() => sanitiseTree(node("n1"))).toThrow();
	});

	it("skips malformed nodes instead of failing the whole observation", () => {
		const tree = sanitiseTree({ package: "p", nodes: [node("n1"), { id: 5 }, null, node("n3")], truncated: false });
		expect(tree.nodes.map((n) => n.id)).toEqual(["n1", "n3"]);
	});
});
