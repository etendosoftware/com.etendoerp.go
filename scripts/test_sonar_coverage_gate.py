# @covers scripts/sonar-coverage-gate.py
"""Unit tests for scripts/sonar-coverage-gate.py.

Run from the module root:  python3 -m unittest discover -s scripts
"""

import contextlib
import importlib.util
import io
import json
import os
import tempfile
import unittest
from unittest import mock

_SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'sonar-coverage-gate.py')
_spec = importlib.util.spec_from_file_location('sonar_coverage_gate', _SCRIPT)
gate = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gate)


class FakeSonar:
    """coverage by branch, and the statuses the compute task goes through."""

    def __init__(self, coverage=None, statuses=()):
        self.cov = coverage or {}
        self.statuses = list(statuses)
        self.asked = []

    def coverage(self, key, branch):
        self.asked.append((key, branch))
        return self.cov.get(branch)

    def task_status(self, task_id):
        return self.statuses.pop(0) if len(self.statuses) > 1 else self.statuses[0]


class VerdictTest(unittest.TestCase):

    def test_below_the_minimum_fails_whatever_the_base(self):
        passed, message = gate.verdict(69.9, 60.0, 70, 1)
        self.assertFalse(passed)
        self.assertIn('below the minimum required (70%)', message)

    def test_more_than_the_tolerance_below_the_base_fails(self):
        passed, message = gate.verdict(83.9, 85.0, 70, 1)
        self.assertFalse(passed)
        self.assertIn('at least 84.0%', message)

    def test_within_the_tolerance_passes(self):
        self.assertTrue(gate.verdict(84.0, 85.0, 70, 1)[0])
        self.assertTrue(gate.verdict(90.0, 85.0, 70, 1)[0])

    def test_a_base_without_coverage_only_checks_the_minimum(self):
        passed, message = gate.verdict(75.0, None, 70, 1)
        self.assertTrue(passed)
        self.assertIn('no coverage to compare with', message)


class CompareTest(unittest.TestCase):

    def test_reads_the_branch_and_the_base_and_summarizes(self):
        sonar = FakeSonar({'actions/mergeblock/x': 85.1, 'main': 85.4})
        code, md = gate.compare(sonar, 'key', 'actions/mergeblock/x', 'main', 70, 1)
        self.assertEqual(code, 0)
        self.assertEqual(sonar.asked, [('key', 'actions/mergeblock/x'), ('key', 'main')])
        self.assertIn('| `actions/mergeblock/x` | 85.1% |', md)
        self.assertIn('| `main` (base) | 85.4% |', md)
        self.assertIn('✅', md)

    def test_a_drop_beyond_the_tolerance_fails(self):
        code, md = gate.compare(FakeSonar({'b': 80.0, 'develop': 85.0}), 'key', 'b', 'develop', 70, 1)
        self.assertEqual(code, 1)
        self.assertIn('❌', md)

    def test_a_branch_without_coverage_fails(self):
        code, md = gate.compare(FakeSonar({'develop': 85.0}), 'key', 'b', 'develop', 70, 1)
        self.assertEqual(code, 1)
        self.assertIn('no coverage for `b`', md)


class WaitTest(unittest.TestCase):

    def test_polls_until_the_task_is_done(self):
        sleeps = []
        status = gate.wait_for_task(FakeSonar(statuses=['PENDING', 'IN_PROGRESS', 'SUCCESS']), 't',
                                    timeout=100, interval=10, sleep=sleeps.append, clock=lambda: 0)
        self.assertEqual(status, 'SUCCESS')
        self.assertEqual(sleeps, [10, 10])

    def test_gives_up_at_the_timeout(self):
        times = iter([0, 50, 101])
        status = gate.wait_for_task(FakeSonar(statuses=['IN_PROGRESS']), 't', timeout=100,
                                    sleep=lambda s: None, clock=lambda: next(times))
        self.assertEqual(status, 'TIMEOUT')


