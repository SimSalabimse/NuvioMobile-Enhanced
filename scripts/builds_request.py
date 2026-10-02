#!/usr/bin/env python3
"""Commit cuts and Cloudflare Access checks for the builds page.

The ledger file is build-request.json beside next-package.json. One object
per surface. This module does not store a Paperclip token. Loopback
local_trusted comments as the board. A rejected comment rolls the file back.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

PAPERCLIP_ORIGIN = "http://127.0.0.1:3100"
LEDGER_ISSUE_ID = "e46fccb3-bde5-4f5f-a481-9e11795bf16f"
ACCESS_TEAM = os.environ.get("NUVIO_ACCESS_TEAM", "steelyx")
ACCESS_AUD = os.environ.get(
    "NUVIO_ACCESS_AUD",
    "1f77c6f46fe438ee8c29ebf10903263581f3ae6f8a40779a7f445f1164b5b2ab",
)
ACCESS_ISS = f"https://{ACCESS_TEAM}.cloudflareaccess.com"
MOBILE_REF = "refs/heads/enhanced"
DESKTOP_REF = "refs/heads/Dev"
MOBILE_BRANCH = "enhanced"
DESKTOP_BRANCH = "Dev"
ISSUE_ID = re.compile(r"\bSIM-\d+\b")
CANCEL_WORD = re.compile(r"\bcancel(?:led|ed|s)?\b", re.I)
SHA_RE = re.compile(r"\b[0-9a-f]{40}\b")
FULL_SHA = re.compile(r"[0-9a-f]{40}")

_requests_enabled = True
_jwks_cache: dict = {"at": 0.0, "keys": {}}
_ledger_cache: dict = {"at": 0.0, "index": {}, "cancelled": {"dmg": [], "ipa": []}}
_commit_cache: dict = {}


class MissingCredential(Exception):
    pass


class LedgerError(Exception):
    pass


class CombineError(Exception):
    pass


def requests_enabled() -> bool:
    return _requests_enabled


def disable_requests() -> None:
    global _requests_enabled
    _requests_enabled = False


def b64url_decode(value: str) -> bytes:
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        raise ValueError("bad base64url")
    pad = "=" * ((4 - len(value) % 4) % 4)
    return base64.urlsafe_b64decode(value + pad)


def b64url_encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _der_len(length: int) -> bytes:
    if length < 0x80:
        return bytes([length])
    raw = length.to_bytes((length.bit_length() + 7) // 8, "big")
    return bytes([0x80 | len(raw)]) + raw


def _der_tlv(tag: int, content: bytes) -> bytes:
    return bytes([tag]) + _der_len(len(content)) + content


def _der_integer(data: bytes) -> bytes:
    data = data.lstrip(b"\x00") or b"\x00"
    if data[0] & 0x80:
        data = b"\x00" + data
    return _der_tlv(0x02, data)


def jwk_rsa_pem(modulus: str, exponent: str) -> str:
    pkcs1 = _der_tlv(0x30, _der_integer(b64url_decode(modulus)) + _der_integer(b64url_decode(exponent)))
    algorithm = _der_tlv(0x30, bytes.fromhex("06092a864886f70d0101010500"))
    spki = _der_tlv(0x30, algorithm + _der_tlv(0x03, b"\x00" + pkcs1))
    body = base64.encodebytes(spki).decode("ascii")
    return "-----BEGIN PUBLIC KEY-----\n" + body + "-----END PUBLIC KEY-----\n"


def fetch_jwks(force: bool = False) -> dict:
    now = time.time()
    if not force and _jwks_cache["keys"] and now - _jwks_cache["at"] < 3600:
        return _jwks_cache["keys"]
    url = ACCESS_ISS + "/cdn-cgi/access/certs"
    with urllib.request.urlopen(url, timeout=8) as response:
        payload = json.loads(response.read().decode("utf-8"))
    keys = {}
    for key in payload.get("keys") or []:
        if not isinstance(key, dict):
            continue
        if key.get("kty") == "RSA" and isinstance(key.get("kid"), str) and key.get("n") and key.get("e"):
            keys[key["kid"]] = key
    _jwks_cache["at"] = now
    _jwks_cache["keys"] = keys
    return keys


def _rsa_verify(pem: str, signing_input: bytes, signature: bytes) -> bool:
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        key_path = root / "key.pem"
        data_path = root / "data.bin"
        sig_path = root / "sig.bin"
        key_path.write_text(pem, encoding="utf-8")
        data_path.write_bytes(signing_input)
        sig_path.write_bytes(signature)
        result = subprocess.run(
            ["openssl", "dgst", "-sha256", "-verify", str(key_path), "-signature", str(sig_path), str(data_path)],
            check=False,
            capture_output=True,
            timeout=5,
        )
    return result.returncode == 0


def verify_access_jwt(token: str, now: float | None = None) -> dict | None:
    """Return Access claims, or None. Fail closed on any doubt."""
    if not isinstance(token, str) or token.count(".") != 2:
        return None
    header_b64, payload_b64, sig_b64 = token.split(".")
    try:
        header = json.loads(b64url_decode(header_b64))
        claims = json.loads(b64url_decode(payload_b64))
        signature = b64url_decode(sig_b64)
    except (ValueError, json.JSONDecodeError, UnicodeError):
        return None
    if not isinstance(header, dict) or not isinstance(claims, dict):
        return None
    if header.get("alg") != "RS256":
        return None
    kid = header.get("kid")
    if not isinstance(kid, str) or not kid:
        return None
    moment = time.time() if now is None else now
    exp = claims.get("exp")
    if isinstance(exp, bool) or not isinstance(exp, (int, float)) or float(exp) < moment - 60:
        return None
    nbf = claims.get("nbf")
    if isinstance(nbf, (int, float)) and not isinstance(nbf, bool) and float(nbf) > moment + 60:
        return None
    if claims.get("iss") != ACCESS_ISS:
        return None
    aud = claims.get("aud")
    auds = aud if isinstance(aud, list) else [aud]
    if ACCESS_AUD not in auds:
        return None
    identity = claims.get("email") or claims.get("sub") or claims.get("common_name")
    if not isinstance(identity, str) or not identity.strip():
        return None
    try:
        keys = fetch_jwks()
    except (OSError, urllib.error.URLError, json.JSONDecodeError, ValueError, TimeoutError):
        return None
    key = keys.get(kid)
    if not isinstance(key, dict):
        return None
    try:
        pem = jwk_rsa_pem(str(key["n"]), str(key["e"]))
    except ValueError:
        return None
    if not _rsa_verify(pem, f"{header_b64}.{payload_b64}".encode("ascii"), signature):
        return None
    return claims


def token_from_headers(headers) -> str | None:
    raw = headers.get("Cf-Access-Jwt-Assertion") if headers is not None else None
    if isinstance(raw, str) and raw.strip():
        return raw.strip()
    cookie = headers.get("Cookie") if headers is not None else None
    if not isinstance(cookie, str):
        return None
    for part in cookie.split(";"):
        name, _, value = part.strip().partition("=")
        if name == "CF_Authorization" and value.strip():
            return value.strip()
    return None


def run_git(
    repo: Path,
    args: list[str],
    env: dict[str, str] | None = None,
    timeout: int = 15,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(repo), *args],
        check=False,
        capture_output=True,
        text=True,
        timeout=timeout,
        env=env,
    )


def git_commit(repo: Path, rev: str) -> str | None:
    if not repo.is_dir():
        return None
    result = run_git(repo, ["rev-parse", "--verify", "--end-of-options", f"{rev}^{{commit}}"])
    if result.returncode != 0:
        return None
    sha = result.stdout.strip().lower()
    if FULL_SHA.fullmatch(sha):
        return sha
    return None


def is_ancestor(repo: Path, older: str, newer: str) -> bool:
    result = run_git(repo, ["merge-base", "--is-ancestor", "--end-of-options", older, newer])
    return result.returncode == 0


def issue_ids(*texts: str) -> list[str]:
    found: list[str] = []
    seen: set[str] = set()
    for text in texts:
        for match in ISSUE_ID.findall(text or ""):
            if match not in seen:
                seen.add(match)
                found.append(match)
    return found


def mobile_repository() -> Path:
    env = os.environ.get("NUVIO_MOBILE_REPO")
    if env:
        return Path(env).expanduser().resolve()
    start = Path(__file__).resolve().parents[1]
    result = run_git(start, ["rev-parse", "--path-format=absolute", "--git-common-dir"])
    if result.returncode != 0:
        return start
    common = Path(result.stdout.strip())
    if common.name == ".git":
        return common.parent
    return common.parent


def desktop_repository() -> Path | None:
    candidates: list[Path] = []
    env = os.environ.get("NUVIO_DESKTOP_REPO")
    if env:
        candidates.append(Path(env).expanduser())
    repos = mobile_repository() / ".paperclip-repositories"
    if repos.is_dir():
        for child in sorted(repos.iterdir()):
            if child.is_dir():
                candidates.append(child)
    candidates.append(Path("/Users/simsalabim/NuvioDesktop"))
    for candidate in candidates:
        if git_commit(candidate, DESKTOP_REF):
            return candidate.resolve()
    return None


def list_commits(repo: Path, branch_ref: str, served: str) -> dict:
    """Commits on branch_ref that the served package does not already contain."""
    now = time.time()
    cache_key = (str(repo), branch_ref, served.lower() if isinstance(served, str) else "")
    cached = _commit_cache.get(cache_key)
    if cached and now - cached[0] < 5:
        return cached[1]
    tip = git_commit(repo, branch_ref)
    if tip is None:
        payload = {"commits": [], "error": "That branch is not available.", "ok": False, "served": "", "tip": ""}
        _commit_cache[cache_key] = (now, payload)
        return payload
    served_text = served.lower() if isinstance(served, str) else ""
    if not served_text:
        payload = {
            "commits": [],
            "error": "No served commit is recorded.",
            "ok": False,
            "served": "",
            "tip": tip,
        }
        _commit_cache[cache_key] = (now, payload)
        return payload
    served_full = git_commit(repo, served_text)
    if served_full is None:
        payload = {
            "commits": [],
            "error": "The served commit is not in this repository.",
            "ok": False,
            "served": "",
            "tip": tip,
        }
        _commit_cache[cache_key] = (now, payload)
        return payload
    if served_full == tip or is_ancestor(repo, tip, served_full):
        payload = {"commits": [], "error": None, "ok": True, "served": served_full, "tip": tip}
        _commit_cache[cache_key] = (now, payload)
        return payload
    result = run_git(
        repo,
        ["log", "--reverse", "--format=%H%x1f%s%x1f%b%x1e", "--end-of-options", f"{served_full}..{branch_ref}"],
    )
    if result.returncode != 0:
        payload = {"commits": [], "error": "The commit list could not be read.", "ok": False, "served": served_full, "tip": tip}
        _commit_cache[cache_key] = (now, payload)
        return payload
    oldest: list[dict] = []
    for record in result.stdout.split("\x1e"):
        record = record.strip("\n")
        if not record.strip():
            continue
        parts = record.split("\x1f")
        if len(parts) < 2:
            continue
        sha = parts[0].strip().lower()
        if not FULL_SHA.fullmatch(sha):
            continue
        subject = parts[1].strip() or "(no subject)"
        body = parts[2] if len(parts) > 2 else ""
        oldest.append(
            {
                "commit": sha,
                "issues": issue_ids(subject, body),
                "short": sha[:8],
                "subject": subject,
            }
        )
    newest = list(reversed(oldest))
    payload = {"commits": newest, "error": None, "ok": True, "served": served_full, "tip": tip}
    _commit_cache[cache_key] = (now, payload)
    return payload


def clear_commit_cache() -> None:
    _commit_cache.clear()


def commits_through(commits: list[dict], chosen: str) -> list[str] | None:
    """Full ids from the branch root of this list through chosen, oldest first."""
    index = next((i for i, row in enumerate(commits) if row.get("commit") == chosen), None)
    if index is None:
        return None
    ordered = list(reversed(commits[index:]))
    found: list[str] = []
    for row in ordered:
        sha = str(row.get("commit") or "")
        if FULL_SHA.fullmatch(sha):
            found.append(sha)
    return found or None


def subjects_through(commits: list[dict], chosen: str) -> list[str] | None:
    """Subjects from the branch root of this list through chosen, oldest first."""
    index = next((i for i, row in enumerate(commits) if row.get("commit") == chosen), None)
    if index is None:
        return None
    ordered = list(reversed(commits[index:]))
    return [str(row.get("subject") or "(no subject)") for row in ordered]


def _branch_snapshot(repo: Path) -> str:
    heads = run_git(repo, ["for-each-ref", "--format=%(refname) %(objectname)", "refs/heads"])
    current = run_git(repo, ["rev-parse", "--verify", "HEAD"])
    return heads.stdout + "\n" + current.stdout


def _git_bytes(repo: Path, args: list[str], env: dict[str, str] | None = None, data: bytes | None = None) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        ["git", "-C", str(repo), *args],
        check=False,
        capture_output=True,
        input=data,
        timeout=30,
        env=env,
    )


def _lookup_blob(repo: Path, rev: str, path: str) -> tuple[str, str] | None:
    result = _git_bytes(repo, ["ls-tree", "-z", "--end-of-options", rev, "--", path])
    if result.returncode != 0 or not result.stdout:
        return None
    record = result.stdout.split(b"\0", 1)[0]
    meta, _, _found = record.partition(b"\t")
    parts = meta.decode("ascii", "replace").split()
    if len(parts) < 3 or parts[1] != "blob" or not FULL_SHA.fullmatch(parts[2]):
        return None
    return parts[0], parts[2]


def _cat_blob(repo: Path, sha: str) -> bytes:
    result = _git_bytes(repo, ["cat-file", "blob", "--end-of-options", sha])
    if result.returncode != 0:
        raise CombineError("A file in the replay could not be read.")
    return result.stdout


def _write_blob(repo: Path, data: bytes) -> str:
    result = _git_bytes(repo, ["hash-object", "-w", "--stdin"], data=data)
    if result.returncode != 0:
        raise CombineError("A replayed file could not be stored.")
    sha = result.stdout.decode("ascii", "replace").strip().lower()
    if not FULL_SHA.fullmatch(sha):
        raise CombineError("A replayed file could not be stored.")
    return sha


def _changed_paths(repo: Path, parent: str, commit: str) -> list[tuple[str, str]]:
    result = _git_bytes(
        repo,
        ["diff-tree", "-r", "--no-renames", "--no-commit-id", "--name-status", "-z", "--end-of-options", parent, commit],
    )
    if result.returncode != 0:
        raise CombineError("A selected commit could not be read.")
    parts = result.stdout.split(b"\0")
    found: list[tuple[str, str]] = []
    index = 0
    while index + 1 < len(parts):
        status = parts[index].decode("utf-8", "replace")
        path = parts[index + 1].decode("utf-8", "surrogateescape")
        index += 2
        if not status or not path:
            continue
        found.append((status[:1], path))
    return found


def _index_entry(repo: Path, env: dict[str, str], path: str) -> tuple[str, str] | None:
    result = _git_bytes(repo, ["ls-files", "-s", "-z", "--", path], env=env)
    if result.returncode != 0 or not result.stdout:
        return None
    meta = result.stdout.split(b"\t", 1)[0].decode("ascii", "replace")
    parts = meta.split()
    if len(parts) < 2 or not FULL_SHA.fullmatch(parts[1]):
        return None
    return parts[0], parts[1]


def _stage(repo: Path, env: dict[str, str], path: str, mode: str, sha: str) -> None:
    line = f"{mode} {sha} 0\t{path}\n".encode("utf-8", "surrogateescape")
    result = _git_bytes(repo, ["update-index", "--index-info"], env=env, data=line)
    if result.returncode != 0:
        raise CombineError(f"The replay could not stage {path}.")


def _unstage(repo: Path, env: dict[str, str], path: str) -> None:
    result = _git_bytes(repo, ["update-index", "--force-remove", "--", path], env=env)
    if result.returncode != 0:
        raise CombineError(f"The replay could not stage {path}.")


def _merge_file(current: bytes, ancestor: bytes, other: bytes) -> tuple[bytes, bool]:
    with tempfile.TemporaryDirectory(prefix="nuvio-merge-") as temporary:
        root = Path(temporary)
        paths = []
        for name, payload in (("current", current), ("base", ancestor), ("other", other)):
            path = root / name
            path.write_bytes(payload)
            paths.append(str(path))
        plain = subprocess.run(["git", "merge-file", "-p", *paths], check=False, capture_output=True, timeout=30)
        if plain.returncode == 0:
            return plain.stdout, False
        if plain.returncode < 0:
            raise CombineError("The file merge failed.")
        for name, payload in (("current", current), ("base", ancestor), ("other", other)):
            (root / name).write_bytes(payload)
        union = subprocess.run(
            ["git", "merge-file", "--union", "-p", *paths],
            check=False,
            capture_output=True,
            timeout=30,
        )
        if union.returncode < 0:
            raise CombineError("The file merge failed.")
        return union.stdout, True


def _merge_states(current: bytes | None, ancestor: bytes | None, other: bytes | None) -> tuple[bytes | None, bool]:
    if current == other:
        return current, False
    if ancestor == current:
        return other, False
    if ancestor == other:
        return current, False
    if current is None or other is None or b"\0" in current or b"\0" in (other or b""):
        survivor = current if current is not None else other
        if current is not None and other is not None:
            return current + b"\n" + other, True
        return survivor, True
    merged, conflict = _merge_file(current, ancestor if ancestor is not None else b"", other)
    return merged, conflict


def _parent_of(repo: Path, sha: str) -> str:
    parent = git_commit(repo, f"{sha}^")
    if parent:
        return parent
    return "4b825dc642cb6eb9a060e54bf8d69288fbee4904"


def _public_git_error(text: str) -> str:
    line = ""
    for row in (text or "").splitlines():
        if row.strip():
            line = row.strip()
    line = re.sub(r"://[^/\s]+@", "://", line)
    return line[:200]


def _commit_message(repo: Path, commits: list[str], kept: list[str]) -> str:
    lines = ["Keep both edits from the selected commits", ""]
    for sha in commits:
        subject = run_git(repo, ["log", "-1", "--format=%s", "--end-of-options", sha]).stdout.strip()
        lines.append(f"- {subject or sha}")
    if kept:
        lines.extend(["", "Both edits kept:"])
        lines.extend(f"- {path}" for path in kept)
    return "\n".join(lines) + "\n"


def combine_commits(repo: Path, base: str, commits: list[str]) -> dict:
    """Replay commits onto base, oldest first, keeping both edits of a shared file.

    The ancestor of a shared file is the served package. A later commit's own
    parent already contains the earlier edit, and replaying that patch would
    drop it. A clean merge stays as the merge. An overlapping merge keeps both
    texts and records the path. The branch refs are not moved.
    """
    if not repo.is_dir():
        raise CombineError("The repository is not available.")
    resolved_base = git_commit(repo, base)
    if resolved_base is None:
        raise CombineError("The served commit is not in this repository.")
    resolved: list[str] = []
    for item in commits:
        sha = git_commit(repo, item.strip())
        if sha is None:
            raise CombineError("A selected commit is not in this repository.")
        if sha != resolved_base:
            resolved.append(sha)
    if not resolved:
        raise CombineError("The commit list is empty.")
    newest = resolved[-1]
    before = _branch_snapshot(repo)
    index_path = Path(tempfile.mkdtemp(prefix="nuvio-combine-")) / "index"
    env = os.environ.copy()
    env["GIT_INDEX_FILE"] = str(index_path)
    env["GIT_TERMINAL_PROMPT"] = "0"
    kept: set[str] = set()
    seen: set[str] = set()
    try:
        seeded = _git_bytes(repo, ["read-tree", "--end-of-options", resolved_base], env=env)
        if seeded.returncode != 0:
            raise CombineError("The served tree could not be read.")
        for sha in resolved:
            for _status, path in _changed_paths(repo, _parent_of(repo, sha), sha):
                other = _lookup_blob(repo, sha, path)
                if path not in seen:
                    seen.add(path)
                    if other is None:
                        _unstage(repo, env, path)
                    else:
                        _stage(repo, env, path, other[0], other[1])
                    continue
                # Merge against the served file so an earlier edit stays in the file.
                current = _index_entry(repo, env, path)
                ancestor = _lookup_blob(repo, resolved_base, path)
                current_bytes = _cat_blob(repo, current[1]) if current else None
                ancestor_bytes = _cat_blob(repo, ancestor[1]) if ancestor else None
                other_bytes = _cat_blob(repo, other[1]) if other else None
                merged, conflict = _merge_states(current_bytes, ancestor_bytes, other_bytes)
                if conflict:
                    kept.add(path)
                if merged is None:
                    _unstage(repo, env, path)
                    continue
                if other is not None and merged == other_bytes:
                    _stage(repo, env, path, other[0], other[1])
                elif current is not None and merged == current_bytes:
                    _stage(repo, env, path, current[0], current[1])
                else:
                    mode = (other[0] if other else None) or (current[0] if current else None) or "100644"
                    _stage(repo, env, path, mode, _write_blob(repo, merged))
        if _branch_snapshot(repo) != before:
            raise CombineError("The combine moved a branch.")
        written = _git_bytes(repo, ["write-tree"], env=env)
        if written.returncode != 0:
            raise CombineError("The replay tree could not be written.")
        tree = written.stdout.decode("ascii", "replace").strip().lower()
        if not FULL_SHA.fullmatch(tree):
            raise CombineError("The replay tree could not be written.")
        tip_tree = run_git(repo, ["rev-parse", "--verify", "--end-of-options", f"{newest}^{{tree}}"])
        same = tip_tree.returncode == 0 and tip_tree.stdout.strip().lower() == tree
        kept_both = sorted(kept)
        if same:
            return {"commit": newest, "keptBoth": kept_both, "ref": None, "sameAsTip": True}
        commit_env = env.copy()
        commit_env.update(
            {
                "GIT_AUTHOR_NAME": "Nuvio Builds",
                "GIT_AUTHOR_EMAIL": "builds@nuvio.local",
                "GIT_COMMITTER_NAME": "Nuvio Builds",
                "GIT_COMMITTER_EMAIL": "builds@nuvio.local",
            }
        )
        created = subprocess.run(
            ["git", "-C", str(repo), "commit-tree", tree, "-p", resolved_base, "-m", _commit_message(repo, resolved, kept_both)],
            check=False,
            capture_output=True,
            text=True,
            timeout=30,
            env=commit_env,
        )
        if created.returncode != 0:
            raise CombineError("The replay commit could not be written.")
        commit = created.stdout.strip().lower()
        if not FULL_SHA.fullmatch(commit):
            raise CombineError("The replay commit could not be written.")
        if _branch_snapshot(repo) != before:
            raise CombineError("The combine moved a branch.")
        ref = f"refs/cuts/{commit}"
        pushed = subprocess.run(
            ["git", "-C", str(repo), "push", "origin", f"{commit}:{ref}"],
            check=False,
            capture_output=True,
            text=True,
            timeout=60,
            env=commit_env,
        )
        if pushed.returncode != 0:
            detail = _public_git_error(pushed.stderr or pushed.stdout)
            message = "The cuts ref could not be pushed."
            if detail:
                message = f"{message} {detail}"
            raise CombineError(message)
        if _branch_snapshot(repo) != before:
            raise CombineError("The combine moved a branch.")
        return {"commit": commit, "keptBoth": kept_both, "ref": ref, "sameAsTip": False}
    finally:
        index_path.unlink(missing_ok=True)
        try:
            index_path.parent.rmdir()
        except OSError:
            pass


def request_path(directory: Path) -> Path:
    return directory / "build-request.json"


def empty_requests() -> dict:
    return {"dmg": None, "ipa": None}


def read_requests(directory: Path) -> dict:
    path = request_path(directory)
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return empty_requests()
    doc = empty_requests()
    if not isinstance(raw, dict):
        return doc
    for platform in ("ipa", "dmg"):
        obj = raw.get(platform)
        if isinstance(obj, dict) and obj.get("platform") == platform and isinstance(obj.get("commit"), str):
            doc[platform] = obj
    return doc


def write_requests(directory: Path, doc: dict) -> None:
    path = request_path(directory)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(doc, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def contained_by_served(repo: Path | None, requested: str, served: str) -> bool:
    if not requested or not served:
        return False
    if requested == served:
        return True
    if repo is None:
        return False
    return is_ancestor(repo, requested, served)


def mark_hosted(doc: dict, platform: str, served: str, repo: Path | None) -> bool:
    obj = doc.get(platform)
    if not isinstance(obj, dict) or obj.get("hosted") is True:
        return False
    requested = obj.get("commit")
    if not isinstance(requested, str):
        return False
    if contained_by_served(repo, requested.lower(), served.lower() if isinstance(served, str) else ""):
        obj["hosted"] = True
        return True
    return False


def _comment_rows(payload: object) -> list[dict]:
    if isinstance(payload, list):
        return [row for row in payload if isinstance(row, dict)]
    if isinstance(payload, dict):
        for key in ("comments", "items"):
            value = payload.get(key)
            if isinstance(value, list):
                return [row for row in value if isinstance(row, dict)]
    return []


def refresh_ledger(now: float, force: bool = False) -> dict:
    if not force and now - _ledger_cache["at"] < 300:
        return _ledger_cache
    try:
        request = urllib.request.Request(
            PAPERCLIP_ORIGIN + f"/api/issues/{LEDGER_ISSUE_ID}/comments",
            headers={"Accept": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=8) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (OSError, urllib.error.URLError, json.JSONDecodeError, ValueError, TimeoutError):
        _ledger_cache["at"] = now
        return _ledger_cache
    index: dict[str, list[str]] = {}
    cancelled = {"dmg": [], "ipa": []}
    for row in _comment_rows(payload):
        body = row.get("body") if isinstance(row.get("body"), str) else ""
        issues = issue_ids(body)
        shas = SHA_RE.findall(body.lower())
        for sha in shas:
            bucket = index.setdefault(sha, [])
            for issue in issues:
                if issue not in bucket:
                    bucket.append(issue)
        if not CANCEL_WORD.search(body):
            continue
        low = body.lower()
        for platform, words in (("ipa", ("iphone", "ipa")), ("dmg", ("mac", "dmg"))):
            if not any(word in low for word in words):
                continue
            for sha in shas:
                if sha not in cancelled[platform]:
                    cancelled[platform].append(sha)
    _ledger_cache["at"] = now
    _ledger_cache["index"] = index
    _ledger_cache["cancelled"] = cancelled
    return _ledger_cache


def attach_ledger_issues(commits: list[dict], now: float) -> None:
    index = refresh_ledger(now).get("index") or {}
    for row in commits:
        extra = index.get(row.get("commit")) or []
        merged = list(row.get("issues") or [])
        for issue in extra:
            if issue not in merged:
                merged.append(issue)
        row["issues"] = merged


def is_cancelled(platform: str, commit: object, now: float, force: bool = False) -> bool:
    if not isinstance(commit, str) or not FULL_SHA.fullmatch(commit.lower()):
        return False
    cache = refresh_ledger(now, force=force)
    return commit.lower() in (cache.get("cancelled") or {}).get(platform, [])


def ledger_body(
    platform: str,
    branch: str,
    commit: str,
    subjects: list[str],
    kept_both: list[str] | None = None,
    override: bool = False,
) -> str:
    surface = "iPhone" if platform == "ipa" else "Mac"
    lines = [
        "The builds page requested this cut. This is not a test.",
        "",
        f"Surface: {surface}",
        f"Branch: {branch}",
        f"Commit: {commit}",
        "Included subjects:",
    ]
    if subjects:
        lines.extend(f"- {subject}" for subject in subjects)
    else:
        lines.append("- (none)")
    if kept_both:
        lines.append("Both edits kept:")
        lines.extend(f"- {path}" for path in kept_both)
    if override:
        other = "Mac" if platform == "ipa" else "iPhone"
        lines.append(
            f"Override: stop the running {surface} compile and replace its unhosted request. "
            f"Do not stop the {other} compile."
        )
    return "\n".join(lines) + "\n"


def post_ledger_comment(body: str) -> None:
    """Comment on SIM-131. No Authorization header: loopback is the local board."""
    payload = json.dumps({"body": body}).encode("utf-8")
    request = urllib.request.Request(
        PAPERCLIP_ORIGIN + f"/api/issues/{LEDGER_ISSUE_ID}/comments",
        data=payload,
        headers={"Accept": "application/json", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            raw = response.read()
            status = getattr(response, "status", 200)
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", "replace")[:300]
        if exc.code in (401, 403):
            raise MissingCredential(detail)
        raise LedgerError(detail)
    except (OSError, urllib.error.URLError, TimeoutError) as exc:
        raise LedgerError(str(exc))
    if status not in (200, 201):
        raise LedgerError(f"ledger status {status}")
    try:
        saved = json.loads(raw.decode("utf-8"))
    except json.JSONDecodeError as exc:
        raise LedgerError("ledger comment was not json") from exc
    if not isinstance(saved, dict) or not saved.get("id"):
        raise LedgerError("ledger comment was not saved")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build request cuts")
    sub = parser.add_subparsers(dest="command", required=True)
    combine = sub.add_parser("combine")
    combine.add_argument("--repo", required=True)
    combine.add_argument("--base", required=True)
    combine.add_argument("--commits", required=True)
    args = parser.parse_args(argv)
    if args.command != "combine":
        return 2
    selected = [item.strip() for item in str(args.commits).split(",") if item.strip()]
    try:
        result = combine_commits(Path(args.repo), str(args.base), selected)
    except CombineError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    sys.stdout.write(json.dumps(result, sort_keys=True) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
