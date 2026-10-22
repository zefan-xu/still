// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The public platforms supported by the replacement's bundled extractor. */
public final class PublicLinkParser {
  private static final Pattern LINKS = Pattern.compile("(?i)\\bhttps?://[^\\s<>\\\"“”]+");
  private static final Pattern PINTEREST_POST =
      Pattern.compile("/pin/(?:[A-Za-z0-9_-]+--)?[0-9]+/?");
  private static final String FACEBOOK_POST_ID = "(?:[0-9]+|pfbid[A-Za-z0-9]+)";
  private static final String YOUTUBE_ID = "([A-Za-z0-9_-]{11})";
  private static final Pattern YOUTUBE_PATH =
      Pattern.compile("/(shorts|live|embed)/" + YOUTUBE_ID + "/?");
  private static final Pattern YOUTUBE_SHORT_LINK = Pattern.compile("/" + YOUTUBE_ID + "/?");
  private static final Pattern YOUTUBE_WATCH_QUERY =
      Pattern.compile("(?:^|&)v=" + YOUTUBE_ID + "(?:&|$)");
  private static final Pattern TELEGRAM_POST =
      Pattern.compile("/([A-Za-z][A-Za-z0-9_]{0,31})/([1-9][0-9]{0,19})/?");
  private static final Pattern TELEGRAM_PREVIEW_POST =
      Pattern.compile("/s/([A-Za-z][A-Za-z0-9_]{0,31})/([1-9][0-9]{0,19})/?");
  private static final Pattern TELEGRAM_PRIVATE_POST =
      Pattern.compile("/c/([1-9][0-9]{0,19})/([1-9][0-9]{0,19})/?");
  private static final Set<String> TELEGRAM_RESERVED =
      new HashSet<>(
          Arrays.asList(
              "a", "addemoji", "addlist", "addstickers", "addstyle", "addtheme", "auction", "auth",
              "bg", "boost", "c", "call", "confirmphone", "contact", "giftcode", "invoice", "iv",
              "joinchat", "k", "login", "m", "nft", "proxy", "s", "setlanguage", "share", "socks",
              "stars", "web", "z"));
  // Official regional domains recognized by the bundled Pinterest extractor.
  private static final Set<String> PINTEREST_DOMAINS =
      new HashSet<>(
          Arrays.asList(
              "com", "fr", "de", "ch", "jp", "cl", "ca", "it", "co.uk", "nz", "ru", "com.au", "at",
              "pt", "co.kr", "es", "com.mx", "dk", "ph", "th", "com.uy", "co", "nl", "info", "kr",
              "ie", "vn", "com.vn", "ec", "mx", "in", "pe", "co.at", "hu", "co.in", "co.nz", "id",
              "com.ec", "com.py", "tw", "be", "uk", "com.bo", "com.pe"));

  private PublicLinkParser() {}

