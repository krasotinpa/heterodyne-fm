import hashlib
import json
import os
from pathlib import Path
import runpy
import shutil
import socket
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
        for mode in ("emulator-power-loss", "super-radio-hardware", "rtl-sdr-hardware", "preflight-all-safe"):
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

    def test_preflight_passport_keeps_distinct_package_identity(self):
        self.write_badging(package="fm.heterodyne.app.preflight")
        result = self.cli("passport", "--apk", str(self.apk), "--source-commit", self.commit,
                          "--purpose", "preflight")
        self.assertEqual(0, result.returncode, result.stderr)
        passport = self.root / "preflight-passport.json"
        passport.write_text(result.stdout)
        result = self.cli("check", "--apk", str(self.apk), "--passport", str(passport))
        self.assertEqual(0, result.returncode, result.stderr)
        data = json.loads(passport.read_text())
        data["purpose"] = "current"
        passport.write_text(json.dumps(data))
        result = self.cli("check", "--apk", str(self.apk), "--passport", str(passport))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertFalse(self.trace.exists())

    def test_prepare_copies_config_without_shared_disks_and_refuses_reuse(self):
        template = self.root / "user-avd/config.ini"
        template.parent.mkdir()
        original = "abi.type=x86_64\nhw.lcd.width=1280\ndisk.dataPartition.path=/personal/userdata.img\nimage.sysdir.1=system-images/android-30/default/x86_64/\n"
        template.write_text(original)
        image = self.sdk / "system-images/android-30/default/x86_64"
        image.mkdir(parents=True)
        (image / "system.img").write_bytes(b"SDK image")
        result = self.cli("prepare", "--name", "owned", "--template", str(template))
        self.assertEqual(0, result.returncode, result.stderr)
        manifest = self.root / ".scratch/preflight-fixtures/owned/fixture.json"
        self.assertEqual("stopped", json.loads(manifest.read_text())["state"])
        self.assertEqual(original, template.read_text())
        config = (manifest.parent / "avd/owned.avd/config.ini").read_text()
        self.assertNotIn("/personal", config)
        self.assertIn("fastboot.forceColdBoot=yes", config)
        self.assertIn("hw.audioOutput=yes", config)
        result = self.cli("fixture", "--manifest", str(manifest))
        self.assertEqual(0, result.returncode, result.stderr)
        result = self.cli("prepare", "--name", "owned", "--template", str(template))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertFalse(self.trace.exists())

    def test_android_ui_requires_preflight_identity_before_any_adb_action(self):
        passport, _ = self.passport()
        result = self.cli("run", "android-ui", "--apk", str(self.apk), "--passport", str(passport))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("requires preflight", result.stderr)
        self.assertFalse(self.trace.exists())

    def android_arguments(self, adb_port):
        self.write_badging(package="fm.heterodyne.app.preflight")
        result = self.cli("passport", "--apk", str(self.apk), "--source-commit", self.commit, "--purpose", "preflight")
        passport = self.root / "preflight-passport.json"
        passport.write_text(result.stdout)
        template = self.root / "template.ini"
        template.write_text("abi.type=x86_64\n")
        image = self.sdk / "system-images/android-30/default/x86_64"
        image.mkdir(parents=True)
        (image / "system.img").write_bytes(b"installed image")
        aapt = self.sdk / "build-tools/36.0.0/aapt"
        aapt.write_text('#!/bin/sh\nif [ "$2" = xmltree ]; then\ncat <<\'XML\'\npackage="fm.heterodyne.app.preflight.test"\ntargetPackage="fm.heterodyne.app.preflight"\nname="fm.heterodyne.app.test.EmulatorOnlyRunner"\nXML\nelse\ncat "$PREFLIGHT_TEST_BADGING"\nfi\n')
        for name in ("platform-tools/adb", "emulator/emulator", "emulator/qemu/linux-x86_64/qemu-system-x86_64-headless"):
            tool = self.sdk / name
            tool.parent.mkdir(parents=True, exist_ok=True)
            tool.write_text('#!/bin/sh\nprintf invoked >> "$PREFLIGHT_TEST_TRACE"\n')
            tool.chmod(0o755)
        result = self.cli("prepare", "--name", "owned", "--template", str(template), "--adb-port", str(adb_port))
        self.assertEqual(0, result.returncode, result.stderr)
        manifest = self.root / ".scratch/preflight-fixtures/owned/fixture.json"
        return ["run", "android-ui", "--apk", str(self.apk), "--passport", str(passport),
                "--manifest", str(manifest), "--test-apk", str(self.apk),
                "--test-sha256", hashlib.sha256(self.apk.read_bytes()).hexdigest()]

    def test_busy_private_adb_port_refuses_before_starting_tools(self):
        with socket.socket() as occupied:
            occupied.bind(("127.0.0.1", 0))
            occupied.listen()
            result = self.cli(*self.android_arguments(occupied.getsockname()[1]))
            self.assertEqual(2, result.returncode, result.stderr)
            self.assertIn("Address already in use", result.stderr)
            self.assertFalse(self.trace.exists())
            self.assertFalse((self.root / ".scratch/preflight-fixtures/owned/runs").exists())

    def test_timeout_retains_partial_output_and_failed_step(self):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        args = self.android_arguments(port)
        # Fault injection at the external process boundary; no ADB process is started.
        hook = self.root / "python-hook"
        hook.mkdir()
        (hook / "sitecustomize.py").write_text("""import subprocess
original = subprocess.run
def run(argv, *args, **kwargs):
    if str(argv[0]).endswith('/platform-tools/adb') and argv[-1] == 'start-server':
        raise subprocess.TimeoutExpired(argv, 60, output=b'partial stdout\\n', stderr=b'partial stderr\\n')
    return original(argv, *args, **kwargs)
subprocess.run = run
""")
        self.env["PYTHONPATH"] = str(hook)
        result = self.cli(*args)
        self.assertEqual(2, result.returncode, result.stderr)
        reports = list((self.root / ".scratch/preflight-fixtures/owned/runs").glob("*/report.json"))
        self.assertEqual(1, len(reports))
        report = json.loads(reports[0].read_text())
        self.assertEqual("failed", report["status"])
        steps = report["Run 4 Preflight Report"]["steps"]
        self.assertEqual(1, len(steps))
        self.assertEqual("timeout", steps[0]["status"])
        self.assertIsNone(steps[0]["exit_code"])
        self.assertEqual("partial stdout\npartial stderr\n", (reports[0].parent / steps[0]["evidence"]).read_text())
        self.assertFalse(self.trace.exists())

    def test_visual_rejects_non_preflight_apk_before_using_adb(self):
        passport, _ = self.passport()
        result = self.cli("run", "visual", "--apk", str(self.apk), "--passport", str(passport))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("visual requires preflight APK", result.stderr)
        self.assertFalse(self.trace.exists())

    def test_visual_rejects_https_before_inspecting_apk_or_using_adb(self):
        passport, _ = self.passport()
        result = self.cli("run", "visual", "--https", "--apk", str(self.apk), "--passport", str(passport))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("--https requires android-ui mode", result.stderr)
        self.assertFalse(self.trace.exists())

    def test_simulated_receiver_requires_preflight_identity_before_adb(self):
        passport, _ = self.passport()
        result = self.cli("run", "simulated-receiver", "--apk", str(self.apk), "--passport", str(passport))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("simulated-receiver requires preflight APK", result.stderr)
        self.assertFalse(self.trace.exists())

    def test_emulator_reboot_requires_preflight_before_adb(self):
        passport, _ = self.passport()
        result = self.cli("run", "emulator-reboot", "--apk", str(self.apk), "--passport", str(passport))
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("emulator-reboot requires preflight APK", result.stderr)
        self.assertFalse(self.trace.exists())

    def test_reboot_observation_rejects_an_extra_app_launch_or_focus_request(self):
        observe = runpy.run_path(str(SCRIPT))["assert_reboot_playback"]
        baseline = "PREFLIGHT pid=42 receiver=FakeReceiver\n"
        playback = (baseline + "EVENT autostart reason=application-open result=focus-request\n"
                    "EVENT autostart reason=application-open result=selected\n"
                    "EVENT Audio start\nF_AUDIO pid=42 state=Playing freq=97700 streaming=true\n")
        observe(baseline + playback, baseline, "42")
        with self.assertRaisesRegex(ValueError, "exactly one new application process"):
            observe(baseline + "PREFLIGHT pid=43 receiver=FakeReceiver\n" + playback, baseline, "42")
        with self.assertRaisesRegex(ValueError, "exactly one autostart focus request"):
            observe(baseline + playback + "EVENT autostart reason=application-open result=focus-request\n",
                    baseline, "42")

    def test_launch_can_poll_before_the_first_journal_write(self):
        read = runpy.run_path(str(SCRIPT))["read_journal"]

        def device(_label, _shell, script):
            result = subprocess.run(["bash", "-c", 'run-as() { shift; "$@"; }; ' + script],
                                    cwd=self.root, capture_output=True, text=True, check=True)
            return result.stdout

        self.assertEqual("", read(device, "first", "fm.heterodyne.app.preflight", pending=True))
        path = self.root / "files/module-diagnostics/diagnostic.log"
        path.parent.mkdir(parents=True)
        path.write_text("PREFLIGHT pid=42 receiver=FakeReceiver\n")
        self.assertEqual(path.read_text(), read(device, "second", "fm.heterodyne.app.preflight", pending=True))

    def test_audio_option_refuses_other_modes_before_inspecting_apk(self):
        result = self.cli("run", "emulator-reboot", "--audio",
                          "--apk", "missing.apk", "--passport", "missing.json")
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertIn("--audio requires simulated-receiver mode", result.stderr)
        self.assertFalse(self.trace.exists())

    def test_continuous_audio_keeps_budget_findings_and_rejects_short_evidence(self):
        continuous_audio = runpy.run_path(str(SCRIPT))["continuous_audio"]
        lines = [
            f"D_SAMPLE second={second} elapsed={second * 1000} bytes={96000 + second * 192000} "
            f"badPackets=0 realignments=0 underruns={underruns} maxGapMs={gap} minFrames=12000 "
            "pauses=0 lostLogs=0 logFailures=0"
            for second, underruns, gap in ((0, 0, 0), (600, 1, 100), (1200, 1, 101), (1800, 3, 101))
        ]
        marker = "D_CONTINUOUS state=Playing starts=1 frequency=97700"
        result = continuous_audio(lambda *_args, **_kwargs: "\n".join([*lines, marker]))
        self.assertEqual([1, 0, 2], result["underruns_per_ten_minutes"])
        self.assertFalse(result["max_gap_budget_passed"])
        self.assertFalse(result["underrun_budget_passed"])
        lines[-1] = lines[-1].replace("elapsed=1800000", "elapsed=1799999")
        with self.assertRaisesRegex(ValueError, "missing thirty-minute PCM evidence"):
            continuous_audio(lambda *_args, **_kwargs: "\n".join([*lines, marker]))
