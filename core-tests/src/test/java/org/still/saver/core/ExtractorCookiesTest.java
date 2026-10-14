// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.*;

import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ExtractorCookiesTest {
  @Test
  public void selectsOnlyUnexpiredCookiesForTheRequestedDomainAndPath() {
    String source =
        "session=one; Domain=.example.com; Path=/media; Secure; Expires=200; "
            + "expired=old; Domain=.example.com; Path=/; Expires=99; "
            + "other=secret; Domain=.other.test; Path=/; "
            + "wrongpath=hidden; Domain=.example.com; Path=/account";
    assertEquals(
        "session=one",
        ExtractorCookies.forUrl(source, "https://cdn.example.com/media/file.mp4", 100));
    assertEquals(
        "", ExtractorCookies.forUrl(source, "https://cdn.example.com/medialibrary/file.mp4", 100));
    assertEquals(
        "", ExtractorCookies.forUrl(source, "https://example.com.evil.test/media/file.mp4", 100));
    assertEquals(
        "", ExtractorCookies.forUrl(source, "https://fakeexample.com/media/file.mp4", 100));
  }

  @Test
  public void requiresExplicitScopeAndKeepsHostOnlyCookiesOnTheirHost() {
    assertEquals(
        "", ExtractorCookies.forUrl("unscoped=secret", "https://cdn.example.com/a.mp4", 100));
    String source = "host=one; Domain=example.com; Path=/";
    assertEquals("host=one", ExtractorCookies.forUrl(source, "https://example.com/a.mp4", 100));
    assertEquals("", ExtractorCookies.forUrl(source, "https://cdn.example.com/a.mp4", 100));
    assertEquals("", ExtractorCookies.forUrl(source, "http://example.com/a.mp4", 100));
  }

  @Test
  public void rejectsHeaderInjectionInvalidValuesAndOversizedInput() {
    assertEquals(
        "",
        ExtractorCookies.forUrl(
            "name=value\r\nInjected: yes; Domain=example.com", "https://example.com/a", 100));
    assertEquals(
        "",
        ExtractorCookies.forUrl(
            "bad name=value; Domain=example.com", "https://example.com/a", 100));
    assertEquals(
        "",
        ExtractorCookies.forUrl(
            "name=back\\slash; Domain=example.com", "https://example.com/a", 100));
    assertEquals(
        "",
        ExtractorCookies.forUrl(
            "name=value; Domain=example.com; Expires=not-a-time", "https://example.com/a", 100));
    char[] tooLong = new char[8193];
    java.util.Arrays.fill(tooLong, 'a');
    assertEquals("", ExtractorCookies.forUrl(new String(tooLong), "https://example.com/a", 100));
  }

  @Test
  public void handlesQuotedValuesAndDoesNotInheritScopeBetweenCookies() {
    String source =
        "first=\"abc==\"; Domain=.example.com; Path=/; unscoped=secret; "
            + "third=xyz; Domain=.example.com; Path=/";
    assertEquals(
        "first=abc==; third=xyz",
        ExtractorCookies.forUrl(source, "https://cdn.example.com/a.mp4", 100));
  }

  @Test
  public void extractorVariantsReceiveScopedCookiesButRawAuthenticationHeadersAreIgnored()
      throws Exception {
    JSONObject format =
        new JSONObject()
            .put("url", "https://cdn.example.com/a.mp4")
            .put("ext", "mp4")
            .put("protocol", "https")
            .put("vcodec", "h264")
            .put("acodec", "aac")
            .put("cookies", "session=generated; Domain=.example.com; Path=/")
            .put(
                "http_headers",
                new JSONObject().put("Cookie", "unscoped=bad").put("Authorization", "bad"));
    JSONObject root = new JSONObject().put("formats", new JSONArray().put(format));
    Variant parsed =
        ExtractorJsonParser.parse(root.toString(), "https://www.tiktok.com/@example/video/42")
            .media
            .get(0)
            .variants
            .get(0);
    assertEquals("session=generated; Domain=.example.com; Path=/", parsed.scopedCookies);
    assertEquals(
        "session=generated", ExtractorCookies.forUrl(parsed.scopedCookies, parsed.url, 100));
    assertFalse(parsed.headers.containsKey("Cookie"));
    assertFalse(parsed.headers.containsKey("Authorization"));
    Variant unscoped =
        new Variant(
            parsed.url,
            "Video",
            "video/mp4",
            0,
            0,
            0,
            Collections.singletonMap("Cookie", "unscoped=bad"));
    assertFalse(unscoped.headers.containsKey("Cookie"));
    assertEquals("", unscoped.scopedCookies);
  }

  @Test
  public void preservesCookieExpiryAndReevaluatesItAtAdmissionAndEveryRedirect() throws Exception {
    String source = "temporary=public; Domain=.example.com; Path=/media; Expires=200";
    JSONObject format =
        new JSONObject()
            .put("url", "https://cdn.example.com/media/a.mp4")
            .put("ext", "mp4")
            .put("protocol", "https")
            .put("vcodec", "h264")
            .put("acodec", "aac")
            .put("cookies", source);
    Variant variant =
        ExtractorJsonParser.parse(
                new JSONObject().put("formats", new JSONArray().put(format)).toString(),
                "https://www.tiktok.com/@example/video/42")
            .media
            .get(0)
            .variants
            .get(0);
    assertEquals(source, variant.scopedCookies);
    assertFalse(variant.headers.containsKey("Cookie"));
    assertEquals(
        "temporary=public", ExtractorCookies.forUrl(variant.scopedCookies, variant.url, 100));
    assertEquals("", ExtractorCookies.forUrl(variant.scopedCookies, variant.url, 201));
    assertEquals(
        "",
        ExtractorCookies.forUrl(
            variant.scopedCookies, "https://cdn.example.com/another/a.mp4", 100));
    assertEquals(
        "",
        ExtractorCookies.forUrl(variant.scopedCookies, "https://unrelated.test/media/a.mp4", 100));
  }
}
