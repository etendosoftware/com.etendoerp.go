# @covers scripts/compare-sonar-coverage.py
"""Unit tests for scripts/compare-sonar-coverage.py.

Run from the module root:  python3 -m unittest discover -s scripts
"""

import importlib.util
import io
import json
import os
import tempfile
import unittest
from unittest import mock

_SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'compare-sonar-coverage.py')
_spec = importlib.util.spec_from_file_location('compare_sonar_coverage', _SCRIPT)
gate = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gate)


class FakeSonar:
    """Coverage by selector ('pr:<key>', 'branch:<name>' or 'main'); a value that is an exception
    is raised. Task statuses are returned in order, the last one repeating."""

    def __init__(self, coverage=None, statuses=('SUCCESS',), gate_status=('OK', []), analysis='A1'):
        self.cov = coverage or {}
        self.statuses = list(statuses)
        self.asked = []
        self.gate_status = gate_status
        self.analysis = analysis
        self.gates_asked = []

    def coverage(self, key, pullRequest='', branch=''):
        selector = f'pr:{pullRequest}' if pullRequest else f'branch:{branch}' if branch else 'main'
        self.asked.append((key, selector))
        value = self.cov.get(selector)
        if isinstance(value, Exception):
            raise value
        return value

    def task_status(self, task_id):
        return self.statuses.pop(0) if len(self.statuses) > 1 else self.statuses[0]

    def task_analysis_id(self, task_id):
        return self.analysis

    def quality_gate(self, analysis_id):
        self.gates_asked.append(analysis_id)
        if isinstance(self.gate_status, Exception):
            raise self.gate_status
        return self.gate_status


def run(argv, sonar, env=None):
    out = io.StringIO()
    code = gate.main(argv + ['--project-key', 'key'], env or {}, sonar, out, sleep=lambda s: None)
    return code, out.getvalue()


class RuleTest(unittest.TestCase):

    def test_below_the_minimum_fails_whatever_the_base(self):
        code, out = run(['--base-branch', 'develop', '--pull-request', '12'],
                        FakeSonar({'pr:12': 69.0, 'branch:develop': 60.0}))
        self.assertEqual(code, 1)
        self.assertIn('COVERAGE BELOW MINIMUM: 69.00% < 70.00%', out)

    def test_more_than_the_tolerance_below_the_base_fails(self):
        code, out = run(['--base-branch', 'develop', '--pull-request', '12'],
                        FakeSonar({'pr:12': 83.5, 'branch:develop': 85.0}))
        self.assertEqual(code, 1)
        self.assertIn('COVERAGE DECREASED: 83.50% < 84.00%', out)

    def test_within_the_tolerance_passes_and_shows_both_figures(self):
        code, out = run(['--base-branch', 'main', '--pull-request', '12'],
                        FakeSonar({'pr:12': 84.4, 'branch:main': 85.0}))
        self.assertEqual(code, 0)
        self.assertIn('| PR #12 | 84.40% |', out)
        self.assertIn('| main (base) | 85.00% |', out)
        self.assertIn('✅ Coverage is OK', out)

    def test_a_base_without_coverage_only_checks_the_minimum(self):
        code, out = run(['--base-branch', 'develop', '--branch', 'feature/x'],
                        FakeSonar({'branch:feature/x': 75.0, 'branch:develop': gate.NotFound('/x')}))
        self.assertEqual(code, 0)
        self.assertIn('no coverage on Sonar yet', out)

    def test_the_thresholds_come_from_the_environment(self):
        code, _ = run(['--base-branch', 'develop', '--pull-request', '12'],
                      FakeSonar({'pr:12': 80.0, 'branch:develop': 85.0}),
                      {'COVERAGE_TOLERANCE': '6', 'COVERAGE_MINIMUM': '50'})
        self.assertEqual(code, 0)

    def test_an_80_minimum_from_the_environment_fails_a_coverage_the_default_would_pass(self):
        code, out = run(['--base-branch', 'develop', '--pull-request', '12'],
                        FakeSonar({'pr:12': 75.0, 'branch:develop': 75.0}),
                        {'COVERAGE_MINIMUM': '80', 'COVERAGE_TOLERANCE': '1'})
        self.assertEqual(code, 1)
        self.assertIn('COVERAGE BELOW MINIMUM: 75.00% < 80.00%', out)

    def test_unset_organization_variables_arrive_empty_and_keep_the_defaults(self):
        code, out = run(['--base-branch', 'develop', '--pull-request', '12'],
                        FakeSonar({'pr:12': 75.0, 'branch:develop': 75.0}),
                        {'COVERAGE_MINIMUM': '', 'COVERAGE_TOLERANCE': ''})
        self.assertEqual(code, 0)
        self.assertIn('Rule: at least 70.00%, and no more than 1.00pp below the base.', out)

    def test_without_a_selector_reads_the_project_main_branch_as_run_sonar_did(self):
        sonar = FakeSonar({'main': 80.0, 'branch:develop': 80.0})
        run(['--base-branch', 'develop'], sonar)
        self.assertEqual(sonar.asked[0], ('key', 'main'))


