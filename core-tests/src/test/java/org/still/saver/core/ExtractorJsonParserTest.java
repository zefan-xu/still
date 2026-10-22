// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ExtractorJsonParserTest {
  private static final String SOURCE = "https://www.instagram.com/reel/Abc123/";

  @Test
  public void readsTelegramEmbedAlbumVideos() throws Exception {
    JSONObject first =
        new JSONObject()
            .put("id", "613")
            .put("channel", "Example channel")
            .put("formats", new JSONArray().put(telegramFormat("first.mp4")));
    JSONObject second =
        new JSONObject()
            .put("id", "614")
            .put("channel", "Example channel")
            .put("formats", new JSONArray().put(telegramFormat("second.mp4")));
    JSONObject root =
        new JSONObject()
            .put("extractor_key", "TelegramEmbed")
            .put("title", "Example channel 613")
            .put("entries", new JSONArray().put(first).put(second));

    Post post =
        ExtractorJsonParser.parse(root.toString(), "https://t.me/example_channel/613");

    assertEquals("Example channel 613", post.title);
    assertEquals("Example channel", post.author);
    assertEquals(2, post.media.size());
    assertEquals(
        "https://cdn4.telesco.pe/file/first.mp4",
        post.media.get(0).chooseVariant("best").url);
    assertEquals("video/mp4", post.media.get(1).chooseVariant("best").mimeType);
  }

  @Test
  public void prefersCombinedMp4AndPreservesOnlyAllowedRequestHeaders() throws Exception {
    JSONObject headers =
        new JSONObject()
            .put("User-Agent", "Synthetic agent")
            .put("Referer", SOURCE)
            .put("Cookie", "must not be used")
            .put("Authorization", "must not be used");
    JSONObject small = format("https://cdn.example/small.mp4", "mp4", 360, 600);
    JSONObject big =
        format("https://cdn.example/big.mp4", "mp4", 1080, 3000)
            .put("http_headers", new JSONObject().put("User-Agent", "Format-specific agent"));
    JSONArray formats =
        new JSONArray()
            .put(small)
            .put(big)
            .put(big)
            .put(format("https://cdn.example/best.webm", "webm", 2160, 8000))
            .put(
                format("https://cdn.example/video-only.mp4", "mp4", 2160, 7000)
                    .put("acodec", "none"))
            .put(format("https://cdn.example/audio-only.mp4", "mp4", 0, 200).put("vcodec", "none"))
            .put(
                format("https://cdn.example/stream.m3u8", "mp4", 2160, 9000)
                    .put("protocol", "m3u8_native"))
            .put(
                format("https://cdn.example/dash.mp4", "mp4", 2160, 9000)
                    .put("protocol", "http_dash_segments"));
    JSONObject root =
        new JSONObject()
            .put("id", "synthetic-id")
            .put("title", "Synthetic title")
            .put("uploader", "Example author")
            .put("http_headers", headers)
            .put("formats", formats);

    Post post = ExtractorJsonParser.parse(root.toString(), SOURCE);
    assertEquals("synthetic-id", post.id);
    assertEquals("Synthetic title", post.title);
    assertEquals("Example author", post.author);
    assertEquals(SOURCE, post.sourceUrl);
    assertEquals(1, post.media.size());
    Media media = post.media.get(0);
    assertEquals(2, media.variants.size());
    assertEquals("https://cdn.example/big.mp4", media.chooseVariant("best").url);
    assertEquals("https://cdn.example/small.mp4", media.chooseVariant("smallest").url);
    assertEquals(3000000, media.chooseVariant("best").bitrate);
    assertEquals("video/mp4", media.chooseVariant("best").mimeType);
    assertEquals("Format-specific agent", media.chooseVariant("best").headers.get("User-Agent"));
    assertEquals(SOURCE, media.chooseVariant("best").headers.get("Referer"));
    assertEquals(2, media.chooseVariant("best").headers.size());
  }

  @Test
  public void excludesUnplayableFormatsBeforeSelectingHighestQuality() throws Exception {
    JSONObject playable = format("https://cdn.example/playable.mp4", "mp4", 720, 2000);
    JSONObject bytevc2 =
        format("https://cdn.example/bytevc2.mp4", "mp4", 2160, 9000).put("vcodec", "bytevc2");
    JSONObject markedUnplayable =
        format("https://cdn.example/unsupported.mp4", "mp4", 2160, 8000)
            .put("format_note", "(UNPLAYABLE)")
            .put("preference", -100);
    JSONObject root =
        new JSONObject()
            .put("formats", new JSONArray().put(playable).put(bytevc2).put(markedUnplayable));

    Post post = ExtractorJsonParser.parse(root.toString(), SOURCE);
    assertEquals(1, post.media.get(0).variants.size());
    assertEquals("https://cdn.example/playable.mp4", post.media.get(0).chooseVariant("best").url);
    assertError(bytevc2.toString(), "no downloadable");
    assertError(markedUnplayable.toString(), "no downloadable");
  }

  @Test
  public void traversesPostMultiVideoEntriesAndDeduplicatesMedia() throws Exception {
    JSONObject one = format("https://cdn.example/one.mp4", "mp4", 720, 2000).put("id", "one");
    JSONObject two = format("https://cdn.example/two.webm", "webm", 480, 1000).put("id", "two");
    JSONObject root =
        new JSONObject()
            .put("id", "post")
            .put("description", "A multi-video post")
            .put("entries", new JSONArray().put(one).put(JSONObject.NULL).put(two).put(one));
    Post post = ExtractorJsonParser.parse(root.toString(), SOURCE);
    assertEquals(2, post.media.size());
    assertEquals("A multi-video post", post.title);
    assertEquals("video/webm", post.media.get(1).chooseVariant("best").mimeType);
  }

  @Test
  public void pairsYouTubeH264VideoWithOriginalAacAudio() throws Exception {
    String watch = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    JSONObject agent = new JSONObject().put("User-Agent", "Synthetic agent");
    JSONArray formats =
        new JSONArray()
            .put(
                new JSONObject()
                    .put("format_id", "sb0")
                    .put("url", "https://i.ytimg.com/sb/dQw4w9WgXcQ/storyboard3_L0/default.jpg")
                    .put("ext", "mhtml")
                    .put("protocol", "mhtml")
                    .put("vcodec", "none")
                    .put("acodec", "none"))
            .put(youtubeAudio("139", "mp4a.40.5", 49, -1))
            .put(youtubeAudio("140-1", "mp4a.40.2", 130, -1))
            .put(youtubeAudio("140-0", "mp4a.40.2", 129, 10))
            .put(youtubeAudio("140-0-drc", "mp4a.40.2", 140, 10))
            .put(youtubeAudio("251", "opus", 160, 10).put("ext", "webm"))
            .put(
                format("https://rr1.googlevideo.example/videoplayback?itag=18", "mp4", 360, 400)
                    .put("vcodec", "avc1.42001E")
                    .put("acodec", "mp4a.40.2")
                    .put("http_headers", agent))
            .put(
                format("https://manifest.googlevideo.example/hls/96/index.m3u8", "mp4", 1080, 4000)
                    .put("protocol", "m3u8_native"))
            .put(youtubeVideo("134", "mp4", "avc1.4D401E", 360, 315))
            .put(youtubeVideo("136", "mp4", "avc1.4D401F", 546, 993).put("format_note", "720p"))
            .put(youtubeVideo("137", "mp4", "avc1.640028", 1080, 3038).put("format_note", "Premium"))
            .put(youtubeVideo("248", "webm", "vp9", 1080, 1158))
            .put(youtubeVideo("399", "mp4", "av01.0.08M.08", 1080, 1142))
            .put(youtubeVideo("401", "mp4", "avc1.640033", 2160, 9000).put("has_drm", true))
            .put(youtubeVideo("137-sr", "mp4", "avc1.640028", 1080, 9000))
            .put(youtubeVideo("299-sr", "mp4", "avc1.64002a", 1440, 9000))
            .put(
                youtubeVideo("264", "mp4", "avc1.640032", 1440, 9000)
                    .put("dynamic_range", "HDR10"));
    JSONObject root =
        new JSONObject()
            .put("id", "dQw4w9WgXcQ")
            .put("title", "A YouTube video")
            .put("channel", "Some channel")
            .put("extractor_key", "Youtube")
            .put("thumbnail", "https://i.ytimg.com/vi_webp/dQw4w9WgXcQ/maxresdefault.webp")
            .put("duration", 213)
            .put("http_headers", agent)
            .put("formats", formats);

    Post post = ExtractorJsonParser.parse(root.toString(), watch);
    assertEquals("dQw4w9WgXcQ", post.id);
    assertEquals("Some channel", post.author);
    Media media = post.media.get(0);
    assertEquals(213000, media.durationMs);
    assertEquals("https://i.ytimg.com/vi_webp/dQw4w9WgXcQ/maxresdefault.webp", media.thumbnail);
    assertEquals(3, media.variants.size());
    Variant best = media.chooseVariant("best");
    assertEquals(youtubeUrl("137"), best.url);
    assertEquals(youtubeUrl("140-0"), best.audioUrl);
    assertEquals("1080p (MP4)", best.label);
    assertEquals("video/mp4", best.mimeType);
    assertEquals((3038 + 129) * 1000, best.bitrate);
    assertEquals(1920, best.width);
    assertEquals("Synthetic agent", best.headers.get("User-Agent"));
    assertEquals(youtubeUrl("136"), media.variants.get(1).url);
    assertEquals("720p (MP4)", media.variants.get(1).label);
    assertEquals(546, media.variants.get(1).height);
    Variant complete = media.chooseVariant("smallest");
    assertEquals("https://rr1.googlevideo.example/videoplayback?itag=18", complete.url);
    assertNull(complete.audioUrl);
    assertEquals("360p (MP4)", complete.label);

    formats.remove(6);
    media = ExtractorJsonParser.parse(root.toString(), watch).media.get(0);
    assertEquals(youtubeUrl("134"), media.chooseVariant("smallest").url);
    assertEquals(youtubeUrl("140-0"), media.chooseVariant("smallest").audioUrl);

    root.put("extractor_key", "Generic");
    assertError(root.toString(), "no downloadable");
    root.put("extractor_key", "Youtube");
    for (int i = formats.length() - 1; i >= 0; i--)
      if ("none".equals(formats.getJSONObject(i).optString("vcodec"))) formats.remove(i);
    assertError(root.toString(), "no version of this video that this phone can save");
    root.put("live_status", "is_live");
    assertError(root.toString(), "live");
  }

  @Test
  public void pairsYouTubeVp9WithOpusOnlyAtHeightsWithoutMp4() throws Exception {
    String watch = "https://www.youtube.com/watch?v=KwvyfS9Icyc";
    JSONArray formats =
        new JSONArray()
            .put(youtubeAudio("140", "mp4a.40.2", 129, -1))
            .put(youtubeOpus("250", 74, 10))
            .put(youtubeOpus("251-0", 140, 10))
            .put(youtubeOpus("251-0-tie", 140, 10))
            .put(youtubeOpus("251-0-drc", 170, 10))
            .put(youtubeOpus("251-1", 160, -1))
            .put(youtubeOpus("251-drm", 200, 10).put("has_drm", true))
            .put(youtubeAudio("251-mp4", "opus", 210, 10))
            .put(youtubeVideo("137", "mp4", "avc1.640028", 1080, 4300))
            .put(youtubeVideo("248", "webm", "vp9", 1080, 3200).put("dynamic_range", "SDR"))
            .put(youtubeVideo("271", "webm", "vp9", 1440, 8700))
            .put(youtubeVideo("271-sr", "webm", "vp9", 1440, 9500))
            .put(youtubeVideo("336", "webm", "vp09.00.50.08", 1440, 9900).put("dynamic_range", "HDR10"))
            .put(youtubeVideo("400", "mp4", "av01.0.12M.08", 1440, 7300))
            .put(
                youtubeVideo("313", "webm", "vp09.00.51.08", 2160, 17500)
                    .put("dynamic_range", "SDR")
                    .put("http_headers", new JSONObject().put("User-Agent", "VP9 agent")))
            .put(youtubeVideo("313-tie", "webm", "vp9", 2160, 17500))
            .put(youtubeVideo("315", "webm", "vp9", 2160, 15000))
            .put(youtubeVideo("0", "webm", "vp9", 0, 500))
            .put(youtubeVideo("337", "webm", "vp9.2", 2160, 22000).put("dynamic_range", "HDR10"))
            .put(youtubeVideo("402", "webm", "vp09.02.51.10", 4320, 40000))
            .put(youtubeVideo("701", "mp4", "av01.0.12M.10", 4320, 38000))
            .put(youtubeVideo("403", "webm", "vp9", 2880, 30000).put("protocol", "m3u8_native"))
            .put(youtubeVideo("135", "mp4", "avc1.4D401F", 480, 1287))
            .put(youtubeVideo("244", "webm", "vp9", 480, 1000))
            .put(youtubeVideo("780", "webm", "vp9", 608, 1208).put("format_note", "480p"));
    JSONObject root =
        new JSONObject()
            .put("id", "KwvyfS9Icyc")
            .put("title", "A 4K YouTube video")
            .put("extractor_key", "Youtube")
            .put("formats", formats);
    // Merges VP9 but not AV1, like Android 10 to 13, and plays every size.
    Device android10 = new Device().decodes(VideoSupport.H264, 4320).decodes(VideoSupport.VP9, 4320);

    Media media = ExtractorJsonParser.parse(root.toString(), watch, android10).media.get(0);
    assertEquals(4, media.variants.size());
    Variant best = media.chooseVariant("best");
    assertEquals(youtubeUrl("313"), best.url);
    assertEquals(youtubeUrl("251-0"), best.audioUrl);
    assertEquals("2160p (WebM)", best.label);
    assertEquals("video/webm", best.mimeType);
    assertEquals((17500 + 140) * 1000, best.bitrate);
    assertEquals(3840, best.width);
    assertEquals(2160, best.height);
    assertEquals("VP9 agent", best.headers.get("User-Agent"));
    Variant qhd = media.variants.get(1);
    assertEquals(youtubeUrl("271"), qhd.url);
    assertEquals(youtubeUrl("251-0"), qhd.audioUrl);
    assertEquals("1440p (WebM)", qhd.label);
    assertEquals("video/webm", qhd.mimeType);
    Variant fullHd = media.variants.get(2);
    assertEquals(youtubeUrl("137"), fullHd.url);
    assertEquals(youtubeUrl("140"), fullHd.audioUrl);
    assertEquals("1080p (MP4)", fullHd.label);
    assertEquals("video/mp4", fullHd.mimeType);
    // The 1080×608 VP9 is also named 480p, which the MP4 already offers.
    assertEquals(youtubeUrl("135"), media.chooseVariant("smallest").url);
    assertEquals("480p (MP4)", media.chooseVariant("smallest").label);

    Media mp4Only =
        ExtractorJsonParser.parse(root.toString(), watch, new Device().decodes(VideoSupport.H264, 4320))
            .media
            .get(0);
    assertEquals(2, mp4Only.variants.size());
    assertEquals(youtubeUrl("137"), mp4Only.variants.get(0).url);
    assertEquals(youtubeUrl("135"), mp4Only.variants.get(1).url);

    // Without H.264, VP9 fills 1080p and 480p too, once per quality name; a JSON null range is SDR.
    for (int i = formats.length() - 1; i >= 0; i--) {
      JSONObject format = formats.getJSONObject(i);
      if (format.optString("vcodec").startsWith("avc1")) formats.remove(i);
      if ("248".equals(format.optString("format_id"))) format.put("dynamic_range", JSONObject.NULL);
    }
    media = ExtractorJsonParser.parse(root.toString(), watch, android10).media.get(0);
    assertEquals(4, media.variants.size());
    Variant vp9FullHd = media.variants.get(2);
    assertEquals(youtubeUrl("248"), vp9FullHd.url);
    assertEquals(youtubeUrl("251-0"), vp9FullHd.audioUrl);
    assertEquals("1080p (WebM)", vp9FullHd.label);
    assertEquals(youtubeUrl("780"), media.chooseVariant("smallest").url);
    assertEquals("480p (WebM)", media.chooseVariant("smallest").label);

    // VP9 is never paired with AAC: no Opus track means no WebM qualities.
    for (int i = formats.length() - 1; i >= 0; i--)
      if ("opus".equals(formats.getJSONObject(i).optString("acodec"))) formats.remove(i);
    assertError(root.toString(), android10, "no version of this video that this phone can save");
  }

  @Test
  public void prefersH264ThenAv1ThenVp9AtEachHeightTheDevicePlays() throws Exception {
    String watch = "https://www.youtube.com/watch?v=KwvyfS9Icyc";
    JSONArray formats =
        new JSONArray()
            .put(youtubeAudio("140", "mp4a.40.2", 129, -1))
            .put(youtubeOpus("251", 140, -1))
            .put(youtubeVideo("136", "mp4", "avc1.4D401F", 720, 2200).put("fps", 30))
            .put(youtubeVideo("137", "mp4", "avc1.640028", 1080, 4300).put("fps", 30))
            .put(
                youtubeVideo("299", "mp4", "avc1.64002a", 1080, 6000)
                    .put("fps", 60)
                    .put("format_note", "1080p60"))
            .put(youtubeVideo("399", "mp4", "av01.0.08M.08", 1080, 2100).put("fps", 30))
            .put(youtubeVideo("400", "mp4", "av01.0.12M.08", 1440, 7300).put("fps", 30))
            .put(
                youtubeVideo("401", "mp4", "av01.0.12M.08.0.110.01.01.01.0", 2160, 14500)
                    .put("fps", 30)
                    .put("dynamic_range", "SDR"))
            .put(youtubeVideo("401-sr", "mp4", "av01.0.12M.08", 2160, 16000).put("fps", 30))
            .put(youtubeVideo("402", "mp4", "av01.0.12M.10", 2160, 20000).put("dynamic_range", "SDR"))
            .put(
                youtubeVideo("701", "mp4", "av01.0.12M.08", 2160, 22000)
                    .put("dynamic_range", "HDR10"))
            .put(youtubeVideo("571", "mp4", "av01.0.16M.08", 4320, 30000).put("fps", 30))
            .put(youtubeVideo("271", "webm", "vp9", 1440, 8700).put("fps", 30))
            .put(youtubeVideo("313", "webm", "vp9", 2160, 17500).put("fps", 30))
            .put(youtubeVideo("272", "webm", "vp9", 4320, 26000).put("fps", 30));
    String json =
        new JSONObject()
            .put("id", "KwvyfS9Icyc")
            .put("title", "A 4K YouTube video")
            .put("extractor_key", "Youtube")
            .put("formats", formats)
            .toString();

    // A recent phone: AV1 to 4K, but only VP9 at 8K.
    Device flagship =
        new Device()
            .decodes(VideoSupport.H264, 1080)
            .decodes(VideoSupport.AV1, 2160)
            .decodes(VideoSupport.VP9, 4320);
    Media media = ExtractorJsonParser.parse(json, watch, flagship).media.get(0);
    assertEquals(5, media.variants.size());
    assertVariant(media.variants.get(0), "272", "251", "4320p (WebM)", "video/webm");
    assertVariant(media.variants.get(1), "401", "140", "2160p (MP4)", "video/mp4");
    assertVariant(media.variants.get(2), "400", "140", "1440p (MP4)", "video/mp4");
    assertVariant(media.variants.get(3), "299", "140", "1080p60 (MP4)", "video/mp4");
    assertVariant(media.variants.get(4), "136", "140", "720p (MP4)", "video/mp4");
    assertTrue(flagship.asked.toString(), flagship.asked.contains("video/av01 7680x4320@30.0"));
    assertTrue(flagship.asked.toString(), flagship.asked.contains("video/avc 1920x1080@60.0"));

    // Software AV1 to 1080p and 30 fps everywhere, like the API 35 emulator.
    Device emulator =
        new Device()
            .decodes(VideoSupport.H264, 1080)
            .decodes(VideoSupport.AV1, 1080)
            .decodes(VideoSupport.VP9, 2160)
            .upTo(30);
    media = ExtractorJsonParser.parse(json, watch, emulator).media.get(0);
    assertEquals(4, media.variants.size());
    assertVariant(media.variants.get(0), "313", "251", "2160p (WebM)", "video/webm");
    assertVariant(media.variants.get(1), "271", "251", "1440p (WebM)", "video/webm");
    assertVariant(media.variants.get(2), "137", "140", "1080p (MP4)", "video/mp4");
    assertVariant(media.variants.get(3), "136", "140", "720p (MP4)", "video/mp4");

    // No AV1 muxing before Android 14: VP9 to 1440p, and 4K is hidden.
    Device android10 =
        new Device().decodes(VideoSupport.H264, 1080).decodes(VideoSupport.VP9, 1440);
    media = ExtractorJsonParser.parse(json, watch, android10).media.get(0);
    assertEquals(3, media.variants.size());
    assertVariant(media.variants.get(0), "271", "251", "1440p (WebM)", "video/webm");
    assertVariant(media.variants.get(1), "299", "140", "1080p60 (MP4)", "video/mp4");
    for (String asked : android10.asked) assertFalse(asked, asked.startsWith(VideoSupport.AV1));

    // An older phone keeps only the H.264 sizes it plays.
    media =
        ExtractorJsonParser.parse(json, watch, new Device().decodes(VideoSupport.H264, 720))
            .media
            .get(0);
    assertEquals(1, media.variants.size());
    assertVariant(media.variants.get(0), "136", "140", "720p (MP4)", "video/mp4");

    assertError(
        json,
        new Device().decodes(VideoSupport.H264, 144),
        "no version of this video that this phone can save");
  }

  private static void assertVariant(
      Variant variant, String video, String audio, String label, String mimeType) {
    assertEquals(label, youtubeUrl(video), variant.url);
    assertEquals(label, youtubeUrl(audio), variant.audioUrl);
    assertEquals(label, variant.label);
    assertEquals(label, mimeType, variant.mimeType);
  }

  @Test
  public void allowsExplicitGifWithoutAudio() throws Exception {
    JSONObject root =
        format("https://cdn.example/gif.mp4", "mp4", 480, 0)
            .put("is_gif", true)
            .put("acodec", "none");
    Post post = ExtractorJsonParser.parse(root.toString(), SOURCE);
    assertEquals("gif", post.media.get(0).kind);
    assertEquals("video/mp4", post.media.get(0).variants.get(0).mimeType);
  }

  @Test
  public void acceptsProgressiveFormatsWhenExtractorOmitsCodecNames() throws Exception {
    JSONObject root =
        new JSONObject()
            .put("url", "https://cdn.example/progressive.mp4")
            .put("ext", "mp4")
            .put("protocol", "https")
            .put("width", 1280)
            .put("height", 720);
    Post post = ExtractorJsonParser.parse(root.toString(), SOURCE);
    assertEquals(
        "https://cdn.example/progressive.mp4", post.media.get(0).chooseVariant("best").url);
  }

  @Test
  public void acceptsCompleteSilentInstagramVideoButStillRejectsDashTracks() throws Exception {
    JSONObject silent =
        new JSONObject()
            .put("url", "https://cdn.example/silent.mp4")
            .put("ext", "mp4")
            .put("protocol", "https")
            .put("acodec", "none");
    Post post = ExtractorJsonParser.parse(silent.toString(), SOURCE);
    assertEquals("video", post.media.get(0).kind);
    assertEquals("https://cdn.example/silent.mp4", post.media.get(0).variants.get(0).url);
    silent.put("vcodec", "h264").put("extractor_key", "Instagram");
    post = ExtractorJsonParser.parse(silent.toString(), SOURCE);
    assertEquals("https://cdn.example/silent.mp4", post.media.get(0).variants.get(0).url);
    JSONObject carousel =
        new JSONObject()
            .put("extractor_key", "Instagram")
            .put("entries", new JSONArray().put(silent));
    silent.remove("extractor_key");
    assertEquals(
        "https://cdn.example/silent.mp4",
        ExtractorJsonParser.parse(carousel.toString(), SOURCE).media.get(0).variants.get(0).url);
    silent.put("extractor_key", "Generic");
    assertError(carousel.toString(), "no downloadable");
    silent.put("extractor_key", "Instagram");
    assertError(
        new JSONObject(silent.toString()).put("container", "mp4_dash").toString(),
        "streaming-only");
    assertError(
        new JSONObject(silent.toString()).put("format_id", "dash-123v").toString(),
        "streaming-only");
    assertError(
        new JSONObject(silent.toString()).put("fragments", new JSONArray()).toString(),
        "streaming-only");
  }

  @Test
  public void rejectsStreamingOnlyInsecureAndCredentialedMedia() throws Exception {
    String[] urls = {
      "https://cdn.example/stream.m3u8",
      "https://cdn.example/manifest.mpd",
      "http://cdn.example/file.mp4",
      "https://user:secret@cdn.example/file.mp4"
    };
    for (String url : urls) assertError(format(url, "mp4", 720, 2000).toString(), "streaming-only");
    assertError(
        format("https://cdn.example/file.mp4", "mp4", 720, 2000).put("acodec", "none").toString(),
        "streaming-only");
    assertError("not json", "unreadable");
  }

  @Test
  public void variantsRejectHeaderInjectionAndAuthenticationHeaders() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("User-Agent", "Agent\r\nCookie: bad");
    headers.put("Referer", "https://user:secret@example.com/");
    headers.put("Cookie", "secret");
    headers.put("Authorization", "secret");
    assertTrue(
        new Variant("https://cdn.example/a.mp4", "Video", "video/mp4", 0, 0, 0, headers)
            .headers.isEmpty());
    headers.put("User-Agent", "Safe agent");
    headers.put("Referer", SOURCE);
    Variant variant =
        new Variant("https://cdn.example/a.mp4", "Video", "video/mp4", 0, 0, 0, headers);
    assertEquals(2, variant.headers.size());
    try {
      variant.headers.clear();
      fail("Headers should be immutable");
    } catch (UnsupportedOperationException expected) {
      /* expected */
    }
  }

  @Test
  public void keepsEachEntrysOwnPreviewAndDuration() throws Exception {
    JSONObject first =
        format("https://cdn.example/one.mp4", "mp4", 720, 1000)
            .put("id", "one")
            .put("thumbnail", "https://images.cdn.example/one.jpg")
            .put("duration", 7.5);
    JSONObject second =
        format("https://cdn.example/two.mp4", "mp4", 720, 1000).put("id", "two");
    JSONObject root =
        new JSONObject()
            .put("title", "Carousel")
            .put("thumbnail", "https://images.cdn.example/parent.jpg")
            .put("duration", 99)
            .put("entries", new JSONArray().put(first).put(second));
    Post post = ExtractorJsonParser.parse(root.toString(), SOURCE);
    assertEquals("https://images.cdn.example/one.jpg", post.media.get(0).thumbnail);
    assertEquals(7500, post.media.get(0).durationMs);
    assertNull(post.media.get(1).thumbnail);
    assertEquals(0, post.media.get(1).durationMs);
  }

  @Test
  public void rejectsUnsafePreviewsAndInvalidDurations() throws Exception {
    Object[][] cases = {
      {"http://images.cdn.example/a.jpg", -1},
      {"https://127.0.0.1/a.jpg", 0},
      {"https://[::1]/a.jpg", "12"},
      {"https://localhost/a.jpg", true},
      {"https://images.cdn.example:8443/a.jpg", JSONObject.NULL}
    };
    for (Object[] row : cases) {
      JSONObject info =
          format("https://cdn.example/a.mp4", "mp4", 720, 1000)
              .put("thumbnail", row[0])
              .put("duration", row[1]);
      Media media = ExtractorJsonParser.parse(info.toString(), SOURCE).media.get(0);
      assertNull(String.valueOf(row[0]), media.thumbnail);
      assertEquals(0, media.durationMs);
    }
  }

  private static JSONObject format(String url, String extension, int height, int kilobits)
      throws Exception {
    return new JSONObject()
        .put("url", url)
        .put("ext", extension)
        .put("height", height)
        .put("width", height * 16 / 9)
        .put("vcodec", "h264")
        .put("acodec", "aac")
        .put("protocol", "https")
        .put("tbr", kilobits);
  }

  private static JSONObject telegramFormat(String name) throws Exception {
    return new JSONObject()
        .put("url", "https://cdn4.telesco.pe/file/" + name)
        .put("ext", "mp4")
        .put("protocol", "https")
        .put("http_headers", new JSONObject().put("User-Agent", "Synthetic agent"));
  }

  private static String youtubeUrl(String formatId) {
    return "https://rr1.googlevideo.example/videoplayback?itag=" + formatId;
  }

  private static JSONObject youtubeVideo(
      String formatId, String extension, String codec, int height, int kilobits) throws Exception {
    return format(youtubeUrl(formatId), extension, height, kilobits)
        .put("format_id", formatId)
        .put("container", extension + "_dash")
        .put("vcodec", codec)
        .put("acodec", "none");
  }

  private static JSONObject youtubeAudio(
      String formatId, String codec, int kilobits, int languagePreference) throws Exception {
    return format(youtubeUrl(formatId), "m4a", 0, kilobits)
        .put("format_id", formatId)
        .put("container", "m4a_dash")
        .put("vcodec", "none")
        .put("acodec", codec)
        .put("language_preference", languagePreference);
  }

  private static JSONObject youtubeOpus(String formatId, int kilobits, int languagePreference)
      throws Exception {
    return youtubeAudio(formatId, "opus", kilobits, languagePreference)
        .put("ext", "webm")
        .put("container", "webm_dash");
  }

  private static void assertError(String json, String expected) {
    assertError(json, VideoSupport.ANY, expected);
  }

  private static void assertError(String json, VideoSupport device, String expected) {
    try {
      ExtractorJsonParser.parse(json, SOURCE, device);
      fail("Expected error containing: " + expected);
    } catch (IOException error) {
      assertTrue(English.message(error), English.message(error).contains(expected));
    }
  }

  /** Merges only the codecs it decodes, each up to a height and frame rate. */
  private static final class Device implements VideoSupport {
    final Map<String, Integer> tallest = new LinkedHashMap<>();
    final List<String> asked = new ArrayList<>();
    double fastest = Double.MAX_VALUE;

    Device decodes(String video, int height) {
      tallest.put(video, height);
      return this;
    }

    Device upTo(double fps) {
      fastest = fps;
      return this;
    }

    @Override
    public boolean canMerge(String container, String video, String audio) {
      assertEquals(VP9.equals(video) ? WEBM : MP4, container);
      assertEquals(VP9.equals(video) ? OPUS : AAC, audio);
      return tallest.containsKey(video);
    }

    @Override
    public boolean canDecode(String video, int width, int height, double fps) {
      asked.add(video + " " + width + "x" + height + "@" + fps);
      Integer max = tallest.get(video);
      return max != null && height <= max && fps <= fastest;
    }
  }
}
