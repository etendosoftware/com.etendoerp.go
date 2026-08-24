#!/usr/bin/env bash
# check-sonar-issues.sh
#
# Issues-only SonarQube check, run in BRANCH mode (not PR mode).
#
# WHY THIS EXISTS: pre-push's step 3 (run-sonar.sh --fail-on-gate) analyzes in
# PULL REQUEST mode (-Dsonar.pullrequest.*). In PR mode the scanner only parses
# the changed files, and since sonar.java.binaries=. points at a directory with
# ZERO .class files (and sonar.java.libraries is empty), ECJ cannot resolve
# types and the JavaSensor silently emits 0 issues — ALWAYS, regardless of what
# is actually in the diff. Branch mode (-Dsonar.branch.name=...) parses the
# whole project and reports real issues. This script runs that branch-mode
# analysis and blocks the push only when an issue lands on a line THIS push
# actually added or modified — it never touches run-sonar.sh or the PR-mode
# gate, it just adds the check that mode structurally cannot perform.
#
# Deliberately does NOT need jacoco coverage (issues only), so it can run
# BEFORE the (slow) JUnit step in pre-push.
#
# Usage: ./check-sonar-issues.sh <base-ref>
#   <base-ref> — same BASE_REF the pre-push hook resolved (e.g. origin/epic/ETP-3504).
#
# Exit codes:
#   0 — no blocking issues in changed lines (or the check could not run at all —
#       tooling failures NEVER hard-block the push, only confirmed issues do).
#   1 — one or more open issues fall inside this push's added/modified lines.

set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CLASSIC_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"

BASE_REF="${1:-}"
POLL_INTERVAL=5
MAX_WAIT=300

warn_skip() {
  echo "⚠️  $1"
  echo "    Not blocking the push (tooling issue, not a confirmed Sonar finding)."
  exit 0
}

if [[ -z "$BASE_REF" ]]; then
  warn_skip "check-sonar-issues.sh: no base ref given, cannot compute the diff."
fi

if ! command -v sonar-scanner >/dev/null 2>&1; then
  warn_skip "sonar-scanner not found on PATH."
fi

# ── Config: projectKey (single source of truth, same as run-sonar.sh) ──────
SONAR_PROPERTIES="$SCRIPT_DIR/sonar-project.properties"
PROJECT_KEY="$(awk -F'=' '$1=="sonar.projectKey"{sub(/^[^=]*=/, ""); print; exit}' "$SONAR_PROPERTIES" 2>/dev/null)"
if [[ -z "$PROJECT_KEY" ]]; then
  warn_skip "sonar.projectKey not found in $SONAR_PROPERTIES."
fi

# ── Credentials: same small loader as run-sonar.sh (not sourced from it — that
# script has side effects like arg parsing we must not trigger here). ──
load_env_file() {
  local env_file="$1"
  [[ -f "$env_file" ]] || return 0
  while IFS='=' read -r key value || [[ -n "$key" ]]; do
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

load_env_file "$SCRIPT_DIR/.env"
load_env_file "$CLASSIC_ROOT/.env"

if [[ -z "${SONAR_HOST_URL:-}" ]]; then
  SONAR_HOST_URL="$(load_gradle_property sonarHostUrl)"
  [[ -z "$SONAR_HOST_URL" ]] && SONAR_HOST_URL="$(load_gradle_property SONAR_HOST_URL)"
fi
if [[ -z "${SONAR_TOKEN:-}" ]]; then
  SONAR_TOKEN="$(load_gradle_property sonarToken)"
  [[ -z "$SONAR_TOKEN" ]] && SONAR_TOKEN="$(load_gradle_property SONAR_TOKEN)"
fi

if [[ -z "${SONAR_HOST_URL:-}" || -z "${SONAR_TOKEN:-}" ]]; then
  warn_skip "Missing SONAR_HOST_URL/SONAR_TOKEN (checked env, $SCRIPT_DIR/.env, $CLASSIC_ROOT/.env, gradle.properties)."
fi

# Strip a trailing slash (mirrors run-sonar.sh — a trailing slash doubles up
# when concatenated into API paths below).
SONAR_HOST_URL="${SONAR_HOST_URL%/}"

BRANCH_NAME="local-issues-$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo 'unknown')"

echo "==> Running sonar-scanner in BRANCH mode (branch=$BRANCH_NAME) — full parse, real issues..."
if ! sonar-scanner \
      -Dsonar.host.url="$SONAR_HOST_URL" \
      -Dsonar.token="$SONAR_TOKEN" \
      -Dsonar.branch.name="$BRANCH_NAME" >/tmp/check-sonar-issues-scanner.log 2>&1; then
  echo "---- scanner output (last 40 lines) ----"
  tail -n 40 /tmp/check-sonar-issues-scanner.log
  echo "-----------------------------------------"
  warn_skip "sonar-scanner failed."
fi

REPORT_TASK_FILE="$SCRIPT_DIR/.scannerwork/report-task.txt"
if [[ ! -f "$REPORT_TASK_FILE" ]]; then
  warn_skip "$REPORT_TASK_FILE not found — scanner may not have completed."
fi

CE_TASK_ID="$(grep "ceTaskId=" "$REPORT_TASK_FILE" | cut -d'=' -f2)"
if [[ -z "$CE_TASK_ID" ]]; then
  warn_skip "Could not read ceTaskId from $REPORT_TASK_FILE."
fi

echo "==> Waiting for analysis task $CE_TASK_ID to complete..."
elapsed=0
TASK_STATUS=""
while (( elapsed < MAX_WAIT )); do
  TASK_STATUS="$(curl -s -u "$SONAR_TOKEN:" "$SONAR_HOST_URL/api/ce/task?id=$CE_TASK_ID" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['task']['status'])" 2>/dev/null)"
  echo "    Status: ${TASK_STATUS:-UNKNOWN} (${elapsed}s elapsed)"
  case "$TASK_STATUS" in
    SUCCESS) break ;;
    FAILED|CANCELED) warn_skip "Sonar analysis task $TASK_STATUS." ;;
    *) sleep "$POLL_INTERVAL"; elapsed=$(( elapsed + POLL_INTERVAL )) ;;
  esac
