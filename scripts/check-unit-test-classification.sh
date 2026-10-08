#!/usr/bin/env bash
#
# Verifies the split that the `goTestUnit` Gradle task relies on (see build.gradle).
#
# `goTestUnit` runs this module's Java tests with no database, selecting them by name: every
# `*IntegrationTest` is excluded, plus two classes that predate that convention. A test that needs
# the DAL but is not named that way would be picked up by `goTestUnit` and fail with an
# OBBaseTest/DAL stack trace in a pipeline that has no database — a failure that reads like broken
# infrastructure rather than a misnamed test. This script turns that into an explicit, early error.
#
# Exit 0: every DB-backed test class is excluded from the unit suite.
# Exit 1: at least one is not. The offender is printed with the rename that fixes it.
set -euo pipefail

cd "$(dirname "$0")/.."
TEST_ROOT="src-test/src"

# Base classes whose @BeforeClass initializes the DAL layer, so any subclass needs a live database.
DAL_BASES="OBBaseTest WeldBaseTest"

# Excluded by name in build.gradle because docs/ refers to them in prose. Keep both lists in sync.
ALLOWED_EXCEPTIONS="UserRoleCompositionServiceOverlapReverificationTest BusinessPartnerHandlerDbTest"

if [ ! -d "$TEST_ROOT" ]; then
  echo "check-unit-test-classification: $TEST_ROOT not found" >&2
  exit 1
fi

# class<TAB>superclass, one per declared type. Javadoc and line comments are dropped first so a
# base class merely *named* in a comment is not read as an extends, and the declaration is matched
# after joining lines because `class X\n    extends Y {` is common here.
decls=$(
  find "$TEST_ROOT" -name '*.java' -print0 |
    xargs -0 -n1 sh -c '
      sed -e "s|//.*||" -e "/^[[:space:]]*\*/d" -e "/^[[:space:]]*\/\*/d" "$0" |
        tr "\n" " " |
        grep -oE "class +[A-Za-z0-9_]+ +extends +[A-Za-z0-9_]+" |
        awk "{print \$2 \"\t\" \$4}"
    ' 2>/dev/null
)

# Transitive closure: a subclass of a local abstract DAL test is DB-backed too.
db_classes="$DAL_BASES"
while : ; do
  added=0
  while IFS="$(printf '\t')" read -r cls super; do
    [ -n "${cls:-}" ] || continue
    case " $db_classes " in *" $cls "*) continue ;; esac
    case " $db_classes " in
      *" $super "*)
        db_classes="$db_classes $cls"
        added=1
        ;;
    esac
  done <<< "$decls"
  [ "$added" -eq 0 ] && break
done

offenders=""
for cls in $db_classes; do
  case " $DAL_BASES " in *" $cls "*) continue ;; esac
  case "$cls" in *IntegrationTest) continue ;; esac
  case " $ALLOWED_EXCEPTIONS " in *" $cls "*) continue ;; esac
  offenders="$offenders $cls"
done

total=$(echo "$db_classes" | wc -w)
echo "check-unit-test-classification: $((total - 2)) DB-backed test classes found under $TEST_ROOT"

if [ -n "$offenders" ]; then
  echo
  echo "These test classes reach a DAL base class but are not excluded from the unit suite:" >&2
  for cls in $offenders; do
    echo "  - $cls  → rename to ${cls%Test}IntegrationTest" >&2
  done
  echo >&2
  echo "They would run in 'goTestUnit', which has no database. Rename them, or add them to" >&2
  echo "dbBackedGoTests in build.gradle and to ALLOWED_EXCEPTIONS in this script." >&2
  exit 1
fi

echo "check-unit-test-classification: OK — every DB-backed test is excluded from goTestUnit"
