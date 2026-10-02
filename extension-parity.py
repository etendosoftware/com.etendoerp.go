#!/usr/bin/env python3
"""Offline parity report between the two NeoHandler binding mechanisms (ETP-5415, A13).

Reads the committed ``src-db/database/sourcedata/ETGO_SF_ENTITY.xml`` and
``ETGO_SF_SPEC.xml`` plus the ``@NeoExtension``-annotated Java sources, and prints which
mechanism binds each ``(spec, entity)``, every conflict between the two, and the distinct
``Java_Qualifier`` count.

No database and no build: the committed XML *is* the shipped configuration, so the report is
reproducible from a clean checkout. Precedent for reading that XML offline is ``make regen-check``
in the sibling ``schema_forge`` repo.

The distinct-qualifier count is a one-way gate. It fails above ``--ceiling`` (default 90, the
count at the time the annotation landed) because a new qualifier means somebody chose the old
mechanism with the new one available. A *decreasing* count is printed and never gated: in a
release where no handler was touched there is nothing legitimate to drop, so gating a decrease
would manufacture work instead of preventing it.

Usage:
    python3 extension-parity.py [--all] [--ceiling N]
"""

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

REPO = os.path.dirname(os.path.abspath(__file__))
SOURCEDATA = os.path.join(REPO, 'src-db', 'database', 'sourcedata')
JAVA_ROOT = os.path.join(REPO, 'src')

# Matches @NeoExtension(spec = "...", entity = "...") in either argument order, across lines.
ANNOTATION_RE = re.compile(r'@NeoExtension\s*\((?P<args>[^)]*)\)', re.S)
ARG_RE = re.compile(r'(?P<key>spec|entity)\s*=\s*"(?P<value>[^"]*)"')
NAMED_RE = re.compile(r'@Named\s*\(\s*"(?P<value>[^"]*)"\s*\)')
CLASS_RE = re.compile(r'\b(?:class|interface)\s+(?P<name>\w+)')
COMMENT_RE = re.compile(r'/\*.*?\*/|//[^\n]*', re.S)
PACKAGE_RE = re.compile(r'^\s*package\s+([\w.]+)\s*;', re.M)


def _text(row, tag):
    node = row.find(tag)
    return None if node is None or node.text is None else node.text.strip()


def read_specs():
    """ETGO_SF_SPEC_ID -> spec name."""
    tree = ET.parse(os.path.join(SOURCEDATA, 'ETGO_SF_SPEC.xml'))
    return {_text(row, 'ETGO_SF_SPEC_ID'): _text(row, 'NAME')
            for row in tree.getroot().findall('ETGO_SF_SPEC')}


def read_entities(specs):
    """List of (spec, entity, qualifier-or-None), sorted."""
    tree = ET.parse(os.path.join(SOURCEDATA, 'ETGO_SF_ENTITY.xml'))
    rows = []
    for row in tree.getroot().findall('ETGO_SF_ENTITY'):
        spec = specs.get(_text(row, 'ETGO_SF_SPEC_ID')) or '<unknown-spec>'
        rows.append((spec, _text(row, 'NAME'), _text(row, 'JAVA_QUALIFIER')))
    return sorted(rows, key=lambda r: (r[0] or '', r[1] or ''))


