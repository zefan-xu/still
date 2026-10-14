// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Public post metadata through the documented FxTwitter REST API. No login or cookies are used. The
 * legacy /status/{id} response has tweet.media.{all,videos,photos}; formats and variants describe
 * the same video qualities. Only HTTPS MP4/image files on Twitter's media CDN are saved. The
 * service is replaceable with a self-hosted HTTPS instance implementing the same response.
 */
public final class MediaResolver {
  public static final String DEFAULT_API_BASE = "https://api.fxtwitter.com";
  private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
  private static final Pattern DIMENSIONS = Pattern.compile("/(\\d{1,5})x(\\d{1,5})/");
  private static final Pattern PHOTO_PATH =
      Pattern.compile("^/media/([A-Za-z0-9_-]{1,64})(?:\\.[A-Za-z]{3,4})?$");
  private final String apiBase;
  private final Transport transport;

  public interface Transport {
    Response get(String url) throws IOException;
  }

  public static final class Response {
    public final int statusCode;
    public final String body;

    public Response(int statusCode, String body) {
      this.statusCode = statusCode;
      this.body = body == null ? "" : body;
    }
  }

  public MediaResolver() {
    this(DEFAULT_API_BASE);
  }

  public MediaResolver(String apiBase) {
    this(apiBase, new HttpsTransport());
  }

  public MediaResolver(String apiBase, Transport transport) {
    URI uri = LinkParser.safeHttpsUri(apiBase);
    if (uri == null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
      throw new IllegalArgumentException(
          "The media API base must be an HTTPS URL without credentials, query, or fragment.");
    }
    if (transport == null) throw new IllegalArgumentException("A transport is required");
    String base = uri.toASCIIString();
    while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
    this.apiBase = base;
    this.transport = transport;
  }

  public Post resolve(String input) throws IOException {
    LinkParser.Parsed link = LinkParser.parse(input);
    if (link.directUrl != null) return directPost(link.directUrl);
    Response response;
    try {
      response = transport.get(apiBase + "/status/" + link.id);
    } catch (IOException e) {
      throw new IOException(
          "Could not reach the media service. Check your connection or try again later. "
              + (e.getMessage() == null ? "" : e.getMessage()),
          e);
    }
    if (response == null) throw new IOException("The media service returned an empty response.");
    if (response.body.length() > MAX_BODY_BYTES
        || response.body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
      throw new IOException("The media service response is too large.");
    }
    JSONObject root = null;
    try {
      root = new JSONObject(response.body);
    } catch (JSONException ignored) {
      // HTTP errors often carry HTML, so report their status before a JSON parsing failure.
    }
    String message = root == null ? "" : root.optString("message", "");
    if (response.statusCode != 200) throw serviceError(response.statusCode, message);
    if (root == null)
      throw new IOException("The media service returned an unreadable response. Try again later.");
    int code = root.optInt("code", 200);
    if (code != 200) throw serviceError(code, message);
    JSONObject tweet = root.optJSONObject("tweet");
    if (tweet == null) tweet = root.optJSONObject("status");
    if (tweet == null)
      throw new IOException("The post is unavailable to the public media service.");
    if ("tombstone".equals(tweet.optString("type"))) {
      throw serviceError(404, tweet.optString("reason", ""));
    }
    JSONObject author = tweet.optJSONObject("author");
    if (author != null && author.optBoolean("protected", false)) throw serviceError(403, "private");

    Map<String, Attachment> attachments = new LinkedHashMap<>();
    collectMedia(tweet, attachments);
    if (attachments.isEmpty()) {
      JSONObject quote = tweet.optJSONObject("quote");
      if (quote != null && !"tombstone".equals(quote.optString("type"))) {
        JSONObject quotedAuthor = quote.optJSONObject("author");
        if (quotedAuthor != null && quotedAuthor.optBoolean("protected", false)) {
          throw serviceError(403, "private");
        }
        collectMedia(quote, attachments);
      }
    }
    List<Media> media = new ArrayList<>();
    for (Attachment attachment : attachments.values()) {
      if (!attachment.variants.isEmpty()) {
        media.add(
            new Media(
                attachment.kind,
                new ArrayList<>(attachment.variants.values()),
                attachment.thumbnail,
                attachment.durationMs));
      }
    }
    if (media.isEmpty()) {
      throw new IOException("This public post has no downloadable videos, GIFs, or photos.");
    }
    String handle = author == null ? "" : author.optString("screen_name", "");
    String name = author == null ? "" : author.optString("name", "");
    String authorLabel = handle.isEmpty() ? name : "@" + handle;
    String title = tweet.optString("text", "").trim();
    if (title.isEmpty()) title = authorLabel.isEmpty() ? "X media" : "Media by " + authorLabel;
    return new Post(link.id, title, authorLabel, link.canonicalUrl, media);
  }

