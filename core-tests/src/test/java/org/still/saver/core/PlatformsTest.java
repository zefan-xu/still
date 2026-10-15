// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.*;

import java.io.IOException;
import org.still.saver.core.Platforms.Kind;
import org.junit.Test;

public class PlatformsTest {
  @Test
  public void listsEveryPlatformInDisplayOrder() {
    assertEquals(
        "X, Instagram, TikTok, Facebook, Pinterest, Threads, Bluesky and YouTube",
        Platforms.names("and"));
    assertEquals(
        "X, Instagram, TikTok, Facebook, Pinterest, Threads, Bluesky or YouTube",
        Platforms.names("or"));
    for (Platforms.Platform platform : Platforms.ALL)
      assertFalse(platform.name, platform.examples.isEmpty());
  }

  @Test
  public void parserErrorNamesThePlatforms() {
    try {
      PublicLinkParser.parse("https://example.com/not-a-supported-post");
      fail("Accepted an unsupported link");
    } catch (IOException expected) {
      assertEquals(
          "Use an HTTPS public post link from " + Platforms.names("or") + ".",
          English.message(expected));
    }
  }

  @Test
  public void detectsPlatformAndKindFromSharedText() {
    Object[][] cases = {
      {"https://twitter.com/a/status/123?s=20", Platforms.X, Kind.POST},
      {"https://pbs.twimg.com/media/abc.jpg", Platforms.X, Kind.MEDIA_LINK},
      {"https://www.instagram.com/p/AbC-123/", Platforms.INSTAGRAM, Kind.POST},
      {"https://www.instagram.com/someone/reel/Abc123/", Platforms.INSTAGRAM, Kind.REEL},
      {"https://instagram.com/reels/Abc123/", Platforms.INSTAGRAM, Kind.REEL},
      {"https://www.tiktok.com/@someone/video/123456789", Platforms.TIKTOK, Kind.VIDEO},
      {"https://www.tiktok.com/@someone/photo/123456789", Platforms.TIKTOK, Kind.PHOTO_POST},
      {"https://vm.tiktok.com/Abc12/", Platforms.TIKTOK, Kind.LINK},
      {"https://facebook.com/watch/?v=123", Platforms.FACEBOOK, Kind.VIDEO},
      {"https://www.facebook.com/someone/videos/123/", Platforms.FACEBOOK, Kind.VIDEO},
      {"https://m.facebook.com/reel/123", Platforms.FACEBOOK, Kind.REEL},
      {"https://fb.watch/AbC-123/", Platforms.FACEBOOK, Kind.VIDEO},
      {"https://www.facebook.com/NASA/posts/10154159437036772/", Platforms.FACEBOOK, Kind.POST},
      {"https://www.pinterest.com/pin/123456789/", Platforms.PINTEREST, Kind.PIN},
      {"https://www.pinterest.co.uk/pin/549228117041314916/", Platforms.PINTEREST, Kind.PIN},
      {"https://pin.it/AbC123", Platforms.PINTEREST, Kind.LINK},
      {"https://www.threads.com/@someone/post/AbC123", Platforms.THREADS, Kind.POST},
      {"https://bsky.app/profile/someone.bsky.social/post/3abc123", Platforms.BLUESKY, Kind.POST},
      {"https://www.youtube.com/watch?v=dQw4w9WgXcQ", Platforms.YOUTUBE, Kind.VIDEO},
      {"https://youtu.be/dQw4w9WgXcQ?si=abc", Platforms.YOUTUBE, Kind.VIDEO},
      {"https://youtube.com/shorts/dQw4w9WgXcQ?feature=share", Platforms.YOUTUBE, Kind.SHORT},
    };
    for (Object[] c : cases) {
      Platforms.Match match = Platforms.detect("Look at this: " + c[0] + " !");
      assertNotNull((String) c[0], match);
      assertSame((String) c[0], c[1], match.platform);
      assertSame((String) c[0], c[2], match.kind);
    }
    assertEquals(
        "Instagram post", English.label(Platforms.detect("https://instagram.com/p/AbC-123/")));
    assertEquals(
        "https://x.com/i/status/123", Platforms.detect("https://x.com/a/status/123").url);
    assertEquals(
        "YouTube Short",
        English.label(Platforms.detect("https://m.youtube.com/shorts/dQw4w9WgXcQ")));
  }

  @Test
  public void unsupportedAndEmptyTextHaveNoPlatform() {
    String[] inputs = {
      null,
      "",
      "   ",
      "https://example.com/not-a-supported-post",
      "https://youtube.com/watch?v=123",
      "https://www.youtube.com/@someone",
      "https://instagram.com/someone/",
      "http://instagram.com/reel/Abc",
      "javascript:alert(1)",
    };
    for (String input : inputs) assertNull(input, Platforms.detect(input));
  }
}
