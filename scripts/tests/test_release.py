# SPDX-License-Identifier: GPL-3.0-or-later
import argparse
import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location("still_release", Path(__file__).parents[1] / "release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)

PINNED = "140bdc209b5830c19cc9199d94fa05ee93853d470b8e1a04fc96d0a11e2a8bb0"
APP = ["app/src/main/java/org/still/saver/MainActivity.java"]


def signer(digest=PINNED, v2="true"):
    return f"""Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): {v2}
Number of signers: 1
Signer #1 certificate DN: CN=Still, O=Still Android
Signer #1 certificate SHA-256 digest: {digest}
"""


def badging(name="1.2.3", code="10203", package="org.still.saver", extra=""):
    return (f"package: name='{package}' versionCode='{code}' versionName='{name}' platformBuildVersionName='15'\n"
            f"minSdkVersion:'24'\n{extra}")


ENTRIES = set(release.LICENSE_ASSETS) | {"classes.dex", "AndroidManifest.xml"}


class PlanTest(unittest.TestCase):
    def test_first_release_uses_the_base_version(self):
        result = release.plan([], (1, 1, 0), [])
        self.assertEqual((result["tag"], result["version_code"]), ("v1.1.0", 10100))

    def test_app_changes_bump_the_patch(self):
        result = release.plan(["v1.1.0", "v1.1.1"], (1, 1, 0), APP)
        self.assertEqual(result["version"], "1.1.2")

    def test_docs_ci_and_test_changes_do_not_release(self):
        changed = ["README.md", ".github/workflows/ci.yml", "core-tests/build.gradle",
                   "app/src/androidTest/java/org/still/saver/FunctionalUiTest.java", "scripts/test.py"]
        result = release.plan(["v1.1.4"], (1, 1, 0), changed)
        self.assertFalse(result["release"])
        self.assertIn("v1.1.4", result["reason"])

    def test_every_shipped_path_counts(self):
        for path in ["app/src/main/res/drawable/ic_still.xml", "app/build.gradle", "gradle/wrapper/gradle-wrapper.properties",
                     "licenses/jsoup-MIT.txt", "THIRD_PARTY_NOTICES.md", "settings.gradle"]:
            with self.subTest(path=path):
                self.assertTrue(release.plan(["v1.0.0"], (1, 0, 0), [path])["release"])

    def test_raised_base_version_wins_over_a_patch(self):
        result = release.plan(["v1.1.7"], (1, 2, 0), ["app/build.gradle"])
        self.assertEqual((result["version"], result["reason"]), ("1.2.0", "baseVersion was raised"))

    def test_manual_bumps_ignore_whether_the_app_changed(self):
        self.assertEqual(release.plan(["v1.1.7"], (1, 1, 0), [], "minor")["version"], "1.2.0")
        self.assertEqual(release.plan(["v1.1.7"], (1, 1, 0), [], "major")["version"], "2.0.0")
        self.assertEqual(release.plan(["v1.1.7"], (1, 1, 0), [], "patch")["version"], "1.1.8")

    def test_tags_sort_numerically_and_ignore_other_names(self):
        tags = ["v1.9.0", "v1.10.0", "v1.2.0", "nightly", "v2.0", "release-3.0.0", "v1.10.0-rc1"]
        self.assertEqual(release.plan(tags, (1, 0, 0), APP)["version"], "1.10.1")

    def test_versions_must_fit_the_version_code_scheme(self):
        with self.assertRaises(ValueError):
            release.plan(["v1.1.99"], (1, 1, 0), APP)
        with self.assertRaises(ValueError):
            release.parse_version("1.100.0")
        with self.assertRaises(ValueError):
            release.plan(["v1.99.3"], (1, 0, 0), [], "minor")

    def test_version_codes_increase_with_every_version(self):
        ordered = [(1, 1, 0), (1, 1, 1), (1, 1, 99), (1, 2, 0), (1, 99, 99), (2, 0, 0), (10, 0, 0)]
        codes = [release.version_code(v) for v in ordered]
        self.assertEqual(codes, sorted(set(codes)))

    def test_base_version_is_read_from_gradle(self):
        gradle = (Path(__file__).parents[2] / "app/build.gradle").read_text()
        self.assertEqual(len(release.base_version(gradle)), 3)
        with self.assertRaises(ValueError):
            release.base_version("versionName '1.0'")


class VerifyTest(unittest.TestCase):
    def test_pinned_signed_release_passes(self):
        self.assertEqual(release.verify("1.2.3", PINNED, signer(), badging(), ENTRIES), [])

    def test_other_signing_keys_are_rejected(self):
        problems = release.verify("1.2.3", PINNED, signer("0" * 64), badging(), ENTRIES)
        self.assertTrue(any("pinned release key" in p for p in problems))

    def test_v1_only_signatures_are_rejected(self):
        self.assertTrue(release.verify("1.2.3", PINNED, signer(v2="false"), badging(), ENTRIES))

    def test_version_package_and_debuggable_mismatches(self):
        self.assertTrue(release.verify("1.2.4", PINNED, signer(), badging(), ENTRIES))
        self.assertTrue(release.verify("1.2.3", PINNED, signer(), badging(code="5"), ENTRIES))
        self.assertTrue(release.verify("1.2.3", PINNED, signer(), badging(package="org.still.saver.debug"), ENTRIES))
        self.assertTrue(release.verify("1.2.3", PINNED, signer(), badging(extra="application-debuggable\n"), ENTRIES))

    def test_gpl_notices_must_be_packaged(self):
        problems = release.verify("1.2.3", PINNED, signer(), badging(), {"classes.dex"})
        self.assertEqual(len(problems), len(release.LICENSE_ASSETS))


class NotesTest(unittest.TestCase):
    def test_notes_name_the_apk_checksum_certificate_and_source(self):
        text = release.notes("1.2.3", "Still-1.2.3.apk", "ab" * 32, PINNED, "zefan-xu/still")
        self.assertIn("**Still-1.2.3.apk**", text)
        self.assertIn("ab" * 32, text)
        self.assertIn("14:0B:DC:20", text)
        self.assertIn("https://github.com/zefan-xu/still/tree/v1.2.3", text)
        self.assertIn("THIRD_PARTY_NOTICES.md", text)


class ChangelogTest(unittest.TestCase):
    def test_lists_app_pull_requests_oldest_first_without_numbers(self):
        commits = [("Add a share sheet (#3)", APP), ("Fix the viewer (#4)", ["app/src/main/res/layout/viewer.xml"])]
        self.assertEqual(release.changelog(commits, "1.2.3", "zefan-xu/still"),
                         "- Add a share sheet\n- Fix the viewer\n")

    def test_docs_ci_and_test_changes_are_left_out(self):
        commits = [("Update the README (#5)", ["README.md"]), ("Run CI on Ubuntu 26 (#6)", [".github/workflows/ci.yml"]),
                   ("Fix the viewer (#7)", APP)]
        self.assertEqual(release.changelog(commits, "1.2.3", "zefan-xu/still"), "- Fix the viewer\n")

    def test_dependency_updates_share_one_line_at_the_end(self):
        commits = [("Build(deps): bump org.json:json from 1 to 2 (#8)", ["app/build.gradle"]),
                   ("Fix the viewer (#9)", APP),
                   ("Build(deps): bump gradle-wrapper from 8 to 9 (#10)", ["gradle/wrapper/gradle-wrapper.properties"]),
                   ("Build(deps): bump actions/checkout from 6 to 7 (#11)", [".github/workflows/ci.yml"])]
        self.assertEqual(release.changelog(commits, "1.2.3", "zefan-xu/still"),
                         "- Fix the viewer\n- Updated dependencies\n")

    def test_first_and_empty_releases_still_say_something(self):
        self.assertEqual(release.changelog(None, "1.1.0", "zefan-xu/still"), "- First published release\n")
        self.assertEqual(release.changelog([("Update the README", ["README.md"])], "1.1.1", "zefan-xu/still"),
                         "- No changes in the app\n")

    def test_long_changelogs_fit_the_store_limit_and_link_the_full_notes(self):
        commits = [(f"Change number {n} to the app, described at some length (#{n})", APP) for n in range(30)]
        text = release.changelog(commits, "1.2.3", "zefan-xu/still")
        self.assertLessEqual(len(text.encode()), release.CHANGELOG_LIMIT)
        self.assertTrue(text.startswith("- Change number 0 "))
        self.assertTrue(text.endswith("More: https://github.com/zefan-xu/still/releases/tag/v1.2.3\n"))
        title = release.changelog([("x" * 900, APP)], "1.2.3", "zefan-xu/still")
        self.assertLessEqual(len(title.encode()), release.CHANGELOG_LIMIT)
        self.assertIn("...\nMore:", title)


class ChangelogCommandTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.enterContext(mock.patch.object(release, "ROOT", self.root))
        self.changelogs = self.root / "fastlane/metadata/android/en-US/changelogs"
        self.enterContext(mock.patch.object(release, "CHANGELOGS", self.changelogs))
        self.git("init", "-q")

    def git(self, *arguments):
        subprocess.run(["git", "-c", "user.name=Still", "-c", "user.email=still@example.com",
                        "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false", "-c", "core.hooksPath=/dev/null",
                        *arguments],
                       cwd=self.root, check=True, capture_output=True)

    def commit(self, subject, path):
        (self.root / path).parent.mkdir(parents=True, exist_ok=True)
        with open(self.root / path, "a") as file:
            file.write(subject + "\n")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", subject)

    def run_command(self, version=None):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            release.command_changelog(argparse.Namespace(version=version, repository="zefan-xu/still"))
        return output.getvalue()

    def test_writes_each_missing_release_by_version_code_and_keeps_existing_ones(self):
        self.commit("Still 1.1.0", APP[0])
        self.git("tag", "v1.1.0")
        self.commit("Add a share sheet (#3)", APP[0])
        self.commit("Update the README (#4)", "README.md")
        self.git("tag", "v1.1.1")
        self.changelogs.mkdir(parents=True)
        (self.changelogs / "10100.txt").write_text("Written by hand\n")
        self.run_command()
        self.assertEqual((self.changelogs / "10100.txt").read_text(), "Written by hand\n")
        self.assertEqual((self.changelogs / "10101.txt").read_text(), "- Add a share sheet\n")

    def test_prints_the_unreleased_version_from_the_latest_tag(self):
        self.commit("Still 1.1.0", APP[0])
        self.git("tag", "v1.1.0")
        self.commit("Fix the viewer (#5)", APP[0])
        self.assertEqual(self.run_command("1.1.1"), "- Fix the viewer\n")
        self.assertFalse(self.changelogs.exists())


class StoreListingTest(unittest.TestCase):
    STORE = Path(__file__).parents[2] / "fastlane/metadata/android/en-US"

    def test_texts_fit_the_store_limits(self):
        for name, limit in [("title.txt", 30), ("short_description.txt", 80), ("full_description.txt", 4000)]:
            with self.subTest(name=name):
                text = (self.STORE / name).read_text().strip()
                self.assertTrue(0 < len(text) <= limit, f"{name} has {len(text)} characters; the limit is {limit}")

    def test_every_release_changelog_fits(self):
        changelogs = list((self.STORE / "changelogs").glob("*.txt"))
        self.assertTrue(changelogs)
        for path in changelogs:
            with self.subTest(path=path.name):
                self.assertTrue(path.stem.isdigit())
                self.assertLessEqual(len(path.read_bytes()), release.CHANGELOG_LIMIT)


class SmokeTest(unittest.TestCase):
    HIERARCHY = (
        '<hierarchy rotation="0"><node index="0" text="" resource-id="" class="android.widget.ImageView" '
        'package="org.still.saver" content-desc="Search" clickable="true" bounds="[836,107][962,233]" />'
        '<node index="1" text="" resource-id="" class="android.widget.ImageView" package="org.still.saver" '
        'content-desc="Settings" checkable="false" clickable="true" bounds="[962,107][1080,233]" />'
        '<node index="2" text="Open Still" resource-id="" class="android.widget.TextView" package="org.still.saver" '
        'content-desc="" clickable="true" bounds="[48,2050][520,2160]" /></hierarchy>'
    )

    def test_views_are_found_by_description_or_text(self):
        self.assertEqual(release.find_view(self.HIERARCHY, "content-desc", "Settings"), (1021, 170))
        self.assertEqual(release.find_view(self.HIERARCHY, "text", "Open Still"), (284, 2105))

    def test_missing_views_are_none(self):
        self.assertIsNone(release.find_view(self.HIERARCHY, "content-desc", "Preferences"))
        self.assertIsNone(release.find_view(self.HIERARCHY, "text", "Settings"))


if __name__ == "__main__":
    unittest.main()
