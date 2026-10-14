// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Pure Java parsing for yt-dlp's --dump-single-json output; no native initialization is needed. */
public final class ExtractorJsonParser {
  private static final int MAX_BYTES = 2 * 1024 * 1024;
  private static final int MAX_ATTACHMENTS = 20;
  private static final String YOUTUBE = "Youtube";

  private ExtractorJsonParser() {}

  public static Post parse(String json, String sourceUrl) throws IOException {
    return parse(json, sourceUrl, VideoSupport.ANY);
  }

  /** YouTube qualities that {@code device} cannot merge or play are left out. */
  public static Post parse(String json, String sourceUrl, VideoSupport device)
      throws IOException {
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
    collect(root, attachments, 0, "", device);
    if (attachments.isEmpty()) {
      if (YOUTUBE.equalsIgnoreCase(firstString(root, "extractor_key", "extractor"))) {
        String live = root.optString("live_status", "");
        if ("is_live".equals(live) || "is_upcoming".equals(live) || "post_live".equals(live))
          throw new IOException(
              "This YouTube video is live or still processing. Try again after the stream ends.");
        throw new IOException("YouTube offers no version of this video that this phone can save.");
      }
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
      JSONObject info,
      Map<String, Media> attachments,
      int depth,
      String extractor,
      VideoSupport device) {
    if (depth > 2 || attachments.size() >= MAX_ATTACHMENTS) return;
    String own = firstString(info, "extractor_key", "extractor");
    if (!own.isEmpty()) extractor = own;
    boolean instagram = "Instagram".equalsIgnoreCase(extractor);
    JSONArray entries = info.optJSONArray("entries");
    if (entries != null) {
      for (int i = 0; i < entries.length() && attachments.size() < MAX_ATTACHMENTS; i++) {
        JSONObject entry = entries.optJSONObject(i);
        if (entry != null) collect(entry, attachments, depth + 1, extractor, device);
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
    if (YOUTUBE.equalsIgnoreCase(extractor) && formats != null)
      addYouTubePairs(formats, sharedHeaders, mp4, device);
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
    String label = quality(info, height) + " (" + extension.toUpperCase(Locale.ROOT) + ")";
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

  /** YouTube's separate video formats in order of preference, with the audio each merges with. */
  private enum Pairing {
    H264("mp4", VideoSupport.H264, "m4a", "mp4a", VideoSupport.AAC, VideoSupport.MP4, " (MP4)"),
    AV1("mp4", VideoSupport.AV1, "m4a", "mp4a", VideoSupport.AAC, VideoSupport.MP4, " (MP4)"),
    VP9("webm", VideoSupport.VP9, "webm", "opus", VideoSupport.OPUS, VideoSupport.WEBM, " (WebM)");

    final String videoExtension, video, audioExtension, audioCodec, audio, container, suffix;

    Pairing(
        String videoExtension,
        String video,
        String audioExtension,
        String audioCodec,
        String audio,
        String container,
        String suffix) {
      this.videoExtension = videoExtension;
      this.video = video;
      this.audioExtension = audioExtension;
      this.audioCodec = audioCodec;
      this.audio = audio;
      this.container = container;
      this.suffix = suffix;
    }

    /**
     * 8-bit SDR only, for every codec. HDR arrives as VP9 profile 2 or 10-bit AV1, and AI-upscaled
     * copies ("-sr") add bytes without detail.
     */
    boolean accepts(JSONObject format) {
      String codec = format.optString("vcodec");
      Object range = format.opt("dynamic_range");
      boolean codecMatches;
      if (this == H264) codecMatches = codec.startsWith("avc1");
      else if (this == AV1) codecMatches = codec.matches("av01\\.0\\.[0-9]{2}[MH]\\.08(?:\\..*)?");
      else codecMatches = "vp9".equals(codec) || codec.startsWith("vp09.00.");
      return codecMatches
          && (range == null || JSONObject.NULL.equals(range) || "SDR".equals(range))
          && !format.optString("format_id").endsWith("-sr");
    }
  }

  /**
   * YouTube serves most qualities as separate video and audio files, which the download merges
   * without re-encoding. Each height takes H.264 with AAC into MP4, then AV1 with AAC into MP4,
   * then VP9 with Opus into WebM: the first that {@code device} can merge and play at that size. A
   * complete file at the same height is kept instead of a pair; a height nothing plays is left out.
   */
  private static void addYouTubePairs(
      JSONArray formats,
      Map<String, String> sharedHeaders,
      List<Variant> variants,
      VideoSupport device) {
    for (Pairing pairing : Pairing.values())
      if (device.canMerge(pairing.container, pairing.video, pairing.audio))
        addPairs(formats, sharedHeaders, variants, pairing, device);
  }

  /**
   * Skips heights that {@code variants} already offers, and after H.264 also quality names:
   * YouTube's VP9 and AV1 ladders have extra sizes, such as a 1080×608 "480p", that would repeat
   * one.
   */
  private static void addPairs(
      JSONArray formats,
      Map<String, String> sharedHeaders,
      List<Variant> variants,
      Pairing pairing,
      VideoSupport device) {
    Set<Integer> heights = new HashSet<>();
    Set<String> qualities = new HashSet<>();
    for (Variant variant : variants) {
      heights.add(variant.height);
      qualities.add(variant.label.substring(0, Math.max(0, variant.label.lastIndexOf(" ("))));
    }
    JSONObject audio = null;
    for (int i = 0; i < formats.length(); i++) {
      JSONObject format = formats.optJSONObject(i);
      if (format != null
          && separateTrack(format)
          && "none".equals(format.optString("vcodec"))
          && pairing.audioExtension.equals(format.optString("ext"))
          && format.optString("acodec").startsWith(pairing.audioCodec)
          && !format.optString("format_id").contains("drc")
          && (audio == null || compareAudio(format, audio) > 0)) audio = format;
    }
    if (audio == null) return;
    boolean byName = pairing != Pairing.H264;
    Map<String, JSONObject> videos = new LinkedHashMap<>();
    for (int i = 0; i < formats.length(); i++) {
      JSONObject format = formats.optJSONObject(i);
      if (format == null
          || !separateTrack(format)
          || !"none".equals(format.optString("acodec"))
          || !pairing.videoExtension.equals(format.optString("ext"))
          || !pairing.accepts(format)) continue;
      int height = format.optInt("height", 0);
      String quality = quality(format, height);
      String key = byName ? quality : String.valueOf(height);
      JSONObject previous = videos.get(key);
      if (height > 0
          && !heights.contains(height)
          && !(byName && qualities.contains(quality))
          && (previous == null || kilobits(format) > kilobits(previous))
          && device.canDecode(
              pairing.video, Math.max(0, format.optInt("width", 0)), height, fps(format)))
        videos.put(key, format);
    }
    for (JSONObject video : videos.values()) {
      Map<String, String> requestHeaders = new LinkedHashMap<>(sharedHeaders);
      requestHeaders.putAll(headers(video.optJSONObject("http_headers")));
      int height = video.optInt("height");
      variants.add(
          new Variant(
              video.optString("url"),
              quality(video, height) + pairing.suffix,
              pairing.container,
              Math.round((kilobits(video) + kilobits(audio)) * 1000),
              Math.max(0, video.optInt("width", 0)),
              height,
              requestHeaders,
              null,
              audio.optString("url")));
    }
  }

  private static double fps(JSONObject format) {
    double value = format.optDouble("fps", 0);
    return Double.isNaN(value) || Double.isInfinite(value) || value < 0 ? 0 : value;
  }

  /**
   * The extractor's quality name, such as "1080p" or "720p60", which also names letterboxed
   * videos by their tier rather than their pixel height; otherwise the height.
   */
  private static String quality(JSONObject format, int height) {
    String note = format.optString("format_note", "");
    if (note.matches("[1-9][0-9]{2,3}p(?:[1-9][0-9]{1,2})?")) return note;
    return height > 0 ? height + "p" : "Video";
  }

  /** A whole HTTPS file holding one track: never a playlist, fragments or a manifest. */
  private static boolean separateTrack(JSONObject format) {
    String protocol = format.optString("protocol", "");
    return ("https".equals(protocol) || "http".equals(protocol))
        && !format.has("fragments")
        && !format.optBoolean("has_drm", false)
        && LinkParser.safeHttpsUri(format.optString("url", "")) != null;
  }

  /** The original-language track first, then the higher bitrate. */
  private static int compareAudio(JSONObject a, JSONObject b) {
    int language =
        Integer.compare(a.optInt("language_preference", -1), b.optInt("language_preference", -1));
    return language != 0 ? language : Double.compare(kilobits(a), kilobits(b));
  }

  private static double kilobits(JSONObject format) {
    double value = format.optDouble("tbr", 0);
    return Double.isNaN(value) || Double.isInfinite(value) || value < 0 ? 0 : value;
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
