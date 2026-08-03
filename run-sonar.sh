#!/usr/bin/env bash
set -euo pipefail

# ── Config ──────────────────────────────────────────────────────────
POLL_INTERVAL=5      # seconds between polls
MAX_WAIT=300         # max seconds to wait for analysis
REPORT_DIR="sonar-reports"
BASE_REF=""
CHANGED_ONLY="true"
ALLOW_DIRTY="false"
FAIL_ON_GATE="false"
COMPARE_COVERAGE="false"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CLASSIC_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"

# projectKey is declared in sonar-project.properties (single source of truth);
# read it here instead of duplicating the value across the script.
SONAR_PROPERTIES="$SCRIPT_DIR/sonar-project.properties"
PROJECT_KEY="$(awk -F'=' '$1=="sonar.projectKey"{sub(/^[^=]*=/, ""); print; exit}' "$SONAR_PROPERTIES")"
if [[ -z "$PROJECT_KEY" ]]; then
  echo "ERROR: sonar.projectKey not found in $SONAR_PROPERTIES"
  exit 1
fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-ref)
      if [[ -z "${2:-}" ]]; then
        echo "ERROR: --base-ref requires a value"
        exit 1
      fi
      BASE_REF="$2"
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
    --all-issues)
      CHANGED_ONLY="false"
      shift
      ;;
    --fail-on-gate)
      # Exit non-zero when the SonarQube Quality Gate is in ERROR (mirrors the
      # server-side gate CI enforces on the PR). Used by the pre-push hook to
      # block pushes that would fail the gate (e.g. new issues > 0).
      FAIL_ON_GATE="true"
      shift
      ;;
    --compare-coverage)
      # Block when this branch's new-code coverage would drop overall coverage
      # below the base branch (minus COVERAGE_TOLERANCE), or overall is under
      # COVERAGE_MINIMUM. Mirrors Jenkins' sonarUtils.compareCoverage.
      COMPARE_COVERAGE="true"
      shift
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
ignore_paths = {
    '.scannerwork',
    '.env',
    'coverage',
    report_dir,
    'domain-boundary-report.json',
    'domain-boundary-report.md',
    'review-report.json',
    'review-report.md'
}

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

prompt_for_base_ref() {
  [[ "$CHANGED_ONLY" == "true" ]] || return 0

  if [[ -n "$BASE_REF" ]]; then
    return 0
  fi

  if [[ -t 0 ]]; then
    echo "==> PR validation mode is enabled by default."
    echo "Enter the PR base commit or target ref used by Sonar Cloud (examples: origin/main, origin/epic/ETP-3504, abc1234)."
    read -r -p "Base commit/ref: " BASE_REF
    return 0
  fi

  echo "ERROR: PR validation mode requires a base commit/ref."
  echo "Run with --base-ref <commit-or-ref>, or use --all-issues for full-project reports."
  exit 1
}

validate_base_ref() {
  [[ "$CHANGED_ONLY" == "true" ]] || return 0

  if [[ -z "$BASE_REF" ]]; then
    echo "ERROR: Base commit/ref cannot be empty in PR validation mode."
    exit 1
  fi

  if ! git rev-parse --verify --quiet "$BASE_REF^{commit}" >/dev/null; then
    echo "ERROR: Base commit/ref not found: $BASE_REF"
    echo "Fetch the target branch or pass a valid commit SHA."
    exit 1
  fi
}

load_env_file "$SCRIPT_DIR/.env"
load_env_file "$CLASSIC_ROOT/.env"

# Fall back to gradle.properties BEFORE prompting for a .env file; otherwise
# credentials configured there are unreachable (the prompt exits when stdin is
# not a TTY, e.g. inside the pre-push hook).
if [[ -z "${SONAR_HOST_URL:-}" ]]; then
  SONAR_HOST_URL="$(load_gradle_property sonarHostUrl)"
fi
if [[ -z "${SONAR_TOKEN:-}" ]]; then
  SONAR_TOKEN="$(load_gradle_property sonarToken)"
fi

if [[ -z "${SONAR_HOST_URL:-}" || -z "${SONAR_TOKEN:-}" ]]; then
  prompt_for_sonar_env_file
fi

if [[ -z "${SONAR_HOST_URL:-}" || -z "${SONAR_TOKEN:-}" ]]; then
  echo "ERROR: Sonar configuration is still incomplete after reading .env and gradle.properties."
  exit 1
fi

SONAR_HOST_URL="${SONAR_HOST_URL%/}"
export SONAR_HOST_URL SONAR_TOKEN REPORT_DIR BASE_REF CHANGED_ONLY PROJECT_KEY

prompt_for_base_ref
validate_base_ref

# ── Step 0: Clean report directory and enforce clean tree ──────────────────
echo "==> Cleaning report directory: $REPORT_DIR"
rm -rf "$REPORT_DIR"
mkdir -p "$REPORT_DIR"
rm -rf .scannerwork
ensure_clean_worktree

