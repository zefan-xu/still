// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Photo posts on platforms whose bundled extractor reads only videos. */
public class PhotoResolversTest {
  private static final String BSKY_FULL =
      "https://cdn.bsky.app/img/feed_fullsize/plain/did:plc:abc/bafkrei1@jpeg";
  private static final String BSKY_THUMB =
      "https://cdn.bsky.app/img/feed_thumbnail/plain/did:plc:abc/bafkrei1@jpeg";

  @Test
  public void blueskyPhotosUseFullSizeImagesAndVideosGoToTheExtractor() throws Exception {
    List<String> requested = new ArrayList<>();
    String images =
        bluesky("app.bsky.embed.images#view", blueskyImages(BSKY_FULL, "https://evil.example/img/a@jpeg"));
    Post post =
        new BlueskyResolver(
                (url, headers) -> {
                  requested.add(url);
                  return new PublicPages.Reply(200, images, null);
                })
            .resolve("https://bsky.app/profile/bsky.app/post/3mv3shqdfuc2e");
    assertEquals(
        BlueskyResolver.API
            + "at%3A%2F%2Fbsky.app%2Fapp.bsky.feed.post%2F3mv3shqdfuc2e&depth=0&parentHeight=0",
        requested.get(0));
    assertEquals("Hello", post.title);
    assertEquals("@bsky.app", post.author);
    assertEquals("https://bsky.app/profile/bsky.app/post/3mv3shqdfuc2e", post.sourceUrl);
    assertEquals(1, post.media.size()); // The second URL is not on Bluesky's image CDN path.
    assertEquals(BSKY_FULL, post.media.get(0).variants.get(0).url);
    assertEquals("image/jpeg", post.media.get(0).variants.get(0).mimeType);
    assertEquals(BSKY_THUMB, post.media.get(0).thumbnail);

    JSONObject quoteWithPhotos =
        new JSONObject()
            .put("$type", "app.bsky.embed.recordWithMedia#view")
            .put("media", embed("app.bsky.embed.images#view", blueskyImages(BSKY_FULL)));
    assertEquals(
        1, BlueskyResolver.parse(bluesky(quoteWithPhotos), "r").media.size());
    assertNull(BlueskyResolver.parse(bluesky("app.bsky.embed.video#view", null), "r"));
    try {
      BlueskyResolver.parse(bluesky("app.bsky.embed.external#view", null), "r");
      fail("A link card has nothing to save");
    } catch (IOException e) {
      assertTrue(English.message(e).contains("no photo or video"));
    }
    try {
      BlueskyResolver.parse(
          new JSONObject()
              .put("thread", new JSONObject().put("$type", "app.bsky.feed.defs#notFoundPost"))
              .toString(),
          "r");
      fail("Deleted posts are unavailable");
    } catch (IOException e) {
      assertTrue(English.message(e).contains("deleted"));
    }
  }

  @Test
  public void pinterestImagePinsUseTheOriginalAndVideoPinsGoToTheExtractor() throws Exception {
    String original = "https://i.pinimg.com/originals/21/fc/53/21fc.jpg";
    JSONObject pin =
        new JSONObject()
            .put("title", "")
            .put("description", "Chewy cookies")
            .put("pinner", new JSONObject().put("username", "baker"))
            .put("images", images("orig", original));
    List<String> requested = new ArrayList<>();
    Post post =
        new PinterestResolver(
                (url, headers) -> {
                  requested.add(url);
                  assertEquals("www/pin/[id].js", headers.get("X-Pinterest-PWS-Handler"));
                  return new PublicPages.Reply(200, pinterest(pin), null);
                })
            .resolve("https://in.pinterest.com/pin/chewy-cookies--560276009885574297/");
    assertTrue(requested.get(0).startsWith(PinterestResolver.API));
    assertTrue(requested.get(0).contains("560276009885574297"));
    assertEquals("Chewy cookies", post.title);
    assertEquals("@baker", post.author);
    assertEquals("https://www.pinterest.com/pin/560276009885574297/", post.sourceUrl);
    assertEquals(original, post.media.get(0).variants.get(0).url);
    assertEquals("https://i.pinimg.com/474x/21/fc/53/21fc.jpg", post.media.get(0).thumbnail);

    JSONObject slots =
        new JSONObject()
            .put(
                "carousel_slots",
                new JSONArray()
                    .put(new JSONObject().put("images", images("originals", original)))
                    .put(
                        new JSONObject()
                            .put("images", images("originals", original.replace("21fc", "22ab")))));
    assertEquals(2, PinterestResolver.parse(pinterest(pin.put("carousel_data", slots)), "1").media.size());
    JSONObject video = new JSONObject().put("videos", new JSONObject().put("video_list", new JSONObject()));
    assertNull(PinterestResolver.parse(pinterest(video), "1"));
  }

  @Test
  public void pinterestShortLinksFollowOnlyPinterestRedirects() throws Exception {
    JSONObject pin = new JSONObject().put("images", images("orig", "https://i.pinimg.com/originals/a.png"));
    List<String> requested = new ArrayList<>();
    PinterestResolver resolver =
        new PinterestResolver(
            (url, headers) -> {
              requested.add(url);
              if (url.startsWith("https://pin.it/"))
                return new PublicPages.Reply(
                    302, "", "https://api.pinterest.com/url_shortener/abc/redirect/");
              if (url.startsWith("https://api.pinterest.com/"))
                return new PublicPages.Reply(301, "", "https://www.pinterest.com/pin/42/sent/");
              return new PublicPages.Reply(200, pinterest(pin), null);
            });
    Post shared = resolver.resolve("https://pin.it/abc");
    assertEquals("image/png", shared.media.get(0).variants.get(0).mimeType);
    assertEquals("42", shared.id);
    assertEquals(3, requested.size());
    assertTrue(requested.get(2).startsWith(PinterestResolver.API));
    PinterestResolver away =
        new PinterestResolver(
            (url, headers) -> new PublicPages.Reply(302, "", "https://evil.example/pin/42/"));
    try {
      away.resolve("https://pin.it/abc");
      fail("Redirects must stay on Pinterest");
    } catch (IOException e) {
      assertTrue(English.message(e).contains("does not lead to a pin"));
    }
  }

