// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import org.junit.Test;

public class ThreadsEmbedResolverTest {
  private static final String LINK = "https://www.threads.com/@demo/post/AbC123";
  private static final String MP4 = "https://scontent.cdninstagram.com/clip.mp4?a=1&b=2";

  @Test
  public void canonicalizesLegacyDomainAndUsesOnlyAnonymousEmbed() throws Exception {
    ThreadsEmbedResolver resolver =
        new ThreadsEmbedResolver(
            url -> {
              assertEquals(LINK + "/embed", url);
              return new MediaResolver.Response(
                  200, embed("<video><source src='" + MP4.replace("&", "&amp;") + "'></video>"));
            });
    Post post = resolver.resolve("Shared: https://threads.net/@DEMO/post/AbC123/?xmt=tracking");
    assertEquals(LINK, post.sourceUrl);
    assertEquals("AbC123", post.id);
    assertEquals("@demo", post.author);
    assertEquals("Requested & caption", post.title);
    assertEquals(MP4, post.media.get(0).variants.get(0).url);
    assertEquals("MP4", post.media.get(0).variants.get(0).label);
  }

  @Test
  public void ignoresContextVideoAndPreservesDistinctTargetAttachments() throws Exception {
    String html =
        "<div><span class='BodyTextContainer'>Parent caption</span><video"
            + " src='https://scontent.fbcdn.net/parent.mp4'></video></div>"
            + embed(
                "<video src='"
                    + MP4
                    + "'><source src='"
                    + MP4
                    + "'><source src='https://scontent.fbcdn.net/other-quality.mp4'></video>"
                    + "<video src='https://scontent.fbcdn.net/second.mp4'></video>");
    Post post = resolve(html);
    assertEquals("Requested & caption", post.title);
    assertEquals(2, post.media.size());
    assertEquals(2, post.media.get(0).variants.size());
    assertEquals("https://scontent.fbcdn.net/second.mp4", post.media.get(1).variants.get(0).url);
  }

  @Test
  public void rejectsWrongIdentityAmbiguousContainersAndUnavailableEmbeds() throws Exception {
    String video = "<video src='" + MP4 + "'></video>";
    assertError(embed(video).replace("post/AbC123?", "post/Wrong?"), "did not provide");
    assertError(
        embed(video) + "<div class='OuterContainerFull'>" + video + "</div>", "did not provide");
    assertError("<html><body>Thread not available</body></html>", "Threads says");
    assertError(embed("<img src='https://scontent.fbcdn.net/preview.jpg'>"), "no downloadable");
  }

  @Test
  public void rejectsUnsafePreviewAndStreamingSources() throws Exception {
    String[] sources = {
      "http://scontent.fbcdn.net/a.mp4",
      "https://user@scontent.fbcdn.net/a.mp4",
      "https://scontent.fbcdn.net.evil.test/a.mp4",
      "https://example.com/a.mp4",
      "https://scontent.fbcdn.net:444/a.mp4",
      "https://scontent.fbcdn.net/a.m3u8",
      "https://scontent.fbcdn.net/a.jpg"
    };
    for (String source : sources)
      assertError(embed("<video><source src='" + source + "'></video>"), "no downloadable");
    assertError(
        embed("<video><source src='" + MP4 + "' type='application/dash+xml'></video>"),
        "no downloadable");
    assertError(
        embed("<source src='" + MP4 + "'><meta property='og:video' content='" + MP4 + "'>"),
        "no downloadable");
  }

  @Test
  public void capsDistinctAttachmentsAndVariants() throws Exception {
    StringBuilder html = new StringBuilder();
    for (int i = 0; i < 25; i++) {
      html.append("<video>");
      for (int j = 0; j < 25; j++)
        html.append("<source src='https://scontent.fbcdn.net/")
            .append(i)
            .append('-')
            .append(j)
            .append(".mp4'>");
      html.append("</video>");
    }
    Post post = resolve(embed(html.toString()));
    assertEquals(20, post.media.size());
    assertEquals(20, post.media.get(0).variants.size());
  }