# ── Step 1: Run scanner ────────────────────────────────────────────
# In PR-validation mode (--base-ref) analyze in PULL REQUEST mode so the server
# computes "new code = diff vs base" exactly like CI's PR gate. This is ephemeral
# (SonarQube auto-purges PR analyses) and never writes to the main branch — a
# plain `sonar-scanner` with no PR/branch params would otherwise pollute main.
# PR mode needs only "Execute Analysis" (which a scan already requires); it does
# NOT need the admin-only new-code-period config that a suffixed branch would.
SCANNER_ARGS=(
  -Dsonar.host.url="$SONAR_HOST_URL"
  -Dsonar.token="$SONAR_TOKEN"
)
SONAR_PR_KEY=""
if [[ "$CHANGED_ONLY" == "true" && -n "$BASE_REF" ]]; then
  PR_SRC_BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo 'HEAD')"
  PR_BASE_BRANCH="${BASE_REF#origin/}"   # Sonar wants a branch NAME, not origin/<name>
  SONAR_PR_KEY="local-${PR_SRC_BRANCH}"  # stable per branch → repeated pushes update, not pile up
  SCANNER_ARGS+=(
    -Dsonar.pullrequest.key="$SONAR_PR_KEY"
    -Dsonar.pullrequest.branch="$PR_SRC_BRANCH"
    -Dsonar.pullrequest.base="$PR_BASE_BRANCH"
  )
  echo "==> Running sonar-scanner (PR mode: key=$SONAR_PR_KEY branch=$PR_SRC_BRANCH base=$PR_BASE_BRANCH)..."
else
  echo "==> Running sonar-scanner..."
fi
export SONAR_PR_KEY
sonar-scanner "${SCANNER_ARGS[@]}"

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
import json, os, urllib.parse, urllib.request, sys

base = os.environ["SONAR_HOST_URL"]
token = os.environ["SONAR_TOKEN"]
report_dir = os.environ.get("REPORT_DIR", "sonar-reports")
project = os.environ["PROJECT_KEY"]

# In PR mode every read must be scoped to the PR, or it reads the main branch
# instead (the bug this replaces). Empty in --all-issues mode → reads default branch.
pr_key = os.environ.get("SONAR_PR_KEY", "")
PR_Q = f"&pullRequest={pr_key}" if pr_key else ""

import base64 as b64
credentials = b64.b64encode(f"{token}:".encode()).decode()

# Set when /api/hotspots/* returns 403 — the token can read Issues but lacks the
# "Browse Security Hotspots" project permission. Distinguishes a permission wall
# (so the gate block can fall back to a local heuristic) from a genuine 0-hotspot
# result. Persisted into the hotspot report files for the separate gate process.
HOTSPOTS_FORBIDDEN = {"flag": False}

def api_get(path):
    req = urllib.request.Request(f"{base}{path}")
    req.add_header("Authorization", f"Basic {credentials}")
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        if e.code == 403 and "/api/hotspots/" in path:
            HOTSPOTS_FORBIDDEN["flag"] = True
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

def fetch_issues(extra_query=""):
    issues_result = []
    page = 1
    while True:
        query = f"componentKeys={project}&ps=500&p={page}&statuses=OPEN,CONFIRMED,REOPENED{PR_Q}"
        if extra_query:
            query = f"{query}&{extra_query}"
        data = api_get(f"/api/issues/search?{query}")
        if data is None:
            break
        issues = data.get("issues", [])
        issues_result.extend(issues)
        if len(issues) < 500:
            break
        page += 1
    return issues_result

def issue_summary(issue):
    return {
        "rule": issue.get("rule", ""),
        "severity": issue.get("severity", ""),
        "type": issue.get("type", ""),
        "message": issue.get("message", ""),
        "line": issue.get("line"),
    }

def group_by_file(issues):
    by_file = {}
    for issue in issues:
        comp = issue.get("component", "")
        filepath = comp[len(prefix):] if comp.startswith(prefix) else comp
        by_file.setdefault(filepath, []).append(issue_summary(issue))
    return by_file

def write_issue_reports(name, issues, write_files=False):
    with open(f"{report_dir}/sonar-issues{name}.json", "w") as f:
        json.dump({"total": len(issues), "issues": issues}, f, indent=2)
    print(f"    Saved: {report_dir}/sonar-issues{name}.json ({len(issues)} {'issue' if len(issues) == 1 else 'issues'})")

    by_file = group_by_file(issues)
    report = {
        path: {"count": len(items), "issues": sorted(items, key=lambda x: x.get("line") or 0)}
        for path, items in sorted(by_file.items())
    }
    with open(f"{report_dir}/sonar-issues-by-file{name}.json", "w") as f:
        json.dump(report, f, indent=2)
    print(f"    Saved: {report_dir}/sonar-issues-by-file{name}.json ({len(report)} {'file' if len(report) == 1 else 'files'})")

    if write_files:
        for filepath, items in by_file.items():
            safe_filename = filepath.replace("/", "_").replace("\\", "_") + ".json"
            with open(f"{files_with_issues_dir}/{safe_filename}", "w") as f:
                json.dump({
                    "file": filepath,
                    "count": len(items),
                    "issues": sorted(items, key=lambda x: x.get("line") or 0)
                }, f, indent=2)
        print(f"    Saved: {len(by_file)} {'individual file report' if len(by_file) == 1 else 'individual file reports'} in {files_with_issues_dir}/")

# Issues (paginated, saved to files)
prefix = project + ":"
files_with_issues_dir = f"{report_dir}/files"
os.makedirs(files_with_issues_dir, exist_ok=True)
all_issues = fetch_issues()
new_code_issues = fetch_issues("inNewCodePeriod=true")
write_issue_reports("", all_issues, write_files=True)
write_issue_reports("-new-code", new_code_issues)

