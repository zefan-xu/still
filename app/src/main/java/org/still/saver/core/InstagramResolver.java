// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Public Instagram posts, including photos and carousels, from the logged-out post page. The page
 * embeds the same media record the web client renders; no session or account cookies are sent.
 */
public final class InstagramResolver {
  static final String HOME = "https://www.instagram.com/";
  private static final java.util.Map<String, String> PAGE_HEADERS = new java.util.HashMap<>();

  static {
    PAGE_HEADERS.put("Accept", "text/html,application/xhtml+xml");
    PAGE_HEADERS.put("Sec-Fetch-Mode", "navigate");
  }

  private static final Pattern POST_PATH =
      Pattern.compile("/(?:[A-Za-z0-9._-]+/)?(p|reel|reels|tv)/([A-Za-z0-9_-]+)/?");
  private static final Pattern DATA_SCRIPT =
      Pattern.compile("<script\\b[^>]*\\bdata-sjs\\b[^>]*>(\\{.+?\\})</script>", Pattern.DOTALL);

  private final PublicPages.Http http;

  public InstagramResolver() {
    this(PublicPages.HTTPS);
  }

  public InstagramResolver(PublicPages.Http http) {
    if (http == null) throw new IllegalArgumentException("A transport is required");
    this.http = http;
  }

  public Post resolve(String input) throws IOException {
    String[] link = link(input);
    String page = HOME + "p/" + link[1] + "/";
    for (int redirects = 0; ; redirects++) {
      PublicPages.Reply reply = http.get(page, PAGE_HEADERS);
      PublicPages.checkCancelled();
      if (reply.status >= 300 && reply.status < 400) {
        String next = redirect(page, reply.location);
        if (redirects == 3) throw new IOException("Instagram sent too many redirects.");
        page = next;
        continue;
      }
      if (reply.status == 429) throw rateLimited();
      if (reply.status == 404) throw new IOException("This Instagram post is deleted or unavailable.");
      if (reply.status != 200)
        throw new IOException("Could not load this Instagram post (status " + reply.status + ").");
      return parse(reply.body, link[0], link[1]);
    }
  }

  /** Only the same post on www.instagram.com; the login wall means the post is not public. */
  private static String redirect(String page, String location) throws IOException {
    URI next = PublicPages.redirect(page, location);
    if (next == null || !"www.instagram.com".equalsIgnoreCase(next.getHost()))
      throw new IOException("Instagram redirected away from this post.");
    String path = next.getRawPath() == null ? "" : next.getRawPath();
    if (path.startsWith("/accounts/login") || path.equals("/") || path.isEmpty())
      throw new IOException(
          "Instagram is asking this network to log in. Wait a few minutes and try again; private"
              + " posts cannot be saved.");
    if (!POST_PATH.matcher(path).matches())
      throw new IOException("Instagram redirected away from this post.");
    return next.toString();
  }

  private static IOException rateLimited() {
    return new IOException(
        "Instagram is limiting anonymous requests from this network. Wait a few minutes and try"
            + " again.");
  }

  static Post parse(String html, String source, String shortcode) throws IOException {
    JSONObject media = null;
    boolean gated = false;
    Matcher scripts = DATA_SCRIPT.matcher(html);
    while (media == null && scripts.find()) {
      String script = scripts.group(1);
      if (!script.contains("xig_polaris_media")) continue;
      JSONObject record;
      try {
        record = find(new JSONObject(script), shortcode, 0);
      } catch (JSONException e) {
        throw new IOException("Instagram returned an unexpected page.", e);
      }
      if (record == null) continue;
      media = record.optJSONObject("if_not_gated_logged_out");
      gated = media == null;
    }
    if (media == null)
      throw new IOException(
          gated
              ? "Instagram requires login for this post. Only public posts can be saved."
              : "Instagram did not include this post. It may be private or deleted, or Instagram"
                  + " is limiting this network; try again in a few minutes.");
    List<JSONObject> items = new ArrayList<>();
    JSONArray carousel = media.optJSONArray("carousel_media");
    for (int i = 0; carousel != null && i < carousel.length() && items.size() < 20; i++) {
      JSONObject item = carousel.optJSONObject(i);
      if (item != null) items.add(item);
    }
    if (items.isEmpty()) items.add(media);
    List<Media> attachments = new ArrayList<>();
    for (JSONObject item : items) {
      Media attachment = item.optInt("media_type") == 2 ? video(item) : photo(item);
      if (attachment != null) attachments.add(attachment);
    }
    if (attachments.isEmpty())
      throw new IOException("This Instagram post has no downloadable photo or video.");
    JSONObject user = media.optJSONObject("user");
    String username = user == null ? "" : user.optString("username", "");
    String author = username.isEmpty() ? "" : "@" + username;
    JSONObject caption = media.optJSONObject("caption");
    String title = caption == null ? "" : caption.optString("text", "").trim();
    if (title.isEmpty()) title = author.isEmpty() ? "Instagram post" : "Instagram post by " + author;
    if (title.length() > 1000) title = title.substring(0, 1000);
    return new Post(shortcode, title, author, source, attachments);
  }

