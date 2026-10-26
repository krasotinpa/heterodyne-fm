"""Build version contract, using isolated repositories and no hardware."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[2] / 'build-support/version_name.py'


class VersionNameTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('version_name', SCRIPT)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-q')
        self.git('config', 'commit.gpgsign', 'false')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('config', 'user.name', 'Test')
        (self.root / 'source').write_text('initial')
        self.git('add', 'source')
        self.git('commit', '-qm', 'initial')

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], text=True).strip()

    def test_clean_dirty_and_untracked(self):
        expected = '0.1.0+' + self.git('rev-parse', 'HEAD')[:7]
        self.assertEqual(expected, self.module.version_name(self.root))
        (self.root / 'scratch').write_text('untracked')
        self.assertEqual(expected, self.module.version_name(self.root))
        (self.root / 'source').write_text('changed')
        self.assertEqual(expected + '-dirty', self.module.version_name(self.root))
        self.git('add', 'source')
        self.assertEqual(expected + '-dirty', self.module.version_name(self.root))
        self.git('commit', '-qm', 'next')
        self.assertEqual('0.1.0+' + self.git('rev-parse', 'HEAD')[:7],
                         self.module.version_name(self.root))

    def test_linked_worktree(self):
        linked = self.root / 'linked'
        self.git('worktree', 'add', '-q', '-b', 'linked', str(linked))
        self.assertTrue((linked / '.git').is_file())
        self.assertEqual('0.1.0+' + self.git('rev-parse', 'HEAD')[:7],
                         self.module.version_name(linked))

    def test_nested_copy_does_not_use_parent_repository(self):
        nested = self.root / 'copy'
        nested.mkdir()
        with self.assertLogs('version_name', level='WARNING') as logs:
            self.assertEqual('0.1.0+unknown', self.module.version_name(nested))
        self.assertIn('no .git', logs.output[0])

    def test_git_failure_is_reported(self):
        for failure in (FileNotFoundError('git missing'),
                        subprocess.CalledProcessError(128, ['git'], stderr='dubious ownership'),
                        subprocess.TimeoutExpired(['git'], 5)):
            with self.subTest(failure=failure), patch.object(self.module.subprocess, 'run', side_effect=failure):
                with self.assertLogs('version_name', level='WARNING'):
                    self.assertEqual('0.1.0+unknown', self.module.version_name(self.root))

    def test_wrong_repository_and_empty_head(self):
        wrong_root = subprocess.CompletedProcess(['git'], 0, stdout='/different/root\n')
        with patch.object(self.module.subprocess, 'run', return_value=wrong_root):
            with self.assertLogs('version_name', level='WARNING'):
                self.assertEqual('0.1.0+unknown', self.module.version_name(self.root))
        self.git('checkout', '--orphan', 'empty')
        with self.assertLogs('version_name', level='WARNING'):
            self.assertEqual('0.1.0+unknown', self.module.version_name(self.root))


if __name__ == '__main__':
    unittest.main()
