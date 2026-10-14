#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Repeatable local/device suites, with explicitly opt-in public-network probes."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "org.still.saver"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
NAV_GESTURAL = "com.android.internal.systemui.navbar.gestural"
NAV_GESTURAL = "com.android.internal.systemui.navbar.gestural"
OFFLINE = [
    "DeviceRegressionTest", "FunctionalUiTest", "MediaWorkflowTest",
    "DeviceSmokeTest#bundledPythonStartsAndRunsYtDlp",
]
LIVE = ["LivePlatformDownloadTest", "LivePostDownloadTest", "LiveMetaAvailabilityTest",
        "DeviceSmokeTest#extractsPublicBlueskyVideoOnDevice",
        "DeviceSmokeTest#resolvesAndSavesPublicTwitterVideoUsingAndroid"]
LIVE_REPORTS = ["x", "bluesky", "instagram", "tiktok", "facebook", "facebook-video",
                "facebook-embed", "facebook-legacy", "threads", "threads-audio", "pinterest",
                "facebook-unavailable", "threads-no-video", "threads-unavailable",
                "x-mixed", "x-gif", "instagram-carousel", "bluesky-photo", "pinterest-photo",
                "tiktok-photo", "threads-photos", "facebook-publisher"]
SCREENSHOTS = [f"{screen}-{theme}" for screen in ("library", "save", "actions", "settings")
               for theme in ("light", "dark")] + ["viewer"]


