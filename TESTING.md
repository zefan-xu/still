# Testing Still

Prerequisites: Python 3, JDK 21, an Android SDK with platform 35 and build-tools 35.0.0, and an unlocked API 29+ emulator (API 24–28 for `legacy`). Set `ANDROID_HOME` or `sdk.dir` in `local.properties`; `.tools/android-sdk` is also recognized. Gradle downloads dependencies on its first run. Disable emulator animations for reliable UI checks.

```sh
python3 scripts/test.py local
python3 scripts/test.py device --serial emulator-5554
python3 scripts/test.py live --serial emulator-5554 --quality both
# Android 7–9, on an API 24–28 emulator:
python3 scripts/test.py legacy --serial emulator-5556
# All stages:
python3 scripts/test.py all --serial emulator-5554
```

`local` runs the runner, release-script and yt-dlp-script tests, JVM unit and property tests with coverage floors, mutation testing, Error Prone, Android lint, and debug, test and release APK builds. `device` adds deterministic Android workflows. `legacy` runs the Android 7–9 selection below on an API 24–28 emulator; `device` refuses those versions and `legacy` refuses newer ones. `live` adds public-network tests. `screenshots` renders the README screens (see below). Suites run sequentially because they share preferences. The runner selects the sole connected emulator or requires `--serial` when several are connected. Physical devices require `--allow-physical --serial <id>` and should be dedicated to testing. The app itself still supports API 24+.

After building, focused reruns are available:

```sh
python3 scripts/test.py device --serial emulator-5554 --skip-build --class MediaWorkflowTest
python3 scripts/test.py live --serial emulator-5554 --skip-build --quality smallest --class 'LivePlatformDownloadTest#threads'
```

## Local gates

Each gate fails the build; none only reports.

