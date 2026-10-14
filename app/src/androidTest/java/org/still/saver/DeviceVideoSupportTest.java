// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import static org.junit.Assert.*;

import android.os.Build;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.still.saver.core.ExtractorJsonParser;
import org.still.saver.core.Media;
import org.still.saver.core.Variant;
import org.still.saver.core.VideoSupport;

/** The real codec list and muxer limits that decide which YouTube qualities this device offers. */
@RunWith(AndroidJUnit4.class)
public final class DeviceVideoSupportTest {
  private static final String WATCH = "https://www.youtube.com/watch?v=legacy";
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

  @Test
  @SdkSuppress(maxSdkVersion = 28)
  public void youTubeOffersOnlyCompleteFilesBelowAndroid10() throws Exception {
    JSONArray formats =
        new JSONArray()
            .put(youtube("18", "mp4", "avc1.42001E", "mp4a.40.2", 360, 500))
            .put(youtube("22", "mp4", "avc1.64001F", "mp4a.40.2", 720, 1500))
            .put(youtube("137", "mp4", "avc1.640028", "none", 1080, 3000).put("container", "mp4_dash"))
            .put(youtube("248", "webm", "vp9", "none", 1080, 2500).put("container", "webm_dash"))
            .put(youtube("399", "mp4", "av01.0.08M.08", "none", 1080, 2000).put("container", "mp4_dash"))
            .put(youtube("140", "m4a", "none", "mp4a.40.2", 0, 129).put("container", "m4a_dash"))
            .put(youtube("251", "webm", "none", "opus", 0, 140).put("container", "webm_dash"));
    JSONObject root =
        new JSONObject()
            .put("id", "legacy")
            .put("title", "Separate tracks")
            .put("extractor_key", "Youtube")
            .put("formats", formats);
    Media media = ExtractorJsonParser.parse(root.toString(), WATCH, device).media.get(0);
    assertEquals(2, media.variants.size());
    for (Variant variant : media.variants) {
      assertNull(variant.label, variant.audioUrl);
      assertEquals("video/mp4", variant.mimeType);
    }
    assertEquals(720, media.chooseVariant("best").height);
    assertEquals(360, media.chooseVariant("smallest").height);

    formats.remove(1);
    formats.remove(0);
    try {
      ExtractorJsonParser.parse(root.toString(), WATCH, device);
      fail("Offered separate tracks that this Android version cannot merge");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("this phone can save"));
    }
  }

  private static JSONObject youtube(
      String id, String extension, String video, String audio, int height, int kilobits)
      throws Exception {
    return new JSONObject()
        .put("format_id", id)
        .put("url", "https://rr1.googlevideo.example/videoplayback?itag=" + id)
        .put("ext", extension)
        .put("protocol", "https")
        .put("vcodec", video)
        .put("acodec", audio)
        .put("height", height)
        .put("width", height * 16 / 9)
        .put("tbr", kilobits);
  }
}
