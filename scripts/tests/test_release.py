# SPDX-License-Identifier: GPL-3.0-or-later
import importlib.util
from pathlib import Path
import unittest

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
