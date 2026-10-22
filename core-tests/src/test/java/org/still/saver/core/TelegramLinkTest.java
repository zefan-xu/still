// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TelegramLinkTest {
  @Test
  public void mapsPublicAndPrivateMessagesToWebRoutes() {
    TelegramLink publicPost =
        TelegramLink.from("Shared video: https://telegram.me/example_channel/613?single");
    assertEquals("https://t.me/example_channel/613", publicPost.canonicalUrl);
    assertEquals("#@example_channel?post=613", publicPost.webHash());
    assertFalse(publicPost.privateChannel);

    TelegramLink privatePost = TelegramLink.from("https://t.me/c/123456/613");
    assertEquals("https://t.me/c/123456/613", privatePost.canonicalUrl);
    assertEquals("#-123456?post=613", privatePost.webHash());
    assertTrue(privatePost.privateChannel);
    assertEquals("telegram_123456_613", privatePost.filenameStem());
  }

  @Test
  public void ignoresOtherPlatformsAndUtilityLinks() {
    assertNull(TelegramLink.from("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
    assertNull(TelegramLink.from("https://t.me/login/613"));
    assertNull(TelegramLink.from("not a link"));
  }
}
