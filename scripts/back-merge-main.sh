#!/usr/bin/env bash
# scripts/back-merge-main.sh
#
# Opens the main -> develop PR as soon as develop lacks one of main's commits (ETP-5904).
# Whatever enters main (a hotfix, a fix or revert made on the daily cut, the cut's own merge
# commit) comes back to develop at once (etendo_schema_forge docs/branch-workflow.md § Back to
# develop).
# Run by .github/workflows/back-merge-main.yml on every push to main.
#
# Needs origin/develop and origin/main fetched, and gh authenticated for the repository
# (GH_TOKEN, GH_REPO). MERGED_SHA, optional, is the commit that moved main: the author of its
# PR is asked to review.
#
# Exit: 0 PR opened, already open, or nothing to bring back · other: a git or gh failure

set -euo pipefail

BASE="develop"
HEAD="main"
DOC="https://github.com/etendosoftware/etendo_schema_forge/blob/develop/docs/branch-workflow.md#back-to-develop"

if [ "$(git rev-list --count "origin/$BASE..origin/$HEAD")" -eq 0 ]; then
  echo "origin/$BASE already contains origin/$HEAD: nothing to bring back."
  exit 0
fi

OPEN="$(gh pr list --base "$BASE" --head "$HEAD" --state open --json url --jq '.[0].url // empty')"
if [ -n "$OPEN" ]; then
  echo "Already open, and it follows $HEAD by itself: $OPEN"
  exit 0
fi

# The PR that moved main, as "<number> <author>"; empty for a direct push or a failed lookup.
MERGED_PR=""
if [ -n "${MERGED_SHA:-}" ]; then
  MERGED_PR="$(gh api "repos/{owner}/{repo}/commits/$MERGED_SHA/pulls" \
    --jq '.[0] // empty | "\(.number) \(.user.login)"' 2>/dev/null || true)"
fi
read -r MERGED_NUMBER AUTHOR <<< "$MERGED_PR" || true

# git merge-tree: 0 merges cleanly, 1 conflicts (tree id, then the conflicted paths), other: error.
set +e
MERGE="$(git merge-tree --write-tree --name-only --no-messages "origin/$BASE" "origin/$HEAD")"
MERGE_RC=$?
set -e
[ "$MERGE_RC" -le 1 ] || exit "$MERGE_RC"

BODY="$(mktemp)"
trap 'rm -f "$BODY"' EXIT
OWN="$(git log --no-merges --format='- %h %s' "origin/$BASE..origin/$HEAD")"
{
  echo "Brings what entered \`$HEAD\` back to \`$BASE\` ([Back to develop]($DOC))."
  [ -z "${MERGED_NUMBER:-}" ] || echo "\`$HEAD\` moved with #$MERGED_NUMBER."
  echo ""
  if [ -n "$OWN" ]; then
    echo "Commits that \`$BASE\` does not have yet:"
    echo ""
    echo "$OWN"
  else
    echo "Only merge commits, no code that \`$BASE\` lacks. Merging it keeps step 1 of the next cut empty."
  fi
  if [ "$MERGE_RC" -eq 1 ]; then
    echo ""
    echo "### Conflicts"
    echo ""
    echo "Merging \`$HEAD\` into \`$BASE\` conflicts in:"
    echo ""
    echo "$MERGE" | tail -n +2 | sed 's/^/- /'
    echo ""
    echo "\`$HEAD\` is protected and cannot take the resolution. Resolve it on a branch created from"
    echo "\`$BASE\` with \`origin/$HEAD\` merged in, and open that branch's PR to \`$BASE\`:"
    echo ""
    echo '```bash'
    echo "git switch -c feature/ETP-XXXX origin/$BASE"
    echo "git merge origin/$HEAD"
    echo '```'
    echo ""
    echo "Once it is merged, this PR has nothing left to bring and can be closed."
  fi
} > "$BODY"

URL="$(gh pr create --base "$BASE" --head "$HEAD" --title "Merge $HEAD into $BASE" --body-file "$BODY")"
echo "Opened $URL"
if [ -n "${AUTHOR:-}" ]; then
  gh pr edit "$URL" --add-reviewer "$AUTHOR" \
    || echo "::warning::Could not request a review from $AUTHOR on $URL."
fi
