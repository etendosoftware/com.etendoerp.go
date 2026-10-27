# @covers scripts/check-extension-guardrails.py
"""Unit tests for scripts/check-extension-guardrails.py.

Run from the module root:  python3 -m unittest discover -s scripts
"""

import importlib.util
import os
import tempfile
import unittest

_SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'check-extension-guardrails.py')
_spec = importlib.util.spec_from_file_location('check_extension_guardrails', _SCRIPT)
guardrails = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(guardrails)

JAVA = 'src/com/etendoerp/go/'
SPEC_XML = (
    '<?xml version="1.0"?><data>'
    '<ETGO_SF_SPEC><NAME>sales-invoice</NAME></ETGO_SF_SPEC>'
    '<ETGO_SF_SPEC><NAME>product</NAME></ETGO_SF_SPEC>'
    '</data>'
)


class GuardrailsTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = self._tmp.name
        self._write('src-db/database/sourcedata/ETGO_SF_SPEC.xml', SPEC_XML)
        self._write(JAVA + 'schemaforge/NeoHandler.java',
                    'package x;\npublic interface NeoHandler { Object handle(Object c); }\n')

    def tearDown(self):
        self._tmp.cleanup()

    def _write(self, rel, content):
        path = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w', encoding='utf-8') as handle:
            handle.write(content)

    def _findings(self, allowed=None):
        allowed = allowed or {}
        return guardrails.compare(guardrails.current_state(self.root), allowed)

    def _kinds(self, allowed=None):
        return sorted(kind for kind, _msg in self._findings(allowed))

    def test_new_named_handler_without_annotation_fails(self):
        self._write(JAVA + 'schemaforge/FooHandler.java',
                    '@Named("foo")\npublic class FooHandler implements NeoHandler {}\n')
        self.assertEqual(self._kinds(), ['legacy-handler'])

    def test_annotated_handler_passes_even_with_named(self):
        self._write(JAVA + 'schemaforge/FooHandler.java',
                    '@NeoExtension(spec = "s", entity = "e")\n@Named("foo")\n'
                    'public class FooHandler implements NeoHandler {}\n')
        self.assertEqual(self._kinds(), [])

    def test_handler_through_superclass_is_detected(self):
        self._write(JAVA + 'schemaforge/AbstractFoo.java',
                    'public abstract class AbstractFoo implements NeoHandler {}\n')
        self._write(JAVA + 'schemaforge/FooHandler.java',
                    '@Named("foo")\npublic class FooHandler extends AbstractFoo {}\n')
        self.assertEqual(self._kinds(), ['legacy-handler'])

    def test_named_class_that_is_not_a_handler_is_ignored(self):
        self._write(JAVA + 'schemaforge/Helper.java', '@Named("h")\npublic class Helper {}\n')
        self.assertEqual(self._kinds(), [])

    def test_allowlisted_legacy_handler_passes_and_a_migrated_one_must_leave_the_list(self):
        self._write(JAVA + 'schemaforge/FooHandler.java',
                    '@Named("foo")\npublic class FooHandler implements NeoHandler {}\n')
        self.assertEqual(self._kinds({'legacyHandlers': ['FooHandler']}), [])
        self.assertEqual(self._kinds({'legacyHandlers': ['FooHandler', 'GoneHandler']}),
                         ['stale-allowlist'])

    def test_qualifier_reads_above_the_allowance_fail_and_below_it_go_stale(self):
        self._write(JAVA + 'schemaforge/Reader.java',
                    'class Reader { void a(E e) { e.getJavaQualifier(); e.getJavaQualifier(); } }\n')
        path = JAVA + 'schemaforge/Reader.java'
        self.assertEqual(self._kinds({'qualifierReads': {path: 1}}), ['qualifier-read'])
        self.assertEqual(self._kinds({'qualifierReads': {path: 2}}), [])
        self.assertEqual(self._kinds({'qualifierReads': {path: 3}}), ['stale-allowlist'])

    def test_comments_do_not_count_as_qualifier_reads(self):
        self._write(JAVA + 'schemaforge/Reader.java',
                    'class Reader { /* e.getJavaQualifier() */ // e.getJavaQualifier()\n }\n')
        self.assertEqual(self._kinds(), [])

    def test_spec_literal_in_a_generic_service_fails_but_not_elsewhere(self):
        self._write(JAVA + 'schemaforge/NeoCrudHandler.java',
                    'class NeoCrudHandler { String s = "sales-invoice"; }\n')
        self._write(JAVA + 'schemaforge/InvoiceSupport.java',
                    'class InvoiceSupport { String s = "sales-invoice"; }\n')
        findings = self._findings()
        self.assertEqual([kind for kind, _ in findings], ['spec-literal'])
        self.assertIn('NeoCrudHandler.java', findings[0][1])

    def test_ambiguous_spec_names_are_not_spec_literals(self):
        self._write(JAVA + 'mcp/McpThing.java', 'class McpThing { String p = "product"; }\n')
        self.assertEqual(self._kinds(), [])

    def test_allowlisted_spec_literal_passes_and_a_removed_one_goes_stale(self):
        self._write(JAVA + 'schemaforge/NeoCrudHandler.java',
                    'class NeoCrudHandler { String s = "sales-invoice"; }\n')
        path = JAVA + 'schemaforge/NeoCrudHandler.java'
        self.assertEqual(self._kinds({'specLiterals': {path: ['sales-invoice']}}), [])
        self._write(JAVA + 'schemaforge/NeoCrudHandler.java', 'class NeoCrudHandler {}\n')
        self.assertEqual(self._kinds({'specLiterals': {path: ['sales-invoice']}}),
                         ['stale-allowlist'])


if __name__ == '__main__':
    unittest.main()
