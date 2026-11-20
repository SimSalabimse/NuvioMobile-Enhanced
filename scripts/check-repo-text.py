#!/usr/bin/env python3
"""Hash-compare words against the personal-name list.

docs/release/name-scan-hashes.txt stores SHA-256 digests of lowercased tokens
only. This script never prints a matched word. repo-text.yml is not on beta;
the older scanner read author names out of git history, which would have put
real names into the check. This is that check, reduced to the hash compare.
"""

from __future__ import annotations

import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HASH_FILE = Path(os.environ.get("NUVIO_NAME_HASHES", ROOT / "docs/release/name-scan-hashes.txt"))
WORD = re.compile(r"[^\W\d_](?:[^\W\d_]|['’\-])*", re.UNICODE)


def load_hashes(path: Path) -> set[str]:
    if not path.is_file():
        print(f"name-scan: hash list is missing: {path}", file=sys.stderr)
        sys.exit(2)
    found: set[str] = set()
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip().lower()
        if not line or line.startswith("#"):
            continue
        if not re.fullmatch(r"[0-9a-f]{64}", line):
            print("name-scan: a hash-list line is not a SHA-256 hex digest", file=sys.stderr)
            sys.exit(2)
        found.add(line)
    return found


def tokens(text: str) -> list[str]:
    found: list[str] = []
    for line in text.splitlines():
        words = [match.group(0).lower().replace("’", "'") for match in WORD.finditer(line)]
        found.extend(words)
        found.extend(f"{left} {right}" for left, right in zip(words, words[1:]))
    return found


def digest(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def git_output(*args: str) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        return ""
    return result.stdout


def resolve_base() -> str:
    base = (os.environ.get("NUVIO_BASE_SHA") or "").strip()
    if base and set(base) != {"0"}:
        return base
    ref = os.environ.get("NUVIO_BASE_REF") or "beta"
    for candidate in (f"origin/{ref}", ref):
        sha = git_output("rev-parse", "--verify", candidate).strip()
        if sha:
            return sha
    print("name-scan: could not resolve the base revision", file=sys.stderr)
    sys.exit(2)


def added_diff(base: str, head: str) -> str:
    result = subprocess.run(
        ["git", "diff", "--unified=0", f"{base}...{head}"],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        result = subprocess.run(
            ["git", "diff", "--unified=0", base, head],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
    lines: list[str] = []
    for line in result.stdout.splitlines():
        if line.startswith(("+++", "---", "diff ", "index ", "@@")):
            continue
        if line.startswith("+"):
            lines.append(line[1:])
    return "\n".join(lines)


def sources() -> list[tuple[str, str]]:
    extra = os.environ.get("NUVIO_SCAN_TEXT")
    if extra is not None:
        return [("extra", extra)]
    base = resolve_base()
    head = (os.environ.get("NUVIO_HEAD_SHA") or "HEAD").strip()
    branch = os.environ.get("NUVIO_HEAD_REF") or git_output("rev-parse", "--abbrev-ref", "HEAD")
    return [
        ("diff", added_diff(base, head)),
        ("commits", git_output("log", "--format=%s%n%b", f"{base}..{head}")),
        ("pr-title", os.environ.get("NUVIO_PR_TITLE") or ""),
        ("pr-body", os.environ.get("NUVIO_PR_BODY") or ""),
        ("branch", branch.strip()),
    ]


def main() -> int:
    wanted = load_hashes(HASH_FILE)
    matched: list[str] = []
    for label, text in sources():
        if any(digest(token) in wanted for token in tokens(text)):
            matched.append(label)
    if matched:
        print("name-scan: a listed personal-name hash matched in: " + ", ".join(matched))
        return 1
    print(f"name-scan: no listed hash matched ({len(wanted)} hashes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
