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


if __name__ == "__main__":
    unittest.main()
