#!/usr/bin/env python3
"""Publish live unsigned-IPA build status and serve the builds page.

The build scripts call this process. Percent moves inside the active stage
band by elapsed/(elapsed + recorded baseline). Remaining time is the
unfinished percent divided by the observed percent-per-second rate.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import plistlib
import re
import sys
import threading
import time
import urllib.error
import urllib.request
import zipfile
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from zoneinfo import ZoneInfo
from pathlib import Path
from urllib.parse import urlparse

_SCRIPT_DIR = Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))
import builds_request

STAGES = (
    ("preflight", 0.0, 2.0),
    ("prepare", 2.0, 20.0),
    ("xcodebuild", 20.0, 90.0),
    ("checks", 90.0, 95.0),
    ("zip", 95.0, 100.0),
)
STAGE_NAMES = tuple(name for name, _start, _end in STAGES)
DEFAULT_BASELINE_SECONDS = {
    "preflight": 30.0,
    "prepare": 180.0,
    "xcodebuild": 1800.0,
    "checks": 20.0,
    "zip": 40.0,
}
NOTES_LIMIT = 20000
LOG_LINE_LIMIT = 500
LOG_TAIL_LINES = 80
BUILD_HISTORY_LIMIT = 40
RECENT_BUILD_LIMIT = 8
SECRET_LINE = re.compile(
    r"(?i)(api[_-]?key|client[_-]?secret|secret|token|password|private[_-]?key)\s*[=:]"
)
JWT_LINE = re.compile(r"eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{10,}")
# Log lines only. A Trakt/Simkl assignment or a local.properties value is not
# release-note text, so this stays off the notes path.
ACCOUNT_VALUE_LINE = re.compile(
    r"(?i)(?:local\.properties|(?:trakt|simkl)[a-z0-9_-]*)\s*[=:].*\S"
)
BUILD_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,80}$")
BUILD_LOG_ROUTE = re.compile(r"^/api/builds/([A-Za-z0-9_-]+)/log$")
VERSION_IN_NAME = re.compile(r"(\d+\.\d+\.\d+(?:-[0-9A-Za-z]+)*)")
DESKTOP_KINDS = ("macos", "windows", "linux")
PUBLIC_DOWNLOAD_FIELDS = ("filename", "bytes", "sha256", "version", "commit")
DOWNLOAD_ROUTES = {
    "/download/ipa": "ipa",
    "/download/desktop/macos": "macos",
    "/download/desktop/windows": "windows",
    "/download/desktop/linux": "linux",
}
OLDER_DOWNLOAD_ROUTE = re.compile(r"^/download/older/([A-Za-z0-9._-]+)$")
PRODUCT_IPHONE = "Nuvio for iPhone"
PRODUCT_MAC = "Nuvio for Mac"
PACKAGE_NOTICE = "The package was published and the debug-symbol step failed."
PAPERCLIP_ORIGIN = "http://127.0.0.1:3100"
UPSTREAM_ROUTINE_ID = "7ae6fd17-3a97-4ea7-8b45-c17d38224440"
UPSTREAM_CACHE_SECONDS = 60.0
UPSTREAM_REFUSED = "The merge and build could not be started."
OSLO = ZoneInfo("Europe/Oslo")
PAPERCLIP_COMPANY_ID = "cd341142-29fa-4c5e-91e5-a4ac0b86f3b2"
NUVIO_PROJECT_ID = "5b2fd5e1-2635-4253-8f06-e289c17cc993"
PAPERCLIP_POLL_SECONDS = 15.0
RUN_CAP_SECONDS = 6 * 60 * 60
SUMMARY_LIMIT = 4
STASH_LIMIT = 4
HELD_BACK_LIMIT = 180
EXPECTED_SAMPLE = 20
EXPECTED_MINIMUM = 5
ISSUE_KEY = re.compile(r"^[A-Z][A-Z0-9]+-\d+$")
COMMIT_SUBJECT = re.compile(r"^- ([0-9a-fA-F]{7,40}) (.+) @(\S+)\s*$")
IPA_SEED_COMMIT = "7f6b9bb9"
HELD_BACK_IPA_SEED = (
    "0.5.4 still shows the player API key after a key is saved, and the player is a dark scrim. "
    "Both return in the next IPA."
)
STATUS_RANK = {"in_progress": 0, "in_review": 1, "todo": 2, "blocked": 3, "done": 4}
OPEN_STATUSES = ("in_progress", "in_review", "todo", "blocked")

def repository_root() -> Path:
    return Path(__file__).resolve().parents[1]


def status_directory(explicit: str | None = None) -> Path:
    if explicit:
        return Path(explicit).expanduser().resolve()
    env = os.environ.get("IPA_STATUS_DIR")
    if env:
        return Path(env).expanduser().resolve()
    return repository_root() / "build" / "ipa-live-status"


def iso(timestamp: float) -> str:
    return datetime.fromtimestamp(timestamp, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False
    return True


def redact(text: str) -> str:
    kept = []
    for line in text.splitlines():
        if SECRET_LINE.search(line) or JWT_LINE.search(line):
            kept.append("[redacted]")
        else:
            kept.append(line)
    redacted = "\n".join(kept).strip()
    if len(redacted) > NOTES_LIMIT:
        redacted = redacted[:NOTES_LIMIT].rstrip() + "\n[truncated]"
    return redacted


def redact_log_line(line: str) -> str:
    """Store one build line. Secrets use the release-note redact() path."""
    piece = line.split("\n", 1)[0].split("\r", 1)[0]
    redacted = redact(piece)
    if (
        redacted == "[redacted]"
        or SECRET_LINE.search(piece)
        or JWT_LINE.search(piece)
        or ACCOUNT_VALUE_LINE.search(piece)
    ):
        return "[redacted]"
    if len(redacted) > LOG_LINE_LIMIT:
        return redacted[:LOG_LINE_LIMIT]
    return redacted


def atomic_write(path: Path, text: str) -> None:
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(text, encoding="utf-8")
    os.replace(temporary, path)


@contextmanager
def locked(directory: Path, exclusive: bool = True):
    directory.mkdir(parents=True, exist_ok=True)
    handle = (directory / ".lock").open("a+")
    try:
        import fcntl

        fcntl.flock(handle.fileno(), fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH)
        yield
    finally:
        import fcntl

        fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        handle.close()


def read_json(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return None


def write_json(path: Path, payload: dict) -> None:
    atomic_write(path, json.dumps(payload, indent=2, sort_keys=True) + "\n")


def builds_path(directory: Path) -> Path:
    return directory / "builds.json"


def logs_directory(directory: Path) -> Path:
    return directory / "logs"


def valid_build_id(value: object) -> bool:
    return isinstance(value, str) and BUILD_ID_RE.fullmatch(value) is not None


def log_file(directory: Path, build_id: str) -> Path:
    return logs_directory(directory) / f"{build_id}.log"


def load_builds(directory: Path) -> list[dict]:
    raw = read_json(builds_path(directory)) or {}
    items = raw.get("builds")
    if not isinstance(items, list):
        return []
    return [item for item in items if isinstance(item, dict) and valid_build_id(item.get("id"))]


def store_builds(directory: Path, builds: list[dict]) -> None:
    write_json(builds_path(directory), {"builds": builds[:BUILD_HISTORY_LIMIT]})


@contextmanager
def log_locked(directory: Path):
    logs = logs_directory(directory)
    logs.mkdir(parents=True, exist_ok=True)
    handle = (logs / ".lock").open("a+")
    try:
        import fcntl

        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        yield
    finally:
        import fcntl

        fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        handle.close()


def new_build_id(now: float) -> str:
    stamp = datetime.fromtimestamp(now, timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return f"{stamp}-{os.urandom(4).hex()}"


def open_build_record(directory: Path, session: dict, now: float, platform: str = "ipa") -> str:
    build_id = new_build_id(now)
    builds = load_builds(directory)
    builds.insert(
        0,
        {
            "id": build_id,
            "platform": platform,
            "status": "building",
            "branch": session.get("branch") or "none",
            "commit": session.get("commit") or "none",
            "startedAt": iso(now),
            "finishedAt": None,
        },
    )
    evicted = builds[BUILD_HISTORY_LIMIT:]
    store_builds(directory, builds[:BUILD_HISTORY_LIMIT])
    logs_directory(directory).mkdir(parents=True, exist_ok=True)
    log_file(directory, build_id).touch()
    for old in evicted:
        old_id = old.get("id")
        if valid_build_id(old_id):
            try:
                log_file(directory, str(old_id)).unlink(missing_ok=True)
            except OSError:
                pass
    return build_id


def close_build_record(directory: Path, build_id: object, status: str, now: float) -> None:
    if not valid_build_id(build_id) or status not in ("succeeded", "failed"):
        return
    builds = load_builds(directory)
    for item in builds:
        if item.get("id") != build_id:
            continue
        if item.get("finishedAt"):
            break
        item["status"] = status
        item["finishedAt"] = iso(now)
        break
    store_builds(directory, builds)


def product_label(platform: object) -> str:
    if platform == "ipa":
        return PRODUCT_IPHONE
    if platform == "desktop":
        return PRODUCT_MAC
    return "Nuvio"


def public_build(item: dict) -> dict:
    return {
        "id": item.get("id"),
        "platform": item.get("platform"),
        "product": product_label(item.get("platform")),
        "status": item.get("status"),
        "branch": item.get("branch") or "none",
        "commit": item.get("commit") or "none",
        "startedAt": item.get("startedAt"),
        "finishedAt": item.get("finishedAt"),
    }


def recent_builds(directory: Path, platform: str) -> list[dict]:
    rows = []
    for item in load_builds(directory):
        if item.get("platform") != platform:
            continue
        rows.append(public_build(item))
        if len(rows) >= RECENT_BUILD_LIMIT:
            break
    return rows


def indexed_build(directory: Path, build_id: str) -> dict | None:
    if not valid_build_id(build_id):
        return None
    for item in load_builds(directory):
        if item.get("id") == build_id:
            return item
    return None


def tail_lines(path: Path, limit: int) -> str:
    try:
        size = path.stat().st_size
    except OSError:
        return ""
    if size <= 0:
        return ""
    with path.open("rb") as handle:
        window = 65536
        blob = b""
        position = size
        while position > 0 and blob.count(b"\n") <= limit:
            step = min(window, position)
            position -= step
            handle.seek(position)
            blob = handle.read(step) + blob
            if window < 1024 * 1024:
                window *= 2
    text = blob.decode("utf-8", errors="replace")
    lines = text.splitlines()
    if position > 0 and lines:
        lines = lines[1:]
    if not lines:
        return ""
    return "\n".join(lines[-limit:])


def log_tail(directory: Path, platform: str) -> str:
    for item in load_builds(directory):
        if item.get("platform") != platform:
            continue
        build_id = item.get("id")
        if not valid_build_id(build_id):
            return ""
        return tail_lines(log_file(directory, str(build_id)), LOG_TAIL_LINES)
    return ""


def append_log_line(directory: Path, build_id: str, line: str) -> None:
    if not valid_build_id(build_id):
        return
    stored = redact_log_line(line)
    path = log_file(directory, build_id)
    path.parent.mkdir(parents=True, exist_ok=True)
    data = (stored + "\n").encode("utf-8", errors="replace")
    with log_locked(directory):
        with path.open("ab") as handle:
            handle.write(data)


def attach_platform_logs(payload: dict, directory: Path, platform: str) -> dict:
    enriched = dict(payload)
    if not directory.exists():
        enriched["logTail"] = ""
        enriched["recentBuilds"] = []
        return enriched
    enriched["logTail"] = log_tail(directory, platform)
    enriched["recentBuilds"] = recent_builds(directory, platform)
    return enriched


def session_path(directory: Path) -> Path:
    return directory / "session.json"


def baseline_path(directory: Path) -> Path:
    return directory / "baselines.json"


def public_path(directory: Path) -> Path:
    return directory / "status.json"


def load_baselines(directory: Path) -> dict[str, float]:
    raw = read_json(baseline_path(directory)) or {}
    baselines = dict(DEFAULT_BASELINE_SECONDS)
    for name in STAGE_NAMES:
        value = raw.get(name)
        if isinstance(value, (int, float)) and math.isfinite(value) and value > 0:
            baselines[name] = float(value)
    return baselines


def store_baselines(directory: Path, baselines: dict[str, float]) -> None:
    write_json(baseline_path(directory), {name: baselines[name] for name in STAGE_NAMES})


def band(name: str) -> tuple[float, float]:
    for stage_name, start, end in STAGES:
        if stage_name == name:
            return start, end
    raise KeyError(name)


def intra_fraction(elapsed: float, baseline: float) -> float:
    baseline = max(float(baseline), 1.0)
    elapsed = max(float(elapsed), 0.0)
    # Stays inside the band and keeps moving for as long as the stage runs.
    return elapsed / (elapsed + baseline)


def format_remaining(seconds: float | None) -> str:
    if seconds is None:
        return "none"
    whole = max(0, int(round(seconds)))
    if whole < 60:
        return f"{whole}s"
    minutes, secs = divmod(whole, 60)
    if minutes < 60:
        return f"{minutes}m {secs:02d}s"
    hours, minutes = divmod(minutes, 60)
    return f"{hours}h {minutes:02d}m"


def prior_rate(baselines: dict[str, float]) -> float:
    total = sum(baselines[name] for name in STAGE_NAMES)
    return 100.0 / max(total, 1.0)


def render(session: dict, baselines: dict[str, float], now: float) -> dict:
    branch = session.get("branch") or "none"
    commit = session.get("commit") or "none"
    notes = session.get("releaseNotes") or "none"
    status = session.get("status") or "idle"
    stage = session.get("stage")
    error = session.get("error")
    updated = iso(now)

    if session.get("closed"):
        # Nothing is building. The header stays Idle with an empty stage.
        # The failure or success remains on the history row, not here.
        return {
            "status": "idle",
            "percent": 0,
            "remainingSeconds": None,
            "remainingLabel": "none",
            "branch": branch,
            "commit": commit,
            "releaseNotes": notes,
            "stage": None,
            "error": None,
            "updatedAt": updated,
        }

    build_started = float(session.get("buildStartedAt") or now)
    elapsed_total = max(0.0, now - build_started)

    if status == "queued" or not stage:
        percent = 0.0
        rate = prior_rate(baselines)
        remaining = (100.0 - percent) / rate
    else:
        start, end = band(str(stage))
        stage_started = float(session.get("stageStartedAt") or now)
        elapsed_stage = max(0.0, now - stage_started)
        fraction = intra_fraction(elapsed_stage, baselines[str(stage)])
        percent = start + (end - start) * fraction
        if elapsed_total >= 1.0 and percent > 0.05:
            rate = percent / elapsed_total
        else:
            rate = prior_rate(baselines)
        remaining = max(0.0, 100.0 - percent) / max(rate, 1e-6)

    return {
        "status": status,
        "percent": round(percent, 2),
        "remainingSeconds": int(round(remaining)),
        "remainingLabel": format_remaining(remaining),
        "branch": branch,
        "commit": commit,
        "releaseNotes": notes,
        "stage": stage,
        "error": error,
        "startedAt": started_at_text(session, now),
        "updatedAt": updated,
    }


def started_at_text(raw: dict, now: float | None = None) -> str | None:
    started = raw.get("startedAt")
    if isinstance(started, str) and started:
        return started
    build_started = raw.get("buildStartedAt")
    if isinstance(build_started, str) and build_started:
        return build_started
    if _finite_number(build_started) is not None:
        return iso(float(build_started))
    if now is None:
        return None
    return iso(now)


def failure_view(raw: dict) -> dict | None:
    """Closed failed compile. The idle header stays idle; this is the stats row."""
    if raw.get("status") != "failed":
        return None
    percent = _finite_number(raw.get("frozenPercent"))
    if percent is None:
        percent = _finite_number(raw.get("percent"))
    if percent is None:
        percent = 0.0
    remaining = raw.get("frozenRemainingSeconds")
    if _finite_number(remaining) is None:
        remaining = raw.get("remainingSeconds")
    if _finite_number(remaining) is None:
        remaining_out = None
        label = "none"
    else:
        remaining_out = int(round(float(remaining)))
        stored = raw.get("remainingLabel")
        if isinstance(stored, str) and stored not in ("", "none"):
            label = stored
        else:
            label = format_remaining(float(remaining))
    stage = raw.get("stage")
    if not isinstance(stage, str) or stage in ("", "none"):
        stage = None
    error = raw.get("error") if isinstance(raw.get("error"), str) and raw.get("error") else None
    commit = raw.get("commit") if isinstance(raw.get("commit"), str) else "none"
    return {
        "commit": commit,
        "error": error,
        "percent": round(float(percent), 2),
        "remainingLabel": label,
        "remainingSeconds": remaining_out,
        "stage": stage,
        "startedAt": started_at_text(raw),
        "status": "failed",
    }


def idle_payload(now: float) -> dict:
    return {
        "status": "idle",
        "percent": 0,
        "remainingSeconds": None,
        "remainingLabel": "none",
        "branch": "none",
        "commit": "none",
        "releaseNotes": "none",
        "stage": None,
        "error": None,
        "updatedAt": iso(now),
    }


def publish(directory: Path, session: dict, baselines: dict[str, float], now: float) -> dict:
    payload = render(session, baselines, now)
    write_json(session_path(directory), session)
    write_json(public_path(directory), payload)
    return payload


def read_text(path: str | None) -> str:
    if not path:
        return ""
    try:
        return Path(path).read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        return f"could not read {path}: {exc}"


def notes_from_files(notes_file: str | None, error_file: str | None) -> str:
    error = redact(read_text(error_file))
    notes = redact(read_text(notes_file))
    if error:
        return error
    if notes:
        return notes
    return "(no release notes)"


def command_start(args: argparse.Namespace) -> int:
    directory = status_directory(args.status_dir)
    now = time.time()
    session = {
        "status": "queued",
        "branch": args.branch or "none",
        "commit": args.commit or "none",
        "releaseNotes": notes_from_files(args.notes_file, args.notes_error_file),
        "stage": None,
        "stageStartedAt": None,
        "buildStartedAt": now,
        "parentPid": int(args.parent_pid),
        "error": None,
        "closed": False,
        "frozenPercent": None,
        "frozenRemainingSeconds": None,
    }
    with locked(directory):
        baselines = load_baselines(directory)
        if not baseline_path(directory).exists():
            store_baselines(directory, baselines)
        session["buildId"] = open_build_record(directory, session, now, platform="ipa")
        payload = publish(directory, session, baselines, now)
    print(
        f"ipa-status: {payload['status']} percent={payload['percent']:.2f}",
        file=sys.stderr,
    )
    return 0


def command_stage(args: argparse.Namespace) -> int:
    if args.name not in STAGE_NAMES:
        print(f"unknown stage {args.name}", file=sys.stderr)
        return 2
    directory = status_directory(args.status_dir)
    now = time.time()
    with locked(directory):
        session = read_json(session_path(directory))
        if not session or session.get("closed"):
            print("ipa-status: no active build session", file=sys.stderr)
            return 2
        baselines = load_baselines(directory)
        previous = session.get("stage")
        if previous and previous != args.name:
            started = float(session.get("stageStartedAt") or now)
            elapsed = max(0.0, now - started)
            if elapsed >= 0.5:
                # Recorded baseline is the last completed duration.
                baselines[str(previous)] = elapsed
                store_baselines(directory, baselines)
        if previous != args.name:
            session["stage"] = args.name
            session["stageStartedAt"] = now
        session["status"] = "building"
        payload = publish(directory, session, baselines, now)
    print(
        f"ipa-status: stage {args.name} percent={payload['percent']:.2f} remaining={payload['remainingLabel']}",
        file=sys.stderr,
    )
    return 0


def command_finish(args: argparse.Namespace) -> int:
    directory = status_directory(args.status_dir)
    now = time.time()
    with locked(directory):
        session = read_json(session_path(directory))
        if not session:
            return 0
        if session.get("closed"):
            return 0
        baselines = load_baselines(directory)
        current = render(session, baselines, now)
        previous = session.get("stage")
        if previous and args.status == "succeeded":
            started = float(session.get("stageStartedAt") or now)
            elapsed = max(0.0, now - started)
            if elapsed >= 0.5:
                baselines[str(previous)] = elapsed
                store_baselines(directory, baselines)
        session["status"] = args.status
        session["closed"] = True
        session["error"] = args.message or None
        close_build_record(directory, session.get("buildId"), args.status, now)
        if args.status == "succeeded":
            session["frozenPercent"] = 100.0
            session["frozenRemainingSeconds"] = 0
            session["stage"] = previous
        else:
            session["frozenPercent"] = current["percent"]
            session["frozenRemainingSeconds"] = current["remainingSeconds"]
        payload = publish(directory, session, baselines, now)
    print(
        f"ipa-status: {payload['status']} percent={payload['percent']:.2f}",
        file=sys.stderr,
    )
    return 0


def command_active(args: argparse.Namespace) -> int:
    directory = status_directory(args.status_dir)
    session = read_json(session_path(directory))
    if session and session.get("status") in ("queued", "building") and not session.get("closed"):
        return 0
    return 1


def command_show(args: argparse.Namespace) -> int:
    payload = current_public(status_directory(args.status_dir), time.time())
    sys.stdout.write(json.dumps(payload, indent=2, sort_keys=True) + "\n")
    return 0


def current_public(directory: Path, now: float) -> dict:
    if not directory.exists():
        return attach_platform_logs(idle_payload(now), directory, "ipa")
    with locked(directory, exclusive=False):
        session = read_json(session_path(directory))
        if not session:
            stored = read_json(public_path(directory))
            if stored and stored.get("status"):
                payload = stored
            else:
                payload = idle_payload(now)
        else:
            baselines = load_baselines(directory)
            payload = render(session, baselines, now)
            failure = failure_view(session)
            if failure:
                payload = dict(payload)
                payload["failure"] = failure
        return attach_platform_logs(payload, directory, "ipa")


def desktop_session_path(directory: Path) -> Path:
    return directory / "desktop-session.json"


def downloads_path(directory: Path) -> Path:
    return directory / "downloads.json"


def artifacts_directory(directory: Path) -> Path:
    return directory / "artifacts"


def empty_catalog() -> dict:
    return {"ipa": None, "ipa-debug": None, "desktop": {"macos": None, "windows": None, "linux": None}}


def load_catalog(directory: Path) -> dict:
    raw = read_json(downloads_path(directory)) or {}
    catalog = empty_catalog()
    ipa = raw.get("ipa")
    if isinstance(ipa, dict):
        catalog["ipa"] = ipa
    ipa_debug = raw.get("ipa-debug")
    if isinstance(ipa_debug, dict):
        catalog["ipa-debug"] = ipa_debug
    desktop = raw.get("desktop")
    if isinstance(desktop, dict):
        for kind in DESKTOP_KINDS:
            entry = desktop.get(kind)
            if isinstance(entry, dict):
                catalog["desktop"][kind] = entry
    return catalog


def _finite_number(value: object) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    if not math.isfinite(value):
        return None
    return float(value)


def desktop_public(directory: Path, now: float) -> dict:
    """Read desktop-session.json. A missing file is the idle payload."""
    if not directory.exists():
        return attach_platform_logs(idle_payload(now), directory, "desktop")
    with locked(directory, exclusive=False):
        raw = read_json(desktop_session_path(directory))
        if not raw:
            payload = idle_payload(now)
        else:
            closed = bool(raw.get("closed")) or raw.get("status") in ("succeeded", "failed")
            branch = raw.get("branch") or "none"
            commit = raw.get("commit") or "none"
            notes = raw.get("releaseNotes") or "none"
            stage = raw.get("stage")
            error = raw.get("error")
            updated = raw.get("updatedAt") or iso(now)
            if closed:
                payload = {
                    "status": "idle",
                    "percent": 0,
                    "remainingSeconds": None,
                    "remainingLabel": "none",
                    "branch": branch,
                    "commit": commit,
                    "releaseNotes": notes,
                    "stage": None,
                    "error": None,
                    "updatedAt": updated,
                }
                failure = failure_view(raw)
                if failure:
                    payload["failure"] = failure
            else:
                status = raw.get("status") or "idle"
                if status not in ("idle", "queued", "building"):
                    status = "idle"
                percent = _finite_number(raw.get("percent"))
                if percent is None:
                    percent = 0.0
                remaining_seconds = raw.get("remainingSeconds")
                if _finite_number(remaining_seconds) is None:
                    remaining_out = None
                else:
                    remaining_out = int(round(float(remaining_seconds)))
                payload = {
                    "status": status,
                    "percent": round(percent, 2),
                    "remainingSeconds": remaining_out,
                    "remainingLabel": raw.get("remainingLabel") or "none",
                    "branch": branch,
                    "commit": commit,
                    "releaseNotes": notes,
                    "stage": stage,
                    "error": error,
                    "startedAt": started_at_text(raw) if status in ("building", "queued") else None,
                    "updatedAt": updated,
                }
        return attach_platform_logs(payload, directory, "desktop")


def safe_filename(name: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]", "_", Path(name).name)
    if cleaned in {"", ".", ".."}:
        return "artifact"
    return cleaned


def content_disposition(filename: str) -> str:
    return f'attachment; filename="{safe_filename(filename)}"'


def infer_version(filename: str) -> str:
    match = VERSION_IN_NAME.search(Path(filename).name)
    return match.group(1) if match else "none"


def artifact_file(directory: Path, entry: dict | None) -> Path | None:
    if not entry:
        return None
    name = entry.get("file") or entry.get("filename")
    if not isinstance(name, str) or not name:
        return None
    relative = Path(name)
    if relative.is_absolute() or any(part in {"", ".", ".."} for part in relative.parts):
        return None
    root = artifacts_directory(directory).resolve()
    path = (root / relative).resolve()
    if path != root and root not in path.parents:
        return None
    if not path.is_file():
        return None
    return path


def desktop_manifest_path(directory: Path) -> Path:
    return artifacts_directory(directory) / "desktop" / "manifest.json"


def overlay_published_desktop(directory: Path, catalog: dict) -> dict:
    """Fill desktop slots from the package publisher's manifest.

    A seeded entry with the same sha256 stays, so the download keeps the
    filename and commit recorded for that file. A different published sha,
    or a platform this catalog does not have, replaces the slot.
    """
    raw = read_json(desktop_manifest_path(directory)) or {}
    for kind in DESKTOP_KINDS:
        published = raw.get(kind)
        if not isinstance(published, dict):
            continue
        filename = published.get("filename")
        if not isinstance(filename, str) or filename != Path(filename).name:
            continue
        candidate = {
            "filename": filename,
            "bytes": published.get("bytes"),
            "sha256": published.get("sha256"),
            "version": published.get("version") or "none",
            "commit": published.get("commit") or "none",
            "file": f"desktop/{filename}",
        }
        if artifact_file(directory, candidate) is None:
            continue
        current = catalog["desktop"].get(kind)
        if isinstance(current, dict) and current.get("sha256") == candidate.get("sha256"):
            if artifact_file(directory, current) is not None:
                continue
            current["file"] = candidate["file"]
            continue
        catalog["desktop"][kind] = candidate
    return catalog


def public_download_entry(directory: Path, entry: dict | None) -> dict | None:
    if artifact_file(directory, entry) is None or entry is None:
        return None
    return {field: entry.get(field) for field in PUBLIC_DOWNLOAD_FIELDS}


def short_commit(commit: object) -> str:
    if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-fA-F]{7,40}", commit):
        return ""
    return commit[:8]


def ipa_bundle_versions(path: Path) -> tuple[str | None, str | None]:
    try:
        if not zipfile.is_zipfile(path):
            return None, None
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if not name.startswith("Payload/") or not name.endswith(".app/Info.plist"):
                    continue
                if name.count("/") != 2:
                    continue
                info = plistlib.loads(archive.read(name))
                short = info.get("CFBundleShortVersionString")
                build = info.get("CFBundleVersion")
                short_text = str(short) if short else None
                build_text = str(build) if build else None
                return short_text, build_text
    except (OSError, zipfile.BadZipFile, plistlib.InvalidFileException, ValueError):
        return None, None
    return None, None


def saved_download_name(kind: str, version: object, build: object, commit: object) -> str:
    short = short_commit(commit) or "unknown"
    ver = version if isinstance(version, str) and version and version != "none" else "unknown"
    ver = safe_filename(ver)
    if kind == "ipa":
        if isinstance(build, str) and build and build != "none":
            build_text = safe_filename(build)
            return safe_filename(f"Nuvio-iPhone-{ver}-{build_text}-{short}.ipa")
        return safe_filename(f"Nuvio-iPhone-{ver}-{short}.ipa")
    return safe_filename(f"Nuvio-Mac-{ver}-{short}.dmg")


def copy_line(title: str, version: object, build: object, commit: object) -> str:
    short = short_commit(commit) or "none"
    ver = version if isinstance(version, str) and version and version != "none" else "none"
    if isinstance(build, str) and build and build != "none":
        ver = f"{ver} ({build})"
    return f"{title} {ver} · {short}"


def package_time(directory: Path, commit: object, platform: str, path: Path) -> str:
    if isinstance(commit, str):
        for item in load_builds(directory):
            if item.get("platform") == platform and item.get("commit") == commit and item.get("finishedAt"):
                return str(item["finishedAt"])
    try:
        return iso(path.stat().st_mtime)
    except OSError:
        return ""


def published_failure_notice(directory: Path, commit: object) -> str | None:
    """The current iPhone card explains a package that exists after a failed build.

    Sideload does not need the dSYM. The history row stays failed.
    """
    if not short_commit(commit):
        return None
    for item in load_builds(directory):
        if item.get("platform") != "ipa":
            continue
        if item.get("commit") != commit:
            return None
        if item.get("status") == "failed":
            return PACKAGE_NOTICE
        return None
    return None


def public_named_entry(directory: Path, entry: dict | None, kind: str) -> dict | None:
    path = artifact_file(directory, entry) if entry else None
    if entry is None or path is None:
        return None
    version = entry.get("version")
    build = entry.get("build") if kind == "ipa" else None
    commit = entry.get("commit")
    if kind == "ipa":
        short, bundle_build = ipa_bundle_versions(path)
        if short and (not isinstance(version, str) or not version or version == "none"):
            version = short
        if bundle_build:
            build = bundle_build
    title = PRODUCT_IPHONE if kind == "ipa" else PRODUCT_MAC
    platform = "ipa" if kind == "ipa" else "desktop"
    payload = {
        "filename": entry.get("filename"),
        "bytes": entry.get("bytes") if entry.get("bytes") is not None else path.stat().st_size,
        "sha256": entry.get("sha256"),
        "version": version,
        "commit": commit,
        "product": title,
        "savedName": saved_download_name(kind, version, build, commit),
        "copyText": copy_line(title, version, build, commit),
        "packagedAt": package_time(directory, commit, platform, path),
    }
    if kind == "ipa" and isinstance(build, str) and build:
        payload["build"] = build
        notice = published_failure_notice(directory, commit)
        if notice:
            payload["notice"] = notice
    return payload


def catalog_snapshot(catalog: dict) -> str:
    return json.dumps(catalog, sort_keys=True, separators=(",", ":"))


def reconcile_catalog(directory: Path) -> dict:
    """Make downloads.json name the same desktop file the manifest publishes."""
    if not directory.exists():
        return empty_catalog()
    with locked(directory):
        catalog = load_catalog(directory)
        before = catalog_snapshot(catalog)
        overlay_published_desktop(directory, catalog)
        ipa = catalog.get("ipa")
        if isinstance(ipa, dict):
            path = artifact_file(directory, ipa)
            if path is not None:
                short, build = ipa_bundle_versions(path)
                if build and ipa.get("build") != build:
                    ipa["build"] = build
                if short and (not ipa.get("version") or ipa.get("version") == "none"):
                    ipa["version"] = short
        if catalog_snapshot(catalog) != before:
            write_json(downloads_path(directory), catalog)
        return catalog


def known_catalog_entries(catalog: dict) -> list[dict]:
    rows: list[dict] = []
    ipa = catalog.get("ipa")
    if isinstance(ipa, dict):
        rows.append(ipa)
    debug = catalog.get("ipa-debug")
    if isinstance(debug, dict):
        rows.append(debug)
    desktop = catalog.get("desktop") if isinstance(catalog.get("desktop"), dict) else {}
    for kind in DESKTOP_KINDS:
        entry = desktop.get(kind)
        if isinstance(entry, dict):
            rows.append(entry)
    return rows


def entry_matches_file(entry: dict, relative: str, name: str) -> bool:
    stored = entry.get("file")
    filename = entry.get("filename")
    return stored in {relative, name} or filename == name


def older_downloads(directory: Path, catalog: dict) -> list[dict]:
    current: set[Path] = set()
    for entry in (catalog.get("ipa"), (catalog.get("desktop") or {}).get("macos")):
        path = artifact_file(directory, entry if isinstance(entry, dict) else None)
        if path is not None:
            current.add(path.resolve())
    root = artifacts_directory(directory)
    if not root.is_dir():
        return []
    packages: list[Path] = []
    for path in root.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in {".ipa", ".dmg"}:
            continue
        relative_parts = path.relative_to(root).parts
        if any(part.startswith(".") for part in relative_parts):
            continue
        packages.append(path)
    packages.sort(key=lambda item: item.stat().st_mtime, reverse=True)
    known = known_catalog_entries(catalog)
    rows: list[dict] = []
    for path in packages:
        if path.resolve() in current:
            continue
        relative = path.relative_to(root).as_posix()
        match = next((entry for entry in known if entry_matches_file(entry, relative, path.name)), None)
        kind = "ipa" if path.suffix.lower() == ".ipa" else "macos"
        version = match.get("version") if match else None
        commit = match.get("commit") if match else None
        build = match.get("build") if match else None
        if kind == "ipa":
            short, bundle_build = ipa_bundle_versions(path)
            if short:
                version = short
            if bundle_build:
                build = bundle_build
        if not isinstance(version, str) or not version or version == "none":
            inferred = infer_version(path.name)
            version = None if inferred == "none" else inferred
        if not isinstance(commit, str) or commit == "none" or not short_commit(commit):
            commit = None
        try:
            size = path.stat().st_size
        except OSError:
            continue
        item = {
            "filename": path.name,
            "bytes": size,
            "version": version,
            "commit": commit,
            "product": PRODUCT_IPHONE if kind == "ipa" else PRODUCT_MAC,
            "status": "older",
        }
        if isinstance(build, str) and build:
            item["build"] = build
        if match and match.get("sha256"):
            item["sha256"] = match.get("sha256")
        rows.append(item)
    return rows


def current_downloads(directory: Path) -> dict:
    catalog = reconcile_catalog(directory)
    desktop = catalog["desktop"]
    return {
        "ipa": public_named_entry(directory, catalog.get("ipa"), "ipa"),
        "desktop": {
            "macos": public_named_entry(directory, desktop.get("macos"), "macos"),
            "windows": public_download_entry(directory, desktop.get("windows")),
            "linux": public_download_entry(directory, desktop.get("linux")),
        },
        "older": older_downloads(directory, catalog),
    }


def resolved_catalog(directory: Path) -> dict:
    if not directory.exists():
        catalog = empty_catalog()
    else:
        with locked(directory, exclusive=False):
            catalog = load_catalog(directory)
    return overlay_published_desktop(directory, catalog)


def catalog_entry(directory: Path, kind: str) -> dict | None:
    catalog = resolved_catalog(directory)
    if kind == "ipa":
        entry = catalog.get("ipa")
    else:
        entry = catalog["desktop"].get(kind)
    if not isinstance(entry, dict):
        return None
    if artifact_file(directory, entry) is None:
        return None
    return entry


def hash_file(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    total = 0
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
            total += len(chunk)
    return total, digest.hexdigest()


def copy_hashed(source: Path, dest: Path) -> tuple[int, str]:
    dest.parent.mkdir(parents=True, exist_ok=True)
    partial = dest.with_name(dest.name + ".partial")
    digest = hashlib.sha256()
    total = 0
    try:
        with source.open("rb") as src, partial.open("wb") as out:
            while True:
                chunk = src.read(1024 * 1024)
                if not chunk:
                    break
                digest.update(chunk)
                out.write(chunk)
                total += len(chunk)
        os.replace(partial, dest)
    except Exception:
        try:
            partial.unlink()
        except OSError:
            pass
        raise
    return total, digest.hexdigest()


def is_zip_ipa(path: Path) -> bool:
    try:
        if not path.is_file() or path.stat().st_size < 22:
            return False
    except OSError:
        return False
    return zipfile.is_zipfile(path)


def command_record_download(args: argparse.Namespace) -> int:
    kind = args.kind
    if kind not in ("ipa", *DESKTOP_KINDS):
        print(f"unknown download kind {kind}", file=sys.stderr)
        return 2
    source = Path(args.path).expanduser()
    if not source.is_file():
        print(f"ipa-status: missing file {source}", file=sys.stderr)
        return 2
    if kind == "ipa" and not is_zip_ipa(source):
        print(f"ipa-status: {source} is not a zip IPA", file=sys.stderr)
        return 2
    directory = status_directory(args.status_dir)
    filename = source.name
    stored = safe_filename(filename)
    version = args.version or infer_version(filename)
    build_number = None
    if kind == "ipa":
        short, bundle_build = ipa_bundle_versions(source)
        if args.version is None and short:
            version = short
        build_number = bundle_build
    commit = args.commit
    if not commit:
        session = read_json(session_path(directory)) or {}
        commit = session.get("commit") or "none"
    dest = artifacts_directory(directory) / stored
    if source.resolve() == dest.resolve():
        total, digest = hash_file(source)
    else:
        total, digest = copy_hashed(source, dest)
    entry = {
        "filename": filename,
        "bytes": total,
        "sha256": digest,
        "version": str(version),
        "commit": str(commit),
        "file": stored,
    }
    if build_number:
        entry["build"] = build_number
    with locked(directory):
        catalog = load_catalog(directory)
        if kind == "ipa":
            catalog["ipa"] = entry
        else:
            catalog["desktop"][kind] = entry
        write_json(downloads_path(directory), catalog)
    print(f"ipa-status: recorded {kind} {filename} bytes={total}", file=sys.stderr)
    return 0


def daemonize(directory: Path) -> None:
    """Fork a session leader and return only in that child.

    The original process exits after the child is ready, so a build script
    does not wait on the heartbeat loop.
    """
    directory.mkdir(parents=True, exist_ok=True)
    read_fd, write_fd = os.pipe()
    pid = os.fork()
    if pid > 0:
        os.close(write_fd)
        ack = os.read(read_fd, 64)
        os.close(read_fd)
        os._exit(0 if ack else 1)
    os.close(read_fd)
    os.setsid()
    (directory / "heartbeat.pid").write_text(f"{os.getpid()}\n", encoding="utf-8")
    os.write(write_fd, b"ok")
    os.close(write_fd)
    devnull = os.open(os.devnull, os.O_RDWR)
    log_fd = os.open(
        str(directory / "heartbeat.log"),
        os.O_WRONLY | os.O_CREAT | os.O_APPEND,
        0o644,
    )
    os.dup2(devnull, 0)
    os.dup2(log_fd, 1)
    os.dup2(log_fd, 2)
    os.close(devnull)
    os.close(log_fd)


def command_heartbeat(args: argparse.Namespace) -> int:
    directory = status_directory(args.status_dir)
    parent = int(args.parent_pid)
    if not args.foreground:
        daemonize(directory)
    while True:
        now = time.time()
        with locked(directory):
            session = read_json(session_path(directory))
            if not session or session.get("closed"):
                return 0
            if not pid_alive(parent):
                baselines = load_baselines(directory)
                current = render(session, baselines, now)
                session["status"] = "failed"
                session["closed"] = True
                session["error"] = session.get("error") or "build process exited"
                session["frozenPercent"] = current["percent"]
                session["frozenRemainingSeconds"] = current["remainingSeconds"]
                close_build_record(directory, session.get("buildId"), "failed", now)
                publish(directory, session, baselines, now)
                return 0
            baselines = load_baselines(directory)
            publish(directory, session, baselines, now)
        time.sleep(2)


def command_serve(args: argparse.Namespace) -> int:
    directory = status_directory(args.status_dir)
    server = ThreadingHTTPServer((args.host, args.port), _handler_for(directory))
    print(f"ipa-status: listening on {args.host}:{server.server_address[1]}", file=sys.stderr)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        return 0
    return 0


def parse_iso(value: object) -> float | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def format_span(seconds: float) -> str:
    minutes = int(round(max(0.0, float(seconds)) / 60.0))
    if minutes < 90:
        return f"{minutes} min"
    hours, mins = divmod(minutes, 60)
    if mins:
        return f"{hours} hr {mins} min"
    return f"{hours} hr"


def format_elapsed(seconds: float) -> str:
    whole = max(0, int(seconds))
    minutes, secs = divmod(whole, 60)
    if minutes < 90:
        if minutes == 0:
            return f"{secs} sec"
        return f"{minutes} min {secs} sec"
    hours, mins = divmod(minutes, 60)
    if mins:
        return f"{hours} hr {mins} min"
    return f"{hours} hr"


def format_count(value: int) -> str:
    number = max(0, int(value))
    if number >= 1_000_000:
        return f"{number / 1_000_000:.1f}M"
    if number >= 1_000:
        return f"{number / 1_000:.1f}k"
    return str(number)


def median_numbers(values: list[float]) -> float:
    ordered = sorted(values)
    mid = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[mid]
    return (ordered[mid - 1] + ordered[mid]) / 2.0


def commit_subjects(notes: object) -> list[str]:
    if not isinstance(notes, str):
        return []
    raw = notes.strip()
    if raw in ("", "none", "(no release notes)"):
        return []
    subjects = []
    for line in raw.splitlines():
        match = COMMIT_SUBJECT.match(line.strip())
        if match:
            subjects.append(match.group(2).strip())
    return subjects


def version_summary(notes: object) -> dict:
    subjects = commit_subjects(notes)
    if not subjects:
        return {"lines": ["No summary for this file."], "empty": True}
    lines = subjects[:SUMMARY_LIMIT]
    extra = len(subjects) - len(lines)
    if extra:
        lines.append(f"And {extra} more in Release notes")
    return {"lines": lines, "empty": False}


def strip_platform_prefix(title: str) -> str:
    for prefix in ("iOS:", "macOS:", "Desktop:"):
        if title.startswith(prefix):
            return title[len(prefix):].lstrip()
    return title


def package_for_title(title: str) -> str | None:
    # A builds-page task names both packages and belongs to neither.
    folded = title.lower()
    if "builds page" in folded or "build site" in folded:
        return None
    if title.startswith("iOS:"):
        return "ipa"
    if title.startswith("macOS:"):
        return "dmg"
    has_ipa = "IPA" in title
    has_dmg = "DMG" in title or "Mac" in title
    if has_ipa and has_dmg:
        return None
    if has_ipa:
        return "ipa"
    if has_dmg:
        return "dmg"
    return None


def role_of(title: str) -> str:
    return "qa" if title.startswith("QA") else "engineer"


def task_credit(status: str, spent: float, expected: float | None) -> float:
    if status == "done":
        return 1.0
    if status in ("in_progress", "in_review"):
        if expected is not None and expected > 0 and spent > 0:
            return min(0.7, spent / expected)
        return 0.4 if status == "in_progress" else 0.7
    return 0.0


def run_seconds(run: dict, now: float) -> float:
    if run.get("status") == "cancelled":
        return 0.0
    start = parse_iso(run.get("startedAt"))
    if start is None:
        return 0.0
    end = parse_iso(run.get("finishedAt"))
    if end is None:
        end = now
    if end < start:
        end = start
    return min(RUN_CAP_SECONDS, end - start)


def select_goal(issues: list[dict]) -> dict | None:
    sim111 = next((issue for issue in issues if issue.get("identifier") == "SIM-111"), None)
    if sim111 and sim111.get("status") != "done":
        return sim111
    project = sim111.get("projectId") if sim111 else NUVIO_PROJECT_ID
    candidates = []
    for issue in issues:
        title = issue.get("title") or ""
        if not str(title).startswith("One current"):
            continue
        if issue.get("status") in ("done", "cancelled"):
            continue
        if project and issue.get("projectId") and issue.get("projectId") != project:
            continue
        candidates.append(issue)
    candidates.sort(key=lambda issue: issue.get("createdAt") or "", reverse=True)
    return candidates[0] if candidates else None


def membership_lists(issues: list[dict], goal: dict | None) -> tuple[list[str], list[str]]:
    if goal is None:
        return [], []
    project = goal.get("projectId")
    by_parent: dict[object, list[dict]] = {}
    for issue in issues:
        if project and issue.get("projectId") and issue.get("projectId") != project:
            continue
        by_parent.setdefault(issue.get("parentId"), []).append(issue)
    rows: list[dict] = []
    for child in by_parent.get(goal.get("id"), []):
        rows.append(child)
        rows.extend(by_parent.get(child.get("id"), []))
    rows.sort(key=lambda issue: int(issue.get("issueNumber") or 0))
    ipa: list[str] = []
    dmg: list[str] = []
    seen: set[str] = set()
    for issue in rows:
        identifier = issue.get("identifier")
        if not isinstance(identifier, str) or identifier in seen or identifier == goal.get("identifier"):
            continue
        if issue.get("status") == "cancelled":
            continue
        seen.add(identifier)
        kind = package_for_title(str(issue.get("title") or ""))
        if kind == "ipa":
            ipa.append(identifier)
        elif kind == "dmg":
            dmg.append(identifier)
    return ipa, dmg


def derive_manifest(issues: list[dict]) -> dict:
    goal = select_goal(issues)
    ipa, dmg = membership_lists(issues, goal)
    return {"dmg": dmg, "goal": goal.get("identifier") if goal else None, "ipa": ipa}


def next_package_path(directory: Path) -> Path:
    return directory / "next-package.json"


def held_back_path(directory: Path) -> Path:
    return directory / "held-back.json"


def platform_commit(catalog: dict, platform: str) -> str:
    if platform == "ipa":
        entry = catalog.get("ipa")
    else:
        entry = (catalog.get("desktop") or {}).get("macos")
    if isinstance(entry, dict) and isinstance(entry.get("commit"), str):
        return entry["commit"]
    return ""


def seed_held_back(ipa_commit: str, dmg_commit: str) -> dict:
    ipa_rows = []
    if ipa_commit.startswith(IPA_SEED_COMMIT):
        ipa_rows = [{"issue": "SIM-122", "summary": HELD_BACK_IPA_SEED}]
    return {
        "dmg": [],
        "ipa": ipa_rows,
        "watched": {"dmg": dmg_commit, "ipa": ipa_commit},
    }


def reconcile_held_back(directory: Path) -> dict:
    catalog = load_catalog(directory)
    commits = {
        "ipa": platform_commit(catalog, "ipa"),
        "dmg": platform_commit(catalog, "dmg"),
    }
    path = held_back_path(directory)
    raw = read_json(path)
    if not isinstance(raw, dict) or not isinstance(raw.get("ipa"), list) or not isinstance(raw.get("dmg"), list):
        seeded = seed_held_back(commits["ipa"], commits["dmg"])
        write_json(path, seeded)
        return seeded
    watched = raw.get("watched") if isinstance(raw.get("watched"), dict) else {}
    changed = False
    for platform in ("ipa", "dmg"):
        current = commits[platform]
        previous = watched.get(platform)
        if previous is None:
            watched[platform] = current
            changed = True
            continue
        if previous != current:
            raw[platform] = []
            watched[platform] = current
            changed = True
    raw["watched"] = {"dmg": watched.get("dmg") or "", "ipa": watched.get("ipa") or ""}
    if changed or not isinstance(raw.get("watched"), dict):
        write_json(path, raw)
    return raw


def ensure_manifest(directory: Path, issues: list[dict]) -> dict:
    path = next_package_path(directory)
    raw = read_json(path)
    if isinstance(raw, dict) and isinstance(raw.get("ipa"), list) and isinstance(raw.get("dmg"), list):
        return raw
    if not issues:
        return {"dmg": [], "goal": None, "ipa": []}
    derived = derive_manifest(issues)
    write_json(path, derived)
    return derived


def notes_text(directory: Path, platform: str, now: float) -> str:
    if platform == "ipa":
        payload = current_public(directory, now)
    else:
        payload = desktop_public(directory, now)
    notes = payload.get("releaseNotes")
    return notes if isinstance(notes, str) else ""


def compile_line(directory: Path, platform: str) -> str:
    build_platform = "ipa" if platform == "ipa" else "desktop"
    samples: list[float] = []
    for item in load_builds(directory):
        if item.get("platform") != build_platform or item.get("status") != "succeeded":
            continue
        start = parse_iso(item.get("startedAt"))
        end = parse_iso(item.get("finishedAt"))
        if start is None or end is None or end < start:
            continue
        samples.append(end - start)
        if len(samples) >= 5:
            break
    if len(samples) >= 3:
        return f"Compile after the tasks: about {format_span(median_numbers(samples))}"
    if platform == "ipa":
        baselines = load_baselines(directory)
        baseline = baselines["xcodebuild"] + baselines["zip"]
        return f"Compile after the tasks: about {format_span(baseline)}"
    return "Compile after the tasks: compile time is unknown"


def paperclip_get(path: str) -> object:
    request = urllib.request.Request(
        PAPERCLIP_ORIGIN + path,
        headers={"Accept": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=8) as response:
        return json.loads(response.read().decode("utf-8"))


def as_list(payload: object) -> list:
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict):
        for key in ("issues", "items", "runs", "agents"):
            value = payload.get(key)
            if isinstance(value, list):
                return value
    return []


def short_code(value: object) -> str | None:
    if isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_]{1,40}", value):
        return value
    return None


def usage_number(usage: dict, *keys: str) -> int:
    for key in keys:
        value = usage.get(key)
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            continue
        if math.isfinite(float(value)):
            return int(value)
    return 0


def safe_issue(raw: dict) -> dict | None:
    if not isinstance(raw.get("id"), str) or not isinstance(raw.get("identifier"), str):
        return None
    number = raw.get("issueNumber")
    return {
        "assigneeAgentId": raw.get("assigneeAgentId") if isinstance(raw.get("assigneeAgentId"), str) else None,
        "completedAt": raw.get("completedAt") if isinstance(raw.get("completedAt"), str) else None,
        "createdAt": raw.get("createdAt") if isinstance(raw.get("createdAt"), str) else None,
        "executionRunId": raw.get("executionRunId") if isinstance(raw.get("executionRunId"), str) else None,
        "id": raw["id"],
        "identifier": raw["identifier"],
        "issueNumber": number if isinstance(number, int) and not isinstance(number, bool) else None,
        "parentId": raw.get("parentId") if isinstance(raw.get("parentId"), str) else None,
        "projectId": raw.get("projectId") if isinstance(raw.get("projectId"), str) else None,
        "status": raw.get("status") if isinstance(raw.get("status"), str) else "",
        "title": raw.get("title") if isinstance(raw.get("title"), str) else "",
        "updatedAt": raw.get("updatedAt") if isinstance(raw.get("updatedAt"), str) else None,
    }


def safe_run(raw: dict, issue_id: str) -> dict | None:
    usage = raw.get("usageJson") if isinstance(raw.get("usageJson"), dict) else {}
    model = usage.get("model") if isinstance(usage.get("model"), str) else None
    if not model and isinstance(raw.get("_model"), str):
        model = raw["_model"]
    if model and (len(model) > 80 or "\n" in model or "/" in model):
        model = None
    has_usage = isinstance(raw.get("usageJson"), dict) and any(
        key in usage
        for key in (
            "inputTokens",
            "input_tokens",
            "outputTokens",
            "output_tokens",
            "cachedInputTokens",
            "cached_input_tokens",
        )
    )
    run_id = raw.get("runId") or raw.get("id")
    if not isinstance(run_id, str):
        return None
    return {
        "agentId": raw.get("agentId") if isinstance(raw.get("agentId"), str) else None,
        "billingType": short_code(usage.get("billingType") or usage.get("billing_type")),
        "cachedInputTokens": usage_number(usage, "cachedInputTokens", "cached_input_tokens", "cache_read_input_tokens"),
        "costStatus": short_code(usage.get("costStatus") or usage.get("cost_status")),
        "finishedAt": raw.get("finishedAt") if isinstance(raw.get("finishedAt"), str) else None,
        "hasUsage": has_usage,
        "inputTokens": usage_number(usage, "inputTokens", "input_tokens"),
        "issueId": issue_id,
        "model": model,
        "outputTokens": usage_number(usage, "outputTokens", "output_tokens"),
        "runId": run_id,
        "startedAt": raw.get("startedAt") if isinstance(raw.get("startedAt"), str) else None,
        "status": raw.get("status") if isinstance(raw.get("status"), str) else "",
    }


def safe_live(raw: dict) -> dict | None:
    if not isinstance(raw.get("id"), str):
        return None
    name = raw.get("agentName") if isinstance(raw.get("agentName"), str) else None
    if name and (len(name) > 80 or "/" in name):
        name = None
    return {
        "agentId": raw.get("agentId") if isinstance(raw.get("agentId"), str) else None,
        "agentName": name,
        "finishedAt": raw.get("finishedAt") if isinstance(raw.get("finishedAt"), str) else None,
        "issueId": raw.get("issueId") if isinstance(raw.get("issueId"), str) else None,
        "runId": raw["id"],
        "startedAt": raw.get("startedAt") if isinstance(raw.get("startedAt"), str) else None,
        "status": raw.get("status") if isinstance(raw.get("status"), str) else "",
    }


def safe_agents(payload: object) -> dict[str, str]:
    names: dict[str, str] = {}
    for agent in as_list(payload):
        if not isinstance(agent, dict):
            continue
        agent_id = agent.get("id")
        name = agent.get("name")
        if isinstance(agent_id, str) and isinstance(name, str) and name and "/" not in name and len(name) <= 80:
            names[agent_id] = name
    return names


def normalize_bundle(raw: object) -> dict:
    if not isinstance(raw, dict):
        raise ValueError("paperclip payload")
    issues = []
    for item in as_list(raw.get("issues")):
        if isinstance(item, dict):
            safe = safe_issue(item)
            if safe:
                issues.append(safe)
    runs: dict[str, list[dict]] = {}
    raw_runs = raw.get("runs")
    if isinstance(raw_runs, dict):
        for issue_id, rows in raw_runs.items():
            if not isinstance(issue_id, str) or not isinstance(rows, list):
                continue
            cleaned = []
            for row in rows:
                if isinstance(row, dict):
                    safe = safe_run(row, issue_id)
                    if safe:
                        cleaned.append(safe)
            runs[issue_id] = cleaned
    live = []
    for item in as_list(raw.get("liveRuns")):
        if isinstance(item, dict):
            safe = safe_live(item)
            if safe:
                live.append(safe)
    return {"agents": safe_agents(raw.get("agents")), "issues": issues, "live": live, "runs": runs}


def nuvio_issues(issues: list[dict]) -> list[dict]:
    project = NUVIO_PROJECT_ID
    anchor = next((issue for issue in issues if issue.get("identifier") == "SIM-111"), None)
    if anchor and anchor.get("projectId"):
        project = anchor["projectId"]
    return [issue for issue in issues if issue.get("projectId") == project]


def ids_for_runs(issues: list[dict], directory: Path, live: list[dict]) -> tuple[set[str], set[str]]:
    scoped = nuvio_issues(issues)
    by_ident = {issue.get("identifier"): issue for issue in scoped}
    manifest = read_json(next_package_path(directory))
    identifiers: list[str] = []
    if isinstance(manifest, dict) and isinstance(manifest.get("ipa"), list) and isinstance(manifest.get("dmg"), list):
        identifiers.extend(str(item) for item in manifest["ipa"] if isinstance(item, str))
        identifiers.extend(str(item) for item in manifest["dmg"] if isinstance(item, str))
    else:
        goal = select_goal(scoped)
        ipa, dmg = membership_lists(scoped, goal)
        identifiers.extend(ipa)
        identifiers.extend(dmg)
    displayed: set[str] = set()
    for identifier in identifiers:
        issue = by_ident.get(identifier)
        if issue:
            displayed.add(issue["id"])
    by_id = {issue["id"]: issue for issue in scoped}
    for row in live:
        issue = by_id.get(row.get("issueId"))
        if issue and issue.get("status") in ("in_progress", "in_review"):
            displayed.add(issue["id"])
    for issue in scoped:
        if issue.get("status") in ("in_progress", "in_review") and issue.get("executionRunId"):
            displayed.add(issue["id"])
    wanted = set(displayed)
    for role in ("qa", "engineer"):
        done = [
            issue
            for issue in scoped
            if issue.get("status") == "done" and role_of(issue.get("title") or "") == role
        ]
        done.sort(key=lambda issue: issue.get("completedAt") or issue.get("updatedAt") or "", reverse=True)
        for issue in done[:EXPECTED_SAMPLE]:
            wanted.add(issue["id"])
    return wanted, displayed


def model_from_heartbeat(run_id: str) -> str | None:
    try:
        payload = paperclip_get(f"/api/heartbeat-runs/{run_id}")
    except (OSError, urllib.error.URLError, json.JSONDecodeError, ValueError):
        return None
    if not isinstance(payload, dict):
        return None
    usage = payload.get("usageJson")
    if not isinstance(usage, dict):
        return None
    model = usage.get("model")
    if isinstance(model, str) and model and len(model) <= 80 and "/" not in model and "\n" not in model:
        return model
    return None


def read_paperclip_live() -> dict:
    directory = status_directory(None)
    issues = paperclip_get(f"/api/companies/{PAPERCLIP_COMPANY_ID}/issues")
    agents = paperclip_get(f"/api/companies/{PAPERCLIP_COMPANY_ID}/agents")
    live_raw = paperclip_get(f"/api/companies/{PAPERCLIP_COMPANY_ID}/live-runs")
    issue_rows = [item for item in as_list(issues) if isinstance(item, dict)]
    live_rows = [item for item in as_list(live_raw) if isinstance(item, dict)]
    live_safe = []
    for item in live_rows:
        safe = safe_live(item)
        if safe:
            live_safe.append(safe)
    wanted, displayed = ids_for_runs(issue_rows, directory, live_safe)
    runs: dict[str, list] = {}
    for issue_id in sorted(wanted):
        try:
            payload = paperclip_get(f"/api/issues/{issue_id}/runs")
        except (OSError, urllib.error.URLError, json.JSONDecodeError, ValueError):
            runs[issue_id] = []
            continue
        runs[issue_id] = [item for item in as_list(payload) if isinstance(item, dict)]
    for issue_id in displayed:
        rows = runs.get(issue_id) or []
        finished = [row for row in rows if isinstance(row.get("finishedAt"), str)]
        finished.sort(key=lambda row: row.get("finishedAt") or "", reverse=True)
        if not finished:
            continue
        latest = finished[0]
        usage = latest.get("usageJson") if isinstance(latest.get("usageJson"), dict) else {}
        if isinstance(usage.get("model"), str) and usage.get("model"):
            continue
        run_id = latest.get("runId") or latest.get("id")
        if not isinstance(run_id, str):
            continue
        model = model_from_heartbeat(run_id)
        if model:
            latest["_model"] = model
    return {"agents": agents, "issues": issue_rows, "liveRuns": live_rows, "runs": runs}


class _WorkCache:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.fetched_at = 0.0
        self.updated_at: str | None = None
        self.bundle: dict | None = None
        self.refreshing = False
        self.reader = read_paperclip_live


WORK_CACHE = _WorkCache()


def reset_work_cache() -> None:
    with WORK_CACHE.lock:
        WORK_CACHE.fetched_at = 0.0
        WORK_CACHE.updated_at = None
        WORK_CACHE.bundle = None
        WORK_CACHE.refreshing = False


def cached_bundle(now: float) -> tuple[dict | None, str | None]:
    with WORK_CACHE.lock:
        fresh = WORK_CACHE.bundle is not None and now - WORK_CACHE.fetched_at < PAPERCLIP_POLL_SECONDS
        if fresh or WORK_CACHE.refreshing:
            return WORK_CACHE.bundle, WORK_CACHE.updated_at
        WORK_CACHE.refreshing = True
        reader = WORK_CACHE.reader
    try:
        bundle = normalize_bundle(reader())
        updated = iso(now)
        ok = True
    except Exception:
        bundle = None
        updated = None
        ok = False
    with WORK_CACHE.lock:
        WORK_CACHE.refreshing = False
        WORK_CACHE.fetched_at = now
        if ok and bundle is not None:
            WORK_CACHE.bundle = bundle
            WORK_CACHE.updated_at = updated
        return WORK_CACHE.bundle, WORK_CACHE.updated_at


def runs_for(issue: dict, bundle: dict) -> list[dict]:
    rows = list((bundle.get("runs") or {}).get(issue.get("id")) or [])
    known = {row.get("runId") for row in rows}
    for live in bundle.get("live") or []:
        if live.get("issueId") != issue.get("id") or live.get("runId") in known:
            continue
        if live.get("status") not in ("running", "queued"):
            continue
        rows.append(
            {
                "agentId": live.get("agentId"),
                "billingType": None,
                "cachedInputTokens": 0,
                "costStatus": None,
                "finishedAt": live.get("finishedAt"),
                "hasUsage": False,
                "inputTokens": 0,
                "issueId": issue.get("id"),
                "model": None,
                "outputTokens": 0,
                "runId": live.get("runId"),
                "startedAt": live.get("startedAt"),
                "status": live.get("status") or "running",
            }
        )
    return rows


def spent_seconds(runs: list[dict], now: float) -> float:
    return sum(run_seconds(run, now) for run in runs)


def expected_by_role(issues: list[dict], bundle: dict, now: float) -> dict[str, float | None]:
    found: dict[str, float | None] = {"engineer": None, "qa": None}
    for role in ("engineer", "qa"):
        done = [
            issue
            for issue in issues
            if issue.get("status") == "done" and role_of(issue.get("title") or "") == role
        ]
        done.sort(key=lambda issue: issue.get("completedAt") or issue.get("updatedAt") or "", reverse=True)
        sample = done[:EXPECTED_SAMPLE]
        if len(sample) < EXPECTED_MINIMUM:
            continue
        totals = []
        for issue in sample:
            stored = list((bundle.get("runs") or {}).get(issue.get("id")) or [])
            totals.append(spent_seconds(stored, now))
        found[role] = median_numbers(totals)
    return found


def agent_time_line(rows: list[dict]) -> str:
    open_rows = [row for row in rows if row.get("status") not in ("done", "cancelled")]
    if not open_rows:
        return "Agent time: 0 min"
    known = [row for row in open_rows if row.get("expected") is not None]
    unknown = len(open_rows) - len(known)
    if not known:
        return "Agent time: estimate unavailable"
    remaining = sum(max(0.0, float(row["expected"]) - float(row["spent"])) for row in known)
    label = format_span(remaining)
    if unknown == 0:
        return f"Agent time: {label}"
    if unknown == 1:
        return f"Agent time: at least {label}, 1 task has no estimate"
    return f"Agent time: at least {label}, {unknown} tasks have no estimate"


def token_line(runs: list[dict]) -> str | None:
    if not runs:
        return None
    usable = [run for run in runs if run.get("hasUsage")]
    if not usable:
        unfinished = [run for run in runs if not run.get("finishedAt") and run.get("status") != "cancelled"]
        if len(runs) == 1 and unfinished:
            return "tokens post when the run finishes"
        if unfinished and len(unfinished) == len(runs):
            return "tokens post when the run finishes"
        return None
    incoming = sum(int(run.get("inputTokens") or 0) for run in usable)
    cached = sum(int(run.get("cachedInputTokens") or 0) for run in usable)
    outgoing = sum(int(run.get("outputTokens") or 0) for run in usable)
    return f"{format_count(incoming)} in · {format_count(cached)} cached · {format_count(outgoing)} out"


def billing_line(runs: list[dict]) -> str | None:
    for run in runs:
        if run.get("billingType") == "subscription_included" or run.get("costStatus") == "unpriced":
            return "subscription, unpriced"
    return None


def finished_model(runs: list[dict]) -> str | None:
    finished = [run for run in runs if run.get("finishedAt") and isinstance(run.get("model"), str)]
    finished.sort(key=lambda run: run.get("finishedAt") or "", reverse=True)
    if not finished:
        return None
    return finished[0]["model"]


def live_for(issue: dict, bundle: dict) -> dict | None:
    rows = [
        row
        for row in bundle.get("live") or []
        if row.get("issueId") == issue.get("id") and row.get("status") in ("running", "queued")
    ]
    rows.sort(key=lambda row: row.get("startedAt") or "", reverse=True)
    return rows[0] if rows else None


def agent_for(issue: dict, runs: list[dict], bundle: dict) -> str:
    live = live_for(issue, bundle)
    if live and live.get("agentName"):
        return str(live["agentName"])
    agents = bundle.get("agents") or {}
    assignee = issue.get("assigneeAgentId")
    if isinstance(assignee, str) and agents.get(assignee):
        return str(agents[assignee])
    for run in runs:
        agent_id = run.get("agentId")
        if isinstance(agent_id, str) and agents.get(agent_id):
            return str(agents[agent_id])
    if live and isinstance(live.get("agentId"), str) and agents.get(live["agentId"]):
        return str(agents[live["agentId"]])
    return "None"


def public_stash(rows: object, platform: str) -> dict:
    clean = []
    if isinstance(rows, list):
        for row in rows:
            if not isinstance(row, dict) or not isinstance(row.get("summary"), str):
                continue
            summary = row["summary"].strip()
            if not summary:
                continue
            item = {"summary": summary}
            issue = row.get("issue")
            if isinstance(issue, str) and ISSUE_KEY.fullmatch(issue):
                item["issue"] = issue
            clean.append(item)
    empty = (
        "Nothing is stashed for the next IPA."
        if platform == "ipa"
        else "Nothing is stashed for the next DMG."
    )
    return {"emptyText": empty, "items": clean[:STASH_LIMIT], "more": max(0, len(clean) - STASH_LIMIT)}


def package_snapshot(
    directory: Path,
    platform: str,
    identifiers: list[str],
    issues_by_ident: dict[str, dict],
    bundle: dict,
    expected: dict[str, float | None],
    notes: str,
    held_rows: list,
    now: float,
) -> dict:
    ordered = []
    for index, identifier in enumerate(identifiers):
        issue = issues_by_ident.get(identifier)
        if issue is None or issue.get("status") == "cancelled":
            continue
        runs = runs_for(issue, bundle)
        spent = spent_seconds(runs, now)
        role = role_of(issue.get("title") or "")
        ordered.append(
            {
                "expected": expected.get(role),
                "index": index,
                "issue": issue,
                "runs": runs,
                "spent": spent,
                "status": issue.get("status") or "",
            }
        )
    ordered.sort(key=lambda row: (STATUS_RANK.get(row["status"], 5), row["index"]))
    total = len(ordered)
    if total == 0:
        headline = "Nothing is queued."
        percent = None
    else:
        done = sum(1 for row in ordered if row["status"] == "done")
        credit = sum(task_credit(row["status"], row["spent"], row["expected"]) for row in ordered)
        headline = f"{done} of {total} tasks done"
        percent = int(round(100.0 * credit / total))
    next_items = []
    for row in ordered:
        title = row["issue"].get("title") or ""
        if title.startswith("QA"):
            continue
        next_items.append(strip_platform_prefix(title))
        if len(next_items) >= SUMMARY_LIMIT:
            break
    tasks = []
    for row in ordered:
        issue = row["issue"]
        runs = row["runs"]
        task = {
            "agent": agent_for(issue, runs, bundle),
            "elapsed": format_elapsed(row["spent"]),
            "elapsedSeconds": int(row["spent"]),
            "id": issue.get("identifier"),
            "status": row["status"],
            "title": strip_platform_prefix(issue.get("title") or ""),
        }
        tokens = token_line(runs)
        billing = billing_line(runs)
        model = finished_model(runs)
        if tokens:
            task["tokens"] = tokens
        if billing:
            task["billing"] = billing
        if model:
            task["model"] = model
        tasks.append(task)
    label = "In the next IPA" if platform == "ipa" else "In the next DMG"
    return {
        "agentTime": agent_time_line(ordered),
        "compile": compile_line(directory, platform),
        "headline": headline,
        "inNext": {"empty": not next_items, "items": next_items},
        "inThisVersion": version_summary(notes),
        "nextLabel": label,
        "percent": percent,
        "stashed": public_stash(held_rows, platform),
        "tasks": tasks,
    }


def working_rows(issues: list[dict], manifest: dict, bundle: dict, now: float) -> list[dict]:
    ipa_ids = {item for item in manifest.get("ipa") or [] if isinstance(item, str)}
    dmg_ids = {item for item in manifest.get("dmg") or [] if isinstance(item, str)}
    live_ids = {
        row.get("issueId")
        for row in bundle.get("live") or []
        if row.get("status") in ("running", "queued") and row.get("issueId")
    }
    rows = []
    for issue in issues:
        if issue.get("status") not in ("in_progress", "in_review"):
            continue
        if issue.get("id") not in live_ids and not issue.get("executionRunId"):
            continue
        runs = runs_for(issue, bundle)
        live = live_for(issue, bundle)
        if live and live.get("startedAt"):
            elapsed = run_seconds(
                {"startedAt": live.get("startedAt"), "finishedAt": live.get("finishedAt"), "status": live.get("status")},
                now,
            )
        else:
            elapsed = spent_seconds(runs, now)
        identifier = issue.get("identifier")
        if identifier in ipa_ids:
            landing = "ipa"
        elif identifier in dmg_ids:
            landing = "dmg"
        else:
            landing = "neither"
        row = {
            "agent": agent_for(issue, runs, bundle),
            "elapsed": format_elapsed(elapsed),
            "elapsedSeconds": int(elapsed),
            "id": identifier,
            "package": landing,
            "status": issue.get("status"),
            "title": issue.get("title") or "",
        }
        model = finished_model(runs)
        if model:
            row["model"] = model
        rows.append(row)
    rows.sort(key=lambda row: row.get("id") or "")
    return rows


def public_work(directory: Path, now: float) -> dict:
    bundle, updated_at = cached_bundle(now)
    safe = bundle or {"agents": {}, "issues": [], "live": [], "runs": {}}
    scoped = nuvio_issues(safe["issues"]) if safe["issues"] else []
    with locked(directory):
        manifest = ensure_manifest(directory, scoped)
        held = reconcile_held_back(directory)
    ipa_notes = notes_text(directory, "ipa", now)
    dmg_notes = notes_text(directory, "dmg", now)
    expected = expected_by_role(scoped, safe, now)
    by_ident = {issue.get("identifier"): issue for issue in scoped}
    ipa_ids = [item for item in manifest.get("ipa") or [] if isinstance(item, str)]
    dmg_ids = [item for item in manifest.get("dmg") or [] if isinstance(item, str)]
    return {
        "dmg": package_snapshot(
            directory,
            "dmg",
            dmg_ids,
            by_ident,
            safe,
            expected,
            dmg_notes,
            held.get("dmg") if isinstance(held.get("dmg"), list) else [],
            now,
        ),
        "ipa": package_snapshot(
            directory,
            "ipa",
            ipa_ids,
            by_ident,
            safe,
            expected,
            ipa_notes,
            held.get("ipa") if isinstance(held.get("ipa"), list) else [],
            now,
        ),
        "updatedAt": updated_at,
        "workingNow": working_rows(scoped, manifest, safe, now),
    }


def command_held_back_add(args: argparse.Namespace) -> int:
    summary = args.summary if isinstance(args.summary, str) else ""
    if len(summary) > HELD_BACK_LIMIT or not summary.strip():
        print("ipa-status: summary must be 1 to 180 characters", file=sys.stderr)
        return 2
    directory = status_directory(args.status_dir)
    with locked(directory):
        raw = reconcile_held_back(directory)
        rows = raw.get(args.platform)
        if not isinstance(rows, list):
            rows = []
        rows.append({"summary": summary})
        raw[args.platform] = rows
        write_json(held_back_path(directory), raw)
    return 0


def command_held_back_clear(args: argparse.Namespace) -> int:
    directory = status_directory(args.status_dir)
    with locked(directory):
        raw = reconcile_held_back(directory)
        raw[args.platform] = []
        write_json(held_back_path(directory), raw)
    return 0


def command_next_package(args: argparse.Namespace) -> int:
    directory = status_directory(args.status_dir)
    try:
        bundle = normalize_bundle(WORK_CACHE.reader())
    except Exception:
        print("ipa-status: Paperclip is not available", file=sys.stderr)
        return 1
    derived = derive_manifest(nuvio_issues(bundle["issues"]))
    with locked(directory):
        write_json(next_package_path(directory), derived)
    return 0


def self_test() -> int:
    import subprocess
    import tempfile

    failures = []

    def check(condition: bool, message: str) -> None:
        if not condition:
            failures.append(message)

    for raw, expected in (
        ("2026-10-06T13:59:00Z", "2026-10-06T14:00:00Z"),
        ("2026-10-06T14:00:00Z", "2026-10-07T14:00:00Z"),
        ("2026-10-26T14:59:00Z", "2026-10-26T15:00:00Z"),
        ("2026-10-26T15:00:00Z", "2026-10-27T15:00:00Z"),
    ):
        moment = datetime.strptime(raw, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
        got = next_upstream_iso(moment)
        check(got == expected, f"oslo schedule {raw} -> {got} expected {expected}")
    check(
        github_slug("https://github.com/SimSalabimse/NuvioMobile-Enhanced.git")
        == "github.com/simsalabimse/nuviomobile-enhanced",
        "github slug",
    )
    check(github_slug("git@github.com:NuvioMedia/NuvioDesktop.git") == "github.com/nuviomedia/nuviodesktop", "ssh slug")
    iphone_ahead = {
        "count": 2,
        "name": "iPhone",
        "short": "4d3346a7",
        "status": "ahead",
        "tip": "4d3346a7ff4817c183b5ed21ee9258f8f47a7226",
    }
    mac_current = {
        "count": 0,
        "name": "Mac",
        "short": "ccd28802",
        "status": "current",
        "tip": "ccd288021dd6843a92942f98a52e4b97cef33e3e",
    }
    iphone_current = {
        "count": 0,
        "name": "iPhone",
        "short": "0bae96d5",
        "status": "current",
        "tip": "0bae96d566240f4f18667044cd0230caf2c9d790",
    }
    iphone_unavailable = {"count": 0, "name": "iPhone", "short": "", "status": "unavailable", "tip": ""}
    mac_unavailable = {"count": 0, "name": "Mac", "short": "", "status": "unavailable", "tip": ""}
    check(
        upstream_status_lines(False, [iphone_ahead, mac_current])
        == ["iPhone upstream has 2 commits that are not merged, through 4d3346a7."],
        "ahead line",
    )
    check(
        upstream_status_lines(False, [iphone_current, mac_current]) == ["Upstream is already merged."],
        "current line",
    )
    check(
        upstream_status_lines(False, [iphone_unavailable, mac_unavailable]) == ["Upstream could not be checked."],
        "unavailable line",
    )
    check(
        upstream_status_lines(False, [iphone_unavailable, mac_current]) == ["iPhone upstream could not be checked."],
        "one unavailable line",
    )
    check(
        upstream_status_lines(False, [iphone_ahead, mac_unavailable])
        == [
            "iPhone upstream has 2 commits that are not merged, through 4d3346a7.",
            "Mac upstream could not be checked.",
        ],
        "ahead and unavailable lines",
    )
    check(
        upstream_status_lines(True, [iphone_ahead, mac_current]) == ["Merge and build is running."],
        "running line",
    )
    check(
        not routine_has_active_issue([{"linkedIssue": {"status": "done"}, "status": "completed"}]),
        "finished routine run looked active",
    )
    check(
        routine_has_active_issue([{"linkedIssue": {"status": "in_progress"}, "status": "queued"}]),
        "active routine issue was missed",
    )
    check(routine_has_active_issue([{"status": "running"}]), "run without an issue yet was missed")
    check(not routine_has_active_issue([{"status": "coalesced"}]), "coalesced run looked active")
    routine_calls: list[tuple] = []

    def record_exchange(method: str, path: str, body: dict | None = None, code: int = 202):
        routine_calls.append((method, path, body))
        return code, b"{}"

    status, payload = start_upstream_run([iphone_ahead, mac_current], False, record_exchange)
    check(status == 200 and payload == {"ok": True}, f"manual run {status} {payload}")
    check(
        routine_calls
        and routine_calls[0][0] == "POST"
        and routine_calls[0][1] == f"/api/routines/{UPSTREAM_ROUTINE_ID}/run"
        and routine_calls[0][2]["source"] == "manual"
        and routine_calls[0][2]["idempotencyKey"] == iphone_ahead["tip"] + "+" + mac_current["tip"],
        f"routine body {routine_calls}",
    )
    status, payload = start_upstream_run([iphone_ahead, mac_current], True, record_exchange)
    check(status == 409 and payload.get("error") == "Merge and build is running.", f"running press {status} {payload}")
    check(len(routine_calls) == 1, "running press called the routine")
    status, payload = start_upstream_run([iphone_current, mac_current], False, record_exchange)
    check(status == 409 and payload.get("error") == "Upstream is already merged.", f"current press {status} {payload}")
    check(len(routine_calls) == 1, "current press called the routine")
    status, payload = start_upstream_run([iphone_ahead, mac_current], False, lambda *_args: (500, b""))
    check(status == 502 and payload.get("error") == UPSTREAM_REFUSED, f"refused run {status} {payload}")
    status, payload = start_upstream_run(
        [iphone_ahead, mac_current],
        False,
        lambda *_args: (_ for _ in ()).throw(UpstreamDown("offline")),
    )
    check(status == 502 and payload.get("error") == UPSTREAM_REFUSED, f"offline run {status} {payload}")
    for accepted in (200, 201):
        status, payload = start_upstream_run([iphone_ahead, mac_current], False, lambda *_args, accepted=accepted: (accepted, b"{}"))
        check(status == 200 and payload == {"ok": True}, f"paperclip {accepted} was not success")
    try:
        with tempfile.TemporaryDirectory() as raw_upstream:
            root = Path(raw_upstream)
            upstream_bare = root / "upstream.git"
            origin_bare = root / "origin.git"
            work = root / "work"
            other = root / "other"
            hooks = root / "hooks"
            hooks.mkdir()
            subprocess.run(["git", "init", "--bare", str(upstream_bare)], check=True, capture_output=True)
            subprocess.run(["git", "init", "--bare", str(origin_bare)], check=True, capture_output=True)
            subprocess.run(["git", "init", "-b", "enhanced", str(work)], check=True, capture_output=True)
            for repo_path in (work,):
                subprocess.run(["git", "-C", str(repo_path), "config", "user.email", "test@example.com"], check=True, capture_output=True)
                subprocess.run(["git", "-C", str(repo_path), "config", "user.name", "Nuvio Test"], check=True, capture_output=True)
                subprocess.run(["git", "-C", str(repo_path), "config", "commit.gpgsign", "false"], check=True, capture_output=True)
                subprocess.run(["git", "-C", str(repo_path), "config", "core.hooksPath", str(hooks)], check=True, capture_output=True)
            (work / "one.txt").write_text("one\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(work), "add", "one.txt"], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(work), "commit", "-m", "one"], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(work), "remote", "add", "origin", str(origin_bare)], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(work), "remote", "add", "enhanced", str(upstream_bare)], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(work), "push", "origin", "enhanced"], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(work), "push", "enhanced", "enhanced"], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(work), "fetch", "origin"], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(work), "fetch", "enhanced"], check=True, capture_output=True)
            (work / "local.txt").write_text("local\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(work), "add", "local.txt"], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(work), "commit", "-m", "local only"], check=True, capture_output=True)
            local_head = subprocess.check_output(["git", "-C", str(work), "rev-parse", "refs/heads/enhanced"], text=True).strip()
            same = compare_refs(work, "iPhone", "origin", "enhanced", "enhanced")
            check(same["status"] == "current" and same["count"] == 0, f"equal tips {same}")
            check(
                subprocess.check_output(["git", "-C", str(work), "rev-parse", "refs/heads/enhanced"], text=True).strip() == local_head,
                "fetch moved enhanced",
            )
            subprocess.run(["git", "-C", str(upstream_bare), "symbolic-ref", "HEAD", "refs/heads/enhanced"], check=True, capture_output=True)
            subprocess.run(["git", "clone", str(upstream_bare), str(other)], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(other), "config", "user.email", "test@example.com"], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(other), "config", "user.name", "Nuvio Test"], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(other), "config", "commit.gpgsign", "false"], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(other), "config", "core.hooksPath", str(hooks)], check=True, capture_output=True)
            (other / "two.txt").write_text("two\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(other), "add", "two.txt"], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(other), "commit", "-m", "two"], check=True, capture_output=True)
            upstream_tip = subprocess.check_output(["git", "-C", str(other), "rev-parse", "HEAD"], text=True).strip().lower()
            subprocess.run(["git", "-C", str(other), "push", "origin", "HEAD:refs/heads/enhanced"], check=True, capture_output=True)
            ahead = compare_refs(work, "iPhone", "origin", "enhanced", "enhanced")
            check(ahead["status"] == "ahead" and ahead["count"] == 1, f"ahead count {ahead}")
            check(ahead["tip"] == upstream_tip and ahead["short"] == upstream_tip[:8], f"ahead tip {ahead}")
            check(
                subprocess.check_output(["git", "-C", str(work), "rev-parse", "refs/heads/enhanced"], text=True).strip() == local_head,
                "second fetch moved enhanced",
            )
            subprocess.run(["git", "-C", str(work), "update-ref", "-d", "refs/remotes/enhanced/enhanced"], check=True, capture_output=True)
            missing = compare_refs(work, "iPhone", "origin", "enhanced", "enhanced")
            check(missing["status"] == "unavailable", f"missing tracking {missing}")
            still_missing = subprocess.run(
                ["git", "-C", str(work), "show-ref", "--verify", "--quiet", "refs/remotes/enhanced/enhanced"],
                capture_output=True,
            )
            check(still_missing.returncode != 0, "fetch created a remote-tracking ref")
            check(
                subprocess.check_output(["git", "-C", str(work), "rev-parse", "refs/heads/enhanced"], text=True).strip() == local_head,
                "missing fetch moved enhanced",
            )
    except Exception as exc:
        check(False, f"upstream fetch fixture {exc}")

    mobile_path = builds_request.mobile_repository()
    desktop_path = builds_request.desktop_repository()
    real_enhanced_before = builds_request.git_commit(mobile_path, "refs/heads/enhanced") if mobile_path else None
    real_dev_before = builds_request.git_commit(desktop_path, "refs/heads/Dev") if desktop_path else None
    live_exchange = _PAPERCLIP_EXCHANGE[0]
    live_loader = _SURFACE_LOADER[0]

    def guarded_exchange(method: str, path: str, body: dict | None = None) -> tuple[int, bytes]:
        if method.upper() == "POST" and path.rstrip("/").endswith("/run"):
            failures.append("self-test called the live routine run")
            return 599, b""
        if method.upper() == "GET" and path.endswith("/runs"):
            return 200, b"[]"
        failures.append(f"unexpected paperclip {method} {path}")
        return 599, b""

    def fixture_surfaces(_now: float) -> list[dict]:
        return [dict(iphone_current), dict(mac_current)]

    _PAPERCLIP_EXCHANGE[0] = guarded_exchange
    _SURFACE_LOADER[0] = fixture_surfaces
    with _upstream_lock:
        _upstream_cache["running"] = None
        _upstream_cache["running_at"] = 0.0
        _upstream_cache["running_token"] = 0
        _upstream_cache["surfaces"] = None
        _upstream_cache["surfaces_at"] = 0.0

    with tempfile.TemporaryDirectory() as temporary:
        directory = Path(temporary)
        baselines = dict(DEFAULT_BASELINE_SECONDS)
        now = 1_000_000.0
        session = {
            "status": "building",
            "branch": "sim-44-ipa-status",
            "commit": "abc123",
            "releaseNotes": "notes",
            "stage": "xcodebuild",
            "stageStartedAt": now,
            "buildStartedAt": now,
            "parentPid": os.getpid(),
            "error": None,
            "closed": False,
        }
        first = render(session, baselines, now + 30)
        second = render(session, baselines, now + 90)
        check(20.0 <= first["percent"] < 90.0, f"first percent out of band: {first['percent']}")
        check(second["percent"] > first["percent"], "percent did not increase inside xcodebuild")
        check(second["remainingSeconds"] != first["remainingSeconds"], "remaining did not change")
        check(first["branch"] == "sim-44-ipa-status", "branch missing")
        check(first["commit"] == "abc123", "commit missing")
        check(first["releaseNotes"] == "notes", "notes missing")

        preflight = dict(session, stage="preflight", stageStartedAt=now, buildStartedAt=now)
        early = render(preflight, baselines, now + 1)
        later = render(preflight, baselines, now + 40)
        check(0.0 < early["percent"] < 2.0, f"preflight early {early['percent']}")
        check(later["percent"] > early["percent"], "preflight percent stuck")
        check(later["percent"] < 2.0, "preflight reached the next band early")

        prepare = dict(session, stage="prepare", stageStartedAt=now + 40, buildStartedAt=now)
        snapped = render(prepare, baselines, now + 40)
        check(abs(snapped["percent"] - 2.0) < 0.02, f"prepare did not snap to 2: {snapped['percent']}")
        check(snapped["remainingSeconds"] != later["remainingSeconds"], "remaining did not update on stage change")

        secret = redact(
            "keep this line\n"
            "TRAKT_CLIENT_SECRET=supersecretvalue\n"
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.abcdefghijklmnop.signaturevalue\n"
        )
        check("supersecretvalue" not in secret and "eyJ" not in secret and "keep this line" in secret, "redaction failed")

        idle = idle_payload(now)
        check(idle["status"] == "idle", "idle status")
        check(idle["branch"] == "none" and idle["commit"] == "none" and idle["releaseNotes"] == "none", "idle none")
        check(idle["remainingLabel"] == "none", "empty idle remaining")

        closed = dict(
            session,
            closed=True,
            status="failed",
            error="build script exited 65",
            frozenPercent=38.64,
            frozenRemainingSeconds=1044,
        )
        closed_view = render(closed, baselines, now + 120)
        check(closed_view["status"] == "idle", "closed session status")
        check(closed_view["remainingLabel"] == "none", "closed session remaining label")
        check(closed_view["remainingSeconds"] is None, "closed session remaining seconds")
        check(closed_view["branch"] == "sim-44-ipa-status", "closed session branch")
        check(closed_view["commit"] == "abc123", "closed session commit")
        check(closed_view["releaseNotes"] == "notes", "closed session notes")
        check(closed_view["error"] is None, "idle header kept the last error")
        check(closed_view["percent"] == 0, "idle header kept a percent")
        check(closed_view["stage"] is None, "idle header kept a stage")
        succeeded = dict(closed, status="succeeded", frozenPercent=100.0, frozenRemainingSeconds=0)
        succeeded_view = render(succeeded, baselines, now + 120)
        check(
            succeeded_view["status"] == "idle" and succeeded_view["remainingLabel"] == "none",
            "finished success should still read idle",
        )

        os.environ["IPA_STATUS_DIR"] = str(directory)
        notes = directory / "notes.txt"
        errors = directory / "errors.txt"
        notes.write_text("hello notes\n", encoding="utf-8")
        errors.write_text("", encoding="utf-8")
        namespace = argparse.Namespace(
            status_dir=str(directory),
            branch="demo",
            commit="f" * 40,
            notes_file=str(notes),
            notes_error_file=str(errors),
            parent_pid=os.getpid(),
            name="preflight",
            status="succeeded",
            message=None,
            host="127.0.0.1",
            port=0,
        )
        check(command_start(namespace) == 0, "start failed")
        time.sleep(0.05)
        first_live = current_public(directory, time.time())
        check(first_live["status"] == "queued", f"expected queued, got {first_live['status']}")
        check(isinstance(first_live.get("startedAt"), str) and first_live["startedAt"].endswith("Z"), "queued start time")
        check(first_live["releaseNotes"] == "hello notes", "live notes missing")
        check(command_stage(namespace) == 0, "stage failed")
        time.sleep(1.2)
        moved = current_public(directory, time.time())
        check(moved["status"] == "building", "stage did not enter building")
        check(moved["percent"] > 0, "building percent stayed 0")
        namespace.message = "build script exited 65"
        namespace.status = "failed"
        errors.write_text("note generator exploded\n", encoding="utf-8")
        failed_notes = notes_from_files(str(notes), str(errors))
        check("exploded" in failed_notes, "error notes were dropped")
        check(command_finish(namespace) == 0, "finish failed")
        done = current_public(directory, time.time())
        check(done["status"] == "idle", "closed session should be idle")
        check(done["remainingLabel"] == "none", "closed session remaining should be none")
        check(done["remainingSeconds"] is None, "closed session remaining seconds should be none")
        check(done["branch"] == "demo", "closed session dropped branch")
        check(done["commit"] == "f" * 40, "closed session dropped commit")
        check(done["releaseNotes"] == "hello notes", "closed session dropped notes")
        check(done["error"] is None, "idle status kept the last error")
        check(done["percent"] == 0, "idle status kept a percent")
        check(done["stage"] is None, "idle status kept a stage")
        check(done.get("failure", {}).get("status") == "failed", "failure status missing")
        check(done.get("failure", {}).get("error") == "build script exited 65", "failure error missing")
        check(isinstance(done.get("failure", {}).get("startedAt"), str), "failure start missing")
        stored_session = read_json(session_path(directory)) or {}
        check(stored_session.get("error") == "build script exited 65", "session dropped the failure")
        check(command_active(namespace) == 1, "closed session still active")
        recorded = load_builds(directory)
        check(len(recorded) == 1, f"expected one build row, got {len(recorded)}")
        check(recorded[0]["status"] == "failed", "finish did not mark the build failed")
        check(recorded[0]["platform"] == "ipa", "build platform")
        check(bool(recorded[0].get("finishedAt")), "finish did not set finishedAt")
        check(log_file(directory, recorded[0]["id"]).exists(), "closing deleted the log")
        append_log_line(directory, recorded[0]["id"], "smoke: first line")
        append_log_line(directory, recorded[0]["id"], "TRAKT_CLIENT_SECRET=supersecretvalue")
        append_log_line(directory, recorded[0]["id"], "SIMKL_CLIENT_ID=simkl-value")
        append_log_line(directory, recorded[0]["id"], "local.properties TRAKT_CLIENT_ID=abc")
        append_log_line(directory, recorded[0]["id"], "x" * 800)
        stored_log = log_file(directory, recorded[0]["id"]).read_text(encoding="utf-8")
        check("smoke: first line" in stored_log, "plain log line missing")
        check("supersecretvalue" not in stored_log and "simkl-value" not in stored_log, "secret value stored")
        check(stored_log.count("[redacted]") >= 3, "redaction marker missing")
        check("x" * 501 not in stored_log, "log line was not capped at 500")
        tailed = current_public(directory, time.time())
        check("logTail" in tailed and "recentBuilds" in tailed, "status log fields missing")
        check("smoke: first line" in tailed["logTail"], "logTail dropped the line")
        check("supersecretvalue" not in tailed["logTail"] and "[redacted]" in tailed["logTail"], "logTail leaked a secret")
        check(len(tailed["recentBuilds"]) == 1, "recentBuilds")
        check(tailed["recentBuilds"][0]["id"] == recorded[0]["id"], "recent build id")
        check(tailed["recentBuilds"][0]["finishedAt"], "recent finishedAt")

        httpd = ThreadingHTTPServer(("127.0.0.1", 0), _handler_for(directory))
        thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        thread.start()
        import urllib.request

        port = httpd.server_address[1]
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/", timeout=5) as response:
            page_html = response.read().decode("utf-8")
            page_cache = response.headers.get("Cache-Control", "")
            page_etag = response.headers.get("ETag", "")
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/assets/app.css", timeout=5) as response:
            page_css = response.read().decode("utf-8")
            css_cache = response.headers.get("Cache-Control", "")
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/assets/app.js", timeout=5) as response:
            page_js = response.read().decode("utf-8")
            js_cache = response.headers.get("Cache-Control", "")
        html = page_html + "\n" + page_css + "\n" + page_js
        check("public" in page_cache and "no-store" not in page_cache, f"html cache {page_cache}")
        check("max-age=0" in page_cache and "no-transform" in page_cache, f"html is cacheable by the proxy {page_cache}")
        check("public" in css_cache and "no-store" not in css_cache and "no-transform" in css_cache, f"css cache {css_cache}")
        check("public" in js_cache and "no-store" not in js_cache and "no-transform" in js_cache, f"js cache {js_cache}")
        check(bool(page_etag), "html etag missing")
        check('src="/assets/app.js"' not in page_html, "page still loads the cached script")
        check('href="/assets/app.css"' not in page_html, "page still loads the cached stylesheet")
        check('id="dashboard-seed"' in page_html and "<style>" in page_html, "inline page missing")
        seed_start = page_html.find('id="dashboard-seed"')
        seed_json = page_html.split('id="dashboard-seed" type="application/json">', 1)[1].split("</script>", 1)[0]
        seed_payload = json.loads(seed_json)
        check("downloads" in seed_payload and "cuts" in seed_payload, "seed is not the dashboard")
        check("servedAt" not in seed_payload, "seed etag changes every second")
        check(seed_start > 0, "seed marker missing")
        import urllib.error

        etag_request = urllib.request.Request(
            f"http://127.0.0.1:{port}/",
            headers={"If-None-Match": page_etag},
        )
        try:
            with urllib.request.urlopen(etag_request, timeout=5) as response:
                check(response.status == 304, f"etag status {response.status}")
        except urllib.error.HTTPError as exc:
            check(exc.code == 304, f"etag status {exc.code}")
        check(("<" + "!DOCTYPE html>") not in Path(__file__).read_text(encoding="utf-8"), "page is still inside the python module")
        check("max-width: 28rem" not in html, "28rem sheet is still the page width")
        check("setInterval(refresh, 2000)" not in html, "idle poll is still every 2 seconds")
        check("Request this iPhone build" in html and "Request this Mac build" in html, "request buttons missing")
        check("Idle" not in page_html and 'id="notes"' not in page_html, "idle meter or notes block still on the page")
        check("flex-direction: column" in html, "page is not one column")
        check("min-width: 0" in html, "full commit line cannot shrink inside the column")
        check(
            "#commit" in html
            and "overflow-wrap: anywhere" in html
            and "word-break: break-all" not in html,
            "full commit sha is not forced to wrap inside the column",
        )
        check("viewport-fit=cover" in html, "missing viewport-fit=cover")
        theme_metas = re.findall(
            r"<meta\b[^>]*\bname=[\"']theme-color[\"'][^>]*>",
            page_html,
            flags=re.IGNORECASE,
        )

        def theme_declares(scheme: str) -> bool:
            needle = f"(prefers-color-scheme: {scheme})"
            return any("media=" in tag and needle in tag and 'content="#221b4a"' in tag for tag in theme_metas)

        check(
            theme_declares("dark") and theme_declares("light"),
            "theme-color media missing",
        )
        check(
            bool(theme_metas) and all(re.search(r"\bmedia\s*=", tag) for tag in theme_metas),
            "bare theme-color still served",
        )

        def css_rule(selector: str) -> str:
            match = re.search(rf"(?:^|\n){selector}\s*\{{([^}}]*)\}}", page_css)
            return match.group(1) if match else ""

        check("background-color: #221b4a" in css_rule("html"), "html background is not #221b4a")
        html_rule = css_rule("html")
        check(
            "height: 100lvh" in html_rule
            and "overflow: hidden" in html_rule
            and "height: 100svh" not in html_rule,
            "html is not the large viewport",
        )
        check("background-image" not in page_css, "stylesheet still has a background-image")
        check("background-color: #221b4a" in css_rule("body"), "body background is not #221b4a")
        body_rule = css_rule("body")
        check(
            "height: 100lvh" in body_rule
            and "overflow: hidden" in body_rule
            and "height: 100svh" not in body_rule,
            "body is not the large viewport",
        )
        check("position: relative" in body_rule, "body is not the teal positioning box")
        grouped = css_rule("html, body")
        check(bool(grouped), "grouped html, body rule missing")
        check("min-height" not in grouped, "grouped html, body still sets min-height")
        for selector in ("html", "body"):
            rule = css_rule(selector)
            check(bool(rule), f"{selector} rule missing")
            check(
                "min-height: 100%" not in rule
                and "min-height: 100vh" not in rule
                and "min-height: 100lvh" not in rule,
                f"{selector} min-height still covers the toolbar",
            )
        check("min-height: 100lvh" not in page_css, "large viewport minimum still covers the toolbar")
        after_rule = css_rule("body::after")
        check(
            "position: absolute" in after_rule and "position: fixed" not in after_rule,
            "teal light is still fixed to the visual viewport",
        )
        check("position: fixed" in css_rule("body::before"), "violet light is no longer fixed")
        main_rule = css_rule("main")
        check(
            "height: 100svh" in main_rule
            and "overflow-y: auto" in main_rule
            and "height: 100%" not in main_rule,
            "main still fills the strip behind the pill",
        )
        check(
            'content="#07080d"' not in page_html
            and 'content="#1a1430"' not in page_html
            and "background-color: #07080d" not in page_html
            and "background-color: #1a1430" not in page_html
            and "--bg: #07080d" not in page_html
            and "--bg: #1a1430" not in page_html,
            "old canvas or theme color still served",
        )
        check("radial-gradient" in page_html, "served backdrop missing")
        check("overflow-x: hidden" not in html, "overflow-x hidden still clips the backdrop")
        check(
            "safe-area-inset-top" in html and "safe-area-inset-bottom" in html,
            "missing safe-area insets",
        )
        check("color-scheme: dark" in html, "page is not dark-first")
        check("@media (prefers-color-scheme: light)" not in html, "light theme still overrides night glass")
        check(
            "#221b4a" in page_css
            and "background-color: #221b4a" in page_css
            and "#07080d" not in page_css
            and "#1a1430" not in page_css,
            "night background missing",
        )
        check("rgba(16, 18, 28, 0.72)" in page_css, "frosted card fill missing")
        check("backdrop-filter: blur(18px)" in page_css, "card blur missing")
        check("radial-gradient" in page_css, "drifting light missing")
        check("@keyframes rise" in page_css and "@keyframes sheen" in page_css, "rise or sheen missing")
        check("28s" in page_css, "light drift timing missing")
        check("prefers-reduced-motion: reduce" in page_css, "reduced motion does not stop the night motion")
        check("#101218" not in html, "forced dark background still in the page")
        check("Show all" in page_js, "summary disclosure missing")
        check("Show Less" not in html and "Show All" not in html, "notes toggle is still a second control")
        check(
            'fetch("/api/dashboard"' in html and "IDLE_POLL_MS = 30000" in html and "ACTIVE_POLL_MS = 2000" in html,
            "dashboard poll missing",
        )
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/status", timeout=5) as response:
            body = json.loads(response.read().decode("utf-8"))
            check(response.headers.get("Cache-Control") == "no-store", "json api is cacheable")
        check(body["status"] == "idle", "api did not serve idle for a closed session")
        check(body["remainingLabel"] == "none", "api remaining was not none")
        check(body["branch"] == "demo", "api dropped branch")
        check(body["commit"] == "f" * 40, "api dropped commit")
        check(body["releaseNotes"] == "hello notes", "api dropped notes")
        check(body["error"] is None, "api kept the idle error")
        check(body["percent"] == 0, "api kept an idle percent")
        check(body["stage"] is None, "api kept an idle stage")
        check("servedAt" in body, "api timestamp missing")
        check("<h1>Nuvio</h1>" in page_html, "masthead missing")
        check("Download the current app, or choose how far the next one goes." in page_html, "lede missing")
        check(">iPhone</h2>" in page_html and ">Mac</h2>" in page_html, "platform headings missing")
        check("Nuvio builds" not in page_html, "old builds title still on the page")
        check("IPA debug" not in html, "debug file is still a current label")
        check("Older downloads" in page_html, "older disclosure missing")
        check("Windows and Linux have no package." not in html, "windows line still on the page")
        check("copy-link" not in html, "copy control still on the page")
        check("min-height: 44px" in page_css, "44px targets missing")
        check(":focus-visible" in page_css, "focus ring missing")
        check("font-size: 16px" in page_css, "body size missing")
        check("-apple-system" in page_css, "system font missing")
        check("background: transparent" in page_css, "outline button lost its transparent fill")
        check("a.download-link" in page_css and "background: #ffffff" in page_css, "download is not the white pill")
        check('fetch("/api/desktop"' not in page_js and 'fetch("/api/downloads"' not in page_js, "page still polls split endpoints")
        check("min-width: 840px" in html and "1120px" in html, "wide layout missing")
        check("minmax(0, 1fr) minmax(0, 1fr)" in html, "two equal columns missing")
        check("a.download-link" in html and "min-height: 44px" in html, "download control size missing")
        check("max-width: 1120px" in html, "page width missing")
        check("prefers-reduced-motion" in html, "reduced motion missing")
        check('id="log-view"' not in page_html and 'id="desktop-log-view"' not in page_html, "log is on the idle page")
        check("Show log" in page_js, "log disclosure missing")
        check("Update everything" in page_js and "Choose commits" in page_html, "update control missing")
        check('id="upstream-run"' in page_html and 'class="upstream"' in page_html, "upstream band missing")
        check(page_html.find('id="upstream-run"') < page_html.find('class="products"'), "upstream band is not before the products")
        check("Run merge and build" in page_html, "run button missing")
        check('id="upstream-run-button" hidden' in page_html, "run button starts visible")
        check("Next automatic merge and build in" in page_js, "countdown copy missing")
        check("Merge and build is running." in page_js, "running line missing")
        check("Upstream is already merged." in page_js, "current line missing")
        check("upstream could not be checked" in page_js, "unavailable line missing")
        check("commits that are not merged" in page_js, "ahead line missing")
        check('fetch("/api/upstream-run"' in page_js, "upstream post missing")
        check("setInterval(tickUpstream, 1000)" in page_js, "countdown is not every second")
        check("clearInterval(upstreamTimer)" not in page_js, "poll clears the countdown")
        check("127.0.0.1:3100" not in page_js and "/api/routines/" not in page_js, "page calls paperclip")
        check("upstreamRun" in seed_payload and isinstance(seed_payload["upstreamRun"].get("nextAt"), str), "seed missing upstream run")
        check(seed_payload["upstreamRun"].get("lines") == ["Upstream is already merged."], "seed lines")
        check(seed_payload["upstreamRun"].get("running") is False, "seed running")
        check("Replace the running build" in page_js, "replace control missing")
        check(
            "This stops the compile that is running and requests the latest instead." in page_js,
            "override confirmation missing",
        )
        check("flex: 0 0 auto" in page_css, "percent can leave the card")
        check("This download is already the latest on " in page_js, "latest line missing")
        check(
            "Checking a commit includes that commit and everything before it." in page_js,
            "prefix sentence missing",
        )
        check("both land in the package." in page_js, "same-file sentence missing")
        check(body["logTail"] and "[redacted]" in body["logTail"], "api logTail missing")
        check("supersecretvalue" not in body["logTail"], "api logTail leaked")
        check(body["recentBuilds"] and body["recentBuilds"][0]["status"] == "failed", "api recentBuilds")
        import urllib.error
        build_id = body["recentBuilds"][0]["id"]
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/builds/{build_id}/log", timeout=5) as response:
            log_type = response.headers.get("Content-Type", "")
            log_body = response.read().decode("utf-8")
        check(log_type.startswith("text/plain"), f"log content type {log_type}")
        check("smoke: first line" in log_body and "supersecretvalue" not in log_body, "build log endpoint")
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{port}/api/builds/not-a-real-build/log", timeout=5)
            check(False, "unknown build log should 404")
        except urllib.error.HTTPError as exc:
            check(exc.code == 404, f"unknown build log returned {exc.code}")

        import urllib.error

        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/desktop", timeout=5) as response:
            desktop_body = json.loads(response.read().decode("utf-8"))
        check(desktop_body["status"] == "idle", "missing desktop session should be idle")
        check(desktop_body["remainingLabel"] == "none", "missing desktop remaining")
        check(desktop_body["remainingSeconds"] is None, "missing desktop remaining seconds")
        check(
            desktop_body["branch"] == "none"
            and desktop_body["commit"] == "none"
            and desktop_body["releaseNotes"] == "none",
            "missing desktop identity",
        )
        check(desktop_body["logTail"] == "" and desktop_body["recentBuilds"] == [], "desktop log fields")
        write_json(
            directory / "desktop-session.json",
            {
                "status": "building",
                "percent": 42.5,
                "remainingLabel": "3m 02s",
                "remainingSeconds": 182,
                "stage": "package",
                "branch": "Dev",
                "commit": "abc123def",
                "releaseNotes": "desktop notes",
                "error": None,
            },
        )
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/desktop", timeout=5) as response:
            building = json.loads(response.read().decode("utf-8"))
        check(building["status"] == "building", "desktop fixture status")
        check(building["percent"] == 42.5, f"desktop fixture percent {building.get('percent')}")
        check(building["remainingLabel"] == "3m 02s", "desktop fixture remaining")
        check(building["branch"] == "Dev" and building["commit"] == "abc123def", "desktop fixture identity")
        check(building["releaseNotes"] == "desktop notes", "desktop fixture notes")
        write_json(
            directory / "desktop-session.json",
            {
                "status": "failed",
                "closed": True,
                "percent": 18,
                "frozenPercent": 18,
                "remainingLabel": "9m 00s",
                "remainingSeconds": 540,
                "stage": "package",
                "branch": "Dev",
                "commit": "abc123def",
                "releaseNotes": "desktop notes",
                "error": "disk full",
            },
        )
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/desktop", timeout=5) as response:
            closed_desktop = json.loads(response.read().decode("utf-8"))
        check(closed_desktop["status"] == "idle", "closed desktop should read idle")
        check(closed_desktop["remainingLabel"] == "none", "closed desktop remaining label")
        check(closed_desktop["remainingSeconds"] is None, "closed desktop remaining seconds")
        check(closed_desktop["error"] is None, "closed desktop kept the error in the header")
        check(closed_desktop["percent"] == 0, "closed desktop kept a percent")
        check(closed_desktop["stage"] is None, "closed desktop kept a stage")
        check(closed_desktop.get("failure", {}).get("error") == "disk full", "desktop failure error")
        check(closed_desktop.get("failure", {}).get("stage") == "package", "desktop failure stage")
        (directory / "desktop-session.json").unlink()

        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/downloads", timeout=5) as response:
            empty_downloads = json.loads(response.read().decode("utf-8"))
        check(empty_downloads["ipa"] is None, "ipa download should start empty")
        check(empty_downloads["desktop"]["macos"] is None, "macos should start empty")
        check(empty_downloads["desktop"]["windows"] is None and empty_downloads["desktop"]["linux"] is None, "other desktops")

        sample_ipa = directory / "sample.ipa"
        with zipfile.ZipFile(sample_ipa, "w") as archive:
            archive.writestr("Payload/Hello.txt", "hi")
        bad_ipa = directory / "bad.ipa"
        bad_ipa.write_bytes(b"not a zip")
        check(
            command_record_download(
                argparse.Namespace(
                    status_dir=str(directory),
                    kind="ipa",
                    path=str(bad_ipa),
                    version="0.0.1",
                    commit="abc",
                )
            )
            == 2,
            "non-zip ipa was recorded",
        )
        check(
            command_record_download(
                argparse.Namespace(
                    status_dir=str(directory),
                    kind="ipa",
                    path=str(sample_ipa),
                    version="0.5.3",
                    commit="3" * 40,
                )
            )
            == 0,
            "zip ipa was not recorded",
        )
        sample_dmg = directory / "Nuvio-macOS-arm64-0.1.26-alpha.dmg"
        sample_dmg.write_bytes(b"dmg-bytes")
        check(
            command_record_download(
                argparse.Namespace(
                    status_dir=str(directory),
                    kind="macos",
                    path=str(sample_dmg),
                    version=None,
                    commit="d" * 40,
                )
            )
            == 0,
            "macos package was not recorded",
        )
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/downloads", timeout=5) as response:
            listed = json.loads(response.read().decode("utf-8"))
        check(listed["ipa"]["filename"] == "sample.ipa", "ipa filename")
        check(listed["ipa"]["bytes"] == sample_ipa.stat().st_size, "ipa bytes")
        check(listed["ipa"]["commit"] == "3" * 40, "ipa commit")
        check(listed["ipa"]["version"] == "0.5.3", "ipa version")
        check("file" not in listed["ipa"], "download record leaked storage name")
        check(listed["desktop"]["macos"]["filename"] == sample_dmg.name, "macos filename")
        check(listed["desktop"]["macos"]["version"] == "0.1.26-alpha", "macos version inferred")
        check(listed["desktop"]["windows"] is None and listed["desktop"]["linux"] is None, "windows and linux stayed empty")
        check(listed["ipa"]["savedName"] == "Nuvio-iPhone-0.5.3-33333333.ipa", f"ipa saved name {listed['ipa'].get('savedName')}")
        check(listed["ipa"]["copyText"] == "Nuvio for iPhone 0.5.3 · 33333333", f"ipa copy text {listed['ipa'].get('copyText')}")
        check(listed["desktop"]["macos"]["savedName"] == "Nuvio-Mac-0.1.26-alpha-dddddddd.dmg", "macos saved name")
        check(listed["desktop"]["macos"]["copyText"] == "Nuvio for Mac 0.1.26-alpha · dddddddd", "macos copy text")
        check(listed.get("older") == [], "fresh catalog should have no older packages")
        check("ipa-debug" not in listed, "debug ipa is still a current download")
        check(published_failure_notice(directory, "f" * 40) == PACKAGE_NOTICE, "failed iphone package notice")
        check(published_failure_notice(directory, "3" * 40) is None, "notice attached to a different commit")
        extra = artifacts_directory(directory) / "nuvio-0.5.2-full-debug.ipa"
        with zipfile.ZipFile(extra, "w") as archive:
            archive.writestr(
                "Payload/Nuvio.app/Info.plist",
                plistlib.dumps({"CFBundleShortVersionString": "0.5.2", "CFBundleVersion": "134"}),
            )
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/downloads", timeout=5) as response:
            aged = json.loads(response.read().decode("utf-8"))
        check(aged["ipa"]["filename"] == "sample.ipa", "older ipa replaced the current ipa")
        check(len(aged["older"]) == 1 and aged["older"][0]["filename"] == extra.name, f"older row {aged.get('older')}")
        check(aged["older"][0]["version"] == "0.5.2", "older version")
        check(aged["older"][0]["build"] == "134", "older build")
        check(aged["older"][0]["status"] == "older", "older status")
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/download/ipa", timeout=5) as response:
            disposition = response.headers.get("Content-Disposition", "")
            downloaded = response.read()
        check(disposition.startswith("attachment;"), f"ipa disposition {disposition}")
        check('filename="Nuvio-iPhone-0.5.3-33333333.ipa"' in disposition, f"ipa filename header {disposition}")
        check(downloaded == sample_ipa.read_bytes(), "ipa download bytes mismatch")
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/download/desktop/macos", timeout=5) as response:
            dmg_disposition = response.headers.get("Content-Disposition", "")
            dmg_bytes = response.read()
        check(dmg_disposition.startswith("attachment;"), f"dmg disposition {dmg_disposition}")
        check('filename="Nuvio-Mac-0.1.26-alpha-dddddddd.dmg"' in dmg_disposition, f"dmg filename header {dmg_disposition}")
        check(dmg_bytes == b"dmg-bytes", "dmg download bytes mismatch")
        with urllib.request.urlopen(
            f"http://127.0.0.1:{port}/download/older/{extra.name}",
            timeout=5,
        ) as response:
            older_bytes = response.read()
        check(older_bytes == extra.read_bytes(), "older ipa bytes mismatch")
        extra.unlink()
        for missing in ("/download/desktop/windows", "/download/desktop/linux"):
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{port}{missing}", timeout=5)
                check(False, f"{missing} should 404")
            except urllib.error.HTTPError as exc:
                check(exc.code == 404, f"{missing} returned {exc.code}")
        (artifacts_directory(directory) / "sample.ipa").unlink()
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{port}/download/ipa", timeout=5)
            check(False, "missing ipa file should 404")
        except urllib.error.HTTPError as exc:
            check(exc.code == 404, f"missing ipa file returned {exc.code}")
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/downloads", timeout=5) as response:
            after_delete = json.loads(response.read().decode("utf-8"))
        check(after_delete["ipa"] is None, "missing ipa file should not stay a link")
        published = artifacts_directory(directory) / "desktop"
        published.mkdir(parents=True)
        (published / "windows.msi").write_bytes(b"msi")
        write_json(
            published / "manifest.json",
            {
                "macos": {
                    "filename": "macos.dmg",
                    "bytes": 9,
                    "sha256": listed["desktop"]["macos"]["sha256"],
                    "version": "0.1.26-alpha",
                    "commit": "unknown",
                },
                "windows": {
                    "filename": "windows.msi",
                    "bytes": 3,
                    "sha256": "abc",
                    "version": "0.1.26-alpha",
                    "commit": "eeeeeee",
                },
            },
        )
        (published / "macos.dmg").write_bytes(b"dmg-bytes")
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/downloads", timeout=5) as response:
            merged = json.loads(response.read().decode("utf-8"))
        check(merged["desktop"]["macos"]["filename"] == sample_dmg.name, "same sha should keep the seeded dmg name")
        check(merged["desktop"]["windows"]["filename"] == "windows.msi", "published windows package missing")
        check(merged["desktop"]["linux"] is None, "linux stayed empty")
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/download/desktop/windows", timeout=5) as response:
            windows_bytes = response.read()
            windows_disposition = response.headers.get("Content-Disposition", "")
        check(windows_bytes == b"msi", "windows download bytes")
        check('filename="windows.msi"' in windows_disposition, f"windows disposition {windows_disposition}")
        for offset in range(45):
            open_build_record(directory, {"branch": "b", "commit": "c" * 40}, now + offset)
        capped = load_builds(directory)
        check(len(capped) == 40, f"build index cap {len(capped)}")
        check(len(recent_builds(directory, "ipa")) == 8, "recent window is not 8")

        check("No summary for this file." not in html and "No release notes" not in html, "empty notes copy still on the page")
        check("In this version" not in page_html, "version group still on the idle page")
        check("In the next IPA" not in html and "In the next DMG" not in html, "next package queues still on the page")
        check("Stashed while testing" not in html, "stash queue still on the page")
        check("Working now" not in html and "next-grid" not in html, "operations board still on the page")
        check("hero-kicker" not in html and "build-row" not in html, "compile hero still on the page")
        check("applyWork" not in page_js and 'fetch("/api/dashboard"' in page_js, "page still renders the work snapshot")
        check("more in Release notes" not in page_js, "old summary cap copy")
        check("Nothing is stashed" not in html and "Nothing new is queued." not in html, "empty queue copy still on the page")

        check(task_credit("done", 10, 10) == 1.0, "done credit")
        check(task_credit("in_progress", 3000, 3000) == 0.7, "spent ratio caps at 0.7")
        check(task_credit("in_progress", 0, 3000) == 0.4, "in progress without spent")
        check(abs(task_credit("in_review", 1000, 2000) - 0.5) < 1e-9, "in review ratio")
        check(task_credit("in_review", 0, None) == 0.7, "in review default")
        check(task_credit("todo", 5, 5) == 0.0 and task_credit("blocked", 5, 5) == 0.0, "todo and blocked credit")
        check(int(round(100.0 * (1 + 0.7 + 0.4 + 0.5) / 6)) == 43, "bar percent")
        moment = 1_700_000_000.0
        check(
            run_seconds(
                {"status": "succeeded", "startedAt": iso(moment), "finishedAt": iso(moment + 10 * 3600)},
                moment + 10 * 3600,
            )
            == RUN_CAP_SECONDS,
            "6 hour cap",
        )
        check(
            run_seconds(
                {"status": "running", "startedAt": iso(moment - 10 * 3600), "finishedAt": None},
                moment,
            )
            == RUN_CAP_SECONDS,
            "running 6 hour cap",
        )
        six_subjects = "\n".join(f"- {'a' * 7}{index} subject {index} @ann" for index in range(1, 7))
        summary = version_summary(six_subjects)
        check(summary["lines"][:4] == [f"subject {index}" for index in range(1, 5)], f"summary subjects {summary}")
        check(summary["lines"][4] == "And 2 more in Release notes", f"summary cap {summary}")
        check(all("@" not in line for line in summary["lines"]), "summary kept the author")
        check(version_summary("no commit rows")["lines"] == ["No summary for this file."], "empty summary")
        check(
            agent_time_line(
                [
                    {"status": "todo", "expected": 6000, "spent": 600},
                    {"status": "blocked", "expected": 6000, "spent": 0},
                ]
            )
            == "Agent time: 3 hr 10 min",
            "full agent estimate",
        )
        check(
            agent_time_line(
                [
                    {"status": "todo", "expected": 3600, "spent": 0},
                    {"status": "todo", "expected": None, "spent": 0},
                ]
            )
            == "Agent time: at least 60 min, 1 task has no estimate",
            "partial agent estimate",
        )
        check(
            agent_time_line([{"status": "in_progress", "expected": None, "spent": 10}])
            == "Agent time: estimate unavailable",
            "missing agent estimate",
        )
        goal = {
            "id": "sim-111",
            "identifier": "SIM-111",
            "status": "blocked",
            "title": "One current iPhone IPA and one current Mac DMG",
            "projectId": "project-nuvio",
            "issueNumber": 111,
            "parentId": None,
        }
        child = {
            "id": "sim-10",
            "identifier": "SIM-10",
            "status": "todo",
            "title": "iOS: child",
            "parentId": "sim-111",
            "projectId": "project-nuvio",
            "issueNumber": 10,
        }
        grand = {
            "id": "sim-11",
            "identifier": "SIM-11",
            "status": "todo",
            "title": "macOS: grand",
            "parentId": "sim-10",
            "projectId": "project-nuvio",
            "issueNumber": 11,
        }
        deeper = {
            "id": "sim-12",
            "identifier": "SIM-12",
            "status": "todo",
            "title": "iOS: too deep",
            "parentId": "sim-11",
            "projectId": "project-nuvio",
            "issueNumber": 12,
        }
        both = {
            "id": "sim-13",
            "identifier": "SIM-13",
            "status": "todo",
            "title": "iOS: also a DMG and a Mac",
            "parentId": "sim-111",
            "projectId": "project-nuvio",
            "issueNumber": 13,
        }
        page_issue = {
            "id": "sim-14",
            "identifier": "SIM-14",
            "status": "todo",
            "title": "Builds page: name the IPA",
            "parentId": "sim-111",
            "projectId": "project-nuvio",
            "issueNumber": 14,
        }
        ipa_ids, dmg_ids = membership_lists([goal, child, grand, deeper, both, page_issue], goal)
        check(ipa_ids == ["SIM-10", "SIM-13"], f"ipa membership {ipa_ids}")
        check(dmg_ids == ["SIM-11"], f"dmg membership {dmg_ids}")
        check("SIM-12" not in ipa_ids and "SIM-14" not in ipa_ids + dmg_ids, "depth and builds page")
        check(package_for_title("Ship the IPA and the Mac DMG") is None, "both packages without a prefix")
        newer = {
            "id": "sim-200",
            "identifier": "SIM-200",
            "status": "todo",
            "title": "One current desktop",
            "projectId": "project-nuvio",
            "createdAt": "2026-10-01T00:00:00Z",
        }
        older = {
            "id": "sim-100",
            "identifier": "SIM-100",
            "status": "todo",
            "title": "One current old",
            "projectId": "project-nuvio",
            "createdAt": "2026-01-01T00:00:00Z",
        }
        check(
            select_goal([dict(goal, status="done"), older, newer])["identifier"] == "SIM-200",
            "newest open goal",
        )

        held_dir = directory / "held-root"
        held_dir.mkdir()
        write_json(
            downloads_path(held_dir),
            {
                "ipa": {"bytes": 1, "commit": "7f6b9bb9abcdef", "filename": "a.ipa", "sha256": "aa", "version": "0.5.4"},
                "desktop": {
                    "linux": None,
                    "macos": {"bytes": 1, "commit": "abc123", "filename": "m.dmg", "sha256": "bb", "version": "0.1.0"},
                    "windows": None,
                },
            },
        )
        seeded = reconcile_held_back(held_dir)
        check(seeded["dmg"] == [], "dmg stash starts empty")
        check(
            seeded["ipa"] == [{"issue": "SIM-122", "summary": HELD_BACK_IPA_SEED}],
            "ipa stash seed",
        )
        check("pre-pr11-dmg" not in json.dumps(seeded), "git stash was seeded")
        seeded["ipa"].append({"issue": "SIM-1", "summary": "second sentence"})
        write_json(held_back_path(held_dir), seeded)
        catalog = load_catalog(held_dir)
        catalog["ipa"]["commit"] = "ffffffffffffffff"
        write_json(downloads_path(held_dir), catalog)
        cleared = reconcile_held_back(held_dir)
        check(cleared["ipa"] == [], f"held-back did not clear on commit change: {cleared['ipa']}")
        check(cleared["watched"]["ipa"] == "ffffffffffffffff", "watched commit")
        long_summary = "x" * 181
        check(
            command_held_back_add(
                argparse.Namespace(status_dir=str(held_dir), platform="ipa", summary=long_summary)
            )
            == 2,
            "long stash summary was accepted",
        )
        check(
            command_held_back_add(
                argparse.Namespace(status_dir=str(held_dir), platform="dmg", summary="one sentence")
            )
            == 0,
            "stash add failed",
        )
        added = read_json(held_back_path(held_dir)) or {}
        check(added["dmg"] == [{"summary": "one sentence"}], f"stash add {added.get('dmg')}")
        check(
            command_held_back_clear(argparse.Namespace(status_dir=str(held_dir), platform="dmg")) == 0,
            "stash clear failed",
        )
        check((read_json(held_back_path(held_dir)) or {})["dmg"] == [], "stash clear did not empty dmg")

        leak = {
            "description": "LEAK-DESCRIPTION",
            "stdout": "LEAK-STDOUT",
            "stderr": "LEAK-STDOUT",
            "workspacePath": "/Users/leak/workspace",
            "authorization": "Bearer leak-token",
            "stash": "stash@{0}",
            "diff": "@@ -1,2 +1,2 @@",
            "stashName": "pre-pr11-dmg",
        }

        def leaked_issue(identifier: str, number: int, status: str, title: str, parent: str | None, **extra: object) -> dict:
            row = {
                "id": identifier.lower(),
                "identifier": identifier,
                "issueNumber": number,
                "status": status,
                "title": title,
                "parentId": parent,
                "projectId": "project-nuvio",
                "createdAt": "2026-09-01T00:00:00Z",
                "completedAt": "2026-09-02T00:00:00Z" if status == "done" else None,
                "updatedAt": "2026-09-02T00:00:00Z",
                "assigneeAgentId": "agent-1",
            }
            row.update(leak)
            row.update(extra)
            return row

        clock = 1_000_000.0
        stub_issues = [
            leaked_issue("SIM-111", 111, "blocked", "One current iPhone IPA and one current Mac DMG", None),
            leaked_issue("SIM-201", 201, "done", "iOS: hide the key", "sim-111"),
            leaked_issue("SIM-202", 202, "in_progress", "iOS: player scrim", "sim-111", executionRunId="run-202"),
            leaked_issue("SIM-203", 203, "in_review", "QA: check the IPA", "sim-111"),
            leaked_issue("SIM-204", 204, "todo", "Builds page: site task", "sim-111"),
            leaked_issue("SIM-205", 205, "cancelled", "iOS: dropped", "sim-111"),
            leaked_issue("SIM-207", 207, "in_progress", "Live Nuvio run", "other", executionRunId="run-live"),
        ]
        stub = {
            "agents": [{"id": "agent-1", "name": "Nuvio Engineer", "adapterConfig": {"token": "Bearer leak-token"}}],
            "issues": stub_issues,
            "liveRuns": [
                {
                    "id": "run-202",
                    "issueId": "sim-202",
                    "agentId": "agent-1",
                    "agentName": "Nuvio Engineer",
                    "status": "running",
                    "startedAt": iso(clock - 100),
                    "finishedAt": None,
                    "stdout": "LEAK-STDOUT",
                    "lastOutputStream": "stdout",
                    "description": "LEAK-DESCRIPTION",
                },
                {
                    "id": "run-live",
                    "issueId": "sim-207",
                    "agentId": "agent-1",
                    "agentName": "Nuvio Engineer",
                    "status": "running",
                    "startedAt": iso(clock - 50),
                    "finishedAt": None,
                    "stdout": "LEAK-STDOUT",
                },
            ],
            "runs": {
                "sim-201": [
                    {
                        "runId": "run-201",
                        "status": "succeeded",
                        "startedAt": iso(clock - 10 * 3600),
                        "finishedAt": iso(clock),
                        "agentId": "agent-1",
                        "stdout": "LEAK-STDOUT",
                        "logPath": "/Users/leak/workspace/run.log",
                        "usageJson": {
                            "inputTokens": 15_600_000,
                            "cachedInputTokens": 2000,
                            "outputTokens": 40,
                            "billingType": "subscription_included",
                            "costStatus": "unpriced",
                            "model": "grok-build",
                            "raw": "Bearer leak-token",
                        },
                    }
                ],
                "sim-202": [
                    {
                        "runId": "run-202",
                        "status": "running",
                        "startedAt": iso(clock - 100),
                        "finishedAt": None,
                        "agentId": "agent-1",
                        "stdout": "LEAK-STDOUT",
                        "usageJson": None,
                        "workspacePath": "/Users/leak/workspace",
                    }
                ],
            },
        }
        calls = {"n": 0}

        def stub_reader() -> dict:
            calls["n"] += 1
            if calls["n"] >= 3:
                raise RuntimeError("paperclip down")
            return stub

        # The page document reads the work snapshot. That can store a manifest
        # before this stub exists. Drop it so the fixture derives its own list.
        next_package_path(directory).unlink(missing_ok=True)
        WORK_CACHE.reader = stub_reader
        reset_work_cache()
        session = read_json(session_path(directory)) or {}
        session["releaseNotes"] = six_subjects
        write_json(session_path(directory), session)
        catalog_now = load_catalog(directory)
        write_json(
            held_back_path(directory),
            {
                "dmg": [],
                "ipa": [
                    {
                        "diff": "@@ -1,2 +1,2 @@",
                        "issue": "SIM-122",
                        "stash": "stash@{0}",
                        "summary": "kept sentence",
                    }
                ],
                "stashName": "pre-pr11-dmg",
                "watched": {
                    "dmg": platform_commit(catalog_now, "dmg"),
                    "ipa": platform_commit(catalog_now, "ipa"),
                },
            },
        )
        first = public_work(directory, clock)
        second = public_work(directory, clock + 10)
        check(calls["n"] == 1, f"paperclip was read {calls['n']} times inside 15s")
        later = public_work(directory, clock + 20)
        check(calls["n"] == 2, f"paperclip did not refresh after 15s ({calls['n']})")
        check(first["dmg"]["headline"] == "Nothing is queued." and first["dmg"]["percent"] is None, "empty dmg list")
        check(first["dmg"]["tasks"] == [], "empty dmg tasks")
        check(first["ipa"]["headline"] == "1 of 3 tasks done", f"ipa headline {first['ipa']['headline']}")
        check(first["ipa"]["percent"] == 70, f"ipa percent {first['ipa']['percent']}")
        check(first["ipa"]["inNext"]["items"] == ["player scrim", "hide the key"], f"inNext {first['ipa']['inNext']}")
        check(all(not item.startswith("QA") for item in first["ipa"]["inNext"]["items"]), "QA title in inNext")
        check("check the IPA" not in first["ipa"]["inNext"]["items"], "QA title was summarized")
        done_task = next(task for task in first["ipa"]["tasks"] if task["id"] == "SIM-201")
        check(done_task["elapsedSeconds"] == RUN_CAP_SECONDS, f"task 6 hour cap {done_task['elapsedSeconds']}")
        check(done_task["tokens"] == "15.6M in · 2.0k cached · 40 out", f"tokens {done_task.get('tokens')}")
        check(done_task["billing"] == "subscription, unpriced", "billing text")
        check(done_task.get("model") == "grok-build", "model")
        open_task = next(task for task in first["ipa"]["tasks"] if task["id"] == "SIM-202")
        check(open_task["tokens"] == "tokens post when the run finishes", f"open tokens {open_task.get('tokens')}")
        check(open_task["elapsedSeconds"] == 100, f"open elapsed {open_task['elapsedSeconds']}")
        check(later["ipa"]["tasks"][0]["id"] == "SIM-202", "task order")
        running_later = next(task for task in later["ipa"]["tasks"] if task["id"] == "SIM-202")
        check(running_later["elapsedSeconds"] == 120, f"elapsed did not advance {running_later['elapsedSeconds']}")
        check(first["ipa"]["inThisVersion"]["lines"][4] == "And 2 more in Release notes", "api summary cap")
        check(first["ipa"]["agentTime"] != first["ipa"]["compile"], "agent time mixed with compile")
        check(first["ipa"]["compile"].startswith("Compile after the tasks:"), first["ipa"]["compile"])
        check(first["ipa"]["stashed"]["items"] == [{"issue": "SIM-122", "summary": "kept sentence"}], "public stash")
        workers = {row["id"]: row for row in first["workingNow"]}
        check(workers["SIM-202"]["package"] == "ipa" and workers["SIM-202"]["agent"] == "Nuvio Engineer", "working package")
        check(workers["SIM-207"]["package"] == "neither", "working neither")
        check(workers["SIM-207"]["elapsedSeconds"] == 50, "working elapsed")
        check(
            next(row for row in later["workingNow"] if row["id"] == "SIM-207")["elapsedSeconds"] == 70,
            "working elapsed did not advance",
        )
        stale_at = later["updatedAt"]
        failed = public_work(directory, clock + 40)
        check(calls["n"] == 3, "down paperclip was not attempted")
        check(failed["updatedAt"] == stale_at, "down paperclip replaced updatedAt")
        check(failed["ipa"]["headline"] == "1 of 3 tasks done", "down paperclip dropped the snapshot")
        rendered = json.dumps(failed)
        for banned in (
            "LEAK-DESCRIPTION",
            "LEAK-STDOUT",
            "/Users/leak/workspace",
            "Bearer leak-token",
            "stash@{",
            "@@ -1,2 +1,2 @@",
            "usageJson",
            "pre-pr11-dmg",
            "stdout",
            "description",
        ):
            check(banned not in rendered, f"fixture output contains {banned}")
        reset_work_cache()
        WORK_CACHE.reader = lambda: stub
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/work", timeout=5) as response:
            live_body = response.read().decode("utf-8")
            live_work = json.loads(live_body)
        check(live_work["ipa"]["headline"] == "1 of 3 tasks done", "api work headline")
        for banned in (
            "LEAK-DESCRIPTION",
            "LEAK-STDOUT",
            "/Users/leak/workspace",
            "Bearer leak-token",
            "stash@{",
            "@@ ",
            "usageJson",
            "pre-pr11-dmg",
            "stdout",
            "description",
        ):
            check(banned not in live_body, f"api work contains {banned}")
        import shutil
        import subprocess

        saved_mobile = builds_request.mobile_repository
        saved_desktop = builds_request.desktop_repository
        saved_post = builds_request.post_ledger_comment
        saved_ledger = builds_request.refresh_ledger
        saved_catalog = downloads_path(directory).read_text(encoding="utf-8")
        saved_session = session_path(directory).read_text(encoding="utf-8") if session_path(directory).exists() else None
        repo = Path(tempfile.mkdtemp(prefix="nuvio-cut-"))
        comments: list[str] = []

        def quiet_ledger(now: float, force: bool = False) -> dict:
            return {"at": now, "cancelled": {"dmg": [], "ipa": []}, "index": {}}

        def record_comment(body: str) -> None:
            comments.append(body)

        try:
            hooks = repo / "no-hooks"
            hooks.mkdir()
            subprocess.run(["git", "init", "-b", "enhanced", str(repo)], check=True, capture_output=True, text=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.email", "test@example.com"], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.name", "Nuvio Test"], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(repo), "config", "core.hooksPath", str(hooks)], check=True, capture_output=True)

            file_count = {"n": 0}

            def git_commit_file(subject: str, body: str = "") -> str:
                file_count["n"] += 1
                name = f"line-{file_count['n']}.txt"
                (repo / name).write_text(subject + "\n", encoding="utf-8")
                subprocess.run(["git", "-C", str(repo), "add", "--", name], check=True, capture_output=True)
                command = ["git", "-C", str(repo), "commit", "-m", subject]
                if body:
                    command.extend(["-m", body])
                subprocess.run(command, check=True, capture_output=True, text=True)
                return subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip().lower()

            base = git_commit_file("served package")
            mid = git_commit_file("SIM-10 first cut")
            tip = git_commit_file("SIM-11 tip cut", "Also SIM-12")
            subprocess.run(["git", "-C", str(repo), "checkout", "-b", "side"], check=True, capture_output=True)
            side = git_commit_file("side only SIM-99")
            subprocess.run(["git", "-C", str(repo), "checkout", "enhanced"], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(repo), "branch", "Dev", base], check=True, capture_output=True)
            builds_request.clear_commit_cache()
            listed = builds_request.list_commits(repo, "refs/heads/enhanced", base)
            check(listed.get("ok") is True, f"commit list {listed.get('error')}")
            listed_ids = [row["commit"] for row in listed.get("commits") or []]
            check(listed_ids == [tip, mid], f"commit order {listed_ids}")
            check(side not in listed_ids, "side branch commit was listed")
            check(listed["commits"][0]["issues"] == ["SIM-11", "SIM-12"], f"tip issues {listed['commits'][0].get('issues')}")
            check(
                builds_request.subjects_through(listed["commits"], mid) == ["SIM-10 first cut"],
                "mid prefix subjects",
            )
            check(
                builds_request.subjects_through(listed["commits"], tip) == ["SIM-10 first cut", "SIM-11 tip cut"],
                "tip prefix subjects",
            )
            behind = builds_request.list_commits(repo, "refs/heads/Dev", tip)
            check(behind.get("ok") is True and behind.get("commits") == [], f"branch behind the package {behind}")

            key_dir = repo / "keys"
            key_dir.mkdir()
            key_path = key_dir / "key.pem"
            subprocess.run(
                ["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(key_path)],
                check=True,
                capture_output=True,
            )
            public_text = subprocess.check_output(["openssl", "pkey", "-in", str(key_path), "-pubout", "-text", "-noout"], text=True)
            modulus_text = public_text.split("Modulus:", 1)[1].split("Exponent:", 1)[0]
            modulus_hex = re.sub(r"[^0-9a-fA-F]", "", modulus_text).lower()
            exponent_match = re.search(r"Exponent:\s+\d+\s+\(0x([0-9a-fA-F]+)\)", public_text)
            check(bool(modulus_hex) and exponent_match is not None, "openssl public key text")
            exponent_hex = (exponent_match.group(1).lower() if exponent_match else "10001")
            if len(exponent_hex) % 2:
                exponent_hex = "0" + exponent_hex
            if len(modulus_hex) % 2:
                modulus_hex = "0" + modulus_hex

            def jwk_part(hex_text: str) -> str:
                raw = bytes.fromhex(hex_text).lstrip(b"\x00") or b"\x00"
                return builds_request.b64url_encode(raw)

            modulus = jwk_part(modulus_hex)
            exponent = jwk_part(exponent_hex)
            pem = builds_request.jwk_rsa_pem(modulus, exponent)
            pem_path = key_dir / "pub.pem"
            pem_path.write_text(pem, encoding="utf-8")
            converted = subprocess.check_output(["openssl", "pkey", "-pubin", "-in", str(pem_path), "-text", "-noout"], text=True)
            converted_hex = re.sub(r"[^0-9a-fA-F]", "", converted.split("Modulus:", 1)[1].split("Exponent:", 1)[0]).lower()
            check(converted_hex.lstrip("0") == modulus_hex.lstrip("0"), "jwk pem modulus mismatch")
            builds_request._jwks_cache["at"] = time.time()
            builds_request._jwks_cache["keys"] = {"test-kid": {"e": exponent, "kid": "test-kid", "kty": "RSA", "n": modulus}}

            def mint(claims: dict) -> str:
                header = {"alg": "RS256", "kid": "test-kid", "typ": "JWT"}
                signing = (
                    builds_request.b64url_encode(json.dumps(header, separators=(",", ":")).encode())
                    + "."
                    + builds_request.b64url_encode(json.dumps(claims, separators=(",", ":")).encode())
                )
                data_path = key_dir / "data.bin"
                sig_path = key_dir / "sig.bin"
                data_path.write_bytes(signing.encode("ascii"))
                subprocess.run(
                    ["openssl", "dgst", "-sha256", "-sign", str(key_path), "-out", str(sig_path), str(data_path)],
                    check=True,
                    capture_output=True,
                )
                return signing + "." + builds_request.b64url_encode(sig_path.read_bytes())

            moment = int(time.time())
            good_claims = {
                "aud": builds_request.ACCESS_AUD,
                "email": "board@example.com",
                "exp": moment + 600,
                "iss": builds_request.ACCESS_ISS,
                "nbf": moment - 10,
                "sub": "board",
            }
            token = mint(good_claims)
            verified = builds_request.verify_access_jwt(token, now=moment)
            check(isinstance(verified, dict) and verified.get("email") == "board@example.com", "access jwt was rejected")
            bad_aud = dict(good_claims, aud="not-this-host")
            check(builds_request.verify_access_jwt(mint(bad_aud), now=moment) is None, "bad audience was accepted")
            expired = dict(good_claims, exp=moment - 120)
            check(builds_request.verify_access_jwt(mint(expired), now=moment) is None, "expired jwt was accepted")
            flipped_sig = bytearray(builds_request.b64url_decode(token.split(".")[2]))
            flipped_sig[-1] ^= 1
            flipped = token.rsplit(".", 1)[0] + "." + builds_request.b64url_encode(bytes(flipped_sig))
            check(builds_request.verify_access_jwt(flipped, now=moment) is None, "bad signature was accepted")

            node = shutil.which("node") or "/opt/homebrew/bin/node"
            prefix_script = r"""
