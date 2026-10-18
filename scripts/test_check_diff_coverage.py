# @covers scripts/check-diff-coverage.py
"""Unit tests for scripts/check-diff-coverage.py.

Run from the module root:  python3 -m unittest discover -s scripts
"""

import contextlib
import importlib.util
import io
import os
import subprocess
import tempfile
import unittest
from unittest import mock

_SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'check-diff-coverage.py')
_spec = importlib.util.spec_from_file_location('check_diff_coverage', _SCRIPT)
diffcov = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(diffcov)

FOO = 'src/com/etendoerp/go/rest/Foo.java'
BAR = 'src/com/etendoerp/go/rest/Bar.java'

JACOCO = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">'
    '<report name="goTestUnit">'
    '<package name="com/etendoerp/go/rest">'
    '<sourcefile name="Foo.java">'
    '<line nr="10" mi="0" ci="2" mb="0" cb="0"/>'
    '<line nr="11" mi="3" ci="0" mb="0" cb="0"/>'
    '<line nr="12" mi="0" ci="0" mb="0" cb="0"/>'
    '</sourcefile>'
    '<sourcefile name="Bar.java">'
    '<line nr="5" mi="4" ci="0" mb="0" cb="0"/>'
    '</sourcefile>'
    '</package>'
    '</report>'
)


def diff_for(path, *hunks):
    """A `git diff -U0` fragment for `path` with the given `@@` hunk headers."""
    return ''.join(['diff --git a/{0} b/{0}\n'.format(path), '--- a/{}\n'.format(path),
                    '+++ b/{}\n'.format(path)] + [h + '\n+changed\n' for h in hunks])


class ParseChangedLinesTest(unittest.TestCase):

    def test_reads_the_added_range_of_each_hunk(self):
        diff = diff_for(FOO, '@@ -10,0 +10,3 @@', '@@ -40 +42 @@')
        self.assertEqual(diffcov.parse_changed_lines(diff), {FOO: {10, 11, 12, 42}})

    def test_a_pure_deletion_adds_no_line(self):
        self.assertEqual(diffcov.parse_changed_lines(diff_for(FOO, '@@ -10,4 +9,0 @@')), {})

    def test_ignores_tests_non_java_files_and_deleted_files(self):
        diff = (diff_for('src-test/src/com/etendoerp/go/rest/FooTest.java', '@@ -1 +1 @@')
                + diff_for('src-db/database/model/tables/C_ORDER.xml', '@@ -1 +1 @@')
                + 'diff --git a/{0} b/{0}\n--- a/{0}\n+++ /dev/null\n@@ -1,5 +0,0 @@\n'.format(BAR))
        self.assertEqual(diffcov.parse_changed_lines(diff), {})

    def test_accepts_src_util_source_roots(self):
        path = 'src-util/modulescript/src/com/etendoerp/go/modulescript/Fix.java'
        self.assertEqual(diffcov.parse_changed_lines(diff_for(path, '@@ -3 +3,2 @@')), {path: {3, 4}})


class ParseJacocoTest(unittest.TestCase):

    def test_keeps_only_executable_lines_with_their_covered_flag(self):
        coverage = diffcov.parse_jacoco(JACOCO)
        self.assertEqual(coverage['com/etendoerp/go/rest/Foo.java'], {10: True, 11: False})
        self.assertEqual(coverage['com/etendoerp/go/rest/Bar.java'], {5: False})


class DbBackedSourcesTest(unittest.TestCase):

    def test_follows_extends_chains_to_a_dal_base(self):
        sources = {
            'Base.java': 'abstract class GoDalTest extends OBBaseTest {}',
            'A.java': 'public class AIntegrationTest extends GoDalTest { Account a; }',
            'B.java': 'public class BTest { Bar b; }',
        }
        self.assertEqual(diffcov.db_backed_sources(sources), [sources['A.java'], sources['Base.java']])

    def test_an_extends_inside_a_comment_does_not_count(self):
        sources = {'C.java': '/** class FakeTest extends OBBaseTest */ public class CTest {}'}
        self.assertEqual(diffcov.db_backed_sources(sources), [])

    def test_referenced_names_ignores_comments(self):
        names = diffcov.referenced_names(['// uses Ghost\nclass X { Account a; }'])
        self.assertIn('Account', names)
        self.assertNotIn('Ghost', names)


