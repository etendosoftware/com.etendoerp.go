#!/usr/bin/env bash
# .githooks/lib/step-cache.sh
#
# Content-addressed cache for pre-push steps. Mirrored verbatim across repos —
# edit it in one and sync, never fork the logic.
#
# WHY THIS IS CHEAP
# The pre-push hook hard-fails on a dirty working tree, so by the time a step
# runs, the working tree IS the HEAD commit. That makes `git rev-parse HEAD:<path>`
# an exact content key for a file or an entire subtree, in ~17ms, with no manual
# hashing and no risk of hashing something the step will not actually read.
#
# OPT-IN BY DESIGN
# Every function is inert unless PRE_PUSH_CACHE=1 is exported. A plain `git push`
# behaves exactly as it did before this file existed: no skips, no new output, no
# new files. Only tooling that deliberately opts in gets the cache.
#
# WHAT MUST NEVER BE CACHED
# Any step whose verdict depends on state outside the working tree. In particular
# every Sonar step: the Quality Gate is computed server-side against the base
# branch's analysis, so an unchanged tree yields a different verdict once the base
# moves. Caching it would manufacture false greens, which is strictly worse than
# being slow.

STEP_CACHE_FILE="${STEP_CACHE_FILE:-$REPO_DIR/tmp/pre-push/step-cache}"

step_cache_enabled() { [ "${PRE_PUSH_CACHE:-0}" = "1" ]; }

# step_cache_key <tools-fingerprint> <input>...
#
# Each <input> is either `path` (this repo) or `<repo dir>|<path>` for an input
# owned by a sibling checkout. Prints a sha256 over every input's content sha plus
# the tools fingerprint.
#
# Returns non-zero — and prints nothing — if ANY input cannot be resolved. That is
# the fail-open path: an unresolvable key means the caller runs the step. A cache
# miss costs seconds; a false hit costs correctness.
#
# The tools fingerprint is not decoration. An identical XML tree passed under
# python 3.13 and failed under Homebrew's python@3.14 (broken pyexpat) on the very
# same commit. A key built only from file contents would have cached that verdict
# and hidden the breakage.
step_cache_key() {
  local tools="$1"; shift
  local acc="tools=${tools}" input dir path sha

  for input in "$@"; do
    if [[ "$input" == *"|"* ]]; then
      dir="${input%%|*}"
      path="${input##*|}"
    else
      dir="$REPO_DIR"
      path="$input"
    fi
    sha="$(git -C "$dir" rev-parse "HEAD:${path}" 2>/dev/null)" || return 1
    [ -n "$sha" ] || return 1
    acc="${acc};${input}=${sha}"
  done

  printf '%s' "$acc" | shasum -a 256 2>/dev/null | cut -d' ' -f1
}

# step_cache_lookup <step> <key> — zero exit means "this exact key already passed".
step_cache_lookup() {
  local step="$1" key="$2"
  [ -n "$key" ] || return 1
  [ -f "$STEP_CACHE_FILE" ] || return 1
  grep -qxF "step-${step}=${key}" "$STEP_CACHE_FILE" 2>/dev/null
}

# step_cache_store <step> <key> — record a pass. Best-effort: an unwritable cache
# must never turn a green push red, so every failure path is swallowed.
step_cache_store() {
  local step="$1" key="$2"
  [ -n "$key" ] || return 0
  mkdir -p "$(dirname "$STEP_CACHE_FILE")" 2>/dev/null || return 0
  if [ -f "$STEP_CACHE_FILE" ]; then
    grep -v "^step-${step}=" "$STEP_CACHE_FILE" > "${STEP_CACHE_FILE}.tmp" 2>/dev/null || true
    mv "${STEP_CACHE_FILE}.tmp" "$STEP_CACHE_FILE" 2>/dev/null || true
  fi
  echo "step-${step}=${key}" >> "$STEP_CACHE_FILE" 2>/dev/null || true
  return 0
}

# step_cache_invalidate <step> — drop any recorded pass for a step that just failed.
step_cache_invalidate() {
  local step="$1"
  [ -f "$STEP_CACHE_FILE" ] || return 0
  grep -v "^step-${step}=" "$STEP_CACHE_FILE" > "${STEP_CACHE_FILE}.tmp" 2>/dev/null || true
  mv "${STEP_CACHE_FILE}.tmp" "$STEP_CACHE_FILE" 2>/dev/null || true
  return 0
}

# step_cache_compute <tools> <input>... — convenience wrapper for call sites.
# Prints the key when the cache is on and every input resolved; prints nothing
# otherwise, so an empty result simply means "run this step".
step_cache_compute() {
  step_cache_enabled || return 0
  step_cache_key "$@" 2>/dev/null || true
}
