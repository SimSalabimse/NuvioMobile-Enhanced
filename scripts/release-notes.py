#!/usr/bin/env python3
"""Release notes for Nuvio builds, backed by changelog.json.

  release-notes.py <version>                 Print markdown notes for <version> (GitHub release body / store.json).
  release-notes.py generate [options]        Build the changelog.json entry for the current version from merged PR
                                             titles and commits since the last release tag, grouped into New,
                                             Improved and Fixed. Hand-written entries are kept; generated ones are
                                             merged in without duplicates.

generate options:
  --version V        Version to write (default: MARKETING_VERSION from the version config).
  --since REF        Start ref (default: newest tag matching --tag-glob that is an ancestor of HEAD).
  --tag-glob GLOB    Release tag glob (default: sideload-* on Mobile, [0-9]* on Desktop; env NUVIO_RELEASE_TAG_GLOB).
  --changelog PATH   changelog.json path.
  --repo OWNER/NAME  GitHub repo for PR titles/labels via `gh` (optional; falls back to merge commit text).
  --write            Write changelog.json (otherwise print the merged entry as JSON).
  --only-if-missing  Only generate when the version has no hand-written entry yet.

Classification: conventional prefixes (feat -> New; fix/bug/hotfix -> Fixed; perf/refactor/improve/ui/ux/style ->
Improved) or PR labels (feature/enhancement -> New; bug/fix -> Fixed; improvement/performance/ui -> Improved).
Prefixes chore/ci/build/docs/test/release/store/merge/sync are skipped. Add [skip-notes] to a title to skip it.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CHANGELOGS = [
    ROOT / "composeApp/src/commonMain/composeResources/files/changelog.json",
]
VERSION_FILES = [
    (ROOT / "composeApp/Configuration/DesktopVersion.properties", r"^VERSION_NAME\s*=\s*(\S+)"),
    (ROOT / "iosApp/Configuration/Version.xcconfig", r"^MARKETING_VERSION\s*=\s*(\S+)"),
]
SECTIONS = ("new", "improved", "fixed")
TITLES = {"new": "New", "improved": "Improved", "fixed": "Fixed"}

PREFIX_RE = re.compile(r"^(?P<type>[a-zA-Z]+)(?:\((?P<scope>[^)]*)\))?(?P<bang>!)?:\s*(?P<text>.+)$")
TYPE_MAP = {
    "feat": "new", "feature": "new", "add": "new",
    "fix": "fixed", "bug": "fixed", "bugfix": "fixed", "hotfix": "fixed",
    "perf": "improved", "refactor": "improved", "improve": "improved", "improvement": "improved",
    "ui": "improved", "ux": "improved", "style": "improved", "polish": "improved", "update": "improved",
}
SKIP_TYPES = {"chore", "ci", "build", "docs", "doc", "test", "tests", "release", "store", "merge", "sync", "revert", "wip"}
LABEL_MAP = {
    "feature": "new", "enhancement": "new", "new": "new",
    "bug": "fixed", "fix": "fixed", "bugfix": "fixed",
    "improvement": "improved", "performance": "improved", "perf": "improved", "ui": "improved", "ux": "improved",
}
SKIP_LABELS = {"skip-notes", "no-changelog", "chore", "ci", "dependencies"}


def run(*cmd: str, check: bool = True) -> str:
    r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)}: {r.stderr.strip()}")
    return r.stdout


def find_changelog(path: str | None) -> Path:
    if path:
        return Path(path)
    for p in DEFAULT_CHANGELOGS:
        if p.exists():
            return p
    return DEFAULT_CHANGELOGS[0]


def current_version() -> str:
    for f, pattern in VERSION_FILES:
        if f.exists():
            m = re.search(pattern, f.read_text(), re.M)
            if m:
                return m.group(1).strip()
    raise SystemExit("release-notes.py: could not find the app version; pass --version")


def default_tag_glob() -> str:
    env = os.environ.get("NUVIO_RELEASE_TAG_GLOB")
    if env:
        return env
    if (ROOT / "composeApp/Configuration/DesktopVersion.properties").exists():
        return "[0-9]*"
    return "sideload-*"


def load(path: Path) -> dict:
    if not path.exists():
        return {"releases": []}
    return json.loads(path.read_text(encoding="utf-8"))


def clean(text: str) -> str:
    """Plain sentence: no markdown escapes, backticks, stray backslashes or trailing PR refs."""
    text = re.sub(r"\\(.)", r"\1", text)
    text = text.replace("\\", "").replace("`", "").replace("**", "")
    text = re.sub(r"\s*\((?:#\d+(?:,\s*)?)+\)\s*$", "", text)
    text = re.sub(r"\s+", " ", text).strip().rstrip(".")
    if not text:
        return ""
    return text[0].upper() + text[1:] + "."


def norm(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", text.lower()).strip()


def classify(title: str, labels: list[str]) -> tuple[str | None, str]:
    if "[skip-notes]" in title.lower():
        return None, ""
    lowered = {l.lower() for l in labels}
    if lowered & SKIP_LABELS:
        return None, ""
    m = PREFIX_RE.match(title.strip())
    if m:
        kind = m.group("type").lower()
        text = m.group("text")
        if kind in SKIP_TYPES:
            return None, ""
        section = TYPE_MAP.get(kind)
        for l in lowered:
            if l in LABEL_MAP:
                section = LABEL_MAP[l]
                break
        return section or "improved", text
    for l in lowered:
        if l in LABEL_MAP:
            return LABEL_MAP[l], title
    low = title.lower()
    if low.startswith(("merge ", "bump ", "sync ", "update store", "revert ")):
        return None, ""
    if re.match(r"^(fix|fixed|fixes)\b", low):
        return "fixed", re.sub(r"^(fix|fixed|fixes)\s*:?\s*", "", title, flags=re.I)
    if re.match(r"^(add|added|new)\b", low):
        return "new", title
    return None, ""  # unprefixed, unlabelled: leave it to hand-written notes


def last_tag(glob: str) -> str | None:
    tags = run("git", "tag", "--list", glob, "--sort=-creatordate", "--merged", "HEAD", check=False).split()
    return tags[0] if tags else None


def pr_info(repo: str | None, number: int) -> tuple[str, list[str]] | None:
    if not repo:
        return None
    try:
        out = run("gh", "pr", "view", str(number), "-R", repo, "--json", "title,labels")
        d = json.loads(out)
        return d["title"], [l["name"] for l in d.get("labels", [])]
    except Exception:
        return None


def collect(since: str | None, repo: str | None) -> dict[str, list[str]]:
    rng = f"{since}..HEAD" if since else "HEAD"
    log = run("git", "log", rng, "--format=%H%x1f%P%x1f%s%x1f%b%x1e")
    found: dict[str, list[str]] = {s: [] for s in SECTIONS}
    pr_commits: set[str] = set()
    items: list[tuple[str, list[str]]] = []
    for record in log.split("\x1e"):
        record = record.strip("\n")
        if not record:
            continue
        sha, parents, subject, body = (record.split("\x1f") + ["", "", "", ""])[:4]
        m = re.match(r"^Merge pull request #(\d+) from \S+", subject)
        if m:
            number = int(m.group(1))
            info = pr_info(repo, number)
            if info is None:
                first_body_line = next((l for l in body.splitlines() if l.strip()), "")
                info = (first_body_line, [])
            items.append(info)
            # Commits merged by this PR are covered by its title.
            ps = parents.split()
            if len(ps) == 2:
                pr_commits.update(run("git", "rev-list", f"{ps[0]}..{ps[1]}", check=False).split())
            continue
        if len(parents.split()) > 1 or sha in pr_commits:
            continue
        sq = re.search(r"\(#(\d+)\)\s*$", subject)
        if sq and repo:
            info = pr_info(repo, int(sq.group(1)))
            items.append(info or (subject, []))
        else:
            items.append((subject, []))
    seen: set[str] = set()
    for title, labels in items:
        section, text = classify(title, labels)
        text = clean(text)
        if not section or not text or norm(text) in seen:
            continue
        seen.add(norm(text))
        found[section].append(text)
    return found


def merge_entry(existing: dict | None, generated: dict[str, list[str]], version: str) -> dict:
    entry = dict(existing or {"version": version})
    entry.setdefault("date", dt.date.today().isoformat())
    known = {norm(t) for s in SECTIONS for t in entry.get(s, [])}
    for s in SECTIONS:
        merged = [clean(t) or t for t in entry.get(s, [])]
        for t in generated.get(s, []):
            if norm(t) not in known:
                merged.append(t)
                known.add(norm(t))
        entry[s] = merged
    return {k: entry[k] for k in ["version", "date", *SECTIONS] if k in entry} | {
        k: v for k, v in entry.items() if k not in ("version", "date", *SECTIONS)
    }


def markdown(entry: dict) -> str:
    parts = []
    for s in SECTIONS:
        notes = [clean(n) for n in entry.get(s, []) if n.strip()]
        if notes:
            parts.append(f"### {TITLES[s]}\n" + "\n".join(f"- {n}" for n in notes))
    return "\n\n".join(parts)


def cmd_print(version: str, changelog: Path) -> int:
    data = load(changelog)
    entry = next((r for r in data.get("releases", []) if r.get("version") == version), None)
    if not entry:
        print(f"release-notes.py: no changelog entry for {version}", file=sys.stderr)
        return 1
    text = markdown(entry)
    if not text:
        return 1
    print(text)
    return 0


def cmd_generate(args: argparse.Namespace) -> int:
    changelog = find_changelog(args.changelog)
    version = args.version or current_version()
    data = load(changelog)
    releases = data.setdefault("releases", [])
    idx = next((i for i, r in enumerate(releases) if r.get("version") == version), None)
    existing = releases[idx] if idx is not None else None
    if args.only_if_missing and existing and any(existing.get(s) for s in SECTIONS):
        print(f"release-notes.py: {version} already has hand-written notes; leaving them", file=sys.stderr)
        return 0
    since = args.since or last_tag(args.tag_glob)
    repo = args.repo or os.environ.get("GITHUB_REPOSITORY")
    generated = collect(since, repo)
    entry = merge_entry(existing, generated, version)
    if idx is None:
        releases.insert(0, entry)
    else:
        releases[idx] = entry
    count = sum(len(v) for v in generated.values())
    print(f"release-notes.py: {version}: {count} generated note(s) since {since or 'the first commit'}", file=sys.stderr)
    if args.write:
        changelog.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"release-notes.py: wrote {changelog.relative_to(ROOT) if changelog.is_relative_to(ROOT) else changelog}", file=sys.stderr)
    else:
        print(json.dumps(entry, indent=2, ensure_ascii=False))
    return 0


def main(argv: list[str]) -> int:
    if argv and argv[0] == "generate":
        p = argparse.ArgumentParser(prog="release-notes.py generate")
        p.add_argument("--version")
        p.add_argument("--since")
        p.add_argument("--tag-glob", default=default_tag_glob())
        p.add_argument("--changelog")
        p.add_argument("--repo")
        p.add_argument("--write", action="store_true")
        p.add_argument("--only-if-missing", action="store_true")
        return cmd_generate(p.parse_args(argv[1:]))
    p = argparse.ArgumentParser(prog="release-notes.py", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("version", nargs="?")
    p.add_argument("--changelog")
    a = p.parse_args(argv)
    return cmd_print(a.version or current_version(), find_changelog(a.changelog))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
