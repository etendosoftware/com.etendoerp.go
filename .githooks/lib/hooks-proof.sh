#!/usr/bin/env bash
# .githooks/lib/hooks-proof.sh
#
# Shared helpers for the "were the hooks actually installed and run?" marker.
#
# How it works
# ------------
#   pre-commit  → after its checks pass, writes a proof file with the tree that
#                 is about to be committed + the hook version that validated it.
#   commit-msg  → reads that proof and, when it matches the tree being committed,
#                 appends a trailer to the commit message:
#
#                     Hooks-Verified: v1 <hooks-version> <fingerprint>
#
#   pre-push    → recomputes the fingerprint for every commit being pushed and
#                 refuses the push when it is missing or does not match.
#   CI          → recomputes the same fingerprint server-side (this is the only
#                 check that cannot be bypassed — see docs/HOOKS-VERIFICATION.md).
#
# The fingerprint is derived from the commit's *tree*, so the trailer cannot be
# copy-pasted from another commit and does not survive an amend that changes
# content. It is an honest-mistake detector, not a security boundary: anything
# client-side can be skipped with --no-verify, which is exactly why the CI check
# exists.
#
# Portability: uses `git hash-object` for hashing so it behaves identically on
# macOS and Linux (no shasum/sha1sum differences).

HOOKS_TRAILER_KEY="Hooks-Verified"
HOOKS_PROOF_FORMAT="v1"

# Absolute path of the .githooks directory this library lives in.
_hooks_dir() {
  cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd
}

# Path of the proof file (inside .git, never committed).
hooks_proof_file() {
  printf '%s/hooks-proof' "$(git rev-parse --git-dir)"
}

# Short hash identifying the current version of the hook scripts. Recorded in
# the trailer so a later hook change does not invalidate older commits.
hooks_version() {
  local dir
  dir="$(_hooks_dir)"
  {
    for f in pre-commit commit-msg pre-push lib/hooks-proof.sh; do
      [ -f "$dir/$f" ] && cat "$dir/$f"
    done
  } | git hash-object --stdin | cut -c1-8
}

# hooks_fingerprint <tree-sha> <hooks-version> → 12-char fingerprint
hooks_fingerprint() {
  printf '%s %s' "$1" "$2" | git hash-object --stdin | cut -c1-12
}

# Record that the pre-commit checks passed for the currently staged tree.
# Called at the end of pre-commit, only on success.
hooks_write_proof() {
  local tree ver
  tree="$(git write-tree)" || return 0
  ver="$(hooks_version)"
  printf '%s %s\n' "$tree" "$ver" > "$(hooks_proof_file)"
}

# Read the proof; prints "<tree> <version>" when present.
hooks_read_proof() {
  local f
  f="$(hooks_proof_file)"
  [ -f "$f" ] && cat "$f"
}

hooks_clear_proof() {
  rm -f "$(hooks_proof_file)"
}

# Extract the trailer value of a commit message file or a commit sha.
# hooks_trailer_of_file <msg-file> | hooks_trailer_of_commit <sha>
hooks_trailer_of_file() {
  grep -E "^${HOOKS_TRAILER_KEY}:[[:space:]]*" "$1" 2>/dev/null | tail -1 \
    | sed -E "s/^${HOOKS_TRAILER_KEY}:[[:space:]]*//"
}

hooks_trailer_of_commit() {
  git log -1 --format=%B "$1" 2>/dev/null \
    | grep -E "^${HOOKS_TRAILER_KEY}:[[:space:]]*" | tail -1 \
    | sed -E "s/^${HOOKS_TRAILER_KEY}:[[:space:]]*//"
}

# hooks_verify_commit <sha> → 0 ok · 1 missing trailer · 2 fingerprint mismatch
# Merge commits are skipped (they are created without a pre-commit run).
hooks_verify_commit() {
  local sha="$1" trailer fmt ver fp tree expected
  # More than one parent → merge commit, nothing to verify.
  if [ "$(git rev-list --parents -n1 "$sha" | wc -w | tr -d ' ')" -gt 2 ]; then
    return 0
  fi
  trailer="$(hooks_trailer_of_commit "$sha")"
  [ -z "$trailer" ] && return 1

  read -r fmt ver fp <<< "$trailer"
  [ "$fmt" = "$HOOKS_PROOF_FORMAT" ] || return 2
  tree="$(git rev-parse "${sha}^{tree}")"
  expected="$(hooks_fingerprint "$tree" "$ver")"
  [ "$fp" = "$expected" ] || return 2
  return 0
}
