#!/usr/bin/env bash
# .githooks/lib/target-branch.sh
#
# The branch a push will be merged into, and whether it still merges cleanly there. The pre-push
# measures the change against that branch (conflict check, relevance diff, Sonar's base ref, the
# unit-test selection), so a hotfix bound for main is not measured against develop. Mirrored
# verbatim across repos (etendo_schema_forge, com.etendoerp.go): edit it in one and sync.
#
#   pushed_branch "<pre-push stdin>"   the branch being pushed: the first refs/heads/ remote ref,
#                                      else the checked-out branch
#   target_branch <branch>             where it merges, as a bare name, in this order:
#                                        1. PRE_PUSH_TARGET, when set
#                                        2. the base of the branch's open PR (gh, when installed and
#                                           signed in; PRE_PUSH_TARGET_LOOKUP=0 skips it)
#                                        3. main for main itself, hotfix/* and the daily cut
#                                           (mergeblock/*), the rule CI uses when there is no PR yet
#                                           (offline-regen-check.yml)
#                                        4. develop
#   check_target_conflicts <branch>    fetches origin/<branch> and exits the hook when HEAD does not
#                                      merge into it cleanly (git merge-tree, no working-tree change)

pushed_branch() {
  local refs="$1" _local_ref local_sha remote_ref _remote_sha
  while read -r _local_ref local_sha remote_ref _remote_sha; do
    [ "$local_sha" = "0000000000000000000000000000000000000000" ] && continue   # a deletion
    case "$remote_ref" in
      refs/heads/*) printf '%s' "${remote_ref#refs/heads/}"; return 0 ;;
    esac
  done <<< "$refs"
  git symbolic-ref --short -q HEAD 2>/dev/null || true
}

target_branch() {
  local branch="$1" pr=""
  if [ -n "${PRE_PUSH_TARGET:-}" ]; then
    printf '%s' "${PRE_PUSH_TARGET#origin/}"
    return 0
  fi
  if [ -n "$branch" ] && [ "${PRE_PUSH_TARGET_LOOKUP:-1}" != "0" ] && command -v gh >/dev/null 2>&1; then
    # "<state> <base>" of the most recent PR whose head is this branch; only an open one counts.
    pr="$(GH_PROMPT_DISABLED=1 gh pr view "$branch" --json state,baseRefName \
      --jq '.state + " " + .baseRefName' 2>/dev/null || true)"
    case "$pr" in
      "OPEN "?*) printf '%s' "${pr#OPEN }"; return 0 ;;
    esac
  fi
  case "$branch" in
    main|hotfix/*|mergeblock/*) printf 'main' ;;
    *) printf 'develop' ;;
  esac
}

check_target_conflicts() {
  local target="$1" base_ref out rc
  echo "==> Checking for conflicts with the target branch (fetch + merge-tree)..."
  if ! git fetch --quiet origin "$target" 2>/dev/null; then
    echo "    SKIPPED ⚠️ (could not fetch origin/$target — offline, or the branch does not exist)"
    return 0
  fi
  base_ref="origin/$target"

  # Tree-level merge check — no working-tree changes (git >= 2.38).
  set +e
  out="$(git merge-tree --write-tree --name-only "$base_ref" HEAD 2>/dev/null)"
  rc=$?
  set -e

  if [ "$rc" -eq 0 ]; then
    echo "    SUCCESS ✅ (merges cleanly into $base_ref)"
    return 0
  elif [ "$rc" -eq 1 ]; then
    # Conflicts. Output is: <tree-oid> on line 1, then conflicted file paths.
    echo "    FAILED ❌ (conflicts with $base_ref)"
    echo ""
    echo "========================================================================="
    echo "❌ ERROR: This branch has merge conflicts with $base_ref."
    echo "========================================================================="
    echo "Conflicting file(s):"
    echo "$out" | tail -n +2 | sed 's/^/  - /'
    echo ""
    echo "👉 Update your branch and resolve the conflicts before pushing:"
    echo "     git merge $base_ref"
    echo "   Then re-run the push. Wrong target? Set PRE_PUSH_TARGET=<branch>."
    echo "========================================================================="
    exit 1
  else
    # rc > 1: merge-tree errored or unsupported — warn but don't hard-block.
    echo "    SKIPPED ⚠️ (merge-tree unavailable or errored, rc=$rc)"
    return 0
  fi
}
