# @covers scripts/full-pipeline-pilot-report.py
"""Unit tests for scripts/full-pipeline-pilot-report.py.

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

_SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'full-pipeline-pilot-report.py')
_spec = importlib.util.spec_from_file_location('full_pipeline_pilot_report', _SCRIPT)
report = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(report)


def counts(passed=0, failed=0, skipped=0, flaky=0):
    return {'passed': passed, 'failed': failed, 'skipped': skipped, 'flaky': flaky}


MODEL = {
    'cells': ['ventas', 'almacenes', 'sin célula'],
    'totals': {
        'go-mock': counts(passed=22000, skipped=5),
        'go-integration': counts(passed=560, failed=1, skipped=8),
        'e2e-mocked': counts(),
        'e2e-integration': counts(passed=46, failed=1, flaky=1, skipped=10),
    },
    'suiteStatus': [
        {'id': 'go-mock', 'annotation': ''},
        {'id': 'go-integration', 'annotation': ''},
        {'id': 'e2e-mocked', 'annotation': ' (no se ejecuta en este pipeline)'},
        {'id': 'e2e-integration', 'annotation': ''},
    ],
    'failures': {
        'ventas': [{'suite': 'go-integration', 'name': 'SalesIT.posts', 'message': 'expected 1\nbut was 2'}],
        'almacenes': [{'suite': 'e2e-integration', 'name': 'Assets › Case 9', 'message': 'TimeoutError: 30000ms'}],
        'sin célula': [],
    },
    'cellsToReview': [{'cell': 'ventas', 'failed': 1}, {'cell': 'almacenes', 'failed': 1}],
    'cause': ['Go integración: 1 test fallido', 'E2E integración: 1 test fallido'],
    'notes': [],
    'warnings': ['3 tests sin célula'],
}


class SummaryMarkdownTest(unittest.TestCase):

    def test_lists_the_failing_tests_by_cell_with_the_first_line_of_their_message(self):
        md = report.summary_markdown(MODEL)
        self.assertIn('**ventas** · 1', md)
        self.assertIn('- Go integración · `SalesIT.posts` — expected 1', md)
        self.assertNotIn('but was 2', md)
        self.assertIn('**almacenes** · 1', md)
        self.assertIn('**Causa:** Go integración: 1 test fallido · E2E integración: 1 test fallido', md)

    def test_shows_every_suite_with_its_counts_and_annotation(self):
        md = report.summary_markdown(MODEL)
        self.assertIn('| Go mocks | 22000 | 0 | 0 | 5 |', md)
        self.assertIn('| E2E integración | 46 | 1 | 1 | 10 |', md)
        self.assertIn('| E2E mocked (no se ejecuta en este pipeline) | 0 | 0 | 0 | 0 |', md)

    def test_lists_the_flaky_tests_and_the_warnings(self):
        md = report.summary_markdown(MODEL, [('tests/a.spec.js', 'A › retried')])
        self.assertIn('### Flaky: fallaron y pasaron al reintentar', md)
        self.assertIn('- `tests/a.spec.js` › A › retried', md)
        self.assertIn('- 3 tests sin célula', md)

    def test_says_so_when_nothing_failed_and_links_the_run(self):
        clean = dict(MODEL, cellsToReview=[], cause=[], warnings=[])
        md = report.summary_markdown(clean, run_url='https://example.test/run/1')
        self.assertIn('Ningún test falló.', md)
        self.assertNotIn('Causa', md)
        self.assertIn('[el artefacto `cell-report` de la corrida](https://example.test/run/1)', md)

    def test_caps_the_failures_shown(self):
        many = [{'suite': 'go-mock', 'name': f'T{i}', 'message': ''} for i in range(report.MAX_FAILURES + 5)]
        model = dict(MODEL, failures={'ventas': many}, cellsToReview=[{'cell': 'ventas', 'failed': len(many)}])
        md = report.summary_markdown(model)
        self.assertIn(f'`T{report.MAX_FAILURES - 1}`', md)
        self.assertNotIn(f'`T{report.MAX_FAILURES}`', md)
        self.assertIn('- … y 5 más en el reporte completo.', md)


class FlakyTestsTest(unittest.TestCase):

    def test_reads_only_the_flaky_tests_of_every_reporter_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            with open(os.path.join(tmp, 'pw-e2e-integration-1.json'), 'w', encoding='utf-8') as fh:
                json.dump({'tests': [
                    {'file': 'tests/a.spec.js', 'title': 'A', 'outcome': 'flaky'},
                    {'file': 'tests/b.spec.js', 'title': 'B', 'outcome': 'expected'},
                    {'file': 'tests/c.spec.js', 'title': 'C', 'outcome': 'unexpected'},
                ]}, fh)
            with open(os.path.join(tmp, 'broken.json'), 'w', encoding='utf-8') as fh:
                fh.write('{not json')
            self.assertEqual(report.flaky_tests(tmp), [('tests/a.spec.js', 'A')])

    def test_no_directory_means_no_flaky_tests(self):
        self.assertEqual(report.flaky_tests(''), [])


class DatadogSeriesTest(unittest.TestCase):

    def series(self, **kwargs):
        payload = report.datadog_series(MODEL, now=1000, **kwargs)
        return {(s['metric'], tuple(s['tags'])): s['points'][0]['value'] for s in payload['series']}

    def test_sends_every_suite_and_outcome_with_the_base_tags(self):
        series = self.series(tags=['ci:github-actions'])
        self.assertEqual(series[('etendo.ci.pilot.tests', ('ci:github-actions', 'suite:e2e-integration', 'outcome:flaky'))], 1)
        self.assertEqual(series[('etendo.ci.pilot.tests', ('ci:github-actions', 'suite:e2e-mocked', 'outcome:passed'))], 0)
        self.assertEqual(sum(1 for m, _ in series if m == 'etendo.ci.pilot.tests'), 16)

    def test_sends_the_failing_tests_of_every_cell_including_the_green_ones(self):
        series = self.series()
        self.assertEqual(series[('etendo.ci.pilot.cell.failed_tests', ('cell:ventas',))], 1)
        self.assertEqual(series[('etendo.ci.pilot.cell.failed_tests', ('cell:sin_celula',))], 0)

    def test_sends_the_duration_of_the_finished_jobs_and_of_the_run(self):
        jobs = [
            {'name': 'Go tests + coverage', 'status': 'completed', 'conclusion': 'success',
             'started_at': '2026-11-05T17:58:06Z', 'completed_at': '2026-11-05T18:17:09Z'},
            {'name': 'Integration (Tomcat + Playwright)', 'status': 'completed', 'conclusion': 'failure',
             'started_at': '2026-11-05T17:58:06Z', 'completed_at': '2026-11-05T18:24:06Z'},
            {'name': 'Cell report', 'status': 'in_progress', 'conclusion': None,
             'started_at': '2026-11-05T18:48:00Z', 'completed_at': None},
        ]
        series = self.series(jobs=jobs)
        durations = {k: v for k, v in series.items() if 'duration' in k[0]}
        self.assertEqual(durations, {
            ('etendo.ci.pilot.job.duration_minutes', ('job:go_tests_coverage', 'result:success')): 19.05,
            ('etendo.ci.pilot.job.duration_minutes', ('job:integration_tomcat_playwright', 'result:failure')): 26.0,
            ('etendo.ci.pilot.run.duration_minutes', ()): 26.0,
        })

    def test_no_finished_job_sends_no_run_duration(self):
        series = self.series(jobs=[{'name': 'Cell report', 'status': 'in_progress'}])
        self.assertFalse([k for k in series if 'duration' in k[0]])

    def test_a_job_keeps_its_tag_with_or_without_the_pilot_prefix(self):
        self.assertEqual(report._job_tag('Pilot: Go tests + coverage'), 'go_tests_coverage')
        self.assertEqual(report._job_tag('Go tests + coverage'), 'go_tests_coverage')

    def test_tag_values_are_plain_ascii_words(self):
        self.assertEqual(report._tag('Integration (Tomcat + Playwright)'), 'integration_tomcat_playwright')
        self.assertEqual(report._tag(' sin célula '), 'sin_celula')


class MainTest(unittest.TestCase):

    def run_main(self, argv, env=None):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = report.main(argv, env or {})
        return code, out.getvalue()

    def write_model(self, tmp):
        path = os.path.join(tmp, 'summary.json')
        with open(path, 'w', encoding='utf-8') as fh:
            json.dump(MODEL, fh)
        return path

    def test_a_missing_report_is_not_an_error(self):
        code, out = self.run_main(['summary', '--cell-summary', '/nonexistent/summary.json'])
        self.assertEqual(code, 0)
        self.assertIn('nothing to report', out)

    def test_summary_prints_the_markdown(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = self.run_main(['summary', '--cell-summary', self.write_model(tmp)])
        self.assertEqual(code, 0)
        self.assertIn('## Tests por célula', out)

    def test_datadog_without_a_key_sends_nothing(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(report, 'post_series') as post:
            code, out = self.run_main(['datadog', '--cell-summary', self.write_model(tmp)])
        self.assertEqual(code, 0)
        self.assertIn('DD_API_KEY is not set', out)
        post.assert_not_called()

    def test_datadog_posts_to_the_eu_site_by_default(self):
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(report, 'post_series', return_value=(202, '{}')) as post:
            code, out = self.run_main(['datadog', '--cell-summary', self.write_model(tmp), '--tag', 'ci:github-actions'],
                                      {'DD_API_KEY': 'k'})
        self.assertEqual(code, 0)
        self.assertIn('Sent', out)
        payload, key, site = post.call_args.args
        self.assertEqual((key, site), ('k', 'datadoghq.eu'))
        self.assertTrue(all('ci:github-actions' in s['tags'] for s in payload['series']))

    def test_a_datadog_rejection_is_a_warning_not_a_failure(self):
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(report, 'post_series', return_value=(403, 'Forbidden')):
            code, out = self.run_main(['datadog', '--cell-summary', self.write_model(tmp)], {'DD_API_KEY': 'k'})
        self.assertEqual(code, 0)
        self.assertIn('::warning::Datadog did not take the metrics (status 403)', out)


VMSTAT = """\
procs -----------memory---------- ---swap-- -----io---- -system-- -------cpu------- -----timestamp-----
 r  b   swpd   free   buff  cache   si   so    bi    bo   in   cs us sy id wa st gu                 UTC
 1  0      0 8000000 100000 2000000    0    0    5   10  100  200  5  2 93  0  0  0 2026-11-05 19:40:00
 2  0      0 4000000 100000 2000000    0    0    5   10  100  200 40 10 50  0  0  0 2026-11-05 19:40:10
 9  1      0 1000000 100000 1000000    0    0    5   10  100  200 80 18  0  2  0  0 2026-11-05 19:40:20
 3  0      0 3000000 100000 2000000    0    0    5   10  100  200 50 10 40  0  0  0 2026-11-05 19:40:30
