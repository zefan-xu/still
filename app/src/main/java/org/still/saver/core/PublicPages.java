// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * Anonymous GETs of public pages and APIs: HTTPS only, no cookies, bounded bodies, and redirects
 * returned to the caller so each resolver decides which hops keep the post's identity.
 */
public final class PublicPages {
  static final String BROWSER =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
          + " Chrome/140.0.0.0 Safari/537.36";
  static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

  public interface Http {
    Reply get(String url, Map<String, String> headers) throws IOException;
  }

  public static final class Reply {
    public final int status;
    public final String body;
    public final String location;

    public Reply(int status, String body, String location) {
      this.status = status;
      this.body = body == null ? "" : body;
      this.location = location;
    }
  }

  public static final Http HTTPS =
      new Http() {
        @Override
        public Reply get(String url, Map<String, String> headers) throws IOException {
          URI uri = LinkParser.safeHttpsUri(url);
          if (uri == null || (uri.getPort() != -1 && uri.getPort() != 443))
            throw new IllegalArgumentException("Public pages must use HTTPS");
          HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
          connection.setConnectTimeout(12000);
          connection.setReadTimeout(15000);
          connection.setInstanceFollowRedirects(false);
          connection.setUseCaches(false);
          connection.setRequestProperty("User-Agent", BROWSER);
          connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
          for (Map.Entry<String, String> header : headers.entrySet())
            connection.setRequestProperty(header.getKey(), header.getValue());
          try {
            int status = connection.getResponseCode();
            String location = connection.getHeaderField("Location");
            if (status != 200) return new Reply(status, "", location);
            if (connection.getContentLengthLong() > MAX_BODY_BYTES)
              throw new IOException("The public page is too large.");
            try (InputStream input = connection.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
              byte[] buffer = new byte[16384];
              int total = 0, read;
              while ((read = input.read(buffer)) != -1) {
                checkCancelled();
                total += read;
                if (total > MAX_BODY_BYTES) throw new IOException("The public page is too large.");
                out.write(buffer, 0, read);
              }
              return new Reply(status, new String(out.toByteArray(), StandardCharsets.UTF_8), null);
            }
          } finally {
            connection.disconnect();
          }
        }
      };

  private PublicPages() {}

  static Map<String, String> accept(String value) {
    return Collections.singletonMap("Accept", value);
  }

  /** Resolves a redirect against the current URL; null when it is not a safe HTTPS URL. */
  static URI redirect(String current, String location) {
    if (location == null) return null;
    try {
      return LinkParser.safeHttpsUri(URI.create(current).resolve(location).toString());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** A host equal to {@code domain} or one of its subdomains. */
  static boolean onDomain(String url, String domain) {
    URI uri = LinkParser.safeHttpsUri(url);
    if (uri == null || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    return host.equals(domain) || host.endsWith("." + domain);
  }

  static void checkStatus(Reply reply, String platform) throws IOException {
    if (reply.status == 429)
      throw new IOException(platform + " is rate limited. Wait a moment and try again.");
    if (reply.status == 401 || reply.status == 403)
      throw new IOException("This " + platform + " post requires login or is restricted.");
    if (reply.status == 404 || reply.status == 410)
      throw new IOException("This " + platform + " post is deleted or unavailable.");
    if (reply.status != 200)
      throw new IOException(
          "Could not load this " + platform + " post (status " + reply.status + ").");
  }

  static void checkCancelled() throws IOException {
    if (Thread.currentThread().isInterrupted())
      throw new IOException("Media extraction was cancelled.");
  }
}
