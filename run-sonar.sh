#!/usr/bin/env bash
set -euo pipefail

# ── Config ──────────────────────────────────────────────────────────
PROJECT_KEY="etendosoftware_com.etendoerp.go_4f22c2cf-5ab2-4734-8244-f9eb74bbbb7a"
POLL_INTERVAL=5      # seconds between polls
MAX_WAIT=300         # max seconds to wait for analysis
REPORT_DIR="sonar-reports"
BASE_REF=""
CHANGED_ONLY="false"
ALLOW_DIRTY="false"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CLASSIC_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-ref)
      BASE_REF="${2:-}"
      shift 2
      ;;
    --changed-only)
      CHANGED_ONLY="true"
      shift
      ;;
    --allow-dirty)
      ALLOW_DIRTY="true"
      shift
      ;;
    --report-dir)
      REPORT_DIR="${2:-}"
      shift 2
      ;;
    *)
      echo "ERROR: Unknown argument: $1"
      exit 1
      ;;
  esac
done

load_env_file() {
  local env_file="$1"
  [[ -f "$env_file" ]] || return 0
  while IFS='=' read -r key value; do
    [[ -n "$key" ]] || continue
    [[ "$key" =~ ^# ]] && continue
    case "$key" in
      SONAR_HOST_URL|SONAR_TOKEN)
        if [[ -z "${!key:-}" ]]; then
          export "$key=$value"
        fi
        ;;
    esac
  done < "$env_file"
}

load_gradle_property() {
  local key="$1"
  local file="$CLASSIC_ROOT/gradle.properties"
  [[ -f "$file" ]] || return 0
  awk -F'=' -v k="$key" '$1==k {sub(/^[^=]*=/, ""); print; exit}' "$file"
}

prompt_for_sonar_env_file() {
  local preferred_env="$SCRIPT_DIR/.env"
  if [[ -t 0 ]]; then
    echo "ERROR: Missing Sonar configuration."
    echo "Complete one of these files with SONAR_HOST_URL and SONAR_TOKEN before running Sonar:"
    echo "  - $preferred_env"
    echo "  - $CLASSIC_ROOT/.env"
    echo
    echo "Example:"
    echo "SONAR_HOST_URL=https://sonar.example.com"
    echo "SONAR_TOKEN=your_token_here"
    echo
    read -r -p "Press Enter after completing the .env file, or Ctrl-C to cancel... " _
    load_env_file "$preferred_env"
    load_env_file "$CLASSIC_ROOT/.env"
    return
  fi

  echo "ERROR: Missing Sonar configuration."
  echo "Create and complete one of these files with SONAR_HOST_URL and SONAR_TOKEN:"
  echo "  - $preferred_env"
  echo "  - $CLASSIC_ROOT/.env"
  echo
  echo "Example:"
  echo "SONAR_HOST_URL=https://sonar.example.com"
  echo "SONAR_TOKEN=your_token_here"
  exit 1
}

ensure_clean_worktree() {
  [[ "$ALLOW_DIRTY" == "true" ]] && return 0

  local status_output
  status_output="$(git status --porcelain --untracked-files=all)"

  if [[ -z "$status_output" ]]; then
    return 0
  fi

  local tmp_status tmp_filtered
  tmp_status="$(mktemp)"
  tmp_filtered="$(mktemp)"
  printf '%s\n' "$status_output" > "$tmp_status"

  python3 - "$REPORT_DIR" "$tmp_status" "$tmp_filtered" <<'PYEOF'
import sys
from pathlib import Path

report_dir = sys.argv[1].rstrip('/')
status_file = Path(sys.argv[2])
filtered_file = Path(sys.argv[3])
ignore_paths = {'.scannerwork', '.env', report_dir}

with status_file.open() as src, filtered_file.open('w') as out:
    for raw in src:
        line = raw.rstrip('\n')
        if not line:
            continue
        path = line[3:] if len(line) > 3 else line
        path = path.strip()
        if path in ignore_paths:
            continue
        if any(path == p or path.startswith(p + '/') for p in ignore_paths):
            continue
        out.write(line + '\n')
PYEOF

  if [[ -s "$tmp_filtered" ]]; then
    echo "ERROR: run-sonar.sh requires a clean working tree for precise PR validation."
    echo "Uncommitted changes:"
    cat "$tmp_filtered"
    rm -f "$tmp_status" "$tmp_filtered"
    echo "Commit or stash changes first, or rerun with --allow-dirty for exploratory use."
    exit 1
  fi

  rm -f "$tmp_status" "$tmp_filtered"
}

load_env_file "$SCRIPT_DIR/.env"
load_env_file "$CLASSIC_ROOT/.env"

if [[ -z "${SONAR_HOST_URL:-}" || -z "${SONAR_TOKEN:-}" ]]; then
  prompt_for_sonar_env_file
fi

if [[ -z "${SONAR_HOST_URL:-}" ]]; then
  SONAR_HOST_URL="$(load_gradle_property sonarHostUrl)"
fi
if [[ -z "${SONAR_TOKEN:-}" ]]; then
  SONAR_TOKEN="$(load_gradle_property sonarToken)"
fi

if [[ -z "${SONAR_HOST_URL:-}" || -z "${SONAR_TOKEN:-}" ]]; then
  echo "ERROR: Sonar configuration is still incomplete after reading .env and gradle.properties."
  exit 1
fi

SONAR_HOST_URL="${SONAR_HOST_URL%/}"
export SONAR_HOST_URL SONAR_TOKEN REPORT_DIR BASE_REF CHANGED_ONLY

# ── Step 0: Clean report directory and enforce clean tree ──────────────────
echo "==> Cleaning report directory: $REPORT_DIR"
rm -rf "$REPORT_DIR"
mkdir -p "$REPORT_DIR"
rm -rf .scannerwork
ensure_clean_worktree

# ── Step 1: Run scanner ────────────────────────────────────────────
echo "==> Running sonar-scanner..."
sonar-scanner \
  -Dsonar.host.url="$SONAR_HOST_URL" \
  -Dsonar.token="$SONAR_TOKEN"

# ── Step 2: Get task ID from report-task.txt ───────────────────────
REPORT_TASK_FILE=".scannerwork/report-task.txt"
if [[ ! -f "$REPORT_TASK_FILE" ]]; then
  echo "ERROR: $REPORT_TASK_FILE not found. Scanner may have failed."
  exit 1
fi

CE_TASK_ID=$(grep "ceTaskId=" "$REPORT_TASK_FILE" | cut -d'=' -f2)
echo "==> Analysis task ID: $CE_TASK_ID"

# ── Step 3: Poll until analysis completes ──────────────────────────
echo "==> Waiting for analysis to complete..."
elapsed=0
while (( elapsed < MAX_WAIT )); do
  TASK_STATUS=$(curl -s -u "$SONAR_TOKEN:" \
    "$SONAR_HOST_URL/api/ce/task?id=$CE_TASK_ID" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['task']['status'])")

  echo "    Status: $TASK_STATUS (${elapsed}s elapsed)"

  case "$TASK_STATUS" in
    SUCCESS)
      echo "==> Analysis completed successfully."
      break
      ;;
    FAILED|CANCELED)
      echo "ERROR: Analysis $TASK_STATUS."
      exit 1
      ;;
    *)
      sleep "$POLL_INTERVAL"
      elapsed=$((elapsed + POLL_INTERVAL))
      ;;
  esac
