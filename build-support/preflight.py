#!/usr/bin/env python3.12
"""Run 4 preflight: inspect APKs and run checks on private Android fixtures."""
import argparse
from contextlib import ExitStack
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
EMULATOR_MODES = ("android-ui", "visual", "simulated-receiver", "emulator-reboot")
SUITE_PHASES = (("abcx", "android-ui", None), ("visual", "visual", None),
                ("f", "simulated-receiver", None), ("g", "emulator-reboot", None),
                ("de", "simulated-receiver", "all"))
BLOCKED_MODES = ("emulator-power-loss", "super-radio-hardware", "rtl-sdr-hardware")
MODES = ("offline", *EMULATOR_MODES, *BLOCKED_MODES, "preflight-all-safe")


def output(*command):
    return subprocess.run(command, cwd=ROOT, check=True, capture_output=True,
                          text=True, timeout=30).stdout.strip()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def measure(apk, sdk, build_tools):
    before = sha256(apk)
    tools = sdk / "build-tools" / build_tools
    badging = output(str(tools / "aapt"), "dump", "badging", str(apk))
    signature = output(str(tools / "apksigner"), "verify", "--print-certs", str(apk))
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    abis = re.search(r"^native-code: (.+)$", badging, re.M)
    certs = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$", signature, re.M)
    minimum = re.search(r"^sdkVersion:'(\d+)'$", badging, re.M)
    target = re.search(r"^targetSdkVersion:'(\d+)'$", badging, re.M)
    require(package and abis and minimum and target and len(certs) == 1, "incomplete APK identity or signature")
    require(before == sha256(apk), "APK changed during inspection")
    return dict(sha256=before, size=apk.stat().st_size, package=package[1], version_code=int(package[2]),
                version_name=package[3], abis=re.findall(r"'([^']+)'", abis[1]),
                min_sdk=int(minimum[1]), target_sdk=int(target[1]), certificate_sha256=certs[0])


def passport(args):
    commit = output("git", "rev-parse", "--verify", args.source_commit + "^{commit}")
    require(re.fullmatch(r"[0-9a-f]{40}", args.source_commit) and commit == args.source_commit,
            "source commit must be an exact full SHA")
    apk = measure(args.apk, args.sdk, args.build_tools)
    require(apk["version_name"] in ("0.1.0+" + commit[:7], "0.1.0+" + commit[:7] + "-dirty"),
            "APK version does not match source commit")
    environment = dict(python=platform.python_version(), system=platform.system(),
                       wsl_distro=os.environ.get("WSL_DISTRO_NAME"), build_tools=args.build_tools)
    for name, path in {
        "emulator": args.sdk / "emulator/source.properties",
        "platform_tools": args.sdk / "platform-tools/source.properties",
        "system_image": args.sdk / "system-images/android-30/default/x86_64/source.properties",
        "gradle_wrapper": ROOT / "gradle/wrapper/gradle-wrapper.properties",
        "java": Path(os.environ.get("JAVA_HOME", "/unavailable")) / "release",
    }.items():
        environment[name] = properties(path) if path.is_file() else None
    return dict(schema=1, purpose=args.purpose, source_commit=commit,
                dirty=apk["version_name"].endswith("-dirty"), apk=apk,
                source_basis="versionName and supplied commit; not proof of reproducible source",
                inspection_environment=environment)


def properties(path):
    result = {}
    for line in path.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith(("#", ";")):
            continue
        key, value = line.split("=", 1)
        require(key.strip() not in result, "duplicate fixture property")
        result[key.strip()] = value.strip()
    return result


def owned_process(info, executable, ports):
    require(type(info["pid"]) is int and info["pid"] > 1, "invalid process PID")
    require(executable.is_file(), "process executable is unavailable")
    proc = Path("/proc") / str(info["pid"])
    require(proc.joinpath("exe").resolve(strict=True) == executable.resolve(strict=True), "foreign process executable")
    start = proc.joinpath("stat").read_text().rpartition(")")[2].split()[19]
    require(start == info["start_ticks"], "process PID was reused")
    sockets = set()
    for fd in proc.joinpath("fd").iterdir():
        try:
            link = os.readlink(fd)
        except FileNotFoundError:
            continue
        if link.startswith("socket:["):
            sockets.add(link[8:-1])
    listeners = set()
    for table in ("tcp", "tcp6"):
        for line in Path("/proc/net", table).read_text().splitlines()[1:]:
            fields = line.split()
            if fields[3] == "0A" and fields[9] in sockets:
                listeners.add(int(fields[1].split(":")[1], 16))
    require(set(ports) <= listeners, "process does not own fixture listeners")
    return proc.joinpath("cmdline").read_bytes().decode().rstrip("\0").split("\0")


def verify_adb(data, sdk):
    argv = owned_process(data["adb_process"], sdk / "platform-tools/adb", (data["adb_port"],))
    require(len(argv) == 9 and argv[1:6] == ["-L", f'tcp:{data["adb_port"]}', "fork-server", "server", "--reply-fd"]
            and argv[6].isdigit() and argv[7:] == ["--one-device", data["serial"]],
            "ADB process is not restricted to this serial")


