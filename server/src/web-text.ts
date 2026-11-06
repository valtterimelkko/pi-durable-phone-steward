/**
 * HTML to readable text for `web_read` and the browse helper's output (A10.5). Small and
 * dependency-free on purpose: it drops scripts, styles and page chrome, keeps headings,
 * paragraphs and list items, decodes the common entities, and caps the result.
 */

const DROP_BLOCKS = ["script", "style", "noscript", "svg", "template", "head", "nav", "footer", "aside", "form", "iframe", "canvas", "select", "button"];
const BLOCK_BREAKS = /<\/?(?:p|div|section|article|main|header|h[1-6]|ul|ol|table|tr|blockquote|pre|figure|figcaption|dl|dt|dd|hr)\b[^>]*>|<br\s*\/?>/gi;

const NAMED: Record<string, string> = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " ", ndash: "-", mdash: "-", hellip: "...", rsquo: "'", lsquo: "'", rdquo: '"', ldquo: '"', copy: "(c)" };

function decodeEntities(text: string): string {
	return text.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (whole, body: string) => {
		if (body[0] === "#") {
			const code = body[1]!.toLowerCase() === "x" ? parseInt(body.slice(2), 16) : parseInt(body.slice(1), 10);
			return Number.isFinite(code) && code > 0 && code < 0x110000 ? String.fromCodePoint(code) : "";
		}
		return NAMED[body.toLowerCase()] ?? whole;
	});
}

export type ReadableText = { title: string; text: string; truncated: boolean };

export function htmlToText(html: string, maxChars: number): ReadableText {
	const titleMatch = /<title[^>]*>([\s\S]*?)<\/title>/i.exec(html);
	const title = titleMatch === null ? "" : decodeEntities(titleMatch[1]!.replace(/<[^>]*>/g, "")).replace(/\s+/g, " ").trim();
	let body = html.replace(/<!--[\s\S]*?-->/g, " ");
	for (const tag of DROP_BLOCKS) {
		body = body.replace(new RegExp(`<${tag}\\b[^>]*>[\\s\\S]*?<\\/${tag}\\s*>`, "gi"), " ");
		// An unclosed one runs to the end of the document: drop its opening tag only.
		body = body.replace(new RegExp(`<${tag}\\b[^>]*>`, "gi"), " ");
	}
	body = body.replace(/<li\b[^>]*>/gi, "\n- ").replace(BLOCK_BREAKS, "\n").replace(/<\/li\s*>/gi, "\n");
	body = body.replace(/<[^>]*>/g, "");
	const lines = decodeEntities(body)
		.split("\n")
		.map((line) => line.replace(/[ \t\r\f\v]+/g, " ").trim())
		.filter((line, index, all) => line !== "" || (index > 0 && all[index - 1] !== ""));
	const text = lines.join("\n").replace(/\n{3,}/g, "\n\n").trim();
	if (text.length <= maxChars) return { title, text, truncated: false };
	return { title, text: text.slice(0, maxChars).trimEnd(), truncated: true };
}