class ClassifyTest(unittest.TestCase):

    COVERAGE = {
        'com/etendoerp/go/rest/Foo.java': {10: True, 11: False, 20: True},
        'com/etendoerp/go/rest/Bar.java': {5: False, 6: False},
    }

    def test_counts_covered_and_uncovered_executable_changed_lines(self):
        results = diffcov.classify({FOO: {10, 11, 12}}, self.COVERAGE, set())
        self.assertEqual(results, [(FOO, 1, [11], False)])

    def test_defers_a_file_with_no_unit_coverage_that_a_db_test_references(self):
        results = diffcov.classify({BAR: {5, 6}}, self.COVERAGE, {'Bar'})
        self.assertEqual(results, [(BAR, 0, [5, 6], True)])

    def test_an_untested_file_no_db_test_references_still_counts(self):
        results = diffcov.classify({BAR: {5, 6}}, self.COVERAGE, set())
        self.assertEqual(results, [(BAR, 0, [5, 6], False)])

    def test_a_file_with_some_unit_coverage_is_never_deferred(self):
        results = diffcov.classify({FOO: {11}}, self.COVERAGE, {'Foo'})
        self.assertEqual(results, [(FOO, 0, [11], False)])

    def test_skips_files_the_report_does_not_know_and_non_executable_lines(self):
        unknown = 'src/com/etendoerp/go/rest/Baz.java'
        self.assertEqual(diffcov.classify({unknown: {1}, FOO: {12, 13}}, self.COVERAGE, set()), [])


class ReportTest(unittest.TestCase):

    def test_passes_exactly_at_the_threshold(self):
        results = [(FOO, 4, [11], False)]
        _, passed = diffcov.report(results, 80, 'block')
        self.assertTrue(passed)

    def test_fails_under_the_threshold_and_marks_the_lines_as_errors_in_block_mode(self):
        lines, passed = diffcov.report([(FOO, 3, [11, 12], False)], 80, 'block')
        self.assertFalse(passed)
        self.assertIn('::error file={},line=11,title=diff-coverage/uncovered::2 changed line(s) not '
                      'covered by goTestUnit: 11-12'.format(FOO), lines)
        self.assertIn('(60.0%), threshold 80%', lines[-1])

    def test_annotate_mode_only_warns(self):
        lines, passed = diffcov.report([(FOO, 0, [11], False)], 80, 'annotate')
        self.assertFalse(passed)
        self.assertTrue(lines[1].startswith('::warning '))

    def test_deferred_lines_are_reported_but_not_counted(self):
        lines, passed = diffcov.report([(BAR, 0, [5, 6], True)], 80, 'block')
        self.assertTrue(passed)
        self.assertIn('2 changed line(s) deferred to the full suite', lines[0])
        self.assertTrue(lines[-1].startswith('diff-coverage: no counted changed lines to measure; 2 line(s) deferred'))

    def test_compress_joins_consecutive_lines(self):
        self.assertEqual(diffcov.compress([3, 4, 5, 9, 11, 12]), '3-5, 9, 11-12')


class MainTest(unittest.TestCase):

    def run_main(self, argv, diff='', env=None):
        out = io.StringIO()
        with tempfile.TemporaryDirectory() as root, \
                mock.patch.object(diffcov, 'ROOT', root), \
                mock.patch.object(diffcov, 'git', return_value=diff), \
                mock.patch.dict(os.environ, env or {}, clear=False), \
                contextlib.redirect_stdout(out):
            xml_path = os.path.join(root, 'goTestUnit.xml')
            with open(xml_path, 'w', encoding='utf-8') as handle:
                handle.write(JACOCO)
            code = diffcov.main(argv + ['--jacoco-xml', xml_path])
        return code, out.getvalue()

    def test_reports_the_changed_lines_and_exits_zero_in_annotate_mode(self):
        code, out = self.run_main(['--base', 'develop'], diff_for(FOO, '@@ -10 +10,2 @@'))
        self.assertEqual(code, 0)
        self.assertIn('diff-coverage: 1/2 changed lines covered by goTestUnit (50.0%)', out)

    def test_block_mode_from_the_environment_fails_under_the_threshold(self):
        code, _ = self.run_main(['--base', 'develop'], diff_for(FOO, '@@ -10 +10,2 @@'),
                                {'DIFF_COVERAGE_MODE': 'block'})
        self.assertEqual(code, 1)

    def test_a_missing_report_explains_how_to_produce_it(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = diffcov.main(['--base', 'develop', '--jacoco-xml', '/nonexistent.xml', '--mode', 'block'])
        self.assertEqual(code, 1)
        self.assertIn('./gradlew goTestUnit goTestUnitCoverageReport', out.getvalue())

    def test_a_git_failure_is_not_a_finding_in_annotate_mode(self):
        error = subprocess.CalledProcessError(128, ['git'], stderr='fatal: bad revision')
        out_buffer = io.StringIO()
        with tempfile.TemporaryDirectory() as root, mock.patch.object(diffcov, 'ROOT', root), \
                mock.patch.object(diffcov, 'git', side_effect=error), contextlib.redirect_stdout(out_buffer):
            xml_path = os.path.join(root, 'goTestUnit.xml')
            with open(xml_path, 'w', encoding='utf-8') as handle:
                handle.write(JACOCO)
            code = diffcov.main(['--base', 'nope', '--jacoco-xml', xml_path])
        self.assertEqual(code, 0)
        self.assertIn('title=diff-coverage/git-error::could not read the diff nope...HEAD: fatal: bad revision',
                      out_buffer.getvalue())


if __name__ == '__main__':
    unittest.main()
