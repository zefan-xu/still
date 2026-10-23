# Third-party notices

Still's application code and GIF encoder are original work under GPL-3.0-or-later.

## Runtime dependencies

| Component | License | Source |
| --- | --- | --- |
| jsoup 1.23.2 | MIT | https://github.com/jhy/jsoup/tree/jsoup-1.23.2 |
| youtubedl-android library and common 0.18.1 | GPL-3.0 | https://github.com/yausername/youtubedl-android/tree/0.18.1 |
| Bundled yt-dlp 2026.10.21 | Unlicense; bundled dependencies retain their own terms | https://github.com/yt-dlp/yt-dlp/releases/tag/2026.10.21 |
| Bundled Python runtime | Python Software Foundation License and component notices | https://github.com/python/cpython |
| Bundled QuickJS runtime | MIT | https://github.com/bellard/quickjs |
| Kotlin standard library 1.7.22 and JetBrains annotations | Apache-2.0 | https://github.com/JetBrains/kotlin; https://github.com/JetBrains/java-annotations |
| AndroidX and Guava listenablefuture | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support; https://github.com/google/guava |
| Jackson annotations/core/databind 2.11.1 | Apache-2.0 | https://github.com/FasterXML |
| Apache Commons IO 2.5 | Apache-2.0 | https://github.com/apache/commons-io |
| Source Serif 4 (4.005; Display Regular and Semibold), unmodified static TTFs | SIL Open Font License 1.1, Reserved Font Name ‘Source’ | https://github.com/adobe-fonts/source-serif/releases/tag/4.005R |
| Material Symbols Outlined (a subset of icons) | Apache-2.0; converted from SVG to Android VectorDrawable | https://github.com/google/material-design-icons |

The upstream Android library's POM declares GPL-3.0. Its packaged native runtimes are redistributed without modification. The app overrides the library's older yt-dlp resource with the unmodified official 2026.10.21 executable in `app/src/main/res/raw/ytdlp` (3,074,818 bytes; SHA-256 `b7af77f686e95f7845ce8d09d21957718febb8b48b30555adb9dd2292ad7bf14`, checked against the release's `SHA2-256SUMS`). APK upgrades install this bundle over an older cached extractor while preserving newer explicit updates. The app bundles no FFmpeg or aria2 executable. Extractor updates replace the bundled yt-dlp only after the download matches the release's SHA2-256SUMS and the zipapp version matches the tag; a mismatch leaves the previous file in place. Its own license and third-party terms still apply.

License texts are in `licenses/` and are packaged into the APK as assets along with this notice and the application license. Upstream component notices remain in their dependency artifacts. Obtain exact corresponding native build sources and notices from the youtubedl-android 0.18.1 source release when redistributing an APK; do not treat the Java wrapper alone as the complete native source.

## Test and build dependencies

JUnit 4.13.2: Eclipse Public License 1.0; Hamcrest: BSD. JVM org.json 20240303: public domain. Android test runner/AndroidJUnit, AndroidX Test Core 1.6.1 and Espresso Core 3.6.1: Apache-2.0. Android Gradle Plugin: Apache-2.0. Gradle wrapper: Apache-2.0. These test libraries are not included in the main APK.

## Build-time art tools

Anatomy's isometric kit (`kit/iso-kit.mjs` from https://github.com/wheresryan22/anatomy at commit `e6600ffc4e8e6620eeb45bf49bcdbd5e4b84163f`, MIT, Copyright (c) 2026 Ryan) is vendored unmodified in `tools/anatomy/` with its license. `tools/figures/build.mjs` uses it to draw the launcher icon, the notification icon, the empty-state figure and the tutorial's phone, which are committed as Android VectorDrawable XML. The kit runs only at development time on Node.js; no anatomy code is included in the APK.

## External tools and services

The public FxTwitter API is an external service, not bundled code. Its source and self-hosting instructions are at https://github.com/FxEmbed/FxEmbed and https://docs.fxembed.com. No affiliation with X, the supported platforms, or FxTwitter is claimed.
