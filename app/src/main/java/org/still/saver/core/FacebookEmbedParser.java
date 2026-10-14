// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** Parses the ordinary public Facebook video embed; never selects recommended videos. */
public final class FacebookEmbedParser {
  private static final int MAX_BYTES = 2 * 1024 * 1024;
  private static final int MAX_JSON_CHARS = 1024 * 1024;
  private static final int MAX_JSON_ROOTS = 64;
  private static final int MAX_DEPTH = 64;
  private static final int MAX_NODES = 50000;
  private static final int MAX_VARIANTS = 20;
  private static final String ID = "[1-9][0-9]{0,24}";
  private static final Pattern PATH_ID =
      Pattern.compile(
          "^/(?:reel/|[A-Za-z0-9._-]+/(?:videos/(?:[A-Za-z0-9._-]+/)?|posts/)"
              + "|groups/[A-Za-z0-9._-]+/(?:permalink|posts)/)("
              + ID
              + ")/?$");
  private static final Pattern QUERY_ID =
      Pattern.compile("(?:^|&)(?:v|video_id|story_fbid)=(" + ID + ")(?:&|$)");
  private static final Pattern SERVER_JSON =
      Pattern.compile("(?:\\bhandleServerJS|\\bs\\.handle)\\s*\\(");

  private FacebookEmbedParser() {}

  /** An explicit embed state, rather than an extraction or transport failure. */
  public static final class UnavailableException extends IOException {
    public UnavailableException() {
      super("Facebook says this video is unavailable to public viewers.");
    }
  }

