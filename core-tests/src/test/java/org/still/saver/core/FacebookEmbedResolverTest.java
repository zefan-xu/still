// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class FacebookEmbedResolverTest {
  private static final String LINK = "https://www.facebook.com/video.php?v=123&tracking=abc";
  private static final String HTML =
      "<script"
          + " data-sjs>{\"video_id\":\"123\",\"videoData\":[{\"sd_src\":\"https://scontent.fbcdn.net/a.mp4?one=1&two=2\"}]}</script>";

  @Test
  public void sendsTheExactPostToPublicEmbedAndKeepsMediaQuery() throws Exception {
    FacebookEmbedResolver resolver =
        new FacebookEmbedResolver(
            url -> {
              assertEquals(
                  "https://www.facebook.com/plugins/video.php?href=https%3A%2F%2Fwww.facebook.com%2Fvideo.php%3Fv%3D123%26tracking%3Dabc&show_text=0&width=500",
                  url);
              return new MediaResolver.Response(200, HTML);
            });
    Post post = resolver.resolve(LINK);
    assertEquals("123", post.id);
    assertEquals(LINK, post.sourceUrl);
    assertEquals(
        "https://scontent.fbcdn.net/a.mp4?one=1&two=2", post.media.get(0).variants.get(0).url);
  }

  @Test
  public void rejectsUnknownOrAmbiguousIdentityBeforeTransport() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    FacebookEmbedResolver resolver =
        new FacebookEmbedResolver(
            url -> {
              calls.incrementAndGet();
              return new MediaResolver.Response(200, HTML);
            });
    for (String link :
        new String[] {
          "https://x.com/i/status/123",
          "https://www.facebook.com/share/r/ABC",
          "https://www.facebook.com/video.php?v=123&video_id=456"
        }) {
      try {
        resolver.resolve(link);
        fail("Expected rejection: " + link);
      } catch (IOException expected) {
        assertTrue(expected.getMessage().contains("numeric ID"));
      }
    }
    assertEquals(0, calls.get());
  }

  @Test
  public void keepsHttpErrorsInsteadOfParsingTheirPayload() throws Exception {
    try {
      new FacebookEmbedResolver(url -> new MediaResolver.Response(429, HTML)).resolve(LINK);
      fail("Accepted rate limited response");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("rate limited"));
    }
  }
}
