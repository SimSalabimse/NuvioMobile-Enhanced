#!/usr/bin/env python3
"""Release notes for Nuvio builds, from the hand-written changelog.json.

  release-notes.py [<version>] [--build N]   Print markdown notes (GitHub release body / store.json) for one
                                             build: the given build, or the newest entry of <version>.

changelog.json has one entry per build ("version" + "build"), newest first, written by hand in short,
plain, user-facing wording. Nothing is generated from commits or pull requests, so developer text never
reaches What's new.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHANGELOG = ROOT / "composeApp/src/commonMain/composeResources/files/changelog.json"
VERSION_FILES = [
    (ROOT / "composeApp/Configuration/DesktopVersion.properties", r"^VERSION_NAME\s*=\s*(\S+)"),
    (ROOT / "iosApp/Configuration/Version.xcconfig", r"^MARKETING_VERSION\s*=\s*(\S+)"),
]
SECTIONS = ("new", "improved", "fixed")
TITLES = {"new": "New", "improved": "Improved", "fixed": "Fixed"}


def current_version() -> str:
    for f, pattern in VERSION_FILES:
        if f.exists():
            m = re.search(pattern, f.read_text(), re.M)
            if m:
                return m.group(1).strip()
    raise SystemExit("release-notes.py: could not find the app version; pass it as an argument")


def clean(text: str) -> str:
    """Plain sentence: no markdown escapes, backticks or stray backslashes."""
    text = re.sub(r"\\(.)", r"\1", text).replace("\\", "").replace("`", "").replace("**", "")
    text = re.sub(r"\s+", " ", text).strip().rstrip(".")
    return text[0].upper() + text[1:] + "." if text else ""


def markdown(entry: dict) -> str:
    parts = []
    for s in SECTIONS:
        notes = [clean(n) for n in entry.get(s, []) if n.strip()]
        if notes:
            parts.append(f"### {TITLES[s]}\n" + "\n".join(f"- {n}" for n in notes))
    return "\n\n".join(parts)


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(prog="release-notes.py", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("version", nargs="?")
    p.add_argument("--build", type=int)
    p.add_argument("--changelog", type=Path, default=CHANGELOG)
    a = p.parse_args(argv)
    version = a.version or current_version()
    releases = json.loads(a.changelog.read_text(encoding="utf-8")).get("releases", [])
    entry = next(
        (r for r in releases if r.get("version") == version and (a.build is None or r.get("build") == a.build)),
        None,
    )
    text = markdown(entry) if entry else ""
    if not text:
        print(f"release-notes.py: no changelog entry for {version} {a.build or ''}".rstrip(), file=sys.stderr)
        return 1
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
