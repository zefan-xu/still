// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * TikTok photo slideshows. The bundled extractor does not accept photo links; TikTok's own page
 * for the same post ID carries the slideshow's images, which download without a session.
 */
public final class TikTokPhotoResolver {
  private static final Pattern PHOTO_PATH =
      Pattern.compile("/@([A-Za-z0-9._-]+)/photo/([0-9]+)/?");
  private static final Pattern DATA_SCRIPT =
      Pattern.compile(
          "<script[^>]*\\bid=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"[^>]*>(.+?)</script>",
          Pattern.DOTALL);
  private static final Map<String, String> PAGE_HEADERS = new HashMap<>();

  static {
    PAGE_HEADERS.put("Accept", "text/html,application/xhtml+xml");
    PAGE_HEADERS.put("Sec-Fetch-Mode", "navigate");
  }

  private final PublicPages.Http http;

  public TikTokPhotoResolver() {
    this(PublicPages.HTTPS);
  }

  public TikTokPhotoResolver(PublicPages.Http http) {
    if (http == null) throw new IllegalArgumentException("A transport is required");
    this.http = http;
  }

  /**
   * The photo post a link points to, or null for a video. Short vm./vt. links and /t/ links are
   * followed one hop to learn which kind of post they name.
   */
  public String photoLink(String url) throws IOException {
    URI uri = URI.create(url);
    if (PHOTO_PATH.matcher(uri.getRawPath()).matches()) return url;
    String host = uri.getHost();
    boolean shortLink =
        "vm.tiktok.com".equals(host)
            || "vt.tiktok.com".equals(host)
            || uri.getRawPath().startsWith("/t/");
    if (!shortLink) return null;
    PublicPages.Reply reply = http.get(url, PAGE_HEADERS);
    PublicPages.checkCancelled();
    URI next = PublicPages.redirect(url, reply.location);
    if (reply.status < 300 || reply.status >= 400 || next == null)
      throw new UserError(ErrorCode.TIKTOK_SHORT_LINK_NO_POST);
    if (!PublicPages.onDomain(next.toString(), "tiktok.com"))
      throw new UserError(ErrorCode.TIKTOK_SHORT_LINK_AWAY);
    return PHOTO_PATH.matcher(next.getRawPath()).matches() ? next.toString() : null;
  }

  public Post resolve(String photoUrl) throws IOException {
    Matcher path = PHOTO_PATH.matcher(URI.create(photoUrl).getRawPath());
    if (!path.matches()) throw new IllegalArgumentException("Not a TikTok photo link");
    // TikTok renders a photo post's details on the post's /video/ page.
    String page = "https://www.tiktok.com/@" + path.group(1) + "/video/" + path.group(2);
    PublicPages.Reply reply = http.get(page, PAGE_HEADERS);
    PublicPages.checkCancelled();
    PublicPages.checkStatus(reply, "TikTok");
    return parse(reply.body, path.group(1), path.group(2));
  }

  static Post parse(String html, String user, String id) throws IOException {
    Matcher script = DATA_SCRIPT.matcher(html);
    if (!script.find())
      throw new UserError(ErrorCode.TIKTOK_MISSING);
    JSONObject detail;
    try {
      JSONObject scope =
          new JSONObject(script.group(1)).getJSONObject("__DEFAULT_SCOPE__");
      detail = scope.optJSONObject("webapp.video-detail");
    } catch (JSONException e) {
      throw new UserError(e, ErrorCode.TIKTOK_UNEXPECTED);
    }
    if (detail == null)
      throw new UserError(ErrorCode.TIKTOK_MISSING);
    if (detail.optInt("statusCode", 0) != 0)
      throw new UserError(ErrorCode.TIKTOK_UNAVAILABLE);
    JSONObject info = detail.optJSONObject("itemInfo");
    JSONObject item = info == null ? null : info.optJSONObject("itemStruct");
    if (item == null || !id.equals(item.optString("id", id)))
      throw new UserError(ErrorCode.TIKTOK_MISSING);
    JSONObject post = item.optJSONObject("imagePost");
    JSONArray images = post == null ? null : post.optJSONArray("images");
    List<Media> media = new ArrayList<>();
    for (int i = 0; images != null && i < images.length() && media.size() < 35; i++) {
      JSONObject image = images.optJSONObject(i);
      JSONObject address = image == null ? null : image.optJSONObject("imageURL");
      JSONArray list = address == null ? null : address.optJSONArray("urlList");
      String url = list == null ? "" : list.optString(0, "");
      String mimeType = cdn(url) ? mimeType(url) : null;
      if (mimeType == null) continue;
      int width = Math.max(0, image.optInt("imageWidth", 0));
      int height = Math.max(0, image.optInt("imageHeight", 0));
      String label = width > 0 && height > 0 ? width + " × " + height : "Original image";
      Variant variant = new Variant(url, label, mimeType, 0, width, height);
      String preview = LinkParser.safePreviewUri(url) == null ? null : url;
      media.add(new Media("photo", Collections.singletonList(variant), preview, 0));
    }
    if (media.isEmpty())
      throw new UserError(ErrorCode.TIKTOK_NO_PHOTOS);
    JSONObject author = item.optJSONObject("author");
    String handle = author == null ? user : author.optString("uniqueId", user);
    String title = item.optString("desc", "").trim();
    if (title.isEmpty()) title = "TikTok photos by @" + handle;
    if (title.length() > 1000) title = title.substring(0, 1000);
    return new Post(
        id, title, "@" + handle, "https://www.tiktok.com/@" + handle + "/photo/" + id, media);
  }

  private static String mimeType(String url) {
    String path = URI.create(url).getPath().toLowerCase(Locale.ROOT);
    if (path.endsWith(".jpeg") || path.endsWith(".jpg")) return "image/jpeg";
    if (path.endsWith(".webp")) return "image/webp";
    if (path.endsWith(".png")) return "image/png";
    return null;
  }

  /** TikTok's image CDNs; regional variants share the tiktokcdn prefix. */
  private static boolean cdn(String url) {
    URI uri = LinkParser.safeHttpsUri(url);
    if (uri == null || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    return host.matches("(?:[a-z0-9-]+\\.)+tiktokcdn(?:-[a-z]{2})?\\.com");
  }

  static boolean handles(String url) {
    return PublicPages.onDomain(url, "tiktok.com");
  }
}
