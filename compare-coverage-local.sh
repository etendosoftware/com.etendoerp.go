#!/usr/bin/env bash
# compare-coverage-local.sh
#
# Local replica of the Jenkins "Compare Coverage Results" stage
# (etendo-go/Jenkinsfile + sonarUtils.groovy).
#
# It answers the same question the pipeline does, BEFORE you push:
#   "Is this branch's SonarQube coverage lower than the epic's?"
#
# How it mirrors the pipeline:
#   - Current coverage  : computed from the SAME JaCoCo aggregate report Jenkins
#                         uploads (build/reports/jacoco/jacocoRootReport.xml),
#                         scoped to the module's `sonar.sources` (src/**.java),
#                         using Sonar's exact coverage formula:
#                           (covered_lines + covered_conditions)
#                           / (lines_to_cover + conditions_to_cover) * 100
#   - Epic baseline     : queried live from SonarQube for `epic/ETP-3504`,
#                         identical to sonarUtils.getCoverageWithRetry().
#   - Pass/fail rule    : strict `current < epic` → FAIL (same as Jenkins).
#
# Caveat: the CURRENT value is computed locally (not by SonarQube), so it is a
# very close approximation — it can differ from Sonar's published number by
# rounding or Sonar-internal rules. The epic baseline IS Sonar's real number.
#
# Requires: SONAR_TOKEN and SONAR_HOST_URL exported (same as run-sonar.sh).
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$SCRIPT_DIR"
CORE_DIR="$(cd -- "$MODULE_DIR/../.." && pwd)"
REPORT="$CORE_DIR/build/reports/jacoco/jacocoRootReport/jacocoRootReport.xml"

# Defaults mirror Jenkinsfile:418
COMPARE_BRANCH="epic/ETP-3504"
RUN_TESTS=1
FAIL_ON_DROP=1

usage() {
  cat <<EOF
Usage: ./compare-coverage-local.sh [options]

  --report-only     Reuse the existing JaCoCo report (skip gradle test+report).
  --branch <ref>    Reference branch to compare against (default: $COMPARE_BRANCH).
  --no-fail         Report the drop but exit 0 (mirrors Jenkins UNSTABLE, non-blocking).
  -h, --help        Show this help.

Env: SONAR_TOKEN, SONAR_HOST_URL must be exported.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --report-only) RUN_TESTS=0; shift ;;
    --branch) COMPARE_BRANCH="${2:?--branch requires a value}"; shift 2 ;;
    --no-fail) FAIL_ON_DROP=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

if [[ -z "${SONAR_TOKEN:-}" || -z "${SONAR_HOST_URL:-}" ]]; then
  echo "❌ ERROR: SONAR_TOKEN and SONAR_HOST_URL must be exported." >&2
  echo "   Add them to your shell profile (see CLAUDE.md / run-sonar.sh)." >&2
  exit 1
fi

PROJECT_KEY="$(grep '^sonar.projectKey=' "$MODULE_DIR/sonar-project.properties" | cut -d= -f2-)"
if [[ -z "$PROJECT_KEY" ]]; then
  echo "❌ ERROR: sonar.projectKey not found in sonar-project.properties" >&2
  exit 1
fi

# ── Step 1: (re)generate the JaCoCo aggregate report, same as Jenkins ──
if [[ "$RUN_TESTS" == "1" ]]; then
  echo "==> Running tests + jacocoRootReport (use --report-only to skip)..."
  # Two invocations, same order as Jenkins, so .exec data exists before the report.
  ( cd "$CORE_DIR" && ./gradlew test --tests "com.etendoerp.go.*" )
  ( cd "$CORE_DIR" && ./gradlew jacocoRootReport --info )
else
  echo "==> Reusing existing report (--report-only)."
fi

if [[ ! -f "$REPORT" ]]; then
  echo "❌ ERROR: JaCoCo report not found at $REPORT" >&2
  echo "   Run without --report-only to generate it." >&2
  exit 1
fi

# ── Step 2: compute current coverage + fetch epic baseline + compare ──
PROJECT_KEY="$PROJECT_KEY" COMPARE_BRANCH="$COMPARE_BRANCH" REPORT="$REPORT" \
MODULE_SRC="$MODULE_DIR/src" FAIL_ON_DROP="$FAIL_ON_DROP" \
SONAR_TOKEN="$SONAR_TOKEN" SONAR_HOST_URL="$SONAR_HOST_URL" \
python3 - <<'PYEOF'
import os, sys, json, urllib.parse, urllib.request
import xml.etree.ElementTree as ET