| Gate | What it checks | Where it is set |
| --- | --- | --- |
| JVM tests | 165 JUnit tests over the parsers, resolvers, Telegram link and bridge routing, network policy, `ByteRanges`, `Mp4Repair`, `LibraryModel`, `Format` and the GIF encoder | `core-tests/src/test`, `app/src/test` |
| Property tests | [jqwik](https://jqwik.net) generates share text, links, hosts, MP4 box trees, random bytes and library histories. Invariants include: every supported post link survives surrounding share text and comes out canonical; preview URLs never reach IP addresses or local names; MP4 repair touches only sample-table flags, is idempotent and never throws; every library item appears exactly once, in order, within its day | `*PropertyTest.java` |
| Coverage | JaCoCo line ≥ 87%, branch ≥ 72% for the code `core-tests` compiles | `core-tests/build.gradle` |
| Mutation | PIT must kill ≥ 73% of mutants; surviving mutants are listed in `core-tests/build/reports/pitest/index.html` | `core-tests/build.gradle` |
| Error Prone | Bug-pattern analysis of core code, warnings as errors | `core-tests/build.gradle` |
| Android lint | Warnings as errors with no baseline; deliberate exceptions are suppressed where they occur with `tools:ignore` or `@SuppressLint` and a reason | `app/build.gradle` |

Raise a floor when a change lifts the measured value well above it, so the floors keep tracking what the suite actually achieves. jqwik records failing seeds in `core-tests/build/jqwik-database` and replays them first on the next run.

## Main functionality coverage

| Function | Automated coverage | Boundary |
| --- | --- | --- |
| First-run tutorial | Opens on a first launcher start with an empty Library, Skip keeps it closed on the next launch, Settings replays it through every step | Swipe, rotation and the Paste a link handoff are checked by hand |
| Link input | Empty input stays usable; invalid clipboard and incoming Android share recover with no download | Valid parsing uses JVM fixtures and live checks; incoming share uses an already open activity |
| Quality and saving | Native picker selection, Save, Save all and duplicate suppression | Offline UI injects a resolved post and holds its queue; the picker opens through its native click handler; live tests resolve and transfer |
| Settings | Quality, auto-save preference, Wi-Fi, concurrency, theme, endpoint/folder validation, recreation persistence | Real Wi-Fi transitions and automatic-save transfer timing remain gaps |
| Download state | Progress/completion persistence, stale callbacks, interrupted cookie-output recovery; actual Retry/Cancel buttons | Retry uses a synthetic failure and held queue; actual failed-transfer retry and FIFO/concurrency remain gaps |
| Library | Case-insensitive search, Keep, remove history retains bytes, delete affects only target, Open routing | Uses real MediaStore files |
| Sharing | Actual Share button checks chooser URI, MIME, ClipData and read flag | External receiving app and cross-UID reads remain untested |
| Player | Real video playing/pause/resume; delayed animation decode while paused; pinch/double-tap zoom, pan while zoomed, and horizontal swiping between saved images and videos; Library Open routing | Delayed video preparation while paused remains a gap |
| GIF export | Actual dialog/export, Android decode, dimensions, looping, 64 frames/8 seconds, MIME/publication and original-video retention | Synthetic silent video; device codecs may differ |
| GIF cancel/error | Gated source opening, actual Library Cancel action, invalid video, no new output/library/cache residue | Notification permission is not explicitly denied in this suite |
| Native extractor | Bundled Python/yt-dlp executes `--version` on Android | Makes no platform request |
| Android 7–9 storage | Save and Retry ask for storage access; the prompt stays up through a rotation, Deny explains why nothing was saved and Allow finishes the waiting Retry. Once granted, Save asks no more. A GIF export writes its file straight into `Downloads/<folder>` and registers it with MediaStore; the GIF and its source video get thumbnails; the viewer hides the system bars with the pre-Android 11 flags; the bundled serif fonts load instead of the system fallback | Runs in CI on API 28 only; API 24–27 run it only when `legacy` is pointed at such an emulator. Transfers through DownloadManager and Still's own downloader are not started |
| Video and audio merging | `StreamMerger` combines synthetic fragmented H.264 and AAC tracks, shaped like YouTube's, into one MP4, AV1 and AAC into one MP4, and synthetic VP9 and Opus WebM tracks into one WebM. Each output track must hold every sample of its input, and Android must decode a frame and report audio. AAC whose priming starts before zero keeps all its samples; a merge stops when cancelled and leaves no output; mismatched or non-media inputs, and H.264 with AAC into WebM, fail with an error | Ranged transfers are checked live and range arithmetic has JVM tests; retrying a failed range in place has no automated test. Merging needs API 29+ (API 24's MP4 writer rejects B-frames) and AV1 API 34+; neither is offered below that |
| Playable qualities | `DeviceVideoSupport` reports H.264 at 320×240 as decodable and 100000×100000 or 100000 fps as not, and claims merges only for the codec, container and API pairs above. It logs what the device decodes at 1080p, 1440p and 2160p | JVM tests use fake devices for the per-height choice of H.264, then AV1, then VP9, then hidden |
| Public platforms | Videos on every platform; X photo+video and GIF posts; Instagram, Threads and TikTok photo carousels; Bluesky photo; Pinterest image pin; Facebook reel/video/embed/legacy route; Telegram public posts and multi-video albums; a YouTube video whose separate video and audio are downloaded in ranges and merged into MP4, and two 4K YouTube clips whose 1440p and 2160p must be AV1 MP4, VP9 WebM or hidden as the device's decoders allow, saving the 2160p (or best offered) variant at every quality choice | Specific samples; public services and availability can change. The original multi-video Telegram sample is no longer public, so CI does not assert album playback end to end. On the API 35 emulator AV1 is hidden above 1080p, so the AV1 path runs live only on devices that decode it |
| Telegram private and restricted channels | Private links route into an in-app Telegram Web login. The bridge test covers complete responses, validated multi-range videos, truncated ranges, hidden video rejection, stale capture replacement and abort/retry state. The Activity smoke test loads Telegram Web and verifies a failed capture returns to a retryable state without a bridge-thread crash | Requires the user's Telegram account, so private-channel access is not automated. API 24–28 WebView behavior remains a device gap |
| Public unavailability | An unavailable Facebook video; deleted and text-only Threads posts | Expected messages asserted; unexpected network/auth/parse errors fail |