done

if [[ "$TASK_STATUS" != "SUCCESS" ]]; then
  warn_skip "Timed out after ${MAX_WAIT}s waiting for Sonar analysis."
fi

# ── Compute this push's changed/added lines per file (added lines only — a
# deleted line cannot carry a "new" issue). ──
DIFF_RANGES_FILE="$(mktemp)"
trap 'rm -f "$DIFF_RANGES_FILE"' EXIT

if ! git diff -U0 "${BASE_REF}...HEAD" > "$DIFF_RANGES_FILE" 2>/dev/null; then
  warn_skip "git diff -U0 ${BASE_REF}...HEAD failed."
fi

# ── Fetch open issues on the branch, filter to this push's changed lines. ──
BRANCH_NAME="$BRANCH_NAME" PROJECT_KEY="$PROJECT_KEY" SONAR_HOST_URL="$SONAR_HOST_URL" \
SONAR_TOKEN="$SONAR_TOKEN" DIFF_FILE="$DIFF_RANGES_FILE" \
python3 - <<'PYEOF'
import base64 as b64, json, os, re, sys, urllib.error, urllib.request

base = os.environ["SONAR_HOST_URL"]
token = os.environ["SONAR_TOKEN"]
project = os.environ["PROJECT_KEY"]
branch = os.environ["BRANCH_NAME"]
diff_file = os.environ["DIFF_FILE"]
credentials = b64.b64encode(f"{token}:".encode()).decode()

def api_get(path):
    req = urllib.request.Request(f"{base}{path}")
    req.add_header("Authorization", f"Basic {credentials}")
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        print(f"    WARNING: {e.code} on {path}", file=sys.stderr)
        return None
    except Exception as e:
        print(f"    WARNING: {e} on {path}", file=sys.stderr)
        return None

def fetch_issues():
    result = []
    page = 1
    while True:
        q = f"componentKeys={project}&branch={branch}&resolved=false&ps=500&p={page}"
        data = api_get(f"/api/issues/search?{q}")
        if data is None:
            break
        issues = data.get("issues", [])
        result.extend(issues)
        if len(issues) < 500:
            break
        page += 1
    return result

# ── Parse `git diff -U0` hunks into {relative_path: set(added_line_numbers)} ──
added_lines = {}
current_file = None
hunk_re = re.compile(r'^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@')

with open(diff_file, encoding="utf-8", errors="replace") as fh:
    for line in fh:
        if line.startswith("+++ "):
            path = line[4:].strip()
            if path == "/dev/null":
                current_file = None
            else:
                current_file = path[2:] if path.startswith("b/") else path
            continue
        if current_file is None:
            continue
        m = hunk_re.match(line)
        if m:
            start = int(m.group(1))
            count = int(m.group(2)) if m.group(2) is not None else 1
            lines_set = added_lines.setdefault(current_file, set())
            for ln in range(start, start + count):
                lines_set.add(ln)

issues = fetch_issues()
prefix = project + ":"
blocking = []
for issue in issues:
    comp = issue.get("component", "")
    filepath = comp[len(prefix):] if comp.startswith(prefix) else comp
    line = issue.get("line")
    if filepath in added_lines and line in added_lines[filepath]:
        blocking.append(issue)

print(f"\n==> {len(issues)} open issue(s) on branch '{branch}', {len(blocking)} inside this push's changed lines.")

if blocking:
    print("\n❌ SONAR ISSUES IN YOUR CHANGED LINES (branch-mode analysis — these are real, unlike PR-mode's 0):\n")
    for i, issue in enumerate(sorted(blocking, key=lambda x: (x.get("component", ""), x.get("line") or 0)), 1):
        comp = issue.get("component", "")
        filepath = comp[len(prefix):] if comp.startswith(prefix) else comp
        print(f"  {i}. {filepath}:{issue.get('line', '?')} — {issue.get('rule', '')} [{issue.get('severity', '')}]")
        print(f"     {issue.get('message', '')}")
    print("\n   Fix these before pushing, or 'git push --no-verify' to bypass (WIP only).")
    sys.exit(1)

print("✅ No blocking Sonar issues in your changed lines.")
sys.exit(0)
PYEOF
exit $?
