#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Keeps the bundled yt-dlp on the latest stable upstream release, verified against its published checksums."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
LATEST = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"
ASSET = "yt-dlp"
SUMS = "SHA2-256SUMS"
BINARY = "app/src/main/res/raw/ytdlp"
RUNTIME = "app/src/main/java/org/still/saver/ExtractorRuntime.java"
NOTICES = "THIRD_PARTY_NOTICES.md"
TAG = re.compile(r"\d{4}\.\d{2}\.\d{2}")
# ExtractorRuntime reads the installed version with this pattern and size limit, then compares it as a string.
VERSION_PY = re.compile(r"""(?m)^__version__\s*=\s*['"]([0-9]{4}\.[0-9]{2}\.[0-9]{2})['"]""")
VERSION_PY_LIMIT = 16384
# Every place the bundle is described. Each pattern must match exactly once.
REFERENCES = [
    (RUNTIME, re.compile(r'BUNDLED_VERSION = "(?P<version>[^"]*)"')),
    (RUNTIME, re.compile(r'BUNDLED_SHA256 =\s*"(?P<sha256>[^"]*)"')),
    (NOTICES, re.compile(r"\| Bundled yt-dlp (?P<version>[^ |]+) \|")),
    (NOTICES, re.compile(r"https://github\.com/yt-dlp/yt-dlp/releases/tag/(?P<version>[^ |]+) \|")),
    (NOTICES, re.compile(r"official (?P<version>\S+) executable in `" + re.escape(BINARY) +
                         r"` \((?P<size>[\d,]+) bytes; SHA-256 `(?P<sha256>[0-9a-f]*)`")),
]


def zipapp_version(data):
    """The version in the zipapp's yt_dlp/version.py, read the way ExtractorRuntime reads it."""
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            info = archive.getinfo("yt_dlp/version.py")
            if info.file_size > VERSION_PY_LIMIT:
                raise ValueError(f"yt_dlp/version.py is {info.file_size} bytes; ExtractorRuntime reads at most {VERSION_PY_LIMIT}")
            text = archive.read(info).decode("utf-8")
    except (zipfile.BadZipFile, KeyError) as error:
        raise ValueError(f"Not a yt-dlp zipapp: {error}") from None
    match = VERSION_PY.search(text)
    if not match:
        raise ValueError("yt_dlp/version.py has no YYYY.MM.DD __version__")
    return match.group(1)


def description(data):
    return {"version": zipapp_version(data), "sha256": hashlib.sha256(data).hexdigest(), "size": f"{len(data):,}"}


def only_match(text, path, pattern):
    matches = list(pattern.finditer(text))
    if len(matches) != 1:
        raise ValueError(f"{path}: expected one match for {pattern.pattern!r}, found {len(matches)}")
    return matches[0]


def rewrite(texts, values):
    """Returns the files with every reference set to `values`, or raises if a reference is missing or repeated."""
    texts = dict(texts)
    for path, pattern in REFERENCES:
        match = only_match(texts[path], path, pattern)
        text = texts[path]
        for name in sorted(pattern.groupindex, key=match.start, reverse=True):
            text = text[:match.start(name)] + values[name] + text[match.end(name):]
        texts[path] = text
    return texts


def problems(root):
    """Ways the references disagree with the committed binary; empty means they all describe it."""
    try:
        actual = description((root / BINARY).read_bytes())
    except (OSError, ValueError) as error:
        return [f"{BINARY}: {error}"]
    found = []
    for path, pattern in REFERENCES:
        try:
            match = only_match((root / path).read_text(), path, pattern)
        except ValueError as error:
            found.append(str(error))
            continue
        for name, value in match.groupdict().items():
            if value != actual[name]:
                found.append(f"{path} gives {name} {value}, but {BINARY} has {actual[name]}")
    return found


