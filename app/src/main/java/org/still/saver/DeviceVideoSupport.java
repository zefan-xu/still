// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.still.saver.core.VideoSupport;

/**
 * This device's {@link StreamMerger} and video decoders, hardware or software. Answers are cached:
 * listing codecs is slow, and every extraction asks about each separate-track quality.
 */
public final class DeviceVideoSupport implements VideoSupport {
  private static final DeviceVideoSupport INSTANCE = new DeviceVideoSupport();
  private static final int ANDROID_10 = 29;
  private static final int ANDROID_14 = 34;

  private final Map<String, List<MediaCodecInfo.VideoCapabilities>> decoders =
      new ConcurrentHashMap<>();
  private final Map<String, Boolean> answers = new ConcurrentHashMap<>();
  private volatile MediaCodecInfo[] codecs;

  private DeviceVideoSupport() {}

  public static DeviceVideoSupport get() {
    return INSTANCE;
  }

  /**
   * MediaMuxer writes H.264 with AAC into MP4 and Opus into WebM from Android 10, and AV1 into MP4
   * from Android 14. Android 7.0's MP4 writer rejects the B-frames in YouTube's H.264, and 7.1 to 9
   * are unverified, so they are offered complete files only.
   */
  @Override
  public boolean canMerge(String container, String video, String audio) {
    if (MP4.equals(container) && AAC.equals(audio)) {
      if (H264.equals(video)) return Build.VERSION.SDK_INT >= ANDROID_10;
      if (AV1.equals(video)) return Build.VERSION.SDK_INT >= ANDROID_14;
    }
    return WEBM.equals(container)
        && VP9.equals(video)
        && OPUS.equals(audio)
        && Build.VERSION.SDK_INT >= ANDROID_10;
  }

  /**
   * A size of 0 is unknown, so any decoder of the type counts. A codec service that throws counts
   * as no decoder, so only complete files are offered.
   */
  @Override
  public boolean canDecode(String video, int width, int height, double fps) {
    String key = video + ' ' + width + 'x' + height + '@' + fps;
    Boolean cached = answers.get(key);
    if (cached != null) return cached;
    List<MediaCodecInfo.VideoCapabilities> found = decoders(video);
    boolean supported = !found.isEmpty() && (width <= 0 || height <= 0);
    for (int i = 0; i < found.size() && !supported; i++) {
      try {
        supported =
            found.get(i).isSizeSupported(width, height)
                && (fps <= 0 || found.get(i).areSizeAndRateSupported(width, height, fps));
      } catch (RuntimeException broken) {
        // This decoder's capabilities are unreadable; another may still play the size.
      }
    }
    answers.put(key, supported);
    return supported;
  }

  private List<MediaCodecInfo.VideoCapabilities> decoders(String mime) {
    List<MediaCodecInfo.VideoCapabilities> cached = decoders.get(mime);
    if (cached != null) return cached;
    List<MediaCodecInfo.VideoCapabilities> found = new ArrayList<>();
    for (MediaCodecInfo codec : codecs()) {
      try {
        if (codec.isEncoder()) continue;
        for (String type : codec.getSupportedTypes()) {
          if (!type.equalsIgnoreCase(mime)) continue;
          MediaCodecInfo.VideoCapabilities video =
              codec.getCapabilitiesForType(type).getVideoCapabilities();
          if (video != null) found.add(video);
        }
      } catch (RuntimeException broken) {
        // The codec withdrew the type or its service failed; another decoder may still play it.
      }
    }
    decoders.put(mime, found);
    return found;
  }

  /** Empty when the codec service fails, so every separate-track quality is hidden. */
  private MediaCodecInfo[] codecs() {
    MediaCodecInfo[] list = codecs;
    if (list == null) {
      try {
        list = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
      } catch (RuntimeException broken) {
        list = new MediaCodecInfo[0];
      }
      codecs = list;
    }
    return list;
  }
}
