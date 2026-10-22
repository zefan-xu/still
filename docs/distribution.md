# Distribution

Still ships as one signed APK on each [GitHub release](https://github.com/zefan-xu/still/releases). This page covers the other ways to get it: Obtainium, IzzyOnDroid and F-Droid. It also covers the store listing they share and whether builds are reproducible. Findings are as of October 2026, for 1.2.1.

| Channel | Status |
| --- | --- |
| Obtainium | Works now. The README has the add-app link. |
| IzzyOnDroid | Not submitted. The APK is over their size limit, and their AI policy is a risk. The request text is below. |
| F-Droid | Feasible. The bundled Python libraries are not a blocker. Two source changes and a recipe are needed. |

## Store listing

`fastlane/metadata/android/en-US/` holds the listing that F-Droid and IzzyOnDroid read:

- `title.txt`, `short_description.txt` (80 characters at most) and `full_description.txt` (4,000 at most), written from the README.
- `images/icon.png`: the launcher icon at 512 px, rendered from the rounded-square cell of `tools/figures/out/icons.svg`.
- `images/phoneScreenshots/1.png` to `4.png`: the four light README screens with no phone frame. IzzyOnDroid asks for unframed screenshots. The current files are cropped from `docs/readme/screens-light.png` at 500 × 1083, because the raw screenshots from the last run were not kept. The next `python3 scripts/readme_art.py <run>/screenshots` rewrites them from the raw screens at 540 × 1170.
- `changelogs/<versionCode>.txt`: one file per release, where versionCode is major × 10000 + minor × 100 + patch.

`scripts/tests/test_release.py` checks the text limits and that every changelog fits in 500 bytes.

To redraw the icon after changing the figures, remove the background rectangle from `icons.svg`, set `viewBox="138 10 108 108" width="512" height="512"`, and screenshot it with headless Chrome using `--default-background-color=00000000 --window-size=512,512`.

### Changelogs

`python3 scripts/release.py changelog` writes the missing changelog file for each release tag. It lists the titles of the pull requests that changed the APK, the same ones as "In the app" in the release notes, and puts Dependabot updates on one "Updated dependencies" line. Longer lists are cut to 500 bytes and end with a link to the full release notes.

Both stores read changelogs from the tagged commit they build or take the APK from. The release workflow tags the `main` commit that CI verified, and `main` only takes pull requests that pass **CI passed**, with no bypass. So the workflow can't commit a version's changelog before it tags that version. Instead:

1. The release workflow runs `release.py changelog --version X.Y.Z` and attaches the result to the GitHub release as `changelog-<versionCode>.txt`.
2. After a release, run `python3 scripts/release.py changelog` in the next pull request. It writes the same text into `fastlane/`.
3. fdroidserver attaches every `<versionCode>.txt` in the newest tag to the matching version. So each changelog shows up in the stores once the next release is tagged. Until then, the newest version has no "What's new" text.

Showing the changelog on the day of release would need one of two changes. The workflow could push a release commit to `main`, which needs a ruleset bypass for GitHub Actions. Or it could tag a commit off `main` that adds the file. The APK records the commit it was built from, though, so that commit would also have to be the one that's built, or F-Droid's build would no longer match ours.

## Reproducible builds

Two builds of the same commit are identical, and they match the published release.

- **Same machine, twice.** Two clean builds of `v1.2.1` in a git worktree (macOS, arm64, Zulu JDK 21.0.8) gave byte-identical APKs, SHA-256 `e079874ca4641609e6ae0ae77ef300be5cc4078f001516879fd04efd2a214303`. Before each build, `app/build`, `build`, `core-tests/build` and `.gradle` were deleted, and Gradle ran with `--no-daemon --no-build-cache --no-configuration-cache`.
- **Against the release.** A fresh clone of `v1.2.1` at a different path, built the same way, matches the official `Still-1.2.1.apk`, which the release workflow built on Ubuntu with Temurin 21. `apksigcopier compare Still-1.2.1.apk --unsigned app-release-unsigned.apk` copies the release signature onto the local APK, and the signature then verifies. F-Droid and IzzyOnDroid use this same check for reproducible builds.
- **One caveat.** In a git worktree, the Android Gradle plugin can't read the commit, so `META-INF/version-control-info.textproto` says `NO_VALID_GIT_FOUND` instead of the commit hash. That file was the only difference between the worktree builds and the release. Build from a normal clone to compare. F-Droid's build server uses a normal clone.

To repeat the check, using `apksigner` from build-tools on your `PATH`:

```sh
git clone https://github.com/zefan-xu/still.git && cd still && git checkout v1.2.1
./gradlew --no-daemon --no-build-cache -PstillVersion=1.2.1 :app:assembleRelease
curl -LO https://github.com/zefan-xu/still/releases/download/v1.2.1/Still-1.2.1.apk
pip install apksigcopier
apksigcopier compare Still-1.2.1.apk --unsigned app/build/outputs/apk/release/app-release-unsigned.apk && echo match
```

## IzzyOnDroid

Nothing has been submitted. Requests go to the [repodata issue tracker](https://codeberg.org/IzzyOnDroid/repodata/issues) on Codeberg, using the "App Inclusion Request" template. The [inclusion policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/) was checked against 1.2.1:

| Requirement | 1.2.1 |
| --- | --- |
| FSF/OSI license, public source | GPL-3.0-or-later on GitHub |
| No trackers or ads | None |
| Fastlane metadata with descriptions, icon and screenshots | Added with this page |
| Release-signed, not debuggable or testOnly | `release.py verify` checks this on every release |
| APK attached to a tagged release, tag matches versionName | `v1.2.1` has `Still-1.2.1.apk` with versionName `1.2.1` |
| No cleartext traffic | `usesCleartextTraffic="false"` |
| Unique package name | `org.still.saver` isn't listed at IzzyOnDroid or F-Droid |
| **APK of 30 MB or less** | **No: 48.5 MB** |
| No extra executables without informed, opt-in consent | The yt-dlp update is opt-in. The wording may need a change (see below). |
| **No vibe-coded apps** | **Risk (see below)** |

Fix or decide on these before submitting:

1. **APK size.** IzzyOnDroid keeps about 30 MB per app, and that is also its limit for a single APK. The universal APK is 48.5 MB, because each of the three ABIs carries about 13–14.5 MB of Python runtime. One APK per ABI would come to about 21 MB for arm64-v8a. That needs ABI splits in `app/build.gradle`, and `release.py verify`, `smoke` and the release workflow would have to handle several APKs. Each APK would also need its own versionCode, or IzzyOnDroid would carry only the arm64-v8a one. The other option is to ask for a size exception in the request.
2. **AI policy.** IzzyOnDroid rejects apps it considers "vibe-coded". Its template asks how much AI tools were used, and it has rejected requests where the tools wrote the core logic. Commits in this repository carry `Co-authored-by: Isaac` trailers, so reviewers will see that AI was used. Only the maintainer can describe how much, and the answer must be accurate. If the honest answer is "Substantial" or "Dominant", expect a rejection, and weigh that before filing.
3. **The yt-dlp update.** The policy allows downloading executable code only with opt-in consent that tells users they are bypassing the repository's checks. The update only runs from Settings, after a confirmation sheet: "Still downloads the latest official yt-dlp release from GitHub. Update when a supported site stops working." Reviewers may ask for a sentence saying the update doesn't come from the app store and isn't checked by it.

Expect a `NonFreeNet` anti-feature, because Still depends on X, Instagram, TikTok and the other platforms, and on GitHub for extractor updates. Seal and YTDLnis carry the same one.

### Request text

Title: `[AppRequest] Still`

**Guidelines.** Tick "I am the developer", "complies with the App Inclusion Policy" (only once the size issue is settled), "not already listed" and "Fastlane folder is available".

**Link to the source code:** https://github.com/zefan-xu/still

**Link to app in another app store:** none

**License used:** GPL-3.0-or-later

**Categories:** Multimedia, Internet

**Summary:** Save photos and videos from supported posts. No ads or tracking.

**Description:**

> Still is a small app for keeping photos, videos and GIFs from supported posts on X, Instagram, TikTok, Facebook, Pinterest, Threads, Bluesky, YouTube and Telegram. Share a link from any app, choose what to keep, and the files land in Downloads/Still and in a library you can browse, share and turn into GIFs. It has no advertising, analytics, purchases or trackers. Telegram private channels use optional in-app Telegram Web sign-in; there is no Still server. The full description, screenshots and changelogs are in `fastlane/metadata/android/en-US`.

**Build instructions:**

```sh
# JDK 17 or newer, Android SDK platform 35 and build-tools 35.0.0, ANDROID_HOME set
git clone https://github.com/zefan-xu/still.git && cd still
git checkout v1.2.1
./gradlew :app:assembleRelease -PstillVersion=1.2.1
# Unsigned output: app/build/outputs/apk/release/app-release-unsigned.apk
```

> The version comes from the tag: `-PstillVersion` sets versionName, and versionCode is major × 10000 + minor × 100 + patch (1.2.1 is 10201). Builds are reproducible: a clean build of `v1.2.1` matches the published `Still-1.2.1.apk` with `apksigcopier compare`. The signing certificate SHA-256 is `140bdc209b5830c19cc9199d94fa05ee93853d470b8e1a04fc96d0a11e2a8bb0`, pinned in `release/signing-certificate.sha256` and checked by the release workflow.

**AI tools.** The maintainer fills in the assistance level, the tools and what they did, from their own knowledge. The commit trailers mentioned above are public.

**Further notices:**

> **Bundled native code.** The scanner will find native libraries and a Python zipapp. All of them are unmodified upstream builds, listed with licenses and sources in THIRD_PARTY_NOTICES.md:
>
> - `lib/<abi>/libpython.so` and `lib/<abi>/libpython.zip.so`: the Python 3 runtime from [youtubedl-android](https://github.com/yausername/youtubedl-android) 0.18.1 (GPL-3.0), taken from Maven Central as `io.github.junkfood02.youtubedl-android:library`. It is built from Termux's Python package as described in the library's BUILD_PYTHON.md. `libpython.zip.so` is a zip of the Python standard library, named `.so` so Android extracts it next to the interpreter.
> - `lib/<abi>/libqjs.so`: QuickJS (MIT) from the same library. yt-dlp uses it to run the JavaScript some sites require.
> - `res/raw/ytdlp`: the official [yt-dlp 2026.08.19](https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.19) zipapp (Unlicense), byte for byte. Its SHA-256 `1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6` was checked against the release's SHA2-256SUMS. The app checks it again before installing it, and it replaces the older yt-dlp inside the library.
>
> There is no FFmpeg or aria2. Video and audio are merged with Android's MediaMuxer.
>
> **Extractor updates.** Still never updates yt-dlp by itself. Settings has an "Update" action. It asks first ("Still downloads the latest official yt-dlp release from GitHub and checks its published checksum before installing it."), and only then downloads the official release from GitHub. The download replaces the installed file only when its SHA-256 matches the release's SHA2-256SUMS and the zipapp's version matches the tag; a mismatch leaves the previous extractor in place. Without an update, the bundled, checksummed copy is used.
>
> **Network.** For X, only the post ID goes to the open-source FxTwitter API, and users can point it at their own instance. Other platforms are contacted directly. Telegram private channels use Telegram Web inside the app, and the session never leaves the app sandbox. There are no trackers, analytics or crash reporting.
>
> **APK size.** (Fill in: the per-ABI change, or a request for a size exception.)

## F-Droid

**Verdict: feasible.** The prebuilt Python libraries are not what blocks inclusion. Two source changes and a recipe are needed, and someone has to open the merge request to fdroiddata.

### How similar apps are handled

[Seal](https://f-droid.org/packages/com.junkfood.seal/) and [YTDLnis](https://f-droid.org/packages/com.deniscerri.ytdl/) are both in F-Droid's main repository. Both use the same Maven Central artifact as Still, `io.github.junkfood02.youtubedl-android`, with its prebuilt `libpython.so` and `libpython.zip.so`. Their [fdroiddata recipes](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/com.junkfood.seal.yml) build with plain `gradle: yes`. They need no srclibs, no scanner exceptions, and no rebuild of Python from source. fdroidserver's source scanner checks the source tree, Gradle repositories and known non-free dependencies. It doesn't unpack AARs from Maven Central.

- Both carry `NonFreeNet: Rely on GitHub API to check and download yt-dlp updates`.
- Seal's recipe uses `sed` to turn off yt-dlp's automatic updates, which are on by default in Seal. Still never updates automatically, so it needs no such patch.
- Seal builds one APK per ABI with an `ABI_FILTERS` Gradle property.
- [YTDLnis's recipe](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/com.deniscerri.ytdl.yml) uses reproducible builds. It sets `binary:` to the developer's release APK and `AllowedAPKSigningKeys`, so F-Droid publishes the developer-signed APK once its own build matches. It reads new versions from an `output-metadata.json` attached to each release (`UpdateCheckMode: HTTP`).

### What F-Droid's scanner reports for Still

`scan_source` from fdroidserver 2.4.5, run on the 1.2.1 source tree, reports three errors. The scanner removes the Gradle wrapper files itself.

1. `binary at app/src/main/res/raw/ytdlp`: the yt-dlp zipapp. F-Droid doesn't accept prebuilt binaries in the source. Possible fixes:
   - Build it from source in the recipe. Add yt-dlp at the same tag as a srclib and build it with `make yt-dlp`, which needs only Python, make and zip and fixes file timestamps. `ExtractorRuntime.BUNDLED_SHA256` must match the result. If yt-dlp's release zipapp can't be rebuilt byte for byte, the recipe has to patch the checksum, and F-Droid would then sign the APK itself rather than reuse ours. Whether it can be rebuilt byte for byte hasn't been checked.
   - Or have the F-Droid build delete the file and fall back to the older yt-dlp inside the youtubedl-android AAR, as Seal does. `BUNDLED_VERSION` and `BUNDLED_SHA256` would then need to come from the build rather than the source.
2. and 3. `unknown maven repo 'https://maven.aliyun.com/repository/central'` in `settings.gradle`, once for plugins and once for dependencies. The mirror is only used outside CI. Remove it, or the recipe has to `sed` it out. Removing it changes a shipped file, so it would trigger a release.

### What the recipe needs

- `gradleprops: [stillVersion=<version>]`, because the version comes from the tag and not from `app/build.gradle`.
- A way for `checkupdates` to find the versionCode, since the source doesn't contain it. The YTDLnis approach would work: attach the Android Gradle plugin's `output-metadata.json` to each release and use `UpdateCheckMode: HTTP`.
- Optionally, reproducible builds: `binary: https://github.com/zefan-xu/still/releases/download/v%v/Still-%v.apk` and `AllowedAPKSigningKeys: 140bdc209b5830c19cc9199d94fa05ee93853d470b8e1a04fc96d0a11e2a8bb0`. Users could then switch between the GitHub, IzzyOnDroid and F-Droid builds without reinstalling. This only works if the yt-dlp zipapp issue is solved without changing the APK.
- `AntiFeatures: NonFreeNet`.
- The universal APK (48.5 MB) is acceptable. Per-ABI builds would be smaller.
