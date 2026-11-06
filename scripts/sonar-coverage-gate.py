#!/usr/bin/env python3
"""Coverage gate of the full pipeline in GitHub Actions: the Jenkins "Compare Coverage Results" stage.

The pipeline publishes a SonarQube branch analysis with the merged JaCoCo report. This script waits
until SonarQube has processed it, then applies the rules Jenkins applies (sonarUtils.compareCoverage
in com.etendoerp.jenkins.pipelines):

  - the branch's coverage must be at least --minimum (Jenkins' COVERAGE_MINIMUM, 70);
  - and it must not be more than --tolerance points (COVERAGE_TOLERANCE, 1) below the base branch:
    main for a PR into main or a push to main, develop otherwise.

Usage:
  scripts/sonar-coverage-gate.py wait --report-task FILE [--timeout SECONDS]
  scripts/sonar-coverage-gate.py compare --properties FILE --branch NAME --base NAME
                                         [--minimum 70] [--tolerance 1]

wait reads the ceTaskId the scanner writes to .scannerwork/report-task.txt and polls the compute
task: it waits for the analysis itself, never a fixed time (Jenkins waits 10 minutes on a branch's
first analysis). compare prints a Markdown summary for the run summary.

Environment: SONAR_HOST_URL, and SONAR_TOKEN (sent as the user of HTTP basic auth, as Jenkins'
curl -u token: does).

Exit codes: 0 passed (or the analysis is ready), 1 the coverage rule failed (or the analysis
failed or timed out), 2 SonarQube could not be read or the arguments are wrong.
"""

import argparse
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

DONE = {'SUCCESS', 'FAILED', 'CANCELED'}


class SonarError(Exception):
    """SonarQube could not be read."""


def read_properties(path):
    """key=value lines of a .properties file ('#' comments ignored)."""
    props = {}
    with open(path, encoding='utf-8') as fh:
        for line in fh:
            line = line.strip()
            if line and not line.startswith('#') and '=' in line:
                key, value = line.split('=', 1)
                props[key.strip()] = value.strip()
    return props


class Sonar:
    def __init__(self, host, token, opener=None):
        self.host = host.rstrip('/')
        self.auth = 'Basic ' + base64.b64encode(f'{token}:'.encode()).decode()
        self.opener = opener or urllib.request.urlopen

    def get(self, path, **params):
        url = f'{self.host}{path}?{urllib.parse.urlencode(params)}'
        request = urllib.request.Request(url, headers={'Authorization': self.auth})
        try:
            with self.opener(request, timeout=30) as response:
                return json.loads(response.read().decode('utf-8'))
        except urllib.error.HTTPError as error:
            raise SonarError(f'{path}: HTTP {error.code}') from error
        except (urllib.error.URLError, OSError, ValueError) as error:
            raise SonarError(f'{path}: {error}') from error

    def task_status(self, task_id):
        return (self.get('/api/ce/task', id=task_id).get('task') or {}).get('status', '')

    def coverage(self, project_key, branch):
        """The branch's coverage in %, or None when SonarQube has no coverage measure for it."""
        data = self.get('/api/measures/component', component=project_key, branch=branch, metricKeys='coverage')
        for measure in (data.get('component') or {}).get('measures') or []:
            if measure.get('metric') == 'coverage' and measure.get('value') not in (None, ''):
                return float(measure['value'])
        return None


def wait_for_task(sonar, task_id, timeout=900, interval=10, sleep=time.sleep, clock=time.monotonic):
    """The compute task's final status, or 'TIMEOUT'."""
    deadline = clock() + timeout
    while True:
        status = sonar.task_status(task_id)
        if status in DONE:
            return status
        if clock() >= deadline:
            return 'TIMEOUT'
        sleep(interval)


def verdict(current, base, minimum, tolerance):
    """(passed, message) with Jenkins' rules. `base` is None when the base branch has no coverage."""
    if current < minimum:
        return False, f'Coverage ({current}%) is below the minimum required ({minimum}%).'
    if base is None:
        return True, f'Coverage ({current}%) is above the minimum; the base branch has no coverage to compare with.'
    floor = round(base - tolerance, 2)
    if current < floor:
        return False, (f'Coverage ({current}%) is lower than the base ({base}%) '
                       f'beyond the {tolerance} point tolerance (at least {floor}%).')
    return True, f'Coverage ({current}%) is within {tolerance} point of the base ({base}%).'


def compare(sonar, project_key, branch, base_branch, minimum, tolerance):
    """(exit code, Markdown summary)."""
    current = sonar.coverage(project_key, branch)
    if current is None:
        return 1, f'## Coverage\n\nSonarQube has no coverage for `{branch}`: the analysis carried no JaCoCo report.\n'
    base = sonar.coverage(project_key, base_branch)
    passed, message = verdict(current, base, minimum, tolerance)
    lines = ['## Coverage', '', '| Branch | Coverage |', '| --- | --- |',
             f'| `{branch}` | {current}% |',
             f"| `{base_branch}` (base) | {'—' if base is None else f'{base}%'} |",
             '', f"{'✅' if passed else '❌'} {message}",
             f'Rules: at least {minimum}%, and no more than {tolerance} point below the base.']
    return (0 if passed else 1), '\n'.join(lines) + '\n'


def _parse(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest='command', required=True)
    wait = sub.add_parser('wait')
    wait.add_argument('--report-task', required=True)
    wait.add_argument('--timeout', type=int, default=900)
    cmp_ = sub.add_parser('compare')
    cmp_.add_argument('--properties', required=True)
    cmp_.add_argument('--branch', required=True)
    cmp_.add_argument('--base', required=True)
    cmp_.add_argument('--minimum', type=float, default=70.0)
    cmp_.add_argument('--tolerance', type=float, default=1.0)
    return parser.parse_args(argv)


def main(argv=None, env=None, sonar=None, sleep=time.sleep):
    args = _parse(argv)
    env = os.environ if env is None else env
    if sonar is None:
        host, token = env.get('SONAR_HOST_URL', ''), env.get('SONAR_TOKEN', '')
        if not host or not token:
            print('SONAR_HOST_URL and SONAR_TOKEN are required.', file=sys.stderr)
            return 2
        sonar = Sonar(host, token)
    try:
        if args.command == 'wait':
            task = read_properties(args.report_task).get('ceTaskId', '')
            if not task:
                print(f'No ceTaskId in {args.report_task}.', file=sys.stderr)
                return 2
            status = wait_for_task(sonar, task, args.timeout, sleep=sleep)
            print(f'SonarQube compute task {task}: {status}')
            return 0 if status == 'SUCCESS' else 1
        key = read_properties(args.properties).get('sonar.projectKey', '')
        if not key:
            print(f'No sonar.projectKey in {args.properties}.', file=sys.stderr)
            return 2
        code, summary = compare(sonar, key, args.branch, args.base, args.minimum, args.tolerance)
        sys.stdout.write(summary)
        return code
    except (OSError, SonarError) as error:
        print(f'SonarQube could not be read: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
