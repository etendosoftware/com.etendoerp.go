# @covers scripts/check-test-hygiene.py
"""Unit tests for scripts/check-test-hygiene.py.

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

_SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'check-test-hygiene.py')
_spec = importlib.util.spec_from_file_location('check_test_hygiene', _SCRIPT)
hygiene = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(hygiene)


def fake_fs(*paths):
    """A `path_exists` callable that knows only the given repo-relative paths."""
    known = set(paths)
    return lambda rel: rel in known


TEST_PATH = 'src-test/src/com/etendoerp/go/rest/FooTest.java'
FOO_SRC = 'src/com/etendoerp/go/rest/Foo.java'

COVERED_SRC = (
    'package com.etendoerp.go.rest;\n'
    '\n'
    '/**\n'
    ' * Tests for Foo.\n'
    ' *\n'
    ' * @covers com.etendoerp.go.rest.Foo\n'
    ' */\n'
    'public class FooTest {}\n'
)


class ParseCoversTest(unittest.TestCase):

    def test_returns_fqn_and_line_from_a_javadoc_block(self):
        self.assertEqual(hygiene.parse_covers(COVERED_SRC), [('com.etendoerp.go.rest.Foo', 6)])

    def test_returns_every_covers_across_javadoc_blocks(self):
        src = ('/** @covers a.b.One */\n'
               'class X {\n'
               '  /**\n'
               '   * @covers a.b.Two\n'
               '   * @covers a.b.Outer$Inner\n'
               '   */\n'
               '}\n')
        self.assertEqual(hygiene.parse_covers(src),
                         [('a.b.One', 1), ('a.b.Two', 4), ('a.b.Outer$Inner', 5)])

    def test_ignores_covers_in_a_line_comment(self):
        self.assertEqual(hygiene.parse_covers('// @covers a.b.Foo\nclass X {}\n'), [])

    def test_ignores_covers_in_a_plain_block_comment(self):
        self.assertEqual(hygiene.parse_covers('/* @covers a.b.Foo */\nclass X {}\n'), [])

    def test_ignores_covers_in_code_outside_any_comment(self):
        src = '/** Tests. */\nclass X { String s = "@covers a.b.Foo"; }\n'
        self.assertEqual(hygiene.parse_covers(src), [])

    def test_returns_empty_without_covers(self):
        self.assertEqual(hygiene.parse_covers('/** Tests. */\nclass X {}\n'), [])


class ClassExistsTest(unittest.TestCase):

    def test_true_when_the_source_file_exists(self):
        self.assertTrue(hygiene.class_exists('com.etendoerp.go.rest.Foo', fake_fs(FOO_SRC)))

    def test_false_when_the_source_file_is_missing(self):
        self.assertFalse(hygiene.class_exists('com.etendoerp.go.rest.Missing', fake_fs(FOO_SRC)))

    def test_nested_dot_notation_resolves_to_the_outer_file(self):
        self.assertTrue(hygiene.class_exists('com.etendoerp.go.rest.Foo.Inner', fake_fs(FOO_SRC)))

    def test_nested_dollar_notation_resolves_to_the_outer_file(self):
        self.assertTrue(hygiene.class_exists('com.etendoerp.go.rest.Foo$Inner', fake_fs(FOO_SRC)))

    def test_doubly_nested_resolves_to_the_outer_file(self):
        self.assertTrue(hygiene.class_exists('com.etendoerp.go.rest.Foo.Mid.Inner', fake_fs(FOO_SRC)))

    def test_nested_under_a_missing_outer_is_false(self):
        self.assertFalse(hygiene.class_exists('com.etendoerp.go.rest.Bar.Inner', fake_fs(FOO_SRC)))

    def test_does_not_walk_up_into_a_package(self):
        # `rest` is lowercase, so `...rest.Foo` must not be satisfied by a file named `rest.java`.
        self.assertFalse(hygiene.class_exists('com.etendoerp.go.rest.Foo',
                                              fake_fs('src/com/etendoerp/go/rest.java')))

    def test_only_looks_under_src(self):
        self.assertFalse(hygiene.class_exists(
            'com.etendoerp.go.rest.Foo',
            fake_fs('src-test/src/com/etendoerp/go/rest/Foo.java')))

    def test_finds_a_class_under_a_src_util_source_root(self):
        script = 'src-util/modulescript/src/com/etendoerp/go/modulescript/SetupScript.java'
        self.assertTrue(hygiene.class_exists(
            'com.etendoerp.go.modulescript.SetupScript', fake_fs(script),
            source_roots=('src', 'src-util/modulescript/src')))


class FindSourceRootsTest(unittest.TestCase):

    def test_returns_src_plus_every_src_util_source_root(self):
        with tempfile.TemporaryDirectory() as root:
            for rel in ('src/com', 'src-util/modulescript/src/com/etendoerp',
                        'src-util/buildvalidation/src/com', 'src-util/notes'):
                os.makedirs(os.path.join(root, rel))
            self.assertEqual(hygiene.find_source_roots(root),
                             ['src', 'src-util/buildvalidation/src', 'src-util/modulescript/src'])

    def test_returns_only_src_without_src_util(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertEqual(hygiene.find_source_roots(root), ['src'])


class CheckFileTest(unittest.TestCase):

    def rules(self, findings):
        return [f[2] for f in findings]

    def test_clean_file_has_no_findings(self):
        for status in ('A', 'M', 'R'):
            with self.subTest(status=status):
                self.assertEqual(hygiene.check_file(TEST_PATH, status, COVERED_SRC, fake_fs(FOO_SRC)), [])

    def test_missing_covers_is_reported_for_every_status(self):
        for status in ('A', 'M', 'R'):
            with self.subTest(status=status):
                findings = hygiene.check_file(TEST_PATH, status, 'public class FooTest {}\n', fake_fs())
                self.assertEqual(len(findings), 1)
                path, line, rule, _ = findings[0]
                self.assertEqual((path, line, rule), (TEST_PATH, 1, 'missing-covers'))

    def test_covers_outside_javadoc_counts_as_missing(self):
        findings = hygiene.check_file(TEST_PATH, 'M', '// @covers com.etendoerp.go.rest.Foo\n',
                                      fake_fs(FOO_SRC))
        self.assertEqual(self.rules(findings), ['missing-covers'])

    def test_covers_to_a_missing_class_is_reported_with_its_line_for_every_status(self):
        for status in ('A', 'M', 'R'):
            with self.subTest(status=status):
                findings = hygiene.check_file(TEST_PATH, status, COVERED_SRC, fake_fs())
                self.assertEqual(len(findings), 1)
                path, line, rule, message = findings[0]
                self.assertEqual((path, line, rule), (TEST_PATH, 6, 'covers-not-found'))
                self.assertIn('com.etendoerp.go.rest.Foo', message)

    def test_only_the_missing_one_of_several_covers_is_reported(self):
        src = '/**\n * @covers com.etendoerp.go.rest.Foo\n * @covers com.etendoerp.go.rest.Gone\n */\n'
        findings = hygiene.check_file(TEST_PATH, 'M', src, fake_fs(FOO_SRC))
        self.assertEqual([(f[1], f[2]) for f in findings], [(3, 'covers-not-found')])
        self.assertIn('com.etendoerp.go.rest.Gone', findings[0][3])

    def test_ticket_named_is_reported_for_added_and_renamed_files(self):
        for status in ('A', 'R'):
            for name in ('Etp1234Test.java', 'ETP-1234Test.java', 'FooEtp5511Test.java'):
                with self.subTest(status=status, name=name):
                    path = 'src-test/src/com/etendoerp/go/rest/' + name
                    findings = hygiene.check_file(path, status, COVERED_SRC, fake_fs(FOO_SRC))
                    self.assertEqual(self.rules(findings), ['ticket-named'])
                    self.assertEqual(findings[0][:2], (path, 1))

    def test_ticket_named_is_not_reported_for_a_modified_file(self):
        path = 'src-test/src/com/etendoerp/go/rest/Etp1234Test.java'
        self.assertEqual(hygiene.check_file(path, 'M', COVERED_SRC, fake_fs(FOO_SRC)), [])

    def test_ticket_in_a_directory_name_is_not_ticket_named(self):
        path = 'src-test/src/com/etendoerp/go/etp1234/FooTest.java'
        self.assertEqual(hygiene.check_file(path, 'A', COVERED_SRC, fake_fs(FOO_SRC)), [])

    def test_fewer_than_four_digits_is_not_ticket_named(self):
        path = 'src-test/src/com/etendoerp/go/rest/Etp123Test.java'
        self.assertEqual(hygiene.check_file(path, 'A', COVERED_SRC, fake_fs(FOO_SRC)), [])

    def test_all_rules_combine_on_one_new_file(self):
        path = 'src-test/src/com/etendoerp/go/rest/Etp1234Test.java'
        findings = hygiene.check_file(path, 'A', 'class Etp1234Test {}\n', fake_fs())
        self.assertEqual(self.rules(findings), ['missing-covers', 'ticket-named'])


class CheckFileSourceRootsTest(unittest.TestCase):

    def test_covers_of_a_src_util_class_is_not_reported(self):
        src = COVERED_SRC.replace('com.etendoerp.go.rest.Foo', 'com.etendoerp.go.modulescript.SetupScript')
        script = 'src-util/modulescript/src/com/etendoerp/go/modulescript/SetupScript.java'
        findings = hygiene.check_file(TEST_PATH, 'M', src, fake_fs(script),
                                      source_roots=('src', 'src-util/modulescript/src'))
        self.assertEqual(findings, [])


class MainGitErrorTest(unittest.TestCase):

    def run_main(self, mode):
        error = subprocess.CalledProcessError(128, ['git', 'diff'], stderr='fatal: bad revision\n')
        out = io.StringIO()
        with mock.patch.object(hygiene, 'git', side_effect=error), contextlib.redirect_stdout(out):
            code = hygiene.main(['--base', 'no-such-ref', '--head', 'HEAD', '--mode', mode])
        return code, out.getvalue()

    def test_annotate_mode_turns_a_git_error_into_a_warning_and_exits_0(self):
        code, out = self.run_main('annotate')
        self.assertEqual(code, 0)
        self.assertIn('::warning title=test-hygiene/git-error::', out)
        self.assertIn('fatal: bad revision', out)

    def test_block_mode_still_fails_on_a_git_error(self):
        code, out = self.run_main('block')
        self.assertEqual(code, 1)
        self.assertIn('::error title=test-hygiene/git-error::', out)


class ParseNameStatusTest(unittest.TestCase):

    def test_keeps_added_modified_and_renamed_test_files(self):
        output = ('A\tsrc-test/src/a/NewTest.java\n'
                  'M\tsrc-test/src/a/OldTest.java\n'
                  'R087\tsrc-test/src/a/BeforeTest.java\tsrc-test/src/a/AfterTest.java\n')
        self.assertEqual(hygiene.parse_name_status(output), [
            ('A', 'src-test/src/a/NewTest.java'),
            ('M', 'src-test/src/a/OldTest.java'),
            ('R', 'src-test/src/a/AfterTest.java'),
        ])

    def test_drops_deleted_and_copied_files(self):
        output = 'D\tsrc-test/src/a/GoneTest.java\nC100\tsrc-test/src/a/XTest.java\tsrc-test/src/a/YTest.java\n'
        self.assertEqual(hygiene.parse_name_status(output), [])

    def test_drops_non_test_files(self):
        output = ('M\tsrc/com/a/Foo.java\n'
                  'A\tsrc-test/src/a/Helper.java\n'
                  'A\tsrc-test/src/a/FooTest.kt\n'
                  'M\tscripts/check-test-hygiene.py\n')
        self.assertEqual(hygiene.parse_name_status(output), [])

    def test_drops_a_rename_out_of_src_test(self):
        output = 'R100\tsrc-test/src/a/FooTest.java\tsrc/a/FooTest.java\n'
        self.assertEqual(hygiene.parse_name_status(output), [])

    def test_ignores_blank_lines(self):
        output = '\n   \nM\tsrc-test/src/a/FooTest.java\n\n'
        self.assertEqual(hygiene.parse_name_status(output), [('M', 'src-test/src/a/FooTest.java')])

    def test_empty_output_yields_nothing(self):
        self.assertEqual(hygiene.parse_name_status(''), [])


class FormatAnnotationTest(unittest.TestCase):

    FINDING = ('src-test/src/a/FooTest.java', 7, 'covers-not-found', 'no class a.Foo')

    def test_annotate_mode_emits_a_warning(self):
        self.assertEqual(hygiene.format_annotation(self.FINDING, 'annotate'),
                         '::warning file=src-test/src/a/FooTest.java,line=7,'
                         'title=test-hygiene/covers-not-found::no class a.Foo')

    def test_block_mode_emits_an_error(self):
        self.assertEqual(hygiene.format_annotation(self.FINDING, 'block'),
                         '::error file=src-test/src/a/FooTest.java,line=7,'
                         'title=test-hygiene/covers-not-found::no class a.Foo')


if __name__ == '__main__':
    unittest.main()
