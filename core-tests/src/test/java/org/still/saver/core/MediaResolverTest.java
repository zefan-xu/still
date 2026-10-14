// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class MediaResolverTest {
  private static final String POST_LINK = "https://x.com/demo/status/123";
  private static final String SMALL =
      "https://video.twimg.com/ext_tw_video/1/pu/vid/320x180/small.mp4?tag=1";
  private static final String BIG =
      "https://video.twimg.com/ext_tw_video/1/pu/vid/1280x720/big.mp4?tag=1";
  private static final String PHOTO = "https://pbs.twimg.com/media/photo.jpg?name=orig";

  @Test
  public void parsesMixedMediaAndDeduplicatesQualitiesAcrossApiLists() throws Exception {
    JSONObject video =
        new JSONObject()
            .put("id", "v1")
            .put("type", "video")
            .put("url", BIG)
            .put("width", 1920)
            .put("height", 1080)
            .put("thumbnail_url", "https://pbs.twimg.com/thumb.jpg")
            .put("duration", 12.345)
            .put(
                "formats",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("url", "https://video.twimg.com/stream.m3u8")
                            .put("container", "m3u8"))
                    .put(format(SMALL, 256000))
                    .put(format(BIG, 2176000)))
            .put(
                "variants",
                new JSONArray().put(legacyVariant(BIG, 2176000)).put(legacyVariant(SMALL, 256000)));
    JSONObject photo =
        new JSONObject()
            .put("id", "p1")
            .put("type", "photo")
            .put("url", PHOTO)
            .put("width", 3000)
            .put("height", 2000);
    JSONObject gif =
        new JSONObject()
            .put("id", "g1")
            .put("type", "gif")
            .put("url", "https://video.twimg.com/tweet_video/loop.mp4")
            .put("width", 640)
            .put("height", 480);
    JSONObject mosaic =
        new JSONObject()
            .put("type", "mosaic_photo")
            .put("url", "https://mosaic.fxtwitter.com/image.jpg");
    JSONObject media =
        new JSONObject()
            .put("all", new JSONArray().put(video).put(photo).put(gif).put(mosaic))
            .put("videos", new JSONArray().put(video).put(gif))
            .put("photos", new JSONArray().put(photo));

    Post post = resolver(200, fixture(media)).resolve(POST_LINK);
    assertEquals("123", post.id);
    assertEquals("A synthetic public post", post.title);
    assertEquals("@demo", post.author);
    assertEquals("https://x.com/i/status/123", post.sourceUrl);
    assertEquals(3, post.media.size());
    Media resolvedVideo = post.media.get(0);
    assertEquals("video", resolvedVideo.kind);
    assertEquals(2, resolvedVideo.variants.size());
    assertEquals(BIG, resolvedVideo.chooseVariant("best").url);
    assertEquals(SMALL, resolvedVideo.chooseVariant("smallest").url);
    assertEquals(BIG, resolvedVideo.chooseVariant("ask").url);
    assertEquals(1280, resolvedVideo.variants.get(0).width);
    assertEquals(720, resolvedVideo.variants.get(0).height);
    assertEquals("720p", resolvedVideo.variants.get(0).label);
    assertEquals("photo", post.media.get(1).kind);
    assertEquals("image/jpeg", post.media.get(1).variants.get(0).mimeType);
    assertEquals("gif", post.media.get(2).kind);
    assertEquals("video/mp4", post.media.get(2).variants.get(0).mimeType);
    assertEquals("https://pbs.twimg.com/thumb.jpg", resolvedVideo.thumbnail);
    assertEquals(12345, resolvedVideo.durationMs);
    assertEquals(
        "https://pbs.twimg.com/media/photo?format=jpg&name=small", post.media.get(1).thumbnail);
    assertEquals(0, post.media.get(1).durationMs);
    assertNull(post.media.get(2).thumbnail);
    assertEquals(0, post.media.get(2).durationMs);
  }

  @Test
  public void dropsUnsafeOrForeignThumbnailsWithoutFailingThePost() throws Exception {
    for (String thumbnail :
        new String[] {
          "http://pbs.twimg.com/thumb.jpg",
          "https://user@pbs.twimg.com/thumb.jpg",
          "https://pbs.twimg.com:444/thumb.jpg",
          "https://10.0.0.1/thumb.jpg",
          "https://images.example/thumb.jpg"
        }) {
      JSONObject video =
          new JSONObject()
              .put("type", "video")
              .put("url", BIG)
              .put("thumbnail_url", thumbnail)
              .put("duration", -4)
              .put("formats", new JSONArray().put(format(BIG, 2176000)));
      Post post =
          resolver(200, fixture(new JSONObject().put("videos", new JSONArray().put(video))))
              .resolve(POST_LINK);
      assertEquals(BIG, post.media.get(0).chooseVariant("best").url);
      assertNull(thumbnail, post.media.get(0).thumbnail);
      assertEquals(0, post.media.get(0).durationMs);
    }
  }

  @Test
  public void directPhotoLinksPreviewSmallAndDirectVideosHaveNoPreview() throws Exception {
    MediaResolver resolver =
        new MediaResolver(
            MediaResolver.DEFAULT_API_BASE,
            url -> {
              throw new AssertionError("Direct links must not contact the service");
            });
    Media photo =
        resolver.resolve("https://pbs.twimg.com/media/AbC_1-2?format=png&name=orig").media.get(0);
    assertEquals("https://pbs.twimg.com/media/AbC_1-2?format=png&name=small", photo.thumbnail);
    assertNull(resolver.resolve(BIG).media.get(0).thumbnail);
  }

  @Test
  public void fallsBackToLegacyVideoVariantsAndMediaLevelUrl() throws Exception {
    JSONObject video =
        new JSONObject()
            .put("type", "animated_gif")
            .put("url", "https://pbs.twimg.com/thumb.jpg")
            .put(
                "video_info",
                new JSONObject().put("variants", new JSONArray().put(legacyVariant(BIG, 2176000))));
    Post post =
        resolver(200, fixture(new JSONObject().put("videos", new JSONArray().put(video))))
            .resolve(POST_LINK);
    assertEquals("gif", post.media.get(0).kind);
    assertEquals(BIG, post.media.get(0).chooseVariant("best").url);
  }

  @Test
  public void usesPublicQuotedMediaOnlyWhenOuterPostHasNone() throws Exception {
    JSONObject video =
        new JSONObject()
            .put("type", "video")
            .put("url", BIG)
            .put("formats", new JSONArray().put(format(SMALL, 256000)).put(format(BIG, 2176000)));
    JSONObject quote =
        new JSONObject(fixture(new JSONObject().put("videos", new JSONArray().put(video))))
            .getJSONObject("tweet");
    quote.put("id", "456").put("text", "Quoted post text");
    quote.getJSONObject("author").put("screen_name", "quoted_author");
    JSONObject root = new JSONObject(fixture(new JSONObject()));
    JSONObject outer = root.getJSONObject("tweet").put("quote", quote);
    String response = root.toString();
    AtomicInteger calls = new AtomicInteger();
    MediaResolver resolver =
        new MediaResolver(
            MediaResolver.DEFAULT_API_BASE,
            url -> {
              calls.incrementAndGet();
              return new MediaResolver.Response(200, response);
            });

    Post post = resolver.resolve(POST_LINK);
    assertEquals(1, calls.get());
    assertEquals("123", post.id);
    assertEquals("A synthetic public post", post.title);
    assertEquals("@demo", post.author);
    assertEquals("https://x.com/i/status/123", post.sourceUrl);
    assertEquals(1, post.media.size());
    assertEquals(BIG, post.media.get(0).chooseVariant("best").url);
    assertEquals(SMALL, post.media.get(0).chooseVariant("smallest").url);

    outer.put("media", photoMedia());
    Post ownMedia = resolver(200, root.toString()).resolve(POST_LINK);
    assertEquals(1, ownMedia.media.size());
    assertEquals(PHOTO, ownMedia.media.get(0).chooseVariant("best").url);

    outer.remove("media");
    quote.put("type", "tombstone");
    assertError(resolver(200, root.toString()), "no downloadable");
    quote.remove("type");
    quote.getJSONObject("author").put("protected", true);
    assertError(resolver(200, root.toString()), "private or restricted");
  }

  @Test
  public void neverTreatsPreviewThumbnailsCardsOrHlsAsDownloads() throws Exception {
    JSONObject row =
        new JSONObject()
            .put("type", "video")
            .put("url", "https://video.twimg.com/stream.m3u8")
            .put("thumbnail_url", PHOTO)
            .put(
                "formats",
                new JSONArray().put(new JSONObject().put("url", PHOTO).put("container", "mp4")));
    JSONObject media =
        new JSONObject()
            .put("all", new JSONArray().put(row))
            .put(
                "external",
                new JSONObject().put("type", "video").put("url", "https://example.com/movie.mp4"));
    assertError(resolver(200, fixture(media)), "no downloadable");
  }

  @Test
  public void directLinksDoNotContactMetadataService() throws Exception {
    MediaResolver resolver =
        new MediaResolver(
            "https://self.example/api",
            url -> {
              fail("Direct media must not make an API request");
              return null;
            });
    Post video = resolver.resolve(BIG);
    assertEquals(BIG, video.media.get(0).chooseVariant("best").url);
    assertEquals("video", video.media.get(0).kind);
    assertEquals(720, video.media.get(0).chooseVariant("best").height);
    Post image = resolver.resolve("https://pbs.twimg.com/media/photo?format=png&name=orig");
    assertEquals("image/png", image.media.get(0).chooseVariant("best").mimeType);
  }

  @Test
  public void selfHostedBaseReceivesOnlyCanonicalId() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    String body = fixture(photoMedia());
    MediaResolver resolver =
        new MediaResolver(
            "https://self.example/service///",
            url -> {
              assertEquals("https://self.example/service/status/123", url);
              calls.incrementAndGet();
              return new MediaResolver.Response(200, body);
            });
    resolver.resolve("Shared post: https://fixupx.com/demo/status/123?s=20");
    assertEquals(1, calls.get());
  }

  @Test
  public void reportsHttpAndApiErrorsWithoutRequiringValidErrorJson() throws Exception {
    assertError(resolver(429, "<html>rate limit</html>"), "rate limited");
    assertError(resolver(200, "{\"code\":429,\"message\":\"RATE_LIMITED\"}"), "rate limited");
    assertError(resolver(403, ""), "private or restricted");
    assertError(resolver(404, "{\"message\":\"PRIVATE\"}"), "private or restricted");
    assertError(resolver(404, ""), "deleted or unavailable");
    assertError(resolver(503, ""), "temporarily unavailable");
    assertError(resolver(200, "broken json"), "unreadable response");
    assertError(resolver(200, "{\"code\":200}"), "unavailable");
    assertError(resolver(200, fixture(new JSONObject())), "no downloadable");
  }

  @Test
  public void rejectsProtectedAuthorsAndTombstones() throws Exception {
    JSONObject root = new JSONObject(fixture(photoMedia()));
    root.getJSONObject("tweet").getJSONObject("author").put("protected", true);
    assertError(resolver(200, root.toString()), "private or restricted");
    assertError(
        resolver(200, "{\"code\":200,\"status\":{\"type\":\"tombstone\",\"reason\":\"private\"}}"),
        "private or restricted");
    assertError(
        resolver(200, "{\"code\":200,\"status\":{\"type\":\"tombstone\",\"reason\":\"deleted\"}}"),
        "deleted or unavailable");
  }

  @Test
  public void rejectsUnsafeMediaUrlsSuppliedByApi() throws Exception {
    String[] badUrls = {
      "http://video.twimg.com/a.mp4",
      "https://video.twimg.com.evil.test/a.mp4",
      "https://user@video.twimg.com/a.mp4",
      "https://example.com/a.mp4",
      "https://pbs.twimg.com/thumb.jpg"
    };
    for (String url : badUrls) {
      JSONObject video = new JSONObject().put("type", "video").put("url", url);
      assertError(
          resolver(200, fixture(new JSONObject().put("videos", new JSONArray().put(video)))),
          "no downloadable");
    }
  }

  @Test
  public void reportsOfflineAndLimitsInjectedResponseSize() throws Exception {
    MediaResolver offline =
        new MediaResolver(
            MediaResolver.DEFAULT_API_BASE,
            url -> {
              throw new IOException("Network unreachable");
            });
    assertError(offline, "Check your connection");
    char[] oversized = new char[2 * 1024 * 1024 + 1];
    Arrays.fill(oversized, 'a');
    assertError(resolver(200, new String(oversized)), "too large");
    char[] multiByte = new char[1024 * 1024];
    Arrays.fill(multiByte, '\u20ac');
    assertError(resolver(200, new String(multiByte)), "too large");
  }

  @Test
  public void rejectsUnsafeApiBase() {
    String[] invalid = {
      "http://self.example",
      "https://user:secret@self.example",
      "https://self.example?token=a",
      "https://self.example#x",
      "https:///missing-host",
      null
    };
    for (String base : invalid) {
      try {
        new MediaResolver(base);
        fail("Accepted API base: " + base);
      } catch (IllegalArgumentException expected) {
        assertTrue(expected.getMessage().contains("HTTPS"));
      }
    }
  }

  @Test
  public void modelSortsAndFreezesVariants() {
    Variant big = new Variant(BIG, "720p", "video/mp4", 2176000, 1280, 720);
    Variant small = new Variant(SMALL, "180p", "video/mp4", 256000, 320, 180);
    Media media = new Media("video", Arrays.asList(small, big, small));
    assertEquals(2, media.variants.size());
    assertSame(big, media.chooseVariant(null));
    assertSame(small, media.chooseVariant("smallest"));
    try {
      media.variants.clear();
      fail("Variants are mutable");
    } catch (UnsupportedOperationException expected) {
      /* expected */
    }
    Post post = new Post("123", "Title", "", POST_LINK, Collections.singletonList(media));
    try {
      post.media.clear();
      fail("Media list is mutable");
    } catch (UnsupportedOperationException expected) {
      /* expected */
    }
  }

  @Test
  public void ordersEmbeddedQualityLabelsConsistentlyWithUnknownFormats() {
    Variant hd = new Variant("https://scontent.fbcdn.net/z.mp4", "HD MP4", "video/mp4", 0, 0, 0);
    Variant sd = new Variant("https://scontent.fbcdn.net/a.mp4", "SD MP4", "video/mp4", 0, 0, 0);
    Variant unknown = new Variant("https://scontent.fbcdn.net/m.mp4", "MP4", "video/mp4", 0, 0, 0);
    for (java.util.List<Variant> order :
        Arrays.asList(
            Arrays.asList(hd, sd, unknown),
            Arrays.asList(sd, unknown, hd),
            Arrays.asList(unknown, hd, sd))) {
      Media media = new Media("video", order);
      assertSame(hd, media.chooseVariant("highest"));
      assertSame(sd, media.chooseVariant("smallest"));
      assertSame(unknown, media.variants.get(1));
    }
  }

  private static JSONObject format(String url, long bitrate) throws Exception {
    return new JSONObject().put("url", url).put("bitrate", bitrate).put("container", "mp4");
  }

  private static JSONObject legacyVariant(String url, long bitrate) throws Exception {
    return new JSONObject()
        .put("url", url)
        .put("bitrate", bitrate)
        .put("content_type", "video/mp4");
  }

  private static JSONObject photoMedia() throws Exception {
    return new JSONObject()
        .put(
            "photos", new JSONArray().put(new JSONObject().put("type", "photo").put("url", PHOTO)));
  }

  private static String fixture(JSONObject media) throws Exception {
    JSONObject author =
        new JSONObject().put("screen_name", "demo").put("name", "Demo").put("protected", false);
    JSONObject tweet =
        new JSONObject()
            .put("id", "123")
            .put("text", "A synthetic public post")
            .put("author", author)
            .put("media", media);
    return new JSONObject().put("code", 200).put("tweet", tweet).toString();
  }

  private static MediaResolver resolver(int status, String body) {
    return new MediaResolver(
        MediaResolver.DEFAULT_API_BASE, url -> new MediaResolver.Response(status, body));
  }

  private static void assertError(MediaResolver resolver, String expected) {
    try {
      resolver.resolve(POST_LINK);
      fail("Expected error containing: " + expected);
    } catch (IOException error) {
      assertTrue(error.getMessage(), error.getMessage().contains(expected));
    }
  }
}
