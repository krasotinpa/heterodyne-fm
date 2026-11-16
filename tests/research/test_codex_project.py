import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class CodexProjectTest(unittest.TestCase):
    def test_disables_mcp_preserves_arguments_and_rejects_ambiguous_names(self):
        launcher = Path(__file__).resolve().parents[2] / "build-support/codex-project.py"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            capture = root / "arguments.json"
            cli = root / "codex"
            cli.write_text(
                f"#!{sys.executable}\n"
                "import json, os, sys\n"
                "from pathlib import Path\n"
                "if sys.argv[1:] == ['mcp', 'list', '--json']:\n"
                "    print(os.environ['HETERODYNE_FIXTURE_SERVERS'])\n"
                "else:\n"
                "    Path(os.environ['HETERODYNE_FIXTURE_CAPTURE']).write_text(json.dumps(sys.argv[1:]))\n"
            )
            cli.chmod(0o755)
            env = dict(os.environ, HETERODYNE_CODEX_BIN=str(cli),
                       HETERODYNE_FIXTURE_CAPTURE=str(capture),
                       HETERODYNE_FIXTURE_SERVERS=json.dumps([{"name": "alpha"}, {"name": "beta-two"}]))
            arguments = ["--config", 'sandbox_mode="read-only"', "--version", "argument with spaces"]
            result = subprocess.run([sys.executable, str(launcher), *arguments],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(capture.read_text()), [
                "--config", "mcp_servers.alpha.enabled=false",
                "--config", "mcp_servers.beta-two.enabled=false", *arguments,
            ])

            capture.unlink()
            env["HETERODYNE_FIXTURE_SERVERS"] = json.dumps([{"name": "ambiguous.name"}])
            result = subprocess.run([sys.executable, str(launcher), "--version"],
                                    env=env, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("cannot be addressed safely", result.stderr)
            self.assertFalse(capture.exists())
