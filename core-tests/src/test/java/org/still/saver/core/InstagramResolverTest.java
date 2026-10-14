// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class InstagramResolverTest {
  private static final String CDN = "https://scontent-sjc6-1.cdninstagram.com/v/t51/";

  @Test
  public void carouselPhotosUseTheUncroppedOriginalAndALightPreview() throws Exception {
    List<String> requested = new ArrayList<>();
    String html = page(record("DePlAyED5AA", carousel(photo("a"), photo("b"))));
    InstagramResolver resolver =
        new InstagramResolver(
            (url, headers) -> {
              requested.add(url);
              return new PublicPages.Reply(200, html, null);
            });
    Post post = resolver.resolve("Look https://www.instagram.com/p/DePlAyED5AA/?rpxt=tracking");
    assertEquals("https://www.instagram.com/p/DePlAyED5AA/", requested.get(0));
    assertEquals("https://www.instagram.com/p/DePlAyED5AA/", post.sourceUrl);
    assertEquals("DePlAyED5AA", post.id);
    assertEquals("@demo", post.author);
    assertEquals("Weekly deals", post.title);
    assertEquals(2, post.media.size());
    Media first = post.media.get(0);
    assertEquals("photo", first.kind);
    assertEquals(1, first.variants.size());
    assertEquals(CDN + "a_n.jpg?stp=dst-jpg_e35_tt6", first.variants.get(0).url);
    assertEquals("image/jpeg", first.variants.get(0).mimeType);
    assertEquals("1080 × 1350", first.variants.get(0).label);
    assertEquals(CDN + "a_n.jpg?stp=dst-jpg_e35_p480x480_tt6", first.thumbnail);
    assertEquals(CDN + "b_n.jpg?stp=dst-jpg_e35_tt6", post.media.get(1).variants.get(0).url);
  }

  @Test
  public void reelUsesItsProgressiveMp4WithoutGuessingItsSize() throws Exception {
    JSONObject reel = photo("poster").put("media_type", 2);
    String mp4 = "https://scontent-sjc6-1.cdninstagram.com/o1/v/t2/clip.mp4?efg=x";
    reel.put(
        "video_versions",
        new JSONArray()
            .put(new JSONObject().put("type", 101).put("url", mp4))
            .put(new JSONObject().put("type", 102).put("url", mp4))
            .put(new JSONObject().put("type", 103).put("url", "https://evil.example/clip.mp4")));
    Post post = parse("Chunk8-jurw", reel.put("caption", JSONObject.NULL));
    assertEquals(1, post.media.size());
    Media video = post.media.get(0);
    assertEquals("video", video.kind);
    assertEquals(1, video.variants.size());
    assertEquals(mp4, video.variants.get(0).url);
    assertEquals("MP4", video.variants.get(0).label);
    assertEquals(0, video.variants.get(0).height);
    assertEquals(CDN + "poster_n.jpg?stp=dst-jpg_e35_p480x480_tt6", video.thumbnail);
    assertEquals("Instagram post by @demo", post.title);
  }

  @Test
  public void onlyTheRequestedPostsRecordIsUsed() throws Exception {
    String html =
        page(record("Other", carousel(photo("wrong")))) + page(record("Wanted", photo("right")));
    Post post = InstagramResolver.parse(html, "https://www.instagram.com/p/Wanted/", "Wanted");
    assertEquals(CDN + "right_n.jpg?stp=dst-jpg_e35_tt6", post.media.get(0).variants.get(0).url);
  }

  @Test
  public void reportsGatedMissingAndUnsafeMediaPlainly() throws Exception {
    JSONObject gated = new JSONObject().put("code", "Gated").put("gating_ruling", "login");
    assertError(page(wrapper(gated)), "Gated", "requires login");
    assertError("<html><body>Log in to Instagram</body></html>", "Gone", "did not include");
    JSONObject unsafe = photo("x");
    unsafe
        .getJSONObject("image_versions2")
        .put("candidates", new JSONArray().put(new JSONObject().put("url", "https://x.test/a.jpg")));
    assertError(page(record("Unsafe", unsafe)), "Unsafe", "no downloadable");
  }

  @Test
  public void redirectsStayOnThePostAndTheLoginWallFailsFast() throws Exception {
    assertResolveError(
        new PublicPages.Reply(302, "", "https://www.instagram.com/accounts/login/?next=x"),
        "log in");
    assertResolveError(
        new PublicPages.Reply(302, "", "https://evil.example/p/AbC/"), "redirected away");
    assertResolveError(new PublicPages.Reply(429, "", null), "limiting anonymous");
    assertResolveError(new PublicPages.Reply(404, "", null), "deleted");

    List<String> requested = new ArrayList<>();
    String html = page(record("AbC", photo("r")));
    InstagramResolver resolver =
        new InstagramResolver(
            (url, headers) -> {
              requested.add(url);
              return requested.size() == 1
                  ? new PublicPages.Reply(302, "", "/reel/AbC/")
                  : new PublicPages.Reply(200, html, null);
            });
    assertEquals(1, resolver.resolve("https://instagram.com/reel/AbC").media.size());
    assertEquals("https://www.instagram.com/reel/AbC/", requested.get(1));
  }

  @Test
  public void acceptsOnlyInstagramPostLinks() throws Exception {
    assertArrayEquals(
        new String[] {"https://www.instagram.com/reel/AbC_1-/", "AbC_1-"},
        InstagramResolver.link("https://m.instagram.com/demo/reels/AbC_1-/?igsh=x"));
    assertTrue(InstagramResolver.handles("https://www.instagram.com/p/AbC/"));
    assertFalse(InstagramResolver.handles("https://www.threads.com/@a/post/AbC"));
    try {
      InstagramResolver.link("https://www.instagram.com/stories/demo/123/");
      fail("Stories are not posts");
    } catch (IOException expected) {
      // Expected.
    }
  }

  private static Post parse(String code, JSONObject media) throws Exception {
    return InstagramResolver.parse(
        page(record(code, media)), "https://www.instagram.com/p/" + code + "/", code);
  }

  private static void assertError(String html, String code, String message) {
    try {
      InstagramResolver.parse(html, "https://www.instagram.com/p/" + code + "/", code);
      fail("Expected " + message);
    } catch (IOException e) {
      assertTrue(English.message(e), English.message(e).contains(message));
    }
  }

  private static void assertResolveError(PublicPages.Reply reply, String message) {
    try {
      new InstagramResolver((url, headers) -> reply).resolve("https://www.instagram.com/p/AbC/");
      fail("Expected " + message);
    } catch (IOException e) {
      assertTrue(English.message(e), English.message(e).contains(message));
    }
  }

  private static JSONObject photo(String name) throws Exception {
    JSONArray candidates =
        new JSONArray()
            .put(
                new JSONObject()
                    .put("url", CDN + name + "_n.jpg?stp=dst-jpg_e35_tt6")
                    .put("width", 1080)
                    .put("height", 1350))
            .put(new JSONObject().put("url", CDN + name + "_n.jpg?stp=dst-jpg_e35_p480x480_tt6"))
            .put(new JSONObject().put("url", CDN + name + "_n.jpg?stp=c0.1_dst-jpg_s150x150_tt6"));
    return new JSONObject()
        .put("media_type", 1)
        .put("original_width", 1080)
        .put("original_height", 1350)
        .put("image_versions2", new JSONObject().put("candidates", candidates));
  }

  private static JSONObject carousel(JSONObject... items) throws Exception {
    JSONArray children = new JSONArray();
    for (JSONObject item : items) children.put(item);
    return new JSONObject().put("media_type", 8).put("carousel_media", children);
  }

  private static JSONObject record(String code, JSONObject media) throws Exception {
    media.put("user", new JSONObject().put("username", "demo"));
    if (!media.has("caption")) media.put("caption", new JSONObject().put("text", "Weekly deals"));
    return wrapper(new JSONObject().put("code", code).put("if_not_gated_logged_out", media));
  }

  /** Relay data nests the record deep inside "require" arrays; mimic a little of that. */
  private static JSONObject wrapper(JSONObject record) throws Exception {
    JSONObject data = new JSONObject().put("data", new JSONObject().put("xig_polaris_media", record));
    return new JSONObject()
        .put(
            "require",
            new JSONArray()
                .put(new JSONArray().put("RelayPrefetchedStreamCache").put(new JSONObject()
                    .put("__bbox", new JSONObject().put("result", data)))));
  }

  private static String page(JSONObject sjs) {
    return "<script type=\"application/json\" data-content-len=\"1\" data-sjs>"
        + sjs
        + "</script><script>other()</script>";
  }
}
