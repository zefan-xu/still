# Test media

## Synthetic fixtures

These fixtures were generated for this project; none come from public posts.

`sample.mp4`: 640×480 H.264, 8 fps, eight silent seconds; red for four seconds, then blue for four seconds.

```sh
ffmpeg -f lavfi -i 'color=c=red:s=640x480:r=8:d=4' -f lavfi -i 'color=c=blue:s=640x480:r=8:d=4' -filter_complex '[0:v][1:v]concat=n=2:v=1:a=0[v]' -map '[v]' -c:v libx264 -pix_fmt yuv420p -movflags +faststart sample.mp4
```

`sample.gif`: 32×24, 4 fps, two seconds of red/blue halves, loops indefinitely.

```sh
ffmpeg -f lavfi -i 'color=c=red:s=32x24:r=4:d=1' -f lavfi -i 'color=c=blue:s=32x24:r=4:d=1' -filter_complex '[0:v][1:v]concat=n=2:v=1:a=0[v]' -map '[v]' -loop 0 sample.gif
```

`split-video.mp4` and `split-audio.m4a`: three seconds of a 320×240, 25 fps H.264 High test pattern with B-frames, and a 440 Hz AAC tone, each a fragmented single-track MP4 like the separate video and audio files YouTube serves.

```sh
ffmpeg -f lavfi -i 'testsrc2=s=320x240:r=25:d=3' -c:v libx264 -profile:v high -bf 2 -pix_fmt yuv420p -g 25 -an -movflags +dash+global_sidx -f mp4 split-video.mp4
ffmpeg -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=3' -c:a aac -b:a 64k -vn -movflags +dash+global_sidx -f mp4 split-audio.m4a
```

`split-video.webm` and `split-audio.webm`: the same three seconds as an 8-bit VP9 (profile 0) test pattern and a 48 kHz Opus tone, each a single-track WebM laid out for DASH like YouTube's VP9 and Opus files.

```sh
ffmpeg -f lavfi -i 'testsrc2=s=320x240:r=25:d=3' -c:v libvpx-vp9 -b:v 200k -pix_fmt yuv420p -g 25 -an -dash 1 -f webm split-video.webm
ffmpeg -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=3' -c:a libopus -b:a 48k -vn -dash 1 -f webm split-audio.webm
```

`split-video-av1.mp4`: the same three seconds as an 8-bit AV1 (Main) test pattern in a fragmented single-track MP4 like YouTube's AV1 files; the merge test pairs it with `split-audio.m4a`. It uses SVT-AV1: a fragmented libaom encode stores an empty `av1C` configuration, which Android's extractor rejects.

```sh
ffmpeg -f lavfi -i 'testsrc2=s=320x240:r=25:d=3' -c:v libsvtav1 -preset 10 -b:v 200k -pix_fmt yuv420p -g 25 -an -movflags +dash+global_sidx -f mp4 split-video-av1.mp4
```

`primed-audio.m4a`: the same AAC tone as an ordinary MP4, whose edit list skips the encoder's 1024 priming samples. Android versions that apply the edit list report its first sample before zero (−23 ms on API 35), which the merge must keep.

```sh
ffmpeg -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=3' -c:a aac -b:a 64k -vn primed-audio.m4a
```

The DocumentsProvider also generates a green 32×24 PNG and a deliberately invalid three-byte input. Encoded bytes may vary between FFmpeg versions; tests check observable properties and compare copied outputs to their current sources.

## README screenshots

`readme/` holds the library shown in the README screenshots (`ReadmeScreenshots`, run with `python3 scripts/test.py screenshots`). Every image is NASA media from the [NASA Image and Video Library](https://images.nasa.gov), which is generally not subject to copyright in the United States ([NASA media usage guidelines](https://www.nasa.gov/nasa-brand-center/images-and-media/)). NASA does not endorse this app.

The photos are the library's unmodified `~medium.jpg` renditions, `https://images-assets.nasa.gov/image/<id>/<id>~medium.jpg`.

| File | NASA ID | Title | Credit |
| --- | --- | --- | --- |
| `earthrise.jpg` | `as08-14-2383` | Apollo 8 Mission image, Earth over the horizon of the moon | NASA |
| `earthset.jpg` | `art002e021278` | A Breathtaking Earthset from Orion | NASA |
| `saturn.jpg` | `PIA21345` | So Far from Home | NASA/JPL-Caltech/Space Science Institute |
| `pillars.jpg` | `GSFC_20171208_Archive_e000842` | Hubble Goes High Def to Revisit the Iconic 'Pillars of Creation' | NASA, ESA and the Hubble Heritage Team (STScI/AURA) |
| `aurora-red.jpg` | `iss072e159172` | The aurora borealis blankets the Earth | NASA |
| `solar-array.jpg` | `iss052e004998` | Earth observation taken by the Expedition 52 crew | NASA/Jack Fischer |
| `curiosity.jpg` | `PIA24543` | Curiosity's Selfie at Mont Mercou | NASA/JPL-Caltech/MSSS |
| `mars-base.jpg` | `PIA23302` | First Humans on Mars (Artist's Concept) | NASA |

The two videos are silent camera moves over NASA stills, made with FFmpeg from the same `~medium.jpg` renditions.

`earthrise-orbit.mp4`: 1080×1080, 12 fps, 24 seconds, a slow push in on `as08-14-2383`.

```sh
ffmpeg -loop 1 -i as08-14-2383~medium.jpg -vf "zoompan=z='1+0.08*on/288':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=288:s=1080x1080:fps=12" -frames:v 288 -c:v libx264 -crf 30 -preset slow -pix_fmt yuv420p -movflags +faststart -an earthrise-orbit.mp4
```

`aurora-pass.mp4`: 1080×720, 12 fps, 18 seconds, a pan across `iss066e146457`, the aurora australis over the Indian Ocean seen from the International Space Station (NASA).

```sh
ffmpeg -loop 1 -i iss066e146457~medium.jpg -vf "scale=1440:-2,crop=1080:720:'360*n/215':(ih-720)/2" -r 12 -frames:v 216 -c:v libx264 -crf 30 -preset slow -pix_fmt yuv420p -movflags +faststart -an aurora-pass.mp4
```
