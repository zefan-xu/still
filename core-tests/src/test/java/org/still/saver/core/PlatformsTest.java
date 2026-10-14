// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.*;

import java.io.IOException;
import org.junit.Test;

public class PlatformsTest {
  @Test
  public void listsEveryPlatformInDisplayOrder() {
    assertEquals(
        "X, Instagram, TikTok, Facebook, Pinterest, Threads and Bluesky", Platforms.names("and"));
    assertEquals(
        "X, Instagram, TikTok, Facebook, Pinterest, Threads or Bluesky", Platforms.names("or"));
    for (Platforms.Platform platform : Platforms.ALL) {
      assertFalse(platform.name, platform.kinds.isEmpty());
      assertFalse(platform.name, platform.examples.isEmpty());
    }
    assertEquals("Videos only", Platforms.FACEBOOK.kinds);
    assertFalse(Platforms.LIMITS.isEmpty());
  }

  @Test
  public void parserErrorNamesThePlatforms() {
    try {
      PublicLinkParser.parse("https://example.com/not-a-supported-post");
      fail("Accepted an unsupported link");
    } catch (IOException expected) {
      assertEquals(
          "Use an HTTPS public post link from " + Platforms.names("or") + ".",
          expected.getMessage());
    }
  }

  @Test
  public void detectsPlatformAndKindFromSharedText() {
    Object[][] cases = {
      {"https://twitter.com/a/status/123?s=20", Platforms.X, "post"},
      {"https://pbs.twimg.com/media/abc.jpg", Platforms.X, "media link"},
      {"https://www.instagram.com/p/AbC-123/", Platforms.INSTAGRAM, "post"},
      {"https://www.instagram.com/someone/reel/Abc123/", Platforms.INSTAGRAM, "reel"},
      {"https://instagram.com/reels/Abc123/", Platforms.INSTAGRAM, "reel"},
      {"https://www.tiktok.com/@someone/video/123456789", Platforms.TIKTOK, "video"},
      {"https://www.tiktok.com/@someone/photo/123456789", Platforms.TIKTOK, "photo post"},
      {"https://vm.tiktok.com/Abc12/", Platforms.TIKTOK, "link"},
      {"https://facebook.com/watch/?v=123", Platforms.FACEBOOK, "video"},
      {"https://www.facebook.com/someone/videos/123/", Platforms.FACEBOOK, "video"},
      {"https://m.facebook.com/reel/123", Platforms.FACEBOOK, "reel"},
      {"https://fb.watch/AbC-123/", Platforms.FACEBOOK, "video"},
      {"https://www.facebook.com/NASA/posts/10154159437036772/", Platforms.FACEBOOK, "post"},
      {"https://www.pinterest.com/pin/123456789/", Platforms.PINTEREST, "pin"},
      {"https://www.pinterest.co.uk/pin/549228117041314916/", Platforms.PINTEREST, "pin"},
      {"https://pin.it/AbC123", Platforms.PINTEREST, "link"},
      {"https://www.threads.com/@someone/post/AbC123", Platforms.THREADS, "post"},
      {"https://bsky.app/profile/someone.bsky.social/post/3abc123", Platforms.BLUESKY, "post"},
    };
    for (Object[] c : cases) {
      Platforms.Match match = Platforms.detect("Look at this: " + c[0] + " !");
      assertNotNull((String) c[0], match);
      assertSame((String) c[0], c[1], match.platform);
      assertEquals((String) c[0], c[2], match.kind);
    }
    assertEquals(
        "Instagram post", Platforms.detect("https://instagram.com/p/AbC-123/").label());
    assertEquals(
        "https://x.com/i/status/123", Platforms.detect("https://x.com/a/status/123").url);
  }

  @Test
  public void unsupportedAndEmptyTextHaveNoPlatform() {
    String[] inputs = {
      null,
      "",
      "   ",
      "https://example.com/not-a-supported-post",
      "https://youtube.com/watch?v=123",
      "https://instagram.com/someone/",
      "http://instagram.com/reel/Abc",
      "javascript:alert(1)",
    };
    for (String input : inputs) assertNull(input, Platforms.detect(input));
  }
}
