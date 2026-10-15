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
    "DeviceRegressionTest", "FunctionalUiTest", "MediaWorkflowTest", "OnboardingTest",
    "StreamMergerTest", "DeviceVideoSupportTest", "DeviceSmokeTest#bundledPythonStartsAndRunsYtDlp",
]
LEGACY = [
    "LegacyStorageTest", "OnboardingTest", "StreamMergerTest", "DeviceVideoSupportTest",
    "DeviceSmokeTest#bundledPythonStartsAndRunsYtDlp",
]
LEGACY_APIS = range(24, 29)
STORAGE = ["android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE"]
LIVE = ["LivePlatformDownloadTest", "LivePostDownloadTest", "LiveMetaAvailabilityTest",
        "DeviceSmokeTest#extractsPublicBlueskyVideoOnDevice",
        "DeviceSmokeTest#resolvesAndSavesPublicTwitterVideoUsingAndroid"]
LIVE_REPORTS = ["x", "bluesky", "instagram", "tiktok", "facebook", "facebook-video",
                "facebook-embed", "facebook-legacy", "threads", "threads-audio", "youtube", "youtube-4k",
                "youtube-vp9", "pinterest",
                "facebook-unavailable", "threads-no-video", "threads-unavailable",
                "x-mixed", "x-gif", "instagram-carousel", "bluesky-photo", "pinterest-photo",
                "tiktok-photo", "threads-photos", "facebook-publisher"]
SCREENSHOTS = [f"{screen}-{theme}" for screen in ("library", "save", "actions", "settings")
               for theme in ("light", "dark")] + ["viewer"]


def parse_instrumentation(output, selectors=()):
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


def is_live_report(data):
    """True when `data` is a JSON array of probe attempts.

    `adb exec-out run-as … cat` exits 0 on API 35 even when the file is missing, and the bytes are
    then `cat: files/live-….json: No such file or directory`. Smallest-quality runs never write the
    photo, carousel and availability reports, so that text must not become evidence."""
    try:
        parsed = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return False
    return isinstance(parsed, list)


def finished_status(report):
    """Process status for a finished run.

    Live (and `all`) exits 0 when the only failures are platforms that refused this network.
    Anything broken, a runner error, or a failure outside the live probes still exits 1."""
    if report.get("passed"):
        return 0
    if report.get("error") or report.get("mode") not in ("live", "all"):
        return 1
    live = report.get("live") or {}
    if live.get("broken") or not live.get("blocked"):
        return 1
    if any(not suite.get("passed") for suite in report.get("suites", []) if "evidence" not in suite):
        return 1
    return 0


