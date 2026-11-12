#!/usr/bin/env python3
"""Coverage-decrease gate: this module's one implementation of the rule Jenkins' "Compare Coverage
Results" stage enforces (sonarUtils.compareCoverage in com.etendoerp.jenkins.pipelines).

Called by run-sonar.sh --compare-coverage (by hand, before a push) and by the "Sonar Build" job of
.github/workflows/mini-pipeline.yml. etendo_schema_forge has the same gate in
scripts/compare-sonar-coverage.js; this is its counterpart, with the same options and rules.

Rule, on the OVERALL coverage read live from SonarQube:
  current < COVERAGE_MINIMUM            -> fail (no base comparison)
  current < base - COVERAGE_TOLERANCE   -> fail
  otherwise                             -> pass
A base branch with no coverage yet (404 or no measure) passes with a warning.

Context:
  push (default)  a tooling failure (SonarQube unreachable, a measure that cannot be read, an
                  analysis that does not finish) never blocks: it is reported as skipped.
  ci              the same failures fail the gate: a PR must not get a green required check
                  because its coverage could not be evaluated.

Usage:
  scripts/compare-sonar-coverage.py --base-branch <branch>
      [--pull-request <key> | --branch <name>]   the current analysis (default: the project's
                                                 main branch, as run-sonar.sh always read it)
      [--label <text>]           how the current analysis is named in the output
      [--project-key <key>]      default: sonar.projectKey of sonar-project.properties
      [--report-task <file>]     first wait for that scanner task (ceTaskId) to be processed
      [--wait-timeout <seconds>] default 600
      [--context push|ci]

Env: SONAR_HOST_URL, SONAR_TOKEN (needs Browse permission on the project: an analysis token
answers 403), COVERAGE_TOLERANCE (default 1), COVERAGE_MINIMUM (default 70).
Exit: 0 pass or skipped, 1 gate failed (or, in ci context, coverage could not be evaluated),
2 usage error.
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

DEFAULT_COVERAGE_TOLERANCE = 1.0
DEFAULT_COVERAGE_MINIMUM = 70.0
DEFAULT_WAIT_TIMEOUT = 600
POLL_SECONDS = 5
HTTP_TIMEOUT = 30
MODULE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class NotFound(Exception):
    """SonarQube answered 404: the branch or measure does not exist (yet)."""


class SonarError(Exception):
    """SonarQube could not be read."""


def read_properties(path):
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
            with self.opener(request, timeout=HTTP_TIMEOUT) as response:
                return json.loads(response.read().decode('utf-8'))
        except urllib.error.HTTPError as error:
            if error.code == 404:
                raise NotFound(path) from error
            if error.code in (401, 403):
                raise SonarError(f'{path}: HTTP {error.code}: the token cannot read the project '
                                 '(reading measures needs Browse permission; an analysis token '
                                 'only publishes analyses)') from error
            raise SonarError(f'{path}: HTTP {error.code}') from error
        except (urllib.error.URLError, OSError, ValueError) as error:
            raise SonarError(f'{path}: {error}') from error

    def task_status(self, task_id):
        return (self.get('/api/ce/task', id=task_id).get('task') or {}).get('status', '')

    def coverage(self, project_key, **selector):
        """Overall coverage in %, or None when there is no coverage measure. `selector` is
        branch=... or pullRequest=..., or nothing for the project's main branch."""
        params = {'component': project_key, 'metricKeys': 'coverage'}
        params.update({k: v for k, v in selector.items() if v})
        measures = (self.get('/api/measures/component', **params).get('component') or {}).get('measures') or []
        for measure in measures:
            if measure.get('metric') != 'coverage':
                continue
            value = measure.get('value')
            if value in (None, ''):
                value = (measure.get('period') or {}).get('value')
            try:
                return None if value in (None, '') else float(value)
            except ValueError:
                return None
        return None


def wait_for_task(sonar, task_id, timeout, sleep=time.sleep, clock=time.monotonic):
    """The compute task's final status (SUCCESS, FAILED, CANCELED) or 'TIMEOUT'."""
    deadline = clock() + timeout
    while True:
        status = sonar.task_status(task_id)
        if status in ('SUCCESS', 'FAILED', 'CANCELED'):
            return status
        if clock() >= deadline:
            return 'TIMEOUT'
        sleep(POLL_SECONDS)


