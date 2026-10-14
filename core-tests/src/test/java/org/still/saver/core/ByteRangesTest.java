// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.*;

import java.util.Collections;
import org.junit.Test;

public class ByteRangesTest {
  @Test
  public void requestsTenMegabyteChunksEndingAtTheFileEnd() {
    assertEquals("bytes=0-0", ByteRanges.request(0, 1));
    assertEquals("bytes=0-10485759", ByteRanges.request(0, 26455880));
    assertEquals("bytes=10485760-20971519", ByteRanges.request(10485760, 26455880));
    assertEquals("bytes=20971520-26455879", ByteRanges.request(20971520, 26455880));
    assertEquals("bytes=5-10485764", ByteRanges.request(5, 10485765));
    for (long[] invalid : new long[][] {{-1, 10}, {10, 10}, {11, 10}, {0, 0}}) {
      try {
        ByteRanges.request(invalid[0], invalid[1]);
        fail("Accepted " + invalid[0] + " of " + invalid[1]);
      } catch (IllegalArgumentException expected) {
        /* expected */
      }
    }
  }

  @Test
  public void parsesOnlyCompleteValidContentRanges() {
    assertArrayEquals(
        new long[] {0, 10485759, 26455880}, ByteRanges.parse("bytes 0-10485759/26455880"));
    assertArrayEquals(new long[] {0, 0, 1}, ByteRanges.parse(" bytes 0-0/1 "));
    String[] invalid = {
      null,
      "",
      "bytes 0-9/*",
      "bytes */100",
      "bytes 10-9/100",
      "bytes 0-100/100",
      "bytes -1-9/100",
      "items 0-9/100",
      "bytes 0-9/100, 20-29/100",
      "bytes 0-9999999999999999999/99999999999999999999",
    };
    for (String header : invalid) assertNull(header, ByteRanges.parse(header));
  }

  @Test
  public void variantsAcceptOnlyHttpsAudioTracks() {
    Variant merged =
        new Variant(
            "https://cdn.example/video.mp4",
            "720p (MP4)",
            "video/mp4",
            0,
            1280,
            720,
            Collections.<String, String>emptyMap(),
            null,
            "https://cdn.example/audio.m4a");
    assertEquals("https://cdn.example/audio.m4a", merged.audioUrl);
    assertNull(new Variant("https://cdn.example/a.mp4", "MP4", "video/mp4", 0, 0, 0).audioUrl);
    try {
      new Variant(
          "https://cdn.example/video.mp4",
          "720p (MP4)",
          "video/mp4",
          0,
          1280,
          720,
          Collections.<String, String>emptyMap(),
          null,
          "http://cdn.example/audio.m4a");
      fail("Accepted an insecure audio track");
    } catch (IllegalArgumentException expected) {
      /* expected */
    }
  }
}
