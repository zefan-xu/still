// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses links locally; it never follows short links or fetches pasted web pages. */
public final class LinkParser {
  private static final Pattern LINKS =
      Pattern.compile("(?i)\\bhttps?://[^\\s<>\\\"“”]+", Pattern.UNICODE_CASE);
  private static final Pattern STATUS =
      Pattern.compile(
          "^/(?:[A-Za-z0-9_]{1,15}/status(?:es)?|i/web/status)/([1-9][0-9]{0,19})"
              + "(?:/(?:video|photo)/[1-9][0-9]?)?/?$");
  private static final Set<String> POST_HOSTS =
      new HashSet<>(
          Arrays.asList(
              "x.com",
              "www.x.com",
              "mobile.x.com",
              "twitter.com",
              "www.twitter.com",
              "mobile.twitter.com",
              "fxtwitter.com",
              "www.fxtwitter.com",
              "fixupx.com",
              "www.fixupx.com",
              "vxtwitter.com",
              "www.vxtwitter.com"));
  private static final Pattern IMAGE_FORMAT =
      Pattern.compile("(?:^|&)format=(jpe?g|png|webp|gif)(?:&|$)");

  private LinkParser() {}

  public static final class Parsed {
    public final String id;
    public final String canonicalUrl;
    public final String directUrl;

    private Parsed(String id, String canonicalUrl, String directUrl) {
      this.id = id;
      this.canonicalUrl = canonicalUrl;
      this.directUrl = directUrl;
    }
  }

  /** Accepts a URL or ordinary share text containing a URL. */
  public static Parsed parse(String input) throws IOException {
    if (input == null || input.trim().isEmpty()) {
      throw new IOException("Paste a public X or Twitter post link first.");
    }
    if (input.length() > 8192) throw new IOException("The pasted link is too long.");
    if (hasForbiddenScheme(input)) throw new IOException("Use an HTTPS public post link.");
    Matcher links = LINKS.matcher(input);
    while (links.find()) {
      String candidate = links.group();
      // Messaging apps commonly wrap or punctuate links with these characters.
      while (!candidate.isEmpty()
          && ".,!;:)]}".indexOf(candidate.charAt(candidate.length() - 1)) >= 0) {
        candidate = candidate.substring(0, candidate.length() - 1);
      }
      Parsed parsed = parseUrl(candidate);
      if (parsed != null) return parsed;
    }
    throw new IOException("Use an HTTPS X or Twitter post link, or a direct Twitter media link.");
  }

  private static Parsed parseUrl(String url) {
    URI uri = safeHttpsUri(url);
    if (uri == null || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    if (POST_HOSTS.contains(host)) {
      Matcher status = STATUS.matcher(uri.getRawPath());
      if (!status.matches()) return null;
      String id = status.group(1);
      // X's snowflakes are unsigned 64-bit integers, without leading zeroes.
      if (id.length() == 20 && id.compareTo("18446744073709551615") > 0) return null;
      return new Parsed(id, "https://x.com/i/status/" + id, null);
    }
    if (mediaMimeType(url) != null) {
      String clean =
          "https://"
              + host
              + uri.getRawPath()
              + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
      return new Parsed("", clean, clean);
    }
    return null;
  }

  static URI safeHttpsUri(String url) {
    if (url == null || url.indexOf('\\') >= 0) return null;
    try {
      URI uri = new URI(url);
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.isOpaque()
          || uri.getPort() > 65535) return null;
      return uri;
    } catch (URISyntaxException e) {
      return null;
    }
  }

  /**
   * Preview images load without a user action, so beyond {@link #safeHttpsUri} they must use the
   * default port and a public DNS name: no IP literals, single-label, localhost or .local hosts.
   */
  public static URI safePreviewUri(String url) {
    if (url == null || url.length() > 4096) return null;
    URI uri = safeHttpsUri(url);
    if (uri == null || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
    if (host.startsWith("[")
        || host.indexOf(':') >= 0
        // Dotted, hex and integer IPv4 forms all resolve without DNS.
        || host.matches("(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*")
        || host.indexOf('.') < 0
        || host.equals("localhost")
        || host.endsWith(".localhost")
        || host.endsWith(".local")
        || !host.matches("[a-z0-9.-]+")) return null;
    return uri;
  }

  static boolean hasForbiddenScheme(String input) {
    return input.trim().matches("(?is)^(?:javascript|data|file|ftp):.*");
  }

  /** Returns a supported CDN MIME type; playlists and preview-only fields are not downloads. */
  static String mediaMimeType(String url) {
    URI uri = safeHttpsUri(url);
    if (uri == null || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    String path = uri.getPath().toLowerCase(Locale.ROOT);
    if ("video.twimg.com".equals(host) && path.endsWith(".mp4")) return "video/mp4";
    if (!"pbs.twimg.com".equals(host)) return null;
    String extension = path.substring(path.lastIndexOf('.') + 1);
    if (uri.getRawQuery() != null) {
      Matcher format = IMAGE_FORMAT.matcher(uri.getRawQuery().toLowerCase(Locale.ROOT));
      if (format.find()) extension = format.group(1);
    }
    switch (extension) {
      case "jpg":
      case "jpeg":
        return "image/jpeg";
      case "png":
        return "image/png";
      case "webp":
        return "image/webp";
      case "gif":
        return "image/gif";
      default:
        return null;
    }
  }
}
