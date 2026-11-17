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
  mail     The failure mail Jenkins sends (postActions.groovy onFailure/onUnstable in
           com.etendoerp.jenkins.pipelines, ETP-6317): subject with the "[cell] " tag, and an HTML
           body with the cause, the cells to review and the per-cell matrix that cell-report.mjs
           writes as mail fragments, the commit, and the link to the run.
  load     The runner load while the E2E suite ran, from `vmstat -n -t` samples: whether a spec
           that timed out did so on a saturated runner (run queue above the vCPUs, no idle CPU,
           swapping) or on an idle one, where the cause is the app or the test.

Usage:
  scripts/full-pipeline-pilot-report.py summary --cell-summary FILE [--pw-dir DIR] [--run-url URL]
  scripts/full-pipeline-pilot-report.py datadog --cell-summary FILE [--jobs FILE] [--tag k:v]...
                                        [--dry-run]
  scripts/full-pipeline-pilot-report.py load --vmstat FILE [--window FILE] [--cpus N]
  scripts/full-pipeline-pilot-report.py mail --report-dir DIR --repo NAME --branch NAME --sha SHA
                                        --run-url URL --run-name TEXT [--pr-url URL] [--author NAME]
                                        [--failed-job NAME]... --out-html FILE --out-subject FILE

--window holds two lines, the UTC start and end of the suite as "YYYY-mm-dd HH:MM:SS"; without it
(or with only the start) every sample from the start on counts.

datadog reads DD_API_KEY, and DD_SITE (default datadoghq.eu), from the environment. Reporting
only: a missing or unreadable input, or a Datadog error, is printed and the exit code is still 0,
so a report never turns a run red.
"""

import argparse
import glob
import json
import os
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.request
from datetime import datetime, timezone

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
    """A tag value a dashboard can filter on: lowercase ASCII letters, digits and single
    underscores. Datadog would turn '(', '+' and spaces into underscores of its own, leaving values
    like 'integration__tomcat___playwright_'; accents are dropped the same way ('sin célula' ->
    'sin_celula')."""
    ascii_text = unicodedata.normalize('NFKD', str(value)).encode('ascii', 'ignore').decode('ascii')
    return re.sub(r'[^a-z0-9]+', '_', ascii_text.lower()).strip('_')


def _job_tag(name):
    """The job's tag without the "Pilot: " prefix the shadow jobs carry, so a job keeps its tag
    when the prefix goes away at the switch from Jenkins."""
    return _tag(re.sub(r'^\s*pilot:\s*', '', str(name), flags=re.IGNORECASE))


def _span(job):
    """(start, end) of a finished job, or None."""
    if job.get('status') != 'completed':
        return None
    try:
        return (datetime.fromisoformat(job['started_at'].replace('Z', '+00:00')),
                datetime.fromisoformat(job['completed_at'].replace('Z', '+00:00')))
    except (KeyError, AttributeError, TypeError, ValueError):
        return None


def _minutes(start, end):
    return round((end - start).total_seconds() / 60, 2)


def datadog_series(model, jobs=(), tags=(), now=None):
    """The /api/v2/series payload of one run: tests per suite and outcome, failing tests per cell,
    the duration of every finished job, and the run's own duration, from the first finished job's
    start to the last one's end: the jobs run in parallel, so that is what compares with the whole
    Jenkins build."""
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
    spans = []
    for job in jobs:
        span = _span(job)
        if span:
            spans.append(span)
            series.append(point('etendo.ci.pilot.job.duration_minutes', _minutes(*span),
                                [f"job:{_job_tag(job.get('name', ''))}", f"result:{job.get('conclusion') or 'unknown'}"]))
    if spans:
        series.append(point('etendo.ci.pilot.run.duration_minutes',
                            _minutes(min(s for s, _ in spans), max(e for _, e in spans)), []))
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


def vmstat_samples(text):
    """One dict per data line of `vmstat -n -t`: the numeric columns by header name, plus 'at',
    the sample's 'YYYY-mm-dd HH:MM:SS' timestamp (its last two fields)."""
    header, samples = None, []
    for line in text.splitlines():
        fields = line.split()
        if 'us' in fields and 'id' in fields and 'r' in fields:
            header = fields
            continue
        if not header or len(fields) < 3 or not fields[0].isdigit():
            continue
        numbers = fields[:-2]
        try:
            values = [int(v) for v in numbers]
        except ValueError:
            continue
        sample = dict(zip(header, values))
        sample['at'] = ' '.join(fields[-2:])
        samples.append(sample)
    return samples


def _pct(values, share):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(len(ordered) * share))]


