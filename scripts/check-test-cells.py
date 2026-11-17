#!/usr/bin/env python3
"""CI check: every JUnit test class under src-test/src belongs to exactly one cell file.

Cells are the product teams (ventas, compras, almacenes, contabilidad, financiero, localizacion,
plataforma). Each one is src-test/cells/<cell>.txt, one fully qualified class name per line;
classes shared by two cells live in src-test/cells/compartidos/<group>.txt and the cells pull
them in with an `@incluye <group>` line. The per-cell test report and the Datadog board read
these files: a test of no cell reaches no team when it fails.

Same rules as `./gradlew goCellCheck` (build.gradle), which needs the whole Etendo project to run:
  1. no-cell     -- a test class that no cell file lists
  2. stale       -- a cell file lists a class that no longer exists
  3. duplicated  -- a class listed in more than one file
  4. bad-include -- `@incluye <group>` naming a file that does not exist

A test class is a .java file under src-test/src with @Test or @ParameterizedTest that is not abstract.

Usage:
  scripts/check-test-cells.py [--root <module dir>]
Prints a ::error per finding and exits 1 when there is any.
"""

import argparse
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
CELLS_DIR = os.path.join('src-test', 'cells')
SHARED = 'compartidos'
TESTS_DIR = os.path.join('src-test', 'src')

TEST_ANNOTATION_RE = re.compile(r'@(org\.junit\.(jupiter\.api\.)?)?(Test|ParameterizedTest)\b')
ABSTRACT_RE = re.compile(r'abstract\s+class')
INCLUDE = '@incluye '


def read_cell_files(root):
    """{'ventas': [...], 'compartidos/ventas-compras': [...]}: the lines of each file, comments dropped."""
    files = {}
    for sub, prefix in (('', ''), (SHARED, SHARED + '/')):
        directory = os.path.join(root, CELLS_DIR, sub)
        if not os.path.isdir(directory):
            continue
        for name in sorted(os.listdir(directory)):
            path = os.path.join(directory, name)
            if os.path.isfile(path) and name.endswith('.txt'):
                with open(path, encoding='utf-8') as f:
                    lines = [line.strip() for line in f]
                files[prefix + name[:-len('.txt')]] = [l for l in lines if l and not l.startswith('#')]
    return files


def is_test_class(src):
    return bool(TEST_ANNOTATION_RE.search(src)) and not ABSTRACT_RE.search(src)


def find_test_classes(root):
    """Fully qualified names of the test classes under src-test/src."""
    base = os.path.join(root, TESTS_DIR)
    classes = set()
    for dirpath, _, names in os.walk(base):
        for name in names:
            if not name.endswith('.java'):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding='utf-8', errors='replace') as f:
                if is_test_class(f.read()):
                    classes.add(os.path.relpath(path, base)[:-len('.java')].replace(os.sep, '.'))
    return classes


def check(files, existing):
    """Findings as (kind, file, message), in a stable order."""
    findings = []
    owners = {}
    for name, lines in sorted(files.items()):
        for line in lines:
            if line.startswith(INCLUDE):
                group = SHARED + '/' + line[len(INCLUDE):].strip()
                if group not in files:
                    findings.append(('bad-include', name, '@incluye {}: {}.txt does not exist'.format(group, group)))
            else:
                owners.setdefault(line, []).append(name)
    for cls in sorted(existing - set(owners)):
        findings.append(('no-cell', None, '{} is in no cell: add it to src-test/cells/<cell>.txt'.format(cls)))
    for cls in sorted(set(owners) - existing):
        for name in owners[cls]:
            findings.append(('stale', name, '{} is listed but is no longer a test class'.format(cls)))
    for cls, names in sorted(owners.items()):
        if len(names) > 1:
            findings.append(('duplicated', names[0], '{} is listed in {}'.format(cls, ', '.join(names))))
    return findings


def format_annotation(finding):
    kind, name, message = finding
    location = 'file={}/{}.txt,'.format(CELLS_DIR, name) if name else ''
    return '::error {}title=test-cells/{}::{}'.format(location, kind, message)


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--root', default=ROOT)
    args = parser.parse_args(argv)
    existing = find_test_classes(args.root)
    findings = check(read_cell_files(args.root), existing)
    for finding in findings:
        print(format_annotation(finding))
    if findings:
        print('{} problem(s) in src-test/cells/. Cells: see the header of each file.'.format(len(findings)))
        return 1
    print('OK: {} test classes, each in exactly one cell file.'.format(len(existing)))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
