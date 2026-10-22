#!/usr/bin/env python3
"""CI guardrails for the move from Java_Qualifier to @NeoExtension (ETP-5588).

Fixes land on whatever base exists, so while the legacy binding is open to new code it keeps
growing. These checks close it. Every finding fails the job:

  1. legacy-handler   a NeoHandler class bound by @Named without @NeoExtension that is not in the
                      allowlist. New customizations use @NeoExtension(spec, entity).
  2. qualifier-read   a file reads getJavaQualifier() more often than its allowlist entry. Entity
                      customizations are resolved through NeoHandlerLookup.forEntity /
                      hasCustomization or NeoExtensionDispatcher, never by reading the column.
  3. spec-literal     a generic service (GENERIC_FILES / GENERIC_GLOBS) names a spec in a string
                      literal not in the allowlist. Window logic belongs in that window's
                      @NeoExtension, reached through a NeoHandler hook.
  4. stale-allowlist  an allowlist entry that no longer matches the code. Remove it: the allowlist
                      only shrinks.

The allowlist (scripts/extension-guardrails-allowlist.json) is the debt that existed when the
check landed. Migrating a handler or removing a violation means deleting its entry in the same
change; adding one needs a reviewer's explicit agreement in the PR.

Usage:
  scripts/check-extension-guardrails.py [--root DIR] [--allowlist FILE] [--print-current]

--print-current prints the allowlist the code would need today, for review; never paste it over
the committed file to make a failure go away.
"""

import argparse
import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
JAVA_PREFIX = os.path.join('src', 'com', 'etendoerp', 'go')

# The window-agnostic pipeline: no spec may be named here.
GENERIC_FILES = [
    'schemaforge/' + name + '.java' for name in (
        'BatchService', 'DocTypeResolver', 'NeoActionSurface', 'NeoBackgroundDefaultsService',
        'NeoBuiltInEndpointHandler', 'NeoCalloutEndpoint', 'NeoCalloutService', 'NeoCrudHandler',
        'NeoDefaultsService', 'NeoDocumentDownloadService', 'NeoExtensionDispatcher',
        'NeoFieldFilter', 'NeoHookDispatcher', 'NeoMandatoryDefaultsService', 'NeoProcessService',
        'NeoReadPredicates', 'NeoRequestRouter', 'NeoResponse', 'NeoSelectorService', 'NeoServlet',
        'NeoServletSupport', 'NeoSubEndpointDispatcher')
]
GENERIC_GLOBS = ['schemaforge/selector/**/*.java', 'mcp/**/*.java']
# Spec names that are also ordinary property/table words; a literal of these is not a spec.
AMBIGUOUS_SPEC_NAMES = {'organization', 'product', 'tax', 'user', 'warehouse'}

COMMENT_RE = re.compile(r'/\*.*?\*/|//[^\n]*', re.S)
STRING_RE = re.compile(r'"([a-z0-9][a-z0-9-]*)"')
NAMED_RE = re.compile(r'@Named\s*\(')
QUALIFIER_READ_RE = re.compile(r'\.getJavaQualifier\s*\(\s*\)')


def _strip(source):
    return COMMENT_RE.sub('', source)


def read_sources(root):
    """Repo-relative path -> comment-free source, for every .java under src/com/etendoerp/go."""
    sources = {}
    for path in glob.glob(os.path.join(root, JAVA_PREFIX, '**', '*.java'), recursive=True):
        with open(path, encoding='utf-8', errors='replace') as handle:
            sources[os.path.relpath(path, root)] = _strip(handle.read())
    return sources


def read_spec_names(root):
    path = os.path.join(root, 'src-db', 'database', 'sourcedata', 'ETGO_SF_SPEC.xml')
    names = {row.findtext('NAME') for row in ET.parse(path).getroot().findall('ETGO_SF_SPEC')}
    return {name for name in names if name} - AMBIGUOUS_SPEC_NAMES


def _class_name(path):
    return os.path.basename(path)[:-len('.java')]


def legacy_handlers(sources):
    """Simple names of NeoHandler classes bound by @Named and not by @NeoExtension."""
    by_name = {_class_name(path): source for path, source in sources.items()}

    def is_handler(name, seen=()):
        source = by_name.get(name)
        if source is None or name in seen:
            return False
        if re.search(r'\b(?:class|interface)\s+%s\b[^{]*\b(?:implements|extends)\b[^{]*\bNeoHandler\b'
                     % name, source):
            return True
        parent = re.search(r'\bclass\s+%s(?:<[^>{]*>)?\s+extends\s+(\w+)' % name, source)
        return bool(parent) and is_handler(parent.group(1), seen + (name,))

    return sorted(name for name, source in by_name.items()
                  if NAMED_RE.search(source) and '@NeoExtension' not in source
                  and is_handler(name))


