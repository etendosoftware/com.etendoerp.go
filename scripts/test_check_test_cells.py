# @covers scripts/check-test-cells.py
"""Unit tests for scripts/check-test-cells.py.

Run from the module root:  python3 -m unittest discover -s scripts
"""

import contextlib
import importlib.util
import io
import os
import tempfile
import unittest

_SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'check-test-cells.py')
_spec = importlib.util.spec_from_file_location('check_test_cells', _SCRIPT)
cells = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cells)

TEST_SRC = 'class FooTest {\n  @Test\n  void works() {}\n}\n'


class RepoFixture:
    """A throwaway module dir with src-test/src and src-test/cells."""

    def __init__(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = self._tmp.name

    def write(self, rel, content):
        path = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w', encoding='utf-8') as f:
            f.write(content)

    def test_class(self, fqn, src=TEST_SRC):
        self.write(os.path.join('src-test', 'src', *fqn.split('.')) + '.java', src)

    def run(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = cells.main(['--root', self.root])
        return code, out.getvalue()

    def close(self):
        self._tmp.cleanup()


class IsTestClassTest(unittest.TestCase):

    def test_plain_and_qualified_annotations_count(self):
        self.assertTrue(cells.is_test_class('@Test void a() {}'))
        self.assertTrue(cells.is_test_class('@ParameterizedTest void a(int x) {}'))
        self.assertTrue(cells.is_test_class('@org.junit.jupiter.api.Test void a() {}'))

    def test_abstract_bases_and_helpers_do_not(self):
        self.assertFalse(cells.is_test_class('abstract class BaseTest { @Test void a() {} }'))
        self.assertFalse(cells.is_test_class('class Fixtures { static int x; }'))
        self.assertFalse(cells.is_test_class('@TestInstance(PER_CLASS) class Fixtures {}'))


class CheckTest(unittest.TestCase):

    def test_every_class_in_one_file_is_clean(self):
        files = {'ventas': ['a.VentasTest', '@incluye ventas-compras'], 'compras': ['a.ComprasTest', '@incluye ventas-compras'],
                 'compartidos/ventas-compras': ['a.SharedTest']}
        self.assertEqual(cells.check(files, {'a.VentasTest', 'a.ComprasTest', 'a.SharedTest'}), [])

    def test_reports_each_kind_of_problem(self):
        files = {'ventas': ['a.GoneTest', 'a.TwiceTest', '@incluye nada'], 'compras': ['a.TwiceTest']}
        kinds = [(k, f) for k, f, _ in cells.check(files, {'a.TwiceTest', 'a.NewTest'})]
        self.assertEqual(kinds, [('bad-include', 'ventas'), ('no-cell', None), ('stale', 'ventas'), ('duplicated', 'compras')])

    def test_annotation_points_at_the_cell_file(self):
        self.assertEqual(cells.format_annotation(('stale', 'ventas', 'x')),
                         '::error file=src-test/cells/ventas.txt,title=test-cells/stale::x')
        self.assertEqual(cells.format_annotation(('no-cell', None, 'x')), '::error title=test-cells/no-cell::x')


class MainTest(unittest.TestCase):

    def setUp(self):
        self.repo = RepoFixture()
        self.addCleanup(self.repo.close)
        self.repo.test_class('com.etendoerp.go.rest.FooTest')
        self.repo.test_class('com.etendoerp.go.rest.BaseTest', 'abstract class BaseTest { @Test void a() {} }')
        self.repo.write('src-test/src/com/etendoerp/go/rest/Helper.java', 'class Helper {}')
        self.repo.write('src-test/cells/ventas.txt', '# Célula: ventas\n\n# --- con mocks (1)\ncom.etendoerp.go.rest.FooTest\n')

    def test_passes_when_every_test_class_has_a_cell(self):
        code, out = self.repo.run()
        self.assertEqual(code, 0)
        self.assertIn('OK: 1 test classes', out)

    def test_fails_on_a_new_test_class_of_no_cell(self):
        self.repo.test_class('com.etendoerp.go.mcp.BarTest')
        code, out = self.repo.run()
        self.assertEqual(code, 1)
        self.assertIn('::error title=test-cells/no-cell::com.etendoerp.go.mcp.BarTest is in no cell', out)

    def test_fails_when_a_listed_class_is_gone(self):
        os.remove(os.path.join(self.repo.root, 'src-test/src/com/etendoerp/go/rest/FooTest.java'))
        code, out = self.repo.run()
        self.assertEqual(code, 1)
        self.assertIn('test-cells/stale', out)


if __name__ == '__main__':
    unittest.main()
