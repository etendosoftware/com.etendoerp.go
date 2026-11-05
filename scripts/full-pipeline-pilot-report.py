#!/usr/bin/env python3
"""Run summary and Datadog metrics of the full pipeline pilot (ETP-6149).

Both read the summary.json that cell-report.mjs (com.etendoerp.jenkins.pipelines, ETP-5799) writes:
the same per-cell model the Jenkins builds archive, which the "Etendo — Tests por célula" board is
built from.

  summary  Markdown for the run summary: tests per suite, the cause of a red run, the failing tests
           by cell with their message, and the flaky E2E tests (from the JSON the Playwright cell
           reporter writes, since summary.json only counts them).
  datadog  Posts the run as etendo.ci.pilot.* metrics. A namespace of its own: the board sums the
           etendo.qa.cell.* metrics of the Jenkins builds, and the shadow runs must not add to them.

Usage:
  scripts/full-pipeline-pilot-report.py summary --cell-summary FILE [--pw-dir DIR] [--run-url URL]
  scripts/full-pipeline-pilot-report.py datadog --cell-summary FILE [--jobs FILE] [--tag k:v]...
                                        [--dry-run]

datadog reads DD_API_KEY, and DD_SITE (default datadoghq.eu), from the environment. Reporting
only: a missing or unreadable input, or a Datadog error, is printed and the exit code is still 0,
so a report never turns a run red.
"""

import argparse
import glob
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime

SUITE_LABELS = {
    'go-mock': 'Go mocks',
    'go-integration': 'Go integración',
    'e2e-mocked': 'E2E mocked',
    'e2e-integration': 'E2E integración',
}
OUTCOMES = ('passed', 'failed', 'flaky', 'skipped')
MAX_FAILURES = 40
MAX_FLAKY = 20
MAX_MESSAGE = 200
GAUGE = 3
DEFAULT_SITE = 'datadoghq.eu'


def load_json(path):
    """The parsed file, or None when it is missing or not JSON."""
    try:
        with open(path, encoding='utf-8') as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return None


def flaky_tests(pw_dir):
    """(file, title) of every E2E test that failed and then passed on retry, in file order."""
    found = []
    for path in sorted(glob.glob(os.path.join(pw_dir or '', '*.json'))):
        data = load_json(path) or {}
        for test in data.get('tests') or []:
            if test.get('outcome') == 'flaky':
                found.append((test.get('file', ''), test.get('title', '')))
    return found


def _first_line(message):
    line = (message or '').strip().splitlines()[0] if (message or '').strip() else ''
    return line if len(line) <= MAX_MESSAGE else line[:MAX_MESSAGE - 1] + '…'


def _cell(text):
    """A table cell: pipes and line breaks would split the row."""
    return str(text).replace('|', '\\|').replace('\n', ' ')


def summary_markdown(model, flaky=(), run_url=''):
    """The run summary of one cell-report model (see the module docstring)."""
    out = ['## Tests por célula', '',
           '| Suite | Pasados | Fallidos | Flaky | Omitidos |', '| --- | --- | --- | --- | --- |']
    status = {s.get('id'): s for s in model.get('suiteStatus') or []}
    totals = model.get('totals') or {}
    for suite, label in SUITE_LABELS.items():
        counts = totals.get(suite) or {}
        note = (status.get(suite) or {}).get('annotation', '')
        out.append(f"| {label}{_cell(note)} | " + ' | '.join(str(counts.get(k, 0)) for k in OUTCOMES) + ' |')

    cause = model.get('cause') or []
    if cause:
        out += ['', '**Causa:** ' + ' · '.join(cause)]

    failures = model.get('failures') or {}
    review = model.get('cellsToReview') or []
    out.append('')
    if not review:
        out.append('Ningún test falló.')
    else:
        out.append('### Tests que fallaron, por célula')
        shown = 0
        for entry in review:
            cell = entry.get('cell', '')
            out += ['', f"**{cell}** · {entry.get('failed', 0)}"]
            for failure in failures.get(cell) or []:
                if shown == MAX_FAILURES:
                    break
                label = SUITE_LABELS.get(failure.get('suite'), failure.get('suite', ''))
                message = _first_line(failure.get('message'))
                out.append(f"- {label} · `{failure.get('name', '')}`" + (f" — {message}" if message else ''))
                shown += 1
        total = sum(e.get('failed', 0) for e in review)
        if total > shown:
            out.append(f'- … y {total - shown} más en el reporte completo.')

    if flaky:
        out += ['', '### Flaky: fallaron y pasaron al reintentar']
        out += [f'- `{f}` › {t}' for f, t in list(flaky)[:MAX_FLAKY]]
        if len(flaky) > MAX_FLAKY:
            out.append(f'- … y {len(flaky) - MAX_FLAKY} más.')

    notes = (model.get('notes') or []) + (model.get('warnings') or [])
    if notes:
        out += ['', '### Avisos'] + [f'- {n}' for n in notes]

    report = 'el artefacto `cell-report` de la corrida'
    out += ['', f"Reporte completo: {f'[{report}]({run_url})' if run_url else report}."]
    return '\n'.join(out) + '\n'


