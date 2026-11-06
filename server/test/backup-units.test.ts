/** S3: the nightly backup unit files (source of truth in the repo; lanes never install them). */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const read = (name: string): string => readFileSync(fileURLToPath(new URL(`../../systemd/${name}`, import.meta.url)), "utf8");

describe("systemd/phone-steward-backup.{service,timer} (template)", () => {
	const service = read("phone-steward-backup.service");
	const timer = read("phone-steward-backup.timer");

	it("the service is a oneshot that runs bin/steward backup against the state dir", () => {
		expect(service).toMatch(/^Type=oneshot$/m);
		expect(service).toMatch(/^ExecStart=\/opt\/phone-steward\/bin\/steward backup$/m);
		expect(service).toMatch(/^Environment=PHONE_STEWARD_STATE_DIR=\/var\/lib\/phone-steward$/m);
	});

	it("the service is confined and low priority, and never restarts or stops the server", () => {
		expect(service).toMatch(/^NoNewPrivileges=true$/m);
		expect(service).toMatch(/^Nice=\d+$/m);
		const commands = service.split("\n").filter((line) => /^Exec\w+=/.test(line));
		expect(commands).toEqual(["ExecStart=/opt/phone-steward/bin/steward backup"]);
		expect(service).not.toMatch(/^Restart=/m);
	});

	it("the timer runs nightly and catches up after downtime", () => {
		expect(timer).toMatch(/^OnCalendar=\*-\*-\* 03:\d\d:00$/m);
		expect(timer).toMatch(/^Persistent=true$/m);
		expect(timer).toMatch(/^WantedBy=timers\.target$/m);
	});

	it("holds no secret", () => {
		for (const text of [service, timer]) expect(text).not.toMatch(/token|password|api[_-]?key|secret/i);
	});
});
