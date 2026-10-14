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
    if (!allowed(initial)) throw new UserError(ErrorCode.EMBED_INVALID_URL);
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
            throw new UserError(error, ErrorCode.EMBED_INVALID_REDIRECT);
          }
          if (!sameEmbed(initial, next))
            throw new UserError(ErrorCode.EMBED_REDIRECTED_AWAY);
          if (redirects == 3) throw new UserError(ErrorCode.EMBED_TOO_MANY_REDIRECTS);
          current = next;
          continue;
        }
        if (status != 200) return new MediaResolver.Response(status, "");
        if (connection.getContentLengthLong() > MAX_BODY_BYTES)
          throw new UserError(ErrorCode.EMBED_TOO_LARGE);
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
              throw new UserError(ErrorCode.EMBED_TOO_LARGE);
            body.write(buffer, 0, read);
          }
          return new MediaResolver.Response(
              status, new String(body.toByteArray(), StandardCharsets.UTF_8));
        }
      } finally {
        connection.disconnect();
      }
    }
    throw new UserError(ErrorCode.EMBED_TOO_MANY_REDIRECTS);
  }

  static void checkBody(MediaResolver.Response response, String platform) throws IOException {
    if (response == null) throw new UserError(ErrorCode.EMBED_EMPTY);
    if (response.statusCode == 429)
      throw new UserError(ErrorCode.RATE_LIMITED, platform);
    if (response.statusCode == 401 || response.statusCode == 403)
      throw new UserError(ErrorCode.LOGIN_REQUIRED, platform);
    if (response.statusCode == 404 || response.statusCode == 410)
      throw new UserError(ErrorCode.UNAVAILABLE, platform);
    if (response.statusCode != 200)
      throw new UserError(ErrorCode.EMBED_STATUS, platform, response.statusCode);
    if (response.body.length() > MAX_BODY_BYTES
        || response.body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES)
      throw new UserError(ErrorCode.EMBED_TOO_LARGE);
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
      throw new UserError(ErrorCode.CANCELLED);
  }

  private static int remainingTimeout(long deadline, int maximum) throws IOException {
    long remaining = (deadline - System.nanoTime()) / 1_000_000;
    if (remaining <= 0) throw new UserError(ErrorCode.EMBED_TIMEOUT);
    return (int) Math.min(remaining, maximum);
  }
}
