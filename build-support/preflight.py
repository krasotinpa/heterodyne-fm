#!/usr/bin/env python3.12
"""Run 4 preflight. Inspection is read-only; only offline currently runs a gate."""
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("passport", "check", "run", "fixture"):
        command = commands.add_parser(name)
        if name == "run":
            command.add_argument("mode", choices=MODES)
        command.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk")))
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
        if args.command == "fixture":
            print(json.dumps(fixture(args), indent=2))
            return 0
        if args.command == "run":
            require(args.mode == "offline", f"mode {args.mode} is not admitted; no actions executed")
        data = passport(args) if args.command == "passport" else check(args)
        if args.command == "run":
            require(data["purpose"] != "historical", "historical APK is inspection-only")
            return subprocess.run(["bash", str(ROOT / "build-support/validate.sh")], cwd=ROOT).returncode
        print(json.dumps(data, ensure_ascii=False, indent=2))
        return 0
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        print(f"preflight refused: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
