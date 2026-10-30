# Paths for verify-nuvio-ios. Source this file.
verify_lib_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
verify_root="$(cd "${verify_lib_dir}/../../../.." && pwd -P)"
VERIFY_STATE_DIR="${verify_root}/build/verify-nuvio-ios"
VERIFY_SESSION_FILE="${VERIFY_STATE_DIR}/session.env"
VERIFY_EVIDENCE_DIR="${VERIFY_STATE_DIR}/evidence"

verify_load_session() {
  if [[ ! -f "${VERIFY_SESSION_FILE}" ]]; then
    echo "No verification session at ${VERIFY_SESSION_FILE}. Run launch.sh first." >&2
    return 1
  fi
  # shellcheck disable=SC1090
  source "${VERIFY_SESSION_FILE}"
}

verify_ensure_local_properties() {
  local properties="${verify_root}/local.properties"
  if [[ -f "${properties}" ]]; then
    return 0
  fi
  # Same public client values the sideload workflow writes when the secret is absent.
  # Names only are logged. The file is gitignored.
  cat > "${properties}" <<'EOF'
# Created by verify-nuvio-ios for a simulator build. Gitignored.
EOF
  python3 - "${properties}" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
text = path.read_text()
values = {
    "NUVIO_SUPABASE_URL": "https://api.nuvio.tv",
    "NUVIO_SUPABASE_ANON_KEY": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzgxNTIxMzQ2LCJleHAiOjE5MzkyMDEzNDZ9.tmQaj682pwzehpqlgCDMnySOqiUvpgRbrE43T4VJpDI",
}
lines = text.splitlines()
present = set()
for line in lines:
    if not line.strip() or line.lstrip().startswith("#") or "=" not in line:
        continue
    key, value = line.split("=", 1)
    if value.strip():
        present.add(key.strip())
with path.open("a") as handle:
    for key, value in values.items():
        if key not in present:
            handle.write(f"{key}={value}\n")
print("local.properties keys:", ", ".join(sorted(present | set(values))))
PY
}

verify_scrub_text() {
  local path="$1"
  [[ -f "${path}" ]] || return 0
  python3 - "${path}" <<'PY'
import re
import sys
from pathlib import Path
path = Path(sys.argv[1])
text = path.read_text(errors="replace")
text = re.sub(
    r"(?i)(https?://\S*(?:real-debrid|alldebrid|premiumize|debrid)\S*)",
    "[redacted-link]",
    text,
)
text = re.sub(r"(?i)(bearer\s+)[A-Za-z0-9._~+/-]+=*", r"\1[redacted]", text)
text = re.sub(r"(?i)(api[_-]?key=)[^&\s]+", r"\1[redacted]", text)
text = re.sub(r"eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", "[redacted-jwt]", text)
path.write_text(text)
PY
}
