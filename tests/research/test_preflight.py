import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / "build-support/preflight.py"


class PreflightTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="preflight fixture ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        support = self.root / "build-support"
        support.mkdir()
        if SCRIPT.exists():
            shutil.copyfile(SCRIPT, support / SCRIPT.name)
        self.script = support / SCRIPT.name
        self.trace = self.root / "effects"
        (support / "validate.sh").write_text(
            '#!/bin/sh\nprintf gate >> "$PREFLIGHT_TEST_TRACE"\nexit "${PREFLIGHT_TEST_GATE_EXIT:-0}"\n'
        )
        self.sdk = self.root / "sdk"
        tools = self.sdk / "build-tools/36.0.0"
        tools.mkdir(parents=True)
        aapt = tools / "aapt"
        aapt.write_text('#!/bin/sh\ncat "$PREFLIGHT_TEST_BADGING"\n')
        signer = tools / "apksigner"
        signer.write_text('#!/bin/sh\nprintf "Signer #1 certificate SHA-256 digest: %s\\n" "' + 'a' * 64 + '"\nexit "${PREFLIGHT_TEST_SIGN_EXIT:-0}"\n')
        for tool in (aapt, signer):
            tool.chmod(0o755)
        self.apk = self.root / "app.apk"
        self.apk.write_bytes(b"fixture APK bytes")
        self.badging = self.root / "badging"
        for args in (["init", "-q"], ["config", "user.name", "<redacted-user>"],
                     ["config", "user.email", "redacted@example.invalid"],
                     ["add", "build-support"], ["-c", "commit.gpgsign=false", "commit", "-qm", "fixture"]):
            result = subprocess.run(["git", "-C", str(self.root), *args], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
        self.commit = subprocess.check_output(["git", "-C", str(self.root), "rev-parse", "HEAD"], text=True).strip()
        self.write_badging()
        self.env = dict(os.environ, PREFLIGHT_TEST_BADGING=str(self.badging), PREFLIGHT_TEST_TRACE=str(self.trace))

    def write_badging(self, suffix="", package="fm.heterodyne.app", abis="'arm64-v8a' 'x86_64'"):
        self.badging.write_text(
            f"package: name='{package}' versionCode='1' versionName='0.1.0+{self.commit[:7]}{suffix}'\n"
            f"sdkVersion:'30'\ntargetSdkVersion:'30'\nnative-code: {abis}\n"
        )

    def cli(self, *args):
        return subprocess.run([sys.executable, str(self.script), *args, "--sdk", str(self.sdk)],
                              cwd=self.root, env=self.env, capture_output=True, text=True)

    def passport(self):
        result = self.cli("passport", "--apk", str(self.apk), "--source-commit", self.commit)
        self.assertEqual(0, result.returncode, result.stderr)
        path = self.root / "passport.json"
        path.write_text(result.stdout)
        return path, json.loads(result.stdout)

    def test_passport_and_tampered_apk(self):
        passport, data = self.passport()
        self.assertEqual(self.commit, data["source_commit"])
        self.assertFalse(data["dirty"])
        self.assertEqual(["arm64-v8a", "x86_64"], data["apk"]["abis"])
        self.assertEqual("a" * 64, data["apk"]["certificate_sha256"])
        checked = self.cli("check", "--apk", str(self.apk), "--passport", str(passport))
        self.assertEqual(0, checked.returncode, checked.stderr)
        self.apk.write_bytes(b"replaced APK")
        rejected = self.cli("run", "offline", "--apk", str(self.apk), "--passport", str(passport))
        self.assertEqual(2, rejected.returncode, rejected.stderr)
        self.assertIn("passport mismatch", rejected.stderr)
        self.assertFalse(self.trace.exists())

    def test_wrong_build_and_restricted_modes_have_no_effects(self):
        passport, data = self.passport()
        for field, value in (("package", "foreign.app"), ("abis", ["arm64-v8a"]),
                             ("min_sdk", 29), ("certificate_sha256", "b" * 64)):
            with self.subTest(field=field):
                altered = json.loads(json.dumps(data))
                altered["apk"][field] = value
                passport.write_text(json.dumps(altered))
                result = self.cli("run", "offline", "--apk", str(self.apk), "--passport", str(passport))
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertFalse(self.trace.exists())
        passport.write_text(json.dumps(data))
        for mode in ("android-ui", "visual", "simulated-receiver", "emulator-reboot",
                     "emulator-power-loss", "super-radio-hardware", "rtl-sdr-hardware", "preflight-all-safe"):
            with self.subTest(mode=mode):
                result = self.cli("run", mode, "--apk", str(self.apk), "--passport", str(passport))
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn("not admitted", result.stderr)
                self.assertFalse(self.trace.exists())

    def test_dirty_stale_signature_failure_and_gate_exit(self):
        passport, _ = self.passport()
        result = self.cli("run", "offline", "--apk", str(self.apk), "--passport", str(passport))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("gate", self.trace.read_text())
        self.trace.unlink()
        self.env["PREFLIGHT_TEST_GATE_EXIT"] = "42"
        result = self.cli("run", "offline", "--apk", str(self.apk), "--passport", str(passport))
        self.assertEqual(42, result.returncode, result.stderr)
        self.trace.unlink()
        self.env["PREFLIGHT_TEST_SIGN_EXIT"] = "1"
        result = self.cli("check", "--apk", str(self.apk), "--passport", str(passport))
        self.assertEqual(2, result.returncode, result.stderr)
        del self.env["PREFLIGHT_TEST_SIGN_EXIT"]
        self.write_badging("-dirty")
        dirty, _ = self.passport()
        result = self.cli("run", "offline", "--apk", str(self.apk), "--passport", str(dirty))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("dirty APK", result.stderr)
        self.write_badging()
        passport, _ = self.passport()
        subprocess.run(["git", "-C", str(self.root), "-c", "commit.gpgsign=false", "commit", "--allow-empty", "-qm", "later"], check=True, capture_output=True)
        result = self.cli("run", "offline", "--apk", str(self.apk), "--passport", str(passport))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("stale APK", result.stderr)
        self.assertFalse(self.trace.exists())

    def test_fixture_rejects_external_disk_and_shared_adb(self):
        fixture_root = self.root / ".scratch/preflight-fixtures/owned"
        avd_home = fixture_root / "avd"
        avd = avd_home / "run4.avd"
        avd.mkdir(parents=True)
        (avd_home / "run4.ini").write_text(f"path={avd}\ntarget=android-30\n")
        config = avd / "config.ini"
        config.write_text("abi.type=x86_64\nhw.lcd.width=1280\nhw.lcd.height=720\nhw.lcd.density=160\n")
        manifest = fixture_root / "fixture.json"
        fixture = dict(schema=1, avd_name="run4", adb_port=5038, emulator_port=5580,
                       serial="emulator-5580", state="stopped")
        manifest.write_text(json.dumps(fixture))
        result = self.cli("fixture", "--manifest", str(manifest))
        self.assertEqual(0, result.returncode, result.stderr)
        (avd / "userdata.img").symlink_to(self.apk)
        result = self.cli("fixture", "--manifest", str(manifest))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("symlink", result.stderr)
        (avd / "userdata.img").unlink()
        image = avd / "userdata-qemu.img"
        image.write_bytes(b"QFI\xfbfixture qcow2 header")
        qemu_img = self.sdk / "emulator/qemu-img"
        qemu_img.parent.mkdir(parents=True)
        qemu_img.write_text('#!/bin/sh\nprintf \'[{"filename":"/outside/userdata.img"}]\\n\'\n')
        qemu_img.chmod(0o755)
        result = self.cli("fixture", "--manifest", str(manifest))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("external disk backing", result.stderr)
        image.unlink()
        config.write_text(config.read_text() + "disk.dataPartition.path=/outside/userdata.img\n")
        result = self.cli("fixture", "--manifest", str(manifest))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("disk path", result.stderr)
        fixture["adb_port"] = 5037
        manifest.write_text(json.dumps(fixture))
        result = self.cli("fixture", "--manifest", str(manifest))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("private ADB", result.stderr)

    def test_running_fixture_requires_owned_processes(self):
        root = self.root / ".scratch/preflight-fixtures/live"
        root.mkdir(parents=True)
        manifest = root / "fixture.json"
        manifest.write_text(json.dumps(dict(schema=1, state="running", avd_name="run4", adb_port=5038,
            emulator_port=5580, serial="emulator-5580",
            emulator_process=dict(pid=os.getpid(), start_ticks="0"),
            adb_process=dict(pid=os.getpid(), start_ticks="0"))))
        result = self.cli("fixture", "--manifest", str(manifest))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("process", result.stderr)

    def test_self_consistent_foreign_and_historical_apks(self):
        for package, abis, message in (("foreign.app", "'x86_64'", "foreign package"),
                ("fm.heterodyne.app", "'arm64-v8a'", "requires x86_64")):
            with self.subTest(package=package, abis=abis):
                self.write_badging(package=package, abis=abis)
                passport, _ = self.passport()
                result = self.cli("run", "offline", "--apk", str(self.apk), "--passport", str(passport))
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn(message, result.stderr)
                self.assertFalse(self.trace.exists())
        result = self.cli("passport", "--apk", str(self.apk), "--source-commit", self.commit, "--purpose", "historical")
        self.assertEqual(0, result.returncode, result.stderr)
        passport.write_text(result.stdout)
        result = self.cli("check", "--apk", str(self.apk), "--passport", str(passport))
        self.assertEqual(0, result.returncode, result.stderr)
        result = self.cli("run", "offline", "--apk", str(self.apk), "--passport", str(passport))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("inspection-only", result.stderr)
        self.assertFalse(self.trace.exists())

    def test_malformed_passport_and_unavailable_tools_fail_closed(self):
        passport, _ = self.passport()
        for contents in ("{", "[]", '{"schema": 999}', '{"schema": 1}'):
            passport.write_text(contents)
            result = self.cli("run", "offline", "--apk", str(self.apk), "--passport", str(passport))
            self.assertEqual(2, result.returncode, result.stderr)
            self.assertFalse(self.trace.exists())
        passport, _ = self.passport()
        (self.sdk / "build-tools/36.0.0/aapt").unlink()
        result = self.cli("run", "offline", "--apk", str(self.apk), "--passport", str(passport))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertFalse(self.trace.exists())