  /** The xig_polaris_media record for this shortcode, anywhere in the page's relay data. */
  private static JSONObject find(Object node, String shortcode, int depth) {
    if (depth > 40) return null;
    if (node instanceof JSONObject) {
      JSONObject object = (JSONObject) node;
      JSONObject record = object.optJSONObject("xig_polaris_media");
      if (record != null && shortcode.equals(record.optString("code"))) return record;
      for (java.util.Iterator<String> keys = object.keys(); keys.hasNext(); ) {
        JSONObject found = find(object.opt(keys.next()), shortcode, depth + 1);
        if (found != null) return found;
      }
    } else if (node instanceof JSONArray) {
      JSONArray array = (JSONArray) node;
      for (int i = 0; i < array.length(); i++) {
        JSONObject found = find(array.opt(i), shortcode, depth + 1);
        if (found != null) return found;
      }
    }
    return null;
  }

  /** The first candidate is the uncropped original; the rest are smaller or square crops. */
  private static Media photo(JSONObject item) {
    JSONObject versions = item.optJSONObject("image_versions2");
    JSONArray candidates = versions == null ? null : versions.optJSONArray("candidates");
    JSONObject original = candidates == null ? null : candidates.optJSONObject(0);
    String url = original == null ? "" : original.optString("url", "");
    String mimeType = imageType(url);
    if (mimeType == null) return null;
    int width = original.optInt("width", item.optInt("original_width", 0));
    int height = original.optInt("height", item.optInt("original_height", 0));
    String label = width > 0 && height > 0 ? width + " × " + height : "Original image";
    return new Media(
        "photo",
        Collections.singletonList(new Variant(url, label, mimeType, 0, width, height)),
        preview(candidates),
        0);
  }

  private static Media video(JSONObject item) {
    JSONArray versions = item.optJSONArray("video_versions");
    List<Variant> variants = new ArrayList<>();
    for (int i = 0; versions != null && i < versions.length() && variants.size() < 10; i++) {
      JSONObject version = versions.optJSONObject(i);
      String url = version == null ? "" : version.optString("url", "");
      if (!cdn(url) || !URI.create(url).getPath().toLowerCase(Locale.ROOT).endsWith(".mp4"))
        continue;
      // The post's original size is not the progressive file's size; label only reported sizes.
      int w = Math.max(0, version.optInt("width", 0)), h = Math.max(0, version.optInt("height", 0));
      variants.add(new Variant(url, h > 0 ? h + "p" : "MP4", "video/mp4", 0, w, h));
    }
    if (variants.isEmpty()) return null;
    JSONObject images = item.optJSONObject("image_versions2");
    double seconds = item.optDouble("video_duration", 0);
    long durationMs =
        Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds <= 0
            ? 0
            : Math.round(seconds * 1000);
    return new Media(
        "video",
        variants,
        preview(images == null ? null : images.optJSONArray("candidates")),
        durationMs);
  }

  /** A 480 px uncropped rendition when offered, else the original. */
  private static String preview(JSONArray candidates) {
    String chosen = null;
    for (int i = 0; candidates != null && i < candidates.length(); i++) {
      JSONObject candidate = candidates.optJSONObject(i);
      String url = candidate == null ? "" : candidate.optString("url", "");
      if (imageType(url) == null || LinkParser.safePreviewUri(url) == null) continue;
      if (chosen == null) chosen = url;
      if (url.contains("_p480x480_")) return url;
    }
    return chosen;
  }

  private static String imageType(String url) {
    if (!cdn(url)) return null;
    String path = URI.create(url).getPath().toLowerCase(Locale.ROOT);
    if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
    if (path.endsWith(".webp")) return "image/webp";
    if (path.endsWith(".png")) return "image/png";
    return null;
  }

  private static boolean cdn(String url) {
    URI uri = LinkParser.safeHttpsUri(url);
    if (uri == null || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    return host.endsWith(".cdninstagram.com") || host.endsWith(".fbcdn.net");
  }

  /** Returns {canonical source URL, shortcode}; tracking parameters are dropped. */
  static String[] link(String input) throws IOException {
    URI uri = URI.create(PublicLinkParser.parse(input));
    if (!handles(uri.toString())) throw new IOException("Use a public Instagram post link.");
    Matcher path = POST_PATH.matcher(uri.getRawPath());
    if (!path.matches()) throw new IOException("Use a public Instagram post link.");
    String kind = path.group(1).startsWith("reel") ? "reel" : path.group(1);
    return new String[] {HOME + kind + "/" + path.group(2) + "/", path.group(2)};
  }

  static boolean handles(String url) {
    String host = URI.create(url).getHost();
    return "instagram.com".equals(host)
        || "www.instagram.com".equals(host)
        || "m.instagram.com".equals(host);
  }
}
