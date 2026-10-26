# @covers scripts/back-merge-main.sh
"""Tests for scripts/back-merge-main.sh: a throwaway repository with its own origin, where main
moves in the ways that do and do not need a main -> develop PR, and a fake gh that records what
the script asks GitHub for.

Run from the module root:  python3 -m unittest discover -s scripts
"""

import os
import shutil
import stat
import subprocess
import tempfile
import unittest

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'back-merge-main.sh')

FAKE_GH = r'''#!/usr/bin/env bash
printf '%s\n' "$*" >> "$GH_STUB_LOG"
case "$1 $2" in
  "pr list") printf '%s\n' "${GH_STUB_OPEN_PR:-}" ;;
  "pr create")
    while [ $# -gt 0 ]; do
      [ "$1" = --body-file ] && cp "$2" "$GH_STUB_BODY"
      shift
    done
    echo "https://github.com/etendosoftware/repo/pull/7" ;;
  "pr edit") exit "${GH_STUB_EDIT_RC:-0}" ;;
  api*) printf '%s\n' "${GH_STUB_MERGED_PR:-}" ;;
esac
'''


def isolated_env(**extra):
    """The environment without GIT_* variables or the user's git config. A hook run from a
    linked worktree exports GIT_DIR; inherited here, it would send the fixture commits to the
    real repository."""
    env = {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}
    env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1')
    env.update(extra)
    return env


class BackMergeMainTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix='back-merge-main-')
        self.origin = os.path.join(self.tmp, 'origin.git')
        self.repo = os.path.join(self.tmp, 'repo')
        self.bin = os.path.join(self.tmp, 'bin')
        self.log = os.path.join(self.tmp, 'gh.log')
        self.body = os.path.join(self.tmp, 'body.md')
        os.makedirs(self.bin)
        gh = os.path.join(self.bin, 'gh')
        with open(gh, 'w') as handle:
            handle.write(FAKE_GH)
        os.chmod(gh, os.stat(gh).st_mode | stat.S_IEXEC)

        subprocess.run(['git', 'init', '-q', '--bare', '-b', 'main', self.origin], check=True, env=isolated_env())
        self.git('init', '-q', '-b', 'main', cwd=self.tmp, repo=self.repo)
        self.git('config', 'user.email', 'test@example.com')
        self.git('config', 'user.name', 'Test')
        self.git('remote', 'add', 'origin', self.origin)
        self.commit('a.txt', 'one\n', 'Feature ETP-1234: Add the first file')
        self.git('branch', 'develop')
        self.push()

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def git(self, *args, cwd=None, repo=None):
        target = [repo] if repo else []
        return subprocess.run(['git', *args, *target], cwd=cwd or self.repo, env=isolated_env(),
                              capture_output=True, text=True, check=True)

    def commit(self, name, content, message):
        with open(os.path.join(self.repo, name), 'w') as handle:
            handle.write(content)
        self.git('add', name)
        self.git('commit', '-q', '-m', message)

    def push(self):
        self.git('push', '-q', 'origin', 'main', 'develop')

    def run_script(self, **stub):
        env = isolated_env(PATH=self.bin + os.pathsep + os.environ['PATH'], GH_STUB_LOG=self.log,
                           GH_STUB_BODY=self.body, MERGED_SHA='abc123', **stub)
        return subprocess.run(['bash', SCRIPT], cwd=self.repo, env=env, capture_output=True, text=True)

    def gh_calls(self):
        if not os.path.exists(self.log):
            return []
        with open(self.log) as handle:
            return handle.read().splitlines()

    def pr_body(self):
        with open(self.body) as handle:
            return handle.read()

    def hotfix(self):
        self.git('checkout', '-q', 'main')
        self.commit('b.txt', 'fix\n', 'Issue #42: Fix the totals on main')
        self.push()

    def test_does_nothing_when_develop_already_contains_main(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('nothing to bring back', result.stdout)
        self.assertEqual(self.gh_calls(), [])

    def test_opens_the_pr_with_the_hotfix_and_asks_its_author_to_review(self):
        self.hotfix()
        result = self.run_script(GH_STUB_MERGED_PR='41 alice')
        self.assertEqual(result.returncode, 0, result.stderr)
        create = [call for call in self.gh_calls() if call.startswith('pr create')]
        self.assertEqual(len(create), 1)
        self.assertIn('--base develop --head main --title Merge main into develop', create[0])
        self.assertIn('Issue #42: Fix the totals on main', self.pr_body())
        self.assertIn('`main` moved with #41.', self.pr_body())
        self.assertNotIn('### Conflicts', self.pr_body())
        self.assertIn('pr edit https://github.com/etendosoftware/repo/pull/7 --add-reviewer alice', self.gh_calls())

    def test_a_clean_cut_brings_only_its_merge_commit(self):
        self.git('checkout', '-q', 'develop')
        self.commit('c.txt', 'feature\n', 'Feature ETP-1234: Add a feature')
        self.git('checkout', '-q', 'main')
        self.git('merge', '-q', '--no-ff', '-m', 'Merge pull request #50 from mergeblock/ETP-1234', 'develop')
        self.push()
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Only merge commits', self.pr_body())
        self.assertNotIn('Add a feature', self.pr_body())

    def test_does_not_open_a_second_pr(self):
        self.hotfix()
        result = self.run_script(GH_STUB_OPEN_PR='https://github.com/etendosoftware/repo/pull/3')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Already open', result.stdout)
        self.assertFalse(any(call.startswith('pr create') for call in self.gh_calls()))

    def test_lists_the_conflicts_and_how_to_resolve_them(self):
        self.git('checkout', '-q', 'develop')
        self.commit('a.txt', 'one on develop\n', 'Feature ETP-1234: Change the first file')
        self.git('checkout', '-q', 'main')
        self.commit('a.txt', 'one on main\n', 'Issue #42: Fix the first file on main')
        self.push()
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('### Conflicts', self.pr_body())
        self.assertIn('- a.txt', self.pr_body())
        self.assertIn('git merge origin/main', self.pr_body())

    def test_a_refused_review_request_still_leaves_the_pr_open(self):
        self.hotfix()
        result = self.run_script(GH_STUB_MERGED_PR='41 etendobot', GH_STUB_EDIT_RC='1')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Opened https://github.com/etendosoftware/repo/pull/7', result.stdout)
        self.assertIn('::warning::Could not request a review from etendobot', result.stdout)


if __name__ == '__main__':
    unittest.main()
