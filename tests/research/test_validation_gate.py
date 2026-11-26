import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class ValidationGateTest(unittest.TestCase):
    def test_parts_and_failure_stop_before_android(self):
        with tempfile.TemporaryDirectory(prefix="gate fixture ") as directory:
            root = Path(directory)
            (root / "build-support").mkdir()
            source = Path(__file__).resolve().parents[2] / "build-support/validate.sh"
            shutil.copyfile(source, root / "build-support/validate.sh")
            binary = root / "bin"
            binary.mkdir()
            python = binary / "python3.12"
            python.write_text('#!/bin/sh\nprintf "python:%s\\n" "$*" >> "$HETERODYNE_GATE_TRACE"\nexit "${HETERODYNE_GATE_PYTHON_EXIT:-0}"\n')
            python.chmod(0o755)
            gradle = root / "gradlew"
            gradle.write_text('#!/bin/sh\nprintf "gradle:%s\\n" "$*" >> "$HETERODYNE_GATE_TRACE"\nif [ "$1" = "-p" ]; then exit "${HETERODYNE_GATE_CONTRACT_EXIT:-0}"; fi\nmkdir -p app/build/outputs/apk/debug app/build/outputs/apk/preflight\nprintf fixture > app/build/outputs/apk/debug/app-debug.apk\nprintf preflight > app/build/outputs/apk/preflight/app-preflight.apk\n')
            gradle.chmod(0o755)
            trace = root / "trace"
            env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ["PATH"],
                       HETERODYNE_GATE_TRACE=str(trace))
            expected_python = ["python:-m unittest discover -s tests/research",
                               "python:-m unittest discover -s tests/build_version"]
            expected_android = ["gradle:-p build-support/ui-contract-consumer check",
                                "gradle:test lint assembleDebug assemblePreflight"]
            for mode, expected in [("all", expected_python + expected_android),
                                   ("python", expected_python), ("android", expected_android)]:
                with self.subTest(mode=mode):
                    trace.unlink(missing_ok=True)
                    result = subprocess.run(["bash", str(root / "build-support/validate.sh"), mode],
                                            env=env, cwd=root.parent, capture_output=True, text=True)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(trace.read_text().splitlines(), expected)
                    if mode != "python":
                        self.assertIn("app/build/outputs/apk/debug/app-debug.apk", result.stdout)
                        self.assertIn("app/build/outputs/apk/preflight/app-preflight.apk", result.stdout)

            trace.unlink()
            env["HETERODYNE_GATE_CONTRACT_EXIT"] = "43"
            result = subprocess.run(["bash", str(root / "build-support/validate.sh"), "android"],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 43)
            self.assertEqual(trace.read_text().splitlines(), expected_android[:1])
            del env["HETERODYNE_GATE_CONTRACT_EXIT"]

            trace.unlink()
            env["HETERODYNE_GATE_PYTHON_EXIT"] = "42"
            result = subprocess.run(["bash", str(root / "build-support/validate.sh")],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 42)
            self.assertEqual(trace.read_text().splitlines(), expected_python[:1])

            trace.unlink()
            result = subprocess.run(["bash", str(root / "build-support/validate.sh"), "unknown"],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            self.assertFalse(trace.exists())
