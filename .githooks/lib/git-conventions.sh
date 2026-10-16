#!/usr/bin/env bash
# .githooks/lib/git-conventions.sh
#
# The Etendo conventions Git Police enforces on a PR, checked locally (ETP-5695) so a wrong commit
# message or branch shows up at commit or push time, not after a push that then has to be rewritten.
# CI keeps Git Police: hooks can be skipped, and only Git Police sees the PR title.
#
#   commit_message_problem <message file>  why the message breaks the convention, or nothing
#   branch_problem <branch> <target>       "error: …" (blocks the push), "warning: …" or nothing
#
# Commit: "Feature|Epic|Hotfix ABC-1234: …", or "Issue #42: …" with the Jira key in the body; the
# first line at most 80 characters. Branch: feature/, epic/ or hotfix/ plus the ticket (optionally
# "-Y26" for a backport, "#42-" for a hotfix with a GitHub issue), or mergeblock/ABC-1234. Only
# develop, hotfix/* and mergeblock/* may target main (.github/workflows/branch-merge-policy.yml).

GIT_CONVENTION_TICKET='[A-Z]{2,4}-[0-9]{1,4}'

commit_message_problem() {
  local file="$1" subject
  subject="$(grep -v '^#' "$file" | sed '/^[[:space:]]*$/d' | head -1)"
  case "$subject" in
    "Merge "*|"fixup! "*|"squash! "*|"amend! "*) return 0 ;;   # made by git, or squashed away
  esac
  if [ "${#subject}" -gt 80 ]; then
    echo "its first line has ${#subject} characters, the limit is 80"
  elif printf '%s' "$subject" | grep -qE "^(Feature|Epic|Hotfix) ${GIT_CONVENTION_TICKET}: [^ ]"; then
    return 0
  elif printf '%s' "$subject" | grep -qE '^Issue #[0-9]{1,6}: [^ ]'; then
    grep -v '^#' "$file" | awk 'seen { print } /[^[:space:]]/ && !seen { seen = 1 }' \
      | grep -qE "$GIT_CONVENTION_TICKET" \
      || echo "an 'Issue #N:' commit needs its Jira key (ETP-1234) in the body"
  else
    echo "its first line must start with 'Feature ETP-1234: ', 'Epic ETP-1234: ', 'Hotfix ETP-1234: ' or 'Issue #42: '"
  fi
}

branch_problem() {
  local branch="$1" target="$2" t="$GIT_CONVENTION_TICKET"
  case "$branch" in ""|develop|main) return 0 ;; esac
  if [ "$target" = "main" ]; then
    case "$branch" in
      hotfix/*|mergeblock/*) ;;
      *) echo "error: $branch cannot target main: only develop, hotfix/* and mergeblock/* can"; return 0 ;;
    esac
  fi
  printf '%s' "$branch" | grep -qE \
    "^(feature|epic)/${t}(-Y[0-9]{2})?$|^hotfix/(#[0-9]{1,6}-)?${t}(-Y[0-9]{2})?$|^mergeblock/${t}$" \
    || echo "warning: $branch is not feature/ETP-1234, epic/ETP-1234, hotfix/#42-ETP-1234 or mergeblock/ETP-1234; Git Police may reject the PR"
}
