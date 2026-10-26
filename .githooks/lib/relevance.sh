#!/usr/bin/env bash
# .githooks/lib/relevance.sh
#
# Single source of truth for "which checks does a change require?" in this repo.
# Sourced by BOTH commit-msg (to stamp the per-push `Checks:` trailer) and pre-push
# (to decide which steps to run), so the two can never drift apart.
#
#   relevance_checks : reads changed-file paths (one per line) on stdin and prints
#                      the required checks as space-separated tokens, a subset of:
#                          xml junit coverage scripts
#                      Empty output = nothing to run (e.g. docs-only change).
#
# Rules (mirror sonar-project.properties + the team's agreed relevance table):
#   xml      ← src-db/database/sourcedata/**, check-etgo-xml.sh
#   junit    ← src/, src-test/, the WHOLE src-db/, web/, referencedata/, the gradle
#              build, sonar-project.properties
#   coverage ← src/ (sonar.sources), src-test/ (moves the coverage delta),
#              sonar-project.properties
#   scripts  ← scripts/**, .github/** → the scripts' Python unit tests, the ones CI's
#              unit-test-classification.yml runs (CI validates the workflows themselves)
#   RUN-ALL  ← .githooks/**, run-sonar.sh and the scripts the pre-push itself runs
#              (check-sonar-issues.sh, scripts/check-diff-coverage.py,
#              scripts/check-test-hygiene.py, scripts/check-unit-test-classification.sh)
#              → everything (validate the tooling itself)
#   SAFE     ← docs, images, Makefile, .gitignore, the agent configuration (.claude/,
#              .codex/, .omp/, .opencode/, .agents/)  → nothing
#   Any file matching NONE of the known buckets → everything (golden rule: when in
#   doubt, run).

RELEVANCE_RE_RUN_ALL='^\.githooks/|^run-sonar\.sh$|^check-sonar-issues\.sh$|^scripts/check-(diff-coverage\.py|test-hygiene\.py|unit-test-classification\.sh)$'
RELEVANCE_RE_SAFE='(^|/)[^/]*\.md$|^docs/|^legal/|(^|/)LICENSE|^\.gitignore$|^\.gitattributes$|\.(png|jpe?g|gif|svg|ico|webp)$|^Makefile$'
RELEVANCE_RE_XML='^src-db/database/sourcedata/|^check-etgo-xml\.sh$'
RELEVANCE_RE_JUNIT='^src/|^src-test/|^src-db/|^web/|^referencedata/|(^|/)[^/]*\.gradle$|^gradle\.properties$|^gradle/|^gradlew|^sonar-project\.properties$'
RELEVANCE_RE_COVER='^src/|^src-test/|^sonar-project\.properties$'
RELEVANCE_RE_SCRIPTS='^scripts/|^\.github/'
RELEVANCE_RE_AGENTS='^\.(claude|codex|omp|opencode|agents)/'
RELEVANCE_RE_KNOWN="${RELEVANCE_RE_RUN_ALL}|${RELEVANCE_RE_SAFE}|${RELEVANCE_RE_XML}|${RELEVANCE_RE_JUNIT}|${RELEVANCE_RE_COVER}|${RELEVANCE_RE_SCRIPTS}|${RELEVANCE_RE_AGENTS}"

# relevance_checks < changed-files-on-stdin  → prints the "xml junit coverage scripts" subset.
relevance_checks() {
  local files
  files="$(grep -Ev '^[[:space:]]*$' || true)"   # read stdin, drop blank lines
  [ -n "$files" ] || return 0                     # nothing changed → no checks

  # Golden rule: any file matching NO known bucket, OR a RUN-ALL tooling file →
  # run everything.
  if printf '%s\n' "$files" | grep -Evq "$RELEVANCE_RE_KNOWN" \
     || printf '%s\n' "$files" | grep -Eq "$RELEVANCE_RE_RUN_ALL"; then
    printf 'xml junit coverage scripts'
    return 0
  fi

  local out=""
  printf '%s\n' "$files" | grep -Eq "$RELEVANCE_RE_XML"   && out="$out xml"
  printf '%s\n' "$files" | grep -Eq "$RELEVANCE_RE_JUNIT" && out="$out junit"
  printf '%s\n' "$files" | grep -Eq "$RELEVANCE_RE_COVER" && out="$out coverage"
  printf '%s\n' "$files" | grep -Eq "$RELEVANCE_RE_SCRIPTS" && out="$out scripts"
  printf '%s' "${out# }"
}
