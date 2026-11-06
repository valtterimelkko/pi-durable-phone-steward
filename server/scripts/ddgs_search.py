#!/usr/bin/env python3
"""DDGS metasearch for the steward's web_search tool (protocol A10.5).

Reads one JSON object on stdin, {"query": "...", "limit": 5}, and prints a JSON list of
{"title", "url", "snippet"}. The query never touches argv or a shell. No API key: DDGS
aggregates public search engines (the host's web-search skill uses the same library).
"""
import json
import sys


def main() -> int:
    request = json.load(sys.stdin)
    query = str(request.get("query", "")).strip()
    limit = max(1, min(int(request.get("limit", 5)), 10))
    if not query:
        print("[]")
        return 0
    from ddgs import DDGS

    hits = DDGS().text(query, max_results=limit, safesearch="moderate")
    out = [
        {"title": str(h.get("title", "")), "url": str(h.get("href", h.get("url", ""))), "snippet": str(h.get("body", ""))}
        for h in (hits or [])
    ]
    print(json.dumps(out))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:  # the caller shows only this one line
        print(f"ddgs search failed: {type(error).__name__}", file=sys.stderr)
        sys.exit(1)
