#!/usr/bin/env bash
set -euo pipefail

# ── Config ──────────────────────────────────────────────────────────
PROJECT_KEY="etendosoftware_com.etendoerp.go_4f22c2cf-5ab2-4734-8244-f9eb74bbbb7a"
POLL_INTERVAL=5      # seconds between polls
MAX_WAIT=300         # max seconds to wait for analysis
REPORT_DIR="sonar-reports"

: "${SONAR_HOST_URL:?Set SONAR_HOST_URL in your environment}"
: "${SONAR_TOKEN:?Set SONAR_TOKEN in your environment}"

SONAR_HOST_URL="${SONAR_HOST_URL%/}"

# ── Step 0: Clean report directory ─────────────────────────────────
echo "==> Cleaning report directory: $REPORT_DIR"
rm -rf "$REPORT_DIR"
mkdir -p "$REPORT_DIR"

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
