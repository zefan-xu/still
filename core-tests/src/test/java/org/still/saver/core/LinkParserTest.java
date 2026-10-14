// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.*;

import java.io.IOException;
import org.junit.Test;

public class LinkParserTest {
  @Test
  public void acceptsSharedPostTextAndCanonicalizes() throws Exception {
    LinkParser.Parsed parsed =
        LinkParser.parse(
            "Look at this! (https://mobile.twitter.com/someone/status/123456789? s=20)"
                .replace("? s", "?s"));
    assertEquals("123456789", parsed.id);
    assertEquals("https://x.com/i/status/123456789", parsed.canonicalUrl);
    assertNull(parsed.directUrl);
  }

  @Test
  public void supportsKnownHostsAndPostPaths() throws Exception {
    String[] hosts = {
      "x.com",
      "www.x.com",
      "mobile.x.com",
      "twitter.com",
      "www.twitter.com",
      "mobile.twitter.com",
      "fxtwitter.com",
      "fixupx.com",
      "vxtwitter.com"
    };
    for (String host : hosts) {
      assertEquals("20", LinkParser.parse("https://" + host + "/jack/status/20?ref=share").id);
    }
    assertEquals("20", LinkParser.parse("https://x.com/i/status/20").id);
    assertEquals("20", LinkParser.parse("https://x.com/i/web/status/20/video/1").id);
    assertEquals("20", LinkParser.parse("https://twitter.com/jack/statuses/20/photo/2").id);
    assertEquals("20", LinkParser.parse("HTTPS://X.COM/jack/status/20").id);
    assertEquals(
        "18446744073709551615", LinkParser.parse("https://x.com/i/status/18446744073709551615").id);
  }

  @Test
  public void acceptsDirectCdnFilesAndPreservesEncodedQuery() throws Exception {
    String video = "https://video.twimg.com/vid/1280x720/movie.mp4?tag=a%26b#preview";
    LinkParser.Parsed parsed = LinkParser.parse(video);
    assertEquals("https://video.twimg.com/vid/1280x720/movie.mp4?tag=a%26b", parsed.directUrl);
    assertEquals("", parsed.id);
    assertEquals(
        "https://pbs.twimg.com/media/image?format=jpg&name=orig",
        LinkParser.parse("https://pbs.twimg.com/media/image?format=jpg&name=orig").directUrl);
    assertNotNull(LinkParser.parse("https://pbs.twimg.com/media/image.png").directUrl);
  }

  @Test
  public void rejectsLookalikesCredentialsAndUnsafeSchemes() {
    String[] hostile = {
      "http://x.com/a/status/20", "ftp://x.com/a/status/20",
      "https://x.com.evil.test/a/status/20", "https://evilx.com/a/status/20",
      "https://x.com@evil.test/a/status/20", "https://evil.test@x.com/a/status/20",
      "https://x.com:8443/a/status/20", "https://evil.test/?url=https://x.com/a/status/20",
      "https://x.com\\@evil.test/a/status/20", "https://video.twimg.com.evil.test/a.mp4",
      "https://user@video.twimg.com/a.mp4", "https://pbs.twimg.com.evil.test/a.jpg",
      "javascript:alert('https://x.com/a/status/20')", "https://x.com%2eevil.test/a/status/20",
      "javascript:alert(\"https://x.com/a/status/20\")", "ftp://https://x.com/a/status/20"
    };
    for (String url : hostile) assertRejected(url);
  }

  @Test
  public void rejectsInvalidIdsAndUnsupportedPaths() {
    String[] invalid = {"0", "020", "-20", "2x0", "18446744073709551616", "123456789012345678901"};
    for (String id : invalid) assertRejected("https://x.com/a/status/" + id);
    assertRejected("https://x.com/a/status/20/anything");
    assertRejected("https://x.com/a/status/20/video/0");
    assertRejected("https://x.com/a/status/%32%30");
    assertRejected("https://x.com/a");
    assertRejected("https://t.co/shortlink");
    assertRejected("https://video.twimg.com/playlist.m3u8");
    assertRejected("https://pbs.twimg.com/media/not-an-image");
    assertRejected("");
    assertRejected(null);
  }

  @Test
  public void previewUrlsRequireAPublicHttpsName() {
    assertNotNull(LinkParser.safePreviewUri("https://pbs.twimg.com/media/a?format=jpg"));
    assertNotNull(LinkParser.safePreviewUri("https://images.cdn.example:443/a.jpg"));
    for (String unsafe :
        new String[] {
          null,
          "http://pbs.twimg.com/a.jpg",
          "https://user@pbs.twimg.com/a.jpg",
          "https://pbs.twimg.com:8443/a.jpg",
          "https://10.0.0.1/a.jpg",
          "https://0x7f.1/a.jpg",
          "https://2130706433/a.jpg",
          "https://[::1]/a.jpg",
          "https://localhost/a.jpg",
          "https://printer.local/a.jpg",
          "https://intranet/a.jpg",
          "https://pbs.twimg.com/" + new String(new char[4100]).replace('\0', 'a'),
        }) assertNull(unsafe, LinkParser.safePreviewUri(unsafe));
  }

  private static void assertRejected(String input) {
    try {
      LinkParser.parse(input);
      fail("Accepted unsupported link: " + input);
    } catch (IOException expected) {
      assertFalse(English.message(expected).isEmpty());
    }
  }
}
