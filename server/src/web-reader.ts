/**
 * `web_read`'s engine (A10.5): fetch one public page and return its readable text.
 *
 * No cookies, no credentials, no downloads, GET only. Every redirect hop is re-validated, and
 * the address actually dialled is validated in the socket's own DNS lookup, so a name that
 * answers differently the second time (DNS rebinding) cannot reach the VPS's private side.
 * The body read is capped, the whole call has a deadline.
 */
import { request as httpRequest, type IncomingMessage } from "node:http";
import { request as httpsRequest } from "node:https";
import { isIP } from "node:net";
import { createBrotliDecompress, createGunzip, createInflate } from "node:zlib";
import { assertPublicUrl, isPublicAddress, systemResolver, WebRefusal, type HostResolver } from "./web-safety.ts";
import { htmlToText } from "./web-text.ts";

export type ReaderOptions = {
	resolve?: HostResolver;
	/** Tests only: hosts exempt from the address rule. */
	allowHosts?: readonly string[];
	/** Tests only: skip the address rule. */
	allowPrivateHosts?: boolean;
	timeoutMs?: number;
	/** Most raw bytes read from one page. */
	maxBytes?: number;
	/** Most characters of text returned. */
	maxTextChars?: number;
	maxRedirects?: number;
};

export type ReadResult = { url: string; title: string; text: string; truncated: boolean; contentType: string };

const TEXT_TYPES = /^(text\/[a-z0-9.+-]+|application\/(json|ld\+json|xml|xhtml\+xml|rss\+xml|atom\+xml|javascript))\b/i;

