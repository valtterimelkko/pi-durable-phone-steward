/** PHONE_STEWARD_TRUSTED_PROXIES reaches the config (Caddy runs in Docker). */
import { afterEach, describe, expect, it } from "vitest";
import { loadConfig } from "../src/config.ts";

const KEY = "PHONE_STEWARD_TRUSTED_PROXIES";
afterEach(() => { delete process.env[KEY]; });

describe("trusted proxies config", () => {
	it("defaults to none (loopback stays trusted inside the helper)", () => {
		delete process.env[KEY];
		expect(loadConfig({ stateDir: "/tmp/x" }).trustedProxies).toEqual([]);
	});
	it("parses the environment list", () => {
		process.env[KEY] = "198.51.100.0/24";
		expect(loadConfig({ stateDir: "/tmp/x" }).trustedProxies).toEqual([{ base: (198 * 2 ** 24 + 51 * 2 ** 16 + 100 * 2 ** 8) >>> 0, mask: 0xffffff00 }]);
	});
	it("refuses to start with a malformed list", () => {
		process.env[KEY] = "198.51.100.0/99";
		expect(() => loadConfig({ stateDir: "/tmp/x" })).toThrow(/TRUSTED_PROXIES/);
	});
});