  private static void collectMedia(JSONObject tweet, Map<String, Attachment> attachments) {
    JSONObject container = tweet.optJSONObject("media");
    if (container == null) return;
    collect(container.optJSONArray("all"), null, attachments);
    collect(container.optJSONArray("videos"), "video", attachments);
    collect(container.optJSONArray("photos"), "photo", attachments);
  }

  private static Post directPost(String url) {
    String mimeType = LinkParser.mediaMimeType(url);
    String kind = "video/mp4".equals(mimeType) ? "video" : "photo";
    int[] dimensions = dimensions(url, 0, 0);
    Variant variant =
        new Variant(
            url,
            label(mimeType, 0, dimensions[0], dimensions[1]),
            mimeType,
            0,
            dimensions[0],
            dimensions[1]);
    // A direct video link has no separate poster image; a photo previews at a small size.
    String preview = "photo".equals(kind) ? twitterPhotoPreview(url) : null;
    return new Post(
        "",
        "Twitter media",
        "",
        url,
        Collections.singletonList(
            new Media(kind, Collections.singletonList(variant), preview, 0)));
  }

  private static void collect(
      JSONArray rows, String fallbackKind, Map<String, Attachment> attachments) {
    if (rows == null) return;
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.optJSONObject(i);
      if (row == null) continue;
      String kind = row.optString("type", fallbackKind == null ? "" : fallbackKind);
      if ("animated_gif".equals(kind)) kind = "gif";
      if (!"photo".equals(kind) && !"video".equals(kind) && !"gif".equals(kind)) continue;
      String url = row.optString("url", "");
      String id = row.optString("id", "");
      List<Variant> variants = parseVariants(row, kind);
      if (variants.isEmpty()) continue;
      String key = kind + ":" + (id.isEmpty() ? (url.isEmpty() ? variants.get(0).url : url) : id);
      Attachment attachment = attachments.get(key);
      if (attachment == null) {
        attachment = new Attachment(kind);
        attachments.put(key, attachment);
      }
      for (Variant variant : variants) attachment.add(variant);
      // The same attachment repeats across all/videos/photos; keep the first preview found.
      if (attachment.thumbnail == null)
        attachment.thumbnail =
            "photo".equals(kind) ? twitterPhotoPreview(url) : twitterThumbnail(row);
      if (attachment.durationMs == 0) attachment.durationMs = durationMs(row);
    }
  }

  /** FxTwitter's video and GIF poster frame; only Twitter's image CDN is trusted for previews. */
  private static String twitterThumbnail(JSONObject row) {
    String value = row.optString("thumbnail_url", "");
    URI uri = LinkParser.safePreviewUri(value);
    if (uri == null || !"pbs.twimg.com".equalsIgnoreCase(uri.getHost())) return null;
    return value;
  }

  /** The small rendition of a Twitter photo, so a preview never fetches the original upload. */
  static String twitterPhotoPreview(String url) {
    URI uri = LinkParser.safePreviewUri(url);
    if (uri == null || !"pbs.twimg.com".equalsIgnoreCase(uri.getHost())) return null;
    String mimeType = LinkParser.mediaMimeType(url);
    if (mimeType == null || !mimeType.startsWith("image/")) return null;
    Matcher path = PHOTO_PATH.matcher(uri.getRawPath());
    if (!path.matches()) return null;
    String format = mimeType.equals("image/jpeg") ? "jpg" : mimeType.substring("image/".length());
    return "https://pbs.twimg.com/media/" + path.group(1) + "?format=" + format + "&name=small";
  }

  /** FxTwitter reports duration in seconds. */
  private static long durationMs(JSONObject row) {
    double seconds = row.optDouble("duration", 0);
    if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds <= 0) return 0;
    return Math.round(seconds * 1000);
  }

  private static List<Variant> parseVariants(JSONObject row, String kind) {
    List<Variant> result = new ArrayList<>();
    int width = Math.max(0, row.optInt("width", 0));
    int height = Math.max(0, row.optInt("height", 0));
    if (!"photo".equals(kind)) {
      collectVariants(row.optJSONArray("formats"), width, height, result);
      collectVariants(row.optJSONArray("variants"), width, height, result);
      JSONObject videoInfo = row.optJSONObject("video_info");
      if (videoInfo != null)
        collectVariants(videoInfo.optJSONArray("variants"), width, height, result);
    }
    String url = row.optString("url", "");
    String mimeType = LinkParser.mediaMimeType(url);
    boolean supported =
        "photo".equals(kind)
            ? mimeType != null && mimeType.startsWith("image/")
            : "video/mp4".equals(mimeType);
    // The media-level dimensions can describe the original upload, larger than its MP4.
    // Prefer the per-format variants; use the media URL only when none are available.
    if (supported && result.isEmpty()) {
      int[] size = dimensions(url, width, height);
      result.add(
          new Variant(url, label(mimeType, 0, size[0], size[1]), mimeType, 0, size[0], size[1]));
    }
    return result;
  }

  private static void collectVariants(
      JSONArray rows, int fallbackWidth, int fallbackHeight, List<Variant> result) {
    if (rows == null) return;
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.optJSONObject(i);
      if (row == null) continue;
      String url = row.optString("url", "");
      if (!"video/mp4".equals(LinkParser.mediaMimeType(url))) continue;
      String container = row.optString("container", "");
      String contentType = row.optString("content_type", "");
      if ((!container.isEmpty() && !"mp4".equalsIgnoreCase(container))
          || (!contentType.isEmpty() && !"video/mp4".equalsIgnoreCase(contentType))) continue;
      int[] size =
          dimensions(url, row.optInt("width", fallbackWidth), row.optInt("height", fallbackHeight));
      long bitrate = Math.max(0, row.optLong("bitrate", 0));
      result.add(
          new Variant(
              url,
              label("video/mp4", bitrate, size[0], size[1]),
              "video/mp4",
              bitrate,
              size[0],
              size[1]));
    }
  }

  private static int[] dimensions(String url, int fallbackWidth, int fallbackHeight) {
    URI uri = LinkParser.safeHttpsUri(url);
    Matcher match = DIMENSIONS.matcher(uri == null ? "" : uri.getPath());
    if (match.find())
      return new int[] {Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2))};
    return new int[] {Math.max(0, fallbackWidth), Math.max(0, fallbackHeight)};
  }

  private static String label(String mimeType, long bitrate, int width, int height) {
    if (mimeType.startsWith("image/")) {
      return width > 0 && height > 0 ? width + " × " + height : "Original image";
    }
    if (height > 0) return height + "p";
    if (bitrate > 0) return (bitrate / 1000) + " kbps";
    return "MP4";
  }

  private static IOException serviceError(int code, String detail) {
    String lower = detail.toLowerCase(Locale.ROOT);
    if (code == 429)
      return new IOException("The media service is rate limited. Wait a moment and try again.");
    if (code == 401 || code == 403 || lower.contains("private") || lower.contains("protected")) {
      return new IOException("This post is private or restricted. Only public posts can be saved.");
    }
    if (code == 404 || code == 410) {
      return new IOException("This post could not be found. It may be deleted or unavailable.");
    }
    if (code >= 500)
      return new IOException("The media service is temporarily unavailable. Try again later.");
    return new IOException("The media service could not load this post (status " + code + ").");
  }

  private static final class Attachment {
    final String kind;
    final Map<String, Variant> variants = new LinkedHashMap<>();
    String thumbnail;
    long durationMs;

    Attachment(String kind) {
      this.kind = kind;
    }

    void add(Variant variant) {
      Variant previous = variants.get(variant.url);
      if (previous == null) {
        variants.put(variant.url, variant);
      } else {
        long bitrate = Math.max(previous.bitrate, variant.bitrate);
        int width = previous.width > 0 ? previous.width : variant.width;
        int height = previous.height > 0 ? previous.height : variant.height;
        variants.put(
            variant.url,
            new Variant(
                variant.url,
                label(variant.mimeType, bitrate, width, height),
                variant.mimeType,
                bitrate,
                width,
                height));
      }
    }
  }

  /** GET metadata only: timeouts, TLS validation, at most three HTTPS redirects, bounded body. */
  private static final class HttpsTransport implements Transport {
    @Override
    public Response get(String url) throws IOException {
      URI current = LinkParser.safeHttpsUri(url);
      if (current == null) throw new IOException("The media service URL must use HTTPS.");
      for (int redirects = 0; redirects <= 3; redirects++) {
        HttpURLConnection connection =
            (HttpURLConnection) new URL(current.toASCIIString()).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Still/1.0 (open-source public media saver)");
        try {
          int status = connection.getResponseCode();
          if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
            String location = connection.getHeaderField("Location");
            if (location == null)
              throw new IOException("The media service sent an invalid redirect.");
            URI next;
            try {
              next = current.resolve(location);
            } catch (IllegalArgumentException e) {
              throw new IOException("The media service sent an invalid redirect.", e);
            }
            current = LinkParser.safeHttpsUri(next.toString());
            if (current == null)
              throw new IOException("The media service redirected to an unsafe URL.");
            if (redirects == 3) throw new IOException("The media service sent too many redirects.");
            continue;
          }
          if (connection.getContentLength() > MAX_BODY_BYTES) {
            throw new IOException("The media service response is too large.");
          }
          InputStream stream =
              status >= 400 ? connection.getErrorStream() : connection.getInputStream();
          if (stream == null) return new Response(status, "");
          try (InputStream input = stream;
              ByteArrayOutputStream body = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
              total += read;
              if (total > MAX_BODY_BYTES)
                throw new IOException("The media service response is too large.");
              body.write(buffer, 0, read);
            }
            return new Response(status, new String(body.toByteArray(), StandardCharsets.UTF_8));
          }
        } finally {
          connection.disconnect();
        }
      }
      throw new IOException("The media service sent too many redirects.");
    }
  }
}
