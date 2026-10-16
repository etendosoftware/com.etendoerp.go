# @covers .githooks/lib/git-conventions.sh
"""Unit tests for .githooks/lib/git-conventions.sh, the Git Police conventions the hooks check.

Run from the module root:  python3 -m unittest discover -s scripts
"""

import os
import subprocess
import tempfile
import unittest

LIB = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '.githooks', 'lib', 'git-conventions.sh')


def bash(script, *args):
    result = subprocess.run(['bash', '-c', '. "$0"; ' + script, LIB, *args],
                            capture_output=True, text=True, check=True)
    return result.stdout.strip()


def message_problem(message):
    with tempfile.NamedTemporaryFile('w', suffix='.msg', delete=False) as handle:
        handle.write(message)
    try:
        return bash('commit_message_problem "$1"', handle.name)
    finally:
        os.unlink(handle.name)


class CommitMessageTest(unittest.TestCase):

    def test_accepts_the_feature_epic_and_hotfix_prefixes(self):
        for subject in ('Feature ETP-5695: Run goTestUnit in the pre-push',
                        'Epic ETP-3504: Merge develop',
                        'Hotfix ETP-12: Fix the totals',
                        'Feature ETP-5695: Warm the cache\n\nA body that explains why.\n'):
            self.assertEqual(message_problem(subject), '', subject)

    def test_an_issue_commit_needs_the_jira_key_in_the_body(self):
        self.assertEqual(message_problem('Issue #42: Fix NPE on session expiry\n\nETP-3400\n'), '')
        self.assertIn('Jira key', message_problem('Issue #42: Fix NPE on session expiry\n'))
        self.assertIn('Jira key', message_problem('Issue #42: Fix ETP-3400 typo\n\nNo key here.\n'))

    def test_rejects_other_prefixes_and_a_missing_ticket(self):
        for subject in ('Fix the totals', 'feature ETP-1: lower case', 'Feature: no ticket',
                        'Feature ETP-12345: five digits', 'Feature ETP-1:no space'):
            self.assertIn('must start with', message_problem(subject), subject)

    def test_counts_the_first_line_against_the_80_character_limit(self):
        subject = 'Feature ETP-5695: ' + 'x' * 62
        self.assertEqual(len(subject), 80)
        self.assertEqual(message_problem(subject), '')
        self.assertIn('81 characters', message_problem(subject + 'y'))

    def test_skips_comments_and_messages_git_writes_itself(self):
        self.assertEqual(message_problem('# Please enter the commit message\n\nFeature ETP-1: Do it\n'), '')
        for subject in ("Merge remote-tracking branch 'origin/develop' into feature/ETP-5695",
                        'fixup! Feature ETP-1: Do it', 'squash! Feature ETP-1: Do it'):
            self.assertEqual(message_problem(subject), '', subject)


class BranchTest(unittest.TestCase):

    def problem(self, branch, target):
        return bash('branch_problem "$1" "$2"', branch, target)

    def test_accepts_the_conventional_names(self):
        for branch, target in (('feature/ETP-5695', 'develop'), ('feature/ETP-3400-Y26', 'develop'),
                               ('epic/ETP-3504', 'develop'), ('hotfix/#42-ETP-3400', 'main'),
                               ('hotfix/ETP-3400', 'develop'), ('mergeblock/ETP-5717', 'main'),
                               ('develop', 'main'), ('main', 'develop')):
            self.assertEqual(self.problem(branch, target), '', branch)

    def test_blocks_a_branch_that_main_does_not_accept(self):
        self.assertTrue(self.problem('feature/ETP-5695', 'main').startswith('error: '))
        self.assertTrue(self.problem('epic/ETP-3504', 'main').startswith('error: '))

    def test_only_warns_about_an_unusual_name(self):
        for branch in ('chore/bump-core', 'feature/etp-5695', 'feature/ETP-5695-extra'):
            self.assertTrue(self.problem(branch, 'develop').startswith('warning: '), branch)


if __name__ == '__main__':
    unittest.main()