"""


class LoadTest(unittest.TestCase):

    def test_reads_every_vmstat_column_by_its_header_and_the_timestamp(self):
        samples = report.vmstat_samples(VMSTAT)
        self.assertEqual(len(samples), 4)
        self.assertEqual(samples[2]['r'], 9)
        self.assertEqual(samples[2]['id'], 0)
        self.assertEqual(samples[2]['at'], '2026-11-05 19:40:20')

    def test_summarizes_only_the_samples_inside_the_window(self):
        md = report.load_markdown(report.vmstat_samples(VMSTAT), '2026-11-05 19:40:10', '2026-11-05 19:40:30')
        self.assertIn('3 muestras cada 10 s, de 2026-11-05 19:40:10 a 2026-11-05 19:40:30 UTC, 4 vCPU.', md)
        self.assertIn('| CPU ocupada (%) | 70 | 100 | 100 |', md)
        self.assertIn('| Procesos esperando CPU (r) | 4.7 | 9 | 9 |', md)
        self.assertIn('| Memoria libre + cache (MB, mín.) | | | 2050 |', md)

    def test_says_how_often_the_cpu_was_saturated(self):
        md = report.load_markdown(report.vmstat_samples(VMSTAT), cpus=4)
        self.assertIn('CPU saturada (más procesos esperando que vCPU) en el 25 % de las muestras. Sin swap.', md)

    def test_an_idle_runner_says_the_cpu_never_saturated(self):
        md = report.load_markdown(report.vmstat_samples(VMSTAT), end='2026-11-05 19:40:10')
        self.assertIn('La CPU nunca se saturó', md)

    def test_no_samples_in_the_window(self):
        md = report.load_markdown(report.vmstat_samples(VMSTAT), start='2026-11-06 00:00:00')
        self.assertIn('Sin muestras de vmstat para la suite.', md)

    def test_main_reads_the_window_file_and_tolerates_a_missing_log(self):
        out = io.StringIO()
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stdout(out):
            log, window = os.path.join(tmp, 'vmstat.log'), os.path.join(tmp, 'window')
            with open(log, 'w', encoding='utf-8') as fh:
                fh.write(VMSTAT)
            with open(window, 'w', encoding='utf-8') as fh:
                fh.write('2026-11-05 19:40:20\n2026-11-05 19:40:30\n')
            self.assertEqual(report.main(['load', '--vmstat', log, '--window', window], {}), 0)
            self.assertEqual(report.main(['load', '--vmstat', os.path.join(tmp, 'none.log')], {}), 0)
        self.assertIn('2 muestras cada 10 s', out.getvalue())
        self.assertIn('No vmstat samples', out.getvalue())


class PostSeriesTest(unittest.TestCase):

    def test_posts_the_payload_with_the_api_key_header(self):
        response = mock.MagicMock(status=202)
        response.read.return_value = b'{}'
        response.__enter__.return_value = response
        with mock.patch.object(report.urllib.request, 'urlopen', return_value=response) as urlopen:
            status, _ = report.post_series({'series': []}, 'k', 'datadoghq.eu')
        request = urlopen.call_args.args[0]
        self.assertEqual(status, 202)
        self.assertEqual(request.full_url, 'https://api.datadoghq.eu/api/v2/series')
        self.assertEqual(request.get_header('Dd-api-key'), 'k')

    def test_a_network_error_comes_back_as_status_zero(self):
        with mock.patch.object(report.urllib.request, 'urlopen', side_effect=report.urllib.error.URLError('down')):
            status, body = report.post_series({'series': []}, 'k')
        self.assertEqual(status, 0)
        self.assertIn('down', body)


if __name__ == '__main__':
    unittest.main()
