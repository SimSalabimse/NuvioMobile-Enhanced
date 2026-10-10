#!/usr/bin/env bash
# Live IPA status helpers sourced by the sideload build scripts.
# Direct usage: scripts/ipa-live-status.sh serve --host 0.0.0.0 --port 8765
# Smoke (no xcodebuild, no download record): scripts/ipa-live-status.sh smoke

set -euo pipefail

ipa_live_status_root() {
  (
    cd "$(dirname "${BASH_SOURCE[0]}")/.."
    pwd -P
  )
}

ipa_live_status() {
  local root
  root="$(ipa_live_status_root)"
  python3 "${root}/scripts/ipa_live_status.py" "$@"
}

# The live site is the listener on 8765. GitHub-hosted workflows call the IPA
# script and have no listener, so they skip this probe.
ipa_live_status_require_listener() {
  local port="${IPA_STATUS_PORT:-8765}"
  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
    return 0
  fi
  if ! curl -fs --max-time 3 -o /dev/null "http://127.0.0.1:${port}/api/status"; then
    echo "ipa-status: listener is not responding on 127.0.0.1:${port}" >&2
    exit 1
  fi
}

# A failed record must fail the build. bash 3.2 ignores a failing command
# inside a function that is on the left of ||, so capture the status explicitly.
ipa_live_status_record_ipa() {
  local ipa_path="$1"
  local version="$2"
  local status=0
  ipa_live_status record-download \
    --kind ipa \
    --path "${ipa_path}" \
    --version "${version}" || status=$?
  if [[ "${status}" -ne 0 ]]; then
    echo "ipa-status: could not record ${ipa_path}" >&2
    exit "${status}"
  fi
}

# Serialize line writes from the stdout and stderr tees. PIPE_BUF is 512 on
# this Mac, and a build line can be longer than that. The mux reader waits for
# a newline, and one printf larger than the pipe buffer (Gradle progress uses
# a single carriage-return line) blocks forever. Send short pieces.
#
# The lock sits next to the mux fifo. A run scratch used as TMPDIR can be
# removed while xcodebuild still holds the fifo. Recreate that parent.
# Sleeping forever on the mkdir stops this reader, the fifo fills, and
# xcodebuild blocks in write().
ipa_log_write() {
  local lock="${IPA_STATUS_LOG_MUX}.lockdir"
  local rest="$1"
  local chunk tries held parent
  parent="$(dirname "${lock}")"
  while true; do
    chunk="${rest:0:4000}"
    rest="${rest:4000}"
    tries=0
    held=0
    while true; do
      if [[ ! -d "${parent}" ]]; then
        mkdir -p "${parent}" 2>/dev/null || true
      fi
      if mkdir "${lock}" 2>/dev/null; then
        held=1
        break
      fi
      if [[ ! -d "${parent}" ]]; then
        break
      fi
      tries=$((tries + 1))
      if [[ "${tries}" -gt 200 ]]; then
        rm -rf "${lock}"
        if mkdir "${lock}" 2>/dev/null; then
          held=1
        fi
        break
      fi
      sleep 0.01
    done
    printf '%s\n' "${chunk}" >&7
    if [[ "${held}" -eq 1 ]]; then
      rmdir "${lock}" 2>/dev/null || rm -rf "${lock}"
    fi
    [[ -n "${rest}" ]] || break
  done
}

# Drain a build fifo without waiting for a newline. read -n returns after
# 4000 characters, under the 64KiB macOS pipe capacity, so a carriage-return
# progress line larger than the pipe still unblocks the writer. A larger read
# would wait for more than the fifo can hold.
ipa_log_read_tee() {
  local mirror_fd="$1"
  local line
  while IFS= read -r -n 4000 line || [[ -n "${line}" ]]; do
    printf '%s\n' "${line}" >&"${mirror_fd}"
    ipa_log_write "${line}"
  done
}

