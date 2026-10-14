# Testing Still

Prerequisites: Python 3, JDK 21, an Android SDK with platform 35 and build-tools 35.0.0, and an unlocked API 29+ emulator. Set `ANDROID_HOME` or `sdk.dir` in `local.properties`; `.tools/android-sdk` is also recognized. Gradle downloads dependencies on its first run. Disable emulator animations for reliable UI checks.

```sh
python3 scripts/test.py local
python3 scripts/test.py device --serial emulator-5554
python3 scripts/test.py live --serial emulator-5554 --quality both
# All stages:
python3 scripts/test.py all --serial emulator-5554
```

`local` runs runner regression tests, JVM tests, lint and both APK builds. `device` adds deterministic Android workflows. `live` adds public-network tests. Suites run sequentially because they share preferences. The runner selects the sole connected emulator or requires `--serial` when several are connected. Physical devices require `--allow-physical --serial <id>` and should be dedicated to testing. The app itself still supports API 24+.

After building, focused reruns are available:

```sh
python3 scripts/test.py device --serial emulator-5554 --skip-build --class MediaWorkflowTest
python3 scripts/test.py live --serial emulator-5554 --skip-build --quality smallest --class 'LivePlatformDownloadTest#threads'
```

## Main functionality coverage

| Function | Automated coverage | Boundary |
| --- | --- | --- |
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
| Public platforms | Videos on every platform; X photo+video and GIF posts; Instagram, Threads and TikTok photo carousels; Bluesky photo; Pinterest image pin; Facebook reel/video/embed/legacy route | Specific samples; public services and availability can change |
| Public unavailability | An unavailable Facebook video; deleted and text-only Threads posts | Expected messages asserted; unexpected network/auth/parse errors fail |

Default device runs select **23 offline checks**: 8 state regressions, 8 UI workflows, 6 media workflows and 1 native-extractor check. Highest-quality live runs select **24 checks**, including availability and smoke checks; 8 of them download every item of photo, carousel and GIF posts and require Android to decode each file. Smallest-quality runs select **11 real transfer/decode checks**. A successful live transfer requires nonempty bytes, duration/dimensions and an Android-decoded frame. Enabled live failures are failures, never silent skips. Some probes use alternate public samples; JSON records all attempts.

## Reports and isolation

Each command writes timestamped logs and `report.json` under ignored `artifacts/test-runs/`. Live runs collect fresh per-platform JSON before removing their private media copies. JVM JUnit results are in `core-tests/build/test-results/test/`; lint reports are in `app/build/reports/`. Inspect logs before sharing: transient provider errors may contain URL details. Success reports do not intentionally include signed download URLs or cookies.

The runner rejects failed, skipped, empty, incomplete, duplicate or wrongly counted instrumentation results and missing requested tests. It checks Android's protocol and JUnit summary because `adb` can exit zero after assertion failures. Instrumentation timeout stops test processes and aborts later suites; dispose of that isolated emulator before retrying.

Workflow fixtures snapshot/restore preferences and remove owned files, including after assertion failures. Real-media fixtures use unique `Downloads/StillTests_<id>` directories. Live probes remove their own jobs/files and restore temporary preferences, including Library state, concurrency and output folder. APK installation uses `-r`; the runner does not clear app data or delete arbitrary Downloads files. Use an isolated device because tests temporarily replace Library state and must not overlap real downloads/exports. Process crashes can interrupt cleanup.

Media fixtures are generated for this project. See [fixture provenance](app/src/androidTest/assets/README.md).

## CI

[Android workflow](.github/workflows/android.yml) runs local checks and offline API 29/35 emulator jobs on pushes and pull requests. Manual dispatch with `live=true` adds a separate API 35 network job at both qualities. Artifacts upload even on failure. Remote workflow execution is a separate verification step; local configuration does not prove those jobs have run.

API 24–28 storage/permission workflows, actual system-picker interaction, cross-app sharing, real transfer scheduling/retry and delayed video preparation need further device coverage. Passing tests does not guarantee every public link or device codec works.
