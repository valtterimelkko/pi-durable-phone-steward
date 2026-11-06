#!/usr/bin/env python3
"""Headless Chromium for the steward's web_browse tool (protocol A10.5).

stdin: {"url": "...", "steps": [{"action": "scroll"|"click"|"wait", ...}], "maxChars": 12000}
stdout: {"url", "title", "text", "truncated", "steps"}

Safety, in this process as well as in the caller (defence in depth):
- a fresh browser context per call: no cookies, no storage, no logins, nothing persisted;
- public addresses only: every request's host is resolved and must be global (no loopback,
  private, link-local), so a page cannot send the browser at the VPS or the LAN;
- GET only: every other request method is aborted, so no form can POST;
- downloads are cancelled, and a click on a submit control, a control inside a form, or a
  download link is refused;
- images, media and fonts are not fetched; the page text is read at the end.
"""
import ipaddress
import json
import re
import socket
import sys
from urllib.parse import urlparse

ALLOWED = {"scroll", "click", "wait"}
GOAL_TIMEOUT_MS = 20000
_host_cache = {}


def public_host(host: str) -> bool:
    host = host.strip("[]")
    if host in _host_cache:
        return _host_cache[host]
    ok = False
    try:
        infos = socket.getaddrinfo(host, None)
        addresses = {info[4][0] for info in infos}
        ok = bool(addresses) and all(ipaddress.ip_address(a.split("%")[0]).is_global for a in addresses)
    except (OSError, ValueError):
        ok = False
    _host_cache[host] = ok
    return ok


def collapse(text: str) -> str:
    lines = [re.sub(r"[ \t\r\f\v]+", " ", line).strip() for line in text.splitlines()]
    return re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip()


def main() -> int:
    request = json.load(sys.stdin)
    url = str(request["url"])
    steps = list(request.get("steps", []))
    max_chars = int(request.get("maxChars", 12000))
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or not public_host(parsed.hostname):
        print("refused: not a public http(s) address", file=sys.stderr)
        return 3
    for step in steps:
        if not isinstance(step, dict) or step.get("action") not in ALLOWED:
            print("refused: step not allowed", file=sys.stderr)
            return 3

    from playwright.sync_api import sync_playwright

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True, args=["--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu"])
        try:
            context = browser.new_context(accept_downloads=False, service_workers="block", viewport={"width": 1280, "height": 900})

            def guard(route, req):
                target = urlparse(req.url)
                if req.method != "GET" or target.scheme not in ("http", "https", "data", "blob", "about"):
                    return route.abort()
                if req.resource_type in ("image", "media", "font"):
                    return route.abort()
                if target.scheme in ("http", "https") and not (target.hostname and public_host(target.hostname)):
                    return route.abort()
                return route.continue_()

            context.route("**/*", guard)
            page = context.new_page()
            page.on("download", lambda d: d.cancel())
            page.goto(url, wait_until="domcontentloaded", timeout=GOAL_TIMEOUT_MS)
            done = 0
            for step in steps:
                action = step["action"]
                if action == "scroll":
                    amount = max(1, min(int(step.get("amount", 1)), 10))
                    dy = 800 * amount * (-1 if step.get("direction") == "up" else 1)
                    page.mouse.wheel(0, dy)
                    page.wait_for_timeout(300)
                elif action == "wait":
                    page.wait_for_timeout(max(0, min(int(step.get("ms", 500)), 3000)))
                elif action == "click":
                    target = page.get_by_text(str(step["text"]), exact=False).first
                    unsafe = target.evaluate(
                        """el => {
                            const t = (el.getAttribute('type') || '').toLowerCase();
                            if (el.tagName === 'INPUT' && ['submit','image','button','reset','password','file'].includes(t)) return true;
                            if (el.hasAttribute('download')) return true;
                            const a = el.closest('a');
                            if (a && a.hasAttribute('download')) return true;
                            const f = el.closest('form');
                            return !!f && (el.tagName === 'BUTTON' || el.closest('button') !== null || el.tagName === 'INPUT');
                        }""",
                        timeout=3000,
                    )
                    if unsafe:
                        print("refused: that control belongs to a form or is a download", file=sys.stderr)
                        return 3
                    target.click(timeout=5000)
                    page.wait_for_timeout(400)
                done += 1
            title = page.title()
            body = collapse(page.inner_text("body", timeout=5000))
            truncated = len(body) > max_chars
            print(json.dumps({"url": page.url, "title": title, "text": body[:max_chars], "truncated": truncated, "steps": done}))
            return 0
        finally:
            browser.close()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"browse failed: {type(error).__name__}: {str(error).splitlines()[0][:200] if str(error) else ''}", file=sys.stderr)
        sys.exit(1)
