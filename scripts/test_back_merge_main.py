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
PR_URL = 'https://github.com/etendosoftware/repo/pull/7'
OPEN_URL = 'https://github.com/etendosoftware/repo/pull/3'

FAKE_GH = r'''#!/usr/bin/env bash
printf '%s\n' "$*" >> "$GH_STUB_LOG"
json=""; prev=""
for arg in "$@"; do [ "$prev" = --json ] && json="$arg"; prev="$arg"; done
case "$1 $2" in
  "pr list") printf '%s\n' "${GH_STUB_OPEN_PR:-}" ;;
  "pr create")
    while [ $# -gt 0 ]; do
      [ "$1" = --body-file ] && cp "$2" "$GH_STUB_BODY"
      shift
    done
    echo "https://github.com/etendosoftware/repo/pull/7" ;;
  "pr view")
    case "$json" in
      assignees) printf '%s\n' "${GH_STUB_ASSIGNEES:-0}" ;;
      reviewDecision) printf '%s\n' "${GH_STUB_REVIEW:-REVIEW_REQUIRED}" ;;
      autoMergeRequest) printf '%s\n' "${GH_STUB_AUTO_MERGE:-false}" ;;
      mergedBy) printf '%s\n' "${GH_STUB_MERGED_BY:-}" ;;
    esac ;;
  "pr edit") exit "${GH_STUB_EDIT_RC:-0}" ;;
  "pr review") printf 'review-token=%s\n' "$GH_TOKEN" >> "$GH_STUB_LOG" ;;
  "pr merge") exit "${GH_STUB_MERGE_RC:-0}" ;;
  "api user") printf '%s\n' "${GH_STUB_BOT:-etendobot}" ;;
  api*)
    case "$2" in
      */pulls) printf '%s\n' "${GH_STUB_MERGED_NUMBER:-}" ;;
      *) printf '%s\n' "${GH_STUB_COMMIT_AUTHOR:-}" ;;
    esac ;;
esac
'''