def fixture(args):
    manifest = args.manifest.absolute()
    root = manifest.parent
    owned = ROOT / ".scratch/preflight-fixtures"
    require(root.resolve().is_relative_to(owned.resolve()) and root.resolve() != owned.resolve(),
            "fixture must be inside this worktree's private fixture root")
    require(not any(p.is_symlink() for p in (manifest, *manifest.parents)), "fixture symlink")
    data = json.loads(manifest.read_text())
    require(data["schema"] == 1 and data["state"] in ("stopped", "running"), "unsupported fixture state")
    require(type(data["adb_port"]) is int and 1024 <= data["adb_port"] <= 65535
            and data["adb_port"] != 5037, "private ADB port required")
    port = data["emulator_port"]
    require(type(port) is int and 5554 <= port <= 5682 and port % 2 == 0, "invalid emulator port")
    require(data["adb_port"] not in (port, port + 1), "fixture ports overlap")
    require(data["serial"] == f"emulator-{port}", "explicit serial mismatch")
    name = data["avd_name"]
    require(re.fullmatch(r"[a-zA-Z0-9_-]+", name), "invalid AVD name")
    avd = root / "avd" / (name + ".avd")
    running = data["state"] == "running"
    if running:
        qemu = args.sdk / "emulator/qemu/linux-x86_64/qemu-system-x86_64-headless"
        argv = owned_process(data["emulator_process"], qemu, (port, port + 1))
        require(argv[1:] == emulator_arguments(data, root),
            "unapproved emulator process arguments")
        verify_adb(data, args.sdk)
    for path in root.rglob("*"):
        require(not path.is_symlink(), "fixture symlink")
        require(path.is_dir() or path.is_file(), "unsupported fixture file")
        require(not path.is_file() or path.stat().st_nlink == 1, "fixture hard link")
        require(running or not path.name.endswith(".lock"), "fixture lock: live ownership is not verified")
        if not path.is_file():
            continue
        with path.open("rb") as stream:
            is_qcow = stream.read(4) == b"QFI\xfb"
        if is_qcow:
            chain = json.loads(output(str(args.sdk / "emulator/qemu-img"), "info", "-U", "--output=json", "--backing-chain", str(path)))
            for image in chain:
                backing = Path(image["filename"])
                if not backing.is_absolute():
                    backing = path.parent / backing
                require(backing.resolve().is_relative_to(root.resolve()), "external disk backing")
    ini = properties(root / "avd" / (name + ".ini"))
    require(Path(ini["path"]) == avd and "path.rel" not in ini, "AVD path is not owned")
    config = properties(avd / "config.ini")
    require(config["abi.type"] == "x86_64", "fixture ABI must be x86_64")
    if running:
        config.update(properties(avd / "hardware-qemu.ini"))
    for key, value in config.items():
        if key == "android.sdk.root":
            require(Path(value).resolve() == args.sdk.resolve(), "foreign SDK root")
        elif key.startswith("image.sysdir."):
            require(Path(value).is_absolute() and Path(value).resolve().is_relative_to((args.sdk / "system-images").resolve()),
                    "system image must be inside the selected SDK")
        elif key.endswith((".path", ".initPath")) or Path(value).is_absolute():
            path = Path(value)
            sdk_input = key in ("kernel.path", "disk.ramdisk.path") or key.endswith(".initPath")
            require(path.is_absolute() and (path.resolve().is_relative_to(root.resolve()) or
                (sdk_input and path.resolve().is_relative_to((args.sdk / "system-images").resolve()))), f"external disk path: {key}")
    return dict(stopped_layout_verified=not running, active_process_ownership="verified" if running else "not tested",
                launch_admitted=False, serial=data["serial"], adb_port=data["adb_port"])


def check(args):
    expected = json.loads(args.passport.read_text())
    require(expected["schema"] == 1, "unsupported passport schema")
    require(isinstance(expected["source_commit"], str) and re.fullmatch(r"[0-9a-f]{40}", expected["source_commit"])
            and type(expected["dirty"]) is bool, "invalid passport source identity")
    actual = measure(args.apk, args.sdk, args.build_tools)
    require(actual == expected["apk"], "APK passport mismatch")
    require(expected["purpose"] in ("historical", "current", "preflight"), "unknown passport purpose")
    package = "fm.heterodyne.app.preflight" if expected["purpose"] == "preflight" else "fm.heterodyne.app"
    require(actual["package"] == package, "foreign package")
    require(actual["min_sdk"] == 30 and actual["target_sdk"] == 30, "incompatible SDK")
    require(actual["version_name"] == "0.1.0+" + expected["source_commit"][:7]
            + ("-dirty" if expected["dirty"] else ""), "inconsistent source identity")
    if expected["purpose"] in ("current", "preflight"):
        require(not expected["dirty"], "dirty APK cannot run preflight")
        require(expected["source_commit"] == output("git", "rev-parse", "HEAD"), "stale APK source commit")
        require(not output("git", "status", "--porcelain", "--untracked-files=no"), "source tree is dirty")
        require("x86_64" in actual["abis"], "current preflight requires x86_64")
    else:
        require(actual["abis"] == ["arm64-v8a"] and not expected["dirty"], "incompatible historical APK")
    return expected


def check_fixture_inputs(args):
    require(1024 <= args.adb_port <= 65535 and args.adb_port != 5037, "private ADB port required")
    require(5554 <= args.emulator_port <= 5682 and args.emulator_port % 2 == 0
            and args.adb_port not in (args.emulator_port, args.emulator_port + 1), "invalid fixture ports")
    require(args.template.is_file(), "AVD template is unavailable")
    config = properties(args.template)
    require(config.get("abi.type") == "x86_64", "template ABI must be x86_64")
    image = args.sdk.resolve() / "system-images/android-30/default/x86_64"
    require((image / "system.img").is_file(), "installed API 30 x86_64 image required")
    return config, image


def prepare(args):
    require(re.fullmatch(r"[a-zA-Z0-9_-]+", args.name), "invalid fixture name")
    original = sha256(args.template)
    config, image = check_fixture_inputs(args)
    # Copy device settings only. Never copy personal disks, snapshots or path properties.
    config = {key: value for key, value in config.items()
              if key.startswith("hw.") and not key.endswith((".path", ".initPath"))
              and not Path(value).is_absolute()}
    root = ROOT / ".scratch/preflight-fixtures" / args.name
    require(not any(p.is_symlink() for p in (root, *root.parents)), "fixture symlink")
    root.mkdir(parents=True, exist_ok=False)
    avd = root / "avd" / (args.name + ".avd")
    avd.mkdir(parents=True)
    config.update({"abi.type": "x86_64", "hw.cpu.arch": "x86_64", "hw.initialOrientation": "landscape",
        "hw.lcd.width": "1280", "hw.lcd.height": "720", "hw.lcd.density": "160", "hw.ramSize": "2048",
        "hw.audioInput": "no", "hw.audioOutput": "yes", "hw.sdCard": "no", "image.sysdir.1": str(image) + "/", "tag.id": "default", "target": "android-30",
        "disk.dataPartition.path": str(avd / "userdata-qemu.img"), "disk.dataPartition.size": "2G",
        "fastboot.forceColdBoot": "yes", "fastboot.forceFastBoot": "no",
        "firstboot.bootFromDownloadableSnapshot": "no", "firstboot.bootFromLocalSnapshot": "no", "firstboot.saveToLocalSnapshot": "no"})
    (avd / "config.ini").write_text("".join(f"{k}={v}\n" for k, v in sorted(config.items())))
    (root / "avd" / (args.name + ".ini")).write_text(f"path={avd}\ntarget=android-30\n")
    manifest = root / "fixture.json"
    manifest.write_text(json.dumps(dict(schema=1, state="stopped", avd_name=args.name,
        adb_port=args.adb_port, emulator_port=args.emulator_port, serial=f"emulator-{args.emulator_port}",
        template_sha256=original), indent=2))
    require(sha256(args.template) == original, "template changed during copy")
    args.manifest = manifest
    return dict(manifest=str(manifest), **fixture(args))


def process_info(pid):
    proc = Path('/proc') / str(pid)
    return dict(pid=pid, start_ticks=proc.joinpath('stat').read_text().rpartition(')')[2].split()[19])


def find_process(executable, marker):
    found = []
    for proc in Path('/proc').iterdir():
        if not proc.name.isdigit():
            continue
        try:
            if proc.joinpath('exe').resolve() == executable.resolve() and marker in proc.joinpath('cmdline').read_bytes().decode().split('\0'):
                found.append(process_info(int(proc.name)))
        except (OSError, ValueError):
            continue
    require(len(found) <= 1, 'ambiguous fixture processes')
    return found[0] if found else None