export function createWebReader(options: ReaderOptions = {}): { read(url: string): Promise<ReadResult> } {
	const resolve = options.resolve ?? systemResolver;
	const timeoutMs = options.timeoutMs ?? 15_000;
	const maxBytes = options.maxBytes ?? 1_500_000;
	const maxTextChars = options.maxTextChars ?? 12_000;
	const maxRedirects = options.maxRedirects ?? 4;
	const exempt = (host: string): boolean => options.allowPrivateHosts === true || options.allowHosts?.includes(host) === true;

	/** One GET, no redirect following. Resolves with the response; the caller drains it. */
	function get(url: URL, deadline: number): Promise<IncomingMessage> {
		return new Promise((resolveResponse, reject) => {
			const host = url.hostname.replace(/^\[|\]$/g, "");
			const lib = url.protocol === "https:" ? httpsRequest : httpRequest;
			const request = lib(
				url,
				{
					method: "GET",
					headers: {
						"user-agent": "Mozilla/5.0 (compatible; PhoneSteward/1.0)",
						accept: "text/html,application/xhtml+xml,text/plain,application/json;q=0.9,*/*;q=0.1",
						"accept-encoding": "gzip, deflate, br",
					},
					agent: false,
					// The address that is dialled is the address that was checked.
					// Node may ask for every address (`all`, happy eyeballs) or for one.
					lookup: ((name: string, lookupOptions: { all?: boolean }, callback: (...args: unknown[]) => void) => {
						void (async () => {
							try {
								const addresses = isIP(name) !== 0 ? [name] : await resolve(name);
								if (addresses.length === 0) throw new WebRefusal("that host name does not resolve");
								if (!exempt(host) && !addresses.every(isPublicAddress)) throw new WebRefusal("that address is private or internal, so it is not opened");
								const family = (address: string): 4 | 6 => (isIP(address) === 6 ? 6 : 4);
								if (lookupOptions?.all === true) callback(null, addresses.map((address) => ({ address, family: family(address) })));
								else callback(null, addresses[0]!, family(addresses[0]!));
							} catch (error) {
								callback(error);
							}
						})();
					}) as never,
				},
				resolveResponse,
			);
			// Whoever rejects first wins, and the timer's message is authoritative: when the
			// deadline fires we destroy the request and reject with the timed-out error here,
			// so a destroy racing the socket's own "aborted" error can never mask it (the
			// stalled-server test's intent, and the owner-facing text).
			let settled = false;
			const timer = setTimeout(() => {
				if (settled) return;
				settled = true;
				request.destroy();
				reject(new Error("the page took too long to answer (timed out)"));
			}, Math.max(1, deadline - Date.now()));
			request.on("close", () => clearTimeout(timer));
			request.on("error", (error: Error) => {
				if (settled) return;
				settled = true;
				clearTimeout(timer);
				reject(error);
			});
			request.end();
		});
	}

	async function drain(response: IncomingMessage, deadline: number): Promise<{ body: Buffer; cutShort: boolean }> {
		const encoding = String(response.headers["content-encoding"] ?? "").toLowerCase();
		const stream =
			encoding === "gzip" || encoding === "x-gzip" ? response.pipe(createGunzip())
			: encoding === "deflate" ? response.pipe(createInflate())
			: encoding === "br" ? response.pipe(createBrotliDecompress())
			: response;
		return new Promise((resolveBody, reject) => {
			const chunks: Buffer[] = [];
			let size = 0;
			let done = false;
			const finish = (cutShort: boolean): void => {
				if (done) return;
				done = true;
				clearTimeout(timer);
				response.destroy();
				resolveBody({ body: Buffer.concat(chunks), cutShort });
			};
			const timer = setTimeout(() => {
				if (done) return;
				done = true;
				response.destroy();
				reject(new Error("the page took too long to finish (timed out)"));
			}, Math.max(1, deadline - Date.now()));
			stream.on("data", (chunk: Buffer) => {
				chunks.push(chunk);
				size += chunk.length;
				if (size >= maxBytes) finish(true);
			});
			stream.on("end", () => finish(false));
			stream.on("error", (error: Error) => {
				if (done) return;
				done = true;
				clearTimeout(timer);
				// Correction 1 item 2: the overall deadline in `get()` destroys the request; the
				// socket then surfaces a bare "aborted" here, which used to win the race against
				// this timer and reject with the wrong text. An error at the deadline is the
				// deadline: report it as a timeout.
				if (Date.now() >= deadline && /aborted/i.test(error.message)) {
					reject(new Error("the page took too long to finish (timed out)"));
					return;
				}
				reject(error);
			});
		});
	}

	return {
		async read(raw: string): Promise<ReadResult> {
			const deadline = Date.now() + timeoutMs;
			const rule = { resolve, allowHosts: options.allowHosts, allowPrivateHosts: options.allowPrivateHosts };
			let url = await assertPublicUrl(raw, rule);
			for (let hop = 0; ; hop++) {
				const response = await get(url, deadline);
				const status = response.statusCode ?? 0;
				if (status >= 300 && status < 400 && response.headers.location !== undefined) {
					response.resume();
					if (hop >= maxRedirects) throw new Error(`too many redirects (more than ${maxRedirects})`);
					let next: string;
					try {
						next = new URL(response.headers.location, url).toString();
					} catch {
						throw new Error("the page redirected to an address that is not valid");
					}
					url = await assertPublicUrl(next, rule);
					continue;
				}
				if (status < 200 || status >= 300) {
					response.resume();
					throw new Error(`the page answered with HTTP ${status}`);
				}
				const contentType = String(response.headers["content-type"] ?? "text/plain").split(";")[0]!.trim().toLowerCase();
				if (/attachment/i.test(String(response.headers["content-disposition"] ?? "")) || !TEXT_TYPES.test(contentType)) {
					response.resume();
					throw new Error(`that is not readable text (${contentType || "unknown type"}) or it is a download; downloads are not opened`);
				}
				const { body, cutShort } = await drain(response, deadline);
				const charset = /charset=([\w-]+)/i.exec(String(response.headers["content-type"] ?? ""))?.[1] ?? "utf-8";
				let decoded: string;
				try {
					decoded = new TextDecoder(charset).decode(body);
				} catch {
					decoded = new TextDecoder("utf-8").decode(body);
				}
				const isHtml = /html|xml/.test(contentType);
				if (isHtml) {
					const page = htmlToText(decoded, maxTextChars);
					return { url: url.toString(), title: page.title, text: page.text, truncated: page.truncated || cutShort, contentType };
				}
				const text = decoded.replace(/\r/g, "").trim();
				return {
					url: url.toString(),
					title: "",
					text: text.length > maxTextChars ? text.slice(0, maxTextChars) : text,
					truncated: text.length > maxTextChars || cutShort,
					contentType,
				};
			}
		},
	};
}