def stable_release(release):
    """Returns (version, zipapp asset, checksum asset) for a stable yt-dlp release, or raises ValueError."""
    version = release.get("tag_name", "")
    if release.get("draft") or release.get("prerelease"):
        raise ValueError(f"yt-dlp {version} is not a stable release")
    if not TAG.fullmatch(version):
        raise ValueError(f"yt-dlp tag {version!r} is not a YYYY.MM.DD version ExtractorRuntime can compare")
    assets = {asset["name"]: asset for asset in release.get("assets", [])}
    missing = [name for name in (ASSET, SUMS) if name not in assets]
    if missing:
        raise ValueError(f"yt-dlp {version} has no {' or '.join(missing)} asset")
    return version, assets[ASSET], assets[SUMS]


def published_sha256(sums, name):
    for line in sums.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].lstrip("*") == name and re.fullmatch(r"[0-9a-fA-F]{64}", parts[0]):
            return parts[0].lower()
    raise ValueError(f"{SUMS} has no checksum for {name}")


def update(root, fetch):
    """Replaces the bundled yt-dlp with the latest stable release; returns what changed. Writes nothing on error."""
    version, asset, sums_asset = stable_release(json.loads(fetch(LATEST)))
    texts = {path: (root / path).read_text() for path in (RUNTIME, NOTICES)}
    previous = only_match(texts[RUNTIME], RUNTIME, REFERENCES[0][1]).group("version")
    if version < previous:
        raise ValueError(f"The latest stable yt-dlp {version} is older than the bundled {previous}")
    if version == previous and not problems(root):
        return {"changed": False, "version": version, "previous": previous}
    expected = published_sha256(fetch(sums_asset["browser_download_url"]).decode("utf-8"), ASSET)
    data = fetch(asset["browser_download_url"])
    values = description(data)
    if values["sha256"] != expected:
        raise ValueError(f"Downloaded {ASSET} has SHA-256 {values['sha256']}, but {SUMS} lists {expected}")
    if asset.get("digest") and asset["digest"] != "sha256:" + expected:
        raise ValueError(f"GitHub lists {ASSET} as {asset['digest']}, but {SUMS} lists {expected}")
    if values["version"] != version:
        raise ValueError(f"The {version} zipapp's yt_dlp/version.py says {values['version']}")
    texts = rewrite(texts, values)
    (root / BINARY).write_bytes(data)
    for path, text in texts.items():
        (root / path).write_text(text)
    return {"changed": True, "version": version, "previous": previous, "sha256": values["sha256"],
            "size": len(data)}


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "still-ytdlp-update"})
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    # Only the API call is authenticated; asset downloads redirect to another host.
    if url.startswith("https://api.github.com/"):
        request.add_header("Accept", "application/vnd.github+json")
        if token:
            request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def command_update(args):
    result = update(Path(args.root), fetch)
    if not result["changed"]:
        message = f"yt-dlp {result['version']} is already bundled and is the latest stable release; nothing to update."
    elif result["previous"] == result["version"]:
        message = f"Re-downloaded yt-dlp {result['version']} because the bundled files disagreed."
    else:
        message = (f"Updated the bundled yt-dlp from {result['previous']} to {result['version']} "
                   f"({result['size']:,} bytes, SHA-256 {result['sha256']}, matching {SUMS}).")
    print(message)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as out:
            out.write(message + "\n")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as out:
            for key in ("changed", "version", "previous"):
                value = result[key]
                out.write(f"{key}={str(value).lower() if isinstance(value, bool) else value}\n")
    return 0


def command_check(args):
    found = problems(Path(args.root))
    for problem in found:
        print("✗ " + problem, file=sys.stderr)
    if not found:
        print(f"✓ {BINARY}, {RUNTIME} and {NOTICES} describe the same yt-dlp")
    return 1 if found else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name, help_text in [("update", "Bundle the latest stable yt-dlp release and rewrite its references"),
                            ("check", "Confirm the version, checksum and size references match the bundled binary")]:
        command = commands.add_parser(name, help=help_text)
        command.add_argument("--root", default=str(ROOT), help="Repository checkout to read and rewrite")
    args = parser.parse_args()
    try:
        return {"update": command_update, "check": command_check}[args.command](args)
    except (ValueError, OSError) as error:
        print(error, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