# Security Hotspots (separate endpoint from issues). Saved so the Quality Gate
# block below can NAME the specific hotspot(s) when a hotspot condition fails,
# instead of only reporting "New Security Hotspots Reviewed: 0.0".
def fetch_hotspots(extra_query=""):
    result = []
    page = 1
    while True:
        query = f"projectKey={project}&ps=500&p={page}&status=TO_REVIEW{PR_Q}"
        if extra_query:
            query = f"{query}&{extra_query}"
        data = api_get(f"/api/hotspots/search?{query}")
        if data is None:  # 403/permission or no hotspots endpoint access
            break
        hs = data.get("hotspots", [])
        result.extend(hs)
        if len(hs) < 500:
            break
        page += 1
    return result

def hotspot_summary(h):
    return {
        "rule": h.get("ruleKey", ""),
        "category": h.get("securityCategory", ""),
        "probability": h.get("vulnerabilityProbability", ""),
        "message": (h.get("message", "") or "").replace("\n", " "),
        "line": h.get("line"),
        "component": h.get("component", ""),
        "key": h.get("key", ""),
    }

def write_hotspot_reports(name, hotspots):
    with open(f"{report_dir}/sonar-hotspots{name}.json", "w") as f:
        json.dump({"total": len(hotspots), "apiForbidden": HOTSPOTS_FORBIDDEN["flag"],
                   "hotspots": hotspots}, f, indent=2)
    print(f"    Saved: {report_dir}/sonar-hotspots{name}.json ({len(hotspots)} {'hotspot' if len(hotspots) == 1 else 'hotspots'})")

all_hotspots = [hotspot_summary(h) for h in fetch_hotspots()]
new_code_hotspots = [hotspot_summary(h) for h in fetch_hotspots("inNewCodePeriod=true")]
write_hotspot_reports("", all_hotspots)
write_hotspot_reports("-new-code", new_code_hotspots)

# Quality gate
qg = api_get(f"/api/qualitygates/project_status?projectKey={project}{PR_Q}")
if qg:
    with open(f"{report_dir}/sonar-quality-gate.json", "w") as f:
        json.dump(qg, f, indent=2)
    print(f"    Saved: {report_dir}/sonar-quality-gate.json")

# Measures
measures = api_get(f"/api/measures/component?component={project}&metricKeys=bugs,vulnerabilities,code_smells,coverage,duplicated_lines_density,ncloc,security_hotspots,reliability_rating,security_rating,sqale_rating{PR_Q}")
if measures:
    with open(f"{report_dir}/sonar-measures.json", "w") as f:
        json.dump(measures, f, indent=2)
    print(f"    Saved: {report_dir}/sonar-measures.json")

# Duplications — only when the Quality Gate fails on a duplication metric. Enumerates
# the files carrying (new) duplicated lines and, via /api/duplications/show, the partner
# file each duplicated block points at, so the handoff can name exactly WHERE to dedupe
# instead of printing only the density percentage.
def gate_has_duplication_failure(qg_doc):
    for c in (qg_doc or {}).get("projectStatus", {}).get("conditions", []):
        if c.get("status") == "ERROR" and "duplicated" in c.get("metricKey", ""):
            return True
    return False

def measure_values(component):
    # New-code metrics live in measures[].value on recent servers and in
    # measures[].period.value on older ones — read whichever is present.
    vals = {}
    for x in component.get("measures", []):
        v = x.get("value")
        if v is None:
            v = (x.get("period") or {}).get("value")
        vals[x["metric"]] = v
    return vals

def fetch_duplications_via_api():
    tree = api_get(
        f"/api/measures/component_tree?component={project}"
        f"&metricKeys=new_duplicated_lines,duplicated_lines,duplicated_lines_density"
        f"&qualifier=FIL&ps=500&s=metric&metricSort=new_duplicated_lines&asc=false{PR_Q}")
    dup_files = []
    for comp in (tree or {}).get("components", []):
        vals = measure_values(comp)
        new_dup = vals.get("new_duplicated_lines")
        total_dup = vals.get("duplicated_lines")
        try:
            new_dup_n = float(new_dup) if new_dup is not None else 0.0
        except ValueError:
            new_dup_n = 0.0
        try:
            total_dup_n = float(total_dup) if total_dup is not None else 0.0
        except ValueError:
            total_dup_n = 0.0
        # Prefer files with NEW duplicated lines (what the PR gate counts); fall back to
        # total duplicated lines when the server does not expose the new-code breakdown.
        if new_dup_n <= 0 and not (new_dup is None and total_dup_n > 0):
            continue
        dup_files.append({
            "file": comp.get("key", "").split(":", 1)[-1],
            "componentKey": comp.get("key", ""),
            "new_duplicated_lines": new_dup,
            "duplicated_lines": total_dup,
            "duplicated_lines_density": vals.get("duplicated_lines_density"),
        })
    # Ask Sonar which blocks duplicate which partner files, per duplicated file.
    for df in dup_files:
        show = api_get(
            f"/api/duplications/show?key={urllib.parse.quote(df['componentKey'], safe='')}{PR_Q}")
        partners = set()
        blocks = []
        if show:
            ref_to_name = {k: (v.get("name") or v.get("key", ""))
                           for k, v in (show.get("files", {}) or {}).items()}
            for dup in show.get("duplications", []):
                blk = []
                for b in dup.get("blocks", []):
                    name = ref_to_name.get(b.get("_ref"), "")
                    short = name.split(":", 1)[-1] if name else ""
                    blk.append({"file": short, "from": b.get("from"), "size": b.get("size")})
                    if short and short != df["file"]:
                        partners.add(short)
                blocks.append(blk)
        df["partners"] = sorted(partners)
        df["blocks"] = blocks
    return dup_files