def qualifier_reads(sources):
    """Repo-relative path -> number of getJavaQualifier() calls, for files that make any."""
    counts = {}
    for path, source in sources.items():
        found = len(QUALIFIER_READ_RE.findall(source))
        if found:
            counts[path.replace(os.sep, '/')] = found
    return counts


def generic_files(root):
    base = os.path.join(root, JAVA_PREFIX)
    paths = {os.path.join(base, rel) for rel in GENERIC_FILES}
    for pattern in GENERIC_GLOBS:
        paths.update(glob.glob(os.path.join(base, pattern), recursive=True))
    return sorted(os.path.relpath(path, root).replace(os.sep, '/')
                  for path in paths if os.path.exists(path))


def spec_literals(sources, generic, spec_names):
    """Repo-relative generic path -> sorted spec names it carries as string literals."""
    found = {}
    for path in generic:
        source = sources.get(path.replace('/', os.sep), sources.get(path, ''))
        names = sorted({m.group(1) for m in STRING_RE.finditer(source) if m.group(1) in spec_names})
        if names:
            found[path] = names
    return found


def current_state(root):
    sources = read_sources(root)
    return {
        'legacyHandlers': legacy_handlers(sources),
        'qualifierReads': qualifier_reads(sources),
        'specLiterals': spec_literals(sources, generic_files(root), read_spec_names(root)),
    }


def compare(current, allowed):
    """List of (kind, message) findings."""
    findings = []
    allowed_handlers = set(allowed.get('legacyHandlers', []))
    for name in current['legacyHandlers']:
        if name not in allowed_handlers:
            findings.append(('legacy-handler',
                             '%s is a NeoHandler bound by @Named without @NeoExtension. Bind it '
                             'with @NeoExtension(spec, entity) instead.' % name))
    for name in sorted(allowed_handlers - set(current['legacyHandlers'])):
        findings.append(('stale-allowlist',
                         'legacyHandlers: %s is no longer a legacy handler; remove it.' % name))

    allowed_reads = allowed.get('qualifierReads', {})
    for path, count in sorted(current['qualifierReads'].items()):
        limit = allowed_reads.get(path, 0)
        if count > limit:
            findings.append(('qualifier-read',
                             '%s reads getJavaQualifier() %d time(s), allowed %d. Resolve the '
                             'customization through NeoHandlerLookup.forEntity / '
                             'hasCustomization or NeoExtensionDispatcher.' % (path, count, limit)))
    for path, limit in sorted(allowed_reads.items()):
        count = current['qualifierReads'].get(path, 0)
        if count < limit:
            findings.append(('stale-allowlist',
                             'qualifierReads: %s now reads it %d time(s); lower its entry to %d '
                             '(remove it at 0).' % (path, count, count)))

    allowed_literals = allowed.get('specLiterals', {})
    for path, names in sorted(current['specLiterals'].items()):
        extra = sorted(set(names) - set(allowed_literals.get(path, [])))
        if extra:
            findings.append(('spec-literal',
                             '%s is a generic service and names spec(s) %s. Move that behaviour '
                             'into the spec\'s @NeoExtension through a NeoHandler hook.'
                             % (path, ', '.join(extra))))
    for path, names in sorted(allowed_literals.items()):
        gone = sorted(set(names) - set(current['specLiterals'].get(path, [])))
        if gone:
            findings.append(('stale-allowlist',
                             'specLiterals: %s no longer names %s; remove it.'
                             % (path, ', '.join(gone))))
    return findings


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--root', default=ROOT)
    parser.add_argument('--allowlist', default=None)
    parser.add_argument('--print-current', action='store_true')
    args = parser.parse_args(argv)

    current = current_state(args.root)
    if args.print_current:
        print(json.dumps(current, indent=2, sort_keys=True))
        return 0

    allowlist = args.allowlist or os.path.join(args.root, 'scripts',
                                               'extension-guardrails-allowlist.json')
    with open(allowlist, encoding='utf-8') as handle:
        allowed = json.load(handle)

    findings = compare(current, allowed)
    for kind, message in findings:
        print('::error::[%s] %s' % (kind, message))
    print('extension guardrails: %d legacy handler(s), %d file(s) reading Java_Qualifier, '
          '%d generic file(s) naming a spec; %d finding(s)'
          % (len(current['legacyHandlers']), len(current['qualifierReads']),
             len(current['specLiterals']), len(findings)))
    return 1 if findings else 0


if __name__ == '__main__':
    sys.exit(main())
