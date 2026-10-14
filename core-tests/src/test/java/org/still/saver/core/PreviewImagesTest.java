// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class PreviewImagesTest {
  private static final String IMAGE = "https://pbs.twimg.com/media/a?format=jpg&name=small";
  private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1};
  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 7};
  private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 4};

  @Test
  public void fetchesAnImageWithoutCredentialsOrCaching() throws Exception {
    Server server = new Server().reply(Fake.image(JPEG));
    assertArrayEquals(JPEG, PreviewImages.fetch(IMAGE, server));
    Fake request = server.opened.get(0);
    assertEquals("image/*", request.getRequestProperty("Accept"));
    assertNull(request.getRequestProperty("Cookie"));
    assertNull(request.getRequestProperty("Referer"));
    assertFalse(request.getUseCaches());
    assertFalse(request.getInstanceFollowRedirects());
    assertEquals(8000, request.getConnectTimeout());
    assertEquals(10000, request.getReadTimeout());
    assertTrue(request.disconnected);
  }

  @Test
  public void followsAtMostThreeRevalidatedRedirects() throws Exception {
    Server server =
        new Server()
            .reply(Fake.redirect("/media/b?format=jpg"))
            .reply(Fake.redirect("https://images.cdn.example/c.jpg"))
            .reply(Fake.redirect("https://images.cdn.example/d.jpg"))
            .reply(Fake.image(PNG));
    assertArrayEquals(PNG, PreviewImages.fetch(IMAGE, server));
    assertEquals("https://pbs.twimg.com/media/b?format=jpg", server.uris.get(1).toString());
    assertEquals(4, server.opened.size());

    Server tooMany = new Server();
    for (int i = 0; i < 4; i++) tooMany.reply(Fake.redirect("https://images.cdn.example/x.jpg"));
    assertFails(tooMany, "Too many");

    assertFails(new Server().reply(Fake.redirect("http://pbs.twimg.com/a.jpg")), "unsafe");
    assertFails(new Server().reply(Fake.redirect("https://10.0.0.2/a.jpg")), "unsafe");
    assertFails(new Server().reply(Fake.redirect("https://localhost/a.jpg")), "unsafe");
    assertFails(new Server().reply(new Fake(302, null, -1, new byte[0])), "Invalid");
  }

  @Test
  public void acceptsOnlySmallSuccessfulImages() throws Exception {
    assertArrayEquals(
        WEBP,
        PreviewImages.fetch(
            IMAGE, new Server().reply(new Fake(200, "image/webp; charset=binary", 13, WEBP))));
    // Octet-stream is accepted only when the bytes are really an image.
    assertArrayEquals(
        JPEG,
        PreviewImages.fetch(
            IMAGE, new Server().reply(new Fake(200, "application/octet-stream", 4, JPEG))));
    assertFails(
        new Server().reply(new Fake(200, "application/octet-stream", 3, new byte[] {'<', 'h', 't'})),
        "not an image");
    assertFails(new Server().reply(new Fake(200, "image/jpeg", 2, new byte[] {1, 2})), "not an image");
    assertFails(new Server().reply(new Fake(404, "image/jpeg", 1, new byte[] {1})), "status 404");
    assertFails(new Server().reply(new Fake(200, "text/html", 1, new byte[] {1})), "not an image");
    assertFails(new Server().reply(new Fake(200, null, 1, new byte[] {1})), "not an image");
    assertFails(
        new Server()
            .reply(new Fake(200, "image/jpeg", PreviewImages.MAX_BYTES + 1L, new byte[] {1})),
        "too large");
    assertFails(
        new Server()
            .reply(new Fake(200, "image/png", -1, new byte[PreviewImages.MAX_BYTES + 1])),
        "too large");
  }

  @Test
  public void neverOpensUnsafeInitialUrls() throws Exception {
    for (String unsafe :
        new String[] {"http://pbs.twimg.com/a.jpg", "https://192.168.0.1/a.jpg", "https://intranet/a"}) {
      Server server = new Server();
      try {
        PreviewImages.fetch(unsafe, server);
        fail("Fetched " + unsafe);
      } catch (IOException expected) {
        assertTrue(server.opened.isEmpty());
      }
    }
  }

  private static void assertFails(Server server, String fragment) {
    try {
      PreviewImages.fetch(IMAGE, server);
      fail("Expected failure containing " + fragment);
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains(fragment));
    }
  }

  private static final class Server implements PreviewImages.Opener {
    final Deque<Fake> replies = new ArrayDeque<>();
    final List<Fake> opened = new ArrayList<>();
    final List<URI> uris = new ArrayList<>();

    Server reply(Fake fake) {
      replies.add(fake);
      return this;
    }

    @Override
    public HttpURLConnection open(URI uri) {
      Fake next = replies.removeFirst();
      opened.add(next);
      uris.add(uri);
      return next;
    }
  }

  private static final class Fake extends HttpURLConnection {
    final int status;
    final String type;
    final long length;
    final byte[] body;
    final Map<String, String> headers = new HashMap<>();
    boolean disconnected;

    Fake(int status, String type, long length, byte[] body) {
      super(url());
      this.status = status;
      this.type = type;
      this.length = length;
      this.body = body;
    }

    static Fake image(byte[] body) {
      return new Fake(200, "image/jpeg", body.length, body);
    }

    static Fake redirect(String location) {
      Fake fake = new Fake(302, "text/html", 0, new byte[0]);
      fake.headers.put("Location", location);
      return fake;
    }

    private static URL url() {
      try {
        return new URL(IMAGE);
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }

    @Override
    public int getResponseCode() {
      return status;
    }

    @Override
    public String getHeaderField(String name) {
      return headers.get(name);
    }

    @Override
    public String getContentType() {
      return type;
    }

    @Override
    public long getContentLengthLong() {
      return length;
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(body);
    }

    @Override
    public void connect() {}

    @Override
    public void disconnect() {
      disconnected = true;
    }

    @Override
    public boolean usingProxy() {
      return false;
    }
  }
}
