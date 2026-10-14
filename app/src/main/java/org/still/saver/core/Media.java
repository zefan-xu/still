// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A photo, video, or animated GIF, with qualities ordered from best to smallest. */
public final class Media {
  public final String kind;
  public final List<Variant> variants;
  /** A preview image URL that passed {@link LinkParser#safePreviewUri}, or null without one. */
  public final String thumbnail;
  /** Playback length in milliseconds; 0 when the source does not report it. */
  public final long durationMs;

  /** Media without preview metadata. */
  public Media(String kind, List<Variant> variants) {
    this(kind, variants, null, 0);
  }

  public Media(String kind, List<Variant> variants, String thumbnail, long durationMs) {
    if (variants == null || variants.isEmpty()) {
      throw new IllegalArgumentException("Media must have a downloadable variant");
    }
    // Resolvers filter untrusted metadata; an unsafe value here is a programming error.
    if (thumbnail != null && LinkParser.safePreviewUri(thumbnail) == null) {
      throw new IllegalArgumentException("Unsafe media preview URL");
    }
    if (durationMs < 0) throw new IllegalArgumentException("Negative media duration");
    this.kind = kind;
    this.thumbnail = thumbnail;
    this.durationMs = durationMs;
    Map<String, Variant> unique = new LinkedHashMap<>();
    for (Variant variant : variants) {
      Variant previous = unique.get(variant.url);
      if (previous == null || compareQuality(variant, previous) < 0) {
        unique.put(variant.url, variant);
      }
    }
    List<Variant> sorted = new ArrayList<>(unique.values());
    Collections.sort(
        sorted,
        new Comparator<Variant>() {
          @Override
          public int compare(Variant a, Variant b) {
            return compareQuality(a, b);
          }
        });
    this.variants = Collections.unmodifiableList(sorted);
  }

  /** The UI handles prompting for "ask"; its automatic fallback is the best variant. */
  public Variant chooseVariant(String quality) {
    return variants.get("smallest".equals(quality) ? variants.size() - 1 : 0);
  }

  /**
   * Size first: codecs differ in efficiency, so an AV1 1440p can have a lower bitrate than an H.264
   * 1080p. Unknown sizes rank below known ones; bitrate then breaks ties.
   */
  private static int compareQuality(Variant a, Variant b) {
    int pixels = Long.compare((long) b.width * b.height, (long) a.width * a.height);
    if (pixels != 0) return pixels;
    int bitrate = Long.compare(b.bitrate, a.bitrate);
    if (bitrate != 0) return bitrate;
    // Some public embeds label HD/SD but provide no numeric metadata.
    int labelledQuality = Integer.compare(labelRank(b.label), labelRank(a.label));
    if (labelledQuality != 0) return labelledQuality;
    return a.url.compareTo(b.url);
  }

  private static int labelRank(String label) {
    if ("HD MP4".equals(label)) return 2;
    if ("SD MP4".equals(label)) return 0;
    return 1;
  }
}
