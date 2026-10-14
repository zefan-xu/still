// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ExtractorJsonParserTest {
  private static final String SOURCE = "https://www.instagram.com/reel/Abc123/";

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

  private static void assertError(String json, String expected) {
    try {
      ExtractorJsonParser.parse(json, SOURCE);
      fail("Expected error containing: " + expected);
    } catch (IOException error) {
      assertTrue(error.getMessage(), error.getMessage().contains(expected));
    }
  }
}