def decide(current, base, minimum, tolerance):
    """(passed, message) for the coverages; `base` is None when the base has none yet."""
    if current < minimum:
        return False, (f'❌ COVERAGE BELOW MINIMUM: {current:.2f}% < {minimum:.2f}% required. '
                       'Add tests until overall coverage reaches the minimum.')
    if base is None:
        return True, (f'⚠️ The base branch has no coverage on Sonar yet: only the {minimum:.2f}% '
                      f'minimum was checked ({current:.2f}%).')
    floor = base - tolerance
    if current < floor:
        return False, (f'❌ COVERAGE DECREASED: {current:.2f}% < {floor:.2f}% (base {base:.2f}% '
                       f'− {tolerance:.2f}pp, short {floor - current:.2f}pp). Add tests until overall '
                       'coverage is not below the base minus the tolerance.')
    return True, (f'✅ Coverage is OK: {current:.2f}% (base {base:.2f}%, at least {floor:.2f}% '
                  f'and {minimum:.2f}%).')


def run(args, env, sonar, out, sleep=time.sleep):
    """The gate; returns the exit code and writes its report to `out`."""
    ci = args.context == 'ci'

    def tooling(message):
        if ci:
            out.write(f'❌ Coverage could not be evaluated: {message}\n')
            return 1
        out.write(f'⚠️ SKIPPED, not blocking: {message}\n')
        return 0

    try:
        tolerance = float(env.get('COVERAGE_TOLERANCE') or DEFAULT_COVERAGE_TOLERANCE)
        minimum = float(env.get('COVERAGE_MINIMUM') or DEFAULT_COVERAGE_MINIMUM)
    except ValueError:
        out.write('COVERAGE_TOLERANCE and COVERAGE_MINIMUM must be numbers.\n')
        return 2

    label = args.label or (f'PR #{args.pull_request}' if args.pull_request else args.branch or 'this branch')
    out.write(f'## Coverage: {label} vs {args.base_branch}\n\n')

    if args.report_task:
        try:
            task = read_properties(args.report_task).get('ceTaskId', '')
        except OSError as error:
            return tooling(f'cannot read {args.report_task}: {error}')
        if not task:
            return tooling(f'no ceTaskId in {args.report_task}')
        try:
            status = wait_for_task(sonar, task, args.wait_timeout, sleep=sleep)
        except (NotFound, SonarError) as error:
            return tooling(f'the analysis status could not be read: {error}')
        if status != 'SUCCESS':
            return tooling(f'the SonarQube analysis ended {status}')

    try:
        current = sonar.coverage(args.project_key, pullRequest=args.pull_request, branch=args.branch)
    except NotFound:
        current = None
    except SonarError as error:
        return tooling(f"{label}'s coverage could not be read: {error}")
    if current is None:
        return tooling(f'Sonar has no coverage for {label}: did the analysis import the JaCoCo report?')

    base_error = None
    try:
        base = sonar.coverage(args.project_key, branch=args.base_branch)
    except NotFound:
        base = None
    except SonarError as error:
        base, base_error = None, error
    if base_error is not None and ci:
        return tooling(f"{args.base_branch}'s coverage could not be read: {base_error}")

    passed, message = decide(current, base, minimum, tolerance)
    out.write('| Analysis | Coverage |\n| --- | --- |\n')
    out.write(f'| {label} | {current:.2f}% |\n')
    out.write(f"| {args.base_branch} (base) | {'—' if base is None else f'{base:.2f}%'} |\n\n")
    out.write(f'{message}\n')
    out.write(f'Rule: at least {minimum:.2f}%, and no more than {tolerance:.2f}pp below the base.\n')
    return 0 if passed else 1


def parse(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--base-branch', required=True)
    current = parser.add_mutually_exclusive_group()
    current.add_argument('--pull-request', default='')
    current.add_argument('--branch', default='')
    parser.add_argument('--label', default='')
    parser.add_argument('--project-key', default='')
    parser.add_argument('--report-task', default='')
    parser.add_argument('--wait-timeout', type=int, default=DEFAULT_WAIT_TIMEOUT)
    parser.add_argument('--context', choices=('push', 'ci'), default='push')
    return parser.parse_args(argv)


def main(argv=None, env=None, sonar=None, out=None, sleep=time.sleep):
    args = parse(argv)
    env = os.environ if env is None else env
    out = out or sys.stdout
    if not args.project_key:
        try:
            args.project_key = read_properties(os.path.join(MODULE_ROOT, 'sonar-project.properties')).get('sonar.projectKey', '')
        except OSError:
            args.project_key = ''
    if not args.project_key:
        out.write('No --project-key and no sonar.projectKey in sonar-project.properties.\n')
        return 2
    if sonar is None:
        host, token = env.get('SONAR_HOST_URL', ''), env.get('SONAR_TOKEN', '')
        if not host or not token:
            if args.context == 'ci':
                out.write('❌ Coverage could not be evaluated: SONAR_HOST_URL and SONAR_TOKEN are required.\n')
                return 1
            out.write('⚠️ SKIPPED, not blocking: SONAR_HOST_URL and SONAR_TOKEN are not set.\n')
            return 0
        sonar = Sonar(host, token)
    return run(args, env, sonar, out, sleep)


if __name__ == '__main__':
    sys.exit(main())