def emulator_arguments(data, root):
    return ['-avd', data['avd_name'], '-datadir', str(root / 'avd' / (data['avd_name'] + '.avd')),
            '-port', str(data['emulator_port']), '-no-window', '-no-audio', '-no-boot-anim',
            '-no-snapshot', '-gpu', 'swiftshader', '-camera-back', 'none', '-camera-front', 'none', '-no-metrics', '-memory', '2048']


VISUAL_SCREENS = ("efir", "parking", "settings", "regions", "stations", "station-error",
                  "catalog-error", "diagnostics", "about")


def visual_matrix(device, phase, run_dir, report):
    shots = report["screenshots"] = []
    report["visual_review"] = "pending"
    device("visual-density", "shell", "wm", "density", "160")
    device("visual-keyboard", "shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "0")
    for width, height in ((1920, 1080), (1280, 720), (1024, 600)):
        resolution = f"{width}x{height}"
        device(f"size-{resolution}", "shell", "wm", "size", resolution)
        for font in ("1.0", "1.3"):
            device(f"font-{resolution}-{font}", "shell", "settings", "put", "system", "font_scale", font)
            for theme in ("Light", "Dark"):
                case = f"{resolution}-{font}-{theme.lower()}"
                guest_id = f"{run_dir.name}-{case}"
                phase("visualScreens", case, "capture", guest_id, "font", font, "theme", theme)
                folder = run_dir / case
                device(f"pull-{case}", "pull", f"/data/local/tmp/run4-visual-{guest_id}", str(folder))
                for screen in VISUAL_SCREENS:
                    path = folder / f"{screen}.png"
                    with path.open("rb") as stream:
                        header = stream.read(24)
                        stream.seek(-12, os.SEEK_END)
                        end = stream.read()
                    require(end == bytes.fromhex("0000000049454e44ae426082")
                            and header[:16] == bytes.fromhex("89504e470d0a1a0a0000000d49484452")
                            and int.from_bytes(header[16:20], "big") == width
                            and int.from_bytes(header[20:24], "big") == height,
                            f"screenshot has invalid PNG boundaries or dimensions: {case}/{screen}")
                    shots.append(dict(case=case, screen=screen, path=str(path.relative_to(run_dir)),
                                      sha256=sha256(path), width=width, height=height))


def read_journal(device, label, package, *, pending=False):
    path = "files/module-diagnostics/diagnostic.log"
    if pending:
        # A fresh process can reach the foreground before the async writer creates the file.
        return device(label, "shell", f"run-as {package} sh -c 'if [ -e {path} ]; then cat {path}; fi'")
    return device(label, "shell", "run-as", package, "cat", path)


def process_journal(log, pid):
    _, marker, current = log.rpartition(f"PREFLIGHT pid={pid} ")
    require(marker, "missing journal for current application process")
    return current


def latest_audio(current, pid):
    states = re.findall(rf"F_AUDIO pid={pid} state=([^\n]+)", current)
    return states[-1] if states else None


def assert_playing(current, pid):
    require(latest_audio(current, pid) == "Playing freq=97700 streaming=true",
            "ordinary process is not streaming the saved station")
    require(current.count(" Audio start") == 1, "playback did not start exactly once")
    require(len(re.findall(r"autostart reason=application-open result=(selected|already-selected)", current)) == 1,
            "expected one successful ordinary launch autostart")


def wait_playing(device, label, pid, package, previous_log=""):
    import time
    deadline = time.monotonic() + 20
    for attempt in range(40):
        log = read_journal(device, f"{label}-{attempt}", package)
        require(log.startswith(previous_log), "existing journal was replaced")
        new_log = log[len(previous_log):]
        if f"PREFLIGHT pid={pid} " in new_log:
            current = process_journal(new_log, pid)
            if latest_audio(current, pid) == "Playing freq=97700 streaming=true":
                assert_playing(current, pid)
                return
        if time.monotonic() >= deadline:
            break
        time.sleep(0.5)
    for name, command in (("audio-flinger", ("dumpsys", "media.audio_flinger")),
                          ("android-log", ("logcat", "-d", "-t", "400"))):
        try:
            device(f"{label}-{name}", "shell", *command, timeout=20)
        except (OSError, ValueError, subprocess.SubprocessError):
            pass  # Keep the streaming failure as the primary error.
    raise ValueError(f"{label}: selected station did not stream after ordinary launch")


def continuous_audio(phase):
    output = phase("continuousPcm", test_class="Run4AudioTest", timeout=1950)
    fields = ("second", "elapsed", "bytes", "badPackets", "realignments", "underruns",
              "maxGapMs", "minFrames", "pauses", "lostLogs", "logFailures")
    pattern = "D_SAMPLE " + " ".join(f"{key}=([0-9]+)" for key in fields)
    samples = [dict(zip(fields, map(int, row))) for row in re.findall(pattern, output)]
    require([sample["second"] for sample in samples] == [0, 600, 1200, 1800]
            and all(sample["elapsed"] >= sample["second"] * 1000 for sample in samples),
            "missing thirty-minute PCM evidence")
    require("D_CONTINUOUS state=Playing starts=1 frequency=97700" in output,
            "missing continuous PCM evidence")
    underruns = [after["underruns"] - before["underruns"]
                 for before, after in zip(samples, samples[1:])]
    require(all(count >= 0 for count in underruns), "AudioTrack underrun counter reset")
    return dict(samples=samples, underruns_per_ten_minutes=underruns,
                max_gap_budget_passed=all(sample["maxGapMs"] <= 100 for sample in samples),
                underrun_budget_passed=all(count <= 1 for count in underruns),
                frequency_khz=97700, pcm="synthetic 48 kHz stereo s16le via Android AudioTrack")


def receiver_lifecycle(device, phase, launch, package, report):
    import time
    import xml.etree.ElementTree as ET

    def assert_pid(label, expected):
        require(device(label, "shell", "pidof", package).strip() == expected,
                "ordinary application PID changed during observation")

    def force_stop(label):
        device(label, "shell", "am", "force-stop", package)
        # pidof exits 1 when the process is gone. Redirect only its output and preserve a detectable marker.
        state = device(label + "-pid", "shell", f"pidof {package} || echo STOPPED").strip()
        require(state == "STOPPED", "application process survived force-stop")

    phase("seedPlayback", test_class="Run4LifecycleTest")
    first = launch("f-live-before-stop")
    wait_playing(device, "f-live-before", first, package)
    force_stop("f-force-stop-playing")
    reopened = launch("f-reopened-playing")
    require(first != reopened, "force-stop must create a new playing process")
    wait_playing(device, "f-playing-reopened", reopened, package)
    time.sleep(10)
    assert_playing(process_journal(read_journal(device, "f-playing-observation", package), reopened), reopened)
    assert_pid("f-playing-observation-pid", reopened)

    # Resolve the ordinary Activity's Stop button at its actual bounds rather than using a coordinate constant.
    xml_file = "/data/local/tmp/run4-stop.xml"
    device("f-stop-tree", "shell", "uiautomator", "dump", xml_file)
    try:
        xml = ET.fromstring(device("f-stop-tree-read", "shell", "cat", xml_file))
    except ET.ParseError as error:
        raise ValueError("ordinary Stop accessibility tree is invalid XML") from error
    nodes = [node for node in xml.iter("node") if node.get("package") == package
             and node.get("text") == "Стоп" and node.get("enabled") == "true"]
    require(len(nodes) == 1, "ordinary Stop button is missing or ambiguous")
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", nodes[0].get("bounds", ""))
    require(bounds, "ordinary Stop button has no bounds")
    left, top, right, bottom = map(int, bounds.groups())
    require(left < right and top < bottom, "ordinary Stop button has empty bounds")
    device("f-stop-button", "shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
    for attempt in range(20):
        log = read_journal(device, f"f-user-stop-{attempt}", package)
        if f"F_AUDIO pid={reopened} state=Stopped freq=97700 streaming=false" in log:
            saved = json.loads(device(f"f-stop-storage-{attempt}", "shell", "run-as", package,
                                      "cat", "files/stations.json"))
            if saved.get("lastPlayed") is False:
                break
        time.sleep(0.5)
    else:
        raise ValueError("Stop did not end the ordinary audio stream")
    force_stop("f-force-stop-stopped")
    stopped = launch("f-reopened-stopped")
    require(stopped not in (first, reopened), "force-stop must create a new stopped process")
    time.sleep(10)
    current = process_journal(read_journal(device, "f-stopped-observation", package), stopped)
    assert_pid("f-stopped-observation-pid", stopped)
    require("autostart reason=application-open result=skipped" in current and " Audio start" not in current
            and f"F_AUDIO pid={stopped} state=Playing" not in current,
            "unwanted autostart after Stop")
    report["force_stop"] = dict(playing_before=first, playing_reopened=reopened, stopped_reopened=stopped,
                                station_khz=97700, playing_observation_seconds=10, stopped_observation_seconds=10)
    replug = phase("replug", test_class="Run4LifecycleTest", timeout=420)
    cycles = re.findall(r"F_REPLUG cycle=(\d+) confirmed=(\d+) audio=(Playing|Stopped) restarts=(\d+) decision=(selected|skipped)", replug)
    require(len(cycles) == 10 and [int(cycle[0]) for cycle in cycles] == list(range(1, 11)), "missing replug evidence")
    require(all(cycle[1:] == ("97700", "Playing", "1", "selected") for cycle in cycles[:8])
            and all(cycle[2:] == ("Stopped", "0", "skipped") for cycle in cycles[8:]), "unexpected replug audio state")
    report["fake_replugs"] = [dict(cycle=int(n), frequency_khz=int(f), audio=a, starts=int(s), decision=d)
                             for n, f, a, s, d in cycles]
    require("F_COOLDOWN decision=rate-limited restarts=0" in replug, "missing cooldown evidence")
    report["quick_return"] = {"decision": "rate-limited", "starts": 0}
    faults = phase("faults", test_class="Run4LifecycleTest")
    report["faults"] = [line.split("stream=", 1)[-1] for line in faults.splitlines()
                        if any(tag in line for tag in ("F_DELAY", "F_STALE", "F_PCM_LOSS"))]
    require(len(report["faults"]) == 3, "missing fault evidence")


def exported_journals(result, count=2):
    exports = re.findall(r"MEDIASTORE name=(heterodyne-[a-zA-Z0-9_.-]+) bytes=(\d+) sha256=([0-9a-f]{64}) pending=0 path=Download/", result)
    require(len(exports) == count and len({entry[0] for entry in exports}) == count, "missing export evidence")
    return [(name, int(size), digest) for name, size, digest in exports]


def verify_journals(device, run_dir, label, exports):
    verified = []
    for index, (name, size, digest) in enumerate(exports):
        path = run_dir / (f"{label}-{name}" if label else name)
        step = f"{label}-pull-export-{index}" if label else f"pull-export-{index}"
        device(step, "pull", "/sdcard/Download/" + name, str(path))
        require(sha256(path) == digest and path.stat().st_size == size, "export evidence mismatch")
        verified.append(dict(name=name, size=size, sha256=digest, evidence=path.name))
    return verified


def assert_reboot_playback(log, baseline, pid):
    require(log.startswith(baseline), "existing journal was replaced")
    new_log = log[len(baseline):]
    require(re.findall(r"PREFLIGHT pid=(\d+) ", new_log) == [pid],
            "expected exactly one new application process after boot")
    current = process_journal(new_log, pid)
    require(current.count("autostart reason=application-open result=focus-request") == 1,
            "expected exactly one autostart focus request after boot")
    assert_playing(current, pid)


def reboot_persistence(device, phase, launch, reboot, cold_boot, identity, package, run_dir, report):
    import time

    def boot_count(label):
        count = device(label, "shell", "settings", "get", "global", "boot_count").strip()
        require(count.isdigit(), "Android boot counter unavailable")
        return int(count)

    def state(label):
        raw = device(label, "shell", "run-as", package, "cat", "files/stations.json")
        saved = json.loads(raw)
        require(saved["lastPlayed"] is True
                and saved["lastStation"] == {"band": "FM", "frequencyKhz": 97700},
                "saved station or lastPlayed mismatch")
        region = saved["regions"][saved["selectedRegion"]]
        require(saved["selectedRegion"] == "yaroslavl" and region["activeName"] == "Проверка G"
                and any(group["name"] == "Проверка G" and
                        any(entry["name"] == "Тест G" and entry["frequencyKhz"] == 97700
                            for entry in group["entries"]) for group in region["sets"]),
                "saved Region, active selection or edited station mismatch")
        return saved

    exports = exported_journals(phase("exportRebootJournals"))
    report["exports_before"] = verify_journals(device, run_dir, "g-before", exports)
    phase("seedRebootPlayback", test_class="Run4LifecycleTest")
    pid = launch("g-before-playback")
    wait_playing(device, "g-before-stream", pid, package)
    report["boots"] = []
    for label, restart, expect_new_host in (("adb-reboot", reboot, False), ("cold-boot", cold_boot, True)):
        old_count = boot_count(f"{label}-before-count")
        old_identity = identity()
        before = state(f"{label}-before-state")
        before_journal = read_journal(device, f"{label}-before-journal", package)
        assert_playing(process_journal(before_journal, pid), pid)
        device(f"{label}-sync", "shell", "sync")
        restart(label, old_count)
        new_count = boot_count(f"{label}-after-count")
        new_identity = identity()
        require(new_count == old_count + 1, "expected exactly one new Android boot")
        require((old_identity != new_identity) == expect_new_host, "unexpected Emulator process identity after boot")
        absent = device(f"{label}-before-manual-launch", "shell", f"pidof {package} || echo STOPPED").strip()
        require(absent == "STOPPED", "preflight process started before manual launch")
        require(state(f"{label}-restored-state") == before, "application data changed during boot")
        old_log = read_journal(device, f"{label}-restored-journal", package)
        require(old_log.startswith(before_journal), "pre-launch journal lost its saved prefix")
        require("PREFLIGHT pid=" not in old_log[len(before_journal):],
                "preflight process started and stopped before manual launch")
        retained = verify_journals(device, run_dir, label, exports)
        version = device(f"{label}-version", "shell", "dumpsys", "package", package)
        require(f"versionName={report['apk']['version_name']}" in version, "APK changed across boot")
        pid = launch(f"{label}-launch", previous_log=old_log)
        wait_playing(device, f"{label}-stream", pid, package, previous_log=old_log)
        time.sleep(35)  # Observe past the application's 30-second autostart cooldown.
        final_log = read_journal(device, f"{label}-observation-journal", package)
        assert_reboot_playback(final_log, before_journal, pid)
        require(device(f"{label}-observed-pid", "shell", "pidof", package).strip() == pid,
                "ordinary application process changed while observing playback")
        require(boot_count(f"{label}-observed-count") == new_count, "Android rebooted again during observation")
        require(state(f"{label}-observed-state") == before, "saved app state changed after manual launch")
        report["boots"].append(dict(kind=label, boot_count_before=old_count, boot_count_after=new_count,
                                    emulator_before=old_identity, emulator_after=new_identity, app_pid=pid,
                                    observation_seconds=35, exports=retained, data_preserved=True, journal_preserved=True,
                                    starts=1))


def verify_test_apk(args, passport_data):
    require(sha256(args.test_apk) == args.test_sha256, 'test APK SHA-256 mismatch')
    tools = args.sdk / 'build-tools' / args.build_tools
    signature = output(str(tools / 'apksigner'), 'verify', '--print-certs', str(args.test_apk))
    require(re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$', signature, re.M)
            == [passport_data['apk']['certificate_sha256']], 'foreign test APK signature')
    xml = output(str(tools / 'aapt'), 'dump', 'xmltree', str(args.test_apk), 'AndroidManifest.xml')
    for identity in ('fm.heterodyne.app.preflight.test', 'fm.heterodyne.app.preflight', 'fm.heterodyne.app.test.EmulatorOnlyRunner'):
        require(f'="{identity}"' in xml, 'foreign test APK manifest')


def require_free_ports(ports):
    import socket

    for port in ports:
        with socket.socket(socket.AF_INET6, socket.SOCK_STREAM) as probe:
            probe.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            probe.bind(('::', port))


def run_emulator(args, passport_data):
    import fcntl

    require(passport_data['purpose'] == 'preflight', f'{args.mode} requires preflight APK')
    require(args.manifest and args.test_apk and args.test_sha256, f'{args.mode} requires manifest, test APK and its trusted SHA-256')
    fixture(args)
    root = args.manifest.absolute().parent
    data = json.loads(args.manifest.read_text())
    require(data['state'] == 'stopped', 'runner requires a stopped fixture')
    verify_test_apk(args, passport_data)
    port = data['emulator_port']
    ports = sorted((data['adb_port'], port, port + 1))
    common = Path(output("git", "rev-parse", "--path-format=absolute", "--git-common-dir"))
    lease_dir = common / "preflight-leases"
    require(not lease_dir.is_symlink(), "preflight lease directory symlink")
    lease_dir.mkdir(exist_ok=True)
    with ExitStack() as leases:
        for path in (root / "runner.lease", *(lease_dir / str(endpoint) for endpoint in ports)):
            require(not path.is_symlink(), "preflight lease symlink")
            lock = leases.enter_context(path.open("a"))
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise ValueError(f"preflight port is already leased: {path.name}") from error
        fixture(args)
        require(json.loads(args.manifest.read_text()) == data, 'fixture changed while acquiring port leases')
        require(not (root / 'runs').exists(), 'runner requires a freshly prepared fixture; previous evidence is retained')
        return run_locked_emulator(args, passport_data, root, data, ports)


def run_locked_emulator(args, passport_data, root, data, ports):
    import time
    import uuid

    adb = args.sdk / 'platform-tools/adb'
    emulator = args.sdk / 'emulator/emulator'
    qemu = args.sdk / 'emulator/qemu/linux-x86_64/qemu-system-x86_64-headless'
    require(all(p.is_file() for p in (adb, emulator, qemu)), 'installed Android tools required')
    # Keep leases until all processes have been checked. Reject port use even outside this repository.
    adb_marker = f"tcp:{data['adb_port']}"
    require(not find_process(qemu, data['avd_name']) and not find_process(adb, adb_marker), 'fixture process already exists')
    require_free_ports(ports)
    run_dir = root / 'runs' / uuid.uuid4().hex
    run_dir.mkdir(parents=True)
    report = {'schema': 1, 'mode': args.mode, 'source_commit': passport_data['source_commit'],
              'apk': passport_data['apk'], 'passport': passport_data, 'test_apk_sha256': args.test_sha256, 'steps': [], 'status': 'failed',
              'Defect and Issue Report': {'status': 'requires Codex triage', 'issues': []},
              'Remaining Car Tests': ['All HU/USB/FM/audio route acceptance; Emulator does not confirm it',
                                     'Physical replug, ignition and D/E remain separate checks'],
              'Skills Execution Report': 'Runner executes tests; agent records skill use in the validation report'}
    report["fixture"] = {key: data[key] for key in ("avd_name", "serial", "adb_port", "emulator_port")}
    report["fixture"]["template_sha256"] = data.get("template_sha256")
    if args.mode == "android-ui":
        report.update(https="blocked" if args.https else "not tested: --https not selected",
                      catalog_fixtures="blocked", media_store_export="blocked")
    env = dict(os.environ, ANDROID_AVD_HOME=str(root / 'avd'), ANDROID_ADB_SERVER_PORT=str(data['adb_port']),
               ADB_SERVER_SOCKET=f"tcp:localhost:{data['adb_port']}")
    adb_prefix = [str(adb), '-P', str(data['adb_port']), '-s', data['serial']]
    def command(label, argv, timeout=60):
        try:
            result = subprocess.run(argv, cwd=ROOT, env=env, capture_output=True, text=True, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            partial = ''.join(value.decode(errors='replace') if isinstance(value, bytes) else value or ''
                              for value in (error.stdout, error.stderr))
            (run_dir / (label + '.log')).write_text(partial)
            report['steps'].append(dict(step=label, status='timeout', exit_code=None,
                                       timeout_seconds=error.timeout, evidence=label + '.log'))
            raise
        (run_dir / (label + '.log')).write_text(result.stdout + result.stderr)
        report['steps'].append(dict(step=label, exit_code=result.returncode, evidence=label + '.log'))
        require(result.returncode == 0, f'{label} exited {result.returncode}')
        return result.stdout
    def device(label, *argv, timeout=60):
        fixture(args)
        return command(label, [*adb_prefix, *argv], timeout)
    emulator_log = (run_dir / 'emulator.log').open('w')
    child = None
    failure = None
    def start_emulator():
        nonlocal child
        fixture(args)
        verify_adb(data, args.sdk)
        require(not find_process(qemu, data['avd_name']), 'an Emulator is already running for this AVD')
        child = subprocess.Popen([str(emulator), *emulator_arguments(data, root)], cwd=ROOT, env=env,
                                 stdout=emulator_log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            info = find_process(qemu, data['avd_name'])
            if info:
                data['emulator_process'] = info
                data['state'] = 'running'
                args.manifest.write_text(json.dumps(data, indent=2))
                try:
                    fixture(args)
                    break
                except (OSError, ValueError, KeyError):
                    pass
            require(child.poll() is None, 'Emulator exited before ownership verification')
            time.sleep(1)
        else:
            raise ValueError('Emulator ownership timeout; no forced termination allowed')

    def wait_boot(previous_count=None):
        deadline = time.monotonic() + 180
        guest_query = ('getprop sys.boot_completed; settings get global boot_count; '
                       'am get-current-user; am get-started-user-state 0')
        while time.monotonic() < deadline:
            fixture(args)
            try:
                result = subprocess.run([*adb_prefix, 'shell', guest_query], env=env,
                                        capture_output=True, text=True, timeout=10)
            except subprocess.TimeoutExpired:
                continue
            if result.returncode == 0:
                ready = result.stdout.strip().splitlines()
                if (len(ready) == 4 and ready[0] == '1' and ready[2:] == ['0', 'RUNNING_UNLOCKED']
                        and (previous_count is None or (ready[1].isdigit() and int(ready[1]) > previous_count))):
                    return
            time.sleep(2)
        raise ValueError('Android boot timeout')

    def stop_emulator(label):
        emulator_info = data.get('emulator_process')
        if emulator_info and Path('/proc', str(emulator_info['pid'])).exists():
            fixture(args)
            command(label, [*adb_prefix, 'emu', 'kill'])
            deadline = time.monotonic() + 45
            while Path('/proc', str(emulator_info['pid'])).exists() and time.monotonic() < deadline:
                if child:
                    child.poll()
                time.sleep(1)
            require(not Path('/proc', str(emulator_info['pid'])).exists(),
                    'Emulator did not exit; forced termination requires permission')
        if emulator_info:
            require(not find_process(qemu, data['avd_name']), 'fixture Emulator still exists')
            stale_lock = root / 'avd' / (data['avd_name'] + '.avd') / 'multiinstance.lock'
            if stale_lock.exists():
                require(not stale_lock.is_symlink() and stale_lock.is_file()
                        and stale_lock.stat().st_size == 0 and stale_lock.stat().st_nlink == 1,
                        'unexpected Emulator lock file')
                stale_lock.unlink()
            data.pop('emulator_process')
        data['state'] = 'stopped'
        args.manifest.write_text(json.dumps(data, indent=2))

    def reboot(label, previous_count):
        device(label, 'reboot')
        wait_boot(previous_count)

    def cold_boot(label, previous_count):
        stop_emulator(label + '-graceful-stop')
        require(child and child.returncode == 0, 'Emulator did not shut down normally')
        start_emulator()
        wait_boot(previous_count)

    try:
        command('adb-start', [str(adb), '-P', str(data['adb_port']), '--one-device', data['serial'], 'start-server'])
        adb_process = find_process(adb, adb_marker)
        require(adb_process, 'private ADB server missing')
        data['adb_process'] = adb_process
        verify_adb(data, args.sdk)
        start_emulator()
        wait_boot()
        require(device('hardware', 'shell', 'getprop', 'ro.hardware').strip() in ('ranchu', 'goldfish'), 'physical device rejected')
        require(device('abi', 'shell', 'getprop', 'ro.product.cpu.abi').strip() == 'x86_64', 'guest ABI mismatch')
        for label, apk in (('install', args.apk), ('install-test', args.test_apk)):
            text = device(label, 'install', '-r', '-t', str(apk), timeout=120)
            require('Success' in text, 'APK installation was not confirmed')
        package = passport_data['apk']['package']
        version = device('installed-version', 'shell', 'dumpsys', 'package', package)
        require(f"versionName={passport_data['apk']['version_name']}" in version, 'installed version mismatch')
        for permission in ('ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION'):
            device('grant-' + permission, 'shell', 'pm', 'grant', package, 'android.permission.' + permission)
        def launch(label, previous_log=None):
            activity = package + '/fm.heterodyne.app.MainActivity'
            start = ('am', 'start', '-W', '--user', '0', '-n', activity)
            if previous_log is not None:
                guard = f'if pidof {package} >/dev/null; then exit 1; fi;'
                start = (guard + ' ' + ' '.join(start),)
            result = device(label, 'shell', *start)
            previous_log = previous_log or ""
            require('Status: ok' in result, 'ordinary launch failed')
            deadline = time.monotonic() + 15
            attempt = 0
            while time.monotonic() < deadline:
                attempt += 1
                pid = device(f'{label}-pid-{attempt}', 'shell', f'pidof {package} || echo STOPPED').strip()
                if pid.isdigit():
                    activities = device(f'{label}-activities-{attempt}', 'shell', 'dumpsys', 'activity', 'activities')
                    live = any(activity in line and re.search(r'(mResumedActivity|topResumedActivity)\s*[:=]', line)
                               for line in activities.splitlines())
                    if live:
                        log = read_journal(device, f'{label}-journal-{attempt}', package, pending=True)
                        require(log.startswith(previous_log), 'existing journal was replaced')
                        if f'PREFLIGHT pid={pid} receiver=FakeReceiver' in log[len(previous_log):]:
                            return pid
                time.sleep(0.5)
            raise ValueError('ordinary launch did not reach foreground with a matching fixture marker')
        launch('ordinary-before')
        def phase(name, label=None, *parameters, test_class="Run4UiTest", timeout=360):
            extras = [arg for i in range(0, len(parameters), 2) for arg in ('-e', *parameters[i:i+2])]
            result = device(label or name, 'shell', 'am', 'instrument', '-w', '-r', *extras, '-e', 'class',
                f'fm.heterodyne.app.test.{test_class}#{name}',
                package + '.test/fm.heterodyne.app.test.EmulatorOnlyRunner', timeout=timeout)
            passed = 'OK (1 test)' in result and 'FAILURES!!!' not in result and 'INSTRUMENTATION_FAILED' not in result
            report['steps'][-1].update(status='passed' if passed else 'failed', expected='OK (1 test)',
                source='real HTTPS' if name == 'realHttps' else 'Emulator and fixtures')
            require(passed, f'{name} instrumentation failed')
            return result
        if args.mode == 'visual':
            visual_matrix(device, phase, run_dir, report)
            report['status'] = 'captured'
        elif args.mode == 'emulator-reboot':
            reboot_persistence(device, phase, launch, reboot, cold_boot,
                               lambda: dict(data['emulator_process']), package, run_dir, report)
            report['status'] = 'passed'
        elif args.mode == 'simulated-receiver':
            if args.audio:
                report['status'] = 'passed'
                if args.audio in ('all', 'continuous'):
                    audio = report['audio'] = continuous_audio(phase)
                    read_journal(device, 'd-observation-journal', package)
                    if not (audio['max_gap_budget_passed'] and audio['underrun_budget_passed']):
                        report['status'] = 'completed-with-findings'
                if args.audio in ('all', 'controls'):
                    listener = package + '/fm.heterodyne.app.preflight.PreflightMediaListener'
                    screen = phase("screenControlsAndSignals", test_class="Run4AudioTest")
                    try:
                        device('e-media-listener-allow', 'shell', 'cmd', 'notification', 'allow_listener', listener, '0')
                        media = phase("mediaSessionAndFocus", test_class="Run4AudioTest")
                    finally:
                        device('e-media-listener-disallow', 'shell', 'cmd', 'notification',
                               'disallow_listener', listener, '0')
                        allowed = device('e-media-listener-verify', 'shell', 'settings', '--user', '0',
                                         'get', 'secure', 'enabled_notification_listeners').strip().split(':')
                        require(listener not in allowed and package + '/.PreflightMediaListener' not in allowed,
                                'fixture notification listener grant was retained')
                    controls = re.findall(r'^INSTRUMENTATION_STATUS: stream=(E_.+)$', screen + media, re.M)
                    expected = ('E_SCREEN', *('E_SIGNAL',) * 4, 'E_DISABLED', 'E_MEDIA', *('E_FOCUS',) * 3, 'E_LOSS')
                    require(tuple(line.split()[0] for line in controls) == expected, 'missing D/E controls evidence')
                    report['audio_controls'] = controls
                    exported = exported_journals(phase('exportWhilePlaying'), count=1)
                    report['audio_exports'] = verify_journals(device, run_dir, 'e', exported)
            else:
                receiver_lifecycle(device, phase, launch, package, report)
                report['status'] = 'passed'
        else:
            prepared = phase('prepareData')
            previous = re.search(r'PREPARE_PID=(\d+)', prepared)
            require(previous, 'missing preparation process evidence')
            previous = previous[1]
            device('force-stop-for-persistence', 'shell', 'am', 'force-stop', package)
            installed = device('reinstall-preserving-data', 'install', '-r', '-t', str(args.apk), timeout=120)
            require('Success' in installed, 'reinstallation failed')
            current = launch('ordinary-after')
            require(previous != current, 'persistence must use a new process')
            report['ordinary_restart'] = dict(before=previous, after=current)
            phase('verifyPersistence')
            if args.https:
                report['https'] = 'failed; see realHttps.log'
                phase('realHttps')
                report['https'] = 'passed; actual HttpsCatalogReader, separate from fixtures'
            report['catalog_fixtures'] = 'failed'
            phase('catalogFixtures')
            report['catalog_fixtures'] = 'passed'
            device('force-stop-after-catalog', 'shell', 'am', 'force-stop', package)
            launch('ordinary-catalog-after')
            report['media_store_export'] = 'failed'
            exported = phase('verifyCatalogAndExport')
            report['exports'] = verify_journals(device, run_dir, None, exported_journals(exported))
            report['media_store_export'] = 'passed'
            report['status'] = 'passed'
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        failure = error
        report['status'] = 'failed'
        report['error'] = str(error)
        if data['state'] == 'running':
            try:
                fixture(args)
                captured = subprocess.run([*adb_prefix, 'exec-out', 'screencap', '-p'], env=env,
                                          capture_output=True, timeout=20)
                if captured.returncode == 0 and captured.stdout.startswith(b'\x89PNG'):
                    (run_dir / 'failure.png').write_bytes(captured.stdout)
                    report['failure_screenshot'] = 'failure.png'
            except (OSError, ValueError, KeyError, subprocess.SubprocessError):
                pass
    finally:
        # Graceful commands only. A missing/reused/foreign PID must never be killed.
        try:
            stop_emulator('emulator-graceful-stop')
            if child:
                report['emulator_exit_code'] = child.poll()
            if 'adb_process' in data:
                verify_adb(data, args.sdk)
                command('adb-stop', [str(adb), '-P', str(data['adb_port']), 'kill-server'])
                deadline = time.monotonic() + 10
                while remaining := find_process(adb, adb_marker):
                    require(remaining == data['adb_process'], 'private ADB identity changed after stop')
                    require(time.monotonic() < deadline, 'private ADB server did not exit')
                    time.sleep(0.1)
                require_free_ports((data['adb_port'],))
                report['adb_server_stopped'] = True
                data.pop('adb_process')
            if report['status'] in ('passed', 'captured', 'completed-with-findings'):
                require(report.get('emulator_exit_code') == 0, 'Emulator did not shut down normally')
            data['state'] = 'stopped'
            args.manifest.write_text(json.dumps(data, indent=2))
        except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
            report['cleanup_error'] = str(error)
            report['status'] = 'failed'
            if failure is None:
                failure = error
        emulator_log.close()
        report['Run 4 Preflight Report'] = dict(status=report['status'], steps=report.pop('steps'))
        (run_dir / 'report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False))
        print(json.dumps(dict(report=str(run_dir / 'report.json'), status=report['status'])))
    if failure:
        raise ValueError(str(failure))
    return 0


def safe_suite_status(statuses):
    if (len(statuses) != len(SUITE_PHASES) + 1 or statuses[2] != "captured"
            or any(status not in ("passed", "captured", "completed-with-findings") for status in statuses)):
        return "failed"
    if "completed-with-findings" in statuses:
        return "completed-with-findings"
    return "awaiting-visual-review"


def offline_validation(log=None):
    return subprocess.run(["bash", str(ROOT / "build-support/validate.sh")], cwd=ROOT,
                          stdout=log, stderr=subprocess.STDOUT if log else None).returncode


def run_safe_suite(args, passport_data):
    require(passport_data["purpose"] == "preflight", "preflight-all-safe requires preflight APK")
    require(args.name and args.template and not args.manifest,
            "preflight-all-safe requires --name and --template to prepare fresh fixtures")
    require(re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", args.name), "invalid suite name")
    require(args.test_apk and args.test_sha256, "preflight-all-safe requires test APK and its trusted SHA-256")
    verify_test_apk(args, passport_data)
    check_fixture_inputs(args)
    fixtures = ROOT / ".scratch/preflight-fixtures"
    require(all(not (fixtures / (args.name + "-" + stage)).exists() for stage, _, _ in SUITE_PHASES),
            "suite requires fresh fixture names")
    root = ROOT / ".scratch/preflight-suites" / args.name
    require(not any(path.is_symlink() for path in (root, *root.parents)), "suite directory symlink")
    root.mkdir(parents=True, exist_ok=False)
    suite_file = root / "report.json"
    offline = dict(stage="offline", mode="offline", status="blocked", evidence="offline.log")
    steps = [offline] + [dict(stage=stage, mode=mode, audio=audio, status="blocked")
                         for stage, mode, audio in SUITE_PHASES]
    report = {
        "schema": 1, "mode": "preflight-all-safe", "status": "running", "passport": passport_data,
        "source_commit": passport_data["source_commit"], "test_apk_sha256": args.test_sha256,
        "Run 4 Preflight Report": {"steps": steps, "visual_review": "pending",
                                   "https_requested": args.https},
        "Defect and Issue Report": {"status": "requires Codex triage", "issues": []},
        "Remaining Car Tests": {
            "status": "not tested", "blocked_modes": BLOCKED_MODES,
            "scope": ["HU display, USB/FM and real audio route", "steering, navigation and Bluetooth focus",
                      "physical replug, ignition and SSD transfer"],
        },
        "Skills Execution Report": {"status": "agent records actual skill use separately"},
    }

    def save():
        pending = suite_file.with_suffix(".tmp")
        pending.write_text(json.dumps(report, ensure_ascii=False, indent=2))
        pending.replace(suite_file)

    def child_result(manifest, step):
        paths = list((manifest.parent / "runs").glob("*/report.json"))
        if len(paths) != 1:
            return False
        path, = paths
        child = json.loads(path.read_text())
        require(child["mode"] == step["mode"] and child["source_commit"] == report["source_commit"]
                and child["apk"] == passport_data["apk"] and child["test_apk_sha256"] == args.test_sha256,
                "unexpected child report")
        step.update(evidence=str(path.relative_to(ROOT)), status=child["status"])
        for outcome in ("https", "catalog_fixtures", "media_store_export"):
            if outcome in child:
                step[outcome] = child[outcome]
        if "audio" in child:
            step["audio_metrics"] = child["audio"]
        if "visual_review" in child:
            report["Run 4 Preflight Report"]["visual_review"] = child["visual_review"]
            step["screenshots"] = len(child["screenshots"])
        return (child.get("emulator_exit_code") == 0 and child.get("adb_server_stopped") is True
                and "cleanup_error" not in child)

    save()
    print(json.dumps(dict(report=str(suite_file), status="running")), flush=True)
    offline["status"] = "running"
    save()
    try:
        with (root / "offline.log").open("w") as log:
            offline["exit_code"] = offline_validation(log)
    except (OSError, subprocess.SubprocessError) as error:
        offline.update(exit_code=2, error=str(error))
    offline["status"] = "passed" if offline["exit_code"] == 0 else "failed"
    if offline["status"] == "passed":
        for step in steps[1:]:
            step["status"] = "running"
            save()
            manifest = None
            try:
                require(sha256(args.apk) == passport_data["apk"]["sha256"], "APK changed during suite")
                child_args = argparse.Namespace(**(vars(args) | dict(
                    name=args.name + "-" + step["stage"], mode=step["mode"], audio=step["audio"],
                    https=args.https and step["mode"] == "android-ui")))
                manifest = Path(prepare(child_args)["manifest"])
                step["exit_code"] = run_emulator(child_args, passport_data)
            except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
                step.update(exit_code=2, error=str(error))
            cleaned = False
            if manifest:
                try:
                    cleaned = child_result(manifest, step)
                except (OSError, ValueError, KeyError, TypeError) as error:
                    step.setdefault("error", str(error))
            if not cleaned:
                step.update(status="failed", exit_code=2)
                step.setdefault("error", "child cleanup was not verified")
                break
            if step["exit_code"] != 0:
                step["status"] = "failed"
    report["status"] = safe_suite_status([step["status"] for step in steps])
    save()
    print(json.dumps(dict(report=str(suite_file), status=report["status"])), flush=True)
    return 2 if report["status"] == "failed" else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("passport", "check", "run", "fixture", "prepare"):
        command = commands.add_parser(name)
        if name == "run":
            command.add_argument("mode", choices=MODES)
            command.add_argument("--manifest", type=Path)
            command.add_argument("--test-apk", type=Path)
            command.add_argument("--test-sha256")
            command.add_argument("--audio", nargs="?", const="all", choices=("all", "continuous", "controls"),
                                 help="Run D/E audio checks in simulated-receiver mode")
            command.add_argument("--https", action="store_true", help="Run real HTTPS separately from catalog fixtures")
        command.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk")))
        if name in ("prepare", "run"):
            command.add_argument("--name", required=name == "prepare")
            command.add_argument("--template", type=Path, required=name == "prepare")
            command.add_argument("--adb-port", type=int, default=5038)
            command.add_argument("--emulator-port", type=int, default=5580)
        if name == "prepare":
            continue
        if name == "fixture":
            command.add_argument("--manifest", type=Path, required=True)
            continue
        command.add_argument("--apk", type=Path, required=True)
        command.add_argument("--build-tools", default="36.0.0")
        if name == "passport":
            command.add_argument("--source-commit", required=True)
            command.add_argument("--purpose", choices=("historical", "current", "preflight"), default="current")
        else:
            command.add_argument("--passport", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            print(json.dumps(prepare(args), indent=2))
            return 0
        if args.command == "fixture":
            print(json.dumps(fixture(args), indent=2))
            return 0
        if args.command == "run":
            require(args.mode not in BLOCKED_MODES, f"mode {args.mode} is not admitted; no actions executed")
            require(not args.audio or args.mode == "simulated-receiver", "--audio requires simulated-receiver mode")
            require(not args.https or args.mode in ("android-ui", "preflight-all-safe"),
                    "--https requires android-ui mode or preflight-all-safe")
            require(not (args.name or args.template) or args.mode == "preflight-all-safe",
                    "--name and --template require preflight-all-safe")
        data = passport(args) if args.command == "passport" else check(args)
        if args.command == "run":
            if args.mode == "preflight-all-safe":
                return run_safe_suite(args, data)
            if args.mode in EMULATOR_MODES:
                return run_emulator(args, data)
            require(data["purpose"] != "historical", "historical APK is inspection-only")
            return offline_validation()
        print(json.dumps(data, ensure_ascii=False, indent=2))
        return 0
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        print(f"preflight refused: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