def local_duplication_scan():
    # Fallback when the measures API is forbidden (analysis tokens often get 403 on
    # /api/measures/*): approximate Sonar's CPD locally so the handoff can still NAME
    # the duplicated files. Line-based (≈Sonar's ~10-line minimum block), over the
    # module's duplication-eligible sources (src/**/*.java — src-test/ and src-db/ are
    # excluded from duplication, mirroring the analysis config). Heuristic, not exact.
    import glob
    WINDOW = 10
    repo_root = os.environ.get("REPO_ROOT") or os.getcwd()
    base_ref = os.environ.get("BASE_REF", "")
    java_files = glob.glob(os.path.join(repo_root, "src", "**", "*.java"), recursive=True)

    def significant_lines(path):
        # (lineno, normalized) for lines that carry real logic. Blank, brace-only and
        # comment lines are dropped so they do not seed noise matches.
        out = []
        try:
            with open(path, encoding="utf-8", errors="replace") as fh:
                for idx, raw in enumerate(fh, 1):
                    s = raw.strip()
                    if not s or s in ("{", "}", "};", "});", ");", ")", "(") \
                            or s.startswith(("//", "*", "/*", "*/")):
                        continue
                    out.append((idx, s))
        except OSError:
            pass
        return out

    # window (tuple of normalized lines) -> list of (relpath, start_line, end_line)
    seeds = {}
    for path in java_files:
        rel = os.path.relpath(path, repo_root)
        sig = significant_lines(path)
        for i in range(len(sig) - WINDOW + 1):
            window = tuple(t for _, t in sig[i:i + WINDOW])
            seeds.setdefault(window, []).append((rel, sig[i][0], sig[i + WINDOW - 1][0]))
    dup_seeds = [v for v in seeds.values() if len(v) >= 2]

    # Restrict reporting to blocks that touch THIS push's changed source files.
    changed = set()
    if base_ref:
        try:
            import subprocess
            diff = subprocess.run(["git", "diff", "--name-only", f"{base_ref}...HEAD"],
                                  cwd=repo_root, capture_output=True, text=True, check=False)
            changed = {l.strip() for l in diff.stdout.splitlines()
                       if l.strip().endswith(".java") and l.strip().startswith("src/")}
        except Exception:
            changed = set()

    per_file = {}
    for occ_list in dup_seeds:
        files_in_seed = {o[0] for o in occ_list}
        for (rel, s_line, e_line) in occ_list:
            if changed and rel not in changed:
                continue
            entry = per_file.setdefault(rel, {"ranges": [], "partners": set()})
            entry["ranges"].append((s_line, e_line))
            entry["partners"].update(f for f in files_in_seed if f != rel)

    def merge(ranges):
        merged = []
        for s, e in sorted(ranges):
            if merged and s <= merged[-1][1] + 1:
                merged[-1] = (merged[-1][0], max(merged[-1][1], e))
            else:
                merged.append([s, e])
        return merged

    results = []
    for rel, entry in per_file.items():
        blocks = merge(entry["ranges"])
        results.append({
            "file": rel,
            "componentKey": "",
            "new_duplicated_lines": None,
            "duplicated_lines": sum(e - s + 1 for s, e in blocks),
            "duplicated_lines_density": None,
            "partners": sorted(entry["partners"]),
            "blocks": [[{"file": rel, "from": s, "size": e - s + 1}] for s, e in blocks],
        })
    results.sort(key=lambda d: d["duplicated_lines"], reverse=True)
    return results

if gate_has_duplication_failure(qg):
    dup_files = fetch_duplications_via_api()
    dup_source = "sonar-api"
    if not dup_files:
        # API gave nothing (commonly a 403 on /api/measures/* for analysis tokens).
        dup_files = local_duplication_scan()
        dup_source = "local-heuristic"
    with open(f"{report_dir}/sonar-duplications.json", "w") as f:
        json.dump({"total": len(dup_files), "source": dup_source, "files": dup_files}, f, indent=2)
    print(f"    Saved: {report_dir}/sonar-duplications.json "
          f"({len(dup_files)} {'file' if len(dup_files) == 1 else 'files'} with duplication, "
          f"source: {dup_source})")

# ── Summary ──
print()
print("=== SONAR ANALYSIS SUMMARY ===")

if qg:
    status = qg.get("projectStatus", {}).get("status", "UNKNOWN")
    print(f"Quality Gate: {status}")
    conditions = qg.get("projectStatus", {}).get("conditions", [])
    failed_conditions = [c for c in conditions if c.get("status") == "ERROR"]
    if failed_conditions:
        print("  Failing Conditions:")
        for c in failed_conditions:
            metric = c["metricKey"].replace("_", " ").title()
            val = c.get("actualValue", "N/A")
            thresh = c.get("errorThreshold", "N/A")
            print(f"    - {metric}: {val} (threshold: < {thresh})")
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
print(f"New-code issues: {len(new_code_issues)}")

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

  echo "==> Filtering issues to changed files against: $BASE_REF"
  if ! git rev-parse --verify "$BASE_REF" >/dev/null 2>&1; then
    echo "ERROR: Invalid base reference: $BASE_REF"
    exit 1
  fi
  git diff --name-only "$BASE_REF"...HEAD > "$REPORT_DIR/changed-files.txt"

  python3 - <<'PYEOF'
import json, os
from pathlib import Path