# The session owner tees its own stdout and stderr into the build log.
# A sourced build script inherits that tee, so it does not start another logger.
ipa_live_status_attach_log() {
  local build_id root mux out_fifo err_fifo
  if [[ "${IPA_STATUS_OWNS_SESSION:-0}" != "1" ]]; then
    return 0
  fi
  if [[ "${IPA_STATUS_LOG_ATTACHED:-0}" == "1" ]]; then
    return 0
  fi
  build_id="$(python3 -c 'import json,os; print(json.load(open(os.environ["IPA_STATUS_DIR"] + "/session.json"))["buildId"])')"
  if [[ -z "${build_id}" ]]; then
    echo "ipa-status: no build id for the log" >&2
    return 0
  fi
  root="$(ipa_live_status_root)"
  mux="$(mktemp -u "${TMPDIR:-/tmp}/nuvio-ipa-log.XXXXXX")"
  out_fifo="$(mktemp -u "${TMPDIR:-/tmp}/nuvio-ipa-out.XXXXXX")"
  err_fifo="$(mktemp -u "${TMPDIR:-/tmp}/nuvio-ipa-err.XXXXXX")"
  mkfifo "${mux}" "${out_fifo}" "${err_fifo}"
  exec 8>&1 9>&2
  python3 -u "${root}/scripts/ipa_live_status.py" log-follow \
    --build-id "${build_id}" \
    --fifo "${mux}" &
  IPA_STATUS_LOG_PY_PID=$!
  exec 7>"${mux}"
  IPA_STATUS_LOG_MUX="${mux}"
  IPA_STATUS_LOG_OUT_FIFO="${out_fifo}"
  IPA_STATUS_LOG_ERR_FIFO="${err_fifo}"
  (
    ipa_log_read_tee 8 < "${out_fifo}"
  ) &
  IPA_STATUS_LOG_OUT_PID=$!
  (
    ipa_log_read_tee 9 < "${err_fifo}"
  ) &
  IPA_STATUS_LOG_ERR_PID=$!
  IPA_STATUS_LOG_ATTACHED=1
  exec >"${out_fifo}" 2>"${err_fifo}"
}

ipa_live_status_detach_log() {
  local mux
  if [[ "${IPA_STATUS_LOG_ATTACHED:-0}" != "1" ]]; then
    return 0
  fi
  IPA_STATUS_LOG_ATTACHED=0
  exec 1>&8 2>&9
  exec 8>&- 9>&-
  if [[ -n "${IPA_STATUS_LOG_OUT_PID:-}" ]]; then
    wait "${IPA_STATUS_LOG_OUT_PID}" 2>/dev/null || true
  fi
  if [[ -n "${IPA_STATUS_LOG_ERR_PID:-}" ]]; then
    wait "${IPA_STATUS_LOG_ERR_PID}" 2>/dev/null || true
  fi
  exec 7>&-
  if [[ -n "${IPA_STATUS_LOG_PY_PID:-}" ]]; then
    wait "${IPA_STATUS_LOG_PY_PID}" 2>/dev/null || true
  fi
  mux="${IPA_STATUS_LOG_MUX:-}"
  if [[ -n "${mux}" ]]; then
    rm -rf "${mux}" "${mux}.lockdir"
  fi
  if [[ -n "${IPA_STATUS_LOG_OUT_FIFO:-}" ]]; then
    rm -f "${IPA_STATUS_LOG_OUT_FIFO}"
  fi
  if [[ -n "${IPA_STATUS_LOG_ERR_FIFO:-}" ]]; then
    rm -f "${IPA_STATUS_LOG_ERR_FIFO}"
  fi
  return 0
}

