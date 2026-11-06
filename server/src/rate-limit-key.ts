/**
 * The pairing rate limit's client key (correction 04 item 7; ).
 *
 * Behind a trusted reverse proxy the key is the left-most X-Forwarded-For address; a direct
 * peer, or a forwarded header from an untrusted peer, uses the socket address. Loopback is
 * always trusted. In production Caddy runs in Docker and connects from the bridge network,
 * so the unit lists that subnet in PHONE_STEWARD_TRUSTED_PROXIES (IPv4 addresses or CIDRs).
 */

/** One trusted IPv4 range: `base` and `mask` as unsigned 32-bit integers. */
export type TrustedRange = { base: number; mask: number };

function ipv4ToInt(address: string): number | undefined {
	const parts = address.split(".");
	if (parts.length !== 4) return undefined;
	let value = 0;
	for (const part of parts) {
		if (!/^\d{1,3}$/.test(part)) return undefined;
		const octet = Number(part);
		if (octet > 255) return undefined;
		value = value * 256 + octet;
	}
	return value >>> 0;
}

/** Parses `"198.51.100.0/24, 203.0.113.9"`; throws on any malformed entry. Undefined or blank → none. */
export function parseTrustedProxies(spec: string | undefined): TrustedRange[] {
	if (spec === undefined || spec.trim() === "") return [];
	return spec.split(",").map((raw) => {
		const entry = raw.trim();
		const [address, bitsText] = entry.split("/");
		const base = ipv4ToInt(address ?? "");
		const bits = bitsText === undefined ? 32 : Number(bitsText);
		if (base === undefined || !Number.isInteger(bits) || bits < 0 || bits > 32) {
			throw new Error(`PHONE_STEWARD_TRUSTED_PROXIES: invalid entry '${entry}'`);
		}
		const mask = bits === 0 ? 0 : (0xffffffff << (32 - bits)) >>> 0;
		return { base: (base & mask) >>> 0, mask };
	});
}

function isLoopback(address: string): boolean {
	return address === "127.0.0.1" || address === "::1" || address === "::ffff:127.0.0.1";
}

function isTrusted(address: string, trusted: readonly TrustedRange[]): boolean {
	if (isLoopback(address)) return true;
	const v4 = address.startsWith("::ffff:") ? address.slice("::ffff:".length) : address;
	const value = ipv4ToInt(v4);
	if (value === undefined) return false;
	return trusted.some((range) => ((value & range.mask) >>> 0) === range.base);
}

/** Minimal shape of an incoming request; keeps the helper unit-testable. */
export type RequestLike = {
	headers: unknown;
	socket: { remoteAddress?: string | undefined };
};

export function rateLimitAddress(req: RequestLike, trusted: readonly TrustedRange[] = []): string {
	const peer = req.socket.remoteAddress ?? "unknown";
	if (!isTrusted(peer, trusted)) return peer;
	const headers = (req.headers ?? {}) as Record<string, unknown>;
	const forwarded = headers["x-forwarded-for"];
	if (typeof forwarded !== "string") return peer;
	const leftMost = forwarded.split(",")[0]?.trim();
	return leftMost !== undefined && leftMost !== "" ? leftMost : peer;
}