report_dir = Path(os.environ.get("REPORT_DIR", "sonar-reports"))
changed = {line.strip() for line in (report_dir / "changed-files.txt").read_text().splitlines() if line.strip()}
issues_by_file = json.loads((report_dir / "sonar-issues-by-file-new-code.json").read_text())
issues = json.loads((report_dir / "sonar-issues-new-code.json").read_text())

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

print(f"    Saved: {report_dir}/sonar-issues-pr-only.json ({len(filtered_issues)} {'issue' if len(filtered_issues) == 1 else 'issues'})")
print(f"    Saved: {report_dir}/sonar-issues-by-file-pr-only.json ({len(filtered_by_file)} {'file' if len(filtered_by_file) == 1 else 'files'})")

# Restrict new-code Security Hotspots to this PR's changed files too.
hs_path = report_dir / "sonar-hotspots-new-code.json"
hs_doc = json.loads(hs_path.read_text()) if hs_path.exists() else {}
new_code_hotspots = hs_doc.get("hotspots", [])
filtered_hotspots = [
    h for h in new_code_hotspots
    if h.get("component", "").split(":", 1)[-1] in changed
]
(report_dir / "sonar-hotspots-pr-only.json").write_text(json.dumps({
    "total": len(filtered_hotspots),
    "apiForbidden": hs_doc.get("apiForbidden", False),
    "hotspots": filtered_hotspots
}, indent=2))
print(f"    Saved: {report_dir}/sonar-hotspots-pr-only.json ({len(filtered_hotspots)} {'hotspot' if len(filtered_hotspots) == 1 else 'hotspots'})")
PYEOF

  echo "PR-only reports saved in: $REPORT_DIR/"
else
  echo "Full-project reports saved in: $REPORT_DIR/"
fi

# ── Quality Gate enforcement (opt-in via --fail-on-gate) ────────────
# Mirrors the server-side Quality Gate that CI enforces on the PR. When the
# gate is in ERROR, exit non-zero so callers (e.g. the pre-push hook) can block.
if [[ "$FAIL_ON_GATE" == "true" ]]; then
  QG_FILE="$REPORT_DIR/sonar-quality-gate.json"
  PR_ISSUES_FILE="$REPORT_DIR/sonar-issues-pr-only.json"
  if [[ ! -f "$QG_FILE" ]]; then
    echo "ERROR: --fail-on-gate set but $QG_FILE not found (analysis may have failed)."
    exit 1
  fi

  GATE_BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo 'this branch')"
  HANDOFF_FILE="$REPORT_DIR/sonar-handoff-prompt.md"

  REPORT_DIR="$REPORT_DIR" QG_FILE="$QG_FILE" PR_ISSUES_FILE="$PR_ISSUES_FILE" \
  CHANGED_ONLY="$CHANGED_ONLY" HANDOFF_FILE="$HANDOFF_FILE" \
  GATE_BRANCH="$GATE_BRANCH" CORE_DIR="$CLASSIC_ROOT" PROJECT_KEY="$PROJECT_KEY" \
  SONAR_HOST_URL="$SONAR_HOST_URL" SONAR_PR_KEY="$SONAR_PR_KEY" REPO_ROOT="$SCRIPT_DIR" \
  python3 - <<'PYEOF'
import json, os, sys

qg = json.load(open(os.environ["QG_FILE"]))
status = qg.get("projectStatus", {}).get("status", "UNKNOWN")
handoff_file = os.environ["HANDOFF_FILE"]
pr_mode = os.environ.get("CHANGED_ONLY") == "true"

# Clean up any stale handoff from a previous run.
try:
    os.remove(handoff_file)
except FileNotFoundError:
    pass

# Failing Quality Gate conditions, keeping the raw metric key.
failing = []  # (metric_key, human_description)
for c in qg.get("projectStatus", {}).get("conditions", []):
    if c.get("status") == "ERROR":
        m = c["metricKey"]
        failing.append((m, f"{m.replace('_',' ').title()}: {c.get('actualValue')} "
                           f"(threshold: {c.get('comparator','')} {c.get('errorThreshold')})"))

# New issues restricted to THIS PR's diff (what CI's PR gate actually counts).
issues = []
pr_file = os.environ.get("PR_ISSUES_FILE", "")
if pr_mode and os.path.isfile(pr_file):
    for i in json.load(open(pr_file)).get("issues", []):
        issues.append({
            "comp": i.get("component", "").split(":", 1)[-1],
            "line": i.get("line", "?"),
            "rule": i.get("rule", ""),
            "sev": i.get("severity", ""),
            "msg": (i.get("message", "") or "").replace("\n", " "),
        })

# Security Hotspots that drive a "...reviewed" gate condition. In PR mode use the
# diff-restricted file; otherwise the new-code file. These NAME the exact hotspot.
report_dir = os.environ["REPORT_DIR"]
hs_name = "sonar-hotspots-pr-only.json" if pr_mode else "sonar-hotspots-new-code.json"
hs_path = os.path.join(report_dir, hs_name)
hs_doc = json.load(open(hs_path)) if os.path.isfile(hs_path) else {}
hotspots = hs_doc.get("hotspots", [])
# True when /api/hotspots/search returned 403 — the token lacks "Browse Security
# Hotspots", so the server could not name the hotspot and we fall back to a local
# heuristic scan of the diff (below) instead of an unhelpful "unknown" message.
hotspots_forbidden = bool(hs_doc.get("apiForbidden", False))
# A hotspot-review condition: e.g. new_security_hotspots_reviewed, security_review_rating.
hotspot_metrics = [d for (m, d) in failing
                   if "security_hotspot" in m or "security_review" in m]

