/**
 * The web tools' address rules (A10.5). The steward runs on a server next
 * to the admin socket, the Docker bridge and cloud metadata, so web tools may only reach
 * PUBLIC addresses: no loopback, private, link-local, CGNAT, multicast or documentation
 * ranges, IPv4 or IPv6, checked on every redirect hop and on the address actually dialled.
 */
import { lookup } from "node:dns/promises";
import { isIP } from "node:net";

/** A request the web tools refuse on purpose (as opposed to one that failed). */
export class WebRefusal extends Error {
	constructor(message: string) {
		super(message);
		this.name = "WebRefusal";
	}
}

function v4Parts(ip: string): number[] | undefined {
	const parts = ip.split(".").map(Number);
	return parts.length === 4 && parts.every((n) => Number.isInteger(n) && n >= 0 && n <= 255) ? parts : undefined;
}

function publicV4(ip: string): boolean {
	const p = v4Parts(ip);
	if (p === undefined) return false;
	const [a, b, c] = p as [number, number, number, number];
	if (a === 0 || a === 10 || a === 127) return false;
	if (a === 100 && b >= 64 && b <= 127) return false; // CGNAT
	if (a === 169 && b === 254) return false; // link-local, cloud metadata
	if (a === 172 && b >= 16 && b <= 31) return false; // private, docker bridges
	if (a === 192 && b === 168) return false;
	if (a === 192 && b === 0 && (c === 0 || c === 2)) return false;
	if (a === 192 && b === 88 && c === 99) return false;
	if (a === 198 && (b === 18 || b === 19)) return false;
	if (a === 198 && b === 51 && c === 100) return false;
	if (a === 203 && b === 0 && c === 113) return false;
	if (a >= 224) return false; // multicast and reserved
	return true;
}

/** Eight 16-bit groups of an IPv6 address, or undefined. */
function v6Groups(ip: string): number[] | undefined {
	let text = ip.toLowerCase();
	const zone = text.indexOf("%");
	if (zone >= 0) text = text.slice(0, zone);
	const dotted = /(\d+\.\d+\.\d+\.\d+)$/.exec(text);
	if (dotted !== null) {
		const p = v4Parts(dotted[1]!);
		if (p === undefined) return undefined;
		text = `${text.slice(0, text.length - dotted[1]!.length)}${((p[0]! << 8) | p[1]!).toString(16)}:${((p[2]! << 8) | p[3]!).toString(16)}`;
	}
	const halves = text.split("::");
	if (halves.length > 2) return undefined;
	const head = halves[0] === "" ? [] : halves[0]!.split(":");
	const tail = halves.length === 2 ? (halves[1] === "" ? [] : halves[1]!.split(":")) : [];
	const missing = 8 - head.length - tail.length;
	if (halves.length === 1 ? head.length !== 8 : missing < 0) return undefined;
	const groups = [...head, ...Array.from({ length: halves.length === 2 ? missing : 0 }, () => "0"), ...tail].map((g) => parseInt(g, 16));
	return groups.length === 8 && groups.every((g) => Number.isInteger(g) && g >= 0 && g <= 0xffff) ? groups : undefined;
}

function publicV6(ip: string): boolean {
	const g = v6Groups(ip);
	if (g === undefined) return false;
	const [g0, g1, g2, g3, g4, g5, g6, g7] = g as [number, number, number, number, number, number, number, number];
	if (g.every((x) => x === 0)) return false; // ::
	if (g0 === 0 && g1 === 0 && g2 === 0 && g3 === 0 && g4 === 0 && g5 === 0 && g6 === 0 && g7 === 1) return false; // ::1
	// IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible: judge the embedded IPv4.
	if (g0 === 0 && g1 === 0 && g2 === 0 && g3 === 0 && g4 === 0 && (g5 === 0xffff || g5 === 0)) {
		return publicV4(`${g6 >> 8}.${g6 & 255}.${g7 >> 8}.${g7 & 255}`);
	}
	// NAT64 (64:ff9b::/96): judge the embedded IPv4.
	if (g0 === 0x64 && g1 === 0xff9b && g2 === 0 && g3 === 0 && g4 === 0 && g5 === 0) {
		return publicV4(`${g6 >> 8}.${g6 & 255}.${g7 >> 8}.${g7 & 255}`);
	}
	if ((g0 & 0xffc0) === 0xfe80) return false; // link-local
	if ((g0 & 0xfe00) === 0xfc00) return false; // unique local
	if ((g0 & 0xff00) === 0xff00) return false; // multicast
	if (g0 === 0x2001 && g1 === 0x0db8) return false; // documentation
	if (g0 === 0x2001 && g1 === 0) return false; // Teredo
	if (g0 === 0x2002) return false; // 6to4
	return true;
}

export function isPublicAddress(ip: string): boolean {
	const version = isIP(ip);
	if (version === 4) return publicV4(ip);
	if (version === 6) return publicV6(ip);
	return false;
}

export type HostResolver = (host: string) => Promise<string[]>;

export const systemResolver: HostResolver = async (host) => (await lookup(host, { all: true })).map((entry) => entry.address);

export type PublicUrlOptions = {
	resolve?: HostResolver;
	/** Hosts exempt from the address rule (tests only). */
	allowHosts?: readonly string[];
	/** Skip the address rule entirely (tests only). */
	allowPrivateHosts?: boolean;
};

/**
 * Parse a URL the model gave and refuse anything but a public http(s) address: no other
 * scheme, no credentials in the URL, and every address the name resolves to must be public.
 */
export async function assertPublicUrl(raw: string, options: PublicUrlOptions = {}): Promise<URL> {
	let url: URL;
	try {
		url = new URL(raw);
	} catch {
		throw new WebRefusal("that is not a valid URL");
	}
	if (url.protocol !== "http:" && url.protocol !== "https:") throw new WebRefusal("only http and https addresses can be opened");
	if (url.username !== "" || url.password !== "") throw new WebRefusal("addresses with a user name or password are not opened");
	const host = url.hostname.replace(/^\[|\]$/g, "");
	if (host === "") throw new WebRefusal("that address has no host");
	if (options.allowPrivateHosts === true || options.allowHosts?.includes(host) === true) return url;
	const addresses = isIP(host) !== 0 ? [host] : await (options.resolve ?? systemResolver)(host).catch(() => []);
	if (addresses.length === 0) throw new WebRefusal("that host name does not resolve");
	if (!addresses.every(isPublicAddress)) throw new WebRefusal("that address is private or internal, so it is not opened");
	return url;
}
