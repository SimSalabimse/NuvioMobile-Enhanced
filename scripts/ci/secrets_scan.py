#!/usr/bin/env python3
"""Scan added diff lines for secrets. Matching text is never printed."""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path


PLACEHOLDER = re.compile(
    r"(?i)(example\.|notasecret|placeholder|changeme|dummy|fake[-_ ]?key|redacted|your[_-]?key)"
)
TOKEN_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("private-key", re.compile(r"-----BEGIN (?:[A-Z0-9]+ )?PRIVATE KEY-----")),
    ("aws-access-key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("github-token", re.compile(r"\b(?:ghp_|gho_|ghu_|ghs_|ghr_)[A-Za-z0-9]{20,}\b")),
    ("github-fine-grained", re.compile(r"\bgithub_pat_[A-Za-z0-9_]{20,}\b")),
    ("slack-token", re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}")),
    ("google-api-key", re.compile(r"\bAIza[0-9A-Za-z\-_]{35}\b")),
    ("stripe-live-key", re.compile(r"\bsk_live_[0-9A-Za-z]{16,}\b")),
]
ASSIGNED = re.compile(
    r"""(?ix)
    \b(?:api[_-]?key|client[_-]?secret|secret[_-]?key|access[_-]?token|
        auth[_-]?token|password|torbox[_-]?api[_-]?key|secret)\b
    \s*[=:]\s*(['"])([^'"]{20,})\1
    """
)
QUERY = re.compile(
    r"(?i)[?&](?:api[_-]?key|apikey|token|access[_-]?token|secret|password)=([^&\s'\"]{12,})"
)
URL_CREDENTIALS = re.compile(r"\b[a-z][a-z0-9+.-]*://([^/\s:@]+):([^/\s@]+)@")
TORBOX = re.compile(
    r"""(?i)\btorbox(?:[_-]?(?:api[_-]?)?key)?\b\s*[=:]\s*['\"]?([A-Za-z0-9_\-]{24,})"""
)


def literal_is_secret(value: str) -> bool:
    if any(char in value for char in "${}()"):
        return False
    if PLACEHOLDER.search(value):
        return False
    # A dotted or camel-case name with no digits is code, not a key.
    if re.fullmatch(r"[A-Za-z][A-Za-z0-9_.]*", value) and not re.search(r"\d", value):
        return False
    return True


def git_text(repo: Path, *args: str) -> str:
    proc = subprocess.run(
        ["git", "-C", str(repo), *args],
        text=True,
        capture_output=True,
        check=False,
    )
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        raise SystemExit(1)
    return proc.stdout


def added_lines(diff: str) -> list[tuple[str, str]]:
    path = ""
    rows: list[tuple[str, str]] = []
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            path = line[6:]
        elif line.startswith("+++ /dev/null"):
            path = ""
        elif line.startswith("+") and not line.startswith("+++") and path:
            rows.append((path, line[1:]))
    return rows


def scan(repo: Path, base: str, head: str) -> int:
    diff = git_text(repo, "diff", "--find-renames", "--unified=0", f"{base}...{head}")
    hits: set[tuple[str, str]] = set()
    for path, line in added_lines(diff):
        for name, pattern in TOKEN_PATTERNS:
            if pattern.search(line):
                hits.add((name, path))
        assigned = ASSIGNED.search(line)
        if assigned and literal_is_secret(assigned.group(2)):
            hits.add(("assigned-secret", path))
        query = QUERY.search(line)
        if query and literal_is_secret(query.group(1)):
            hits.add(("secret-query", path))
        creds = URL_CREDENTIALS.search(line)
        if creds and literal_is_secret(creds.group(2)):
            hits.add(("url-credentials", path))
        torbox = TORBOX.search(line)
        if torbox and literal_is_secret(torbox.group(1)):
            hits.add(("torbox-key", path))
    if hits:
        for name, path in sorted(hits):
            print(f"secrets: {name} in {path}")
        return 1
    print("secrets scan: clean")
    return 0


def main() -> None:
    parser = argparse.ArgumentParser(description="Scan a diff for secrets.")
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", default="HEAD")
    args = parser.parse_args()
    raise SystemExit(scan(args.repo, args.base, args.head))


if __name__ == "__main__":
    main()
