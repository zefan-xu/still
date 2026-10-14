// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Locale;

/**
 * Downloads a small preview image without credentials. Previews load without a user action, so
 * every hop is rechecked with {@link LinkParser#safePreviewUri} and no cookies, Referer, or
 * extractor headers are ever sent.
 */
public final class PreviewImages {
  public static final int MAX_BYTES = 2 * 1024 * 1024;
  private static final int MAX_REDIRECTS = 3;

  public interface Opener {
    HttpURLConnection open(URI uri) throws IOException;
  }

  public static final Opener HTTPS =
      uri -> (HttpURLConnection) new URL(uri.toASCIIString()).openConnection();

  private PreviewImages() {}

  public static byte[] fetch(String url) throws IOException {
    return fetch(url, HTTPS);
  }

  public static byte[] fetch(String url, Opener opener) throws IOException {
    // A process-wide cookie store would attach platform cookies to preview requests.
    if (CookieHandler.getDefault() != null)
      throw new IllegalStateException("Preview requests must not use a shared cookie store");
    URI current = LinkParser.safePreviewUri(url);
    if (current == null) throw new IOException("Unsafe preview URL");
    for (int redirects = 0; ; redirects++) {
      HttpURLConnection connection = opener.open(current);
      try {
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "image/*");
        connection.setRequestProperty("User-Agent", "Still (open-source public media saver)");
        int status = connection.getResponseCode();
        if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
          if (redirects == MAX_REDIRECTS) throw new IOException("Too many preview redirects");
          String location = connection.getHeaderField("Location");
          if (location == null) throw new IOException("Invalid preview redirect");
          URI next;
          try {
            next = current.resolve(location);
          } catch (IllegalArgumentException e) {
            throw new IOException("Invalid preview redirect", e);
          }
          current = LinkParser.safePreviewUri(next.toString());
          if (current == null) throw new IOException("Preview redirected to an unsafe URL");
          continue;
        }
        if (status != HttpURLConnection.HTTP_OK)
          throw new IOException("Preview unavailable (status " + status + ")");
        String type = connection.getContentType();
        String mime =
            type == null ? "" : type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        // Some CDNs (Bluesky) label thumbnails as octet-stream; the signature check decides.
        if (!mime.equals("image/jpeg")
            && !mime.equals("image/png")
            && !mime.equals("image/webp")
            && !mime.equals("image/gif")
            && !mime.equals("application/octet-stream"))
          throw new IOException("Preview is not an image");
        if (connection.getContentLengthLong() > MAX_BYTES)
          throw new IOException("Preview is too large");
        try (InputStream input = connection.getInputStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream()) {
          byte[] buffer = new byte[8192];
          int read;
          int total = 0;
          while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BYTES) throw new IOException("Preview is too large");
            body.write(buffer, 0, read);
          }
          byte[] bytes = body.toByteArray();
          if (!imageSignature(bytes)) throw new IOException("Preview is not an image");
          return bytes;
        }
      } finally {
        connection.disconnect();
      }
    }
  }

  /** JPEG, PNG, GIF or WebP magic bytes. */
  static boolean imageSignature(byte[] b) {
    if (b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff)
      return true;
    if (b.length >= 8
        && (b[0] & 0xff) == 0x89
        && b[1] == 'P'
        && b[2] == 'N'
        && b[3] == 'G'
        && b[4] == 0x0d
        && b[5] == 0x0a
        && b[6] == 0x1a
        && b[7] == 0x0a) return true;
    if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return true;
    return b.length >= 12
        && b[0] == 'R'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == 'F'
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P';
  }
}