const fs = require("fs");
const vm = require("vm");
const context = { console: console };
vm.createContext(context);
vm.runInContext(fs.readFileSync(process.argv[2], "utf8"), context);
const commits = [{commit:"aaa"},{commit:"bbb"},{commit:"ccc"}];
let selected = context.defaultSelection(commits);
function assert(cond, code) { if (!cond) process.exit(code); }
assert(context.newestChecked(commits, selected) === "aaa", 2);
selected = context.applyPrefixToggle(commits, selected, 1, false);
assert(!selected.aaa && !selected.bbb && selected.ccc, 3);
assert(context.newestChecked(commits, selected) === "ccc", 4);
selected = context.applyPrefixToggle(commits, selected, 1, true);
assert(!selected.aaa && selected.bbb && selected.ccc, 5);
assert(context.newestChecked(commits, selected) === "bbb", 6);
selected = context.applyPrefixToggle(commits, selected, 0, true);
assert(context.newestChecked(commits, selected) === "aaa", 7);
selected = context.applyPrefixToggle(commits, selected, 0, false);
assert(!selected.aaa && selected.bbb && context.newestChecked(commits, selected) === "bbb", 8);
assert(context.countdownLabel("2026-10-06T14:00:00Z", Date.parse("2026-10-06T10:56:00Z")) === "Next automatic merge and build in 3h 04m", 20);
assert(context.countdownLabel("2026-10-06T14:00:00Z", Date.parse("2026-10-06T13:47:55Z")) === "Next automatic merge and build in 12m 05s", 21);
assert(context.countdownLabel("2026-10-06T14:00:00Z", Date.parse("2026-10-06T13:59:18Z")) === "Next automatic merge and build in 42s", 22);
assert(context.countdownLabel("2026-10-06T14:00:00Z", Date.parse("2026-10-06T13:00:00Z")) === "Next automatic merge and build in 1h 00m", 23);
const aheadLines = context.upstreamStatusLines(false, [
  {name:"iPhone", status:"ahead", count:2, short:"4d3346a7"},
  {name:"Mac", status:"current", count:0, short:"ccd28802"}
]);
assert(aheadLines.length === 1 && aheadLines[0] === "iPhone upstream has 2 commits that are not merged, through 4d3346a7.", 24);
const currentLines = context.upstreamStatusLines(false, [
  {name:"iPhone", status:"current", count:0, short:"0bae96d5"},
  {name:"Mac", status:"current", count:0, short:"ccd28802"}
]);
assert(currentLines.length === 1 && currentLines[0] === "Upstream is already merged.", 25);
const missingLines = context.upstreamStatusLines(false, [
  {name:"iPhone", status:"unavailable"},
  {name:"Mac", status:"unavailable"}
]);
assert(missingLines.length === 1 && missingLines[0] === "Upstream could not be checked.", 26);
const oneMissing = context.upstreamStatusLines(false, [
  {name:"iPhone", status:"unavailable"},
  {name:"Mac", status:"current"}
]);
assert(oneMissing.length === 1 && oneMissing[0] === "iPhone upstream could not be checked.", 27);
const runningLines = context.upstreamStatusLines(true, [{name:"iPhone", status:"ahead", count:2, short:"4d3346a7"}]);
assert(runningLines.length === 1 && runningLines[0] === "Merge and build is running.", 28);
"""
            script_path = repo / "prefix.js"
            script_path.write_text(prefix_script, encoding="utf-8")
            prefix_run = subprocess.run(
                [node, str(script_path), str(static_root() / "app.js")],
                capture_output=True,
                text=True,
            )
            check(prefix_run.returncode == 0, f"prefix selection {prefix_run.returncode} {prefix_run.stderr}")

            cut_script = r"""
