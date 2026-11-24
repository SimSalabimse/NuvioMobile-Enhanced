#!/usr/bin/env python3
"""Negative test for the name scan. The dummy token is not a person's name."""

from __future__ import annotations

import hashlib
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCANNER = ROOT / "scripts" / "check-repo-text.py"
HASH_FILE = ROOT / "docs" / "release" / "name-scan-hashes.txt"
TOKEN = "zzzdummytoken"
PAIR = "zzzdummy pairtoken"


def sha(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def scan(text: str, hashes: list[str]) -> int:
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "hashes.txt"
        path.write_text("".join(f"{item}\n" for item in hashes), encoding="utf-8")
        env = os.environ.copy()
        env["NUVIO_NAME_HASHES"] = str(path)
        env["NUVIO_SCAN_TEXT"] = text
        result = subprocess.run(["python3", str(SCANNER)], env=env, text=True, capture_output=True)
        sys.stdout.write(result.stdout)
        sys.stderr.write(result.stderr)
        return result.returncode


def main() -> int:
    listed = [
        line.strip().lower()
        for line in HASH_FILE.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.strip().startswith("#")
    ]
    if sha(TOKEN) in listed or sha(PAIR) in listed:
        print("name-scan negative test: the dummy hash is in the real list", file=sys.stderr)
        return 1
    rejected = scan(f"hello {TOKEN} there", [sha(TOKEN)])
    if rejected == 0:
        print("name-scan negative test: scanner accepted the dummy token", file=sys.stderr)
        return 1
    print("name-scan negative test: scanner rejected zzzdummytoken")
    if scan("hello world", [sha(TOKEN)]) != 0:
        print("name-scan negative test: scanner rejected clean text", file=sys.stderr)
        return 1
    if scan(f"see {PAIR} now", [sha(PAIR)]) == 0:
        print("name-scan negative test: scanner accepted the dummy pair", file=sys.stderr)
        return 1
    if scan(TOKEN, []) != 0:
        print("name-scan negative test: an empty list rejected the dummy", file=sys.stderr)
        return 1
    print("name-scan negative test passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
