# Verification

Still 1.1.0, verified on 2026-10-13 with JDK 21, Android SDK 35 and an isolated Android 15/API 35 ARM64 emulator. Instagram photo resolution was also checked from a physical Android 16 phone on a home network.

## Automated results

| Check | Observed result |
| --- | --- |
| JVM tests | 99 tests pass: link parsing, every resolver (X, Instagram, Bluesky, Pinterest, TikTok photos, Threads, Facebook embeds), extractor JSON, scoped cookies, preview fetching, MP4 repair, library grouping, formatting and GIF encoding. |
| Android build and lint | Debug and instrumentation APKs build; lint reports zero errors and nine warnings (newer dependency versions, native Switch usage, backup metadata, one redundant API guard, one custom view constructor). |
| Offline device suite | 22 deterministic checks pass: settings, quality selection, saving several items, library search and file operations, Retry/Cancel, Open/Share routing, playback, GIF export, cancellation and failure, download-state recovery and the bundled extractor. |
| Live public posts | 24 checks pass at highest quality. Each resolves a real public post, downloads every item through the app's queue and requires Android to decode it. |

## Live posts

| Platform | Post | Saved and decoded |
| --- | --- | --- |
| X | `x.com/i/status/1841206275088290279` | Video, 1280×720 |
| X | `x.com/i/status/1623739803874349067` | Video 720×1278 and two photos (1152×2048, 1638×2048) |
| X | `x.com/i/status/1575199173472927762` | GIF animation (MP4), 498×276 |
| Instagram | `instagram.com/reel/Chunk8-jurw/` | Video, 480×854 |
| Instagram | `instagram.com/p/DePlAyED5AA/` | Carousel of two photos, 1080×1350 |
| TikTok | `tiktok.com/@hankgreen1/video/7047596209028074758` | Video, 576×1024 |
| TikTok | `tiktok.com/@anamiriamyepez/photo/7498924643227536645` | Photo slideshow, three 1080×1080 photos |
| Facebook | `facebook.com/reel/1195289147628387` | Video, 480×848 |
| Facebook | `facebook.com/cnn/videos/10155529876156509/` | Video, 1080×1080 |
| Facebook | `facebook.com/100033620354545/videos/106560053808006/` | Video via the extractor, the public embed and the legacy `video.php` link |
| Pinterest | `pinterest.com/pin/664281013778109217/` | Video, 1080×1920 |
| Pinterest | `in.pinterest.com/pin/…--560276009885574297/` | Image pin, 1024×683 |
| Bluesky | `bsky.app/profile/bsky.app/post/3l3vgf77uco2g` | Video, 1920×1080 |
| Bluesky | `bsky.app/profile/bsky.app/post/3mv3shqdfuc2e` | Photo, 4000×3000 |
| Threads | `threads.com/@zuck/post/DHV7vTivqWD`, `…/DMF6uUgx9f9` | Silent video and video with audio |
| Threads | `threads.com/@nasa/post/DeSYGIdFUvp` | Carousel of seven photos at 2048 px |

Expected failures are also asserted: an unavailable Facebook video, a deleted Threads post and a text-only Threads post each report a specific, plain message.

## Findings fixed during verification

- The bundled extractor reads only videos, so photo posts on Instagram, Bluesky, Pinterest and TikTok failed. Each now uses the platform's own public data for photos; videos keep the extractor.
- Threads photo posts were rejected; the public embed's photos are now saved.
- Regional Pinterest links such as `in.pinterest.com` were rejected; they are now accepted.
- X GIF animations are MP4 files whose composition-offset box has non-zero reserved flags. Android's MP4 extractor rejects such files, so they would not play or show a thumbnail. Still zeroes those flags after the download and changes no other byte.

## Limits

These are specific public samples, not a guarantee for every post, region or codec. Instagram and TikTok may refuse anonymous requests from a network after many requests, or from unusual HTTP clients; Still then reports that the platform is limiting the network. Login-required, private and deleted posts, streaming-only formats, split audio/video tracks and Facebook photos are not supported. Only API 35 was exercised on an emulator; API 24–28 storage, font, thumbnail and immersive-mode paths are untested on devices. Remote CI has not been run.

## Reproduce

```sh
python3 scripts/test.py local
python3 scripts/test.py device --serial emulator-5554
python3 scripts/test.py live --serial emulator-5554 --quality highest
```

Use an isolated API 29+ emulator. Live checks contact public services, remove their own downloads and write per-post JSON under the ignored `artifacts/test-runs/`. [TESTING.md](TESTING.md) describes the coverage boundaries.
