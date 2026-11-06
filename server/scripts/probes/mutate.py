"""Shared mutation runner: python3 mutate.py <file> <patch.json>

patch.json: [{"old": "...", "new": "..."}] — each old must occur exactly once."""
import json
import sys

reverse = "--reverse" in sys.argv
args = [a for a in sys.argv[1:] if a != "--reverse"]
path = args[0]
patches = json.loads(open(args[1]).read())
src = open(path).read()
if reverse:
    for patch in patches:
        src = src.replace(patch["new"], patch["old"])
    open(path, "w").write(src)
    sys.exit(0)
for patch in patches:
    count = patch.get("count", 1)
    actual = src.count(patch["old"])
    if (count == "all" and actual == 0) or (count != "all" and actual != count):
        print(f"ANCHOR-COUNT {actual} for {path}: {patch['old'][:60]!r}")
        sys.exit(3)
for patch in patches:
    if patch.get("count") == "all":
        src = src.replace(patch["old"], patch["new"])
    else:
        src = src.replace(patch["old"], patch["new"], 1)
open(path, "w").write(src)