# Per-file duplication detail (saved by the download step when a duplication condition
# fails) — names the files carrying duplicated lines and the partner each block matches.
dup_path = os.path.join(report_dir, "sonar-duplications.json")
dup_doc = json.load(open(dup_path)) if os.path.isfile(dup_path) else {}
dup_files = dup_doc.get("files", [])
dup_source = dup_doc.get("source", "")

# ── Local heuristic: candidate hotspot locations from the diff ──
# Only used when the gate flags a hotspot condition but the API would not name it
# (403). Greps THIS push's changed files for the security-sensitive patterns Sonar
# most commonly raises as hotspots in Java, so the handoff still points at concrete
# file:line locations. Heuristic — may over- or under-report.
def scan_hotspot_candidates():
    import re
    changed_path = os.path.join(report_dir, "changed-files.txt")
    if not os.path.isfile(changed_path):
        return []
    repo_root = os.environ.get("REPO_ROOT", ".")
    changed = [l.strip() for l in open(changed_path) if l.strip()
               and l.strip().endswith((".java",))]
    # (rule, label, regex). Kept small and high-signal, matched against the full
    # file text (DOTALL) so multi-line cases are caught. The S2077 pattern requires
    # a string literal concatenated with `+` inside a query/statement call (a fully
    # parameterised PreparedStatement is safe and must NOT match).
    DOTALL = re.DOTALL
    PATTERNS = [
        ("java:S2077", "Formatting SQL queries is security-sensitive",
         re.compile(r"(?:createStatement|prepareStatement|executeQuery|executeUpdate|execute|createSQLQuery|createQuery)\s*\([^)]*?[\"'][^\"']*?[\"']\s*\+", DOTALL)),
        ("java:S2076", "Executing OS commands is security-sensitive",
         re.compile(r"(?:Runtime\.getRuntime\(\)\s*\.\s*exec|new\s+ProcessBuilder)\s*\(")),
        ("java:S2245", "Using pseudorandom number generators is security-sensitive",
         re.compile(r"\bnew\s+Random\s*\(|\bMath\.random\s*\(")),
        ("java:S5852", "Slow regex (ReDoS) is security-sensitive",
         re.compile(r"\bPattern\.compile\s*\(")),
        ("java:S4790", "Using weak hashing algorithms is security-sensitive",
         re.compile(r"MessageDigest\.getInstance\s*\(\s*\"(?:MD5|SHA-1|SHA1)\"")),
    ]
    out = []
    for rel in changed:
        full = os.path.join(repo_root, rel)
        if not os.path.isfile(full):
            continue
        try:
            text = open(full, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        file_lines = text.splitlines()
        for rule, label, rx in PATTERNS:
            for m in rx.finditer(text):
                line_no = text.count("\n", 0, m.start()) + 1
                code = file_lines[line_no - 1].strip() if line_no <= len(file_lines) else ""
                out.append({"comp": rel, "line": line_no, "rule": rule,
                            "label": label, "code": code[:100]})
    out.sort(key=lambda c: (c["comp"], c["line"]))
    return out

# ── Decide whether to block, mirroring CI's PR gate ──
# CI counts "new issues" over the PR diff only. Branch-mode `new_violations` may
# also count new code OUTSIDE this PR's diff, which CI ignores. So:
#   - the new_violations condition blocks only if PR-diff issues > 0;
#   - any OTHER failing condition (coverage/duplication/ratings on new code) blocks.
other_failing = [d for (m, d) in failing if m != "new_violations"]
block = bool(issues) or bool(other_failing)

if not block:
    if status == "ERROR":
        print("\n⚠️  Quality Gate is ERROR in branch mode, but no new issues fall inside")
        print("   this PR's diff — CI's PR gate would PASS. Not blocking the push.")
        for (m, d) in failing:
            print(f"     (branch-mode, ignored) {d}")
    else:
        print(f"\n✅ Quality Gate: {status} — no blocking conditions.")
    sys.exit(0)

branch = os.environ.get("GATE_BRANCH", "this branch")
core_dir = os.environ.get("CORE_DIR", "<core_dir>")

# ── Build a copy-pasteable handoff prompt (delimited by ---) ──
lines = []
lines.append("---")
lines.append("HANDOFF PROMPT — copy everything between the --- lines and give it to a coding agent")
lines.append("---")
lines.append("")
lines.append("You are fixing SonarQube Quality Gate violations that are BLOCKING a `git push`")
lines.append(f"on the `com.etendoerp.go` module (branch: {branch}). The pre-push hook blocked the")
lines.append("push because CI's SonarQube Quality Gate would fail.")
lines.append("")
lines.append("SCOPE — fix ONLY the issues listed below. Make minimal, behavior-preserving edits")
lines.append("that satisfy each Sonar rule. Do NOT weaken tests or reduce coverage. Each Sonar rule")
lines.append("key is given so you can look it up.")
lines.append("")
if other_failing:
    lines.append("Failing Quality Gate condition(s):")
    for d in other_failing:
        lines.append(f"  - {d}")
    lines.append("")
if dup_files:
    lines.append(f"Files carrying duplicated lines ({len(dup_files)}, most-duplicated first) —")
    lines.append("dedupe these; a duplicated block needs only ONE copy refactored to clear it:")
    if dup_source == "local-heuristic":
        lines.append("  (LOCAL HEURISTIC — SonarQube's measures API was not readable with this token,")
        lines.append("   so duplicated blocks were detected locally over src/**/*.java with a ~10-line")
        lines.append("   window. Line ranges are approximate; verify against the SonarQube UI.)")
    for n, df in enumerate(dup_files, 1):
        bits = []
        if df.get("new_duplicated_lines") is not None:
            bits.append(f"new dup lines: {df['new_duplicated_lines']}")
        if df.get("duplicated_lines") is not None:
            bits.append(f"total dup lines: {df['duplicated_lines']}")
        if df.get("duplicated_lines_density") is not None:
            bits.append(f"file density: {df['duplicated_lines_density']}%")
        suffix = f"  ({', '.join(bits)})" if bits else ""
        lines.append(f"  {n}. {df['file']}{suffix}")
        partners = df.get("partners", [])
        if partners:
            lines.append(f"     duplicates with: {', '.join(partners)}")
        for blk in df.get("blocks", [])[:3]:
            locs = "; ".join(
                f"{b['file']}:{b['from']}-{b['from'] + (b['size'] or 1) - 1}"
                for b in blk if b.get("from") is not None and b.get("file"))
            if locs:
                lines.append(f"       block: {locs}")
    lines.append("")
if hotspot_metrics:
    host = os.environ.get("SONAR_HOST_URL", "").rstrip("/")
    pkey = os.environ.get("PROJECT_KEY", "")
    pr_key = os.environ.get("SONAR_PR_KEY", "")
    hs_url = f"{host}/security_hotspots?id={pkey}"
    # Scope the UI link the same way the analysis was scoped: PR > branch > newcode.
    if pr_key:
        hs_url += f"&pullRequest={pr_key}"
    elif branch and branch != "this branch":
        hs_url += f"&branch={branch}&inNewCodePeriod=true"
    else:
        hs_url += "&inNewCodePeriod=true"
    lines.append("Security Hotspot review is blocking the gate. A Security Hotspot is")
    lines.append("security-sensitive code that must be MANUALLY reviewed and marked")
    lines.append("'Safe'/'Acknowledged' (or fixed). The gate requires 100% of NEW hotspots")
    lines.append("reviewed — an unreviewed one reads as 'Reviewed: 0.0%'.")
    if hotspots:
        lines.append(f"New Security Hotspot(s) to review ({len(hotspots)}) — file:line — rule (probability) — category — message:")
        for n, h in enumerate(hotspots, 1):
            comp = h.get("component", "").split(":", 1)[-1]
            lines.append(f"  {n}. {comp}:{h.get('line', '?')} — {h.get('rule', '')} "
                         f"({h.get('probability', '')}) — {h.get('category', '')}")
            lines.append(f"     {h.get('message', '')}")
    else:
        # API could not name the hotspot. If that was a 403 permission wall, scan
        # the diff locally so the agent still gets concrete file:line candidates.
        candidates = scan_hotspot_candidates() if hotspots_forbidden else []
        if hotspots_forbidden:
            lines.append("  The exact hotspot could not be read from SonarQube: the local token got")
            lines.append("  HTTP 403 on /api/hotspots/search (it lacks the 'Browse Security Hotspots'")
            lines.append("  project permission). This happens with analysis tokens (sqa_/sqp_); use a")
            lines.append("  User Token (squ_) from an account with Browse permission to list them.")
            lines.append("  CI's token can list them; the URL below shows them too.")
        else:
            lines.append("  (Could not enumerate the hotspot(s) via the API — the analysis may not")
            lines.append("   expose them to this token. Open the URL below to see the flagged line.)")
        if candidates:
            lines.append("")
            lines.append(f"  LOCAL HEURISTIC — security-sensitive lines in THIS push's diff ({len(candidates)} candidate(s)).")
            lines.append("  These are likely (not confirmed) the flagged hotspot(s); verify against the URL:")
            for n, c in enumerate(candidates, 1):
                lines.append(f"    {n}. {c['comp']}:{c['line']} — {c['rule']} — {c['label']}")
                lines.append(f"       {c['code']}")
        elif hotspots_forbidden:
            lines.append("  (Local heuristic scan of the diff found no obvious security-sensitive")
            lines.append("   pattern — the hotspot may be in code outside this push's changed files.)")
    lines.append(f"  Review them here: {hs_url}")
    lines.append("  Fix in code, or (if safe) mark each hotspot Reviewed in SonarQube, then re-push.")
    lines.append("")
if issues:
    lines.append(f"New issues in this PR ({len(issues)}) — file:line — rule [severity] — message:")
    for n, i in enumerate(issues, 1):
        lines.append(f"  {n}. {i['comp']}:{i['line']} — {i['rule']} [{i['sev']}]")
        lines.append(f"     {i['msg']}")
    lines.append("")
lines.append("After fixing, verify locally and re-push:")
lines.append(f"  cd {core_dir}")
lines.append('  ./gradlew test --tests "com.etendoerp.go.*"')
lines.append("  git push        # the pre-push hook re-checks the Quality Gate")
lines.append("")
lines.append("---")
lines.append("END HANDOFF")
lines.append("---")
handoff = "\n".join(lines)

# Write the handoff file (the pre-push hook prints this to the console).
with open(handoff_file, "w") as fh:
    fh.write(handoff + "\n")

# Also echo it (captured to the step log) plus a short header.
print("\n❌ QUALITY GATE FAILED — this push would fail CI's SonarQube gate.")
print(handoff)
print("\n  Bypass with 'git push --no-verify' (WIP only).")
sys.exit(1)
PYEOF
fi

# ── Coverage-decrease gate (opt-in via --compare-coverage) ──────────
# Blocks a push when this branch's OVERALL coverage is more than COVERAGE_TOLERANCE
# (pp, default 1) below the base branch's, or below the absolute COVERAGE_MINIMUM
# (default 70). Mirrors the Jenkins sonarUtils.compareCoverage gate. Current and
# base coverage are queried live from Sonar.
if [[ "$COMPARE_COVERAGE" == "true" ]]; then
  CMP_BRANCH="${BASE_REF#origin/}"
  CMP_BRANCH="${CMP_BRANCH:-epic/ETP-3504}"
  GATE_BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo 'this branch')"

  if [[ "$GATE_BRANCH" == "$CMP_BRANCH" ]]; then
    echo "==> Coverage comparison skipped (on the base branch '$CMP_BRANCH')."
  else
    echo "==> Comparing overall coverage: $GATE_BRANCH vs $CMP_BRANCH ..."
    set +e
    CMP_BRANCH="$CMP_BRANCH" GATE_BRANCH="$GATE_BRANCH" \
    SONAR_HOST_URL="$SONAR_HOST_URL" SONAR_TOKEN="$SONAR_TOKEN" PROJECT_KEY="$PROJECT_KEY" \
    SONAR_PR_KEY="${SONAR_PR_KEY:-}" \
    COVERAGE_TOLERANCE="${COVERAGE_TOLERANCE:-1}" COVERAGE_MINIMUM="${COVERAGE_MINIMUM:-70}" \
    python3 - <<'PYEOF'
