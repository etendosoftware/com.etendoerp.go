#!/usr/bin/env python3
"""CI check for the reuse-first test protocol on the JUnit classes a PR adds or modifies.

The protocol itself lives in etendo_schema_forge: docs/testing/test-reuse-policy.md.

Checks, for every added/modified src-test/**/*Test.java:
  1. missing-covers     -- no `@covers <FQN>` inside a Javadoc comment
  2. covers-not-found   -- `@covers` names a class with no source file under src/
  3. ticket-named       -- a NEW file whose name contains etp-?NNNN (case-insensitive)

Usage:
  scripts/check-test-hygiene.py --base <sha> --head <sha> [--mode annotate|block]

Mode (flag or TEST_HYGIENE_MODE env): `annotate` prints ::warning and exits 0;
`block` prints ::error and exits 1 when anything is found.
"""

import argparse
import glob
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))

TEST_FILE_RE = re.compile(r'^src-test/.+Test\.java$')
TICKET_NAME_RE = re.compile(r'etp-?\d{4}', re.IGNORECASE)
JAVADOC_RE = re.compile(r'/\*\*.*?\*/', re.DOTALL)
# A tag counts only when it opens a Javadoc line (`/** @covers x */` or ` * @covers x`),
# so prose that mentions @covers inside the comment is ignored.
COVERS_LINE_RE = re.compile(r'^\s*(?:/\*\*|\*+)?\s*@covers\s+([\w.$]+)\s*(?:\*/)?\s*$')


def is_test_file(path):
    return bool(TEST_FILE_RE.match(path))


def is_ticket_named(path):
    return bool(TICKET_NAME_RE.search(os.path.basename(path)))


def parse_covers(src):
    """Every `@covers <FQN>` found inside a Javadoc comment, with its 1-based line."""
    found = []
    for block in JAVADOC_RE.finditer(src):
        first_line = src.count('\n', 0, block.start()) + 1
        for offset, text in enumerate(block.group(0).split('\n')):
            match = COVERS_LINE_RE.match(text)
            if match:
                found.append((match.group(1), first_line + offset))
    return found


def find_source_roots(root):
    """`src` plus every Java source root under src-util (e.g. src-util/modulescript/src)."""
    found = sorted(os.path.relpath(path, root).replace(os.sep, '/')
                   for path in glob.glob(os.path.join(root, 'src-util', '*', 'src'))
                   if os.path.isdir(path))
    return ['src'] + found


def class_exists(fqn, path_exists, source_roots=('src',)):
    """True if `fqn` (or its outer class, for a nested `Outer.Inner`) has a file under a source root."""
    parts = fqn.replace('$', '.').split('.')
    while parts:
        rel = '/'.join(parts) + '.java'
        if any(path_exists(root + '/' + rel) for root in source_roots):
            return True
        if len(parts) < 2 or not parts[-2][:1].isupper():
            return False
        parts = parts[:-1]
    return False


def check_file(path, status, src, path_exists, source_roots=('src',)):
    """Pure core: the findings for one changed test file."""
    findings = []
    covers = parse_covers(src)
    if not covers:
        findings.append((path, 1, 'missing-covers',
                         'test class has no `@covers <fully.qualified.Class>` in its Javadoc'))
    for fqn, line in covers:
        if not class_exists(fqn, path_exists, source_roots):
            findings.append((path, line, 'covers-not-found',
                             '@covers names a class with no source under ' + ', '.join(source_roots) + ': ' + fqn))
    if status in ('A', 'R') and is_ticket_named(path):
        findings.append((path, 1, 'ticket-named',
                         'new test file is named after a ticket; name it by behavior '
                         '(the ticket goes in the commit message)'))
    return findings


def format_annotation(finding, mode):
    path, line, rule, message = finding
    level = 'error' if mode == 'block' else 'warning'
    return '::{} file={},line={},title=test-hygiene/{}::{}'.format(level, path, line, rule, message)


def parse_name_status(output):
    """`git diff --name-status` output -> [(status, path)] for test files."""
    rows = []
    for row in output.splitlines():
        if not row.strip():
            continue
        parts = row.split('\t')
        status, path = parts[0][0], parts[-1]
        if status in ('A', 'M', 'R') and is_test_file(path):
            rows.append((status, path))
    return rows


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, check=True,
                          capture_output=True, text=True).stdout


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--base', required=True)
    parser.add_argument('--head', required=True)
    parser.add_argument('--mode', default=os.environ.get('TEST_HYGIENE_MODE', 'annotate'),
                        choices=('annotate', 'block'))
    args = parser.parse_args(argv)

    rng = args.base + '...' + args.head
    roots = find_source_roots(ROOT)

    def path_exists(rel):
        return os.path.isfile(os.path.join(ROOT, rel))

    try:
        changed = parse_name_status(git('diff', '--name-status', '-M', rng))
        findings = []
        for status, path in changed:
            src = git('show', args.head + ':' + path)
            findings.extend(check_file(path, status, src, path_exists, roots))
    except subprocess.CalledProcessError as error:
        # A git failure (shallow clone, missing ref) is an infrastructure problem, not a
        # finding: it must not fail the job while the check is annotate-only.
        level = 'error' if args.mode == 'block' else 'warning'
        reason = ((error.stderr or '').strip().splitlines() or [str(error)])[0]
        print('::{} title=test-hygiene/git-error::could not read the diff {}: {}'.format(level, rng, reason))
        return 1 if args.mode == 'block' else 0

    for finding in findings:
        print(format_annotation(finding, args.mode))
    print('test-hygiene: {} changed test file(s), {} finding(s) [mode: {}]'.format(
        len(changed), len(findings), args.mode))
    if findings:
        print('See docs/testing/test-reuse-policy.md in etendo_schema_forge')
    return 1 if findings and args.mode == 'block' else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
