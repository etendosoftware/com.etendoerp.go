#!/usr/bin/env bash
# scripts/verify-hooks-trailer.sh
#
# Verifies that every commit in a range carries a valid Hooks-Verified trailer,
# i.e. that the git hooks were installed and ran when the commit was created.
#
# This is the server-side counterpart of the pre-push check: it runs in CI, so
# it cannot be skipped with --no-verify. It is what turns "we can detect missing
# hooks by looking at GitHub" into "a PR without hooks fails the check".
#
# Usage:
#   scripts/verify-hooks-trailer.sh <base-ref> [head-ref]
#   scripts/verify-hooks-trailer.sh origin/develop HEAD
#
# Exit: 0 all good · 1 one or more commits unverified
#
# Merge commits are skipped (they are not produced by a pre-commit run).
# Commits authored before HOOKS_ENFORCE_SINCE (ISO date, optional) are skipped,
# so the rule can be rolled out without rewriting existing history.

set -euo pipefail

BASE="${1:?usage: verify-hooks-trailer.sh <base-ref> [head-ref]}"
HEAD_REF="${2:-HEAD}"
SINCE="${HOOKS_ENFORCE_SINCE:-}"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../.githooks/lib/hooks-proof.sh
. "$SCRIPT_DIR/../.githooks/lib/hooks-proof.sh"

RANGE="$(git rev-list "${BASE}..${HEAD_REF}")"
if [ -z "$RANGE" ]; then
  echo "No commits to verify in ${BASE}..${HEAD_REF}."
  exit 0
fi

FAILED=0
CHECKED=0
for sha in $RANGE; do
  if [ -n "$SINCE" ]; then
    ts="$(git log -1 --format=%aI "$sha")"
    [[ "$ts" < "$SINCE" ]] && continue
  fi

  set +e
  hooks_verify_commit "$sha"
  rc=$?
  set -e
  CHECKED=$((CHECKED + 1))

  case "$rc" in
    0) ;;
    1)
      printf '❌ %s  %s\n     missing %s trailer — hooks were not active for this commit\n' \
        "$(git rev-parse --short "$sha")" "$(git log -1 --format=%s "$sha")" "$HOOKS_TRAILER_KEY"
      FAILED=$((FAILED + 1))
      ;;
    2)
      printf '❌ %s  %s\n     %s fingerprint does not match the commit tree\n     (trailer copied from another commit, or content changed after the hook ran)\n' \
        "$(git rev-parse --short "$sha")" "$(git log -1 --format=%s "$sha")" "$HOOKS_TRAILER_KEY"
      FAILED=$((FAILED + 1))
      ;;
  esac
done

if [ "$FAILED" -gt 0 ]; then
  cat <<EOF

$FAILED of $CHECKED commit(s) were created without active git hooks.

How to fix:
  1. Enable the hooks:   npm install        # sets core.hooksPath=.githooks
  2. Re-create the commits so they get stamped:
       git rebase --exec 'git commit --amend --no-edit' $BASE
  3. Push again (force-with-lease on your own branch).

Why this matters: without the hooks, the local validations (pipeline, tests,
quality gate) never ran on those commits.
EOF
  exit 1
fi

echo "✔ $CHECKED commit(s) verified — hooks were active for all of them."
