// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Small checked-in responses for FxTwitter, Instagram and TikTok. No network. */
public class FixtureResponsesTest {
  @Test
  public void fxtwitterStatusBecomesOneMp4() throws Exception {
    String body = fixture("fixtures/fxtwitter-status.json");
    Post post =
        new MediaResolver(
                MediaResolver.DEFAULT_API_BASE,
                url -> {
                  assertTrue(url.endsWith("/status/1841206275088290279"));
                  return new MediaResolver.Response(200, body);
                })
            .resolve("https://x.com/ada/status/1841206275088290279");
    assertEquals("1841206275088290279", post.id);
    assertEquals("A public clip", post.title);
    assertEquals("@ada", post.author);
    assertEquals("https://x.com/i/status/1841206275088290279", post.sourceUrl);
    assertEquals(1, post.media.size());
    Media video = post.media.get(0);
    assertEquals("video", video.kind);
    assertEquals(1500L, video.durationMs);
    assertEquals(
        "https://video.twimg.com/ext_tw_video/1/pu/vid/640x360/clip.mp4",
        video.variants.get(0).url);
    assertEquals("video/mp4", video.variants.get(0).mimeType);
    assertEquals(360, video.variants.get(0).height);
    assertEquals("https://pbs.twimg.com/ext_tw_video_thumb/1/pu/img/poster.jpg", video.thumbnail);
  }

  @Test
  public void instagramPageJsonBecomesTheOriginalPhoto() throws Exception {
    Post post =
        InstagramResolver.parse(
            fixture("fixtures/instagram-post.html"),
            "https://www.instagram.com/p/AbC12/",
            "AbC12");
    assertEquals("AbC12", post.id);
    assertEquals("@ada", post.author);
    assertEquals("Hello from the page", post.title);
    assertEquals(1, post.media.size());
    Media photo = post.media.get(0);
    assertEquals("photo", photo.kind);
    assertEquals(
        "https://scontent.cdninstagram.com/v/t51/a.jpg", photo.variants.get(0).url);
    assertEquals("image/jpeg", photo.variants.get(0).mimeType);
    assertEquals(1080, photo.variants.get(0).width);
    assertEquals(1350, photo.variants.get(0).height);
    assertEquals("https://scontent.cdninstagram.com/v/t51/a_p480x480_.jpg", photo.thumbnail);
  }

  @Test
  public void tiktokRehydrationJsonBecomesTheSlideshow() throws Exception {
    Post post =
        TikTokPhotoResolver.parse(
            fixture("fixtures/tiktok-photo.html"), "ada", "7047596209028074758");
    assertEquals("7047596209028074758", post.id);
    assertEquals("@ada", post.author);
    assertEquals("Two slides", post.title);
    assertEquals(
        "https://www.tiktok.com/@ada/photo/7047596209028074758", post.sourceUrl);
    assertEquals(2, post.media.size());
    assertEquals("photo", post.media.get(0).kind);
    assertEquals(
        "https://p16-sign.tiktokcdn.com/tos/photo-a.jpeg", post.media.get(0).variants.get(0).url);
    assertEquals("image/jpeg", post.media.get(0).variants.get(0).mimeType);
    assertEquals(1920, post.media.get(0).variants.get(0).height);
    assertEquals(
        "https://p16-sign.tiktokcdn.com/tos/photo-b.jpeg", post.media.get(1).variants.get(0).url);
  }

  private static String fixture(String name) throws IOException {
    try (java.io.InputStream in = FixtureResponsesTest.class.getClassLoader().getResourceAsStream(name)) {
      if (in == null) throw new IOException("Missing fixture " + name);
      byte[] bytes = new byte[8192];
      int count = in.read(bytes);
      if (count < 0 || in.read() != -1) throw new IOException("Unexpected fixture size for " + name);
      return new String(bytes, 0, count, StandardCharsets.UTF_8);
    }
  }
}