done

if (( elapsed >= MAX_WAIT )); then
  echo "ERROR: Timed out after ${MAX_WAIT}s waiting for analysis."
  exit 1
fi

# ── Step 4: Download reports ───────────────────────────────────────
echo "==> Downloading reports..."

# Issues (paginated, saved to a single file)
python3 - <<'PYEOF'
import json, os, urllib.request, sys

base = os.environ["SONAR_HOST_URL"]
token = os.environ["SONAR_TOKEN"]
report_dir = os.environ.get("REPORT_DIR", "sonar-reports")
project = "etendosoftware_com.etendoerp.go_4f22c2cf-5ab2-4734-8244-f9eb74bbbb7a"

import base64 as b64
credentials = b64.b64encode(f"{token}:".encode()).decode()

def api_get(path):
    req = urllib.request.Request(f"{base}{path}")
    req.add_header("Authorization", f"Basic {credentials}")
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        print(f"    WARNING: {e.code} on {path}", file=sys.stderr)
        # Try with just the token in the URL as fallback (older SonarQube)
        try:
            fallback_url = f"{base}{path}{'&' if '?' in path else '?'}token={token}"
            req2 = urllib.request.Request(fallback_url)
            with urllib.request.urlopen(req2) as resp:
                return json.loads(resp.read())
        except Exception:
            print(f"    SKIPPED: Could not fetch {path.split('?')[0]} (403 - insufficient permissions)", file=sys.stderr)
            return None

# Paginated issues
all_issues = []
page = 1
while True:
    data = api_get(f"/api/issues/search?componentKeys={project}&ps=500&p={page}&statuses=OPEN,CONFIRMED,REOPENED")
    if data is None:
        break
    issues = data.get("issues", [])
    all_issues.extend(issues)
    if len(issues) < 500:
        break
    page += 1

result = {"total": len(all_issues), "issues": all_issues}
with open(f"{report_dir}/sonar-issues.json", "w") as f:
    json.dump(result, f, indent=2)
print(f"    Saved: {report_dir}/sonar-issues.json ({len(all_issues)} issues)")

# Group issues by file and save per-file reports
prefix = project + ":"
files_with_issues_dir = f"{report_dir}/files"
os.makedirs(files_with_issues_dir, exist_ok=True)