import base64 as b64, json, os, sys, urllib.error, urllib.parse, urllib.request

base = os.environ["SONAR_HOST_URL"].rstrip("/")
token = os.environ["SONAR_TOKEN"]
project = os.environ["PROJECT_KEY"]
cmp_branch = os.environ["CMP_BRANCH"]
gate_branch = os.environ["GATE_BRANCH"]
pr_key = os.environ.get("SONAR_PR_KEY", "")
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
    except Exception as e:  # network/DNS — never hard-block on tooling failure
        print(f"    WARNING: {e} on {path}", file=sys.stderr)
        return None

def read_metric(doc, metric):
    """New-code metrics (new_coverage) carry their value under measures[].period.value;
    plain metrics (coverage) under measures[].value. Handle both."""
    if not doc:
        return None
    for m in doc.get("component", {}).get("measures", []):
        if m.get("metric") != metric:
            continue
        v = m.get("value")
        if v in (None, ""):
            v = (m.get("period") or {}).get("value")
        if v in (None, ""):
            return None
        try:
            return float(v)
        except ValueError:
            return None
    return None

tolerance = float(os.environ.get("COVERAGE_TOLERANCE") or "1")
min_coverage = float(os.environ.get("COVERAGE_MINIMUM") or "70")

pr_q = f"&pullRequest={urllib.parse.quote(pr_key)}" if pr_key else ""
# Current branch OVERALL coverage.
current = read_metric(
    api_get(f"/api/measures/component?component={project}&metricKeys=coverage{pr_q}"),
    "coverage")