# Capture branch, commit, and release notes, then publish a queued session.
# A build that is already active (the sideload parent) is left alone.
ipa_live_status_open_build() {
  local root branch commit notes_file notes_err meta_out previous_bump repo
  ipa_live_status_require_listener
  root="$(ipa_live_status_root)"
  if [[ -z "${IPA_STATUS_DIR:-}" ]]; then
    IPA_STATUS_DIR="${root}/build/ipa-live-status"
  fi
  export IPA_STATUS_DIR

  if ipa_live_status active; then
    IPA_STATUS_OWNS_SESSION=0
    return 0
  fi

  IPA_STATUS_OWNS_SESSION=1
  # Identity and notes come from the building checkout when a worktree sets
  # IPA_STATUS_REPO. Unset keeps this helper's own repo. Status dir stays here.
  repo="${IPA_STATUS_REPO:-${root}}"
  branch="$(git -C "${repo}" rev-parse --abbrev-ref HEAD)"
  commit="$(git -C "${repo}" rev-parse HEAD)"
  notes_file="$(mktemp "${TMPDIR:-/tmp}/nuvio-ipa-notes.XXXXXX")"
  notes_err="$(mktemp "${TMPDIR:-/tmp}/nuvio-ipa-notes-err.XXXXXX")"
  IPA_STATUS_NOTES_FILE="${notes_file}"
  IPA_STATUS_NOTES_ERR="${notes_err}"
  : >"${notes_file}"
  : >"${notes_err}"

  if meta_out="$(cd "${repo}" && ./scripts/release-metadata.sh "${commit}" 2>"${notes_err}")"; then
    previous_bump="$(printf '%s\n' "${meta_out}" | sed -n 's/^previous_bump=//p')"
    if [[ -z "${previous_bump}" ]]; then
      printf '%s\n' "release-metadata.sh did not report previous_bump" >>"${notes_err}"
    elif ! (cd "${repo}" && ./scripts/generate-release-notes.sh --from "${previous_bump}" --to "${commit}" --offline >"${notes_file}" 2>>"${notes_err}"); then
      : >"${notes_file}"
    else
      : >"${notes_err}"
    fi
  fi

  ipa_live_status start \
    --branch "${branch}" \
    --commit "${commit}" \
    --notes-file "${notes_file}" \
    --notes-error-file "${notes_err}" \
    --parent-pid "$$"
  ipa_live_status heartbeat --parent-pid "$$"
  if [[ -f "${IPA_STATUS_DIR}/heartbeat.pid" ]]; then
    IPA_STATUS_HEARTBEAT_PID="$(tr -cd '0-9' < "${IPA_STATUS_DIR}/heartbeat.pid")"
  fi
  ipa_live_status_attach_log
}

