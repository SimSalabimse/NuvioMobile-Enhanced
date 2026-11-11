#!/usr/bin/env python3
"""CI check for repeat-offender text in this repo.

Fails when tracked text contains:
  - a personal name taken from git history (two-word author, not a bot)
  - a hardcoded TheIntroDB key
  - a hardcoded addon manifest URL on a host that is not a fixture

Warns, and still exits 0, when a commit whose subject says "fix" does not
touch a test file. The owner is referred to as Simsalabim. This script does
not print a matched personal name.

The fix-commit range is CHECK_REPO_TEXT_BASE..HEAD when that variable is set,
otherwise the first argument, otherwise no range is checked.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEXT_SUFFIXES = {
    ".kt", ".kts", ".swift", ".md", ".txt", ".json", ".yml", ".yaml",
    ".properties", ".xml", ".html", ".sh", ".py", ".gradle",
}
AUTHOR_ALLOW = {"cursor agent"}
ADDON_HOST_OK = re.compile(
    r"^(?:localhost|127\.0\.0\.1|automation\.invalid|example|"
    r".+\.example|example\.com|.+\.example\.com|example\.test|.+\.example\.test)$",
    re.IGNORECASE,
)
MANIFEST_URL = re.compile(
    r"""(?ix)(?:https?|stremio)://([^/\s"'<>]+)[^\s"'<>]*manifest\.json"""
)
KEY_LINE = re.compile(r"theintrodb|the_intro_db|theIntroDb", re.IGNORECASE)
KEY_TOKEN = re.compile(r"""["']([A-Za-z0-9_\-]{20,})["']""")
KEY_PLACEHOLDER = re.compile(
    r"example|placeholder|redacted|changeme|your-key|test-key|sample",
    re.IGNORECASE,
)
# Preference keys and resource names are snake_case. A saved key is a long token
# with a digit, not an identifier.
SNAKE_IDENTIFIER = re.compile(r"[a-z0-9_]+")
FIX_SUBJECT = re.compile(r"(?i)(?:^|[^a-z])fix(?:[^a-z]|$)")
TEST_PATH = re.compile(
    r"(Test\.kt$|Tests\.swift$|commonTest/|androidHostTest/|androidFullHostTest/|"
    r"iosAppTests/|/src/test/)"
)


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True, errors="replace")


def tracked_text_files() -> list[str]:
    files = []
    for path in git("ls-files", "-z").split("\0"):
        if not path:
            continue
        if Path(path).suffix.lower() in TEXT_SUFFIXES or path == "README":
            files.append(path)
    return files


def personal_names() -> list[str]:
    names = []
    seen = set()
    for raw in git("log", "--format=%an").splitlines():
        name = raw.strip()
        if name.lower() in seen:
            continue
        seen.add(name.lower())
        parts = name.split()
        if len(parts) != 2:
            continue
        if not all(re.fullmatch(r"[A-Za-zÀ-ÿ]{2,}", part) for part in parts):
            continue
        if name.lower() in AUTHOR_ALLOW:
            continue
        names.append(name)
    return names


def check_names(files: list[str]) -> int:
    names = personal_names()
    failures = 0
    lowered = [(name, name.lower()) for name in names]
    for path in files:
        text = (ROOT / path).read_text(errors="replace")
        for index, line in enumerate(text.splitlines(), 1):
            folded = line.lower()
            if any(needle in folded for _, needle in lowered):
                print(f"{path}:{index}: personal name from git history")
                failures += 1
    return failures


def addon_host_allowed(host: str) -> bool:
    host = host.split("@")[-1].split(":")[0]
    return bool(ADDON_HOST_OK.fullmatch(host))


def looks_like_saved_key(token: str) -> bool:
    if KEY_PLACEHOLDER.search(token):
        return False
    if SNAKE_IDENTIFIER.fullmatch(token) and "_" in token:
        return False
    return bool(re.search(r"\d", token))


def check_addon_urls(files: list[str]) -> int:
    failures = 0
    for path in files:
        # Vendored Stremio protocol notes. They are not this app's addon list.
        if path.startswith("Docs/Stremio addons refer/"):
            continue
        text = (ROOT / path).read_text(errors="replace")
        for index, line in enumerate(text.splitlines(), 1):
            for match in MANIFEST_URL.finditer(line):
                if addon_host_allowed(match.group(1)):
                    continue
                print(f"{path}:{index}: hardcoded addon URL")
                failures += 1
    return failures


def check_introdb_keys(files: list[str]) -> int:
    failures = 0
    for path in files:
        text = (ROOT / path).read_text(errors="replace")
        for index, line in enumerate(text.splitlines(), 1):
            if not KEY_LINE.search(line):
                continue
            if "http://" in line.lower() or "https://" in line.lower():
                continue
            for token in KEY_TOKEN.findall(line):
                if not looks_like_saved_key(token):
                    continue
                print(f"{path}:{index}: hardcoded TheIntroDB key")
                failures += 1
    return failures


def is_test(path: str) -> bool:
    return bool(TEST_PATH.search(path))


def warn_fix_commits(base: str) -> None:
    if not base:
        print("fix-commit check: no base, skipped")
        return
    try:
        log = git("log", "--format=%H%x09%s", f"{base}..HEAD")
    except subprocess.CalledProcessError:
        print(f"fix-commit check: cannot read {base}..HEAD, skipped")
        return
    for line in log.splitlines():
        sha, _, subject = line.partition("\t")
        if not FIX_SUBJECT.search(subject):
            continue
        files = git("diff-tree", "--no-commit-id", "--name-only", "-r", sha).splitlines()
        if any(is_test(path) for path in files):
            continue
        print(f"warning: {sha[:9]} does not touch a test file: {subject}")


def main() -> int:
    os.chdir(ROOT)
    files = tracked_text_files()
    failures = check_names(files) + check_addon_urls(files) + check_introdb_keys(files)
    base = os.environ.get("CHECK_REPO_TEXT_BASE", "").strip()
    if not base and len(sys.argv) > 1:
        base = sys.argv[1]
    warn_fix_commits(base)
    if failures:
        print(f"{failures} repo-text error(s)")
        return 1
    print("repo text check passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
