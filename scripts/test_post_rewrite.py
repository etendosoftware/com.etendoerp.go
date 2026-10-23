# @covers .githooks/post-rewrite
"""Tests for .githooks/post-rewrite: commits stamped by the real hooks in a throwaway repository,
then rewritten in the ways that keep or break their Hooks-Verified seal.

Run from the module root:  python3 -m unittest discover -s scripts
"""

import os
import shutil
import subprocess
import tempfile
import unittest

def isolated_env(**extra):
    """The environment without GIT_* variables. A hook run from a linked worktree exports GIT_DIR;
    inherited here, it would send the fixture commits to the real repository."""
    env = {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}
    env.update(extra)
    return env


HOOKS = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '.githooks'))
WARNING = 'trailer does not match their content'


class PostRewriteTest(unittest.TestCase):

    def setUp(self):
        self.repo = tempfile.mkdtemp(prefix='post-rewrite-')
        self.git('init', '-q', '-b', 'main')
        self.git('config', 'user.email', 'test@example.com')
        self.git('config', 'user.name', 'Test')
        self.git('config', 'core.hooksPath', HOOKS)
        self.commit('a.txt', 'one\n', 'Feature ETP-1234: Add the first file')

    def tearDown(self):
        shutil.rmtree(self.repo)

    def git(self, *args, check=True):
        return subprocess.run(['git', *args], cwd=self.repo, env=isolated_env(GIT_EDITOR='true'),
                              capture_output=True, text=True, check=check)

    def write(self, name, content):
        with open(os.path.join(self.repo, name), 'w') as handle:
            handle.write(content)

    def commit(self, name, content, message, *extra):
        self.write(name, content)
        self.git('add', name)
        return self.git('commit', '-q', '-m', message, *extra)

    def seal_is_valid(self, rev='HEAD'):
        lib = os.path.join(HOOKS, 'lib', 'hooks-proof.sh')
        result = subprocess.run(['bash', '-c', '. "$0"; hooks_verify_commit "$1"', lib, rev],
                                cwd=self.repo, env=isolated_env(), capture_output=True, text=True)
        return result.returncode == 0

    def conflicting_rebase(self):
        """A feature commit replayed onto a main that changed the same line, conflict resolved."""
        self.git('checkout', '-q', '-b', 'feature')
        self.commit('a.txt', 'two\n', 'Feature ETP-1234: Change the line on the feature')
        self.git('checkout', '-q', 'main')
        self.commit('a.txt', 'three\n', 'Feature ETP-1234: Change the line on main')
        self.git('checkout', '-q', 'feature')
        self.assertNotEqual(self.git('rebase', 'main', check=False).returncode, 0)
        self.write('a.txt', 'two and three\n')
        self.git('add', 'a.txt')
        return self.git('rebase', '--continue')

    def test_an_amend_made_with_the_hooks_stays_silent(self):
        self.write('a.txt', 'one, amended\n')
        self.git('add', 'a.txt')
        result = self.git('commit', '-q', '--amend', '--no-edit')
        self.assertNotIn(WARNING, result.stderr)
        self.assertTrue(self.seal_is_valid())

    def test_warns_after_an_amend_that_skipped_the_hooks(self):
        self.write('a.txt', 'one, amended\n')
        self.git('add', 'a.txt')
        result = self.git('commit', '-q', '--amend', '--no-edit', '--no-verify')
        self.assertIn(WARNING, result.stderr)
        self.assertIn('Add the first file', result.stderr)
        self.assertIn("git rebase --exec 'git commit --amend --no-edit' <base>", result.stderr)
        self.assertFalse(self.seal_is_valid())

    def test_a_rebase_that_replays_the_same_diff_stays_silent(self):
        self.git('checkout', '-q', '-b', 'feature')
        self.commit('b.txt', 'feature\n', 'Feature ETP-1234: Add a feature file')
        self.git('checkout', '-q', 'main')
        self.commit('a.txt', 'one on main\n', 'Feature ETP-1234: Change the first file on main')
        self.git('checkout', '-q', 'feature')
        result = self.git('rebase', 'main')
        self.assertNotIn(WARNING, result.stderr)
        self.assertTrue(self.seal_is_valid())

    def test_warns_when_a_rebase_resolves_a_conflict(self):
        result = self.conflicting_rebase()
        self.assertIn(WARNING, result.stderr)
        self.assertIn('Change the line on the feature', result.stderr)
        self.assertFalse(self.seal_is_valid())

    def test_the_suggested_reseal_leaves_nothing_to_warn_about(self):
        self.conflicting_rebase()
        result = self.git('rebase', '--exec', 'git commit --amend --no-edit', 'main')
        self.assertNotIn(WARNING, result.stderr)
        self.assertTrue(self.seal_is_valid())


if __name__ == '__main__':
    unittest.main()