def _tag(value):
    """Datadog turns spaces into underscores anyway; doing it here keeps the tags predictable."""
    return str(value).strip().lower().replace(' ', '_')


def _minutes(job):
    try:
        start = datetime.fromisoformat(job['started_at'].replace('Z', '+00:00'))
        end = datetime.fromisoformat(job['completed_at'].replace('Z', '+00:00'))
    except (KeyError, AttributeError, TypeError, ValueError):
        return None
    return round((end - start).total_seconds() / 60, 2)


def datadog_series(model, jobs=(), tags=(), now=None):
    """The /api/v2/series payload of one run: tests per suite and outcome, failing tests per cell,
    and the duration of every finished job."""
    now = int(now if now is not None else time.time())
    base = list(tags)

    def point(metric, value, extra):
        return {'metric': metric, 'type': GAUGE, 'points': [{'timestamp': now, 'value': value}],
                'tags': base + extra}

    series = []
    totals = model.get('totals') or {}
    for suite in SUITE_LABELS:
        counts = totals.get(suite) or {}
        for outcome in OUTCOMES:
            series.append(point('etendo.ci.pilot.tests', counts.get(outcome, 0),
                                [f'suite:{suite}', f'outcome:{outcome}']))
    failures = model.get('failures') or {}
    for cell in model.get('cells') or []:
        series.append(point('etendo.ci.pilot.cell.failed_tests', len(failures.get(cell) or []),
                            [f'cell:{_tag(cell)}']))
    for job in jobs:
        minutes = _minutes(job) if job.get('status') == 'completed' else None
        if minutes is not None:
            series.append(point('etendo.ci.pilot.job.duration_minutes', minutes,
                                [f"job:{_tag(job.get('name', ''))}", f"result:{job.get('conclusion') or 'unknown'}"]))
    return {'series': series}


def post_series(payload, api_key, site=DEFAULT_SITE):
    """POSTs the payload; returns (HTTP status, body). Network errors come back as status 0."""
    request = urllib.request.Request(
        f'https://api.{site}/api/v2/series', data=json.dumps(payload).encode('utf-8'), method='POST',
        headers={'Content-Type': 'application/json', 'DD-API-KEY': api_key})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, response.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode('utf-8', 'replace')
    except (urllib.error.URLError, OSError) as error:
        return 0, str(error)


def _parse(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest='command', required=True)
    summary = sub.add_parser('summary')
    summary.add_argument('--cell-summary', required=True)
    summary.add_argument('--pw-dir', default='')
    summary.add_argument('--run-url', default='')
    datadog = sub.add_parser('datadog')
    datadog.add_argument('--cell-summary', required=True)
    datadog.add_argument('--jobs', default='')
    datadog.add_argument('--tag', action='append', default=[])
    datadog.add_argument('--dry-run', action='store_true')
    return parser.parse_args(argv)


def main(argv=None, env=None):
    args = _parse(argv)
    env = os.environ if env is None else env
    model = load_json(args.cell_summary)
    if model is None:
        print(f'No per-cell report ({args.cell_summary} missing or unreadable): nothing to report.')
        return 0
    if args.command == 'summary':
        sys.stdout.write(summary_markdown(model, flaky_tests(args.pw_dir), args.run_url))
        return 0
    jobs = (load_json(args.jobs) or {}).get('jobs') or [] if args.jobs else []
    payload = datadog_series(model, jobs, args.tag)
    if args.dry_run:
        print(json.dumps(payload, indent=2))
        return 0
    api_key = env.get('DD_API_KEY', '')
    if not api_key:
        print('DD_API_KEY is not set: the run is not sent to Datadog.')
        return 0
    status, body = post_series(payload, api_key, env.get('DD_SITE') or DEFAULT_SITE)
    if 200 <= status < 300:
        print(f"Sent {len(payload['series'])} series to Datadog.")
    else:
        print(f'::warning::Datadog did not take the metrics (status {status}): {body[:300]}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
