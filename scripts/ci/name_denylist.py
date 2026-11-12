#!/usr/bin/env python3
"""Fail when a configured name appears in the diff against a base ref.

The name list is read from NAME_DENYLIST or ~/.config/nuvio/name-denylist.
The list is never printed. A missing or empty list is a failure.
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path


LIST_PATH = Path.home() / ".config" / "nuvio" / "name-denylist"


def load_terms() -> list[str]:
    raw = os.environ.get("NAME_DENYLIST")
    if raw is None:
        if not LIST_PATH.is_file():
            return []
        raw = LIST_PATH.read_text(encoding="utf-8", errors="replace")
    terms: list[str] = []
    for chunk in raw.replace(",", "\n").splitlines():
        term = chunk.strip()
        if not term or term.startswith("#"):
            continue
        terms.append(term)
    return terms


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


def term_pattern(term: str) -> re.Pattern[str]:
    body = re.escape(term)
    if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9 .'_-]*", term):
        return re.compile(rf"(?<![\w]){body}(?![\w])", re.IGNORECASE)
    return re.compile(body, re.IGNORECASE)


def scan(repo: Path, base: str, head: str) -> int:
    terms = load_terms()
    if not terms:
        print("name denylist: list missing or empty", file=sys.stderr)
        return 1
    patterns = [term_pattern(term) for term in terms]
    diff = git_text(repo, "diff", "--find-renames", "--unified=0", f"{base}...{head}")
    messages = git_text(repo, "log", "--format=%s%n%b", f"{base}..{head}")
    hits: set[str] = set()

    def label_for(path: str) -> str:
        if any(pattern.search(path) for pattern in patterns):
            return "file path"
        return f"file {path}"

    for path, line in added_lines(diff):
        if any(pattern.search(line) for pattern in patterns):
            hits.add(label_for(path))
    if any(pattern.search(messages) for pattern in patterns):
        hits.add("commit message")
    ref_name = git_text(repo, "rev-parse", "--abbrev-ref", head).strip()
    if ref_name and ref_name != "HEAD" and any(pattern.search(ref_name) for pattern in patterns):
        hits.add("ref name")
    if hits:
        for hit in sorted(hits):
            print(f"name denylist: match in {hit}")
        return 1
    print("name denylist: clean")
    return 0


def main() -> None:
    parser = argparse.ArgumentParser(description="Scan a diff for denied names.")
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", default="HEAD")
    args = parser.parse_args()
    raise SystemExit(scan(args.repo, args.base, args.head))


if __name__ == "__main__":
    main()