# Base branch OVERALL coverage, queried live like sonarUtils.getCoverageWithRetry.
enc = urllib.parse.quote(cmp_branch)
base_cov = read_metric(
    api_get(f"/api/measures/component?component={project}&branch={enc}&metricKeys=coverage"),
    "coverage")

if current is None:
    print("    SKIPPED ⚠️  Could not read this branch's coverage from Sonar — not blocking.")
    sys.exit(0)

# Absolute floor: below the minimum the push is blocked outright, no base comparison.
if current < min_coverage:
    print(f"    {gate_branch} coverage: {current:.2f}%")
    print(f"\n❌ COVERAGE BELOW MINIMUM — {current:.2f}% < {min_coverage:.2f}% required.")
    print( "   Add tests until overall coverage reaches the minimum, then re-push.")
    print( "   Bypass with 'git push --no-verify' (WIP only).")
    sys.exit(1)

if base_cov is None:
    print(f"    SKIPPED ⚠️  No coverage on Sonar for '{cmp_branch}' yet — not blocking.")
    sys.exit(0)

min_required = base_cov - tolerance
print(f"    {gate_branch} coverage: {current:.2f}%")
print(f"    {cmp_branch} coverage: {base_cov:.2f}% (min required with {tolerance:.2f}pp tolerance: {min_required:.2f}%)")

# current >= base_cov - tolerance: overall coverage is not more than the tolerance
# below the base branch's.
if current < min_required:
    short = min_required - current
    print(f"\n❌ COVERAGE DECREASED — this push would fail Jenkins' 'Compare Coverage Results'.")
    print(f"   {current:.2f}% < {min_required:.2f}% (base {base_cov:.2f}% − {tolerance:.2f}pp) on '{cmp_branch}' (short {short:.2f}pp).")
    print( "   Add tests until overall coverage is >= the base (minus tolerance), then re-push.")
    print( "   Bypass with 'git push --no-verify' (WIP only).")
    sys.exit(1)

print("    Coverage is OK ✅ (not below base − tolerance, and above the minimum).")
sys.exit(0)
PYEOF
    CMP_RC=$?
    set -e
    if [[ "$CMP_RC" -ne 0 ]]; then
      exit "$CMP_RC"
    fi
  fi
fi
