// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import static org.junit.Assert.*;

import android.os.Build;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.still.saver.core.VideoSupport;

/** The real codec list and muxer limits that decide which YouTube qualities this device offers. */
@RunWith(AndroidJUnit4.class)
public final class DeviceVideoSupportTest {
  private final VideoSupport device = DeviceVideoSupport.get();

  @Test
  public void decodersAnswerForRealAndImpossibleSizes() {
    assertTrue(device.canDecode(VideoSupport.H264, 320, 240, 25));
    assertTrue(device.canDecode(VideoSupport.H264, 320, 240, 0));
    assertTrue("Unknown size", device.canDecode(VideoSupport.H264, 0, 0, 0));
    assertFalse(device.canDecode(VideoSupport.H264, 100000, 100000, 30));
    assertFalse(device.canDecode(VideoSupport.VP9, 100000, 100000, 0));
    assertFalse(device.canDecode(VideoSupport.H264, 320, 240, 100000));
    assertFalse(device.canDecode("video/x-still-none", 320, 240, 25));
    assertFalse(device.canDecode("video/x-still-none", 0, 0, 0));
    // Cached answers stay the same.
    assertTrue(device.canDecode(VideoSupport.H264, 320, 240, 25));
    for (String codec : new String[] {VideoSupport.H264, VideoSupport.VP9, VideoSupport.AV1})
      for (int[] size : new int[][] {{1920, 1080}, {2560, 1440}, {3840, 2160}})
        for (int fps : new int[] {30, 60})
          Log.i(
              "StillVideoSupport",
              codec
                  + " "
                  + size[0]
                  + "x"
                  + size[1]
                  + "@"
                  + fps
                  + " "
                  + device.canDecode(codec, size[0], size[1], fps));
  }

  @Test
  public void mergesOnlyWhatMediaMuxerWrites() {
    assertEquals(
        Build.VERSION.SDK_INT >= 29,
        device.canMerge(VideoSupport.MP4, VideoSupport.H264, VideoSupport.AAC));
    assertEquals(
        Build.VERSION.SDK_INT >= 34,
        device.canMerge(VideoSupport.MP4, VideoSupport.AV1, VideoSupport.AAC));
    assertEquals(
        Build.VERSION.SDK_INT >= 29,
        device.canMerge(VideoSupport.WEBM, VideoSupport.VP9, VideoSupport.OPUS));
    assertFalse(device.canMerge(VideoSupport.WEBM, VideoSupport.VP9, VideoSupport.AAC));
    assertFalse(device.canMerge(VideoSupport.MP4, VideoSupport.VP9, VideoSupport.AAC));
    assertFalse(device.canMerge(VideoSupport.MP4, VideoSupport.H264, VideoSupport.OPUS));
    assertFalse(device.canMerge(VideoSupport.WEBM, VideoSupport.AV1, VideoSupport.OPUS));
  }
}