Default device runs select **34 offline checks**: 8 state regressions, 2 core error-code checks (every code has its string, and the live check sorts the codes into blocked and broken), 9 UI workflows (one for the first-run tutorial), 6 media workflows, 6 video-and-audio merge checks, 2 playable-quality checks and 1 native-extractor check. Legacy runs select **15 checks**: 4 Android 7–9 storage checks, the first-run tutorial, the 6 merge checks (which there confirm that merging is withheld), 3 playable-quality checks (one confirms that YouTube offers only complete files) and the native-extractor check. Highest-quality live runs select **28 checks**, including availability and smoke checks; 8 of them download every item of photo, carousel and GIF posts and require Android to decode each file. Smallest-quality runs select **14 real transfer/decode checks**. A merged YouTube download must also contain an audio track, match its variant's height and be saved with its container's MIME type and extension. A successful live transfer requires nonempty bytes, duration/dimensions and an Android-decoded frame. Enabled live failures are failures, never silent skips. Some probes use alternate public samples; JSON records all attempts.

## Reports and isolation

Each command writes timestamped logs and `report.json` under ignored `artifacts/test-runs/`. Live runs collect fresh per-platform JSON before removing their private media copies. JVM JUnit results are in `core-tests/build/test-results/test/` and `app/build/test-results/testDebugUnitTest/`; lint reports are in `app/build/reports/`. Inspect logs before sharing: transient provider errors may contain URL details. Success reports do not intentionally include signed download URLs or cookies.

The runner rejects failed, skipped, empty, incomplete or duplicate instrumentation results, results that disagree with the test count Android declares, and missing requested tests. It checks Android's protocol and JUnit summary because `adb` can exit zero after assertion failures. Instrumentation timeout stops test processes and aborts later suites; dispose of that isolated emulator before retrying.

Workflow fixtures snapshot/restore preferences and remove owned files, including after assertion failures. `legacy` revokes the app's storage permissions before and after its run, because Android shows the prompt only while they are revoked; the storage checks restore the screen rotation they change. Real-media fixtures use unique `Downloads/StillTests_<id>` directories. Live probes remove their own jobs/files and restore temporary preferences, including Library state, concurrency and output folder. APK installation uses `-r`; the runner does not clear app data or delete arbitrary Downloads files. Use an isolated device because tests temporarily replace Library state and must not overlap real downloads/exports. Process crashes can interrupt cleanup.

Workflow media fixtures are generated for this project; the README screenshot library is public-domain NASA imagery. See [fixture provenance](app/src/androidTest/assets/README.md).

## README screenshots

```sh
python3 scripts/test.py screenshots --serial emulator-5554
python3 scripts/readme_art.py artifacts/test-runs/<run>/screenshots
```

`ReadmeScreenshots` runs only with this mode. It seeds an isolated library and a three-photo post, then captures the library, save sheet, long-press actions and settings in light and dark, plus the viewer. The runner sets a 9:41 demo status bar and gesture navigation for the run and restores the previous navigation mode afterwards. `readme_art.py` (Pillow, fontTools and uharfbuzz) redraws `docs/readme/` from those files.

## CI and releases