class ContextTest(unittest.TestCase):

    def test_push_context_never_blocks_on_an_unreadable_current_coverage(self):
        code, out = run(['--base-branch', 'develop', '--pull-request', '12'],
                        FakeSonar({'pr:12': gate.SonarError('HTTP 500')}))
        self.assertEqual(code, 0)
        self.assertIn('SKIPPED, not blocking', out)

    def test_ci_context_fails_closed_on_an_unreadable_current_coverage(self):
        code, out = run(['--base-branch', 'develop', '--pull-request', '12', '--context', 'ci'],
                        FakeSonar({'pr:12': gate.SonarError('HTTP 403: the token cannot read the project')}))
        self.assertEqual(code, 1)
        self.assertIn('Coverage could not be evaluated', out)

    def test_ci_context_fails_closed_when_the_base_cannot_be_read(self):
        code, _ = run(['--base-branch', 'develop', '--pull-request', '12', '--context', 'ci'],
                      FakeSonar({'pr:12': 85.0, 'branch:develop': gate.SonarError('HTTP 502')}))
        self.assertEqual(code, 1)

    def test_push_context_only_checks_the_minimum_when_the_base_cannot_be_read(self):
        code, _ = run(['--base-branch', 'develop', '--pull-request', '12'],
                      FakeSonar({'pr:12': 85.0, 'branch:develop': gate.SonarError('HTTP 502')}))
        self.assertEqual(code, 0)

    def test_no_coverage_on_the_analysis_fails_in_ci(self):
        code, out = run(['--base-branch', 'develop', '--pull-request', '12', '--context', 'ci'], FakeSonar({}))
        self.assertEqual(code, 1)
        self.assertIn('did the analysis import the JaCoCo report', out)

    def test_missing_credentials_skip_on_push_and_fail_in_ci(self):
        out = io.StringIO()
        self.assertEqual(gate.main(['--base-branch', 'develop', '--project-key', 'k'], {}, None, out), 0)
        self.assertEqual(gate.main(['--base-branch', 'develop', '--project-key', 'k', '--context', 'ci'], {}, None, out), 1)


class WaitTest(unittest.TestCase):

    def report_task(self, tmp, content):
        path = os.path.join(tmp, 'report-task.txt')
        with open(path, 'w', encoding='utf-8') as fh:
            fh.write(content)
        return path

    def test_waits_for_the_analysis_before_reading_the_coverage(self):
        sonar = FakeSonar({'pr:12': 85.0, 'branch:develop': 85.0}, statuses=['PENDING', 'IN_PROGRESS', 'SUCCESS'])
        with tempfile.TemporaryDirectory() as tmp:
            code, _ = run(['--base-branch', 'develop', '--pull-request', '12', '--context', 'ci',
                           '--report-task', self.report_task(tmp, 'ceTaskId=AX12\n')], sonar)
        self.assertEqual(code, 0)
        self.assertEqual(sonar.statuses, ['SUCCESS'])

    def test_a_failed_analysis_fails_in_ci(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run(['--base-branch', 'develop', '--pull-request', '12', '--context', 'ci',
                             '--report-task', self.report_task(tmp, 'ceTaskId=AX12\n')], FakeSonar(statuses=['FAILED']))
        self.assertEqual(code, 1)
        self.assertIn('ended FAILED', out)

    def test_gives_up_at_the_timeout(self):
        times = iter([0, 50, 700])
        status = gate.wait_for_task(FakeSonar(statuses=['IN_PROGRESS']), 't', 600,
                                    sleep=lambda s: None, clock=lambda: next(times))
        self.assertEqual(status, 'TIMEOUT')


class QualityGateTest(unittest.TestCase):

    FAILED = ('ERROR', [{'status': 'ERROR', 'metricKey': 'new_violations', 'comparator': 'GT',
                         'errorThreshold': '0', 'actualValue': '2'}])

    def gate_run(self, sonar, extra=('--check-quality-gate',), current=85.0):
        sonar.cov = {'pr:12': current, 'branch:main': 85.0}
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, 'report-task.txt')
            with open(path, 'w', encoding='utf-8') as fh:
                fh.write('ceTaskId=AX12\n')
            return run(['--base-branch', 'main', '--pull-request', '12', '--context', 'ci',
                        '--report-task', path, *extra], sonar)

    def test_a_failed_gate_fails_and_lists_its_conditions_after_the_coverage(self):
        code, out = self.gate_run(FakeSonar(gate_status=self.FAILED))
        self.assertEqual(code, 1)
        self.assertLess(out.index('Coverage is OK'), out.index('QUALITY GATE FAILED'))
        self.assertIn('- new_violations: 2 (GT 0)', out)

    def test_a_passed_gate_of_the_same_analysis_passes(self):
        sonar = FakeSonar(analysis='AN-7')
        code, out = self.gate_run(sonar)
        self.assertEqual(code, 0)
        self.assertEqual(sonar.gates_asked, ['AN-7'])
        self.assertIn('Quality gate passed', out)

    def test_the_gate_is_reported_even_when_the_coverage_fails(self):
        code, out = self.gate_run(FakeSonar(gate_status=self.FAILED), current=60.0)
        self.assertEqual(code, 1)
        self.assertIn('COVERAGE BELOW MINIMUM', out)
        self.assertIn('QUALITY GATE FAILED', out)

    def test_an_unreadable_gate_fails_in_ci(self):
        code, out = self.gate_run(FakeSonar(gate_status=gate.SonarError('HTTP 500')))
        self.assertEqual(code, 1)
        self.assertIn('the quality gate could not be read', out)

    def test_a_project_without_gate_passes_with_a_warning(self):
        code, out = self.gate_run(FakeSonar(gate_status=('NONE', [])))
        self.assertEqual(code, 0)
        self.assertIn('no quality gate', out)

    def test_without_the_flag_the_gate_is_not_read(self):
        sonar = FakeSonar(gate_status=self.FAILED)
        code, _ = self.gate_run(sonar, extra=())
        self.assertEqual(code, 0)
        self.assertEqual(sonar.gates_asked, [])

    def test_the_flag_needs_a_report_task(self):
        code, out = run(['--base-branch', 'main', '--pull-request', '12', '--check-quality-gate'], FakeSonar())
        self.assertEqual(code, 2)
        self.assertIn('needs --report-task', out)


