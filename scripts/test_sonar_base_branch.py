# @covers scripts/sonar-base-branch.sh
"""Tests for scripts/sonar-base-branch.sh: a clone of a throwaway origin, on feature/x, whose local
main lags, matches, has commits of its own or is checked out, as run-sonar.sh finds it before a
PR-mode analysis.

Run from the module root:  python3 -m unittest discover -s scripts
"""

import os
import re
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'sonar-base-branch.sh')


def isolated_env():
    """The environment without GIT_* variables or the user's git config. A hook run from a
    linked worktree exports GIT_DIR; inherited here, it would send the fixture commits to the
    real repository."""
    env = {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}
    env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1')
    return env


class SonarBaseBranchTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='sonar-base-branch-')
        self.origin = os.path.join(self.tmp, 'origin.git')
        self.seed = os.path.join(self.tmp, 'seed')
        self.clone = os.path.join(self.tmp, 'clone')
        self.git(self.tmp, 'init', '-q', '--bare', '-b', 'main', self.origin)
        self.git(self.tmp, 'init', '-q', '-b', 'main', self.seed)
        self.commit(self.seed, 'base.txt')
        self.git(self.seed, 'remote', 'add', 'origin', self.origin)
        self.git(self.seed, 'push', '-q', 'origin', 'main')
        self.git(self.tmp, 'clone', '-q', '-b', 'main', self.origin, self.clone)
        self.git(self.clone, 'switch', '-q', '-c', 'feature/x')

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def git(self, cwd, *args):
        return subprocess.run(['git', '-c', 'user.email=t@example.com', '-c', 'user.name=T', *args], cwd=cwd,
                              env=isolated_env(), capture_output=True, text=True, check=True).stdout.strip()

    def commit(self, cwd, name):
        with open(os.path.join(cwd, name), 'w') as handle:
            handle.write(name + '\n')
        self.git(cwd, 'add', '-A')
        self.git(cwd, 'commit', '-q', '-m', name)

    def origin_moves(self, commits=1):
        for i in range(commits):
            self.commit(self.seed, 'origin-%d.txt' % i)
        self.git(self.seed, 'push', '-q', 'origin', 'main')
        self.git(self.clone, 'fetch', '-q', 'origin')

    def commit_on_local_main(self):
        self.git(self.clone, 'switch', '-q', 'main')
        self.commit(self.clone, 'local.txt')
        self.git(self.clone, 'switch', '-q', 'feature/x')

    def sha(self, ref):
        return self.git(self.clone, 'rev-parse', ref)

    def run_script(self, *args):
        return subprocess.run(['bash', SCRIPT, *args], cwd=self.clone, env=isolated_env(), capture_output=True, text=True)

    def test_leaves_a_local_branch_that_matches_origin_alone_and_prints_the_base(self):
        result = self.run_script('origin/main')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertRegex(result.stdout, r'(?m)^==> Sonar base: main @ %s\w* \(\d{4}-\d{2}-\d{2}\)$' % self.sha('main')[:7])

    def test_fast_forwards_a_local_branch_that_is_only_behind(self):
        self.origin_moves(2)
        stale = self.sha('main')
        result = self.run_script('origin/main')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.sha('main'), self.sha('origin/main'))
        self.assertIn('fast-forwarded from %s' % stale[:7], result.stdout)
        self.assertEqual(self.git(self.clone, 'branch', '--show-current'), 'feature/x')

    def test_is_fine_without_a_local_branch_since_sonar_then_reads_origin(self):
        self.origin_moves()
        self.git(self.clone, 'branch', '-q', '-D', 'main')
        result = self.run_script('origin/main')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("no local main, so Sonar reads origin's", result.stdout)
        self.assertEqual(self.git(self.clone, 'branch', '--list', 'main'), '')

    def test_stops_on_a_local_branch_with_commits_of_its_own_and_leaves_it_as_it_is(self):
        for diverged in (False, True):
            with self.subTest(diverged=diverged):
                if diverged:
                    self.origin_moves()
                else:
                    self.commit_on_local_main()
                local = self.sha('main')
                result = self.run_script('origin/main')
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertRegex(result.stdout, r'(?m)^ERROR: Sonar compares against your local main, at \w+ \(.+\), '
                                                r'but origin/main is at')
                self.assertIn('It has 1 commit(s) that origin/main lacks', result.stdout)
                self.assertIn('git branch -f main origin/main', result.stdout)
                self.assertEqual(self.sha('main'), local)

    def test_stops_on_a_branch_behind_while_checked_out_in_another_worktree(self):
        self.origin_moves()
        stale = self.sha('main')
        self.git(self.clone, 'worktree', 'add', '-q', os.path.join(self.tmp, 'wt'), 'main')
        result = self.run_script('origin/main')
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('It is checked out, so it cannot be moved from here', result.stdout)
        self.assertIn('git merge --ff-only origin/main', result.stdout)
        self.assertEqual(self.sha('main'), stale)

    def test_leaves_a_base_that_is_not_an_origin_branch_alone_and_rejects_bad_usage(self):
        self.origin_moves()
        stale = self.sha('main')
        for base in ('main', stale):
            result = self.run_script(base)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertTrue(result.stdout.startswith('==> Sonar base: %s @ ' % base), result.stdout)
        self.assertEqual(self.sha('main'), stale)
        self.assertEqual(self.run_script().returncode, 2)
        self.assertEqual(self.run_script('').returncode, 2)


if __name__ == '__main__':
    unittest.main()
