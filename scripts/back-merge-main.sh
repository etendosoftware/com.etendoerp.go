#!/usr/bin/env bash
# scripts/back-merge-main.sh
#
# Opens the main -> develop PR as soon as develop lacks one of main's commits (ETP-5904), and lets
# it merge itself (ETP-5950). Whatever enters main (a hotfix, a fix or revert made on the daily
# cut, the cut's own merge commit) comes back to develop at once (etendo_schema_forge
# docs/branch-workflow.md § Back to develop). That content was already reviewed and passed the
# full pipeline on its way into main, so the PR needs no second human step unless it conflicts.
# Run by .github/workflows/back-merge-main.yml on every push to main.
#
# Needs origin/develop and origin/main fetched, and gh authenticated for the repository with the
# token that opens the PR (GH_TOKEN, GH_REPO). APPROVE_TOKEN, optional, is a second identity that
# approves the PR when develop requires a review: GitHub never lets the PR's author approve it.
#
# The PR is assigned to the GitHub user of main's last commit (after a cut or a hotfix, its merge
# commit: whoever merged it). Without conflicts it gets the approval and auto-merge, so GitHub
# merges it once the required checks pass; with conflicts it gets neither and waits for its
# assignee. An already open PR gets whatever of that it lacks.
#
# Exit: 0 PR opened or updated, or nothing to bring back · other: a git or gh failure

set -euo pipefail

BASE="develop"
HEAD="main"
DOC="https://github.com/etendosoftware/etendo_schema_forge/blob/develop/docs/branch-workflow.md#back-to-develop"

if [ "$(git rev-list --count "origin/$BASE..origin/$HEAD")" -eq 0 ]; then
  echo "origin/$BASE already contains origin/$HEAD: nothing to bring back."
  exit 0
fi

# git merge-tree: 0 merges cleanly, 1 conflicts (tree id, then the conflicted paths), other: error.
set +e
MERGE="$(git merge-tree --write-tree --name-only --no-messages "origin/$BASE" "origin/$HEAD")"
MERGE_RC=$?
set -e
[ "$MERGE_RC" -le 1 ] || exit "$MERGE_RC"

# Who moved main: the author of its last commit, else whoever merged that commit's PR. Never the
# bot that opens the PR. Empty when neither resolves (a failed lookup leaves the PR unassigned).
MAIN_SHA="$(git rev-parse "origin/$HEAD")"
MERGED_NUMBER="$(gh api "repos/{owner}/{repo}/commits/$MAIN_SHA/pulls" --jq '.[0].number // empty' 2>/dev/null || true)"
ASSIGNEE="$(gh api "repos/{owner}/{repo}/commits/$MAIN_SHA" --jq '.author.login // empty' 2>/dev/null || true)"
if [ -z "$ASSIGNEE" ] && [ -n "$MERGED_NUMBER" ]; then
  ASSIGNEE="$(gh pr view "$MERGED_NUMBER" --json mergedBy --jq '.mergedBy.login // empty' 2>/dev/null || true)"
fi
BOT="$(gh api user --jq .login 2>/dev/null || true)"
[ "$ASSIGNEE" != "$BOT" ] || ASSIGNEE=""

URL="$(gh pr list --base "$BASE" --head "$HEAD" --state open --json url --jq '.[0].url // empty')"
if [ -n "$URL" ]; then
  echo "Already open, and it follows $HEAD by itself: $URL"
else
  BODY="$(mktemp)"
  trap 'rm -f "$BODY"' EXIT
  OWN="$(git log --no-merges --format='- %h %s' "origin/$BASE..origin/$HEAD")"
  {
    echo "Brings what entered \`$HEAD\` back to \`$BASE\` ([Back to develop]($DOC))."
    [ -z "$MERGED_NUMBER" ] || echo "\`$HEAD\` moved with #$MERGED_NUMBER."
    echo ""
    if [ -n "$OWN" ]; then
      echo "Commits that \`$BASE\` does not have yet:"
      echo ""
      echo "$OWN"
    else
      echo "Only merge commits, no code that \`$BASE\` lacks. Merging it keeps step 1 of the next cut empty."
    fi
    echo ""
    if [ "$MERGE_RC" -eq 0 ]; then
      echo "It merges itself once the required checks pass: that code was already reviewed on its way into \`$HEAD\`."
    else
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
fi

if [ -n "$ASSIGNEE" ] && [ "$(gh pr view "$URL" --json assignees --jq '.assignees | length')" -eq 0 ]; then
  gh pr edit "$URL" --add-assignee "$ASSIGNEE" \
    && echo "Assigned to $ASSIGNEE." \
    || echo "::warning::Could not assign $URL to $ASSIGNEE."
fi

if [ "$MERGE_RC" -ne 0 ]; then
  echo "::warning::$URL conflicts with $BASE: no approval or auto-merge, it waits for ${ASSIGNEE:-a human}."
  exit 0
fi

if [ -n "${APPROVE_TOKEN:-}" ] && [ "$(gh pr view "$URL" --json reviewDecision --jq .reviewDecision)" != "APPROVED" ]; then
  GH_TOKEN="$APPROVE_TOKEN" gh pr review "$URL" --approve \
    --body "Back-merge of what entered \`$HEAD\`, already reviewed on its way there." \
    && echo "Approved." \
    || echo "::warning::Could not approve $URL."
fi

if [ "$(gh pr view "$URL" --json autoMergeRequest --jq '.autoMergeRequest != null')" != "true" ]; then
  gh pr merge "$URL" --auto --merge \
    && echo "Auto-merge enabled: it merges once the required checks pass." \
    || echo "::warning::Could not enable auto-merge on $URL."
fi