| Workflow | When | What |
| --- | --- | --- |
| [CI](.github/workflows/ci.yml) | Every pull request and push to `main` | actionlint, then `scripts/test.py local` (core-tests plus `:app:testDebugUnitTest`), then the offline device suite on API 29 and API 35 emulators and the legacy selection on an API 28 emulator. Device and live jobs set `stillMinifyDebug`, so the debug APK they install is shrunk with the release R8 rules plus `proguard-instrumentation.pro`. That extra file keeps the Kotlin stdlib, `ListenableFuture`, and `org.still.saver` members the instrumentation code calls. Release shrinking does not use it. The **CI passed** job is the single required check; it does not yet wait for the API 28 job |
| [Release](.github/workflows/release.yml) | Mondays 15:00 UTC, or by hand | Plans a version from tags and changed app files, reruns CI on that commit, builds the signed APK, verifies its version, certificate and notices, installs and launches it on an API 35 emulator, then publishes it |
| [Live platforms](.github/workflows/live.yml) | Sundays 14:00 UTC, or by hand | The live suite on API 35. The emulator step exits 0 when every failure is a platform that refused GitHub's network, and exits 1 when a platform or the runner is broken. The report job writes that verdict on scheduled and manual runs. Scheduled runs open or update a `live-check` issue only when a platform is broken, and a `live-blocked` issue after three scheduled weeks in a row in which platforms were only blocked |
| [Pull request](.github/workflows/pull-request.yml) | Pull requests | Labels by path for release notes; auto-merges non-major Dependabot updates once CI passes |
| [yt-dlp](.github/workflows/ytdlp.yml) | Fridays 06:00 UTC, or by hand | Bundles the latest stable yt-dlp on a `deps/yt-dlp` branch, opens or updates its `dependencies` pull request with auto-merge on, and starts the live check on that branch; closes the pull request once `main` is current |

Reports, JUnit XML, coverage and mutation reports upload as artifacts even on failure, and the checks job writes a Markdown summary of test counts, coverage and mutation score. Live checks run from GitHub's network, where platforms may rate-limit or block requests (YouTube asks it to prove it is not a bot). Each failed attempt's JSON records a `category`: `blocked` for rate limits, bot checks and login walls aimed at the network, `broken` for anything else. `report.json`'s `live` section and the job summary list blocked and broken platforms; a failure with no JSON, or a runner error, counts as broken. A smallest-quality pass does not write the photo, carousel and availability reports, and `adb exec-out run-as` can exit 0 while printing `cat: No such file`. Those replies are not evidence. `scripts/test.py live` exits 0 when every failure is blocked and exits 1 when anything is broken, so a network block does not fail the emulator step. The report job writes the verdict into the job summary on scheduled and manual runs. Only the weekly schedule opens or closes issues. Each scheduled run uploads a `live-verdict` artifact (`passed`, `blocked` or `broken`), and the next scheduled run reads the previous two to count blocked weeks.

`scripts/release.py` holds the release logic so it is unit-tested locally (`scripts/tests/test_release.py`): `plan` decides whether and what to release, `verify` checks the APK with `apksigner` and `aapt2`, `smoke` installs and launches it on an emulator, and `notes` writes the install and verification notes.

`scripts/ytdlp.py update` downloads the latest stable yt-dlp zipapp, verifies `SHA2-256SUMS.sig` with the pinned key in `release/yt-dlp-signing-key.asc`, checks the zipapp against that `SHA2-256SUMS` and its `yt_dlp/version.py` against the tag, then rewrites `app/src/main/res/raw/ytdlp`, `ExtractorRuntime` and `THIRD_PARTY_NOTICES.md`; `check` confirms the three agree. The in-app Settings update does the same checksum and version checks before replacing the installed extractor, and leaves the previous file in place when they fail. It does not check the OpenPGP signature: that verification needs GnuPG, which this script has and the app does not ship. Its tests (`scripts/tests/test_ytdlp.py`) use a fake release and also check the committed bundle. By default the yt-dlp workflow opens its pull request with `GITHUB_TOKEN`, which requires **Allow GitHub Actions to create and approve pull requests** under Settings → Actions → General. GitHub holds CI for such pull requests until a maintainer selects **Approve workflows to run** in the merge box. A dispatched CI run would not help, because checks from `workflow_dispatch` runs never satisfy **CI passed**. To skip the approval, install a GitHub App on the repository with read and write access to contents and pull requests, then add its ID as the `YTDLP_APP_ID` repository variable and its private key as the `YTDLP_APP_PRIVATE_KEY` secret. The workflow then pushes and opens the pull request as the app, and CI starts by itself.

API 24–27 storage/permission workflows, transfers into Downloads on API 24–28, actual system-picker interaction, cross-app sharing, real transfer scheduling/retry and delayed video preparation need further device coverage. Passing tests does not guarantee every public link or device codec works.
