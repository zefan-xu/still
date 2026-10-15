// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.still.saver.core.Platforms.Kind;

/** URL parsing and platform detection, with no network and no Android framework. */
public class LinkAndPlatformTest {
  @Test
  public void shareTextResolvesToThePlatformAndKind() {
    expect(
        "see https://x.com/ada/status/1841206275088290279?s=20",
        Platforms.X,
        Kind.POST,
        "https://x.com/i/status/1841206275088290279");
    expect(
        "https://www.instagram.com/reel/Chunk8-jurw/?igsh=1",
        Platforms.INSTAGRAM,
        Kind.REEL,
        "https://www.instagram.com/reel/Chunk8-jurw/?igsh=1");
    expect(
        "https://www.tiktok.com/@hankgreen1/video/7047596209028074758",
        Platforms.TIKTOK,
        Kind.VIDEO,
        "https://www.tiktok.com/@hankgreen1/video/7047596209028074758");
    expect(
        "https://www.tiktok.com/@ada/photo/7047596209028074758",
        Platforms.TIKTOK,
        Kind.PHOTO_POST,
        "https://www.tiktok.com/@ada/photo/7047596209028074758");
    expect(
        "https://youtu.be/dQw4w9WgXcQ?t=3",
        Platforms.YOUTUBE,
        Kind.VIDEO,
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
    expect(
        "https://www.youtube.com/shorts/dQw4w9WgXcQ",
        Platforms.YOUTUBE,
        Kind.SHORT,
        "https://www.youtube.com/shorts/dQw4w9WgXcQ");
    expect(
        "https://www.facebook.com/reel/1195289147628387",
        Platforms.FACEBOOK,
        Kind.REEL,
        "https://www.facebook.com/reel/1195289147628387");
    expect(
        "https://www.pinterest.com/pin/664281013778109217/",
        Platforms.PINTEREST,
        Kind.PIN,
        "https://www.pinterest.com/pin/664281013778109217/");
    expect(
        "https://www.threads.com/@zuck/post/DHV7vTivqWD",
        Platforms.THREADS,
        Kind.POST,
        "https://www.threads.com/@zuck/post/DHV7vTivqWD");
    expect(
        "https://bsky.app/profile/bsky.app/post/3l3vgf77uco2g",
        Platforms.BLUESKY,
        Kind.POST,
        "https://bsky.app/profile/bsky.app/post/3l3vgf77uco2g");
  }

  @Test
  public void xParserKeepsTheStatusIdAndDropsTracking() throws Exception {
    LinkParser.Parsed parsed =
        LinkParser.parse("From a chat: https://fxtwitter.com/ada/status/1841206275088290279.");
    assertEquals("1841206275088290279", parsed.id);
    assertEquals("https://x.com/i/status/1841206275088290279", parsed.canonicalUrl);
    assertNull(parsed.directUrl);
  }

  @Test
  public void unsupportedAndCleartextTextIsNotAPlatform() {
    assertNull(Platforms.detect("hello"));
    assertNull(Platforms.detect("http://x.com/ada/status/1841206275088290279"));
    assertNull(Platforms.detect("https://example.com/watch?v=dQw4w9WgXcQ"));
  }

  private static void expect(String text, Platforms.Platform platform, Kind kind, String url) {
    Platforms.Match match = Platforms.detect(text);
    assertEquals(platform.name, platform, match.platform);
    assertEquals(kind, match.kind);
    assertEquals(url, match.url);
  }
}
