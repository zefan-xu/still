// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PublicPageResolverTest {
  @Test
  public void keepsWholeAlbumsForTelegramOnly() {
    assertTrue(PublicPageResolver.wantsPlaylist("https://t.me/public_channel/613"));
    assertFalse(PublicPageResolver.wantsPlaylist("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
    assertFalse(PublicPageResolver.wantsPlaylist("https://www.facebook.com/cnn/videos/123/"));
  }
}
