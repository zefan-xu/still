# SPDX-License-Identifier: GPL-3.0-or-later
import importlib.util
from pathlib import Path
import unittest
from types import SimpleNamespace
from tempfile import TemporaryDirectory
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location("still_test", Path(__file__).parents[1] / "test.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
spec = importlib.util.spec_from_file_location("ci_summary", Path(__file__).parents[1] / "ci_summary.py")
ci_summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci_summary)

BOT_CHECK = {"passed": False, "category": "blocked",
             "error": "org.still.saver.core.UserError: YOUTUBE_BOT_CHECK",
             "cause": "com.yausername.youtubedl_android.YoutubeDLException: ERROR: [youtube] LeUcjqqhNxM: Sign in"
                      " to confirm you’re not a bot."}


def output(status=0, summary="OK (1 test)", terminal="-1"):
    return f"""INSTRUMENTATION_STATUS: class=org.still.saver.Example
INSTRUMENTATION_STATUS: test=works
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=org.still.saver.Example
INSTRUMENTATION_STATUS: test=works
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS_CODE: {status}
INSTRUMENTATION_RESULT: stream=\n{summary}
INSTRUMENTATION_CODE: {terminal}
"""


class InstrumentationResultsTest(unittest.TestCase):
    def test_success_requires_requested_test(self):
        self.assertTrue(runner.parse_instrumentation(output(), ["org.still.saver.Example#works"])["passed"])
        self.assertFalse(runner.parse_instrumentation(output(), ["org.still.saver.Missing"])["passed"])

    def test_junit_failure_despite_adb_success(self):
        self.assertFalse(runner.parse_instrumentation(output(-2, "FAILURES!!!"))["passed"])

    def test_ignored_and_assumption_are_failures(self):
        for status in (-3, -4):
            with self.subTest(status=status):
                self.assertFalse(runner.parse_instrumentation(output(status))["passed"])

    def test_empty_run(self):
        self.assertFalse(runner.parse_instrumentation("OK (0 tests)\nINSTRUMENTATION_CODE: -1\n")["passed"])

    def test_fatal_error(self):
        self.assertFalse(runner.parse_instrumentation(output(terminal="0") + "INSTRUMENTATION_RESULT: shortMsg=crash")["passed"])

    def test_incomplete_execution(self):
        self.assertFalse(runner.parse_instrumentation(output().split("INSTRUMENTATION_STATUS_CODE: 0")[0])["passed"])

    def test_declared_count_must_match_results(self):
        self.assertFalse(runner.parse_instrumentation(output().replace("numtests=1", "numtests=2"))["passed"])

    def test_duplicate_result(self):
        self.assertFalse(runner.parse_instrumentation(output() + output())["passed"])


class RunnerWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = patch.object(runner, "ROOT", Path(self.temp.name))
        self.root.start()
        self.addCleanup(self.root.stop)
        self.args = SimpleNamespace(mode="device", serial="emulator-5554", allow_physical=False,
                                    skip_build=True, classes=None, quality="both")
        self.run = runner.Run(self.args)
        self.run.adb = "adb"
        self.run.serial = "emulator-5554"

    def test_zero_python_tests_fail(self):
        self.run.command = Mock(return_value=(0, "Ran 0 tests in 0s\nOK", "runner.log"))
        with self.assertRaises(RuntimeError):
            self.run.python_tests()
        self.assertFalse(self.run.report["suites"][-1]["passed"])

    def test_zero_jvm_tests_fail(self):
        self.run.command = Mock(return_value=(0, "BUILD SUCCESSFUL", "local.log"))
        with self.assertRaises(RuntimeError):
            self.run.gradle()

    def test_offline_invocation_excludes_live_flag(self):
        self.run.command = Mock(return_value=(0, output(), "device.log"))
        self.assertTrue(self.run.instrumentation("offline", ["Example#works"]))
        command = self.run.command.call_args.args[1]
        self.assertNotIn("livePlatforms", command)
        self.assertIn("org.still.saver.Example#works", command)
        self.assertEqual(command[1:3], ["-s", "emulator-5554"])

    @patch.object(runner.subprocess, "run")
    def test_live_clears_prior_json_before_instrumentation(self, adb):
        events = []
        adb.side_effect = lambda *args, **kwargs: events.append("remove") or SimpleNamespace(returncode=0)
        self.run.command = Mock(side_effect=lambda *args, **kwargs: events.append("instrument") or (0, output(), "live.log"))
        self.run.collect_live = Mock()
        self.assertTrue(self.run.instrumentation("live", ["Example#works"], "smallest"))
        self.assertEqual(events, ["remove"] * len(runner.LIVE_REPORTS) + ["instrument"])
        self.assertTrue(all(call.args[0][-1].endswith(".json") for call in adb.call_args_list))
        self.assertIn("livePlatforms", self.run.command.call_args.args[1])
        self.run.collect_live.assert_called_once_with("live")

    @patch.object(runner.subprocess, "run")
    def test_timeout_stops_android_processes_and_raises(self, adb):
        self.run.command = Mock(return_value=(124, "", "timeout.log"))
        with self.assertRaisesRegex(RuntimeError, "timed out"):
            self.run.instrumentation("offline", ["Example"])
        self.assertEqual([c.args[0][-2:] for c in adb.call_args_list],
                         [["force-stop", "org.still.saver"], ["force-stop", "org.still.saver.test"]])

    def test_execution_aborts_later_suites_after_timeout(self):
        self.args.mode = "all"
        self.run.python_tests = Mock()
        self.run.device = Mock()
        self.run.instrumentation = Mock(side_effect=RuntimeError("Instrumentation timed out"))
        self.assertEqual(self.run.execute(), 1)
        self.run.instrumentation.assert_called_once()

    def test_instrumentation_arguments_are_passed_to_the_runner(self):
        self.run.command = Mock(return_value=(0, output(), "shots.log"))
        self.assertTrue(self.run.instrumentation("shots", ["Example#works"], arguments=[("readmeScreenshots", "true")]))
        command = self.run.command.call_args.args[1]
        index = command.index("readmeScreenshots")
        self.assertEqual(command[index - 1:index + 2], ["-e", "readmeScreenshots", "true"])
        self.assertEqual(command[-1], runner.RUNNER)

    @patch.object(runner.subprocess, "run")
    def test_screenshots_require_every_png(self, adb):
        png = b"\x89PNG\r\n\x1a\n" + b"data"
        adb.side_effect = lambda cmd, **kw: SimpleNamespace(
            returncode=0, stdout=b"" if cmd[-1].endswith("viewer.png") else png)
        self.run.report["suites"].append({"passed": True, "errors": []})
        self.assertFalse(self.run.collect_screenshots())
        self.assertFalse(self.run.report["suites"][-1]["passed"])
        self.assertIn("viewer", self.run.report["suites"][-1]["errors"][0])
        self.assertEqual(len(list((self.run.directory / "screenshots").glob("*.png"))), len(runner.SCREENSHOTS) - 1)
        self.assertEqual(adb.call_args_list[-1].args[0][-3:], ["rm", "-rf", "files/readme"])

    def test_quality_metrics_read_coverage_and_mutation_reports(self):
        root = Path(self.temp.name)
        jacoco = root / "core-tests/build/reports/jacoco/test/jacocoTestReport.xml"
        jacoco.parent.mkdir(parents=True)
        jacoco.write_text('<report><counter type="LINE" missed="1" covered="3"/>'
                          '<counter type="BRANCH" missed="1" covered="1"/></report>')
        pitest = root / "core-tests/build/reports/pitest/mutations.xml"
        pitest.parent.mkdir(parents=True)
        pitest.write_text('<mutations><mutation status="KILLED"/><mutation status="SURVIVED"/>'
                          '<mutation status="TIMED_OUT"/><mutation status="NO_COVERAGE"/></mutations>')
        self.assertEqual(runner.quality_metrics(root),
                         {"line_coverage": 0.75, "branch_coverage": 0.5, "mutation_score": 0.5, "mutants": 4})

    @patch.object(runner.subprocess, "check_output", return_value="List of devices attached\nphysical123\tdevice\n")
    @patch.object(runner, "find_sdk", return_value=Path("/test-sdk"))
    def test_physical_device_needs_explicit_opt_in(self, sdk, adb):
        self.args.serial = "physical123"
        with self.assertRaisesRegex(RuntimeError, "isolated emulator"):
            self.run.device()

    @patch.object(runner.subprocess, "check_output", return_value="List of devices attached\nemulator-5554\tdevice\nemulator-5556\tdevice\n")
    @patch.object(runner, "find_sdk", return_value=Path("/test-sdk"))
    def test_multiple_devices_require_serial(self, sdk, adb):
        self.args.serial = None
        with self.assertRaisesRegex(RuntimeError, "exactly one"):
            self.run.device()

    @patch.object(runner, "find_sdk", return_value=Path("/test-sdk"))
    def test_legacy_mode_accepts_only_api_24_to_28(self, sdk):
        cases = [("device", 28, "use legacy"), ("device", 29, None), ("legacy", 23, "API 24–28"),
                 ("legacy", 24, None), ("legacy", 28, None), ("legacy", 29, "use device")]
        for mode, api, error in cases:
            with self.subTest(mode=mode, api=api):
                self.args.mode = mode
                self.run.command = Mock(return_value=(0, "Success", "install.log"))
                with patch.object(runner.subprocess, "check_output", side_effect=lambda command, **kw:
                                  "List of devices attached\nemulator-5554\tdevice\n" if command[-1] == "devices"
                                  else str(api) if command[-1] == "ro.build.version.sdk" else "x"):
                    if error:
                        with self.assertRaisesRegex(RuntimeError, error):
                            self.run.device()
                        self.run.command.assert_not_called()
                    else:
                        self.run.device()
                        self.assertEqual(self.run.command.call_count, 2)

    @patch.object(runner.subprocess, "run")
    def test_legacy_revokes_storage_around_its_selection(self, adb):
        self.args.mode = "legacy"
        events = []
        adb.side_effect = lambda command, **kw: events.append(command[-3:]) or SimpleNamespace(returncode=0)
        self.run.python_tests = Mock()
        self.run.device = Mock()
        self.run.instrumentation = Mock(side_effect=lambda name, selectors: events.append((name, selectors)) or True)
        self.run.execute()
        self.assertNotIn("error", self.run.report)
        revoke = [["revoke", "org.still.saver", "android.permission." + p + "_EXTERNAL_STORAGE"]
                  for p in ("READ", "WRITE")]
        self.assertEqual(events, revoke + [("device-legacy", runner.LEGACY)] + revoke)

    @patch.object(runner.subprocess, "run")
    def test_legacy_restores_revoked_storage_after_a_timeout(self, adb):
        self.args.classes = ["LegacyStorageTest"]
        self.run.instrumentation = Mock(side_effect=RuntimeError("Instrumentation timed out"))
        with self.assertRaisesRegex(RuntimeError, "timed out"):
            self.run.legacy()
        self.run.instrumentation.assert_called_once_with("device-legacy", ["LegacyStorageTest"])
        self.assertEqual([c.args[0][-3] for c in adb.call_args_list], ["revoke"] * 4)

    def test_legacy_selection_stays_offline_and_covers_the_storage_suite(self):
        self.assertIn("LegacyStorageTest", runner.LEGACY)
        self.assertIn("OnboardingTest", runner.LEGACY)
        self.assertNotIn("LegacyStorageTest", runner.OFFLINE)
        for selector in runner.LEGACY:
            self.assertNotIn(selector, runner.LIVE)
            self.assertFalse(selector.startswith("Live"), selector)
            if selector.startswith("DeviceSmokeTest"):
                self.assertIn(selector, runner.OFFLINE)


