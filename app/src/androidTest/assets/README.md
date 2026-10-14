# Synthetic test media

These fixtures were generated for this project; none come from public posts.

`sample.mp4`: 640×480 H.264, 8 fps, eight silent seconds; red for four seconds, then blue for four seconds.

```sh
ffmpeg -f lavfi -i 'color=c=red:s=640x480:r=8:d=4' -f lavfi -i 'color=c=blue:s=640x480:r=8:d=4' -filter_complex '[0:v][1:v]concat=n=2:v=1:a=0[v]' -map '[v]' -c:v libx264 -pix_fmt yuv420p -movflags +faststart sample.mp4
```

`sample.gif`: 32×24, 4 fps, two seconds of red/blue halves, loops indefinitely.

```sh
ffmpeg -f lavfi -i 'color=c=red:s=32x24:r=4:d=1' -f lavfi -i 'color=c=blue:s=32x24:r=4:d=1' -filter_complex '[0:v][1:v]concat=n=2:v=1:a=0[v]' -map '[v]' -loop 0 sample.gif
```

The DocumentsProvider also generates a green 32×24 PNG and a deliberately invalid three-byte input. Encoded bytes may vary between FFmpeg versions; tests check observable properties and compare copied outputs to their current sources.