const fs = require("fs");
const vm = require("vm");
const context = { console: console };
vm.createContext(context);
vm.runInContext(fs.readFileSync(process.argv[2], "utf8"), context);
const nodes = {};
function Element(tag) {
  this.tagName = String(tag || "").toUpperCase();
  this.childNodes = [];
  this.attributes = {};
  this.hidden = false;
  this.textContent = "";
  this.className = "";
  this.id = "";
  this.type = "";
  this.checked = false;
  this.disabled = false;
  this.open = false;
  this.parentNode = null;
  this.style = {};
}
Element.prototype.appendChild = function (child) {
  child.parentNode = this;
  this.childNodes.push(child);
  return child;
};
Element.prototype.removeChild = function (child) {
  const index = this.childNodes.indexOf(child);
  if (index >= 0) this.childNodes.splice(index, 1);
  child.parentNode = null;
  return child;
};
Object.defineProperty(Element.prototype, "firstChild", {
  get() { return this.childNodes[0] || null; }
});
Element.prototype.setAttribute = function (name, value) {
  this.attributes[name] = String(value);
  if (name === "id") this.id = String(value);
};
Element.prototype.getAttribute = function (name) {
  return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
};
Element.prototype.removeAttribute = function (name) { delete this.attributes[name]; };
Element.prototype.addEventListener = function (type, fn) {
  this.listeners = this.listeners || {};
  this.listeners[type] = this.listeners[type] || [];
  this.listeners[type].push(fn);
};
Element.prototype.click = function () {
  (this.listeners && this.listeners.click || []).forEach((fn) => fn({ target: this }));
};
Element.prototype.querySelector = function () { return null; };
Element.prototype.querySelectorAll = function (selector) {
  const found = [];
  const visit = (node) => {
    if (selector === "input[data-index]" && node.tagName === "INPUT" && node.getAttribute("data-index") != null) found.push(node);
    (node.childNodes || []).forEach(visit);
  };
  visit(this);
  return found;
};
function walk(node, found) {
  found.push(node);
  (node.childNodes || []).forEach((child) => walk(child, found));
  return found;
}
function textOf(node) {
  const parts = [];
  walk(node, []).forEach((item) => {
    if (item.textContent) parts.push(item.textContent);
  });
  return parts.join("\n");
}
function findId(node, id) {
  if (!node) return null;
  if (node.id === id) return node;
  const kids = node.childNodes || [];
  for (let i = 0; i < kids.length; i++) {
    const found = findId(kids[i], id);
    if (found) return found;
  }
  return null;
}
const documentStub = {
  createElement(tag) { return new Element(tag); },
  getElementById(id) {
    if (nodes[id]) return nodes[id];
    const roots = Object.keys(nodes);
    for (let i = 0; i < roots.length; i++) {
      const found = findId(nodes[roots[i]], id);
      if (found) return found;
    }
    return null;
  }
};
context.document = documentStub;
function mount(id) {
  const node = documentStub.createElement("div");
  node.id = id;
  nodes[id] = node;
  return node;
}
const ipa = mount("ipa-cut");
const dmg = mount("dmg-cut");
const ipaUpdate = mount("ipa-update");
const dmgUpdate = mount("dmg-update");
const ipaChoose = mount("ipa-choose");
const dmgChoose = mount("dmg-choose");
mount("ipa-compile");
mount("dmg-compile");
const tip = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
function fail(code, detail) { console.error(detail); process.exit(code); }
context.renderSurface("dmg", { branch: "Dev", commits: [], error: null, pending: false }, true);
if (textOf(dmgUpdate).indexOf("This download is already the latest on Dev.") < 0) fail(2, textOf(dmgUpdate));
if (walk(dmgUpdate, []).some((node) => node.className === "update-button" || node.className === "request-button")) fail(3, "mac request button");
if (!dmgChoose.hidden) fail(13, "mac chooser open");
context.renderSurface("dmg", { branch: "Dev", commits: [], error: "No served commit is recorded.", pending: false }, true);
if (textOf(dmgUpdate).indexOf("No served commit is recorded.") < 0) fail(14, textOf(dmgUpdate));
if (walk(dmgUpdate, []).some((node) => node.className === "update-button")) fail(15, "update without a served commit");
const idleCut = {
  branch: "enhanced",
  commits: [{ commit: tip, issues: ["SIM-11"], short: "aaaaaaaa", subject: "SIM-11 tip cut" }],
  error: null,
  pending: false,
  tip: tip
};
context.renderSurface("ipa", idleCut, true);
const ipaText = textOf(ipa);
const updateText = textOf(ipaUpdate);
if (updateText.indexOf("Update everything") < 0) fail(16, updateText);
if (updateText.indexOf("1 commit, through aaaaaaaa.") < 0) fail(17, updateText);
if (ipaChoose.hidden) fail(18, "chooser hidden");
if (ipaChoose.open) fail(19, "chooser starts open");
if (ipaText.indexOf("Request this iPhone build") < 0) fail(4, ipaText);
if (ipaText.indexOf("Checking a commit includes that commit") < 0) fail(5, ipaText);
if (ipaText.indexOf("both land in the package") < 0) fail(11, ipaText);
if (ipaText.indexOf("SIM-11 tip cut") < 0) fail(6, ipaText);
const boxes = walk(ipa, []).filter((node) => node.tagName === "INPUT");
if (boxes.length !== 1 || !boxes[0].checked) fail(7, "default checkbox");
const link = walk(ipa, []).find((node) => node.tagName === "A" && node.textContent === "SIM-11");
if (!link || link.href.indexOf("/SIM/issues/SIM-11") < 0) fail(8, "issue link");
const updateButton = walk(ipaUpdate, []).find((node) => node.className === "update-button");
if (!updateButton || updateButton.disabled) fail(20, "idle update button");
context.renderSurface("ipa", {
  branch: "enhanced",
  busy: "iPhone is compiling.",
  commits: [{ commit: tip, issues: ["SIM-11"], short: "aaaaaaaa", subject: "SIM-11 tip cut" }],
  error: null,
  pending: false,
  tip: tip
}, true);
const grayButton = walk(ipaUpdate, []).find((node) => node.className === "update-button");
const replaceButton = walk(ipaUpdate, []).find((node) => node.className === "text-button");
if (!grayButton || !grayButton.disabled) fail(21, "gray update button");
if (textOf(ipaUpdate).indexOf("iPhone is compiling.") < 0) fail(22, textOf(ipaUpdate));
if (!replaceButton || replaceButton.textContent !== "Replace the running build") fail(23, "replace control");
let posted = null;
context.fetch = function (url, options) {
  posted = { url: url, body: options && options.body };
  return Promise.resolve({
    ok: false,
    status: 409,
    json: function () { return Promise.resolve({ error: "refused in the test" }); }
  });
};
replaceButton.click();
if (posted) fail(24, "first press sent a request");
if (replaceButton.textContent !== "Replace it") fail(25, replaceButton.textContent);
if (textOf(ipaUpdate).indexOf("This stops the compile that is running and requests the latest instead.") < 0) fail(26, textOf(ipaUpdate));
replaceButton.click();
if (!posted || posted.url !== "/api/build-request") fail(27, "second press did not post");
const sent = JSON.parse(posted.body);
if (sent.override !== true || sent.commit !== tip || sent.platform !== "ipa") fail(28, posted.body);
context.renderSurface("ipa", {
  branch: "enhanced",
  commits: [{ commit: tip, issues: ["SIM-11"], short: "aaaaaaaa", subject: "SIM-11 tip cut" }],
  error: null,
  pending: true,
  request: { commit: tip },
  tip: tip
}, true);
const pendingText = textOf(ipa) + "\n" + textOf(ipaUpdate);
if (pendingText.indexOf("Requested · aaaaaaaa") < 0) fail(9, pendingText);
const pendingButton = walk(ipa, []).find((node) => node.className === "request-button");
const pendingUpdate = walk(ipaUpdate, []).find((node) => node.className === "update-button");
if (!pendingButton || !pendingButton.disabled) fail(10, "pending button");
if (!pendingUpdate || !pendingUpdate.disabled) fail(29, "pending update button");
context.renderSurface("ipa", {
  branch: "enhanced",
  commits: [{ commit: tip, issues: ["SIM-11"], short: "aaaaaaaa", subject: "SIM-11 tip cut" }],
  error: null,
  pending: true,
  request: { commit: tip, keptBoth: ["same.txt", "parts.txt"] },
  tip: tip
}, true);
const keptText = textOf(ipa);
if (keptText.indexOf("Both edits kept in same.txt, parts.txt.") < 0) fail(12, keptText);
context.renderCompile("", {
  status: "building",
  stage: "xcodebuild",
  percent: 39.5,
  remainingLabel: "8m 02s",
  remainingSeconds: 482,
  commit: tip,
  startedAt: "2026-10-02T09:16:00Z",
  error: null,
  logTail: "compile line"
});
const stats = textOf(nodes["ipa-compile"]);
if (stats.indexOf("Building") < 0 || stats.indexOf("xcodebuild") < 0 || stats.indexOf("39.5%") < 0) fail(30, stats);
if (stats.indexOf("8m 02s left") < 0 || stats.indexOf("aaaaaaaa") < 0 || stats.indexOf("started ") < 0) fail(31, stats);
const logDetails = walk(nodes["ipa-compile"], []).find((node) => node.tagName === "DETAILS");
if (!logDetails || logDetails.open) fail(32, "log opened during the compile");
mount("upstream-countdown");
mount("upstream-lines");
const runButton = mount("upstream-run-button");
mount("upstream-state");
context.renderUpstream({
  nextAt: "2026-10-06T14:00:00Z",
  running: false,
  surfaces: [
    {name:"iPhone", status:"ahead", count:2, short:"4d3346a7", tip:"4d3346a7ff4817c183b5ed21ee9258f8f47a7226"},
    {name:"Mac", status:"current", count:0, short:"ccd28802", tip:"ccd288021dd6843a92942f98a52e4b97cef33e3e"}
  ]
});
if (textOf(nodes["upstream-lines"]).indexOf("iPhone upstream has 2 commits that are not merged, through 4d3346a7.") < 0) fail(40, textOf(nodes["upstream-lines"]));
if (textOf(nodes["upstream-countdown"]).indexOf("Next automatic merge and build in ") !== 0) fail(41, textOf(nodes["upstream-countdown"]));
if (runButton.hidden) fail(42, "button hidden while ahead");
let upstreamPosted = null;
const previousFetch = context.fetch;
context.fetch = function (url, options) {
  upstreamPosted = { url: url, body: options && options.body };
  return Promise.resolve({
    ok: false,
    status: 502,
    json: function () { return Promise.resolve({ error: "The merge and build could not be started." }); }
  });
};
runButton.click();
if (!upstreamPosted || upstreamPosted.url !== "/api/upstream-run" || upstreamPosted.body !== "{}") fail(43, JSON.stringify(upstreamPosted));
if (String(upstreamPosted.url).indexOf("3100") >= 0 || String(upstreamPosted.url).indexOf("routines") >= 0) fail(44, upstreamPosted.url);
context.renderUpstream({
  nextAt: "2026-10-06T14:00:00Z",
  running: true,
  lines: ["Merge and build is running."],
  surfaces: [{name:"iPhone", status:"ahead", count:2, short:"4d3346a7", tip:"4d3346a7ff4817c183b5ed21ee9258f8f47a7226"}]
});
if (textOf(nodes["upstream-lines"]).indexOf("Merge and build is running.") < 0) fail(45, textOf(nodes["upstream-lines"]));
if (!runButton.hidden) fail(46, "button shown while running");
context.renderUpstream({
  nextAt: "2026-10-07T14:00:00Z",
  running: false,
  surfaces: [
    {name:"iPhone", status:"current", count:0, short:"0bae96d5", tip:"0bae96d566240f4f18667044cd0230caf2c9d790"},
    {name:"Mac", status:"current", count:0, short:"ccd28802", tip:"ccd288021dd6843a92942f98a52e4b97cef33e3e"}
  ]
});
if (textOf(nodes["upstream-lines"]).indexOf("Upstream is already merged.") < 0) fail(47, textOf(nodes["upstream-lines"]));
if (!runButton.hidden) fail(48, "button shown when current");
context.renderUpstream({
  nextAt: "2026-10-07T14:00:00Z",
  running: false,
  surfaces: [
    {name:"iPhone", status:"unavailable", count:0, short:"", tip:""},
    {name:"Mac", status:"unavailable", count:0, short:"", tip:""}
  ]
});
if (textOf(nodes["upstream-lines"]).indexOf("Upstream could not be checked.") < 0) fail(49, textOf(nodes["upstream-lines"]));
if (!runButton.hidden) fail(50, "button shown when upstream cannot be checked");
context.fetch = previousFetch;
"""
            cut_path = repo / "cut.js"
            cut_path.write_text(cut_script, encoding="utf-8")
            cut_run = subprocess.run(
                [node, str(cut_path), str(static_root() / "app.js")],
                capture_output=True,
                text=True,
            )
            check(cut_run.returncode == 0, f"cut markup {cut_run.returncode} {cut_run.stdout} {cut_run.stderr}")

            builds_request.mobile_repository = lambda: repo
            builds_request.desktop_repository = lambda: None
            builds_request.post_ledger_comment = record_comment
            builds_request.refresh_ledger = quiet_ledger
            builds_request.clear_commit_cache()
            catalog = read_json(downloads_path(directory)) or {}
            catalog["ipa"]["commit"] = base
            write_json(downloads_path(directory), catalog)

            def post_json(access_token: str | None, payload: dict | None = None, raw: bytes | None = None) -> tuple[int, dict]:
                data = raw if raw is not None else json.dumps(payload or {}).encode("utf-8")
                headers = {"Content-Type": "application/json"}
                if access_token:
                    headers["Cf-Access-Jwt-Assertion"] = access_token
                request = urllib.request.Request(
                    f"http://127.0.0.1:{port}/api/build-request",
                    data=data,
                    headers=headers,
                    method="POST",
                )
                try:
                    with urllib.request.urlopen(request, timeout=15) as response:
                        return response.status, json.loads(response.read().decode("utf-8"))
                except urllib.error.HTTPError as exc:
                    raw_body = exc.read().decode("utf-8", "replace")
                    try:
                        parsed = json.loads(raw_body)
                    except json.JSONDecodeError:
                        parsed = {"raw": raw_body}
                    return exc.code, parsed

            with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/dashboard", timeout=15) as response:
                dashboard = json.loads(response.read().decode("utf-8"))
                dash_cache = response.headers.get("Cache-Control", "")
            check(dash_cache == "no-store", f"dashboard cache {dash_cache}")
            check(dashboard["poll"]["idleMs"] == 30000 and dashboard["poll"]["activeMs"] == 2000, "poll intervals")
            check(dashboard["requestEnabled"] is True, "request control hidden")
            check([row["commit"] for row in dashboard["cuts"]["ipa"]["commits"]] == [tip, mid], "dashboard commit list")
            check(dashboard["cuts"]["ipa"]["commits"][0]["subject"] == "SIM-11 tip cut", "dashboard tip subject")
            check(dashboard["upstreamRun"]["lines"] == ["Upstream is already merged."], "dashboard upstream lines")
            check(dashboard["upstreamRun"]["running"] is False, "dashboard upstream running")
            store_path = repository_root() / "store.json"
            store_before = store_path.read_bytes() if store_path.exists() else None
            request_existed = builds_request.request_path(directory).exists()

            def post_upstream(access_token: str | None, raw: bytes | None = None) -> tuple[int, dict]:
                data = raw if raw is not None else b"{}"
                headers = {"Content-Type": "application/json"}
                if access_token:
                    headers["Cf-Access-Jwt-Assertion"] = access_token
                request = urllib.request.Request(
                    f"http://127.0.0.1:{port}/api/upstream-run",
                    data=data,
                    headers=headers,
                    method="POST",
                )
                try:
                    with urllib.request.urlopen(request, timeout=15) as response:
                        return response.status, json.loads(response.read().decode("utf-8"))
                except urllib.error.HTTPError as exc:
                    raw_body = exc.read().decode("utf-8", "replace")
                    try:
                        parsed = json.loads(raw_body)
                    except json.JSONDecodeError:
                        parsed = {"raw": raw_body}
                    return exc.code, parsed

            saved_exchange = _PAPERCLIP_EXCHANGE[0]
            upstream_calls: list[tuple] = []

            def upstream_fake(method: str, path: str, body: dict | None = None) -> tuple[int, bytes]:
                upstream_calls.append((method.upper(), path, None if body is None else dict(body)))
                if method.upper() == "GET":
                    if any(item[0] == "POST" for item in upstream_calls):
                        active = [{"linkedIssue": {"id": "run-issue", "status": "in_progress"}, "status": "queued"}]
                        return 200, json.dumps(active).encode()
                    return 200, b"[]"
                if method.upper() == "POST" and path == f"/api/routines/{UPSTREAM_ROUTINE_ID}/run":
                    return 202, b'{"id":"accepted"}'
                return 500, b""

            _PAPERCLIP_EXCHANGE[0] = upstream_fake
            try:
                status, body = post_upstream(None)
                check(
                    status == 401 and body.get("error") == "Sign in through Cloudflare Access to request a build.",
                    f"upstream 401 {status} {body}",
                )
                check(upstream_calls == [], "unsigned upstream press called paperclip")
                status, body = post_upstream(token)
                check(status == 409 and body.get("error") == "Upstream is already merged.", f"current upstream {status} {body}")
                check(not any(item[0] == "POST" for item in upstream_calls), "current upstream posted a run")
                ahead_surfaces = [
                    {
                        "count": 2,
                        "name": "iPhone",
                        "short": "4d3346a7",
                        "status": "ahead",
                        "tip": "4d3346a7ff4817c183b5ed21ee9258f8f47a7226",
                    },
                    {
                        "count": 0,
                        "name": "Mac",
                        "short": "ccd28802",
                        "status": "current",
                        "tip": "ccd288021dd6843a92942f98a52e4b97cef33e3e",
                    },
                ]
                with _upstream_lock:
                    _upstream_cache["surfaces"] = ahead_surfaces
                    _upstream_cache["surfaces_at"] = time.time()
                upstream_calls.clear()
                status, body = post_upstream(token)
                check(status == 200 and body.get("ok") is True, f"upstream run {status} {body}")
                posts = [item for item in upstream_calls if item[0] == "POST"]
                check(len(posts) == 1 and posts[0][1] == f"/api/routines/{UPSTREAM_ROUTINE_ID}/run", f"upstream posts {posts}")
                check(posts[0][2]["source"] == "manual", f"upstream source {posts[0][2]}")
                check(
                    posts[0][2]["idempotencyKey"] == ahead_surfaces[0]["tip"] + "+" + ahead_surfaces[1]["tip"],
                    f"upstream key {posts[0][2]}",
                )
                status, body = post_upstream(token)
                check(status == 409 and body.get("error") == "Merge and build is running.", f"second upstream {status} {body}")
                check(len([item for item in upstream_calls if item[0] == "POST"]) == 1, "second press started another run")
                check(
                    request_existed or not builds_request.request_path(directory).exists(),
                    "upstream run wrote build-request.json",
                )
                if store_before is not None:
                    check(store_path.read_bytes() == store_before, "upstream run changed store.json")
            finally:
                _PAPERCLIP_EXCHANGE[0] = saved_exchange
                with _upstream_lock:
                    _upstream_cache["surfaces"] = None
                    _upstream_cache["surfaces_at"] = 0.0
                    _upstream_cache["running"] = None
                    _upstream_cache["running_at"] = 0.0
                    _upstream_cache["running_token"] = 0
            status, body = post_json(None, {"commit": tip, "platform": "ipa"})
            check(status == 401, f"missing jwt returned {status}")
            check(not builds_request.request_path(directory).exists(), "missing jwt wrote a request")
            check(comments == [], "missing jwt commented")
            status, body = post_json("not-a-jwt", {"commit": tip, "platform": "ipa"})
            check(status == 401, f"invalid jwt returned {status}")
            check(not builds_request.request_path(directory).exists(), "invalid jwt wrote a request")
            status, body = post_json(token, {"commit": base, "platform": "ipa"})
            check(status == 409 and "already the served" in str(body.get("error")), f"served commit {status} {body}")
            status, body = post_json(token, {"commit": side, "platform": "ipa"})
            check(status == 409, f"side commit returned {status} {body}")
            status, body = post_json(token, raw=b"not-json")
            check(status == 400, f"bad body returned {status}")
            check(not builds_request.request_path(directory).exists(), "refused request wrote a file")
            write_json(
                session_path(directory),
                {
                    "branch": "enhanced",
                    "buildStartedAt": time.time(),
                    "closed": False,
                    "commit": tip,
                    "error": None,
                    "releaseNotes": "hello notes",
                    "stage": "xcodebuild",
                    "stageStartedAt": time.time(),
                    "status": "building",
                },
            )
            status, body = post_json(token, {"commit": tip, "platform": "ipa"})
            check(status == 409 and "compiling" in str(body.get("error")), f"compiling request {status} {body}")
            check(comments == [], "compiling request commented")
            session_text = session_path(directory).read_text(encoding="utf-8")
            status, body = post_json(token, {"commit": tip, "platform": "dmg", "override": True})
            check(status == 409, f"mac override while the mac repo is missing returned {status} {body}")
            check(session_path(directory).read_text(encoding="utf-8") == session_text, "mac override touched the iphone session")
            check(not builds_request.request_path(directory).exists(), "refused override wrote a request")
            status, saved = post_json(token, {"commit": tip, "platform": "ipa", "override": True})
            check(status == 201, f"override request {status} {saved}")
            overridden = builds_request.read_requests(directory)
            check(
                overridden["ipa"]["commit"] == tip and overridden["ipa"].get("override") is True,
                f"override file {overridden}",
            )
            check(overridden["dmg"] is None, "ipa override wrote mac")
            check(session_path(directory).read_text(encoding="utf-8") == session_text, "override stopped the compile session")
            check(
                len(comments) == 1 and "Override:" in comments[0] and "Do not stop the Mac" in comments[0],
                f"override comment {comments}",
            )
            builds_request.request_path(directory).unlink(missing_ok=True)
            comments.clear()
            if saved_session is None:
                session_path(directory).unlink(missing_ok=True)
            else:
                session_path(directory).write_text(saved_session, encoding="utf-8")
            status, saved = post_json(token, {"commit": tip, "platform": "ipa"})
            check(status == 201, f"tip request {status} {saved}")
            stored = builds_request.read_requests(directory)
            check(stored["ipa"]["commit"] == tip and stored["ipa"]["hosted"] is False, f"request file {stored}")
            check(stored["ipa"]["branch"] == "enhanced" and stored["ipa"]["platform"] == "ipa", "request identity")
            check(stored["ipa"]["subjects"] == ["SIM-10 first cut", "SIM-11 tip cut"], f"subjects {stored['ipa'].get('subjects')}")
            check(stored["ipa"].get("keptBoth") == [], f"clean request kept both {stored['ipa'].get('keptBoth')}")
            check(stored["dmg"] is None, "mac request was written")
            local_refs = subprocess.run(["git", "-C", str(repo), "show-ref"], capture_output=True, text=True)
            check("refs/cuts/" not in local_refs.stdout, f"clean request created a cuts ref {local_refs.stdout}")
            check(len(comments) == 1, f"comment count {len(comments)}")
            ledger = comments[0]
            check("This is not a test." in ledger, "ledger phrase missing")
            check("Surface: iPhone" in ledger and "Branch: enhanced" in ledger, "ledger surface")
            check(f"Commit: {tip}" in ledger, "ledger commit")
            check(ledger.index("SIM-10 first cut") < ledger.index("SIM-11 tip cut"), "ledger subject order")
            status, body = post_json(token, {"commit": tip, "platform": "ipa"})
            check(status == 409 and "unhosted" in str(body.get("error")).lower(), f"second press {status} {body}")
            check(len(comments) == 1, "second press commented")
            check(builds_request.read_requests(directory)["ipa"]["commit"] == tip, "second press replaced the request")
            held = builds_request.request_path(directory).read_text(encoding="utf-8")
            doc = json.loads(held)
            doc["ipa"]["hosted"] = True
            builds_request.write_requests(directory, doc)
            held = builds_request.request_path(directory).read_text(encoding="utf-8")

            def reject_comment(body: str) -> None:
                raise builds_request.MissingCredential("rejected")

            builds_request.post_ledger_comment = reject_comment
            status, body = post_json(token, {"commit": mid, "platform": "ipa"})
            check(status == 503, f"missing credential returned {status} {body}")
            check(builds_request.request_path(directory).read_text(encoding="utf-8") == held, "credential failure kept the new request")
            check(builds_request.requests_enabled() is False, "button stayed shipped after a credential failure")
            status, body = post_json(token, {"commit": mid, "platform": "ipa"})
            check(status == 404, f"unshipped button returned {status}")
            check(len(comments) == 1, "credential failure commented")

            bare = Path(tempfile.mkdtemp(prefix="nuvio-cuts-origin-"))
            try:
                subprocess.run(["git", "init", "--bare", str(bare)], check=True, capture_output=True)
                subprocess.run(["git", "-C", str(repo), "remote", "add", "origin", str(bare)], check=True, capture_output=True)
                enhanced_before = subprocess.check_output(
                    ["git", "-C", str(repo), "rev-parse", "refs/heads/enhanced"],
                    text=True,
                ).strip().lower()
                dev_before = subprocess.check_output(
                    ["git", "-C", str(repo), "rev-parse", "refs/heads/Dev"],
                    text=True,
                ).strip().lower()

                def show_file(sha: str, path: str) -> str:
                    return subprocess.check_output(["git", "-C", str(repo), "show", f"{sha}:{path}"], text=True)

                def run_combine(base_sha: str, shas: list[str]) -> tuple[int, dict, str]:
                    proc = subprocess.run(
                        [
                            sys.executable,
                            str(Path(builds_request.__file__)),
                            "combine",
                            "--repo",
                            str(repo),
                            "--base",
                            base_sha,
                            "--commits",
                            ",".join(shas),
                        ],
                        capture_output=True,
                        text=True,
                    )
                    payload: dict = {}
                    if proc.returncode == 0 and proc.stdout.strip():
                        payload = json.loads(proc.stdout)
                    return proc.returncode, payload, proc.stderr

                def commit_detached(parent: str, subject: str, files: dict[str, str]) -> str:
                    subprocess.run(
                        ["git", "-C", str(repo), "checkout", "--detach", parent],
                        check=True,
                        capture_output=True,
                    )
                    for name, text in files.items():
                        path = repo / name
                        path.parent.mkdir(parents=True, exist_ok=True)
                        path.write_text(text, encoding="utf-8")
                        subprocess.run(["git", "-C", str(repo), "add", "--", name], check=True, capture_output=True)
                    subprocess.run(["git", "-C", str(repo), "commit", "-m", subject], check=True, capture_output=True)
                    return subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip().lower()

                code, payload, err = run_combine(base, [mid, tip])
                check(code == 0, f"clean combine {code} {err}")
                check(
                    payload.get("commit") == tip and payload.get("sameAsTip") is True and payload.get("ref") is None,
                    f"clean combine payload {payload}",
                )
                check(payload.get("keptBoth") == [], f"clean combine kept {payload.get('keptBoth')}")
                remote = subprocess.run(
                    ["git", "-C", str(repo), "ls-remote", "origin", "refs/cuts/*"],
                    capture_output=True,
                    text=True,
                )
                check(remote.returncode == 0 and remote.stdout.strip() == "", f"clean combine pushed {remote.stdout} {remote.stderr}")

                parts_base = commit_detached(base, "parts base", {"parts.txt": "alpha\nmiddle\nomega\n"})
                part_a = commit_detached(parts_base, "part a", {"parts.txt": "ALPHA\nmiddle\nomega\n"})
                part_b = commit_detached(parts_base, "part b", {"parts.txt": "alpha\nmiddle\nOMEGA\n"})
                subprocess.run(["git", "-C", str(repo), "checkout", "enhanced"], check=True, capture_output=True)
                code, payload, err = run_combine(parts_base, [part_a, part_b])
                check(code == 0, f"parts combine {code} {err}")
                merged = show_file(payload.get("commit", ""), "parts.txt") if payload.get("commit") else ""
                check("ALPHA" in merged and "OMEGA" in merged, f"parts text {merged!r}")
                check(
                    merged != show_file(part_a, "parts.txt") and merged != show_file(part_b, "parts.txt"),
                    "parts file matched one side",
                )
                check(payload.get("sameAsTip") is False, "parts replay matched the newer commit")
                check(payload.get("ref") == f"refs/cuts/{payload.get('commit')}", f"parts ref {payload}")
                remote = subprocess.run(
                    ["git", "-C", str(repo), "ls-remote", "origin", str(payload.get("ref") or "")],
                    capture_output=True,
                    text=True,
                )
                check(str(payload.get("commit") or "missing") in remote.stdout, f"parts cuts ref missing {remote.stdout} {remote.stderr}")

                same_base = commit_detached(base, "same base", {"same.txt": "one\ntwo\n"})
                same_a = commit_detached(same_base, "same a", {"same.txt": "FROM-A\ntwo\n"})
                same_b = commit_detached(same_base, "same b", {"same.txt": "FROM-B\ntwo\n"})
                subprocess.run(["git", "-C", str(repo), "checkout", "enhanced"], check=True, capture_output=True)
                code, payload, err = run_combine(same_base, [same_a, same_b])
                check(code == 0, f"same combine {code} {err}")
                merged = show_file(payload.get("commit", ""), "same.txt") if payload.get("commit") else ""
                check("FROM-A" in merged and "FROM-B" in merged, f"same text {merged!r}")
                check(
                    merged != show_file(same_a, "same.txt") and merged != show_file(same_b, "same.txt"),
                    "same file matched one side",
                )
                check(payload.get("keptBoth") == ["same.txt"], f"same keptBoth {payload.get('keptBoth')}")
                check(
                    payload.get("sameAsTip") is False and payload.get("ref") == f"refs/cuts/{payload.get('commit')}",
                    f"same payload {payload}",
                )
                remote = subprocess.run(
                    ["git", "-C", str(repo), "ls-remote", "origin", str(payload.get("ref") or "")],
                    capture_output=True,
                    text=True,
                )
                check(str(payload.get("commit") or "missing") in remote.stdout, f"same cuts ref missing {remote.stdout} {remote.stderr}")
                check(
                    subprocess.check_output(["git", "-C", str(repo), "rev-parse", "refs/heads/enhanced"], text=True).strip().lower()
                    == enhanced_before,
                    "enhanced moved",
                )
                check(
                    subprocess.check_output(["git", "-C", str(repo), "rev-parse", "refs/heads/Dev"], text=True).strip().lower()
                    == dev_before,
                    "Dev moved",
                )
                origin_heads = subprocess.run(["git", "-C", str(bare), "show-ref"], capture_output=True, text=True)
                check("refs/heads/" not in origin_heads.stdout, f"origin branch moved {origin_heads.stdout}")

                builds_request._requests_enabled = True
                builds_request.post_ledger_comment = record_comment
                subprocess.run(["git", "-C", str(repo), "checkout", "enhanced"], check=True, capture_output=True)
                (repo / "overlap.txt").write_text("FROM-A\nrest\n", encoding="utf-8")
                subprocess.run(["git", "-C", str(repo), "add", "--", "overlap.txt"], check=True, capture_output=True)
                subprocess.run(["git", "-C", str(repo), "commit", "-m", "SIM-20 overlap first"], check=True, capture_output=True)
                overlap_a = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip().lower()
                (repo / "overlap.txt").write_text("FROM-B\nrest\n", encoding="utf-8")
                subprocess.run(["git", "-C", str(repo), "add", "--", "overlap.txt"], check=True, capture_output=True)
                subprocess.run(["git", "-C", str(repo), "commit", "-m", "SIM-21 overlap second"], check=True, capture_output=True)
                overlap_b = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip().lower()
                status, saved = post_json(token, {"commit": overlap_b, "platform": "ipa"})
                check(status == 201, f"overlap request {status} {saved}")
                stored = builds_request.read_requests(directory)
                overlap_commit = stored["ipa"]["commit"]
                check(overlap_commit not in (overlap_a, overlap_b), f"overlap stored a side {stored['ipa']}")
                check(stored["ipa"].get("keptBoth") == ["overlap.txt"], f"overlap kept {stored['ipa'].get('keptBoth')}")
                overlap_text = show_file(overlap_commit, "overlap.txt")
                check("FROM-A" in overlap_text and "FROM-B" in overlap_text, f"overlap file {overlap_text!r}")
                check(
                    overlap_text != show_file(overlap_a, "overlap.txt") and overlap_text != show_file(overlap_b, "overlap.txt"),
                    "overlap file matched one side",
                )
                check(
                    subprocess.check_output(["git", "-C", str(repo), "rev-parse", "refs/heads/enhanced"], text=True).strip().lower()
                    == overlap_b,
                    "request moved enhanced",
                )
                check(
                    subprocess.check_output(["git", "-C", str(repo), "rev-parse", "refs/heads/Dev"], text=True).strip().lower()
                    == dev_before,
                    "request moved Dev",
                )
                check(len(comments) == 2, f"overlap comment count {len(comments)}")
                overlap_ledger = comments[-1]
                check("This is not a test." in overlap_ledger, "overlap ledger phrase")
                check("Surface: iPhone" in overlap_ledger and "Branch: enhanced" in overlap_ledger, "overlap ledger surface")
                check(f"Commit: {overlap_commit}" in overlap_ledger, "overlap ledger commit")
                check("overlap.txt" in overlap_ledger and "Both edits kept:" in overlap_ledger, "overlap ledger path")
                remote = subprocess.run(
                    ["git", "-C", str(repo), "ls-remote", "origin", f"refs/cuts/{overlap_commit}"],
                    capture_output=True,
                    text=True,
                )
                check(overlap_commit in remote.stdout, f"request cuts ref missing {remote.stdout} {remote.stderr}")
            finally:
                shutil.rmtree(bare, ignore_errors=True)
        except Exception as exc:
            check(False, f"request route setup failed: {exc}")
        finally:
            builds_request.mobile_repository = saved_mobile
            builds_request.desktop_repository = saved_desktop
            builds_request.post_ledger_comment = saved_post
            builds_request.refresh_ledger = saved_ledger
            builds_request._requests_enabled = True
            builds_request._jwks_cache["at"] = 0.0
            builds_request._jwks_cache["keys"] = {}
            builds_request.clear_commit_cache()
            downloads_path(directory).write_text(saved_catalog, encoding="utf-8")
            if saved_session is None:
                session_path(directory).unlink(missing_ok=True)
            else:
                session_path(directory).write_text(saved_session, encoding="utf-8")
            shutil.rmtree(repo, ignore_errors=True)

        httpd.shutdown()

    real_enhanced_after = builds_request.git_commit(mobile_path, "refs/heads/enhanced") if mobile_path else None
    real_dev_after = builds_request.git_commit(desktop_path, "refs/heads/Dev") if desktop_path else None
    check(real_enhanced_after == real_enhanced_before, f"enhanced moved {real_enhanced_before} {real_enhanced_after}")
    check(real_dev_after == real_dev_before, f"Dev moved {real_dev_before} {real_dev_after}")
    _PAPERCLIP_EXCHANGE[0] = live_exchange
    _SURFACE_LOADER[0] = live_loader

    if failures:
        for message in failures:
            print(f"FAIL {message}", file=sys.stderr)
        return 1
    print("ipa-status self-test passed")
    return 0


def command_log_follow(args: argparse.Namespace) -> int:
    if not valid_build_id(args.build_id):
        print("ipa-status: invalid build id", file=sys.stderr)
        return 2
    directory = status_directory(args.status_dir)
    fifo = Path(args.fifo)
    with fifo.open("r", encoding="utf-8", errors="replace", buffering=1) as handle:
        while True:
            line = handle.readline()
            if line == "":
                break
            if line.endswith("\n"):
                line = line[:-1]
            if line.endswith("\r"):
                line = line[:-1]
            try:
                append_log_line(directory, args.build_id, line)
            except Exception:
                continue
    return 0


def send_bytes(
    handler: BaseHTTPRequestHandler,
    code: int,
    content_type: str,
    body: bytes,
    cache_control: str = "no-store",
    etag: str | None = None,
) -> None:
    handler.send_response(code)
    handler.send_header("Content-Type", content_type)
    handler.send_header("Content-Length", str(len(body)))
    handler.send_header("Cache-Control", cache_control)
    if etag:
        handler.send_header("ETag", etag)
    handler.end_headers()
    if code != 304:
        handler.wfile.write(body)


def send_json(handler: BaseHTTPRequestHandler, payload: dict) -> None:
    send_json_code(handler, 200, payload)


def send_json_code(handler: BaseHTTPRequestHandler, code: int, payload: dict) -> None:
    body = (json.dumps(payload, sort_keys=True) + "\n").encode("utf-8")
    send_bytes(handler, code, "application/json; charset=utf-8", body)


def send_build_log(handler: BaseHTTPRequestHandler, directory: Path, build_id: str) -> None:
    with locked(directory, exclusive=False):
        if indexed_build(directory, build_id) is None:
            send_bytes(handler, 404, "text/plain; charset=utf-8", b"not found\n")
            return
        path = log_file(directory, build_id)
    try:
        body = path.read_bytes()
    except OSError:
        body = b""
    send_bytes(handler, 200, "text/plain; charset=utf-8", body)


def send_file(handler: BaseHTTPRequestHandler, path: Path, filename: str) -> None:
    size = path.stat().st_size
    handler.send_response(200)
    handler.send_header("Content-Type", "application/octet-stream")
    handler.send_header("Content-Length", str(size))
    handler.send_header("Content-Disposition", content_disposition(filename))
    handler.send_header("Cache-Control", "no-store")
    handler.end_headers()
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            try:
                handler.wfile.write(chunk)
            except (BrokenPipeError, ConnectionResetError):
                return


def older_file(directory: Path, name: str) -> Path | None:
    if not re.fullmatch(r"[A-Za-z0-9._-]+", name):
        return None
    if Path(name).suffix.lower() not in {".ipa", ".dmg"}:
        return None
    root = artifacts_directory(directory).resolve()
    if not root.is_dir():
        return None
    matches: list[Path] = []
    for candidate in root.rglob(name):
        if not candidate.is_file() or candidate.name != name:
            continue
        resolved = candidate.resolve()
        if resolved != root and root not in resolved.parents:
            continue
        matches.append(resolved)
    if len(matches) != 1:
        return None
    return matches[0]


def send_download(handler: BaseHTTPRequestHandler, directory: Path, kind: str) -> None:
    entry = catalog_entry(directory, kind)
    path = artifact_file(directory, entry)
    if entry is None or path is None:
        send_bytes(handler, 404, "text/plain; charset=utf-8", b"not found\n")
        return
    if kind in ("ipa", "macos"):
        named = public_named_entry(directory, entry, kind)
        filename = str((named or {}).get("savedName") or entry.get("filename") or path.name)
    else:
        filename = str(entry.get("filename") or path.name)
    send_file(handler, path, filename)


def send_older(handler: BaseHTTPRequestHandler, directory: Path, name: str) -> None:
    path = older_file(directory, name)
    if path is None:
        send_bytes(handler, 404, "text/plain; charset=utf-8", b"not found\n")
        return
    send_file(handler, path, path.name)


STATIC_FILES = {
    "/assets/app.css": "app.css",
    "/assets/app.js": "app.js",
}
STATIC_TYPES = {
    ".css": "text/css; charset=utf-8",
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
}
SURFACES = {
    "dmg": {"branch": builds_request.DESKTOP_BRANCH, "name": "Mac", "ref": builds_request.DESKTOP_REF},
    "ipa": {"branch": builds_request.MOBILE_BRANCH, "name": "iPhone", "ref": builds_request.MOBILE_REF},
}


def static_root() -> Path:
    return Path(__file__).resolve().parent / "builds_page"


def page_document(directory: Path) -> bytes:
    """One document. The script and package data travel with the HTML.

    Cloudflare keeps /assets/app.js from the previous page. That script stops
    on the first missing node and never paints the packages. A separate
    /api/dashboard fetch can also come back as the Access login page.
    """
    root = static_root()
    html = (root / "index.html").read_text(encoding="utf-8")
    css = (root / "app.css").read_text(encoding="utf-8").replace("</style", "<\\/style")
    script = (root / "app.js").read_text(encoding="utf-8").replace("</script", "<\\/script")
    payload = dashboard_payload(directory, time.time())
    payload.pop("servedAt", None)
    payload.pop("work", None)
    for key in ("iphone", "desktop"):
        block = payload.get(key)
        if isinstance(block, dict):
            block.pop("updatedAt", None)
    seed = json.dumps(payload, sort_keys=True, separators=(",", ":")).replace("<", "\\u003c")
    style = "<style>\n" + css + "\n</style>"
    inline = (
        '<script id="dashboard-seed" type="application/json">'
        + seed
        + "</script>\n<script>\n"
        + script
        + "\n</script>"
    )
    link = '<link rel="stylesheet" href="/assets/app.css">'
    source = '<script src="/assets/app.js" defer></script>'
    if html.count(link) != 1 or html.count(source) != 1:
        raise RuntimeError("builds page asset markers missing")
    html = html.replace(link, style, 1).replace(source, inline, 1)
    return html.encode("utf-8")


def send_page(handler: BaseHTTPRequestHandler, directory: Path) -> None:
    body = page_document(directory)
    etag = hashlib.sha256(body).hexdigest()
    cache = "public, max-age=0, must-revalidate, no-transform"
    if handler.headers.get("If-None-Match") == etag:
        handler.send_response(304)
        handler.send_header("ETag", etag)
        handler.send_header("Cache-Control", cache)
        handler.send_header("Content-Length", "0")
        handler.end_headers()
        return
    send_bytes(handler, 200, "text/html; charset=utf-8", body, cache_control=cache, etag=etag)


def send_static(handler: BaseHTTPRequestHandler, name: str) -> None:
    root = static_root().resolve()
    path = (root / name).resolve()
    if path != root and root not in path.parents:
        send_bytes(handler, 404, "text/plain; charset=utf-8", b"not found\n")
        return
    try:
        body = path.read_bytes()
    except OSError:
        send_bytes(handler, 404, "text/plain; charset=utf-8", b"not found\n")
        return
    etag = hashlib.sha256(body).hexdigest()
    cache = "public, max-age=3600, no-transform"
    if handler.headers.get("If-None-Match") == etag:
        handler.send_response(304)
        handler.send_header("ETag", etag)
        handler.send_header("Cache-Control", cache)
        handler.send_header("Content-Length", "0")
        handler.end_headers()
        return
    send_bytes(handler, 200, STATIC_TYPES[path.suffix], body, cache_control=cache, etag=etag)


def surface_repo(platform: str) -> Path | None:
    if platform == "ipa":
        repo = builds_request.mobile_repository()
        return repo if repo.is_dir() else None
    return builds_request.desktop_repository()


def served_commit(directory: Path, platform: str) -> str:
    commit = platform_commit(load_catalog(directory), platform)
    if not isinstance(commit, str):
        return ""
    commit = commit.strip().lower()
    if commit in {"", "none"}:
        return ""
    return commit


def restore_request_file(path: Path, previous: str | None) -> None:
    if previous is None:
        try:
            path.unlink()
        except FileNotFoundError:
            return
        return
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(previous, encoding="utf-8")
    os.replace(temporary, path)


def cut_view(directory: Path, platform: str, now: float, status_payload: dict) -> dict:
    spec = SURFACES[platform]
    repo = surface_repo(platform)
    served = served_commit(directory, platform)
    if repo is None:
        listing = {
            "commits": [],
            "error": f"The {spec['name']} repository is not available.",
            "ok": False,
            "served": "",
            "tip": "",
        }
    else:
        listing = builds_request.list_commits(repo, spec["ref"], served)
        if listing.get("ok"):
            builds_request.attach_ledger_issues(listing["commits"], now)
    busy = None
    if status_payload.get("status") in ("building", "queued"):
        busy = f"{spec['name']} is compiling."
    with locked(directory):
        doc = builds_request.read_requests(directory)
        if repo is not None and builds_request.mark_hosted(doc, platform, listing.get("served") or served, repo):
            builds_request.write_requests(directory, doc)
        current = doc.get(platform)
    cancelled = False
    pending = False
    if isinstance(current, dict) and current.get("hosted") is not True:
        cancelled = builds_request.is_cancelled(platform, current.get("commit"), now, force=False)
        pending = not cancelled
    request_public = None
    if isinstance(current, dict):
        subjects = current.get("subjects") if isinstance(current.get("subjects"), list) else []
        kept_both = current.get("keptBoth") if isinstance(current.get("keptBoth"), list) else []
        request_public = {
            "branch": current.get("branch"),
            "cancelled": cancelled,
            "commit": current.get("commit"),
            "hosted": current.get("hosted") is True,
            "keptBoth": [item for item in kept_both if isinstance(item, str)],
            "platform": platform,
            "requestedAt": current.get("requestedAt"),
            "subjects": subjects,
        }
    return {
        "branch": spec["branch"],
        "busy": busy,
        "commits": listing.get("commits") or [],
        "error": listing.get("error"),
        "pending": pending,
        "request": request_public,
        "servedCommit": listing.get("served") or "",
        "tip": listing.get("tip") or "",
    }


class UpstreamDown(Exception):
    pass


def quiet_git_env() -> dict[str, str]:
    env = os.environ.copy()
    env["GIT_TERMINAL_PROMPT"] = "0"
    return env


def github_slug(url: str) -> str:
    text = url.strip()
    text = re.sub(r"\.git$", "", text, flags=re.IGNORECASE)
    text = re.sub(r"^git@github\.com:", "github.com/", text, flags=re.IGNORECASE)
    text = re.sub(r"^ssh://git@github\.com/", "github.com/", text, flags=re.IGNORECASE)
    text = re.sub(r"^https?://github\.com/", "github.com/", text, flags=re.IGNORECASE)
    return text.lower().rstrip("/")


def remote_for_slug(repo: Path, slug: str) -> str | None:
    result = builds_request.run_git(repo, ["remote", "-v"], env=quiet_git_env())
    if result.returncode != 0:
        return None
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) >= 2 and github_slug(parts[1]) == slug:
            return parts[0]
    return None


def next_upstream_moment(moment: datetime) -> datetime:
    """Next 16:00 Europe/Oslo strictly after moment, as UTC."""
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    utc_moment = moment.astimezone(timezone.utc)
    day = utc_moment.astimezone(OSLO).date()
    for offset in range(4):
        candidate_day = day + timedelta(days=offset)
        local_sixteen = datetime(
            candidate_day.year,
            candidate_day.month,
            candidate_day.day,
            16,
            0,
            0,
            tzinfo=OSLO,
        )
        as_utc = local_sixteen.astimezone(timezone.utc).replace(microsecond=0)
        if as_utc > utc_moment:
            return as_utc
    raise RuntimeError("no upcoming Europe/Oslo 16:00")


def next_upstream_iso(moment: datetime) -> str:
    return next_upstream_moment(moment).strftime("%Y-%m-%dT%H:%M:%SZ")


def ahead_sentence(name: str, count: int, short: str) -> str:
    if count == 1:
        return f"{name} upstream has 1 commit that is not merged, through {short}."
    return f"{name} upstream has {count} commits that are not merged, through {short}."


def upstream_status_lines(running: bool, surfaces: list[dict]) -> list[str]:
    if running:
        return ["Merge and build is running."]
    readable = [item for item in surfaces if item.get("status") != "unavailable"]
    if not readable:
        return ["Upstream could not be checked."]
    lines: list[str] = []
    for item in surfaces:
        status = item.get("status")
        name = str(item.get("name") or "")
        if status == "ahead":
            lines.append(ahead_sentence(name, int(item.get("count") or 0), str(item.get("short") or "")))
        elif status == "unavailable":
            lines.append(f"{name} upstream could not be checked.")
    if lines:
        return lines
    return ["Upstream is already merged."]


def fetch_tracking(repo: Path, remote: str, branch: str) -> bool:
    """Update one existing remote-tracking ref. Leave refs/heads alone."""
    dest = f"refs/remotes/{remote}/{branch}"
    if builds_request.git_commit(repo, dest) is None:
        return False
    head = f"refs/heads/{branch}"
    before = builds_request.git_commit(repo, head)
    result = builds_request.run_git(
        repo,
        [
            "fetch",
            "--no-tags",
            "--no-prune",
            "--no-recurse-submodules",
            remote,
            f"+refs/heads/{branch}:{dest}",
        ],
        env=quiet_git_env(),
        timeout=30,
    )
    after = builds_request.git_commit(repo, head)
    if before != after:
        return False
    return result.returncode == 0


def compare_refs(repo: Path, name: str, ours_remote: str, upstream_remote: str, branch: str) -> dict:
    empty = {"count": 0, "name": name, "short": "", "status": "unavailable", "tip": ""}
    if not fetch_tracking(repo, ours_remote, branch) or not fetch_tracking(repo, upstream_remote, branch):
        return empty
    ours = builds_request.git_commit(repo, f"refs/remotes/{ours_remote}/{branch}")
    tip = builds_request.git_commit(repo, f"refs/remotes/{upstream_remote}/{branch}")
    if not ours or not tip:
        return empty
    if ours == tip:
        count = 0
    else:
        result = builds_request.run_git(
            repo,
            ["rev-list", "--count", f"{ours}..{tip}"],
            env=quiet_git_env(),
            timeout=15,
        )
        if result.returncode != 0:
            return empty
        try:
            count = int(result.stdout.strip())
        except ValueError:
            return empty
    return {
        "count": count,
        "name": name,
        "short": tip[:8],
        "status": "ahead" if count else "current",
        "tip": tip,
    }


UPSTREAM_SURFACE_SPECS = (
    {
        "branch": "enhanced",
        "name": "iPhone",
        "ours": "github.com/simsalabimse/nuviomobile-enhanced",
        "repo": builds_request.mobile_repository,
        "upstream": "github.com/luqmanfadlli/nuviomobile-enhanced",
    },
    {
        "branch": "Dev",
        "name": "Mac",
        "ours": "github.com/simsalabimse/nuviodesktop",
        "repo": builds_request.desktop_repository,
        "upstream": "github.com/nuviomedia/nuviodesktop",
    },
)


def compare_upstream_surface(spec: dict) -> dict:
    empty = {"count": 0, "name": spec["name"], "short": "", "status": "unavailable", "tip": ""}
    try:
        repo = spec["repo"]()
        if repo is None or not Path(repo).is_dir():
            return empty
        ours_remote = remote_for_slug(Path(repo), spec["ours"])
        upstream_remote = remote_for_slug(Path(repo), spec["upstream"])
        if not ours_remote or not upstream_remote or ours_remote == upstream_remote:
            return empty
        return compare_refs(Path(repo), spec["name"], ours_remote, upstream_remote, spec["branch"])
    except Exception:
        return empty


def load_upstream_surfaces(now: float) -> list[dict]:
    del now
    return [compare_upstream_surface(spec) for spec in UPSTREAM_SURFACE_SPECS]


_SURFACE_LOADER = [load_upstream_surfaces]
_upstream_lock = threading.Lock()
_upstream_cache: dict = {
    "running": None,
    "running_at": 0.0,
    "running_token": 0,
    "surfaces": None,
    "surfaces_at": 0.0,
}


def upstream_surfaces(now: float, force: bool = False) -> list[dict]:
    with _upstream_lock:
        cached = _upstream_cache["surfaces"]
        if isinstance(cached, list) and not force and now - float(_upstream_cache["surfaces_at"]) < UPSTREAM_CACHE_SECONDS:
            return cached
        loaded = _SURFACE_LOADER[0](now)
        _upstream_cache["surfaces"] = loaded
        _upstream_cache["surfaces_at"] = time.time()
        return loaded


TERMINAL_RUN_STATUSES = {"cancelled", "canceled", "coalesced", "completed", "failed", "skipped"}
TERMINAL_ISSUE_STATUSES = {"cancelled", "canceled", "done"}


def routine_has_active_issue(payload: object) -> bool:
    runs: list = []
    if isinstance(payload, list):
        runs = payload
    elif isinstance(payload, dict):
        for key in ("runs", "items"):
            if isinstance(payload.get(key), list):
                runs = payload[key]
                break
    for run in runs:
        if not isinstance(run, dict):
            continue
        issue = run.get("linkedIssue") if isinstance(run.get("linkedIssue"), dict) else None
        issue_status = issue.get("status") if issue else None
        if isinstance(issue_status, str) and issue_status and issue_status not in TERMINAL_ISSUE_STATUSES:
            return True
        run_status = run.get("status")
        if isinstance(run_status, str) and run_status not in TERMINAL_RUN_STATUSES:
            return True
    return False


def paperclip_exchange_live(method: str, path: str, body: dict | None = None) -> tuple[int, bytes]:
    """Loopback board call. No Authorization header, same path as the ledger comment."""
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(PAPERCLIP_ORIGIN + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return getattr(response, "status", 200), response.read()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read()
    except (OSError, urllib.error.URLError, TimeoutError) as exc:
        raise UpstreamDown(str(exc)) from exc


_PAPERCLIP_EXCHANGE = [paperclip_exchange_live]


def paperclip_exchange(method: str, path: str, body: dict | None = None) -> tuple[int, bytes]:
    return _PAPERCLIP_EXCHANGE[0](method, path, body)


def read_routine_running() -> bool:
    status, raw = paperclip_exchange("GET", f"/api/routines/{UPSTREAM_ROUTINE_ID}/runs")
    if status != 200:
        raise UpstreamDown(f"routine status {status}")
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise UpstreamDown("routine runs were not json") from exc
    return routine_has_active_issue(payload)


def _store_running(value: bool, token: int) -> bool:
    with _upstream_lock:
        if token != _upstream_cache["running_token"]:
            return bool(_upstream_cache["running"])
        _upstream_cache["running"] = value
        _upstream_cache["running_at"] = time.time()
        return value


def upstream_running(now: float, force: bool = False) -> bool:
    with _upstream_lock:
        cached = _upstream_cache["running"]
        if cached is not None and not force and now - float(_upstream_cache["running_at"]) < UPSTREAM_CACHE_SECONDS:
            return bool(cached)
        token = int(_upstream_cache["running_token"])
    try:
        value = read_routine_running()
    except UpstreamDown:
        with _upstream_lock:
            if _upstream_cache["running"] is not None and token == _upstream_cache["running_token"]:
                return bool(_upstream_cache["running"])
        value = False
    return _store_running(value, token)


def mark_routine_running() -> None:
    with _upstream_lock:
        _upstream_cache["running_token"] = int(_upstream_cache["running_token"]) + 1
        _upstream_cache["running"] = True
        _upstream_cache["running_at"] = time.time()


def upstream_idempotency_key(surfaces: list[dict]) -> str:
    return "+".join(str(item.get("tip") or "") for item in surfaces)


def start_upstream_run(
    surfaces: list[dict],
    running: bool,
    exchange=None,
) -> tuple[int, dict]:
    """Ask Paperclip to run the routine. Does not merge, push, or package."""
    caller = paperclip_exchange if exchange is None else exchange
    if running:
        return 409, {"error": "Merge and build is running."}
    if not any(item.get("status") == "ahead" for item in surfaces):
        lines = upstream_status_lines(False, surfaces)
        return 409, {"error": lines[0]}
    try:
        status, _raw = caller(
            "POST",
            f"/api/routines/{UPSTREAM_ROUTINE_ID}/run",
            {"idempotencyKey": upstream_idempotency_key(surfaces), "source": "manual"},
        )
    except UpstreamDown:
        return 502, {"error": UPSTREAM_REFUSED}
    if status in (200, 201, 202):
        mark_routine_running()
        return 200, {"ok": True}
    return 502, {"error": UPSTREAM_REFUSED}


def submit_upstream_run(now: float) -> tuple[int, dict]:
    surfaces = upstream_surfaces(now)
    try:
        running = read_routine_running()
    except UpstreamDown:
        return 502, {"error": UPSTREAM_REFUSED}
    with _upstream_lock:
        _upstream_cache["running_token"] = int(_upstream_cache["running_token"]) + 1
        _upstream_cache["running"] = running
        _upstream_cache["running_at"] = time.time()
    return start_upstream_run(surfaces, running)


def upstream_run_payload(now: float) -> dict:
    surfaces = upstream_surfaces(now)
    running = upstream_running(now)
    return {
        "lines": upstream_status_lines(running, surfaces),
        "nextAt": next_upstream_iso(datetime.fromtimestamp(now, timezone.utc)),
        "running": running,
        "surfaces": surfaces,
    }


def dashboard_payload(directory: Path, now: float) -> dict:
    iphone = current_public(directory, now)
    desktop = desktop_public(directory, now)
    active = iphone.get("status") in ("building", "queued") or desktop.get("status") in ("building", "queued")
    return {
        "cuts": {
            "dmg": cut_view(directory, "dmg", now, desktop),
            "ipa": cut_view(directory, "ipa", now, iphone),
        },
        "desktop": desktop,
        "downloads": current_downloads(directory),
        "iphone": iphone,
        "poll": {"active": active, "activeMs": 2000, "idleMs": 30000},
        "requestEnabled": builds_request.requests_enabled(),
        "servedAt": iso(now),
        "upstreamRun": upstream_run_payload(now),
        "work": public_work(directory, now),
    }


def read_json_body(handler: BaseHTTPRequestHandler) -> tuple[dict | None, str | None]:
    try:
        length = int(handler.headers.get("Content-Length") or "0")
    except ValueError:
        return None, "The request body is not valid."
    if length <= 0 or length > 8192:
        return None, "The request body is not valid."
    try:
        payload = json.loads(handler.rfile.read(length).decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError):
        return None, "The request body is not valid."
    if not isinstance(payload, dict):
        return None, "The request body is not valid."
    return payload, None


def submit_build_request(directory: Path, platform: str, commit: str, override: bool = False) -> tuple[int, dict]:
    if platform not in SURFACES:
        return 400, {"error": "Pick iPhone or Mac."}
    if not re.fullmatch(r"[0-9a-fA-F]{40}", commit or ""):
        return 400, {"error": "The commit must be the full 40-character id."}
    commit = commit.lower()
    spec = SURFACES[platform]
    repo = surface_repo(platform)
    if repo is None:
        return 409, {"error": f"The {spec['name']} repository is not available."}
    builds_request.clear_commit_cache()
    listing = builds_request.list_commits(repo, spec["ref"], served_commit(directory, platform))
    if not listing.get("ok"):
        return 409, {"error": listing.get("error") or "The commit list is not available."}
    served_full = listing.get("served") or ""
    if served_full and commit == served_full:
        return 409, {"error": f"That commit is already the served {spec['name']} package."}
    subjects = builds_request.subjects_through(listing["commits"], commit)
    selected = builds_request.commits_through(listing["commits"], commit)
    if subjects is None or not selected:
        return 409, {"error": "That commit is not on the branch since the served package."}
    now = time.time()
    status_payload = current_public(directory, now) if platform == "ipa" else desktop_public(directory, now)
    if status_payload.get("status") in ("building", "queued") and not override:
        return 409, {"error": f"{spec['name']} is compiling."}
    path = builds_request.request_path(directory)
    existing = builds_request.read_requests(directory)
    current = existing.get(platform)
    if isinstance(current, dict) and current.get("hosted") is not True and not override:
        if not builds_request.is_cancelled(platform, current.get("commit"), time.time(), force=True):
            return 409, {"error": f"An unhosted {spec['name']} request is already on the ledger."}
    try:
        combined = builds_request.combine_commits(repo, served_full, selected)
    except builds_request.CombineError as exc:
        return 409, {"error": str(exc)}
    result_commit = str(combined["commit"])
    kept_both = [item for item in combined.get("keptBoth") or [] if isinstance(item, str)]
    body = builds_request.ledger_body(platform, spec["branch"], result_commit, subjects, kept_both, override=override)
    with locked(directory):
        doc = builds_request.read_requests(directory)
        if builds_request.mark_hosted(doc, platform, served_full, repo):
            builds_request.write_requests(directory, doc)
        current = doc.get(platform)
        if isinstance(current, dict) and current.get("hosted") is not True and not override:
            if not builds_request.is_cancelled(platform, current.get("commit"), time.time(), force=True):
                return 409, {"error": f"An unhosted {spec['name']} request is already on the ledger."}
        previous = path.read_text(encoding="utf-8") if path.exists() else None
        saved_request = {
            "branch": spec["branch"],
            "commit": result_commit,
            "hosted": False,
            "keptBoth": kept_both,
            "platform": platform,
            "requestedAt": iso(time.time()),
            "subjects": subjects,
        }
        if override:
            saved_request["override"] = True
        doc[platform] = saved_request
        builds_request.write_requests(directory, doc)
        try:
            builds_request.post_ledger_comment(body)
        except builds_request.MissingCredential:
            restore_request_file(path, previous)
            builds_request.disable_requests()
            return 503, {"error": "Paperclip rejected the ledger comment without a credential. The request was not saved."}
        except builds_request.LedgerError:
            restore_request_file(path, previous)
            return 502, {"error": "The ledger comment failed. The request was not saved."}
        saved = doc[platform]
    return 201, {"ok": True, "request": saved}


def handle_build_request(handler: BaseHTTPRequestHandler, directory: Path) -> None:
    if not builds_request.requests_enabled():
        send_bytes(handler, 404, "text/plain; charset=utf-8", b"not found\n")
        return
    token = builds_request.token_from_headers(handler.headers)
    if not token or builds_request.verify_access_jwt(token) is None:
        body = (json.dumps({"error": "Sign in through Cloudflare Access to request a build."}) + "\n").encode("utf-8")
        send_bytes(handler, 401, "application/json; charset=utf-8", body)
        return
    payload, error = read_json_body(handler)
    if error or payload is None:
        body = (json.dumps({"error": error or "The request body is not valid."}) + "\n").encode("utf-8")
        send_bytes(handler, 400, "application/json; charset=utf-8", body)
        return
    status, result = submit_build_request(
        directory,
        str(payload.get("platform") or ""),
        str(payload.get("commit") or ""),
        override=payload.get("override") is True,
    )
    body = (json.dumps(result, sort_keys=True) + "\n").encode("utf-8")
    send_bytes(handler, status, "application/json; charset=utf-8", body)


def handle_upstream_run(handler: BaseHTTPRequestHandler, directory: Path) -> None:
    del directory
    token = builds_request.token_from_headers(handler.headers)
    if not token or builds_request.verify_access_jwt(token) is None:
        send_json_code(handler, 401, {"error": "Sign in through Cloudflare Access to request a build."})
        return
    payload, error = read_json_body(handler)
    if error or payload is None:
        send_json_code(handler, 400, {"error": error or "The request body is not valid."})
        return
    status, result = submit_upstream_run(time.time())
    send_json_code(handler, status, result)


def _handler_for(directory: Path):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802
            path = urlparse(self.path).path
            if path in ("/", "/index.html"):
                send_page(self, directory)
                return
            static_name = STATIC_FILES.get(path)
            if static_name:
                send_static(self, static_name)
                return
            if path == "/api/dashboard":
                send_json(self, dashboard_payload(directory, time.time()))
                return
            if path == "/api/status":
                payload = current_public(directory, time.time())
                payload["servedAt"] = iso(time.time())
                send_json(self, payload)
                return
            if path == "/api/desktop":
                payload = desktop_public(directory, time.time())
                payload["servedAt"] = iso(time.time())
                send_json(self, payload)
                return
            if path == "/api/downloads":
                send_json(self, current_downloads(directory))
                return
            if path == "/api/work":
                send_json(self, public_work(directory, time.time()))
                return
            kind = DOWNLOAD_ROUTES.get(path)
            if kind:
                send_download(self, directory, kind)
                return
            older_match = OLDER_DOWNLOAD_ROUTE.fullmatch(path)
            if older_match:
                send_older(self, directory, older_match.group(1))
                return
            match = BUILD_LOG_ROUTE.fullmatch(path)
            if match:
                send_build_log(self, directory, match.group(1))
                return
            send_bytes(self, 404, "text/plain; charset=utf-8", b"not found\n")

        def do_POST(self) -> None:  # noqa: N802
            path = urlparse(self.path).path
            if path == "/api/build-request":
                handle_build_request(self, directory)
                return
            if path == "/api/upstream-run":
                handle_upstream_run(self, directory)
                return
            send_bytes(self, 404, "text/plain; charset=utf-8", b"not found\n")

        def log_message(self, fmt: str, *log_args) -> None:
            return

    return Handler


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Live IPA build status")
    parser.add_argument("--status-dir", default=None)
    sub = parser.add_subparsers(dest="command", required=True)

    start = sub.add_parser("start")
    start.add_argument("--branch", required=True)
    start.add_argument("--commit", required=True)
    start.add_argument("--notes-file", required=True)
    start.add_argument("--notes-error-file", default=None)
    start.add_argument("--parent-pid", required=True)
    start.set_defaults(func=command_start)

    stage = sub.add_parser("stage")
    stage.add_argument("--name", required=True)
    stage.set_defaults(func=command_stage)

    finish = sub.add_parser("finish")
    finish.add_argument("--status", required=True, choices=("succeeded", "failed"))
    finish.add_argument("--message", default=None)
    finish.set_defaults(func=command_finish)

    record = sub.add_parser("record-download")
    record.add_argument("--kind", required=True, choices=("ipa", "macos", "windows", "linux"))
    record.add_argument("--path", required=True)
    record.add_argument("--version", default=None)
    record.add_argument("--commit", default=None)
    record.set_defaults(func=command_record_download)

    held = sub.add_parser("held-back")
    held_sub = held.add_subparsers(dest="held_command", required=True)
    held_add = held_sub.add_parser("add")
    held_add.add_argument("platform", choices=("ipa", "dmg"))
    held_add.add_argument("summary")
    held_add.set_defaults(func=command_held_back_add)
    held_clear = held_sub.add_parser("clear")
    held_clear.add_argument("platform", choices=("ipa", "dmg"))
    held_clear.set_defaults(func=command_held_back_clear)

    package_cmd = sub.add_parser("next-package")
    package_cmd.set_defaults(func=command_next_package)

    active = sub.add_parser("active")
    active.set_defaults(func=command_active)

    show = sub.add_parser("show")
    show.set_defaults(func=command_show)

    follow = sub.add_parser("log-follow")
    follow.add_argument("--build-id", required=True)
    follow.add_argument("--fifo", required=True)
    follow.set_defaults(func=command_log_follow)

    heartbeat = sub.add_parser("heartbeat")
    heartbeat.add_argument("--parent-pid", required=True)
    heartbeat.add_argument("--foreground", action="store_true")
    heartbeat.set_defaults(func=command_heartbeat)

    serve = sub.add_parser("serve")
    serve.add_argument("--host", default="0.0.0.0")
    serve.add_argument("--port", type=int, default=8765)
    serve.set_defaults(func=command_serve)

    test = sub.add_parser("self-test")
    test.set_defaults(func=lambda _args: self_test())
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":
    sys.exit(main())