  @Test
  public void tiktokPhotoPostsReadTheirSlideshowAndShortLinksAreClassified() throws Exception {
    String image =
        "https://p16-common-sign.tiktokcdn-us.com/tos-maliva-i-photomode-us/4e11~tplv-photomode-image.jpeg?x=1";
    JSONObject item =
        new JSONObject()
            .put("id", "749")
            .put("desc", "Slides")
            .put("author", new JSONObject().put("uniqueId", "maker"))
            .put(
                "imagePost",
                new JSONObject()
                    .put(
                        "images",
                        new JSONArray()
                            .put(slide(image))
                            .put(slide("https://evil.example/a.jpeg"))));
    List<String> requested = new ArrayList<>();
    TikTokPhotoResolver resolver =
        new TikTokPhotoResolver(
            (url, headers) -> {
              requested.add(url);
              if (url.startsWith("https://vm.tiktok.com/"))
                return new PublicPages.Reply(301, "", "https://www.tiktok.com/@maker/photo/749?_r=1");
              return new PublicPages.Reply(200, tiktok(item), null);
            });
    String link = resolver.photoLink("https://vm.tiktok.com/ZMabc/");
    assertEquals("https://www.tiktok.com/@maker/photo/749?_r=1", link);
    Post post = resolver.resolve(link);
    assertEquals("https://www.tiktok.com/@maker/video/749", requested.get(1));
    assertEquals("Slides", post.title);
    assertEquals("@maker", post.author);
    assertEquals(1, post.media.size());
    assertEquals(image, post.media.get(0).variants.get(0).url);
    assertEquals("1080 × 1080", post.media.get(0).variants.get(0).label);

    assertNull(resolver.photoLink("https://www.tiktok.com/@maker/video/749"));
    TikTokPhotoResolver video =
        new TikTokPhotoResolver(
            (url, headers) ->
                new PublicPages.Reply(301, "", "https://www.tiktok.com/@maker/video/1?_r=1"));
    assertNull(video.photoLink("https://vt.tiktok.com/ZMabc/"));
    TikTokPhotoResolver away =
        new TikTokPhotoResolver(
            (url, headers) -> new PublicPages.Reply(301, "", "https://evil.example/@a/photo/1"));
    try {
      away.photoLink("https://vm.tiktok.com/ZMabc/");
      fail("Short links must stay on TikTok");
    } catch (IOException e) {
      assertTrue(English.message(e).contains("away from TikTok"));
    }
    JSONObject gone = new JSONObject().put("statusCode", 10204);
    try {
      TikTokPhotoResolver.parse(page(gone), "maker", "749");
      fail("Unavailable posts fail plainly");
    } catch (IOException e) {
      assertTrue(English.message(e).contains("unavailable"));
    }
  }

  @Test
  public void pinterestRegionalHostsAreAccepted() throws Exception {
    assertEquals(
        "https://in.pinterest.com/pin/a--1/",
        PublicLinkParser.parse("https://in.pinterest.com/pin/a--1/"));
    assertEquals(
        "https://uk.pinterest.com/pin/2/", PublicLinkParser.parse("https://uk.pinterest.com/pin/2/"));
    try {
      PublicLinkParser.parse("https://in.pinterest.evil/pin/2/");
      fail("Unknown Pinterest domains are rejected");
    } catch (IOException expected) {
      // Expected.
    }
  }

  private static JSONObject slide(String url) {
    return new JSONObject()
        .put("imageWidth", 1080)
        .put("imageHeight", 1080)
        .put("imageURL", new JSONObject().put("urlList", new JSONArray().put(url)));
  }

  private static String tiktok(JSONObject item) {
    return page(
        new JSONObject()
            .put("statusCode", 0)
            .put("itemInfo", new JSONObject().put("itemStruct", item)));
  }

  private static String page(JSONObject detail) {
    JSONObject data =
        new JSONObject()
            .put("__DEFAULT_SCOPE__", new JSONObject().put("webapp.video-detail", detail));
    return "<script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\" type=\"application/json\">"
        + data
        + "</script>";
  }

  private static JSONObject images(String key, String url) {
    return new JSONObject().put(key, new JSONObject().put("url", url));
  }

  private static String pinterest(JSONObject pin) {
    return new JSONObject()
        .put("resource_response", new JSONObject().put("data", pin))
        .toString();
  }

  private static JSONArray blueskyImages(String... urls) {
    JSONArray images = new JSONArray();
    for (String url : urls)
      images.put(
          new JSONObject().put("fullsize", url).put("thumb", url.replace("feed_fullsize", "feed_thumbnail")));
    return images;
  }

  private static JSONObject embed(String type, JSONArray images) {
    JSONObject embed = new JSONObject().put("$type", type);
    if (images != null) embed.put("images", images);
    return embed;
  }

  private static String bluesky(String type, JSONArray images) {
    return bluesky(embed(type, images));
  }

  private static String bluesky(JSONObject embed) {
    JSONObject post =
        new JSONObject()
            .put("author", new JSONObject().put("handle", "bsky.app"))
            .put("record", new JSONObject().put("text", "Hello"))
            .put("embed", embed);
    return new JSONObject().put("thread", new JSONObject().put("post", post)).toString();
  }
}
