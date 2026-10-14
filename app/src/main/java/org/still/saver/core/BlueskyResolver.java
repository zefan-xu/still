// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Bluesky photo posts through the public AppView API. The bundled extractor reads only videos, so
 * a video post is reported back to the caller rather than resolved here.
 */
public final class BlueskyResolver {
  static final String API = "https://public.api.bsky.app/xrpc/app.bsky.feed.getPostThread?uri=";
  private static final Pattern POST_PATH =
      Pattern.compile("/profile/([A-Za-z0-9._:-]+)/post/([A-Za-z0-9]+)/?");
  private final PublicPages.Http http;

  public BlueskyResolver() {
    this(PublicPages.HTTPS);
  }

  public BlueskyResolver(PublicPages.Http http) {
    if (http == null) throw new IllegalArgumentException("A transport is required");
    this.http = http;
  }

  /** The post's photos, or null when its media is a video for the extractor to resolve. */
  public Post resolve(String input) throws IOException {
    String[] link = link(input);
    String uri = "at://" + link[0] + "/app.bsky.feed.post/" + link[1];
    PublicPages.Reply reply =
        http.get(
            API + URLEncoder.encode(uri, "UTF-8") + "&depth=0&parentHeight=0",
            PublicPages.accept("application/json"));
    PublicPages.checkCancelled();
    if (reply.status == 400 && reply.body.contains("NotFound"))
      throw new UserError(ErrorCode.UNAVAILABLE, Platforms.BLUESKY.name);
    PublicPages.checkStatus(reply, "Bluesky");
    return parse(reply.body, link[1]);
  }

  static Post parse(String json, String rkey) throws IOException {
    JSONObject post;
    try {
      JSONObject thread = new JSONObject(json).getJSONObject("thread");
      String type = thread.optString("$type");
      if (type.endsWith("#notFoundPost") || type.endsWith("#blockedPost"))
        throw new UserError(ErrorCode.UNAVAILABLE, Platforms.BLUESKY.name);
      post = thread.getJSONObject("post");
    } catch (JSONException e) {
      throw new UserError(e, ErrorCode.BLUESKY_UNEXPECTED);
    }
    JSONObject embed = post.optJSONObject("embed");
    if (embed != null && embed.optString("$type").endsWith("recordWithMedia#view"))
      embed = embed.optJSONObject("media");
    String type = embed == null ? "" : embed.optString("$type");
    if (type.endsWith("video#view")) return null;
    if (!type.endsWith("images#view"))
      throw new UserError(ErrorCode.BLUESKY_NO_MEDIA);
    List<Media> media = new ArrayList<>();
    JSONArray images = embed.optJSONArray("images");
    for (int i = 0; images != null && i < images.length() && media.size() < 20; i++) {
      JSONObject image = images.optJSONObject(i);
      String full = image == null ? "" : image.optString("fullsize", "");
      if (!cdn(full)) continue;
      String thumb = image.optString("thumb", "");
      Variant variant = new Variant(full, "Full size", mimeType(full), 0, 0, 0);
      media.add(
          new Media(
              "photo",
              Collections.singletonList(variant),
              cdn(thumb) && LinkParser.safePreviewUri(thumb) != null ? thumb : null,
              0));
    }
    if (media.isEmpty()) throw new UserError(ErrorCode.BLUESKY_NO_PHOTO);
    JSONObject author = post.optJSONObject("author");
    String handle = author == null ? "" : author.optString("handle", "");
    JSONObject record = post.optJSONObject("record");
    String title = record == null ? "" : record.optString("text", "").trim();
    if (title.isEmpty()) title = handle.isEmpty() ? "Bluesky post" : "Bluesky post by @" + handle;
    if (title.length() > 1000) title = title.substring(0, 1000);
    String source =
        "https://bsky.app/profile/" + (handle.isEmpty() ? "unknown" : handle) + "/post/" + rkey;
    return new Post(rkey, title, handle.isEmpty() ? "" : "@" + handle, source, media);
  }

  /** Bluesky's image CDN names the format after an @ at the end of the path. */
  private static String mimeType(String url) {
    String path = URI.create(url).getPath();
    if (path.endsWith("@png")) return "image/png";
    if (path.endsWith("@webp")) return "image/webp";
    return "image/jpeg";
  }

  private static boolean cdn(String url) {
    URI uri = LinkParser.safeHttpsUri(url);
    return uri != null
        && "cdn.bsky.app".equalsIgnoreCase(uri.getHost())
        && (uri.getPort() == -1 || uri.getPort() == 443)
        && uri.getPath().startsWith("/img/");
  }

  /** Returns {actor, rkey}. */
  static String[] link(String input) throws IOException {
    URI uri = URI.create(PublicLinkParser.parse(input));
    Matcher path = POST_PATH.matcher(uri.getRawPath());
    if (!handles(uri.toString()) || !path.matches())
      throw new UserError(ErrorCode.BLUESKY_LINK);
    return new String[] {path.group(1), path.group(2)};
  }

  static boolean handles(String url) {
    String host = URI.create(url).getHost();
    return "bsky.app".equals(host) || "www.bsky.app".equals(host);
  }
}