def parse_instrumentation(output, expected_count=None, selectors=()):
    """ADB exit status does not encode JUnit failure; require a complete successful run."""
    bundle, started, finished, errors, declared = {}, [], [], [], set()
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, sep, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if sep:
                bundle[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            try:
                code = int(line.split(": ", 1)[1])
                if "numtests" in bundle:
                    declared.add(int(bundle["numtests"]))
            except ValueError:
                errors.append("Invalid instrumentation status")
                bundle = {}
                continue
            name = (bundle.get("class", ""), bundle.get("test", ""))
            if code == 1:
                started.append(name)
            elif code <= 0:
                finished.append({"class": name[0], "test": name[1], "status": code,
                                 "stack": bundle.get("stack", "")})
                if code != 0:
                    errors.append(f"{name[0]}#{name[1]} failed or skipped (status {code})")
            bundle = {}
    terminal = re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$", output, re.MULTILINE)
    summary = re.findall(r"\bOK \((\d+) tests?\)", output)
    names = [(r["class"], r["test"]) for r in finished]
    if terminal != ["-1"]:
        errors.append("Missing or unsuccessful terminal instrumentation code")
    if len(summary) != 1 or int(summary[0]) != len(finished):
        errors.append("Missing or inconsistent JUnit success summary")
    if not started or len(started) != len(finished) or sorted(started) != sorted(names):
        errors.append("Empty or incomplete test execution")
    if len(set(names)) != len(names) or any(not c or not t for c, t in names):
        errors.append("Duplicate or unnamed test results")
    if declared != {len(finished)}:
        errors.append("Declared test count does not match results")
    if expected_count is not None and len(finished) != expected_count:
        errors.append(f"Expected {expected_count} tests; received {len(finished)}")
    for selector in selectors:
        cls, sep, method = selector.partition("#")
        if not any(c == cls and (not sep or t == method) for c, t in names):
            errors.append(f"Requested test did not execute: {selector}")
    if "INSTRUMENTATION_FAILED" in output or "shortMsg=" in output:
        errors.append("Instrumentation reported a fatal error")
    return {"passed": not errors, "count": len(finished), "tests": finished, "errors": errors}


def quality_metrics(root=None):
    """Line/branch coverage and mutation score from the latest reports, when present."""
    root = root or ROOT
    metrics = {}
    jacoco = root / "core-tests/build/reports/jacoco/test/jacocoTestReport.xml"
    if jacoco.exists():
        for counter in ET.parse(jacoco).getroot().findall("counter"):
            if counter.get("type") in ("LINE", "BRANCH"):
                covered, missed = int(counter.get("covered")), int(counter.get("missed"))
                metrics[counter.get("type").lower() + "_coverage"] = round(covered / max(1, covered + missed), 4)
    pitest = root / "core-tests/build/reports/pitest/mutations.xml"
    if pitest.exists():
        statuses = [m.get("status") for m in ET.parse(pitest).getroot().findall("mutation")]
        killed = sum(s in ("KILLED", "TIMED_OUT", "MEMORY_ERROR") for s in statuses)
        metrics["mutation_score"] = round(killed / max(1, len(statuses)), 4)
        metrics["mutants"] = len(statuses)
    return metrics


def find_sdk():
    candidates = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    properties = ROOT / "local.properties"
    if properties.exists():
        for line in properties.read_text().splitlines():
            if line.startswith("sdk.dir="):
                candidates.append(line.split("=", 1)[1].replace("\\:", ":").replace("\\ ", " "))
    candidates += [str(ROOT / ".tools/android-sdk"), str(Path.home() / "Library/Android/sdk")]
    for candidate in candidates:
        if candidate and (Path(candidate) / "platform-tools/adb").is_file():
            return Path(candidate)
    raise RuntimeError("Android SDK unavailable; set ANDROID_HOME to an installed SDK")


class Run:
    def __init__(self, args):
        self.args = args
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
        self.directory = ROOT / "artifacts/test-runs" / stamp
        self.directory.mkdir(parents=True)
        self.report = {"started_utc": stamp, "mode": args.mode, "suites": [], "passed": False}
        self.adb = None
        self.serial = None

    def command(self, name, command, timeout=1200):
        print(f"Running {name}…", flush=True)
        log = self.directory / f"{name}.log"
        env = os.environ.copy()
        if not env.get("JAVA_HOME"):
            mac_jdk = Path("/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home")
            if mac_jdk.is_dir():
                env["JAVA_HOME"] = str(mac_jdk)
        try:
            with log.open("w") as stream:
                process = subprocess.run(command, cwd=ROOT, env=env, stdout=stream,
                                         stderr=subprocess.STDOUT, timeout=timeout)
            code = process.returncode
        except subprocess.TimeoutExpired:
            with log.open("a") as stream:
                stream.write("\nRunner timeout: command did not complete.\n")
            code = 124
        output = log.read_text(errors="replace")
        return code, output, log.name

    def build_apks(self):
        code, _, log = self.command("build", [str(ROOT / "gradlew"), ":app:assembleDebug", ":app:assembleDebugAndroidTest"])
        self.report["suites"].append({"name": "build", "passed": code == 0, "exit_code": code, "log": log})
        if code:
            raise RuntimeError(f"APK build failed; see {self.directory / log}")

    def gradle(self):
        # check = JVM unit/property tests, Error Prone and coverage floors; pitest = mutation floor;
        # assembleRelease runs lintVital and proves the unsigned release variant still builds.
        code, _, log = self.command("local", [str(ROOT / "gradlew"), ":core-tests:check", ":core-tests:pitest",
                                 ":app:lintDebug", ":app:assembleDebug", ":app:assembleDebugAndroidTest",
                                 ":app:assembleRelease"], timeout=2400)
        results = list((ROOT / "core-tests/build/test-results/test").glob("TEST-*.xml"))
        count = failures = 0
        for path in results:
            suite = ET.parse(path).getroot()
            count += int(suite.get("tests", "0"))
            failures += int(suite.get("failures", "0")) + int(suite.get("errors", "0")) + int(suite.get("skipped", "0"))
        passed = code == 0 and count > 0 and failures == 0
        self.report["suites"].append({"name": "local", "passed": passed, "exit_code": code, "log": log,
                                     "jvm_tests": count, "jvm_failures_or_skips": failures, **quality_metrics()})
        if not passed:
            raise RuntimeError(f"Gradle checks failed; see {self.directory / log}")

    def python_tests(self):
        code, output, log = self.command("runner-tests", [sys.executable, "-m", "unittest", "discover",
                                                      "-s", "scripts/tests", "-v"])
        counts = re.findall(r"Ran (\d+) tests? in", output)
        passed = code == 0 and len(counts) == 1 and int(counts[0]) > 0
        self.report["suites"].append({"name": "runner-tests", "passed": passed,
                                     "exit_code": code, "log": log})
        if not passed:
            raise RuntimeError("Test runner regression checks failed")

    def device(self):
        self.adb = str(find_sdk() / "platform-tools/adb")
        listing = subprocess.check_output([self.adb, "devices"], text=True)
        devices = [line.split()[0] for line in listing.splitlines() if line.endswith("\tdevice")]
        if self.args.serial:
            if self.args.serial not in devices:
                raise RuntimeError(f"Selected device is unavailable: {self.args.serial}")
            self.serial = self.args.serial
        elif len(devices) == 1:
            self.serial = devices[0]
        else:
            raise RuntimeError("Use --serial to select exactly one connected device")
        if not self.serial.startswith("emulator-") and not self.args.allow_physical:
            raise RuntimeError("Use an isolated emulator, or --allow-physical --serial for a dedicated test device")
        metadata = {"serial": self.serial}
        for key, prop in [("api", "ro.build.version.sdk"), ("abi", "ro.product.cpu.abi"),
                          ("model", "ro.product.model"), ("android", "ro.build.version.release")]:
            metadata[key] = subprocess.check_output(self.adb_args("shell", "getprop", prop), text=True).strip()
        self.report["device"] = metadata
        if int(metadata["api"]) < 29:
            raise RuntimeError("The deterministic workflow suite requires API 29+; app minimum remains API 24")
        for name, path in [("install-app", "app/build/outputs/apk/debug/app-debug.apk"),
                           ("install-tests", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")]:
            code, output, _ = self.command(name, self.adb_args("install", "-r", str(ROOT / path)))
            if code or "Success" not in output:
                raise RuntimeError(f"{name} failed")

    def adb_args(self, *arguments):
        return [self.adb, "-s", self.serial, *arguments]

    def instrumentation(self, name, selectors, count, quality=None, arguments=()):
        selectors = [s if s.startswith(PACKAGE + ".") else PACKAGE + "." + s for s in selectors]
        command = self.adb_args("shell", "am", "instrument", "-w", "-r", "-e", "class", ",".join(selectors))
        for key, value in arguments:
            command += ["-e", key, value]
        if quality:
            for platform in LIVE_REPORTS:
                subprocess.run(self.adb_args("shell", "run-as", PACKAGE, "rm", "-f", f"files/live-{platform}.json"),
                               capture_output=True, timeout=30, check=True)
            command += ["-e", "livePlatforms", "true", "-e", "qualityChoice", quality]
        command.append(RUNNER)
        code, output, log = self.command(name, command, timeout=2400 if quality else 900)
        result = parse_instrumentation(output, count, selectors)
        result.update(name=name, log=log, exit_code=code)
        if code:
            result["passed"] = False
            result["errors"].append(f"adb exited {code}")
        self.report["suites"].append(result)
        if code == 124:
            # Killing host adb does not stop instrumentation on Android. Abort the remaining suites.
            for package in (PACKAGE, PACKAGE + ".test"):
                subprocess.run(self.adb_args("shell", "am", "force-stop", package),
                               capture_output=True, timeout=30)
            raise RuntimeError("Instrumentation timed out; stopped test processes. Dispose of the isolated device before retrying")
        if quality:
            self.collect_live(name)
        print(f"{name}: {result['count']} tests, {'PASS' if result['passed'] else 'FAIL'}", flush=True)
        # Keep running independent suites so the report shows the full failure scope.
        return result["passed"]

    def collect_live(self, name):
        destination = self.directory / name
        destination.mkdir()
        collected = []
        for platform in LIVE_REPORTS:
            filename = f"live-{platform}.json"
            result = subprocess.run(self.adb_args("exec-out", "run-as", PACKAGE, "cat", "files/" + filename),
                                    capture_output=True, timeout=30)
            if result.returncode == 0:
                (destination / filename).write_bytes(result.stdout)
                collected.append(filename)
        self.report["suites"][-1]["evidence"] = collected
        # Media copies are owned by these probes; keep JSON in app storage, remove large copies.
        for platform in LIVE_REPORTS:
            subprocess.run(self.adb_args("shell", "run-as", PACKAGE, "rm", "-f", f"files/live-{platform}.media"),
                           capture_output=True, timeout=30)

    def presentation_mode(self, restore=None):
        """Enters a 9:41 demo status bar with gesture navigation, or restores the given navigation overlay."""
        def shell(*arguments):
            return subprocess.run(self.adb_args("shell", *arguments), capture_output=True, text=True,
                                  timeout=30, check=True).stdout

        def demo(command, *extras):
            shell("am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", command, *extras)

        if restore is not None:
            demo("exit")
            if restore:
                shell("cmd", "overlay", "enable-exclusive", "--category", restore)
            return None
        overlays = shell("cmd", "overlay", "list", "android")
        current = next((line.split()[-1] for line in overlays.splitlines()
                        if line.strip().startswith("[x]") and ".navbar." in line), "")
        shell("cmd", "overlay", "enable-exclusive", "--category", NAV_GESTURAL)
        shell("settings", "put", "global", "sysui_demo_allowed", "1")
        # System UI registers its demo receiver only after it observes the setting change.
        time.sleep(2)
        demo("enter")
        demo("clock", "-e", "hhmm", "0941")
        demo("battery", "-e", "level", "100", "-e", "plugged", "false")
        demo("network", "-e", "wifi", "show", "-e", "level", "4", "-e", "fully", "true")
        demo("network", "-e", "mobile", "hide")
        demo("notifications", "-e", "visible", "false")
        time.sleep(2)
        return current if current != NAV_GESTURAL else ""

    def collect_screenshots(self):
        destination = self.directory / "screenshots"
        destination.mkdir()
        missing = []
        for screen in SCREENSHOTS:
            result = subprocess.run(self.adb_args("exec-out", "run-as", PACKAGE, "cat", f"files/readme/{screen}.png"),
                                    capture_output=True, timeout=60)
            if result.returncode or not result.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
                missing.append(screen)
                continue
            (destination / f"{screen}.png").write_bytes(result.stdout)
        subprocess.run(self.adb_args("shell", "run-as", PACKAGE, "rm", "-rf", "files/readme"),
                       capture_output=True, timeout=30)
        suite = self.report["suites"][-1]
        suite["screenshots"] = str(destination.relative_to(ROOT))
        if missing:
            suite["passed"] = False
            suite["errors"].append("Missing screenshots: " + ", ".join(missing))
            return False
        print(f"Screenshots: {destination}", flush=True)
        return True

    def execute(self):
        try:
            self.python_tests()
            if self.args.skip_build:
                pass
            elif self.args.mode in ("local", "all"):
                self.gradle()
            else:
                self.build_apks()
            if self.args.mode != "local":
                self.device()
                if self.args.mode in ("device", "all"):
                    self.instrumentation("device-offline", self.args.classes or OFFLINE,
                                         None if self.args.classes else 23)
                if self.args.mode in ("live", "all"):
                    qualities = ["highest", "smallest"] if self.args.quality == "both" else [self.args.quality]
                    for quality in qualities:
                        selectors = self.args.classes or (LIVE if quality == "highest" else ["LivePlatformDownloadTest"])
                        self.instrumentation("live-" + quality, selectors,
                                             None if self.args.classes else (24 if quality == "highest" else 11), quality)
                if self.args.mode == "screenshots":
                    restore = self.presentation_mode()
                    try:
                        if self.instrumentation("screenshots", ["ReadmeScreenshots"], 1,
                                                arguments=[("readmeScreenshots", "true")]):
                            self.collect_screenshots()
                    finally:
                        self.presentation_mode(restore)
            self.report["passed"] = bool(self.report["suites"]) and all(s["passed"] for s in self.report["suites"])
        except (RuntimeError, OSError, subprocess.SubprocessError, ET.ParseError, ValueError) as error:
            self.report["error"] = str(error)
            print(error, file=sys.stderr)
        finally:
            self.report["finished_utc"] = datetime.now(timezone.utc).isoformat()
            (self.directory / "report.json").write_text(json.dumps(self.report, indent=2) + "\n")
            print(f"{'PASS' if self.report['passed'] else 'FAIL'}: {self.args.mode}", flush=True)
            print(f"Report: {self.directory / 'report.json'}", flush=True)
        return 0 if self.report["passed"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["local", "device", "live", "all", "screenshots"])
    parser.add_argument("--serial", help="ADB serial; required when several devices are connected")
    parser.add_argument("--allow-physical", action="store_true", help="Use the explicitly selected dedicated physical test device")
    parser.add_argument("--quality", choices=["highest", "smallest", "both"], default="both")
    parser.add_argument("--skip-build", action="store_true", help="Reuse existing APKs for focused debugging")
    parser.add_argument("--class", dest="classes", action="append", help="Exact Android class or Class#method; repeatable")
    args = parser.parse_args()
    if args.classes and args.mode in ("local", "all"):
        parser.error("--class is for device or live focused runs")
    if args.allow_physical and not args.serial:
        parser.error("--allow-physical requires --serial for a dedicated test device")
    if args.skip_build and args.mode == "local":
        parser.error("local requires its build checks")
    return Run(args).execute()


if __name__ == "__main__":
    sys.exit(main())
