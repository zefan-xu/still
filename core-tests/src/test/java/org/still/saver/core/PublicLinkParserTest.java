// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.*;

import java.io.IOException;
import org.junit.Test;

public class PublicLinkParserTest {
  @Test
  public void supportsPublicPostAndShortLinks() throws Exception {
    String[] urls = {
      "https://www.tiktok.com/@someone/video/123456789",
      "https://www.tiktok.com/t/Abc12/",
      "https://vm.tiktok.com/Abc12/",
      "https://vt.tiktok.com/Abc12/",
      "https://www.instagram.com/reel/AbC_123/",
      "https://instagram.com/p/AbC-123/",
      "https://facebook.com/watch/?v=123",
      "https://www.facebook.com/someone/videos/123/",
      "https://m.facebook.com/reel/123",
      "https://www.facebook.com/share/v/AbC123/",
      "https://fb.watch/AbC-123/",
      "https://www.pinterest.com/pin/123456789/",
      "https://pin.it/AbC123",
      "https://www.threads.net/@someone/post/AbC123",
      "https://www.threads.com/@someone/post/AbC123",
      "https://bsky.app/profile/someone.bsky.social/post/3abc123",
      "https://bsky.app/profile/did:plc:abcdef/post/3abc123"
    };
    for (String url : urls) assertEquals(url, PublicLinkParser.parse("Shared media: " + url));
    assertEquals(
        "https://x.com/i/status/123",
        PublicLinkParser.parse("https://twitter.com/a/status/123?s=20"));
  }

  @Test
  public void acceptsPublicVideoPostFormsRecognizedByBundledExtractors() throws Exception {
    String[] urls = {
      "https://www.facebook.com/NASA/posts/10154159437036772/",
      "https://www.facebook.com/someone/posts/pfbidAbc123/",
      "https://www.facebook.com/groups/123/posts/456/",
      "https://www.facebook.com/groups/science/permalink/456/",
      "https://www.facebook.com/permalink.php?story_fbid=456&id=123",
      "https://www.facebook.com/story.php?id=123&story_fbid=pfbidAbc123",
      "https://www.instagram.com/someone/reel/Abc123/",
      "https://www.instagram.com/some.one/p/Abc123/",
      "https://www.pinterest.com/pin/a-video--549228117041314916/",
      "https://www.pinterest.co.uk/pin/549228117041314916/",
      "https://pinterest.de/pin/549228117041314916/",
      "https://www.pinterest.com.au/pin/549228117041314916/",
      "https://m.pinterest.ca/pin/549228117041314916/",
      "https://www.pinterest.co.in/pin/549228117041314916/"
    };
    for (String url : urls) assertEquals(url, PublicLinkParser.parse("Shared media: " + url));
  }

  @Test
  public void rejectsUnlistedHostsCredentialsAndNonPostPages() {
    String[] urls = {
      "http://instagram.com/reel/Abc",
      "https://instagram.com.evil.test/reel/Abc",
      "https://tiktok.com@evil.test/@a/video/123",
      "https://user@www.tiktok.com/@a/video/123",
      "https://facebook.com:8443/watch/?v=123",
      "https://youtube.com/watch?v=123",
      "https://bsky.app/profile/someone.bsky.social",
      "https://instagram.com/someone/",
      "https://pinterest.com/someone/board/",
      "https://www.pinterest.co.uk.evil.test/pin/123/",
      "https://pinterest.example/pin/123/",
      "https://notpinterest.de/pin/123/",
      "https://www.facebook.com/groups/123/posts/",
      "https://www.facebook.com/someone/posts/not-a-post-id/",
      "https://www.facebook.com/permalink.php?id=123",
      "https://www.instagram.com/share/reel/Abc123/",
      "https://threads.com/",
      "https://tiktok.com/",
      "https://facebook.com/watch/",
      "https://facebook.com/video.php?other=123",
      "https://evil.test/?next=https://instagram.com/reel/Abc",
      "https://x.com/a/status/0"
    };
    for (String url : urls) {
      try {
        PublicLinkParser.parse(url);
        fail("Accepted: " + url);
      } catch (IOException expected) {
        assertFalse(expected.getMessage().isEmpty());
      }
    }
  }
}
