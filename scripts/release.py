#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Release planning, signed-APK verification and an installed-release smoke check."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "org.still.saver"
TAG = re.compile(r"^v(\d{1,3})\.(\d{1,2})\.(\d{1,2})$")
# Paths whose changes alter the published APK. Docs, CI and test-only edits do not warrant a release.
SHIPPED = ("app/src/main/", "app/build.gradle", "build.gradle", "settings.gradle", "gradle.properties",
           "gradle/", "LICENSE", "THIRD_PARTY_NOTICES.md", "licenses/")
LICENSE_ASSETS = ("assets/licenses/LICENSE", "assets/licenses/THIRD_PARTY_NOTICES.md")
CHANGELOGS = ROOT / "fastlane/metadata/android/en-US/changelogs"
# F-Droid and IzzyOnDroid cut a version's changelog at 500 bytes.
CHANGELOG_LIMIT = 500


def parse_version(text):
    match = re.fullmatch(r"(\d{1,3})\.(\d{1,2})\.(\d{1,2})", text)
    if not match:
        raise ValueError(f"Not a MAJOR.MINOR.PATCH version below x.100.100: {text}")
    return tuple(int(part) for part in match.groups())


def format_version(version):
    return ".".join(str(part) for part in version)


def version_code(version):
    return version[0] * 10000 + version[1] * 100 + version[2]


def bump(version, kind):
    major, minor, patch = version
    if kind == "major":
        return major + 1, 0, 0
    if kind == "minor":
        if minor == 99:
            raise ValueError("Minor version 99 is the last; release a major version")
        return major, minor + 1, 0
    if patch == 99:
        raise ValueError("Patch version 99 is the last; raise baseVersion to the next minor")
    return major, minor, patch + 1


def base_version(gradle_text):
    match = re.search(r"^def baseVersion = '([^']+)'", gradle_text, re.MULTILINE)
    if not match:
        raise ValueError("app/build.gradle has no baseVersion")
    return parse_version(match.group(1))


def plan(tags, base, changed_files, kind="auto"):
    """Returns the release decision. `changed_files` is every path changed since the latest tag."""
    released = sorted(tuple(int(g) for g in m.groups()) for m in (TAG.match(t) for t in tags) if m)
    if not released:
        version = base
        reason = "first release"
    else:
        latest = released[-1]
        shipped = sorted(f for f in changed_files if f.startswith(SHIPPED))
        if kind == "auto" and not shipped:
            return {"release": False, "reason": f"No app changes since v{format_version(latest)}"}
        if kind in ("auto", "patch") and base > latest:
            version, reason = base, "baseVersion was raised"
        else:
            version = bump(latest, "patch" if kind == "auto" else kind)
            reason = f"{'patch' if kind == 'auto' else kind} after v{format_version(latest)}"
        if version <= latest:
            raise ValueError(f"v{format_version(version)} is not newer than v{format_version(latest)}")
    return {"release": True, "version": format_version(version), "tag": "v" + format_version(version),
            "version_code": version_code(version), "reason": reason}


def git(*arguments):
    return subprocess.check_output(["git", *arguments], cwd=ROOT, text=True)


def released_tags():
    """(version, tag) for every release tag, oldest first."""
    tags = git("tag", "--list", "v*").split()
    return sorted((tuple(int(g) for g in m.groups()), t) for m, t in ((TAG.match(t), t) for t in tags) if m)


def command_plan(args):
    tags = git("tag", "--list", "v*").split()
    released = released_tags()
    changed = git("diff", "--name-only", released[-1][1], "HEAD").split() if released else []
    result = plan(tags, base_version((ROOT / "app/build.gradle").read_text()), changed, args.bump)
    print(json.dumps(result, indent=2))
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as out:
            for key, value in result.items():
                out.write(f"{key}={str(value).lower() if isinstance(value, bool) else value}\n")
    return 0


def find_tool(name):
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "")
    properties = ROOT / "local.properties"
    if not sdk.name and properties.exists():
        for line in properties.read_text().splitlines():
            if line.startswith("sdk.dir="):
                sdk = Path(line.split("=", 1)[1])
    if name == "adb":
        return str(sdk / "platform-tools/adb")
    versions = sorted((sdk / "build-tools").glob("*/" + name))
    if not versions:
        raise RuntimeError(f"{name} not found under {sdk}/build-tools")
    return str(versions[-1])