class LiveOutcomeTest(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def suite(self, name, reports, statuses, errors=()):
        """reports maps platform to (test, attempts); statuses maps test to its JUnit status."""
        (self.directory / name).mkdir()
        for platform, (test, attempts) in reports.items():
            for attempt in attempts:
                attempt.setdefault("test", test)
            (self.directory / name / f"live-{platform}.json").write_text(runner.json.dumps(attempts))
        tests = [{"class": "org.still.saver." + t.split("#")[0], "test": t.split("#")[1], "status": s,
                  "stack": "" if s == 0 else f"java.lang.AssertionError: {t} failed\n\tat Example"}
                 for t, s in statuses.items()]
        failed = [f"org.still.saver.{t} failed or skipped (status {s})" for t, s in statuses.items() if s]
        return {"name": name, "tests": tests, "evidence": [f"live-{p}.json" for p in reports],
                "errors": failed + (["Missing or inconsistent JUnit success summary"] if failed else []) + list(errors)}

    def test_junit_result_decides_and_a_later_candidate_can_pass(self):
        suite = self.suite("live-highest", {
            "x": ("LivePlatformDownloadTest#x", [{"passed": True}]),
            "tiktok": ("LivePlatformDownloadTest#tiktok", [dict(BOT_CHECK), {"passed": True}]),
        }, {"LivePlatformDownloadTest#x": 0, "LivePlatformDownloadTest#tiktok": 0})
        self.assertEqual(runner.live_outcome(self.directory, [suite]),
                         {"passed": ["tiktok", "x"], "blocked": {}, "broken": {}})

    def test_bot_check_on_every_attempt_is_blocked(self):
        suite = self.suite("live-highest", {
            "youtube": ("LivePlatformDownloadTest#youtube", [dict(BOT_CHECK), dict(BOT_CHECK)]),
        }, {"LivePlatformDownloadTest#youtube": -2})
        outcome = runner.live_outcome(self.directory, [suite])
        self.assertEqual(list(outcome["blocked"]), ["youtube"])
        self.assertIn("not a bot", outcome["blocked"]["youtube"])
        self.assertIn("caused by com.yausername", outcome["blocked"]["youtube"])
        self.assertEqual(outcome["broken"], {})

    def test_one_broken_or_uncategorized_attempt_makes_the_platform_broken(self):
        suite = self.suite("live-highest", {
            "youtube": ("LivePlatformDownloadTest#youtube", [
                dict(BOT_CHECK), {"passed": False, "category": "broken", "error": "AssertionError: Expected video"}]),
            "threads-unavailable": ("LiveMetaAvailabilityTest#threadsUnavailable", [
                {"passed": False, "message": "Threads did not provide a public video embed for this post."}]),
        }, {"LivePlatformDownloadTest#youtube": -2, "LiveMetaAvailabilityTest#threadsUnavailable": -2})
        outcome = runner.live_outcome(self.directory, [suite])
        self.assertEqual(outcome["broken"], {
            "threads-unavailable": "Threads did not provide a public video embed for this post.",
            "youtube": "AssertionError: Expected video"})
        self.assertEqual(outcome["blocked"], {})

    def test_failure_without_json_and_runner_errors_are_broken(self):
        suite = self.suite("live-highest", {"x": ("LivePlatformDownloadTest#x", [{"passed": True}])},
                           {"LivePlatformDownloadTest#x": 0,
                            "DeviceSmokeTest#resolvesAndSavesPublicTwitterVideoUsingAndroid": -2},
                           errors=["adb exited 1"])
        outcome = runner.live_outcome(self.directory, [suite])
        self.assertEqual(outcome["broken"], {
            "DeviceSmokeTest#resolvesAndSavesPublicTwitterVideoUsingAndroid":
                "java.lang.AssertionError: DeviceSmokeTest#resolvesAndSavesPublicTwitterVideoUsingAndroid failed",
            "live-highest": "adb exited 1"})
        self.assertEqual(outcome["passed"], ["x"])

    def test_failed_test_whose_json_shows_no_failure_is_broken(self):
        suite = self.suite("live-highest", {"x-gif": ("LivePostDownloadTest#xGif", [{"passed": True}])},
                           {"LivePostDownloadTest#xGif": -2})
        self.assertEqual(runner.live_outcome(self.directory, [suite])["broken"],
                         {"x-gif": "java.lang.AssertionError: LivePostDownloadTest#xGif failed"})

    def test_worst_result_across_qualities_wins(self):
        highest = self.suite("live-highest", {
            "youtube": ("LivePlatformDownloadTest#youtube", [dict(BOT_CHECK)]),
            "x": ("LivePlatformDownloadTest#x", [{"passed": False, "category": "broken", "error": "gone"}]),
        }, {"LivePlatformDownloadTest#youtube": -2, "LivePlatformDownloadTest#x": -2})
        smallest = self.suite("live-smallest", {
            "youtube": ("LivePlatformDownloadTest#youtube", [{"passed": True}]),
            "x": ("LivePlatformDownloadTest#x", [dict(BOT_CHECK)]),
        }, {"LivePlatformDownloadTest#youtube": 0, "LivePlatformDownloadTest#x": -2})
        outcome = runner.live_outcome(self.directory, [highest, smallest])
        self.assertEqual((outcome["passed"], list(outcome["blocked"]), outcome["broken"]),
                         ([], ["youtube"], {"x": "gone"}))

    def test_unreadable_json_is_broken(self):
        suite = self.suite("live-highest", {}, {"LivePlatformDownloadTest#x": -2})
        (self.directory / "live-highest/live-x.json").write_text("[{")
        suite["evidence"] = ["live-x.json"]
        self.assertIn("x", runner.live_outcome(self.directory, [suite])["broken"])

    def test_suites_without_evidence_are_ignored(self):
        self.assertEqual(runner.live_outcome(self.directory, [{"name": "build", "passed": False}]),
                         {"passed": [], "blocked": {}, "broken": {}})

    @patch.object(runner.subprocess, "run")
    def test_collect_live_records_the_outcome_in_the_report(self, adb):
        attempts = runner.json.dumps([dict(BOT_CHECK, test="LivePlatformDownloadTest#youtube")]).encode()
        adb.side_effect = lambda cmd, **kw: SimpleNamespace(
            returncode=0 if cmd[-1] == "files/live-youtube.json" else 1, stdout=attempts)
        with patch.object(runner, "ROOT", self.directory):
            run = runner.Run(SimpleNamespace(mode="live"))
        run.adb, run.serial = "adb", "emulator-5554"
        run.report["suites"].append({"name": "live-highest", "errors": [], "tests": [
            {"class": "org.still.saver.LivePlatformDownloadTest", "test": "youtube", "status": -2, "stack": ""}]})
        run.collect_live("live-highest")
        self.assertEqual(run.report["suites"][-1]["evidence"], ["live-youtube.json"])
        self.assertEqual(list(run.report["live"]["blocked"]), ["youtube"])


class LiveVerdictTest(unittest.TestCase):
    def report(self, blocked=(), broken=(), passed=False, **extra):
        live = {"passed": ["x"], "blocked": {n: "not a bot" for n in blocked},
                "broken": {n: "gone" for n in broken}}
        suites = [{"name": "build", "passed": True}, {"name": "live-highest", "passed": passed, "evidence": []}]
        return {"mode": "live", "passed": passed, "suites": suites, "live": live, **extra}

    def test_verdicts(self):
        cases = {
            "passed": self.report(passed=True),
            "blocked": self.report(blocked=["youtube", "youtube-4k"]),
            "broken": self.report(blocked=["youtube"], broken=["instagram"]),
        }
        for expected, report in cases.items():
            with self.subTest(expected):
                self.assertEqual(ci_summary.verdict(report), expected)

    def test_unexplained_failures_are_broken(self):
        failing_build = self.report(blocked=["youtube"])
        failing_build["suites"][0]["passed"] = False
        no_live = self.report()
        del no_live["live"]
        for report in (self.report(blocked=["youtube"], error="Instrumentation timed out"), failing_build,
                       no_live, self.report()):
            with self.subTest(report=report):
                self.assertEqual(ci_summary.verdict(report), "broken")

    def test_live_outputs(self):
        self.assertEqual(ci_summary.live_outputs(self.report(blocked=["youtube", "youtube-4k"])),
                         "verdict=blocked\nblocked=youtube, youtube-4k\nbroken=\n")
        self.assertEqual(ci_summary.live_outputs(None), "verdict=broken\nblocked=\nbroken=\n")

    def test_summary_lists_blocked_and_broken_platforms(self):
        report = self.report(blocked=["youtube"], broken=["instagram"])
        report["live"]["broken"]["instagram"] = "Could not load | status 500\n"
        text = ci_summary.summary(report)
        self.assertIn("**Live verdict: broken.**", text)
        self.assertIn("| instagram | **broken** | Could not load &#124; status 500 |", text)
        self.assertIn("| youtube | blocked | not a bot |", text)
        self.assertIn("| x | pass | |", text)
        self.assertNotIn("Live verdict", ci_summary.summary({"mode": "local", "passed": True, "suites": []}))


if __name__ == "__main__":
    unittest.main()
