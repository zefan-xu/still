// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Pure Java parsing for yt-dlp's --dump-single-json output; no native initialization is needed. */
public final class ExtractorJsonParser {
  private static final int MAX_BYTES = 2 * 1024 * 1024;
  private static final int MAX_ATTACHMENTS = 20;

  private ExtractorJsonParser() {}

  public static Post parse(String json, String sourceUrl) throws IOException {
    if (json == null
        || json.length() > MAX_BYTES
        || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw new IOException("The extractor response is empty or too large.");
    }
    JSONObject root;
    try {
      root = new JSONObject(json);
    } catch (JSONException e) {
      throw new IOException("The extractor returned unreadable media information.", e);
    }
    Map<String, Media> attachments = new LinkedHashMap<>();
    collect(root, attachments, 0, false);
    if (attachments.isEmpty()) {
      throw new IOException(
          "This post has no downloadable complete video file. It may require login or use a"
              + " streaming-only format.");
    }
    String title = firstString(root, "title", "description");
    if (title.isEmpty()) title = "Public post media";
    return new Post(
        root.optString("id", ""),
        title,
        firstString(root, "uploader", "channel", "uploader_id"),
        sourceUrl,
        new ArrayList<>(attachments.values()));
  }

  private static void collect(
      JSONObject info, Map<String, Media> attachments, int depth, boolean instagram) {
    if (depth > 2 || attachments.size() >= MAX_ATTACHMENTS) return;
    String extractor = firstString(info, "extractor_key", "extractor");
    if (!extractor.isEmpty()) instagram = "Instagram".equalsIgnoreCase(extractor);
    JSONArray entries = info.optJSONArray("entries");
    if (entries != null) {
      for (int i = 0; i < entries.length() && attachments.size() < MAX_ATTACHMENTS; i++) {
        JSONObject entry = entries.optJSONObject(i);
        if (entry != null) collect(entry, attachments, depth + 1, instagram);
      }
      return;
    }
    Map<String, String> sharedHeaders = headers(info.optJSONObject("http_headers"));
    String sharedCookies = info.optString("cookies", "");
    boolean gif = info.optBoolean("is_gif", false) || "gif".equals(info.optString("media_type"));
    List<Variant> mp4 = new ArrayList<>();
    List<Variant> webm = new ArrayList<>();
    JSONArray formats = info.optJSONArray("formats");
    if (formats != null) {
      for (int i = 0; i < formats.length(); i++) {
        JSONObject format = formats.optJSONObject(i);
        if (format != null)
          addFormat(format, sharedHeaders, sharedCookies, gif, instagram, mp4, webm);
      }
    }
    if (mp4.isEmpty() && webm.isEmpty())
      addFormat(info, sharedHeaders, sharedCookies, gif, instagram, mp4, webm);
    List<Variant> variants = mp4.isEmpty() ? webm : mp4;
    if (variants.isEmpty()) return;
    String id = info.optString("id", "");
    String key = id.isEmpty() ? variants.get(0).url : id;
    if (!attachments.containsKey(key))
      attachments.put(
          key, new Media(gif ? "gif" : "video", variants, thumbnail(info), durationMs(info)));
  }

  /** Only the entry's own preview; a playlist parent's image would label the wrong carousel item. */
  private static String thumbnail(JSONObject info) {
    Object value = info.opt("thumbnail");
    if (!(value instanceof String)) return null;
    return LinkParser.safePreviewUri((String) value) == null ? null : (String) value;
  }

  /** yt-dlp reports duration in seconds. */
  private static long durationMs(JSONObject info) {
    Object value = info.opt("duration");
    if (!(value instanceof Number)) return 0;
    double seconds = ((Number) value).doubleValue();
    if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds <= 0) return 0;
    return Math.round(seconds * 1000);
  }

  private static void addFormat(
      JSONObject info,
      Map<String, String> sharedHeaders,
      String sharedCookies,
      boolean gif,
      boolean instagram,
      List<Variant> mp4,
      List<Variant> webm) {
    String url = info.optString("url", "");
    URI uri = LinkParser.safeHttpsUri(url);
    if (uri == null) return;
    String path = uri.getPath().toLowerCase(Locale.ROOT);
    if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return;
    String protocol = info.optString("protocol", "");
    if (!protocol.isEmpty() && !"https".equals(protocol) && !"http".equals(protocol)) return;
    String videoCodec = info.optString("vcodec", "");
    String audioCodec = info.optString("acodec", "");
    // TikTok exposes ByteVC2 files in MP4 containers but marks them as unplayable.
    // A high bitrate must not promote those files above usable progressive formats.
    if ("bytevc2".equalsIgnoreCase(videoCodec)
        || info.optString("format_note", "").toUpperCase(Locale.ROOT).contains("UNPLAYABLE"))
      return;
    // Progressive formats from several social extractors omit codec names. Explicit
    // "none" marks a split audio/video track; an omitted codec remains unknown.
    if ("none".equals(videoCodec)) return;
    // Instagram's progressive video_versions are complete files, including silent
    // videos with or without codec metadata. Its split DASH tracks are excluded below.
    if (!gif && !instagram && "none".equals(audioCodec) && !videoCodec.isEmpty()) return;
    if (info.optString("container", "").contains("_dash")
        || info.optString("format_id", "").startsWith("dash-")
        || info.has("fragments")) return;
    String extension = info.optString("ext", "").toLowerCase(Locale.ROOT);
    if (extension.isEmpty()) extension = path.substring(path.lastIndexOf('.') + 1);
    if (!"mp4".equals(extension) && !"webm".equals(extension)) return;
    Map<String, String> requestHeaders = new LinkedHashMap<>(sharedHeaders);
    requestHeaders.putAll(headers(info.optJSONObject("http_headers")));
    int width = Math.max(0, info.optInt("width", 0));
    int height = Math.max(0, info.optInt("height", 0));
    double kilobits = info.optDouble("tbr", 0);
    if (!(kilobits > 0)) kilobits = info.optDouble("vbr", 0) + info.optDouble("abr", 0);
    long bitrate =
        Double.isNaN(kilobits) || Double.isInfinite(kilobits)
            ? 0
            : Math.max(0, Math.round(kilobits * 1000));
    String label =
        (height > 0 ? height + "p" : "Video") + " (" + extension.toUpperCase(Locale.ROOT) + ")";
    Variant variant =
        new Variant(
            url,
            label,
            "video/" + extension,
            bitrate,
            width,
            height,
            requestHeaders,
            info.optString("cookies", sharedCookies));
    ("mp4".equals(extension) ? mp4 : webm).add(variant);
  }

  private static Map<String, String> headers(JSONObject object) {
    Map<String, String> result = new LinkedHashMap<>();
    if (object == null) return result;
    java.util.Iterator<String> keys = object.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if ("User-Agent".equalsIgnoreCase(key) || "Referer".equalsIgnoreCase(key)) {
        Object value = object.opt(key);
        if (value instanceof String) result.put(key, (String) value);
      }
    }
    return result;
  }

  private static String firstString(JSONObject object, String... names) {
    for (String name : names) {
      String value = object.optString(name, "").trim();
      if (!value.isEmpty()) return value;
    }
    return "";
  }
}
