#!/usr/bin/env python3.12
"""Run 4 preflight: inspect APKs, prepare private AVDs and run offline or Android UI checks."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
MODES = ("offline", "android-ui", "visual", "simulated-receiver", "emulator-reboot",
         "emulator-power-loss", "super-radio-hardware", "rtl-sdr-hardware", "preflight-all-safe")


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
        require(argv[1:] == ["-avd", name, "-datadir", str(avd), "-port", str(port),
            "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot", "-gpu", "swiftshader",
            "-camera-back", "none", "-camera-front", "none", "-no-metrics", "-memory", "2048"],
            "unapproved emulator process arguments")
        argv = owned_process(data["adb_process"], args.sdk / "platform-tools/adb", (data["adb_port"],))
        require(len(argv) == 9 and argv[1:6] == ["-L", f'tcp:{data["adb_port"]}', "fork-server", "server", "--reply-fd"]
                and argv[6].isdigit() and argv[7:] == ["--one-device", data["serial"]],
                "ADB process is not restricted to this serial")
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


def prepare(args):
    require(re.fullmatch(r"[a-zA-Z0-9_-]+", args.name), "invalid fixture name")
    require(1024 <= args.adb_port <= 65535 and args.adb_port != 5037, "private ADB port required")
    require(5554 <= args.emulator_port <= 5682 and args.emulator_port % 2 == 0
            and args.adb_port not in (args.emulator_port, args.emulator_port + 1), "invalid fixture ports")
    original = sha256(args.template)
    config = properties(args.template)
    require(config.get("abi.type") == "x86_64", "template ABI must be x86_64")
    image = args.sdk.resolve() / "system-images/android-30/default/x86_64"
    require((image / "system.img").is_file(), "installed API 30 x86_64 image required")
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
        "hw.sdCard": "no", "image.sysdir.1": str(image) + "/", "tag.id": "default", "target": "android-30",
        "disk.dataPartition.path": str(avd / "userdata-qemu.img"), "disk.dataPartition.size": "2G",
        "fastboot.forceColdBoot": "yes", "fastboot.forceFastBoot": "no",
        "firstboot.bootFromLocalSnapshot": "no", "firstboot.saveToLocalSnapshot": "no"})
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


def android_ui(args, passport_data):
    import fcntl
    import socket
    import time
    import uuid
    require(passport_data['purpose'] == 'preflight', 'android-ui requires preflight APK')
    require(args.manifest and args.test_apk and args.test_sha256, 'android-ui requires manifest, test APK and its trusted SHA-256')
    fixture(args)
    root = args.manifest.absolute().parent
    data = json.loads(args.manifest.read_text())
    require(data['state'] == 'stopped', 'runner requires a stopped fixture')
    require(sha256(args.test_apk) == args.test_sha256, 'test APK SHA-256 mismatch')
    tools = args.sdk / 'build-tools' / args.build_tools
    signature = output(str(tools / 'apksigner'), 'verify', '--print-certs', str(args.test_apk))
    require(re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$', signature, re.M)
            == [passport_data['apk']['certificate_sha256']], 'foreign test APK signature')
    xml = output(str(tools / 'aapt'), 'dump', 'xmltree', str(args.test_apk), 'AndroidManifest.xml')
    for identity in ('fm.heterodyne.app.preflight.test', 'fm.heterodyne.app.preflight', 'fm.heterodyne.app.test.EmulatorOnlyRunner'):
        require(f'="{identity}"' in xml, 'foreign test APK manifest')
    adb = args.sdk / 'platform-tools/adb'
    emulator = args.sdk / 'emulator/emulator'
    qemu = args.sdk / 'emulator/qemu/linux-x86_64/qemu-system-x86_64-headless'
    require(all(p.is_file() for p in (adb, emulator, qemu)), 'installed Android tools required')
    lock = (root / 'runner.lease').open('w')
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    # No ADB command is allowed until all private ports and fixture process names are unused.
    require(not find_process(qemu, data['avd_name']) and not find_process(adb, f"tcp:{data['adb_port']}"), 'fixture process already exists')
    for port in (data['adb_port'], data['emulator_port'], data['emulator_port'] + 1):
        with socket.socket(socket.AF_INET6, socket.SOCK_STREAM) as probe:
            probe.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
            probe.bind(('::', port))
    run_dir = root / 'runs' / uuid.uuid4().hex
    run_dir.mkdir(parents=True)
    report = {'schema': 1, 'mode': 'android-ui', 'source_commit': passport_data['source_commit'],
              'apk': passport_data['apk'], 'test_apk_sha256': args.test_sha256, 'steps': [], 'status': 'failed',
              'Defect and Issue Report': {'status': 'requires Codex triage', 'issues': []},
              'Remaining Car Tests': ['All HU/USB/FM/audio route acceptance; Emulator does not confirm it',
                                     'Visual, F, G and D/E remain separate blocks'],
              'Skills Execution Report': 'Runner executes tests; agent records skill use in the validation report'}
    env = dict(os.environ, ANDROID_AVD_HOME=str(root / 'avd'), ANDROID_ADB_SERVER_PORT=str(data['adb_port']),
               ADB_SERVER_SOCKET=f"tcp:localhost:{data['adb_port']}")
    adb_prefix = [str(adb), '-P', str(data['adb_port']), '-s', data['serial']]
    def command(label, argv, timeout=60):
        result = subprocess.run(argv, cwd=ROOT, env=env, capture_output=True, text=True, timeout=timeout)
        (run_dir / (label + '.log')).write_text(result.stdout + result.stderr)
        report['steps'].append(dict(step=label, exit_code=result.returncode, evidence=label + '.log'))
        require(result.returncode == 0, f'{label} exited {result.returncode}')
        return result.stdout
    def verify_adb():
        argv = owned_process(data['adb_process'], adb, (data['adb_port'],))
        require(len(argv) == 9 and argv[1:6] == ['-L', f"tcp:{data['adb_port']}", 'fork-server', 'server', '--reply-fd']
                and argv[6].isdigit() and argv[7:] == ['--one-device', data['serial']], 'foreign ADB server')
    def device(label, *argv, timeout=60):
        fixture(args)
        return command(label, [*adb_prefix, *argv], timeout)
    emulator_log = (run_dir / 'emulator.log').open('w')
    child = None
    failure = None
    try:
        command('adb-start', [str(adb), '-P', str(data['adb_port']), '--one-device', data['serial'], 'start-server'])
        data['adb_process'] = find_process(adb, f"tcp:{data['adb_port']}")
        require(data['adb_process'], 'private ADB server missing')
        verify_adb()
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
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            fixture(args)
            result = subprocess.run([*adb_prefix, 'shell', 'getprop', 'sys.boot_completed'], env=env,
                                    capture_output=True, text=True, timeout=10)
            if result.returncode == 0 and result.stdout.strip() == '1':
                break
            time.sleep(2)
        else:
            raise ValueError('Android boot timeout')
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
        def launch(label):
            result = device(label, 'shell', 'am', 'start', '-W', '-n', package + '/fm.heterodyne.app.MainActivity')
            require('Status: ok' in result, 'ordinary launch failed')
            pid = device(label + '-pid', 'shell', 'pidof', package).strip()
            require(pid.isdigit(), 'ordinary application process missing')
            return pid
        launch('ordinary-before')
        def phase(name):
            result = device(name, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                'fm.heterodyne.app.test.Run4UiTest#' + name,
                package + '.test/fm.heterodyne.app.test.EmulatorOnlyRunner', timeout=360)
            require('OK (1 test)' in result and 'FAILURES!!!' not in result and 'INSTRUMENTATION_FAILED' not in result,
                    f'{name} instrumentation failed')
        phase('prepareData')
        previous = device('before-stop-pid', 'shell', 'pidof', package).strip()
        device('force-stop-for-persistence', 'shell', 'am', 'force-stop', package)
        installed = device('reinstall-preserving-data', 'install', '-r', '-t', str(args.apk), timeout=120)
        require('Success' in installed, 'reinstallation failed')
        current = launch('ordinary-after')
        require(previous != current, 'persistence must use a new process')
        report['ordinary_restart'] = dict(before=previous, after=current)
        phase('verifyPersistence')
        if args.https:
            phase('realHttps')
            report['https'] = 'passed; actual HttpsCatalogReader, separate from fixtures'
        else:
            report['https'] = 'not tested: --https not selected'
        phase('catalogFixtures')
        device('force-stop-after-catalog', 'shell', 'am', 'force-stop', package)
        launch('ordinary-catalog-after')
        phase('verifyCatalogAndExport')
        report['status'] = 'passed'
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        failure = error
        report['error'] = str(error)
    finally:
        # Graceful commands only. A missing/reused/foreign PID must never be killed.
        try:
            if 'emulator_process' in data:
                fixture(args)
                command('emulator-graceful-stop', [*adb_prefix, 'emu', 'kill'])
                deadline = time.monotonic() + 45
                while Path('/proc', str(data['emulator_process']['pid'])).exists() and time.monotonic() < deadline:
                    if child:
                        child.poll()
                    time.sleep(1)
                require(not Path('/proc', str(data['emulator_process']['pid'])).exists(), 'Emulator did not exit; forced termination requires permission')
            if 'adb_process' in data:
                verify_adb()
                command('adb-stop', [str(adb), '-P', str(data['adb_port']), 'kill-server'])
            data['state'] = 'stopped'
            args.manifest.write_text(json.dumps(data, indent=2))
        except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
            report['cleanup_error'] = str(error)
            report['status'] = 'failed'
            failure = error
        emulator_log.close()
        report['Run 4 Preflight Report'] = dict(status=report['status'], steps=report.pop('steps'))
        (run_dir / 'report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False))
        lock.close()
        print(json.dumps(dict(report=str(run_dir / 'report.json'), status=report['status'])))
    if failure:
        raise ValueError(str(failure))
    return 0


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
            command.add_argument("--https", action="store_true", help="Run real HTTPS separately from catalog fixtures")
        command.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk")))
        if name == "prepare":
            command.add_argument("--name", required=True)
            command.add_argument("--template", type=Path, required=True)
            command.add_argument("--adb-port", type=int, default=5038)
            command.add_argument("--emulator-port", type=int, default=5580)
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
            require(args.mode in ("offline", "android-ui"), f"mode {args.mode} is not admitted; no actions executed")
        data = passport(args) if args.command == "passport" else check(args)
        if args.command == "run":
            if args.mode == "android-ui":
                return android_ui(args, data)
            require(data["purpose"] != "historical", "historical APK is inspection-only")
            return subprocess.run(["bash", str(ROOT / "build-support/validate.sh")], cwd=ROOT).returncode
        print(json.dumps(data, ensure_ascii=False, indent=2))
        return 0
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        print(f"preflight refused: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