by_file = {}
for issue in all_issues:
    comp = issue.get("component", "")
    filepath = comp[len(prefix):] if comp.startswith(prefix) else comp
    by_file.setdefault(filepath, []).append({
        "rule": issue.get("rule", ""),
        "severity": issue.get("severity", ""),
        "type": issue.get("type", ""),
        "message": issue.get("message", ""),
        "line": issue.get("line"),
    })

for filepath, issues in by_file.items():
    # Create safe filename
    safe_filename = filepath.replace("/", "_").replace("\\", "_") + ".json"
    with open(f"{files_with_issues_dir}/{safe_filename}", "w") as f:
        json.dump({"file": filepath, "count": len(issues), "issues": sorted(issues, key=lambda x: x.get("line") or 0)}, f, indent=2)

report = {f: {"count": len(issues), "issues": sorted(issues, key=lambda x: x.get("line") or 0)} for f, issues in sorted(by_file.items())}
with open(f"{report_dir}/sonar-issues-by-file.json", "w") as f:
    json.dump(report, f, indent=2)
print(f"    Saved: {report_dir}/sonar-issues-by-file.json ({len(report)} files)")
print(f"    Saved: {len(by_file)} individual file reports in {files_with_issues_dir}/")

# Quality gate
qg = api_get(f"/api/qualitygates/project_status?projectKey={project}")
if qg:
    with open(f"{report_dir}/sonar-quality-gate.json", "w") as f:
        json.dump(qg, f, indent=2)
    print(f"    Saved: {report_dir}/sonar-quality-gate.json")

# Measures
measures = api_get(f"/api/measures/component?component={project}&metricKeys=bugs,vulnerabilities,code_smells,coverage,duplicated_lines_density,ncloc,security_hotspots,reliability_rating,security_rating,sqale_rating")
if measures:
    with open(f"{report_dir}/sonar-measures.json", "w") as f:
        json.dump(measures, f, indent=2)
    print(f"    Saved: {report_dir}/sonar-measures.json")

# ── Summary ──
print()
print("=== SONAR ANALYSIS SUMMARY ===")

if qg:
    status = qg.get("projectStatus", {}).get("status", "UNKNOWN")
    print(f"Quality Gate: {status}")
    print()

if measures:
    rating_map = {"1.0": "A", "2.0": "B", "3.0": "C", "4.0": "D", "5.0": "E"}
    for m in measures.get("component", {}).get("measures", []):
        name = m["metric"].replace("_", " ").title()
        val = rating_map.get(m["value"], m["value"]) if m["metric"].endswith("_rating") else m["value"]
        print(f"  {name}: {val}")
else:
    print("  (measures not available - check token permissions)")

print(f"\nOpen issues: {len(all_issues)}")

by_type = {}
by_sev = {}
for i in all_issues:
    t = i.get("type", "UNKNOWN")
    s = i.get("severity", "UNKNOWN")
    by_type[t] = by_type.get(t, 0) + 1
    by_sev[s] = by_sev.get(s, 0) + 1

for label, counts in [("By type", by_type), ("By severity", by_sev)]:
    print(f"\n{label}:")
    for k, v in sorted(counts.items()):
        print(f"  {k}: {v}")
PYEOF

echo ""
echo "Dashboard: $SONAR_HOST_URL/dashboard?id=$PROJECT_KEY"
echo "Reports saved in: $REPORT_DIR/"

if [[ "$CHANGED_ONLY" == "true" ]]; then
  if [[ -z "$BASE_REF" ]]; then
    echo "ERROR: --changed-only requires --base-ref <ref>"
    exit 1
  fi

  echo "==> Filtering issues to changed files against: $BASE_REF"
  git diff --name-only "$BASE_REF"...HEAD > "$REPORT_DIR/changed-files.txt"

  python3 - <<'PYEOF'
import json, os
from pathlib import Path

report_dir = Path(os.environ.get("REPORT_DIR", "sonar-reports"))
changed = {line.strip() for line in (report_dir / "changed-files.txt").read_text().splitlines() if line.strip()}
issues_by_file = json.loads((report_dir / "sonar-issues-by-file.json").read_text())
issues = json.loads((report_dir / "sonar-issues.json").read_text())

filtered_by_file = {path: data for path, data in issues_by_file.items() if path in changed}
filtered_issues = [
    issue for issue in issues.get("issues", [])
    if issue.get("component", "").split(":", 1)[-1] in changed
]

(report_dir / "sonar-issues-pr-only.json").write_text(json.dumps({
    "baseRef": os.environ.get("BASE_REF", ""),
    "changedFiles": sorted(changed),
    "total": len(filtered_issues),
    "issues": filtered_issues
}, indent=2))

(report_dir / "sonar-issues-by-file-pr-only.json").write_text(
    json.dumps(filtered_by_file, indent=2)
 )

print(f"    Saved: {report_dir}/sonar-issues-pr-only.json ({len(filtered_issues)} issues)")
print(f"    Saved: {report_dir}/sonar-issues-by-file-pr-only.json ({len(filtered_by_file)} files)")
PYEOF

  echo "PR-only reports saved in: $REPORT_DIR/"
fi
