#!/usr/bin/env python3
"""Coverage of the Java lines a change touches, measured with the database-free suite (goTestUnit).

A pre-push that runs only goTestUnit cannot compare the module's overall coverage with develop:
the DB-backed tests are missing, so the overall figure always drops. This check looks instead at
the lines the change adds or modifies (git diff against the merge base) and reports how many of
the executable ones goTestUnit covers. The overall comparison stays where the full suite runs.

A changed file that goTestUnit does not cover at all, but that a DB-backed test references, is
reported as deferred: whatever covers it runs only with a database, so the full suite in CI is the
one to judge it. A file that no test references still counts, so untested code cannot hide there.

Usage:
  scripts/check-diff-coverage.py --base <ref> [--head <ref>] [--jacoco-xml PATH]
                                 [--threshold 80] [--mode annotate|block]

Produce the report first, from etendo_core:  ./gradlew goTestUnit goTestUnitCoverageReport

Mode (flag or DIFF_COVERAGE_MODE env): `annotate` prints ::warning and exits 0; `block` prints
::error and exits 1 when the covered share of the counted lines is under the threshold.
"""

import argparse
import glob
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
DEFAULT_XML = os.path.join(ROOT, '..', '..', 'build', 'reports', 'jacoco', 'goTestUnit', 'goTestUnit.xml')

# A production Java file and the path JaCoCo knows it by (package path + file name).
SOURCE_FILE_RE = re.compile(r'^(?:src|src-util/[^/]+/src)/(.+\.java)$')
HUNK_RE = re.compile(r'^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@')
EXTENDS_RE = re.compile(r'class\s+(\w+)(?:\s*<[^>{]*>)?\s+extends\s+(\w+)')
COMMENT_RE = re.compile(r'/\*.*?\*/|//[^\n]*', re.DOTALL)
IDENTIFIER_RE = re.compile(r'\b[A-Z]\w*\b')

# Base classes whose setup needs a live database (kept in sync with check-unit-test-classification.sh).
DAL_BASES = ('OBBaseTest', 'WeldBaseTest')


def parse_changed_lines(diff_text):
    """`git diff -U0` output -> {repo path: set of added/modified line numbers} for Java sources."""
    changed = {}
    current = None
    for row in diff_text.splitlines():
        if row.startswith('+++ '):
            target = row[4:]
            path = target[2:] if target.startswith('b/') else None
            current = path if path and SOURCE_FILE_RE.match(path) else None
            continue
        match = HUNK_RE.match(row)
        if match and current:
            start = int(match.group(1))
            count = int(match.group(2)) if match.group(2) is not None else 1
            changed.setdefault(current, set()).update(range(start, start + count))
    return {path: lines for path, lines in changed.items() if lines}


def parse_jacoco(xml_text):
    """JaCoCo XML -> {package path/File.java: {line number: covered?}} for executable lines only."""
    coverage = {}
    for package in ET.fromstring(xml_text).iter('package'):
        for source in package.findall('sourcefile'):
            lines = {}
            for line in source.findall('line'):
                missed, covered = int(line.get('mi', 0)), int(line.get('ci', 0))
                if missed + covered:
                    lines[int(line.get('nr'))] = covered > 0
            coverage[package.get('name') + '/' + source.get('name')] = lines
    return coverage


def db_backed_sources(test_sources):
    """The sources, among {path: text}, of test classes that reach a DAL base class."""
    declared = {}
    for path, text in test_sources.items():
        for child, parent in EXTENDS_RE.findall(COMMENT_RE.sub(' ', text)):
            declared[child] = (parent, path)
    db_classes = set(DAL_BASES)
    grew = True
    while grew:
        grew = False
        for child, (parent, _) in declared.items():
            if child not in db_classes and parent in db_classes:
                db_classes.add(child)
                grew = True
    paths = {path for child, (_, path) in declared.items()
             if child in db_classes and child not in DAL_BASES}
    return [test_sources[path] for path in sorted(paths)]


def referenced_names(sources):
    """Every capitalised identifier the given sources mention: the classes they can exercise."""
    names = set()
    for text in sources:
        names.update(IDENTIFIER_RE.findall(COMMENT_RE.sub(' ', text)))
    return names


def classify(changed, coverage, db_names):
    """Per changed file with executable changed lines: (path, covered, uncovered lines, deferred)."""
    results = []
    for path in sorted(changed):
        jacoco_path = SOURCE_FILE_RE.match(path).group(1)
        file_lines = coverage.get(jacoco_path)
        if not file_lines:
            continue  # no executable code, or a class goTestUnit's report does not know
        executable = sorted(line for line in changed[path] if line in file_lines)
        if not executable:
            continue
        uncovered = [line for line in executable if not file_lines[line]]
        class_name = os.path.basename(path)[:-len('.java')]
        deferred = not any(file_lines.values()) and class_name in db_names
        results.append((path, len(executable) - len(uncovered), uncovered, deferred))
    return results


