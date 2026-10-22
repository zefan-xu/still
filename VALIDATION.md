# Verification

Still 1.2.1, verified on 2026-10-14 with JDK 21, Android SDK 35 and an isolated Android 15/API 35 ARM64 emulator. Instagram photo resolution was also checked from a physical Android 16 phone on a home network.

## Automated results

| Check | Observed result |
| --- | --- |
| JVM tests | 163 tests pass: link parsing and Telegram/YouTube link canonicalization, every resolver (X, Instagram, Bluesky, Pinterest, TikTok photos, Threads, Facebook embeds, Telegram public posts, YouTube), YouTube stream pairing, codec preference and quality ranking, ranged transfers, extractor JSON, scoped cookies, preview fetching, MP4 repair, library grouping, formatting and GIF encoding. |
| Android build and lint | Debug, release and instrumentation APKs build; lint treats warnings as errors and reports no issues, with no baseline. |
| Offline device suite | 32 deterministic checks pass: settings, quality selection, saving several items, library search and file operations, Retry/Cancel, Open/Share routing, playback, GIF export, cancellation and failure, download-state recovery, the first-run tutorial, video and audio merging, playable-quality detection and the bundled extractor. |
| Live public posts | 27 checks were verified on 2026-10-14. Telegram's public post was verified separately on 2026-10-22, bringing the configured highest-quality suite to 28 checks. Each successful probe resolves a real public post, downloads every item through the app's queue and requires Android to decode it. |

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
| YouTube | `youtube.com/watch?v=LeUcjqqhNxM` | Separate video and audio merged on the device: 1280×720 at highest, 256×144 at smallest, each with audio (2026-10-14) |
| YouTube | `youtu.be/HOfdboHvshg`, `youtube.com/shorts/tPEE9ZwTmy0` | Merged 1920×818 (labelled 1080p, two 10 MB ranges) and an 854×470 Short (2026-10-14) |
| YouTube | `youtube.com/watch?v=KwvyfS9Icyc` | 18-second clip whose 1440p and 2160p are AV1 and VP9 only. The emulator can't decode that AV1, so it is offered VP9: VP9 and Opus merged into a 3840×2160 WebM (39.8 MB, four 10 MB ranges) with audio, saved as `video/webm`; every packet matches YouTube's files and the Gallery app plays it. In a one-off check on the emulator, `StreamMerger` merged its 1080p AV1 and AAC into a playable MP4 whose packets match YouTube's (2026-10-14) |
| YouTube | `youtube.com/watch?v=GNe2XHW3C4I` | 5-second clip with VP9 only above 1080p, merged into a 3840×2160 WebM (5.0 MB) with audio (2026-10-14) |
| Telegram | `t.me/europa_press/613` | Public channel video, 848×480 with audio, 61 seconds (2026-10-22) |

Expected failures are also asserted: an unavailable Facebook video, a deleted Threads post and a text-only Threads post each report a specific, plain message.

## Findings fixed during verification

- The bundled extractor reads only videos, so photo posts on Instagram, Bluesky, Pinterest and TikTok failed. Each now uses the platform's own public data for photos; videos keep the extractor.
- Threads photo posts were rejected; the public embed's photos are now saved.
- Regional Pinterest links such as `in.pinterest.com` were rejected; they are now accepted.
- X GIF animations are MP4 files whose composition-offset box has non-zero reserved flags. Android's MP4 extractor rejects such files, so they would not play or show a thumbnail. Still zeroes those flags after the download and changes no other byte.

## Limits

These are specific public samples, not a guarantee for every post, region or codec. Instagram and TikTok may refuse anonymous requests from a network after many requests, or from unusual HTTP clients; Still then reports that the platform is limiting the network. Apart from Telegram's optional in-app web sign-in, login-required, private and deleted posts, streaming-only formats, split audio/video tracks outside YouTube's H.264, 8-bit AV1 with AAC and SDR VP9 with Opus files (HDR, 10-bit and AI-upscaled copies are skipped; AV1 needs Android 14, the first release whose MP4 muxer writes it, and H.264 and VP9 need Android 10), and Facebook photos are not supported. Telegram Web's login page and retryable failed-capture flow were checked on an API 35 emulator; downloading from an authenticated private channel was not run because no Telegram account was supplied. On an API 24 emulator, Android's MP4 writer stopped at the first B-frame of the H.264 test asset ("do not support out of order frames"), and YouTube's H.264 has B-frames; the same merge tests pass on API 29 and 35 emulators. Merging was not tried on API 25–28, so Android 7 to 9 are offered YouTube's complete files only; on an API 28 emulator the legacy suite checks that merging is withheld and only complete files are offered. A quality is hidden when none of the phone's decoders reports that size and frame rate; the API 35 emulator decodes VP9 to 4K at 30 fps but AV1 only to 2048×2048 in software, so it is offered no AV1 above 1080p. The AV1 merge was verified only on API 35. Android's WebM muxer drops Opus's 6.5 ms encoder delay, so merged WebM audio starts that much late. Only API 35 ran the live suite; the API 24 emulator ran only the merge and decoder checks. On an API 28 emulator, the storage prompt (including a rotation while it is open), GIF files written straight into Downloads, their thumbnails, the viewer's immersive mode and the bundled fonts are checked; API 24–27 storage, font, thumbnail and immersive-mode paths are untested on devices. GitHub CI runs `scripts/test.py local` and the offline device suite on API 29 and API 35 emulators, and the legacy selection on an API 28 emulator, for every pull request, push to `main` and release.

## Reproduce

```sh
python3 scripts/test.py local
python3 scripts/test.py device --serial emulator-5554
python3 scripts/test.py live --serial emulator-5554 --quality highest
```

Use an isolated API 29+ emulator. Live checks contact public services, remove their own downloads and write per-post JSON under the ignored `artifacts/test-runs/`. [TESTING.md](TESTING.md) describes the coverage boundaries.
