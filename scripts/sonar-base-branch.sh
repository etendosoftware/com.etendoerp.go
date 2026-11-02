#!/usr/bin/env bash
# scripts/sonar-base-branch.sh
#
# Makes the local branch Sonar compares against match origin's before a PR-mode analysis
# (ETP-6067). run-sonar.sh passes the base as a bare name (sonar.pullrequest.base=main): the
# server knows no origin/ prefix. The scanner resolves that name to refs/heads/<name> before
# refs/remotes/origin/<name> (SonarQube's GitScmProvider.resolveTargetRef), so a stale local main
# moves the merge base months back: code already in main counts as new and fails the gate.
# Mirrored verbatim in etendo_schema_forge and com.etendoerp.go: edit it in one and sync.
#
# Usage: scripts/sonar-base-branch.sh <base-ref>
#   origin/<name>  the local <name> must not lag or differ from origin/<name>:
#                    no local branch, or the same commit   nothing to do
#                    only behind                           fast-forwarded to origin/<name>, as already
#                                                          fetched (git fetch from the repo itself)
#                    behind while checked out, ahead or    exits 1 and says how to fix it
#                    diverged
#   anything else  (a commit, a local branch) left alone
# Prints the base Sonar will read: "==> Sonar base: <name> @ <sha> (<date>)".
#
# Exit: 0 the base matches origin · 1 the local branch needs fixing by hand · 2 bad usage

set -euo pipefail

if [ $# -ne 1 ] || [ -z "$1" ]; then
  echo "usage: $0 <base-ref>" >&2
  exit 2
fi
BASE_REF="$1"
describe() { git log -1 --format='%h (%cs)' "$1"; }

case "$BASE_REF" in
  origin/?*) ;;
  *)
    echo "==> Sonar base: $BASE_REF @ $(describe "$BASE_REF")"
    exit 0
    ;;
esac

NAME="${BASE_REF#origin/}"
REMOTE="$(git rev-parse --verify "refs/remotes/$BASE_REF^{commit}")"
LOCAL="$(git rev-parse --verify --quiet "refs/heads/$NAME^{commit}" || true)"

if [ -z "$LOCAL" ]; then
  echo "==> Sonar base: $BASE_REF @ $(describe "$REMOTE") (no local $NAME, so Sonar reads origin's)"
  exit 0
fi
if [ "$LOCAL" = "$REMOTE" ]; then
  echo "==> Sonar base: $NAME @ $(describe "$REMOTE")"
  exit 0
fi

STALE="Sonar compares against your local $NAME, at $(describe "$LOCAL"), but $BASE_REF is at $(describe "$REMOTE")."
if git merge-base --is-ancestor "$LOCAL" "$REMOTE"; then
  # git fetch refuses to move a branch checked out in any worktree, and moves it only forward.
  if git fetch --quiet --no-tags --no-prune --no-write-fetch-head . "refs/remotes/$BASE_REF:refs/heads/$NAME" 2>/dev/null; then
    echo "==> Sonar base: $NAME @ $(describe "$REMOTE"), fast-forwarded from $(describe "$LOCAL")"
    exit 0
  fi
  echo "ERROR: $STALE"
  echo "It is checked out, so it cannot be moved from here. Where it is checked out, run:"
  echo "  git merge --ff-only $BASE_REF"
  exit 1
fi

echo "ERROR: $STALE"
echo "It has $(git rev-list --count "$REMOTE..$LOCAL") commit(s) that $BASE_REF lacks, and Sonar would take them as already in it."
echo "Push them, or, when you do not need them, drop them with:"
echo "  git branch -f $NAME $BASE_REF"
exit 1
