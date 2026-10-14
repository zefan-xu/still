// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class MediaTest {
  private static final List<Variant> VARIANTS =
      Collections.singletonList(
          new Variant("https://video.twimg.com/a.mp4", "720p", "video/mp4", 0, 1280, 720));

  @Test
  public void mediaWithoutPreviewMetadataHasNoThumbnailOrDuration() {
    Media media = new Media("video", VARIANTS);
    assertNull(media.thumbnail);
    assertEquals(0, media.durationMs);
  }

  @Test
  public void keepsValidatedPreviewMetadata() {
    Media media = new Media("video", VARIANTS, "https://pbs.twimg.com/thumb.jpg", 1500);
    assertEquals("https://pbs.twimg.com/thumb.jpg", media.thumbnail);
    assertEquals(1500, media.durationMs);
  }

  @Test
  public void rejectsUnsafePreviewsAndNegativeDurations() {
    for (String unsafe :
        new String[] {"http://pbs.twimg.com/a.jpg", "https://192.168.1.1/a.jpg", "not a url"}) {
      try {
        new Media("video", VARIANTS, unsafe, 0);
        fail("Accepted unsafe preview " + unsafe);
      } catch (IllegalArgumentException expected) {
        // Resolvers must filter untrusted previews before constructing media.
      }
    }
    try {
      new Media("video", VARIANTS, null, -1);
      fail("Accepted a negative duration");
    } catch (IllegalArgumentException expected) {
      // Durations are either known or zero.
    }
  }
}