def read_annotated():
    """List of (spec, entity, fqn, class-name, own-@Named-or-None, path) for annotated sources.

    Comments are stripped first so that a javadoc mentioning the annotation is not read as a
    binding. The list is ordered by fully qualified class name, matching how
    ``NeoExtensionIndex.build`` orders the CDI beans, so that when two classes claim one pair both
    sides name the same winner.
    """
    found = []
    for base, _dirs, files in os.walk(JAVA_ROOT):
        for name in files:
            if not name.endswith('.java'):
                continue
            path = os.path.join(base, name)
            with open(path, encoding='utf-8', errors='replace') as handle:
                raw = handle.read()
            if '@NeoExtension' not in raw:
                continue
            source = COMMENT_RE.sub('', raw)
            package = PACKAGE_RE.search(source)
            package = package.group(1) if package else ''
            for match in ANNOTATION_RE.finditer(source):
                args = dict((m.group('key'), m.group('value'))
                            for m in ARG_RE.finditer(match.group('args')))
                if 'spec' not in args or 'entity' not in args:
                    continue
                tail = source[match.end():]
                near = source[max(0, match.start() - 400):match.start()] + tail[:400]
                named = NAMED_RE.search(near)
                klass = CLASS_RE.search(tail)
                simple = klass.group('name') if klass else name[:-5]
                found.append((args['spec'], args['entity'],
                              '%s.%s' % (package, simple) if package else simple, simple,
                              named.group('value') if named else None,
                              os.path.relpath(path, REPO)))
    return sorted(found, key=lambda f: f[2])


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--all', action='store_true',
                        help='also list the rows no mechanism binds')
    parser.add_argument('--ceiling', type=int, default=90,
                        help='fail when the distinct Java_Qualifier count exceeds this')
    args = parser.parse_args()

    entities = read_entities(read_specs())
    annotated = read_annotated()
    by_pair = {}
    conflicts = []

    for spec, entity, _fqn, klass, named, path in annotated:
        key = (spec.lower(), entity.lower())
        if key in by_pair:
            first = by_pair[key]
            conflicts.append(
                'duplicate annotation on spec=%s entity=%s: %s (%s) and %s (%s)'
                % (spec, entity, first[0], first[2], klass, path))
            continue
        by_pair[key] = (klass, named, path)

    print('=== @NeoExtension parity (ETP-5415 A13) ===')
    print('source: %s' % os.path.relpath(SOURCEDATA, REPO))
    print()
    print('%-34s %-28s %-12s %s' % ('SPEC', 'ENTITY', 'MECHANISM', 'BINDS'))

    bound_by_annotation = 0
    bound_by_qualifier = 0
    unbound = 0
    claimed = set()

    for spec, entity, qualifier in entities:
        key = ((spec or '').lower(), (entity or '').lower())
        hit = by_pair.get(key)
        if hit:
            claimed.add(key)
            bound_by_annotation += 1
            print('%-34s %-28s %-12s %s' % (spec, entity, 'annotation', hit[0]))
            if qualifier and qualifier != hit[1]:
                conflicts.append(
                    'spec=%s entity=%s is annotated on %s (%s) while the row still carries '
                    'Java_Qualifier=%s (the class\'s own @Named is %s)'
                    % (spec, entity, hit[0], hit[2], qualifier, hit[1] or 'absent'))
        elif qualifier:
            bound_by_qualifier += 1
            print('%-34s %-28s %-12s %s' % (spec, entity, 'qualifier', qualifier))
        else:
            unbound += 1
            if args.all:
                print('%-34s %-28s %-12s %s' % (spec, entity, 'none', '-'))

    for spec, entity, _fqn, klass, _named, path in annotated:
        if (spec.lower(), entity.lower()) not in claimed:
            conflicts.append('%s (%s) annotates spec=%s entity=%s, which matches no '
                             'ETGO_SF_ENTITY row' % (klass, path, spec, entity))

    distinct = sorted({q for _s, _e, q in entities if q})
    rows_with_qualifier = sum(1 for _s, _e, q in entities if q)

    print()
    print('=== conflicts ===')
    if conflicts:
        for line in sorted(set(conflicts)):
            print('  CONFLICT: %s' % line)
    else:
        print('  none')

    print()
    print('=== counts ===')
    print('  entity rows                : %d' % len(entities))
    print('  bound by @NeoExtension     : %d' % bound_by_annotation)
    print('  bound by Java_Qualifier    : %d' % bound_by_qualifier)
    print('  bound by neither           : %d%s'
          % (unbound, '' if args.all else '  (--all to list)'))
    print('  rows carrying a qualifier  : %d' % rows_with_qualifier)
    print('  DISTINCT Java_Qualifier    : %d  (ceiling %d)' % (len(distinct), args.ceiling))

    if len(distinct) > args.ceiling:
        print()
        print('FAIL: the distinct Java_Qualifier count rose above the ceiling of %d.'
              % args.ceiling)
        print('      A new qualifier means the old binding mechanism was chosen with '
              '@NeoExtension available.')
        return 1

    if len(distinct) < args.ceiling:
        print()
        print('NOTE: the distinct count is %d below the ceiling. Not a failure — lower the '
              'ceiling when the drop is deliberate.' % (args.ceiling - len(distinct)))

    return 0


if __name__ == '__main__':
    sys.exit(main())