def certificate_digests(apksigner_output):
    return sorted(set(re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", apksigner_output)))


def badging(aapt_output):
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", aapt_output, re.MULTILINE)
    min_sdk = re.search(r"^(?:sdkVersion|minSdkVersion):'(\d+)'", aapt_output, re.MULTILINE)
    if not package:
        raise ValueError("APK badging has no package line")
    return {"package": package.group(1), "version_code": int(package.group(2)),
            "version_name": package.group(3), "min_sdk": int(min_sdk.group(1)) if min_sdk else None}


def verify(version, pinned, apksigner_output, aapt_output, entries):
    """Returns a list of problems; empty means the APK is the release we intend to publish."""
    problems = []
    digests = certificate_digests(apksigner_output)
    if "Verified using v2 scheme (APK Signature Scheme v2): true" not in apksigner_output:
        problems.append("APK is not signed with APK Signature Scheme v2")
    if digests != [pinned]:
        problems.append(f"Signed by {digests or 'no certificate'}; expected the pinned release key {pinned}")
    info = badging(aapt_output)
    expected = parse_version(version)
    if info["package"] != PACKAGE:
        problems.append(f"Package is {info['package']}, not {PACKAGE}")
    if info["version_name"] != version:
        problems.append(f"versionName is {info['version_name']}, not {version}")
    if info["version_code"] != version_code(expected):
        problems.append(f"versionCode is {info['version_code']}, not {version_code(expected)}")
    if info["min_sdk"] != 24:
        problems.append(f"minSdk is {info['min_sdk']}, not 24")
    for asset in LICENSE_ASSETS:
        if asset not in entries:
            problems.append(f"Missing GPL notice {asset}")
    if "application-debuggable" in aapt_output:
        problems.append("Release APK is debuggable")
    return problems


def command_verify(args):
    apksigner = subprocess.run([find_tool("apksigner"), "verify", "--verbose", "--print-certs", args.apk],
                               capture_output=True, text=True)
    if apksigner.returncode:
        print(apksigner.stdout + apksigner.stderr, file=sys.stderr)
        return 1
    aapt = subprocess.check_output([find_tool("aapt2"), "dump", "badging", args.apk], text=True)
    entries = set(subprocess.check_output(["unzip", "-Z1", args.apk], text=True).split())
    pinned = (ROOT / "release/signing-certificate.sha256").read_text().strip()
    problems = verify(args.version, pinned, apksigner.stdout, aapt, entries)
    for problem in problems:
        print("✗ " + problem, file=sys.stderr)
    if not problems:
        print(f"✓ {Path(args.apk).name}: {PACKAGE} {args.version}, signed by the pinned release key")
    return 1 if problems else 0


def find_view(hierarchy, attribute, value):
    """Centre of the first view whose text or content-desc equals value in a uiautomator dump, or None."""
    node = re.search(rf'<node [^>]*{attribute}="{re.escape(value)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
                     hierarchy)
    if not node:
        return None
    left, top, right, bottom = map(int, node.groups())
    return (left + right) // 2, (top + bottom) // 2


def command_smoke(args):
    """Installs the exact release APK on a disposable emulator and exercises launch and sharing."""
    adb = [find_tool("adb"), "-s", args.serial]

    def run(*arguments, check=True):
        result = subprocess.run([*adb, *arguments], capture_output=True, text=True, timeout=120)
        if check and result.returncode:
            raise RuntimeError(f"adb {' '.join(arguments)} failed: {result.stdout}{result.stderr}")
        return result.stdout

    if not args.serial.startswith("emulator-"):
        raise RuntimeError("The release smoke check uninstalls the app; use a disposable emulator")
    run("uninstall", PACKAGE, check=False)
    if "Success" not in run("install", args.apk):
        raise RuntimeError("Release APK did not install")
    run("logcat", "-c")
    installed = run("shell", "dumpsys", "package", PACKAGE)
    if f"versionName={args.version}" not in installed:
        raise RuntimeError(f"Installed versionName is not {args.version}")
    checks = [
        ("launches the library", ["shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity"]),
        ("handles an unsupported shared link", ["shell", "am", "start", "-W", "-a", "android.intent.action.SEND",
                                                 "-t", "text/plain", "--es", "android.intent.extra.TEXT",
                                                 "https://example.com/not-a-post", "-n", f"{PACKAGE}/.ShareActivity"]),
    ]
    for description, command in checks:
        output = run(*command)
        if "Status: ok" not in output and "Complete" not in output:
            raise RuntimeError(f"Release build failed to start when it {description}: {output}")
        time.sleep(3)
        if not run("shell", "pidof", PACKAGE, check=False).strip():
            raise RuntimeError(f"Release build exited after it {description}")
        print(f"✓ Release build {description}")

    def tap(attribute, value, expected_activity, description):
        run("shell", "uiautomator", "dump", "/sdcard/still-smoke.xml")
        hierarchy = run("exec-out", "cat", "/sdcard/still-smoke.xml")
        run("shell", "rm", "-f", "/sdcard/still-smoke.xml")
        point = find_view(hierarchy, attribute, value)
        if point is None:
            raise RuntimeError(f'Release build shows no "{value}" when it should {description}')
        run("shell", "input", "tap", str(point[0]), str(point[1]))
        time.sleep(3)
        if f"{PACKAGE}/{expected_activity}" not in run("shell", "dumpsys", "activity", "activities"):
            raise RuntimeError(f'Tapping "{value}" in the release build did not {description}')
        print(f"✓ Release build can {description}")

    # Settings is not exported, so reach it the way a person does.
    tap("text", "Open Still", ".MainActivity", "open the library from the share sheet")
    tap("content-desc", "Settings", ".SettingsActivity", "open settings")
    crashes = run("logcat", "-d", "-b", "crash")
    if PACKAGE in crashes:
        raise RuntimeError("Release build crashed:\n" + crashes)
    run("shell", "am", "force-stop", PACKAGE)
    return 0


def notes(version, apk_name, apk_sha256, certificate, repository):
    fingerprint = ":".join(certificate[i:i + 2] for i in range(0, len(certificate), 2)).upper()
    return f"""## Install

1. On your Android phone (Android 7.0 or newer), download **{apk_name}** from the assets below.
2. Open it, and allow your browser or file manager to install apps when Android asks.

Each release installs over the previous one and keeps your library. A build you made yourself, or one from before the first published release, is signed differently: uninstall it once, then install this one.

<details>
<summary>Verify this download</summary>

| | |
| --- | --- |
| APK SHA-256 | `{apk_sha256}` |
| Signing certificate SHA-256 | `{fingerprint}` |

```sh
shasum -a 256 {apk_name}
apksigner verify --print-certs {apk_name}
```

</details>

Source for this exact build: [`v{version}`](https://github.com/{repository}/tree/v{version}). Still is licensed under GPL-3.0-or-later; [THIRD_PARTY_NOTICES.md](https://github.com/{repository}/blob/v{version}/THIRD_PARTY_NOTICES.md) lists bundled components and where to get their source.

"""


def command_notes(args):
    import hashlib
    digest = hashlib.sha256(Path(args.apk).read_bytes()).hexdigest()
    pinned = (ROOT / "release/signing-certificate.sha256").read_text().strip()
    print(notes(args.version, Path(args.apk).name, digest, pinned, args.repository), end="")
    return 0


def changelog(commits, version, repository):
    """The store changelog from a release's (subject, changed paths) commits, oldest first; None for the first release.

    Lists the pull requests that changed the APK, like the "In the app" release notes, within CHANGELOG_LIMIT."""
    if commits is None:
        lines = ["- First published release"]
    else:
        lines, dependencies = [], False
        for subject, files in commits:
            if not any(f.startswith(SHIPPED) for f in files):
                continue
            if subject.startswith("Build(deps"):
                dependencies = True
                continue
            line = "- " + re.sub(r" \(#\d+\)$", "", subject)
            if line not in lines:
                lines.append(line)
        if dependencies:
            lines.append("- Updated dependencies")
        lines = lines or ["- No changes in the app"]
    text = "\n".join(lines) + "\n"
    if len(text.encode()) <= CHANGELOG_LIMIT:
        return text
    more = f"More: https://github.com/{repository}/releases/tag/v{version}"
    budget = CHANGELOG_LIMIT - len(more.encode()) - 2
    kept = []
    for line in lines:
        if len("\n".join(kept + [line]).encode()) > budget:
            break
        kept.append(line)
    if not kept:
        kept = [lines[0].encode()[:budget - 3].decode(errors="ignore") + "..."]
    return "\n".join(kept + [more]) + "\n"


def commits_between(start, end):
    """(subject, changed paths) for each commit after start up to end, oldest first."""
    log = git("log", "--reverse", "--format=%x00%s", "--name-only", f"{start}..{end}")
    commits = []
    for chunk in log.split("\0")[1:]:
        subject, *files = chunk.split("\n")
        commits.append((subject, [f for f in files if f]))
    return commits


def command_changelog(args):
    released = released_tags()
    if args.version:
        commits = commits_between(released[-1][1], "HEAD") if released else None
        print(changelog(commits, format_version(parse_version(args.version)), args.repository), end="")
        return 0
    for index, (version, tag) in enumerate(released):
        path = CHANGELOGS / f"{version_code(version)}.txt"
        if path.exists():
            continue
        commits = commits_between(released[index - 1][1], tag) if index else None
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(changelog(commits, format_version(version), args.repository))
        print(f"Wrote {path.relative_to(ROOT)}")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    plan_parser = commands.add_parser("plan", help="Decide whether and what to release")
    plan_parser.add_argument("--bump", choices=["auto", "patch", "minor", "major"], default="auto")
    verify_parser = commands.add_parser("verify", help="Check a signed release APK before publishing")
    verify_parser.add_argument("apk")
    verify_parser.add_argument("--version", required=True)
    smoke_parser = commands.add_parser("smoke", help="Install and exercise a release APK on an emulator")
    smoke_parser.add_argument("apk")
    smoke_parser.add_argument("--version", required=True)
    smoke_parser.add_argument("--serial", default="emulator-5554")
    notes_parser = commands.add_parser("notes", help="Print the release notes header for a verified APK")
    notes_parser.add_argument("apk")
    notes_parser.add_argument("--version", required=True)
    notes_parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", "zefan-xu/still"))
    changelog_parser = commands.add_parser(
        "changelog", help="Write missing store changelogs for released tags, or print one for an unreleased --version")
    changelog_parser.add_argument("--version", help="Print the changelog for this version from the latest tag to HEAD")
    changelog_parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", "zefan-xu/still"))
    args = parser.parse_args()
    commands_by_name = {"plan": command_plan, "verify": command_verify, "smoke": command_smoke, "notes": command_notes,
                        "changelog": command_changelog}
    return commands_by_name[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
