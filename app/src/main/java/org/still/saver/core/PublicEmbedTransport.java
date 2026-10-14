// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Anonymous public embed GETs with bounded responses and redirects that retain post identity. */
final class PublicEmbedTransport implements MediaResolver.Transport {
  static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

  @Override
  public MediaResolver.Response get(String url) throws IOException {
    URI initial = LinkParser.safeHttpsUri(url);
    if (!allowed(initial)) throw new IOException("Invalid public embed URL.");
    URI current = initial;
    long deadline = System.nanoTime() + 45_000_000_000L;
    for (int redirects = 0; redirects <= 3; redirects++) {
      checkCancelled();
      HttpURLConnection connection =
          (HttpURLConnection) new URL(current.toASCIIString()).openConnection();
      connection.setConnectTimeout(remainingTimeout(deadline, 12000));
      connection.setReadTimeout(remainingTimeout(deadline, 10000));
      connection.setInstanceFollowRedirects(false);
      connection.setRequestMethod("GET");
      connection.setRequestProperty("Accept", "text/html");
      connection.setRequestProperty("User-Agent", "Mozilla/5.0");
      try {
        int status = connection.getResponseCode();
        checkCancelled();
        remainingTimeout(deadline, 10000);
        if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
          String location = connection.getHeaderField("Location");
          URI next;
          try {
            next =
                location == null
                    ? null
                    : LinkParser.safeHttpsUri(current.resolve(location).toString());
          } catch (IllegalArgumentException error) {
            throw new IOException("Invalid public embed redirect.", error);
          }
          if (!sameEmbed(initial, next))
            throw new IOException("The public embed redirected away from this post.");
          if (redirects == 3) throw new IOException("Too many public embed redirects.");
          current = next;
          continue;
        }
        if (status != 200) return new MediaResolver.Response(status, "");
        if (connection.getContentLengthLong() > MAX_BODY_BYTES)
          throw new IOException("The public embed response is too large.");
        try (InputStream input = connection.getInputStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream()) {
          byte[] buffer = new byte[8192];
          int total = 0;
          while (true) {
            checkCancelled();
            connection.setReadTimeout(remainingTimeout(deadline, 10000));
            int read = input.read(buffer);
            checkCancelled();
            remainingTimeout(deadline, 10000);
            if (read == -1) break;
            total += read;
            if (total > MAX_BODY_BYTES)
              throw new IOException("The public embed response is too large.");
            body.write(buffer, 0, read);
          }
          return new MediaResolver.Response(
              status, new String(body.toByteArray(), StandardCharsets.UTF_8));
        }
      } finally {
        connection.disconnect();
      }
    }
    throw new IOException("Too many public embed redirects.");
  }

  static void checkBody(MediaResolver.Response response, String platform) throws IOException {
    if (response == null) throw new IOException("The public embed returned an empty response.");
    if (response.statusCode == 429)
      throw new IOException(platform + " is rate limited. Wait a moment and try again.");
    if (response.statusCode == 401 || response.statusCode == 403)
      throw new IOException("This " + platform + " post requires login or is restricted.");
    if (response.statusCode == 404 || response.statusCode == 410)
      throw new IOException("This " + platform + " post is deleted or unavailable.");
    if (response.statusCode != 200)
      throw new IOException(
          "Could not load the public " + platform + " embed (status " + response.statusCode + ").");
    if (response.body.length() > MAX_BODY_BYTES
        || response.body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES)
      throw new IOException("The public embed response is too large.");
  }

  static boolean sameEmbed(URI initial, URI next) {
    return allowed(initial)
        && allowed(next)
        && initial.getHost().equalsIgnoreCase(next.getHost())
        && trimSlash(initial.getRawPath()).equals(trimSlash(next.getRawPath()))
        && java.util.Objects.equals(initial.getRawQuery(), next.getRawQuery());
  }

  private static boolean allowed(URI uri) {
    if (uri == null
        || LinkParser.safeHttpsUri(uri.toString()) == null
        || uri.getRawFragment() != null
        || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
    if ("www.threads.com".equalsIgnoreCase(uri.getHost())) {
      return uri.getRawPath().matches("/@[A-Za-z0-9._-]+/post/[A-Za-z0-9_-]+/embed/?")
          && uri.getRawQuery() == null;
    }
    return "www.facebook.com".equalsIgnoreCase(uri.getHost())
        && "/plugins/video.php".equals(uri.getRawPath())
        && uri.getRawQuery() != null
        && uri.getRawQuery().startsWith("href=https%3A%2F%2F");
  }

  private static String trimSlash(String path) {
    return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
  }

  private static void checkCancelled() throws IOException {
    if (Thread.currentThread().isInterrupted())
      throw new IOException("Media extraction was cancelled.");
  }

  private static int remainingTimeout(long deadline, int maximum) throws IOException {
    long remaining = (deadline - System.nanoTime()) / 1_000_000;
    if (remaining <= 0) throw new IOException("The public embed request timed out. Try again.");
    return (int) Math.min(remaining, maximum);
  }
}