  public static String parse(String input) throws IOException {
    try {
      LinkParser.Parsed twitter = LinkParser.parse(input);
      return twitter.canonicalUrl;
    } catch (IOException ignored) {
      /* Try the other supported public platforms. */
    }
    if (input == null || input.trim().isEmpty())
      throw new UserError(ErrorCode.LINK_EMPTY);
    if (input.length() > 8192) throw new UserError(ErrorCode.LINK_TOO_LONG);
    if (LinkParser.hasForbiddenScheme(input))
      throw new UserError(ErrorCode.LINK_NOT_HTTPS);
    Matcher links = LINKS.matcher(input);
    while (links.find()) {
      String candidate = links.group();
      while (!candidate.isEmpty()
          && ".,!;:)]}".indexOf(candidate.charAt(candidate.length() - 1)) >= 0) {
        candidate = candidate.substring(0, candidate.length() - 1);
      }
      URI uri = LinkParser.safeHttpsUri(candidate);
      if (uri == null || (uri.getPort() != -1 && uri.getPort() != 443)) continue;
      String host = uri.getHost().toLowerCase(Locale.ROOT);
      String path = uri.getRawPath();
      String youtube = youtubeUrl(host, path, uri.getRawQuery());
      if (youtube != null) return youtube;
      boolean supported = false;
      switch (host) {
        case "tiktok.com":
        case "www.tiktok.com":
        case "m.tiktok.com":
          supported =
              path.matches("/(?:@[A-Za-z0-9._-]+/(?:video|photo)/[0-9]+|t/[A-Za-z0-9_-]+)/?");
          break;
        case "vm.tiktok.com":
        case "vt.tiktok.com":
        case "fb.watch":
        case "www.fb.watch":
        case "pin.it":
        case "www.pin.it":
          supported = path.matches("/[A-Za-z0-9_-]+/?");
          break;
        case "instagram.com":
        case "www.instagram.com":
        case "m.instagram.com":
          supported =
              path.matches("/(?:(?!share/)[A-Za-z0-9._-]+/)?(?:p|reel|reels|tv)/[A-Za-z0-9_-]+/?");
          break;
        case "facebook.com":
        case "www.facebook.com":
        case "m.facebook.com":
        case "mbasic.facebook.com":
          boolean queryPost =
              path.matches(
                      "/(?:watch(?:/live)?/?|(?:video/)?video\\.php|video/embed|photo\\.php"
                          + "|story\\.php|permalink\\.php)")
                  && uri.getRawQuery() != null
                  && uri.getRawQuery()
                      .matches(
                          "(?:.*&)?(?:v|video_id|story_fbid)=" + FACEBOOK_POST_ID + "(?:&.*)?");
          supported =
              queryPost
                  || path.matches(
                      "/(?:reel/[0-9]+/?|share/(?:v|r)/[A-Za-z0-9_-]+/?"
                          + "|[A-Za-z0-9._-]+/(?:videos/(?:[A-Za-z0-9._-]+/)?|posts/)"
                          + FACEBOOK_POST_ID
                          + "/?|groups/[A-Za-z0-9._-]+/(?:permalink|posts)/"
                          + FACEBOOK_POST_ID
                          + "/?)");
          break;
        case "pinterest.com":
        case "www.pinterest.com":
        case "m.pinterest.com":
          supported = PINTEREST_POST.matcher(path).matches();
          break;
        case "threads.net":
        case "www.threads.net":
        case "threads.com":
        case "www.threads.com":
          supported = path.matches("/@[A-Za-z0-9._-]+/post/[A-Za-z0-9_-]+/?");
          break;
        case "bsky.app":
        case "www.bsky.app":
          supported = path.matches("/profile/[A-Za-z0-9._:-]+/post/[A-Za-z0-9]+/?");
          break;
        case "t.me":
        case "www.t.me":
        case "telegram.me":
        case "www.telegram.me":
          Matcher privatePost = TELEGRAM_PRIVATE_POST.matcher(path);
          if (privatePost.matches())
            return "https://t.me/c/" + privatePost.group(1) + "/" + privatePost.group(2);
          Matcher telegram =
              (path.startsWith("/s/") ? TELEGRAM_PREVIEW_POST : TELEGRAM_POST).matcher(path);
          if (telegram.matches()
              && !TELEGRAM_RESERVED.contains(telegram.group(1).toLowerCase(Locale.ROOT)))
            return "https://t.me/" + telegram.group(1) + "/" + telegram.group(2);
          break;
        default:
          supported = isPinterestHost(host) && PINTEREST_POST.matcher(path).matches();
          break;
      }
      if (supported) {
        return "https://"
            + host
            + path
            + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
      }
    }
    throw new UserError(ErrorCode.LINK_UNSUPPORTED);
  }

  /**
   * One video's watch or Shorts link with playlist, timestamp and share-tracking parameters
   * dropped; null for other hosts and for channel, playlist or search pages.
   */
  private static String youtubeUrl(String host, String path, String query) {
    Matcher id;
    switch (host) {
      case "youtu.be":
        id = YOUTUBE_SHORT_LINK.matcher(path);
        return id.matches() ? "https://www.youtube.com/watch?v=" + id.group(1) : null;
      case "youtube.com":
      case "www.youtube.com":
      case "m.youtube.com":
      case "music.youtube.com":
        id = YOUTUBE_PATH.matcher(path);
        if (id.matches())
          return "shorts".equals(id.group(1))
              ? "https://www.youtube.com/shorts/" + id.group(2)
              : "https://www.youtube.com/watch?v=" + id.group(2);
        if (!path.matches("/watch/?") || query == null) return null;
        id = YOUTUBE_WATCH_QUERY.matcher(query);
        return id.find() ? "https://www.youtube.com/watch?v=" + id.group(1) : null;
      default:
        return null;
    }
  }

  /** pinterest.&lt;domain&gt; with www., m. or a regional label such as in. or uk. */
  static boolean isPinterestHost(String host) {
    if (host.startsWith("www.")) host = host.substring(4);
    else if (host.matches("(?:m|[a-z]{2})\\.pinterest\\..+")) host = host.substring(host.indexOf('.') + 1);
    return host.startsWith("pinterest.") && PINTEREST_DOMAINS.contains(host.substring(10));
  }
}