def isolated_env(**extra):
    """The environment without GIT_* variables or the user's git config. A hook run from a
    linked worktree exports GIT_DIR; inherited here, it would send the fixture commits to the
    real repository."""
    env = {key: value for key, value in os.environ.items()
           if not key.startswith('GIT_') and key not in ('GH_TOKEN', 'APPROVE_TOKEN')}
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
                           GH_STUB_BODY=self.body, GH_TOKEN='bot-token', **stub)
        return subprocess.run(['bash', SCRIPT], cwd=self.repo, env=env, capture_output=True, text=True)

    def gh_calls(self, prefix=''):
        if not os.path.exists(self.log):
            return []
        with open(self.log) as handle:
            return [call for call in handle.read().splitlines() if call.startswith(prefix)]

    def pr_body(self):
        with open(self.body) as handle:
            return handle.read()

    def hotfix(self):
        self.git('checkout', '-q', 'main')
        self.commit('b.txt', 'fix\n', 'Issue #42: Fix the totals on main')
        self.push()

    def conflict(self):
        self.git('checkout', '-q', 'develop')
        self.commit('a.txt', 'one on develop\n', 'Feature ETP-1234: Change the first file')
        self.git('checkout', '-q', 'main')
        self.commit('a.txt', 'one on main\n', 'Issue #42: Fix the first file on main')
        self.push()

    def test_does_nothing_when_develop_already_contains_main(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('nothing to bring back', result.stdout)
        self.assertEqual(self.gh_calls(), [])

    def test_opens_the_pr_assigns_whoever_moved_main_and_turns_on_auto_merge(self):
        self.hotfix()
        result = self.run_script(GH_STUB_COMMIT_AUTHOR='alice', GH_STUB_MERGED_NUMBER='41')
        self.assertEqual(result.returncode, 0, result.stderr)
        create = self.gh_calls('pr create')
        self.assertEqual(len(create), 1)
        self.assertIn('--base develop --head main --title Merge main into develop', create[0])
        self.assertIn('Issue #42: Fix the totals on main', self.pr_body())
        self.assertIn('`main` moved with #41.', self.pr_body())
        self.assertIn('It merges itself once the required checks pass', self.pr_body())
        self.assertEqual(self.gh_calls('pr edit'), [f'pr edit {PR_URL} --add-assignee alice'])
        self.assertEqual(self.gh_calls('pr merge'), [f'pr merge {PR_URL} --auto --merge'])
        self.assertEqual(self.gh_calls('pr review'), [])

    def test_approves_with_the_second_token_when_develop_needs_a_review(self):
        self.hotfix()
        result = self.run_script(GH_STUB_COMMIT_AUTHOR='alice', APPROVE_TOKEN='approver-token')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.gh_calls(f'pr review {PR_URL} --approve')), 1)
        self.assertEqual(self.gh_calls('review-token='), ['review-token=approver-token'])

    def test_falls_back_to_whoever_merged_the_pr_of_mains_last_commit(self):
        self.hotfix()
        result = self.run_script(GH_STUB_MERGED_NUMBER='41', GH_STUB_MERGED_BY='bob')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.gh_calls('pr view 41'), ['pr view 41 --json mergedBy --jq .mergedBy.login // empty'])
        self.assertEqual(self.gh_calls('pr edit'), [f'pr edit {PR_URL} --add-assignee bob'])

    def test_never_assigns_the_bot_that_opens_the_pr(self):
        self.hotfix()
        result = self.run_script(GH_STUB_COMMIT_AUTHOR='etendobot', GH_STUB_BOT='etendobot')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.gh_calls('pr edit'), [])
        self.assertEqual(len(self.gh_calls('pr merge')), 1)

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

    def test_completes_an_open_pr_instead_of_opening_a_second_one(self):
        self.hotfix()
        result = self.run_script(GH_STUB_OPEN_PR=OPEN_URL, GH_STUB_COMMIT_AUTHOR='alice', APPROVE_TOKEN='approver-token')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Already open', result.stdout)
        self.assertEqual(self.gh_calls('pr create'), [])
        self.assertEqual(self.gh_calls('pr edit'), [f'pr edit {OPEN_URL} --add-assignee alice'])
        self.assertEqual(len(self.gh_calls(f'pr review {OPEN_URL} --approve')), 1)
        self.assertEqual(self.gh_calls('pr merge'), [f'pr merge {OPEN_URL} --auto --merge'])

    def test_leaves_alone_an_open_pr_that_has_everything_already(self):
        self.hotfix()
        result = self.run_script(GH_STUB_OPEN_PR=OPEN_URL, GH_STUB_COMMIT_AUTHOR='alice', GH_STUB_ASSIGNEES='1',
                                 GH_STUB_REVIEW='APPROVED', GH_STUB_AUTO_MERGE='true', APPROVE_TOKEN='approver-token')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.gh_calls('pr edit') + self.gh_calls('pr review') + self.gh_calls('pr merge'), [])

    def test_a_conflict_gets_its_assignee_but_no_approval_or_auto_merge(self):
        self.conflict()
        result = self.run_script(GH_STUB_COMMIT_AUTHOR='alice', APPROVE_TOKEN='approver-token')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('### Conflicts', self.pr_body())
        self.assertIn('- a.txt', self.pr_body())
        self.assertIn('git merge origin/main', self.pr_body())
        self.assertNotIn('It merges itself', self.pr_body())
        self.assertEqual(self.gh_calls('pr edit'), [f'pr edit {PR_URL} --add-assignee alice'])
        self.assertEqual(self.gh_calls('pr review') + self.gh_calls('pr merge'), [])
        self.assertIn('it waits for alice', result.stdout)

    def test_a_refused_assignment_or_auto_merge_still_leaves_the_pr_open(self):
        self.hotfix()
        result = self.run_script(GH_STUB_COMMIT_AUTHOR='alice', GH_STUB_EDIT_RC='1', GH_STUB_MERGE_RC='1')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(f'Opened {PR_URL}', result.stdout)
        self.assertIn('::warning::Could not assign', result.stdout)
        self.assertIn('::warning::Could not enable auto-merge', result.stdout)


if __name__ == '__main__':
    unittest.main()