  /** Returns only a known numerical post/video ID; short and share links need resolution first. */
  public static String videoId(String sourceUrl) {
    if (sourceUrl == null || sourceUrl.length() > 8192) return null;
    URI uri = LinkParser.safeHttpsUri(sourceUrl);
    if (uri == null || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    if (!"facebook.com".equals(host)
        && !"www.facebook.com".equals(host)
        && !"m.facebook.com".equals(host)
        && !"mbasic.facebook.com".equals(host)) return null;
    Matcher path = PATH_ID.matcher(uri.getRawPath());
    if (path.matches()) return path.group(1);
    if (!uri.getRawPath()
        .matches(
            "/(?:watch(?:/live)?/?|(?:video/)?video\\.php|video/embed|photo\\.php"
                + "|story\\.php|permalink\\.php)")) return null;
    if (uri.getRawQuery() == null) return null;
    String found = null;
    // Splitting prevents an ampersand consumed by one match from hiding a conflicting ID.
    for (String query : uri.getRawQuery().split("&")) {
      Matcher value = QUERY_ID.matcher(query);
      if (!value.matches()) {
        if (query.matches("(?:v|video_id|story_fbid)(?:=.*)?")) return null;
        continue;
      }
      if (found != null && !found.equals(value.group(1))) return null;
      found = value.group(1);
    }
    return found;
  }

  public static Post parse(String html, String sourceUrl) throws IOException {
    String target = videoId(sourceUrl);
    if (target == null) throw new IOException("The Facebook link has no known numerical video ID.");
    if (html == null
        || html.length() > MAX_BYTES
        || html.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw new IOException("The Facebook embed response is empty or too large.");
    }
    Document document = Jsoup.parse(html, sourceUrl);
    Scan scan = new Scan(target);
    for (Element script : document.select("script")) {
      if (scan.roots >= MAX_JSON_ROOTS || scan.nodes >= MAX_NODES) {
        scan.limited = true;
        break;
      }
      String data = script.data();
      int beginning = skipWhitespace(data, 0);
      if ((script.hasAttr("data-sjs") || "application/json".equalsIgnoreCase(script.attr("type")))
          && beginning < data.length()
          && (data.charAt(beginning) == '{' || data.charAt(beginning) == '[')) {
        scan.json(data, beginning);
        continue;
      }
      Matcher calls = SERVER_JSON.matcher(data);
      int offset = 0;
      while (calls.find(offset) && scan.roots < MAX_JSON_ROOTS && scan.nodes < MAX_NODES) {
        int start = skipWhitespace(data, calls.end());
        int end = scan.json(data, start);
        offset = end > start ? end : calls.end();
      }
    }
    if (scan.roots >= MAX_JSON_ROOTS || scan.nodes >= MAX_NODES) scan.limited = true;
    if (!scan.variants.isEmpty()) {
      String title = scan.title;
      if (title.isEmpty()) {
        title = document.select("meta[property=og:title]").attr("content").trim();
        if (title.isEmpty() || "Facebook".equalsIgnoreCase(title)) title = "Facebook video";
      }
      return new Post(
          target,
          title,
          scan.author,
          sourceUrl,
          Collections.singletonList(
              new Media(
                  "video",
                  new ArrayList<>(scan.variants.values()),
                  scan.thumbnail,
                  scan.durationMs)));
    }
    if (!scan.limited && explicitlyUnavailable(document)) throw new UnavailableException();
    throw new IOException("The public Facebook embed has no playable file for this video.");
  }

  private static boolean explicitlyUnavailable(Document document) {
    document.select("script,style,noscript,template,[hidden],[aria-hidden=true],.hidden_elem").remove();
    // The player includes a generic failure overlay even when the video is publicly playable.
    // Only the standalone embed error, including its specific explanation, is authoritative.
    for (Element block :
        document.select(".pluginSkinLight.pluginFontHelvetica > ._2gn_ > ._2go0")) {
      boolean title = false;
      boolean explanation = false;
      for (Element child : block.children()) {
        if (child.hasClass("_3i0o")
            && child.hasClass("_50f7")
            && "Video Unavailable".equalsIgnoreCase(child.text().trim())) title = true;
        if (child.hasClass("_3i0p")
            && "This video may no longer exist, or you don't have permission to view it."
                .equals(child.text().trim())) explanation = true;
      }
      if (title && explanation) return true;
    }
    return false;
  }

  private static int skipWhitespace(String text, int offset) {
    while (offset < text.length() && Character.isWhitespace(text.charAt(offset))) offset++;
    return offset;
  }

  private static String recordId(JSONObject object) {
    String found = "";
    for (String key : new String[] {"video_id", "videoId", "id"}) {
      Object value = object.opt(key);
      if (value == null || value == JSONObject.NULL) continue;
      if (value instanceof String || value instanceof Number) {
        String id = String.valueOf(value);
        if (!id.matches(ID) || (!found.isEmpty() && !found.equals(id))) return "<invalid>";
        found = id;
      } else {
        return "<invalid>";
      }
    }
    return found;
  }

  private static final class Scan {
    final String target;
    final Map<String, Variant> variants = new LinkedHashMap<>();
    String title = "";
    String author = "";
    String thumbnail;
    long durationMs;
    int roots;
    int nodes;
    int jsonChars;
    boolean limited;

    Scan(String target) {
      this.target = target;
    }

    int json(String script, int start) {
      if (start >= script.length()
          || (script.charAt(start) != '{' && script.charAt(start) != '[')) return start;
      roots++;
      int depth = 0;
      boolean string = false;
      boolean escaped = false;
      for (int i = start; i < script.length() && i - start < MAX_JSON_CHARS; i++) {
        char c = script.charAt(i);
        if (string) {
          if (escaped) escaped = false;
          else if (c == '\\') escaped = true;
          else if (c == '"') string = false;
        } else if (c == '"') string = true;
        else if (c == '{' || c == '[') {
          if (++depth > MAX_DEPTH) {
            limited = true;
            return script.length();
          }
        } else if (c == '}' || c == ']') {
          if (--depth == 0) {
            int end = i + 1;
            jsonChars += end - start;
            if (jsonChars > MAX_BYTES) {
              limited = true;
              return script.length();
            }
            try {
              String json = script.substring(start, end);
              visit(json.charAt(0) == '{' ? new JSONObject(json) : new JSONArray(json), 0);
            } catch (JSONException ignored) {
              /* Malformed scripts are not evidence that the requested video is available. */
            }
            return end;
          }
          if (depth < 0) return i + 1;
        }
      }
      if (script.length() - start >= MAX_JSON_CHARS) limited = true;
      return script.length();
    }

    void visit(Object value, int depth) {
      if (++nodes > MAX_NODES || depth > MAX_DEPTH) {
        limited = true;
        return;
      }
      if (value instanceof JSONObject) {
        JSONObject object = (JSONObject) value;
        if (target.equals(recordId(object))) video(object, 0);
        Iterator<String> keys = object.keys();
        while (keys.hasNext() && nodes < MAX_NODES) visit(object.opt(keys.next()), depth + 1);
      } else if (value instanceof JSONArray) {
        JSONArray array = (JSONArray) value;
        for (int i = 0; i < array.length() && nodes < MAX_NODES; i++) visit(array.opt(i), depth + 1);
      }
    }

    void video(JSONObject video, int depth) {
      if (++nodes > MAX_NODES || depth > 8) {
        limited = true;
        return;
      }
      if (variants.size() >= MAX_VARIANTS) return;
      String id = recordId(video);
      if (!id.isEmpty() && !target.equals(id)) return;
      if (title.isEmpty()) title = text(video, "title", "name");
      if (author.isEmpty()) {
        author = text(video, "ownerName");
        JSONObject owner = video.optJSONObject("owner");
        if (author.isEmpty() && owner != null) author = text(owner, "name");
      }
      if (thumbnail == null) thumbnail = thumbnail(video);
      if (durationMs == 0) durationMs = durationMs(video);
      add(video, "sd_src", "SD MP4");
      add(video, "hd_src", "HD MP4");
      add(video, "browser_native_sd_url", "SD MP4");
      add(video, "browser_native_hd_url", "HD MP4");
      add(video, "playable_url", "SD MP4");
      add(video, "playable_url_quality_hd", "HD MP4");
      add(video, "progressive_url", "MP4");
      for (String key : new String[] {"videoData", "progressive_urls"}) {
        Object rows = video.opt(key);
        if (rows instanceof JSONObject) video((JSONObject) rows, depth + 1);
        else if (rows instanceof JSONArray) {
          JSONArray array = (JSONArray) rows;
          for (int i = 0;
              i < Math.min(array.length(), MAX_VARIANTS) && variants.size() < MAX_VARIANTS;
              i++) {
            JSONObject row = array.optJSONObject(i);
            if (row != null) video(row, depth + 1);
          }
        }
      }
      for (String key :
          new String[] {
            "videoDeliveryLegacyFields", "videoDeliveryResponseFragment", "videoDeliveryResponseResult"
          }) {
        JSONObject nested = video.optJSONObject(key);
        if (nested != null) video(nested, depth + 1);
      }
    }

    /** Poster images of the target record only; recommended videos never contribute. */
    String thumbnail(JSONObject video) {
      JSONObject preferred = video.optJSONObject("preferred_thumbnail");
      JSONObject image = preferred == null ? null : preferred.optJSONObject("image");
      JSONObject legacy = video.optJSONObject("thumbnailImage");
      Object[] candidates = {
        image == null ? null : image.opt("uri"),
        legacy == null ? null : legacy.opt("uri"),
        video.opt("first_frame_thumbnail")
      };
      for (Object candidate : candidates) {
        if (!(candidate instanceof String)) continue;
        URI uri = LinkParser.safePreviewUri((String) candidate);
        if (uri != null && cdnHost(uri.getHost().toLowerCase(Locale.ROOT), "fbcdn.net"))
          return (String) candidate;
      }
      return null;
    }

    long durationMs(JSONObject video) {
      Object milliseconds = video.opt("playable_duration_in_ms");
      if (milliseconds instanceof Number && ((Number) milliseconds).longValue() > 0)
        return ((Number) milliseconds).longValue();
      Object seconds = video.opt("length_in_second");
      if (!(seconds instanceof Number)) return 0;
      double value = ((Number) seconds).doubleValue();
      if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0) return 0;
      return Math.round(value * 1000);
    }

    void add(JSONObject record, String key, String label) {
      if (variants.size() >= MAX_VARIANTS) return;
      Object raw = record.opt(key);
      if (!(raw instanceof String)) return;
      String url = ((String) raw).trim();
      URI uri = LinkParser.safeHttpsUri(url);
      if (uri == null
          || (uri.getPort() != -1 && uri.getPort() != 443)
          || !uri.getPath().toLowerCase(Locale.ROOT).endsWith(".mp4")) return;
      String host = uri.getHost().toLowerCase(Locale.ROOT);
      if (!cdnHost(host, "fbcdn.net")
          && !cdnHost(host, "cdninstagram.com")
          && !cdnHost(host, "fbsbx.com")) return;
      if ("none".equalsIgnoreCase(record.optString("vcodec"))
          || record.has("fragments")
          || record.optString("container").toLowerCase(Locale.ROOT).contains("dash")) return;
      int width = 0;
      int height = 0;
      long bitrate = 0;
      // Legacy SD/HD URLs share a video object, whose dimensions describe neither rendition.
      // Progressive entries have their own rendition metadata.
      if ("progressive_url".equals(key)) {
        width = Math.max(0, record.optInt("width", 0));
        height = Math.max(0, record.optInt("height", 0));
        bitrate = Math.max(0, record.optLong("bitrate", 0));
        JSONObject metadata = record.optJSONObject("metadata");
        if (metadata != null) {
          width = Math.max(width, metadata.optInt("width", 0));
          height = Math.max(height, metadata.optInt("height", 0));
          bitrate = Math.max(bitrate, metadata.optLong("bitrate", 0));
        }
      }
      if (height > 0) label = height + "p MP4";
      Variant candidate = new Variant(url, label, "video/mp4", bitrate, width, height);
      Variant previous = variants.get(url);
      if (previous == null || ("SD MP4".equals(previous.label) && "HD MP4".equals(label))) {
        variants.put(url, candidate);
      }
    }
  }

  private static boolean cdnHost(String host, String domain) {
    return domain.equals(host) || host.endsWith("." + domain);
  }

  private static String text(JSONObject object, String... keys) {
    for (String key : keys) {
      Object value = object.opt(key);
      if (value instanceof JSONObject) value = ((JSONObject) value).opt("text");
      if (value instanceof String && !((String) value).trim().isEmpty()) {
        String text = ((String) value).trim();
        return text.length() > 500 ? text.substring(0, 500) : text;
      }
    }
    return "";
  }
}
