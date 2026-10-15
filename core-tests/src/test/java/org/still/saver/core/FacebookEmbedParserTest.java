// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.*;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class FacebookEmbedParserTest {
  private static final String ID = "106560053808006";
  private static final String SOURCE = "https://www.facebook.com/video.php?v=" + ID;
  private static final String SD = "https://video.example.fbcdn.net/a.mp4?token=synthetic&x=1";
  private static final String HD = "https://video.example.fbcdn.net/z.mp4";

  @Test
  public void parsesPublicVideoConfigBeforeGenericPlayerErrorText() throws Exception {
    JSONObject row = new JSONObject().put("video_id", ID).put("sd_src", SD);
    Post post = FacebookEmbedParser.parse(config(row) + genericPlayerError(), SOURCE);
    assertEquals(ID, post.id);
    assertEquals(SOURCE, post.sourceUrl);
    assertEquals(1, post.media.size());
    Variant variant = post.media.get(0).chooseVariant("best");
    assertEquals(SD, variant.url);
    assertEquals("SD MP4", variant.label);
    assertEquals("video/mp4", variant.mimeType);
    assertTrue(variant.headers.isEmpty());
    assertEquals("", variant.scopedCookies);
  }

  @Test
  public void preservesLegacyQualityWithoutAssigningSharedVideoDimensions() throws Exception {
    JSONObject record =
        new JSONObject()
            .put("id", ID)
            .put("width", 1920)
            .put("height", 1080)
            .put("bitrate", 9000000)
            .put("sd_src", SD)
            .put("hd_src", HD);
    Media media = FacebookEmbedParser.parse(json(record), SOURCE).media.get(0);
    assertEquals(2, media.variants.size());
    assertEquals(HD, media.chooseVariant("best").url);
    assertEquals(SD, media.chooseVariant("smallest").url);
    assertEquals("HD MP4", media.chooseVariant("best").label);
    for (Variant variant : media.variants) {
      assertEquals(0, variant.width);
      assertEquals(0, variant.height);
      assertEquals(0, variant.bitrate);
    }
  }

  @Test
  public void supportsNativeLegacyAndProgressiveFieldsForOnlyTheTargetVideo() throws Exception {
    JSONObject target =
        new JSONObject()
            .put("id", ID)
            .put("__typename", "Video")
            .put("title", new JSONObject().put("text", "A {quoted} title"))
            .put("owner", new JSONObject().put("name", "Public author"))
            .put("videoDeliveryLegacyFields", new JSONObject().put("browser_native_sd_url", SD))
            .put(
                "videoDeliveryResponseFragment",
                new JSONObject()
                    .put(
                        "videoDeliveryResponseResult",
                        new JSONObject()
                            .put(
                                "progressive_urls",
                                new JSONArray()
                                    .put(
                                        new JSONObject()
                                            .put("progressive_url", HD)
                                            .put(
                                                "metadata",
                                                new JSONObject()
                                                    .put("width", 1280)
                                                    .put("height", 720)
                                                    .put("bitrate", 2000000))))));
    JSONObject recommended =
        new JSONObject()
            .put("id", "999")
            .put("browser_native_hd_url", "https://video.example.fbcdn.net/recommendation.mp4");
    Post post =
        FacebookEmbedParser.parse(
            json(new JSONObject().put("nodes", new JSONArray().put(recommended).put(target))), SOURCE);
    assertEquals("A {quoted} title", post.title);
    assertEquals("Public author", post.author);
    assertEquals(2, post.media.get(0).variants.size());
    Variant best = post.media.get(0).chooseVariant("best");
    assertEquals(HD, best.url);
    assertEquals("720p MP4", best.label);
    assertEquals(1280, best.width);
    assertEquals(2000000, best.bitrate);
  }

  @Test
  public void rejectsConflictingNestedAndRecommendedVideoIds() throws Exception {
    JSONObject unrelated = new JSONObject().put("video_id", "999").put("sd_src", SD);
    JSONObject malformedId = new JSONObject().put("video_id", "not-a-video").put("sd_src", SD);
    JSONObject root =
        new JSONObject()
            .put("video_id", ID)
            .put("videoData", new JSONArray().put(unrelated).put(malformedId));
    assertOrdinaryError(config(root), SOURCE);
    assertOrdinaryError(json(new JSONObject().put("id", "999").put("playable_url", SD)), SOURCE);
    assertOrdinaryError(
        json(new JSONObject().put("id", ID).put("video_id", "999").put("sd_src", SD)), SOURCE);
    assertOrdinaryError(json(new JSONObject().put("sd_src", SD)), SOURCE);
  }

  @Test
  public void handlesEscapedJsonStringsAndMultipleServerScripts() throws Exception {
    JSONObject video = new JSONObject().put("video_id", ID).put("sd_src", SD);
    String script = config(video).replace("/", "\\/");
    // Only JSON strings escape slashes; HTML closing tags must remain valid.
    script = script.replace("<\\/script>", "</script>");
    Post post =
        FacebookEmbedParser.parse(
            "<script>handleServerJS({broken});</script>"
                + script
                + "<script>s.handle({\"message\":\"Video Unavailable\"});</script>",
            SOURCE);
    assertEquals(SD, post.media.get(0).chooseVariant("best").url);
  }

  @Test
  public void distinguishesExplicitPublicUnavailableFromExtractionFailure() throws Exception {
    try {
      FacebookEmbedParser.parse(unavailable(), SOURCE);
      fail("Expected the explicit public-unavailable diagnosis");
    } catch (FacebookEmbedParser.UnavailableException expected) {
      assertTrue(English.message(expected).contains("public viewers"));
    }
    assertOrdinaryError("<title>Facebook</title>", SOURCE);
    assertOrdinaryError("<p>Help for a Video Unavailable message</p>", SOURCE);
    assertOrdinaryError("<script>var message = 'Video Unavailable';</script>", SOURCE);
    assertOrdinaryError("<div hidden>Video Unavailable</div>", SOURCE);
    assertOrdinaryError("<div aria-hidden='true'>Video Unavailable</div>", SOURCE);
    assertOrdinaryError("<div class='hidden_elem'>Video Unavailable</div>", SOURCE);
    assertOrdinaryError(genericPlayerError(), SOURCE);
    assertOrdinaryError(
        json(
                new JSONObject()
                    .put("id", ID)
                    .put("browser_native_sd_url", "https://video.example.fbcdn.net/video.m3u8"))
            + genericPlayerError(),
        SOURCE);
  }

  @Test
  public void explicitUnavailableCannotBeOverriddenByAnUnrelatedVideo() throws Exception {
    String html =
        unavailable()
            + json(new JSONObject().put("id", "999").put("browser_native_hd_url", HD));
    try {
      FacebookEmbedParser.parse(html, SOURCE);
      fail("An unrelated recommendation must not make this target playable");
    } catch (FacebookEmbedParser.UnavailableException expected) {
      /* expected */
    }
  }

  @Test
  public void acceptsOnlyCompleteHttpsMp4FromRecognizedMediaHosts() throws Exception {
    String[] rejected = {
      "http://video.example.fbcdn.net/file.mp4",
      "https://user:secret@video.example.fbcdn.net/file.mp4",
      "https://video.example.fbcdn.net:8443/file.mp4",
      "https://video.example.fbcdn.net/file.m3u8",
      "https://video.example.fbcdn.net/file.mpd",
      "https://video.example.fbcdn.net/file",
      "https://video.example.fbcdn.net.evil.example/file.mp4",
      "https://cdn.example/file.mp4"
    };
    for (String url : rejected) {
      assertOrdinaryError(json(new JSONObject().put("id", ID).put("sd_src", url)), SOURCE);
    }
    JSONObject split = new JSONObject().put("id", ID).put("progressive_url", HD);
    assertOrdinaryError(json(new JSONObject(split.toString()).put("vcodec", "none")), SOURCE);
    assertOrdinaryError(json(new JSONObject(split.toString()).put("container", "mp4_dash")), SOURCE);
    assertOrdinaryError(json(new JSONObject(split.toString()).put("fragments", new JSONArray())), SOURCE);
    for (String host : new String[] {"video.example.fbcdn.net", "video.example.cdninstagram.com", "lookaside.fbsbx.com"}) {
      String url = "https://" + host + "/file.mp4";
      assertEquals(
          url,
          FacebookEmbedParser.parse(json(new JSONObject().put("id", ID).put("playable_url", url)), SOURCE)
              .media.get(0).variants.get(0).url);
    }
  }

  @Test
  public void acceptsDocumentedCompleteSilentProgressiveVideo() throws Exception {
    JSONObject silent =
        new JSONObject()
            .put("id", ID)
            .put("progressive_url", HD)
            .put("vcodec", "h264")
            .put("acodec", "none");
    Post post = FacebookEmbedParser.parse(json(silent), SOURCE);
    assertEquals(HD, post.media.get(0).chooseVariant("best").url);
    assertEquals("video", post.media.get(0).kind);
    assertOrdinaryError(json(new JSONObject(silent.toString()).put("fragments", new JSONArray())), SOURCE);
    assertOrdinaryError(json(new JSONObject(silent.toString()).put("container", "mp4_dash")), SOURCE);
    assertOrdinaryError(json(new JSONObject(silent.toString()).put("vcodec", "none")), SOURCE);
  }

  @Test
  public void capsAndDeduplicatesPlayableVariants() throws Exception {
    JSONArray progressive = new JSONArray();
    for (int i = 0; i < 25; i++) {
      progressive.put(
          new JSONObject().put("progressive_url", "https://video.example.fbcdn.net/" + i + ".mp4"));
    }
    progressive.put(progressive.get(0));
    Post post =
        FacebookEmbedParser.parse(
            json(new JSONObject().put("id", ID).put("progressive_urls", progressive)), SOURCE);
    assertEquals(1, post.media.size());
    assertEquals(20, post.media.get(0).variants.size());
    Post duplicate =
        FacebookEmbedParser.parse(
            json(new JSONObject().put("id", ID).put("sd_src", SD).put("hd_src", SD)), SOURCE);
    assertEquals(1, duplicate.media.get(0).variants.size());
    assertEquals("HD MP4", duplicate.media.get(0).variants.get(0).label);
  }

  @Test
  public void refusesOversizedAndDeeplyNestedResponsesWithoutFalseUnavailableDiagnosis() throws Exception {
    StringBuilder large = new StringBuilder();
    for (int i = 0; i <= 2 * 1024 * 1024; i++) large.append('x');
    assertOrdinaryError(large.toString(), SOURCE);
    StringBuilder nested = new StringBuilder("<script data-sjs>");
    for (int i = 0; i < 70; i++) nested.append('[');
    nested.append("{}");
    for (int i = 0; i < 70; i++) nested.append(']');
    nested.append("</script>").append(unavailable());
    assertOrdinaryError(nested.toString(), SOURCE);
  }

  @Test
  public void extractsOnlyKnownNumericIdsFromSupportedFacebookSources() {
    for (String source :
        new String[] {
          SOURCE,
          "https://facebook.com/video/video.php?v=" + ID,
          "https://m.facebook.com/watch/?v=" + ID + "&_rdr",
          "https://mbasic.facebook.com/reel/" + ID,
          "https://www.facebook.com/person/videos/title/" + ID + "/",
          "https://www.facebook.com/person/posts/" + ID,
          "https://www.facebook.com/groups/group/posts/" + ID,
          "https://www.facebook.com/permalink.php?story_fbid=" + ID
        }) assertEquals(source, ID, FacebookEmbedParser.videoId(source));
    for (String source :
        new String[] {
          "https://www.facebook.com/share/v/" + ID,
          "https://fb.watch/" + ID,
          "https://www.facebook.com/person/posts/pfbidExample",
          "https://evil.example/video.php?v=" + ID,
          "https://facebook.com.evil.example/video.php?v=" + ID,
          "http://www.facebook.com/video.php?v=" + ID,
          "https://user:secret@www.facebook.com/video.php?v=" + ID,
          "https://www.facebook.com/video.php?v=" + ID + "&video_id=999",
          "https://www.facebook.com/video.php?v=" + ID + "&video_id=invalid",
          "https://www.facebook.com/video.php?v=0",
          null
        }) assertNull(source, FacebookEmbedParser.videoId(source));
  }

  @Test
  public void takesPreviewAndDurationFromTheTargetRecordOnly() throws Exception {
    String preview = "https://scontent.example.fbcdn.net/poster.jpg";
    JSONObject recommended =
        new JSONObject()
            .put("id", "999")
            .put("sd_src", "https://video.example.fbcdn.net/other.mp4")
            .put(
                "preferred_thumbnail",
                new JSONObject()
                    .put("image", new JSONObject().put("uri", "https://x.fbcdn.net/other.jpg")));
    JSONObject target =
        new JSONObject()
            .put("id", ID)
            .put("sd_src", SD)
            .put("playable_duration_in_ms", 4200)
            .put(
                "preferred_thumbnail",
                new JSONObject().put("image", new JSONObject().put("uri", preview)));
    Media media =
        FacebookEmbedParser.parse(json(recommended) + json(target), SOURCE).media.get(0);
    assertEquals(preview, media.thumbnail);
    assertEquals(4200, media.durationMs);

    JSONObject legacy =
        new JSONObject()
            .put("id", ID)
            .put("sd_src", SD)
            .put("length_in_second", 3.5)
            .put("thumbnailImage", new JSONObject().put("uri", "https://images.example/a.jpg"))
            .put("first_frame_thumbnail", preview);
    media = FacebookEmbedParser.parse(json(legacy), SOURCE).media.get(0);
    assertEquals(preview, media.thumbnail);
    assertEquals(3500, media.durationMs);

    JSONObject none = new JSONObject().put("id", ID).put("sd_src", SD);
    media = FacebookEmbedParser.parse(json(recommended) + json(none), SOURCE).media.get(0);
    assertNull(media.thumbnail);
    assertEquals(0, media.durationMs);
  }

  private static String config(JSONObject video) throws Exception {
    JSONObject wrapper =
        new JSONObject()
            .put("video_id", ID)
            .put("videoData", new JSONArray().put(video));
    JSONObject server =
        new JSONObject()
            .put(
                "instances",
                new JSONArray()
                    .put(
                        new JSONArray()
                            .put("instance")
                            .put(new JSONArray().put("VideoConfig"))
                            .put(new JSONArray().put(wrapper))));
    return "<script>handleServerJS(" + server + ");</script>";
  }

  private static String json(JSONObject object) {
    return "<script data-sjs>" + object + "</script>";
  }

  private static String unavailable() {
    return "<div class='pluginSkinLight pluginFontHelvetica'><div class='_2gn_'>"
        + "<div class='_2go0'><div class='_3i0o _50f7'>Video Unavailable</div>"
        + "<div class='_3i0p'>This video may no longer exist, or you don't have permission to view it."
        + "</div></div></div></div>";
  }

  private static String genericPlayerError() {
    return "<div class='pluginSkinLight pluginFontHelvetica'><div class='_53j5'>"
        + "<div class='_2gn_ _5sjk _1jto _3htz'><div class='_2go0'>"
        + "<div class='_3i0o _50f7'><span>Video unavailable</span></div>"
        + "<div class='_3i0p'><span>Sorry, this video could not be played.</span>"
        + "</div></div></div></div></div>";
  }

  private static void assertOrdinaryError(String html, String source) {
    try {
      FacebookEmbedParser.parse(html, source);
      fail("Expected an ordinary parser error");
    } catch (IOException expected) {
      assertFalse(expected.getMessage(), expected instanceof FacebookEmbedParser.UnavailableException);
    }
  }
}