  @Test
  public void reportsHttpFailuresAndRejectsOversizedBodies() throws Exception {
    for (int status : new int[] {401, 403, 404, 410, 429, 500}) {
      try {
        new ThreadsEmbedResolver(url -> new MediaResolver.Response(status, embed("")))
            .resolve(LINK);
        fail("Accepted status " + status);
      } catch (IOException expected) {
        assertTrue(expected.getMessage().contains("Threads"));
      }
    }
    char[] body = new char[PublicEmbedTransport.MAX_BODY_BYTES / 2];
    Arrays.fill(body, '\u20ac');
    try {
      new ThreadsEmbedResolver(url -> new MediaResolver.Response(200, new String(body)))
          .resolve(LINK);
      fail("Accepted oversized UTF-8 body");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("too large"));
    }
  }

  @Test
  public void redirectsMustRetainEmbedAndPostIdentity() {
    URI initial = URI.create(LINK + "/embed");
    assertTrue(PublicEmbedTransport.sameEmbed(initial, URI.create(LINK + "/embed/")));
    for (String destination :
        new String[] {
          "https://www.threads.com/",
          "https://www.threads.com/login",
          "https://www.threads.com/@demo/post/Other/embed",
          "https://evil.test/@demo/post/AbC123/embed",
          "http://www.threads.com/@demo/post/AbC123/embed",
          LINK + "/embed?anything=1"
        }) {
      assertFalse(destination, PublicEmbedTransport.sameEmbed(initial, URI.create(destination)));
    }
    URI facebook =
        URI.create(
            "https://www.facebook.com/plugins/video.php?href=https%3A%2F%2Fwww.facebook.com%2Freel%2F123");
    assertFalse(
        PublicEmbedTransport.sameEmbed(
            facebook, URI.create(facebook.toString().replace("123", "456"))));
  }

  @Test
  public void usesPosterFromTheVideoCdnOnly() throws Exception {
    String poster = "https://scontent.cdninstagram.com/poster.jpg?a=1&b=2";
    Post post =
        resolve(
            embed(
                "<video poster='"
                    + poster.replace("&", "&amp;")
                    + "' src='"
                    + MP4
                    + "'></video>"));
    assertEquals(poster, post.media.get(0).thumbnail);
    assertEquals(0, post.media.get(0).durationMs);
    for (String unsafe :
        new String[] {
          "https://images.example/poster.jpg",
          "http://scontent.cdninstagram.com/poster.jpg",
          "https://scontent.cdninstagram.com:444/poster.jpg"
        }) {
      Post rejected = resolve(embed("<video poster='" + unsafe + "' src='" + MP4 + "'></video>"));
      assertNull(unsafe, rejected.media.get(0).thumbnail);
    }
  }

  @Test
  public void photoPostsKeepTheirImagesInOrderAndSkipAvatars() throws Exception {
    String photo = "https://scontent-a.cdninstagram.com/v/t51.82787-15/one_n.jpg?stp=x&amp;a=1";
    String html =
        embed(
            "<div class='AvatarContainer'><img src='https://scontent.cdninstagram.com/v/t51-19/me.jpg'></div>"
                + "<div class='MediaScrollImageContainer'><img src='"
                + photo
                + "'></div><div class='MediaScrollImageContainer'><img"
                + " src='https://scontent-b.cdninstagram.com/v/t51.82787-15/one_n.jpg?stp=x'></div>"
                + "<video src='"
                + MP4
                + "'></video><div class='MediaScrollImageContainer'><img"
                + " src='https://scontent.fbcdn.net/v/two_n.webp'></div>");
    Post post = resolve(html);
    assertEquals(3, post.media.size()); // The second image is the first on another CDN host.
    assertEquals("photo", post.media.get(0).kind);
    assertEquals(photo.replace("&amp;", "&"), post.media.get(0).variants.get(0).url);
    assertEquals("image/jpeg", post.media.get(0).variants.get(0).mimeType);
    assertEquals("video", post.media.get(1).kind);
    assertEquals("image/webp", post.media.get(2).variants.get(0).mimeType);
    String single =
        embed(
            "<div class='SingleInnerMediaContainer'><img"
                + " src='https://scontent.cdninstagram.com/v/single_n.jpg'></div>");
    assertEquals(1, resolve(single).media.size());
  }

  private static String embed(String media) {
    return "<div class='LinkContainer'><a href='"
        + LINK
        + "?tracking=1'>Permalink</a></div><div class='OuterContainerFull'><span"
        + " class='TextContentContainerFull'><span class='BodyTextContainer'>Requested &amp;"
        + " caption</span></span>"
        + media
        + "</div>";
  }

  private static Post resolve(String html) throws Exception {
    return new ThreadsEmbedResolver(url -> new MediaResolver.Response(200, html)).resolve(LINK);
  }

  private static void assertError(String html, String fragment) throws Exception {
    try {
      resolve(html);
      fail("Expected " + fragment);
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains(fragment));
    }
  }
}
