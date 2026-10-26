# @covers .githooks/lib/relevance.sh
"""Tests for .githooks/lib/relevance.sh: which pre-push checks a set of changed files calls for.
The pre-push picks its steps from it and commit-msg stamps the same set in the Checks trailer.

Run from the module root:  python3 -m unittest discover -s scripts
"""

import os
import subprocess
import unittest

LIB = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '.githooks', 'lib',
                                   'relevance.sh'))
EVERYTHING = 'xml junit coverage scripts'


def checks(*files):
    result = subprocess.run(['bash', '-c', '. "$0"; relevance_checks', LIB], input='\n'.join(files),
                            capture_output=True, text=True, check=True)
    return result.stdout.strip()


class RelevanceChecksTest(unittest.TestCase):

    def test_runs_nothing_for_docs_or_the_agent_configuration(self):
        self.assertEqual(checks('docs/guide.md', 'README.md'), '')
        for path in ('.claude/settings.json', '.codex/agents/helper.toml', '.omp/agents/helper.md',
                     '.opencode/agents/helper.md', '.agents/skills/helper/SKILL.md'):
            self.assertEqual(checks(path), '', path)

    def test_runs_only_the_script_tests_for_workflows_and_scripts(self):
        for path in ('.github/workflows/mini-pipeline.yml', 'scripts/back-merge-main.sh',
                     'scripts/test_back_merge_main.py', 'scripts/verify-hooks-trailer.sh'):
            self.assertEqual(checks(path), 'scripts', path)

    def test_runs_everything_for_the_tooling_the_pre_push_itself_runs(self):
        for path in ('.githooks/pre-push', 'run-sonar.sh', 'check-sonar-issues.sh', 'scripts/check-diff-coverage.py',
                     'scripts/check-test-hygiene.py', 'scripts/check-unit-test-classification.sh'):
            self.assertEqual(checks(path), EVERYTHING, path)

    def test_runs_everything_for_a_file_no_bucket_knows(self):
        self.assertEqual(checks('docs/guide.md', 'tools/unknown.txt'), EVERYTHING)

    def test_a_workflow_next_to_java_code_keeps_the_java_checks(self):
        self.assertEqual(checks('src/com/etendoerp/go/Foo.java', '.github/workflows/mini-pipeline.yml'),
                         'junit coverage scripts')

    def test_sourcedata_runs_the_xml_check_and_the_tests(self):
        self.assertEqual(checks('src-db/database/sourcedata/ETGO_SF_SPEC.xml'), 'xml junit')


if __name__ == '__main__':
    unittest.main()