report = os.environ["REPORT"]
module_src = os.environ["MODULE_SRC"]
project_key = os.environ["PROJECT_KEY"]
compare_branch = os.environ["COMPARE_BRANCH"]
sonar_url = os.environ["SONAR_HOST_URL"].rstrip("/")
sonar_token = os.environ["SONAR_TOKEN"]
fail_on_drop = os.environ["FAIL_ON_DROP"] == "1"

# Set of package-relative java paths Sonar analyzes (sonar.sources=src).
src_files = set()
for dirpath, _, files in os.walk(module_src):
    for f in files:
        if f.endswith(".java"):
            rel = os.path.relpath(os.path.join(dirpath, f), module_src)
            src_files.add(rel.replace(os.sep, "/"))

if not src_files:
    print(f"❌ No .java files under {module_src}", file=sys.stderr)
    sys.exit(1)

# Stream the (large) JaCoCo XML, summing LINE/BRANCH counters for matched files.
lines_total = lines_covered = cond_total = cond_covered = 0
matched = 0
context = ET.iterparse(report, events=("end",))
for event, elem in context:
    if elem.tag != "package":
        continue
    pkg = elem.get("name", "")  # e.g. com/etendoerp/go/rest
    for sf in elem.findall("sourcefile"):
        rel = f"{pkg}/{sf.get('name')}"
        if rel not in src_files:
            continue
        matched += 1
        for c in sf.findall("counter"):
            t = c.get("type")
            cov = int(c.get("covered", 0))
            mis = int(c.get("missed", 0))
            if t == "LINE":
                lines_covered += cov
                lines_total += cov + mis
            elif t == "BRANCH":
                cond_covered += cov
                cond_total += cov + mis
    elem.clear()  # free memory for the 30MB+ report

denom = lines_total + cond_total
if denom == 0:
    print("❌ No coverage data matched the module sources in the JaCoCo report.", file=sys.stderr)
    print(f"   (matched {matched} source files; is the report stale or tests not run?)", file=sys.stderr)
    sys.exit(1)

current = (lines_covered + cond_covered) / denom * 100.0

# Fetch epic baseline from Sonar (same API as sonarUtils.groovy).
qs = urllib.parse.urlencode({
    "component": project_key,
    "branch": compare_branch,
    "metricKeys": "coverage",
})
req = urllib.request.Request(f"{sonar_url}/api/measures/component?{qs}")
import base64
req.add_header("Authorization", "Basic " + base64.b64encode(f"{sonar_token}:".encode()).decode())
try:
    with urllib.request.urlopen(req, timeout=30) as r:
        data = json.load(r)
except Exception as e:
    print(f"❌ Failed to query Sonar for '{compare_branch}': {e}", file=sys.stderr)
    sys.exit(1)

measures = (data.get("component") or {}).get("measures", [])
epic_val = next((m.get("value") for m in measures if m.get("metric") == "coverage"), None)
# sonarUtils returns 0.0 when the reference has no coverage info.
epic = float(epic_val) if epic_val not in (None, "", "0", "0.0") else 0.0

print()
print("=== LOCAL COVERAGE COMPARISON (mirrors Jenkins 'Compare Coverage Results') ===")
print(f"  Project           : {project_key}")
print(f"  Source files       : {matched} matched (sonar.sources=src)")
print(f"  Lines              : {lines_covered}/{lines_total} covered")
print(f"  Conditions         : {cond_covered}/{cond_total} covered")
print(f"  Current coverage   : {current:.2f}%  (computed locally, Sonar formula)")
print(f"  {compare_branch} : {epic:.2f}%  (live from SonarQube)")
delta = current - epic
print(f"  Delta              : {delta:+.2f}%")
print()

if current < epic:
    print(f"❌ FAIL: coverage ({current:.2f}%) is LOWER than {compare_branch} ({epic:.2f}%).")
    print("   Jenkins would mark this build UNSTABLE.")
    sys.exit(1 if fail_on_drop else 0)
else:
    print(f"✅ OK: coverage ({current:.2f}%) is >= {compare_branch} ({epic:.2f}%).")
    sys.exit(0)
PYEOF
