// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

/** Links arrive from any app's share sheet, so parsing must be total, canonical and strict. */
public class LinkParsingPropertyTest {
  private static final Set<String> SUPPORTED_SITES =
      new HashSet<>(
          Arrays.asList(
              "x.com", "tiktok.com", "instagram.com", "facebook.com", "fb.watch", "pinterest.com",
              "pin.it", "threads.net", "threads.com", "bsky.app", "youtube.com"));

  @Property(tries = 3000)
  void arbitraryTextIsAcceptedOrRejectedWithAMessage(@ForAll String text) {
    assertTotal(text);
  }

  @Property(tries = 3000)
  void linkShapedTextIsAcceptedOrRejectedWithAMessage(@ForAll("linkish") String text) {
    assertTotal(text);
  }

  @Property(tries = 2000)
  void everySupportedPostSurvivesShareTextAndIsCanonical(
      @ForAll("posts") String post, @ForAll("wrapping") String[] around) throws Exception {
    String canonical = PublicLinkParser.parse(post);
    assertEquals("Share text must not change the result", canonical, PublicLinkParser.parse(around[0] + post + around[1]));
    assertCanonical(canonical);
  }

  @Property(tries = 2000)
  void xStatusIdsAreUnsigned64BitWithoutLeadingZeroes(@ForAll("statusIds") String id) {
    String link = "https://x.com/someone/status/" + id;
    boolean valid =
        id.matches("[1-9][0-9]{0,19}") && new BigInteger(id).compareTo(new BigInteger("18446744073709551615")) <= 0;
    try {
      LinkParser.Parsed parsed = LinkParser.parse(link);
      assertTrue("Accepted invalid id " + id, valid);
      assertEquals(id, parsed.id);
      assertEquals("https://x.com/i/status/" + id, parsed.canonicalUrl);
    } catch (IOException rejected) {
      assertFalse("Rejected valid id " + id, valid);
    }
  }

  @Property(tries = 2000)
  void previewUrlsNeverTargetAddressesOrLocalNames(@ForAll("unsafeHosts") String host, @ForAll("paths") String path) {
    assertNull(host, LinkParser.safePreviewUri("https://" + host + path));
  }

  @Property(tries = 2000)
  void acceptedPreviewUrlsArePublicHttpsNames(@ForAll("linkish") String url) {
    URI uri = LinkParser.safePreviewUri(url);
    if (uri == null) return;
    assertEquals("https", uri.getScheme().toLowerCase(Locale.ROOT));
    assertNull(uri.getRawUserInfo());
    assertTrue(uri.getPort() == -1 || uri.getPort() == 443);
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    assertTrue(host, host.contains(".") && host.matches("[a-z0-9.-]+") && !host.matches("[0-9.]+\\.?"));
  }

  @Property(tries = 1000)
  void portsOtherThanHttpsAreRejected(
      @ForAll("posts") String post, @ForAll @LongRange(min = 0, max = 70000) long port) {
    URI uri = URI.create(post);
    String withPort = "https://" + uri.getHost() + ":" + port + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    try {
      PublicLinkParser.parse(withPort);
      assertEquals(443, port);
    } catch (IOException rejected) {
      assertNotEquals(443, port);
    }
  }

  private static void assertTotal(String text) {
    try {
      assertCanonical(PublicLinkParser.parse(text));
    } catch (IOException rejected) {
      assertNotNull(English.message(rejected));
      assertFalse(English.message(rejected).isEmpty());
    }
  }

  private static void assertCanonical(String canonical) throws IOException {
    URI uri = URI.create(canonical);
    assertEquals("https", uri.getScheme());
    assertNull(uri.getRawUserInfo());
    assertEquals(-1, uri.getPort());
    assertNull("Fragments are dropped", uri.getRawFragment());
    assertFalse(canonical.matches(".*\\s.*"));
    String host = uri.getHost();
    assertEquals(host.toLowerCase(Locale.ROOT), host);
    assertTrue(host, SUPPORTED_SITES.stream().anyMatch(s -> host.equals(s) || host.endsWith("." + s)) || host.startsWith("pinterest.") || host.contains(".pinterest.") || host.endsWith("twimg.com"));
    assertEquals("Canonical links are fixed points", canonical, PublicLinkParser.parse(canonical));
  }

