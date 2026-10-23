#!/usr/bin/env python3
"""Turn one changelog.json release entry into Sidestore/GitHub release notes."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def load_entry(path: Path, version: str | None, build: int | None) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    releases = data.get("releases")
    if not isinstance(releases, list) or not releases:
        raise SystemExit("changelog.json has no releases")
    for entry in releases:
        if not isinstance(entry, dict):
            continue
        if build is not None and entry.get("build") == build:
            return entry
        if version is not None and str(entry.get("version")) == version:
            return entry
    raise SystemExit(f"No changelog entry for version={version!r} build={build!r}")


def section(title: str, items: object) -> list[str]:
    if not isinstance(items, list) or not items:
        return []
    lines = [f"{title}:"]
    for item in items:
        text = str(item).strip()
        if text:
            lines.append(f"- {text}")
    return lines


def render(entry: dict, branch: str, sha: str) -> str:
    version = entry.get("version")
    build = entry.get("build")
    header = (
        f"Unsigned sideload IPA {version} ({build}), {branch} {sha}. "
        "Sign with SideStore, AltStore, or TrollStore. No Apple certificate is embedded."
    )
    blocks = [header, ""]
    for title, key in (
        ("New", "new"),
        ("Improved", "improved"),
        ("Fixed", "fixed"),
        ("Changed", "changed"),
    ):
        part = section(title, entry.get(key))
        if part:
            blocks.extend(part)
            blocks.append("")
    return "\n".join(blocks).rstrip() + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--changelog", type=Path, required=True)
    parser.add_argument("--version")
    parser.add_argument("--build", type=int)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.version is None and args.build is None:
        parser.error("pass --version and/or --build")
    entry = load_entry(args.changelog, args.version, args.build)
    text = render(entry, args.branch, args.sha[:8])
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
