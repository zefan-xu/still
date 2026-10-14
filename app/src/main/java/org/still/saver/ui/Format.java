// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.ui;

import java.util.Locale;

/** Short, human labels for media metadata. Pure Java so it is covered by JVM tests. */
public final class Format {
  private Format() {}

  public static String duration(long ms) {
    if (ms <= 0) return "";
    long seconds = (ms + 500) / 1000;
    long hours = seconds / 3600;
    long minutes = (seconds % 3600) / 60;
    long rest = seconds % 60;
    return hours > 0
        ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
        : String.format(Locale.ROOT, "%d:%02d", minutes, rest);
  }

  public static String size(long bytes) {
    if (bytes < 0) return "";
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return Math.round(bytes / 1024.0) + " KB";
    if (bytes < 1024L * 1024 * 1024)
      return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    return String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
  }

  /** "1080p" for video-like sizes, "4096 × 2304" otherwise; empty when unknown. */
  public static String resolution(int width, int height, boolean video) {
    if (width <= 0 || height <= 0) return "";
    return video ? Math.min(width, height) + "p" : width + " × " + height;
  }

  /** "video", "gif" or "photo" for the names extractors use; other kinds are returned as given. */
  public static String kind(String kind) {
    switch (kind.toLowerCase(Locale.ROOT)) {
      case "video":
        return "video";
      case "gif":
      case "animated_gif":
        return "gif";
      case "photo":
      case "image":
        return "photo";
      default:
        return kind;
    }
  }

  /** A kind the app has no word for, as a label: "audio" reads "Audio". */
  public static String capitalized(String kind) {
    if (kind.isEmpty()) return kind;
    return kind.substring(0, 1).toUpperCase(Locale.ROOT) + kind.substring(1);
  }
}