def load_markdown(samples, start='', end='', cpus=4):
    """The runner load summary of the samples inside [start, end] (see the module docstring)."""
    window = [s for s in samples if (not start or s['at'] >= start) and (not end or s['at'] <= end)]
    out = ['## Carga del runner durante la suite E2E', '']
    if not window:
        return '\n'.join(out + ['Sin muestras de vmstat para la suite.']) + '\n'
    busy = [100 - s.get('id', 0) for s in window]
    queue = [s.get('r', 0) for s in window]
    wait = [s.get('wa', 0) for s in window]
    free_mb = [(s.get('free', 0) + s.get('buff', 0) + s.get('cache', 0)) // 1024 for s in window]
    swapped = sum(s.get('si', 0) + s.get('so', 0) for s in window)
    saturated = sum(1 for r in queue if r > cpus)
    out += [f'{len(window)} muestras cada 10 s, de {window[0]["at"]} a {window[-1]["at"]} UTC, {cpus} vCPU.', '',
            '| Métrica | Promedio | p95 | Máx |', '| --- | --- | --- | --- |',
            f'| CPU ocupada (%) | {sum(busy) // len(busy)} | {_pct(busy, 0.95)} | {max(busy)} |',
            f'| Procesos esperando CPU (r) | {sum(queue) / len(queue):.1f} | {_pct(queue, 0.95)} | {max(queue)} |',
            f'| Espera de disco (wa %) | {sum(wait) // len(wait)} | {_pct(wait, 0.95)} | {max(wait)} |',
            f'| Memoria libre + cache (MB, mín.) | | | {min(free_mb)} |', '']
    share = 100 * saturated // len(window)
    verdict = (f'CPU saturada (más procesos esperando que vCPU) en el {share} % de las muestras.'
               if saturated else 'La CPU nunca se saturó: nunca hubo más procesos esperando que vCPU.')
    out.append(verdict + (f' Hubo swap ({swapped} KB/s sumados).' if swapped else ' Sin swap.'))
    return '\n'.join(out) + '\n'


def _escape(text):
    return (str(text or '').replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
            .replace('"', '&quot;').replace("'", '&#39;'))


def failure_mail(repo, branch, sha, run_url, run_name, head='', fragment='', subject_tag='',
                 pr_url='', author='', failed_jobs=(), now=None):
    """Subject and HTML body of the failure mail, as Jenkins' postActions.onFailure builds them.

    head, fragment and subject_tag are cell-report.mjs's email-head.html, email.html (both already
    escaped there) and email-subject.txt; each may be empty, and then the mail goes without it.
    """
    tag = ' '.join(subject_tag.split())
    subject = (f"🚨 {tag + ' ' if tag else ''}Errors found in module '{repo}' on branch "
               f"'{branch}' - {run_name}")
    reason = 'Failed jobs: ' + ', '.join(failed_jobs) if failed_jobs else 'Build Failed'
    # With the report's cause on top, the reason is context only, as in onFailure.
    reason_line = (f"<p style='color:#656d76;font-size:12px;'>Mensaje del pipeline: "
                   f"<em>{_escape(reason)}</em></p>" if head.strip()
                   else f'<p><strong>⇢ Reasons:</strong> <em>{_escape(reason)}</em></p>')
    details = '' if head.strip() else 'The build failed because one or more test suites failed.<br>'
    commit = [f'<p><strong>Repository:</strong> {_escape(repo)}<br>',
              f'<strong>Branch:</strong> {_escape(branch)}<br>',
              f'<strong>Commit:</strong> {_escape(sha)}']
    if author:
        commit.append(f'<br><strong>Author:</strong> {_escape(author)}')
    if pr_url:
        commit.append(f"<br><strong>Pull request:</strong> <a href='{_escape(pr_url)}'>{_escape(pr_url)}</a>")
    commit.append('</p>')
    when = (now or datetime.now(timezone.utc)).strftime('%a %b %d %H:%M:%S UTC %Y')
    body = f"""<html>
  <head>
    <style>
      body {{ font-family: 'Arial', sans-serif; }}
      .header {{ font-size: 16px; font-weight: bold; color: #cb2727ff; }}
    </style>
  </head>
  <body>
    <p><em>{when}</em></p>
    <p>__________________________________________________________</p>
    <h2 class='header'>🚫 BUILD FAILED 🚫</h2>
    {head.strip()}
    {fragment.strip()}
    {''.join(commit)}
    {reason_line}
    <p>{details}For more information on the failing run, visit: <a href='{_escape(run_url)}'>{_escape(run_name)}</a></p>
    <p class='footer'><em>Best regards,<br>#EtendoBot 🤖 </em></p>
    <p>__________________________________________________________</p>
  </body>
</html>
"""
    return subject, body


def _read_text(path):
    try:
        with open(path, encoding='utf-8') as fh:
            return fh.read()
    except OSError:
        return None


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
    load = sub.add_parser('load')
    load.add_argument('--vmstat', required=True)
    load.add_argument('--window', default='')
    load.add_argument('--cpus', type=int, default=4)
    mail = sub.add_parser('mail')
    mail.add_argument('--report-dir', default='')
    for name in ('--repo', '--branch', '--sha', '--run-url', '--run-name', '--out-html', '--out-subject'):
        mail.add_argument(name, required=True)
    mail.add_argument('--pr-url', default='')
    mail.add_argument('--author', default='')
    mail.add_argument('--failed-job', action='append', default=[])
    return parser.parse_args(argv)


def main(argv=None, env=None):
    args = _parse(argv)
    env = os.environ if env is None else env
    if args.command == 'load':
        text = _read_text(args.vmstat)
        if text is None:
            print(f'No vmstat samples ({args.vmstat} missing): nothing to report.')
            return 0
        bounds = [line.strip() for line in (_read_text(args.window) or '').splitlines() if line.strip()]
        start, end = (bounds + ['', ''])[:2]
        sys.stdout.write(load_markdown(vmstat_samples(text), start, end, args.cpus))
        return 0
    if args.command == 'mail':
        # A missing report fragment only leaves it out: the mail still goes, as in Jenkins.
        fragment = lambda name: (_read_text(os.path.join(args.report_dir, name)) or '') if args.report_dir else ''
        subject, body = failure_mail(
            args.repo, args.branch, args.sha, args.run_url, args.run_name,
            head=fragment('email-head.html'), fragment=fragment('email.html'),
            subject_tag=fragment('email-subject.txt'), pr_url=args.pr_url, author=args.author,
            failed_jobs=args.failed_job)
        with open(args.out_html, 'w', encoding='utf-8') as fh:
            fh.write(body)
        with open(args.out_subject, 'w', encoding='utf-8') as fh:
            fh.write(subject)
        print(subject)
        return 0
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
