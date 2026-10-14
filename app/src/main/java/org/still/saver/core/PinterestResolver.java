// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pinterest image pins through the public pin resource the website loads. Video pins are reported
 * back to the caller for the bundled extractor, which already resolves them.
 */
public final class PinterestResolver {
  static final String API = "https://www.pinterest.com/resource/PinResource/get/?data=";
  /** Short links land on /pin/&lt;id&gt;/sent/?invite_code=…, so text may follow the ID. */
  private static final Pattern PIN_PATH =
      Pattern.compile("/pin/(?:[A-Za-z0-9_-]+--)?([0-9]+)(?:/.*)?");
  private static final Map<String, String> HEADERS = new HashMap<>();

  static {
    HEADERS.put("Accept", "application/json");
    HEADERS.put("X-Pinterest-PWS-Handler", "www/pin/[id].js");
  }

  private final PublicPages.Http http;

  public PinterestResolver() {
    this(PublicPages.HTTPS);
  }

  public PinterestResolver(PublicPages.Http http) {
    if (http == null) throw new IllegalArgumentException("A transport is required");
    this.http = http;
  }

  /** The pin's images, or null when it is a video pin for the extractor to resolve. */
  public Post resolve(String input) throws IOException {
    String id = pinId(PublicLinkParser.parse(input));
    String options =
        "{\"options\":{\"field_set_key\":\"unauth_react_main_pin\",\"id\":\"" + id + "\"}}";
    PublicPages.Reply reply = http.get(API + URLEncoder.encode(options, "UTF-8"), HEADERS);
    PublicPages.checkCancelled();
    PublicPages.checkStatus(reply, "Pinterest");
    return parse(reply.body, id);
  }

  /** Short pin.it links redirect through Pinterest's own hosts to the pin. */
  private String pinId(String url) throws IOException {
    for (int redirects = 0; redirects <= 4; redirects++) {
      URI uri = URI.create(url);
      Matcher path = PIN_PATH.matcher(uri.getRawPath());
      if (pinterest(uri.getHost()) && path.matches()) return path.group(1);
      if (redirects == 4) break;
      PublicPages.Reply reply = http.get(url, PublicPages.accept("text/html"));
      PublicPages.checkCancelled();
      URI next = PublicPages.redirect(url, reply.location);
      if (reply.status < 300 || reply.status >= 400 || next == null || !pinterest(next.getHost()))
        throw new UserError(ErrorCode.PINTEREST_NOT_PIN);
      url = next.toString();
    }
    throw new UserError(ErrorCode.PINTEREST_TOO_MANY_REDIRECTS);
  }

  static Post parse(String json, String id) throws IOException {
    JSONObject pin;
    try {
      pin = new JSONObject(json).getJSONObject("resource_response").optJSONObject("data");
    } catch (JSONException e) {
      throw new UserError(e, ErrorCode.PINTEREST_UNEXPECTED);
    }
    if (pin == null) throw new UserError(ErrorCode.PINTEREST_UNAVAILABLE);
    if (video(pin)) return null;
    Set<String> urls = new LinkedHashSet<>();
    JSONObject carousel = pin.optJSONObject("carousel_data");
    JSONArray slots = carousel == null ? null : carousel.optJSONArray("carousel_slots");
    for (int i = 0; slots != null && i < slots.length(); i++) {
      JSONObject slot = slots.optJSONObject(i);
      add(urls, slot == null ? null : slot.optJSONObject("images"));
    }
    JSONObject story = pin.optJSONObject("story_pin_data");
    JSONArray pages = story == null ? null : story.optJSONArray("pages");
    for (int i = 0; pages != null && i < pages.length(); i++) {
      JSONObject page = pages.optJSONObject(i);
      JSONArray blocks = page == null ? null : page.optJSONArray("blocks");
      for (int j = 0; blocks != null && j < blocks.length(); j++) {
        JSONObject block = blocks.optJSONObject(j);
        JSONObject image = block == null ? null : block.optJSONObject("image");
        add(urls, image == null ? null : image.optJSONObject("images"));
      }
    }
    if (urls.isEmpty()) add(urls, pin.optJSONObject("images"));
    List<Media> media = new ArrayList<>();
    for (String url : urls) {
      if (media.size() == 20) break;
      Variant variant = new Variant(url, "Original image", mimeType(url), 0, 0, 0);
      media.add(new Media("photo", Collections.singletonList(variant), preview(url), 0));
    }
    if (media.isEmpty()) throw new UserError(ErrorCode.PINTEREST_NO_IMAGE);
    JSONObject pinner = pin.optJSONObject("pinner");
    String username = pinner == null ? "" : pinner.optString("username", "");
    String title = pin.optString("title", "").trim();
    if (title.isEmpty()) title = pin.optString("description", "").trim();
    if (title.isEmpty()) title = "Pinterest pin";
    if (title.length() > 1000) title = title.substring(0, 1000);
    return new Post(
        id,
        title,
        username.isEmpty() ? "" : "@" + username,
        "https://www.pinterest.com/pin/" + id + "/",
        media);
  }

  private static boolean video(JSONObject pin) {
    JSONObject videos = pin.optJSONObject("videos");
    if (videos != null && videos.optJSONObject("video_list") != null) return true;
    JSONObject story = pin.optJSONObject("story_pin_data");
    JSONArray pages = story == null ? null : story.optJSONArray("pages");
    for (int i = 0; pages != null && i < pages.length(); i++) {
      JSONObject page = pages.optJSONObject(i);
      JSONArray blocks = page == null ? null : page.optJSONArray("blocks");
      for (int j = 0; blocks != null && j < blocks.length(); j++) {
        JSONObject block = blocks.optJSONObject(j);
        if (block != null && block.optJSONObject("video") != null) return true;
      }
    }
    return false;
  }

  /** The original upload; Pinterest names it "orig" on pins and "originals" in slots/pages. */
  private static void add(Set<String> urls, JSONObject images) {
    if (images == null) return;
    for (String key : new String[] {"orig", "originals"}) {
      JSONObject image = images.optJSONObject(key);
      String url = image == null ? "" : image.optString("url", "");
      if (cdn(url) && mimeType(url) != null) {
        urls.add(url);
        return;
      }
    }
  }

  /** The 474 px grid rendition of the same image. */
  private static String preview(String original) {
    String url = original.replace("/originals/", "/474x/");
    return !url.equals(original) && LinkParser.safePreviewUri(url) != null ? url : null;
  }

  private static String mimeType(String url) {
    String path = URI.create(url).getPath().toLowerCase(java.util.Locale.ROOT);
    if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
    if (path.endsWith(".png")) return "image/png";
    if (path.endsWith(".webp")) return "image/webp";
    if (path.endsWith(".gif")) return "image/gif";
    return null;
  }

  private static boolean cdn(String url) {
    URI uri = LinkParser.safeHttpsUri(url);
    return uri != null
        && "i.pinimg.com".equalsIgnoreCase(uri.getHost())
        && (uri.getPort() == -1 || uri.getPort() == 443);
  }

  private static boolean pinterest(String host) {
    if (host == null) return false;
    host = host.toLowerCase(java.util.Locale.ROOT);
    return host.equals("pin.it")
        || host.equals("www.pin.it")
        || host.equals("api.pinterest.com")
        || PublicLinkParser.isPinterestHost(host);
  }

  static boolean handles(String url) {
    String host = URI.create(url).getHost();
    return pinterest(host) && !"api.pinterest.com".equals(host);
  }
}