ipa_live_status_close() {
  local code=$?
  if [[ $# -ge 1 ]]; then
    code="$1"
  fi
  if [[ "${IPA_STATUS_OWNS_SESSION:-0}" != "1" ]]; then
    return 0
  fi
  if [[ "${IPA_STATUS_CLOSED:-0}" == "1" ]]; then
    return 0
  fi
  IPA_STATUS_CLOSED=1
  if [[ "${code}" -eq 0 ]]; then
    ipa_live_status finish --status succeeded || true
  else
    ipa_live_status finish --status failed --message "build script exited ${code}" || true
  fi
  if [[ -n "${IPA_STATUS_HEARTBEAT_PID:-}" ]]; then
    kill "${IPA_STATUS_HEARTBEAT_PID}" >/dev/null 2>&1 || true
    wait "${IPA_STATUS_HEARTBEAT_PID}" >/dev/null 2>&1 || true
  fi
  ipa_live_status_detach_log
  if [[ -n "${IPA_STATUS_NOTES_FILE:-}" ]]; then
    rm -f "${IPA_STATUS_NOTES_FILE}"
  fi
  if [[ -n "${IPA_STATUS_NOTES_ERR:-}" ]]; then
    rm -f "${IPA_STATUS_NOTES_ERR}"
  fi
  return 0
}

# Prove a newline-free progress line cannot fill the fifo, and that removing
# the lock's parent does not stall the writer. No listener and no xcodebuild.
ipa_live_status_wait_file() {
  python3 -c 'import os, sys, time
path, want, timeout = sys.argv[1], int(sys.argv[2]), float(sys.argv[3])
deadline = time.time() + timeout
while time.time() < deadline:
    if os.path.exists(path) and os.path.getsize(path) >= want:
        sys.exit(0)
    time.sleep(0.05)
sys.exit(1)' "$1" "$2" "$3"
}

ipa_live_status_smoke_tee() {
  local tmp mux outfifo errfifo captured block_dir block_fifo
  local drain_pid out_pid err_pid writer_pid dummy_pid fail
  local blocked_root blocked_file
  fail=""
  block_dir="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-ipa-block.XXXXXX")"
  block_fifo="${block_dir}/fifo"
  mkfifo "${block_fifo}"
  sleep 30 <"${block_fifo}" &
  dummy_pid=$!
  python3 -c 'import os, sys
payload = b"B" * 200000
fd = os.open(sys.argv[1], os.O_WRONLY)
view = payload
while view:
    wrote = os.write(fd, view)
    if wrote <= 0:
        raise SystemExit("short write")
    view = view[wrote:]
os.close(fd)' "${block_fifo}" &
  writer_pid=$!
  sleep 0.4
  if ! kill -0 "${writer_pid}" 2>/dev/null; then
    fail="writer was not blocked by a full fifo"
  fi
  kill "${dummy_pid}" "${writer_pid}" 2>/dev/null || true
  wait "${dummy_pid}" 2>/dev/null || true
  wait "${writer_pid}" 2>/dev/null || true
  rm -rf "${block_dir}"
  if [[ -n "${fail}" ]]; then
    echo "ipa-status: ${fail}" >&2
    return 1
  fi

  tmp="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-ipa-tee.XXXXXX")"
  captured="$(mktemp "${TMPDIR:-/tmp}/nuvio-ipa-tee-cap.XXXXXX")"
  mux="${tmp}/mux"
  outfifo="${tmp}/out"
  errfifo="${tmp}/err"
  mkfifo "${mux}" "${outfifo}" "${errfifo}"
  # os.read returns whatever is already in the fifo. A buffered
  # read(4096) would wait to fill that size and hide a finished chunk.
  python3 -c 'import os, sys
fd = sys.stdin.fileno()
out = os.open(sys.argv[1], os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644)
while True:
    chunk = os.read(fd, 4096)
    if not chunk:
        break
    os.write(out, chunk)
os.close(out)
' "${captured}" <"${mux}" &
  drain_pid=$!
  exec 7>"${mux}"
  IPA_STATUS_LOG_MUX="${mux}"
  exec 8>/dev/null 9>/dev/null
  (
    ipa_log_read_tee 8 <"${outfifo}"
  ) &
  out_pid=$!
  (
    ipa_log_read_tee 9 <"${errfifo}"
  ) &
  err_pid=$!
  exec 3>"${outfifo}" 4>"${errfifo}"
  # The open fifos survive. The lock parent is gone on purpose.
  rm -rf "${tmp}"
  python3 -c 'import os
def write_all(fd, payload):
    view = payload
    while view:
        wrote = os.write(fd, view)
        if wrote <= 0:
            raise SystemExit("short write")
        view = view[wrote:]
write_all(3, b"P"*79999 + b"\r")' &
  writer_pid=$!
  if ! ipa_live_status_wait_file "${captured}" 80000 5; then
    fail="stdout reader held a carriage-return line"
  fi
  if [[ -z "${fail}" ]]; then
    wait "${writer_pid}" || fail="stdout writer failed"
  else
    kill "${writer_pid}" 2>/dev/null || true
    wait "${writer_pid}" 2>/dev/null || true
  fi
  if [[ -z "${fail}" && ! -d "${tmp}" ]]; then
    fail="lock parent was not recreated"
  fi
  if [[ -z "${fail}" ]]; then
    python3 -c 'import os
def write_all(fd, payload):
    view = payload
    while view:
        wrote = os.write(fd, view)
        if wrote <= 0:
            raise SystemExit("short write")
        view = view[wrote:]
write_all(4, b"E"*40000)' &
    writer_pid=$!
    if ! ipa_live_status_wait_file "${captured}" 120000 5; then
      fail="stderr reader held a carriage-return line"
    fi
    if [[ -z "${fail}" ]]; then
      wait "${writer_pid}" || fail="stderr writer failed"
    else
      kill "${writer_pid}" 2>/dev/null || true
      wait "${writer_pid}" 2>/dev/null || true
    fi
  fi
  exec 3>&- 4>&-
  if [[ -z "${fail}" ]]; then
    local spins
    spins=0
    while kill -0 "${out_pid}" 2>/dev/null || kill -0 "${err_pid}" 2>/dev/null; do
      spins=$((spins + 1))
      if [[ "${spins}" -gt 50 ]]; then
        fail="tee did not finish after the writer closed"
        break
      fi
      sleep 0.1
    done
  fi
  kill "${out_pid}" "${err_pid}" 2>/dev/null || true
  wait "${out_pid}" 2>/dev/null || true
  wait "${err_pid}" 2>/dev/null || true
  # Parent is a file, so mkdir -p cannot recreate it. The write must return.
  if [[ -z "${fail}" ]]; then
    blocked_root="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-ipa-lock.XXXXXX")"
    blocked_file="${blocked_root}/not-a-directory"
    : >"${blocked_file}"
    IPA_STATUS_LOG_MUX="${blocked_file}/mux"
    ipa_log_write "lock-fallback" || fail="lock fallback write failed"
    IPA_STATUS_LOG_MUX="${mux}"
    rm -rf "${blocked_root}"
  fi
  exec 7>&- 8>&- 9>&-
  unset IPA_STATUS_LOG_MUX
  wait "${drain_pid}" 2>/dev/null || true
  if [[ -z "${fail}" ]]; then
    python3 -c '