  @Provide
  Arbitrary<String> posts() {
    Arbitrary<String> id = Arbitraries.strings().numeric().ofMinLength(1).ofMaxLength(19).filter(s -> s.charAt(0) != '0');
    Arbitrary<String> slug = Arbitraries.strings().withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-").ofMinLength(1).ofMaxLength(14);
    Arbitrary<String> user = Arbitraries.strings().withChars("abcdefghijklmnopqrstuvwxyz0123456789._").ofMinLength(1).ofMaxLength(15);
    Arbitrary<String> twitterUser = Arbitraries.strings().withChars("abcdefghijklmnopqrstuvwxyz0123456789_").ofMinLength(1).ofMaxLength(15);
    Arbitrary<String> youtubeId = Arbitraries.strings().withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-").ofLength(11);
    return Arbitraries.oneOf(
        Combinators.combine(Arbitraries.of("x.com", "twitter.com", "mobile.twitter.com", "fxtwitter.com"), twitterUser, id)
            .as((h, u, i) -> "https://" + h + "/" + u + "/status/" + i),
        Combinators.combine(Arbitraries.of("www.tiktok.com", "tiktok.com"), user, Arbitraries.of("video", "photo"), id)
            .as((h, u, k, i) -> "https://" + h + "/@" + u + "/" + k + "/" + i),
        slug.map(s -> "https://vm.tiktok.com/" + s + "/"),
        Combinators.combine(Arbitraries.of("www.instagram.com", "instagram.com"), Arbitraries.of("p", "reel", "reels", "tv"), slug)
            .as((h, k, s) -> "https://" + h + "/" + k + "/" + s + "/"),
        id.map(i -> "https://www.facebook.com/reel/" + i),
        Combinators.combine(user, id).as((u, i) -> "https://www.facebook.com/" + u + "/videos/" + i + "/"),
        id.map(i -> "https://www.facebook.com/watch/?v=" + i),
        slug.map(s -> "https://fb.watch/" + s + "/"),
        Combinators.combine(Arbitraries.of("www.pinterest.com", "in.pinterest.com", "pinterest.co.uk", "www.pinterest.de"), id)
            .as((h, i) -> "https://" + h + "/pin/" + i + "/"),
        slug.map(s -> "https://pin.it/" + s),
        Combinators.combine(Arbitraries.of("www.threads.net", "www.threads.com"), user, slug)
            .as((h, u, s) -> "https://" + h + "/@" + u + "/post/" + s),
        Combinators.combine(user, Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(13))
            .as((u, s) -> "https://bsky.app/profile/" + u + ".bsky.social/post/" + s),
        Combinators.combine(
                Arbitraries.of(
                    "https://www.youtube.com/watch?v=",
                    "https://m.youtube.com/watch?feature=share&v=",
                    "https://youtu.be/",
                    "https://youtube.com/shorts/"),
                youtubeId)
            .as((prefix, v) -> prefix + v));
  }

  /** Prefixes and suffixes that messaging apps put around a shared link. */
  @Provide
  Arbitrary<String[]> wrapping() {
    Arbitrary<String> before = Arbitraries.of("", " ", "Look at this ", "(", "Check it out:\n", "“Funny” — ", "\t");
    Arbitrary<String> after = Arbitraries.of("", " ", ".", ",", ")", "!", " via the app", "\n\nSent from my phone", "]");
    return Combinators.combine(before, after).as((b, a) -> new String[] {b, a});
  }

  @Provide
  Arbitrary<String> statusIds() {
    return Arbitraries.oneOf(
        Arbitraries.longs().greaterOrEqual(1).map(Long::toUnsignedString),
        Arbitraries.longs().map(Long::toUnsignedString),
        Arbitraries.of("18446744073709551615", "18446744073709551616", "99999999999999999999", "0", "01", "000123"),
        Arbitraries.strings().numeric().ofMinLength(1).ofMaxLength(22));
  }

  @Provide
  Arbitrary<String> unsafeHosts() {
    Arbitrary<Integer> octet = Arbitraries.integers().between(0, 255);
    return Arbitraries.oneOf(
        Combinators.combine(octet, octet, octet, octet).as((a, b, c, d) -> a + "." + b + "." + c + "." + d),
        Arbitraries.longs().between(0, 0xffffffffL).map(String::valueOf),
        Arbitraries.longs().between(0, 0xffffffffL).map(n -> "0x" + Long.toHexString(n)),
        Combinators.combine(octet, octet).as((a, b) -> "0x" + Integer.toHexString(a) + "." + b),
        Arbitraries.of("localhost", "LOCALHOST", "printer.local", "nas.local.", "router", "a.localhost", "[::1]", "[fe80::1]"),
        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12).map(s -> s.toLowerCase(Locale.ROOT) + ".local"),
        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12).map(s -> s.toLowerCase(Locale.ROOT)),
        Combinators.combine(Arbitraries.of("cdn.example.com", "images.example.org"), Arbitraries.integers().between(1, 65535).filter(p -> p != 443))
            .as((h, p) -> h + ":" + p));
  }

  @Provide
  Arbitrary<String> paths() {
    return Arbitraries.of("", "/", "/a.jpg", "/media/123.png?format=jpg", "/x?y=z#frag");
  }

  /** Mixtures of URL syntax that stress the scanner, URI parsing and host checks. */
  @Provide
  Arbitrary<String> linkish() {
    Arbitrary<String> scheme = Arbitraries.of("https://", "http://", "HTTPS://", "javascript:", "file://", "data:", "ftp://", "https:/", "https:///", "");
    Arbitrary<String> host =
        Arbitraries.of(
            "x.com", "www.instagram.com", "tiktok.com", "facebook.com", "pinterest.com", "in.pinterest.com",
            "bsky.app", "threads.net", "pbs.twimg.com", "video.twimg.com", "www.youtube.com", "youtu.be",
            "evil.com", "x.com.evil.com",
            "user:pass@x.com", "x.com:443", "x.com:8080", "127.0.0.1", "[::1]", "xn--80ak6aa92e.com", "x.com.",
            "ex ample.com", "%78.com", "localhost");
    Arbitrary<String> path =
        Arbitraries.strings()
            .withChars("abcXYZ019/_-.@?=&#%:;~!$'()*+, ")
            .ofMaxLength(40)
            .map(s -> "/" + s);
    Arbitrary<String> junk = Arbitraries.strings().ofMaxLength(8);
    return Combinators.combine(junk, scheme, host, path, junk).as((a, s, h, p, b) -> a + s + h + p + b);
  }
}