class SonarClientTest(unittest.TestCase):

    def response(self, payload):
        resp = mock.MagicMock()
        resp.read.return_value = json.dumps(payload).encode()
        resp.__enter__.return_value = resp
        return resp

    def test_reads_the_coverage_measure_with_basic_auth(self):
        opener = mock.MagicMock(return_value=self.response(
            {'component': {'measures': [{'metric': 'coverage', 'value': '85.3'}]}}))
        sonar = gate.Sonar('https://sonar.example/', 'tok', opener)
        self.assertEqual(sonar.coverage('key', 'actions/feature/ETP-1'), 85.3)
        request = opener.call_args.args[0]
        self.assertTrue(request.full_url.startswith('https://sonar.example/api/measures/component?'))
        self.assertIn('branch=actions%2Ffeature%2FETP-1', request.full_url)
        self.assertEqual(request.get_header('Authorization'), 'Basic dG9rOg==')

    def test_no_coverage_measure_is_none(self):
        opener = mock.MagicMock(return_value=self.response({'component': {'measures': []}}))
        self.assertIsNone(gate.Sonar('https://s', 't', opener).coverage('key', 'b'))

    def test_a_forbidden_read_names_the_missing_permission(self):
        opener = mock.MagicMock(side_effect=gate.urllib.error.HTTPError('u', 403, 'forbidden', {}, io.BytesIO(b'')))
        with self.assertRaises(gate.SonarError) as raised:
            gate.Sonar('https://s', 't', opener).coverage('key', 'b')
        self.assertIn('Browse permission', str(raised.exception))

    def test_an_http_error_is_a_sonar_error(self):
        opener = mock.MagicMock(side_effect=gate.urllib.error.HTTPError('u', 404, 'nf', {}, io.BytesIO(b'')))
        with self.assertRaises(gate.SonarError):
            gate.Sonar('https://s', 't', opener).coverage('key', 'b')


class MainTest(unittest.TestCase):

    def run_main(self, argv, sonar, env=None):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = gate.main(argv, env or {}, sonar, sleep=lambda s: None)
        return code, out.getvalue(), err.getvalue()

    def test_wait_reads_the_task_id_the_scanner_wrote(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = os.path.join(tmp, 'report-task.txt')
            with open(report, 'w', encoding='utf-8') as fh:
                fh.write('projectKey=k\nceTaskId=AX12\nceTaskUrl=https://s/api/ce/task?id=AX12\n')
            code, out, _ = self.run_main(['wait', '--report-task', report], FakeSonar(statuses=['SUCCESS']))
        self.assertEqual(code, 0)
        self.assertIn('AX12: SUCCESS', out)

    def test_a_failed_analysis_fails_the_wait(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = os.path.join(tmp, 'report-task.txt')
            with open(report, 'w', encoding='utf-8') as fh:
                fh.write('ceTaskId=AX12\n')
            code, _, _ = self.run_main(['wait', '--report-task', report], FakeSonar(statuses=['FAILED']))
        self.assertEqual(code, 1)

    def test_compare_takes_the_project_key_from_the_properties(self):
        sonar = FakeSonar({'b': 85.0, 'develop': 85.0})
        with tempfile.TemporaryDirectory() as tmp:
            props = os.path.join(tmp, 'sonar-project.properties')
            with open(props, 'w', encoding='utf-8') as fh:
                fh.write('# comment\nsonar.projectKey=etendosoftware_go\nsonar.sources=src\n')
            code, out, _ = self.run_main(['compare', '--properties', props, '--branch', 'b', '--base', 'develop'], sonar)
        self.assertEqual(code, 0)
        self.assertEqual(sonar.asked[0], ('etendosoftware_go', 'b'))

    def test_missing_credentials_is_a_usage_error(self):
        code, _, err = self.run_main(['compare', '--properties', 'x', '--branch', 'b', '--base', 'develop'], None, {})
        self.assertEqual(code, 2)
        self.assertIn('SONAR_HOST_URL and SONAR_TOKEN are required', err)


if __name__ == '__main__':
    unittest.main()