import pathlib, sys
data = pathlib.Path(sys.argv[1]).read_bytes().replace(b"\n", b"")
want = (b"P" * 79999 + b"\r") + (b"E" * 40000) + b"lock-fallback"
if data != want:
    print("captured mismatch bytes=%s" % len(data), file=sys.stderr)
    sys.exit(1)
' "${captured}" || fail="captured log missed the progress line"
  fi
  rm -f "${captured}"
  rm -rf "${tmp}"
  if [[ -n "${fail}" ]]; then
    echo "ipa-status: ${fail}" >&2
    return 1
  fi
}

# Prove the log path without xcodebuild and without record-download.
ipa_live_status_smoke() {
  ipa_live_status_smoke_tee
  if [[ -z "${IPA_STATUS_REPO:-}" ]]; then
    if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
      IPA_STATUS_REPO="$(pwd -P)"
      export IPA_STATUS_REPO
    fi
  fi
  if ipa_live_status active; then
    echo "ipa-status: a build session is already active" >&2
    return 1
  fi
  trap ipa_live_status_close EXIT
  ipa_live_status_open_build
  if [[ "${IPA_STATUS_OWNS_SESSION:-0}" != "1" ]]; then
    echo "ipa-status: smoke did not open a session" >&2
    return 1
  fi
  ipa_live_status stage --name preflight
  echo "smoke: first $(python3 -c 'import time; print("%.3f" % time.time())')"
  sleep 1.2
  echo "smoke: second $(python3 -c 'import time; print("%.3f" % time.time())')"
  echo "smoke: wrap $(python3 -c 'print("w" * 200)')"
  echo "TRAKT_CLIENT_SECRET=supersecretvalue"
  sleep 2.5
  ipa_live_status_close 0
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  if [[ "${1:-}" == "smoke" ]]; then
    ipa_live_status_smoke
  else
    ipa_live_status "$@"
  fi
fi
