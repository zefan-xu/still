// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

/**
 * What this device can merge and play. The extractor parser offers a separate-track quality only
 * when both answers are yes, so a phone never sees a quality it would fail to save or play.
 */
public interface VideoSupport {
  String MP4 = "video/mp4";
  String WEBM = "video/webm";
  String H264 = "video/avc";
  String AV1 = "video/av01";
  String VP9 = "video/x-vnd.on2.vp9";
  String AAC = "audio/mp4a-latm";
  String OPUS = "audio/opus";

  /** Every combination at every size, for pure-Java callers that play nothing themselves. */
  VideoSupport ANY =
      new VideoSupport() {
        @Override
        public boolean canMerge(String container, String video, String audio) {
          return true;
        }

        @Override
        public boolean canDecode(String video, int width, int height, double fps) {
          return true;
        }
      };

  /** Whether the merge can copy {@code video} and {@code audio} tracks into {@code container}. */
  boolean canMerge(String container, String video, String audio);

  /** Whether a decoder plays {@code video} at this size and rate; {@code fps} is 0 if unknown. */
  boolean canDecode(String video, int width, int height, double fps);
}
