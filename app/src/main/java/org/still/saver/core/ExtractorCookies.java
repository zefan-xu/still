// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Converts yt-dlp's scoped cookie field into a header for the selected HTTPS media URL. */
public final class ExtractorCookies {
  private static final int MAX_LENGTH = 8192;

  private ExtractorCookies() {}

  /** Keeps the extractor's scope/expiry information for later admission and redirect checks. */
  public static String sanitize(String serialized) {
    return serialized == null
            || serialized.length() > MAX_LENGTH
            || serialized.indexOf('\r') >= 0
            || serialized.indexOf('\n') >= 0
        ? ""
        : serialized;
  }

  public static String forUrl(String serialized, String url, long nowSeconds) {
    URI target = LinkParser.safeHttpsUri(url);
    if (target == null
        || serialized == null
        || serialized.length() > MAX_LENGTH
        || serialized.indexOf('\r') >= 0
        || serialized.indexOf('\n') >= 0) return "";
    List<String> pairs = new ArrayList<>();
    Cookie current = null;
    for (String part : serialized.split(";", -1)) {
      int equals = part.indexOf('=');
      String name = (equals < 0 ? part : part.substring(0, equals)).trim();
      String value = equals < 0 ? "" : part.substring(equals + 1).trim();
      String attribute = name.toLowerCase(Locale.ROOT);
      if (attribute.equals("domain")
          || attribute.equals("path")
          || attribute.equals("expires")
          || attribute.equals("secure")
          || attribute.equals("version")
          || attribute.equals("httponly")
          || attribute.equals("samesite")
          || attribute.equals("max-age")) {
        if (current == null) continue;
        switch (attribute) {
          case "domain":
            current.domain = value.toLowerCase(Locale.ROOT);
            break;
          case "path":
            current.path = value;
            break;
          case "expires":
            try {
              current.expires = Long.parseLong(value);
            } catch (NumberFormatException ignored) {
              current.valid = false;
            }
            break;
          default:
            break;
        }
      } else {
        if (current != null) current.appendIfMatches(target, nowSeconds, pairs);
        current = new Cookie(name, value, equals >= 0);
      }
    }
    if (current != null) current.appendIfMatches(target, nowSeconds, pairs);
    return String.join("; ", pairs);
  }

  private static final class Cookie {
    final String name, value;
    String domain = "", path = "/";
    long expires = Long.MAX_VALUE;
    boolean valid;

    Cookie(String name, String rawValue, boolean hasEquals) {
      this.name = name;
      if (rawValue.length() >= 2 && rawValue.startsWith("\"") && rawValue.endsWith("\""))
        rawValue = rawValue.substring(1, rawValue.length() - 1);
      value = rawValue;
      valid =
          hasEquals
              && name.matches("[!#$%&'*+.^_`|~A-Za-z0-9-]+")
              && value.matches("[\\x21\\x23-\\x2B\\x2D-\\x3A\\x3C-\\x5B\\x5D-\\x7E]*");
    }

    void appendIfMatches(URI target, long nowSeconds, List<String> output) {
      if (!valid || domain.isEmpty() || expires <= nowSeconds || !path.startsWith("/")) return;
      String host = target.getHost().toLowerCase(Locale.ROOT);
      boolean subdomains = domain.startsWith(".");
      String base = subdomains ? domain.substring(1) : domain;
      if (!base.matches("[A-Za-z0-9.-]+") || base.startsWith(".") || base.endsWith(".")) return;
      if (!host.equals(base) && !(subdomains && host.endsWith("." + base))) return;
      String requestedPath = target.getRawPath();
      if (requestedPath.isEmpty()) requestedPath = "/";
      if (!requestedPath.equals(path)
          && !(requestedPath.startsWith(path)
              && (path.endsWith("/") || requestedPath.charAt(path.length()) == '/'))) return;
      output.add(name + "=" + value);
    }
  }
}
