/**
 * item 7: the pairing rate limit's client key. Behind Caddy (loopback peer)
 * the key is the left-most X-Forwarded-For address; a direct peer — and any forwarded
 * header from a non-loopback peer, which is never trusted — uses the socket address.
 */
import { describe, expect, it } from "vitest";
import { parseTrustedProxies, rateLimitAddress } from "../src/rate-limit-key.ts";

function req(peer: string | undefined, forwarded?: string): { headers: Record<string, string>; socket: { remoteAddress: string | undefined } } {
	return {
		headers: forwarded === undefined ? {} : { "x-forwarded-for": forwarded },
		socket: { remoteAddress: peer },
	};
}

describe("rateLimitAddress", () => {
	it("behind a loopback peer, uses the left-most X-Forwarded-For address", () => {
		expect(rateLimitAddress(req("127.0.0.1", "203.0.113.7, 10.0.0.2"))).toBe("203.0.113.7");
		expect(rateLimitAddress(req("::1", "198.51.100.9"))).toBe("198.51.100.9");
	});

	it("behind a loopback peer without the header, uses the socket address", () => {
		expect(rateLimitAddress(req("127.0.0.1"))).toBe("127.0.0.1");
	});

	it("never trusts X-Forwarded-For from a non-loopback peer", () => {
		expect(rateLimitAddress(req("203.0.113.7", "1.2.3.4"))).toBe("203.0.113.7");
		expect(rateLimitAddress(req("10.0.0.2", "1.2.3.4"))).toBe("10.0.0.2");
	});

	it("handles a blank header and an unknown peer", () => {
		expect(rateLimitAddress(req("127.0.0.1", "  "))).toBe("127.0.0.1");
		expect(rateLimitAddress(req(undefined))).toBe("unknown");
	});
});

describe("rateLimitAddress with configured trusted proxies (Caddy in Docker)", () => {
	const trusted = parseTrustedProxies("198.51.100.0/24, 203.0.113.9");

	it("trusts X-Forwarded-For from a peer inside a configured CIDR", () => {
		expect(rateLimitAddress(req("198.51.100.8", "203.0.113.7"), trusted)).toBe("203.0.113.7");
		expect(rateLimitAddress(req("::ffff:198.51.100.8", "203.0.113.9"), trusted)).toBe("203.0.113.9");
		expect(rateLimitAddress(req("203.0.113.9", "198.51.100.10"), trusted)).toBe("198.51.100.10");
	});

	it("still never trusts a peer outside the configured set", () => {
		expect(rateLimitAddress(req("172.19.0.8", "1.2.3.4"), trusted)).toBe("172.19.0.8");
		expect(rateLimitAddress(req("203.0.113.7", "1.2.3.4"), trusted)).toBe("203.0.113.7");
	});

	it("keeps loopback trusted, and rejects malformed entries", () => {
		expect(rateLimitAddress(req("127.0.0.1", "203.0.113.7"), trusted)).toBe("203.0.113.7");
		expect(() => parseTrustedProxies("198.51.100.0/40")).toThrow();
		expect(() => parseTrustedProxies("not-an-ip")).toThrow();
		expect(parseTrustedProxies(undefined).length).toBe(0);
	});
});
