// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTTP byte ranges for chunked downloads; YouTube throttles long single responses. */
public final class ByteRanges {
  public static final long CHUNK_BYTES = 10L * 1024 * 1024;
  private static final Pattern CONTENT_RANGE =
      Pattern.compile("bytes ([0-9]{1,18})-([0-9]{1,18})/([0-9]{1,18})");

  private ByteRanges() {}

  /** The Range header for the chunk of a {@code length}-byte file that starts at {@code start}. */
  public static String request(long start, long length) {
    if (start < 0 || start >= length) throw new IllegalArgumentException("Range outside the file");
    return "bytes=" + start + "-" + (Math.min(start + CHUNK_BYTES, length) - 1);
  }

  /** {first, last, length} from a Content-Range header, or null unless it is a valid range. */
  public static long[] parse(String contentRange) {
    if (contentRange == null) return null;
    Matcher match = CONTENT_RANGE.matcher(contentRange.trim());
    if (!match.matches()) return null;
    long first = Long.parseLong(match.group(1));
    long last = Long.parseLong(match.group(2));
    long length = Long.parseLong(match.group(3));
    if (last < first || last >= length) return null;
    return new long[] {first, last, length};
  }
}
