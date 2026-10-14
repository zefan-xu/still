// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One downloadable file. GIF animations from X use the video/mp4 MIME type. */
public final class Variant {
  public final String url;
  public final String label;
  public final String mimeType;
  public final long bitrate;
  public final int width;
  public final int height;
  public final Map<String, String> headers;
  public final String scopedCookies;
  /** A separate audio track that must be merged with {@link #url}'s video; null for one file. */
  public final String audioUrl;

  public Variant(String url, String label, String mimeType, long bitrate, int width, int height) {
    this(url, label, mimeType, bitrate, width, height, Collections.<String, String>emptyMap());
  }

  public Variant(
      String url,
      String label,
      String mimeType,
      long bitrate,
      int width,
      int height,
      Map<String, String> headers) {
    this(url, label, mimeType, bitrate, width, height, headers, null);
  }

  /**
   * Only the extractor parser supplies cookies with explicit URL scope; raw headers are ignored.
   */
  Variant(
      String url,
      String label,
      String mimeType,
      long bitrate,
      int width,
      int height,
      Map<String, String> headers,
      String scopedCookies) {
    this(url, label, mimeType, bitrate, width, height, headers, scopedCookies, null);
  }

  Variant(
      String url,
      String label,
      String mimeType,
      long bitrate,
      int width,
      int height,
      Map<String, String> headers,
      String scopedCookies,
      String audioUrl) {
    if (audioUrl != null && LinkParser.safeHttpsUri(audioUrl) == null)
      throw new IllegalArgumentException("The audio track must use HTTPS");
    this.url = url;
    this.audioUrl = audioUrl;
    this.label = label;
    this.mimeType = mimeType;
    this.bitrate = Math.max(0, bitrate);
    this.width = Math.max(0, width);
    this.height = Math.max(0, height);
    Map<String, String> safeHeaders = new LinkedHashMap<>();
    if (headers != null) {
      for (Map.Entry<String, String> header : headers.entrySet()) {
        String name = header.getKey();
        String value = header.getValue();
        if (name == null
            || value == null
            || value.length() > 2048
            || value.indexOf('\r') >= 0
            || value.indexOf('\n') >= 0) continue;
        if ("User-Agent".equalsIgnoreCase(name) && value.length() <= 1024) {
          safeHeaders.put("User-Agent", value);
        } else if ("Referer".equalsIgnoreCase(name) && LinkParser.safeHttpsUri(value) != null) {
          safeHeaders.put("Referer", value);
        }
      }
    }
    this.scopedCookies = ExtractorCookies.sanitize(scopedCookies);
    this.headers = Collections.unmodifiableMap(safeHeaders);
  }
}