def live_outcome(directory, suites):
    """Sorts live probes into passed, blocked (the platform refused this network) and broken.

    JUnit decides pass or fail; a failed probe is blocked only when every failed attempt in its
    JSON says so. Failures with no JSON, and runner errors, are broken."""
    rank = {"passed": 0, "blocked": 1, "broken": 2}
    worst = {}

    def first_line(result):
        return (result["stack"].strip().splitlines() or [""])[0]

    def record(name, status, error=""):
        if name not in worst or rank[status] > rank[worst[name][0]]:
            worst[name] = (status, error)

    for suite in suites:
        if "evidence" not in suite:
            continue
        results = {r["class"].rsplit(".", 1)[-1] + "#" + r["test"]: r for r in suite["tests"]}
        explained = set()
        for filename in suite["evidence"]:
            platform = filename[len("live-"):-len(".json")]
            try:
                attempts = json.loads((directory / suite["name"] / filename).read_text())
            except ValueError:
                record(platform, "broken", f"Unreadable {filename}")
                continue
            tests = {a.get("test") for a in attempts}
            explained |= tests
            if any(results.get(t, {}).get("status") == 0 for t in tests):
                record(platform, "passed")
                continue
            failures = [a for a in attempts if not a.get("passed")]
            if failures:
                last = failures[-1]
                error = " caused by ".join(filter(None, (last.get("error") or last.get("message"), last.get("cause"))))
            else:
                error = next((first_line(results[t]) for t in tests if t in results), "")
            blocked = failures and all(a.get("category") == "blocked" for a in failures)
            record(platform, "blocked" if blocked else "broken", error)
        failed = {f"{r['class']}#{r['test']} failed or skipped (status {r['status']})" for r in suite["tests"]
                  if r["status"] != 0}
        for name, result in results.items():
            if result["status"] != 0 and name not in explained:
                record(name, "broken", first_line(result))
        # A failing JUnit run always lacks the "OK (n tests)" line; anything else is a runner problem.
        for error in suite["errors"]:
            if error not in failed and error != "Missing or inconsistent JUnit success summary":
                record(suite["name"], "broken", error)
    return {"passed": sorted(n for n, (s, _) in worst.items() if s == "passed"),
            **{status: {n: e for n, (s, e) in sorted(worst.items()) if s == status}
               for status in ("blocked", "broken")}}


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
        # testDebugUnitTest runs the app module's JVM tests; assembleRelease runs lintVital.
        code, _, log = self.command("local", [str(ROOT / "gradlew"), ":core-tests:check", ":core-tests:pitest",
                                 ":app:testDebugUnitTest", ":app:lintDebug", ":app:assembleDebug",
                                 ":app:assembleDebugAndroidTest", ":app:assembleRelease"], timeout=2400)
        results = []
        missing_results = []
        for directory in (ROOT / "core-tests/build/test-results/test",
                          ROOT / "app/build/test-results/testDebugUnitTest"):
            found = list(directory.glob("TEST-*.xml"))
            if not found:
                missing_results.append(str(directory.relative_to(ROOT)))
            results.extend(found)
        count = failures = 0
        for path in results:
            suite = ET.parse(path).getroot()
            count += int(suite.get("tests", "0"))
            failures += int(suite.get("failures", "0")) + int(suite.get("errors", "0")) + int(suite.get("skipped", "0"))
        passed = code == 0 and count > 0 and failures == 0 and not missing_results
        self.report["suites"].append({"name": "local", "passed": passed, "exit_code": code, "log": log,
                                     "jvm_tests": count, "jvm_failures_or_skips": failures,
                                     **({"missing_results": missing_results} if missing_results else {}),
                                     **quality_metrics()})
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
        api = int(metadata["api"])
        if self.args.mode == "legacy":
            if api not in LEGACY_APIS:
                raise RuntimeError("legacy runs on API 24–28; use device for API 29+")
        elif api < 29:
            raise RuntimeError("The deterministic workflow suite requires API 29+; use legacy for API 24–28")
        for name, path in [("install-app", "app/build/outputs/apk/debug/app-debug.apk"),
                           ("install-tests", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")]:
            code, output, _ = self.command(name, self.adb_args("install", "-r", str(ROOT / path)))
            if code or "Success" not in output:
                raise RuntimeError(f"{name} failed")

    def adb_args(self, *arguments):
        return [self.adb, "-s", self.serial, *arguments]

    def instrumentation(self, name, selectors, quality=None, arguments=()):
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
        result = parse_instrumentation(output, selectors)
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

    def legacy(self):
        """Android prompts only while the whole storage group is revoked; revoking also stops the app."""
        def revoke():
            for permission in STORAGE:
                subprocess.run(self.adb_args("shell", "pm", "revoke", PACKAGE, permission),
                               capture_output=True, timeout=30, check=True)

        revoke()
        try:
            return self.instrumentation("device-legacy", self.args.classes or LEGACY)
        finally:
            revoke()

    def collect_live(self, name):
        destination = self.directory / name
        destination.mkdir()
        collected = []
        for platform in LIVE_REPORTS:
            filename = f"live-{platform}.json"
            result = subprocess.run(self.adb_args("exec-out", "run-as", PACKAGE, "cat", "files/" + filename),
                                    capture_output=True, timeout=30)
            # run-as often exits 0 and prints cat's error, so the bytes have to be a JSON array.
            if result.returncode == 0 and is_live_report(result.stdout):
                (destination / filename).write_bytes(result.stdout)
                collected.append(filename)
        self.report["suites"][-1]["evidence"] = collected
        self.report["live"] = live_outcome(self.directory, self.report["suites"])
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
                    self.instrumentation("device-offline", self.args.classes or OFFLINE)
                if self.args.mode == "legacy":
                    self.legacy()
                if self.args.mode in ("live", "all"):
                    qualities = ["highest", "smallest"] if self.args.quality == "both" else [self.args.quality]
                    for quality in qualities:
                        selectors = self.args.classes or (LIVE if quality == "highest" else ["LivePlatformDownloadTest"])
                        self.instrumentation("live-" + quality, selectors, quality)
                if self.args.mode == "screenshots":
                    restore = self.presentation_mode()
                    try:
                        if self.instrumentation("screenshots", ["ReadmeScreenshots"],
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
            status = finished_status(self.report)
            if status == 0 and not self.report["passed"]:
                label = "BLOCKED"
            else:
                label = "PASS" if status == 0 else "FAIL"
            print(f"{label}: {self.args.mode}", flush=True)
            print(f"Report: {self.directory / 'report.json'}", flush=True)
        return status


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["local", "device", "legacy", "live", "all", "screenshots"])
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