class SonarClientTest(unittest.TestCase):

    def response(self, payload):
        resp = mock.MagicMock()
        resp.read.return_value = json.dumps(payload).encode()
        resp.__enter__.return_value = resp
        return resp

    def test_reads_a_pull_request_coverage_with_basic_auth(self):
        opener = mock.MagicMock(return_value=self.response(
            {'component': {'measures': [{'metric': 'coverage', 'value': '85.3'}]}}))
        sonar = gate.Sonar('https://sonar.example/', 'tok', opener)
        self.assertEqual(sonar.coverage('key', pullRequest='1574'), 85.3)
        request = opener.call_args.args[0]
        self.assertIn('/api/measures/component?', request.full_url)
        self.assertIn('pullRequest=1574', request.full_url)
        self.assertNotIn('branch=', request.full_url)
        self.assertEqual(request.get_header('Authorization'), 'Basic dG9rOg==')

    def test_reads_a_new_code_value_under_period(self):
        opener = mock.MagicMock(return_value=self.response(
            {'component': {'measures': [{'metric': 'coverage', 'period': {'value': '72.5'}}]}}))
        self.assertEqual(gate.Sonar('https://s', 't', opener).coverage('key', branch='develop'), 72.5)

    def test_a_404_is_not_found_and_a_403_names_the_missing_permission(self):
        def http(code):
            return mock.MagicMock(side_effect=gate.urllib.error.HTTPError('u', code, 'x', {}, io.BytesIO(b'')))
        with self.assertRaises(gate.NotFound):
            gate.Sonar('https://s', 't', http(404)).coverage('key', branch='b')
        with self.assertRaises(gate.SonarError) as raised:
            gate.Sonar('https://s', 't', http(403)).coverage('key', branch='b')
        self.assertIn('Browse permission', str(raised.exception))

    def test_reads_the_failed_conditions_of_an_analysis_gate(self):
        opener = mock.MagicMock(return_value=self.response({'projectStatus': {'status': 'ERROR', 'conditions': [
            {'status': 'OK', 'metricKey': 'new_coverage'},
            {'status': 'ERROR', 'metricKey': 'new_violations', 'actualValue': '2'}]}}))
        status, failed = gate.Sonar('https://s', 't', opener).quality_gate('AN-7')
        self.assertEqual(status, 'ERROR')
        self.assertEqual([c['metricKey'] for c in failed], ['new_violations'])
        self.assertIn('/api/qualitygates/project_status?analysisId=AN-7', opener.call_args.args[0].full_url)


if __name__ == '__main__':
    unittest.main()