def summarize(results):
    """(covered, counted, deferred) line totals."""
    counted = [r for r in results if not r[3]]
    covered = sum(r[1] for r in counted)
    total = covered + sum(len(r[2]) for r in counted)
    deferred = sum(r[1] + len(r[2]) for r in results if r[3])
    return covered, total, deferred


def compress(lines):
    """[3, 4, 5, 9] -> '3-5, 9'."""
    spans = []
    for line in lines:
        if spans and line == spans[-1][1] + 1:
            spans[-1][1] = line
        else:
            spans.append([line, line])
    return ', '.join(str(a) if a == b else '{}-{}'.format(a, b) for a, b in spans)


def report(results, threshold, mode):
    """The lines to print and whether the check passes."""
    covered, total, deferred = summarize(results)
    passed = total == 0 or covered * 100 >= threshold * total
    level = 'error' if mode == 'block' and not passed else 'warning'
    out = []
    for path, hit, uncovered, is_deferred in results:
        if is_deferred:
            out.append('  {}: {} changed line(s) deferred to the full suite '
                       '(only DB-backed tests reference it)'.format(path, hit + len(uncovered)))
            continue
        out.append('  {}: {}/{} changed line(s) covered'.format(path, hit, hit + len(uncovered)))
        if uncovered:
            out.append('::{} file={},line={},title=diff-coverage/uncovered::{} changed line(s) not '
                       'covered by goTestUnit: {}'.format(level, path, uncovered[0], len(uncovered),
                                                          compress(uncovered)))
    if total == 0:
        summary = 'diff-coverage: no counted changed lines to measure'
    else:
        summary = 'diff-coverage: {}/{} changed lines covered by goTestUnit ({:.1f}%), threshold {:g}%'.format(
            covered, total, 100.0 * covered / total, threshold)
    if deferred:
        summary += '; {} line(s) deferred to the full suite'.format(deferred)
    out.append(summary + ' [mode: {}]'.format(mode))
    return out, passed


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, check=True, capture_output=True,
                          encoding='utf-8', errors='replace').stdout


def read_test_sources(root):
    sources = {}
    for path in glob.glob(os.path.join(root, 'src-test', '**', '*.java'), recursive=True):
        with open(path, encoding='utf-8', errors='replace') as handle:
            sources[path] = handle.read()
    return sources


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--base', required=True)
    parser.add_argument('--head', default='HEAD')
    parser.add_argument('--jacoco-xml', default=DEFAULT_XML)
    parser.add_argument('--threshold', type=float, default=80.0)
    parser.add_argument('--mode', default=os.environ.get('DIFF_COVERAGE_MODE', 'annotate'),
                        choices=('annotate', 'block'))
    args = parser.parse_args(argv)
    failure = 1 if args.mode == 'block' else 0

    if not os.path.isfile(args.jacoco_xml):
        print('::{} title=diff-coverage/no-report::{} not found; run, from etendo_core: '
              './gradlew goTestUnit goTestUnitCoverageReport'.format(
                  'error' if failure else 'warning', args.jacoco_xml))
        return failure
    try:
        roots = ['src'] + sorted(os.path.relpath(p, ROOT) for p in glob.glob(os.path.join(ROOT, 'src-util', '*', 'src')))
        changed = parse_changed_lines(git('diff', '-U0', '--no-color', args.base + '...' + args.head, '--', *roots))
    except subprocess.CalledProcessError as error:
        reason = ((error.stderr or '').strip().splitlines() or [str(error)])[0]
        print('::{} title=diff-coverage/git-error::could not read the diff {}...{}: {}'.format(
            'error' if failure else 'warning', args.base, args.head, reason))
        return failure

    with open(args.jacoco_xml, encoding='utf-8') as handle:
        coverage = parse_jacoco(handle.read())
    report_time = os.path.getmtime(args.jacoco_xml)
    stale = [p for p in changed if os.path.isfile(os.path.join(ROOT, p))
             and os.path.getmtime(os.path.join(ROOT, p)) > report_time]
    if stale:
        print('::warning title=diff-coverage/stale-report::{} changed file(s) are newer than {}; '
              'rerun goTestUnit and goTestUnitCoverageReport'.format(len(stale), args.jacoco_xml))

    db_names = referenced_names(db_backed_sources(read_test_sources(ROOT)))
    lines, passed = report(classify(changed, coverage, db_names), args.threshold, args.mode)
    for line in lines:
        print(line)
    return 0 if passed else failure


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
