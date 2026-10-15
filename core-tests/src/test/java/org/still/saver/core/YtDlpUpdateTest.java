// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/** Checksums and version checks that run before an extractor update is installed. */
public class YtDlpUpdateTest {
  private static final String VERSION = "2026.03.04";
  private static final String BINARY_URL =
      "https://github.com/yt-dlp/yt-dlp/releases/download/2026.03.04/yt-dlp";
  private static final String SUMS_URL =
      "https://github.com/yt-dlp/yt-dlp/releases/download/2026.03.04/SHA2-256SUMS";

  @Test
  public void acceptsAReleaseWhoseChecksumDigestAndVersionMatch() throws Exception {
    byte[] binary = zipapp("__version__ = '" + VERSION + "'\n");
    byte[] sums = sumsFile(sha256(binary), "yt-dlp");
    Checked checked = checked(binary, sums, "sha256:" + sha256(binary), "sha256:" + sha256(sums));
    assertArrayEquals(binary, YtDlpUpdate.verifiedZipapp(checked.release, checked.source));
    assertEquals(VERSION, checked.release.version);
    assertEquals(sha256(binary), checked.release.binary.sha256);
    assertEquals(sha256(sums), checked.release.sums.sha256);
    assertEquals(BINARY_URL, checked.release.binary.url);
    assertEquals(SUMS_URL, checked.release.sums.url);
  }

  @Test
  public void acceptsUppercaseDigestsAndABinaryModeChecksumLine() throws Exception {
    byte[] binary = zipapp("__version__ = \"" + VERSION + "\"\n");
    String hash = sha256(binary).toUpperCase(Locale.ROOT);
    byte[] sums = (hash + "  *yt-dlp\r\n").getBytes(StandardCharsets.UTF_8);
    Checked checked =
        checked(
            binary,
            sums,
            "sha256:" + hash,
            "sha256:" + sha256(sums).toUpperCase(Locale.ROOT));
    assertArrayEquals(binary, YtDlpUpdate.verifiedZipapp(checked.release, checked.source));
    assertEquals(hash.toLowerCase(Locale.ROOT), YtDlpUpdate.publishedSha256(new String(sums, StandardCharsets.UTF_8), "yt-dlp"));
  }

  @Test
  public void acceptsAReleaseWithoutGitHubDigests() throws Exception {
    byte[] binary = zipapp("__version__ = '" + VERSION + "'\n");
    byte[] sums = sumsFile(sha256(binary), "yt-dlp");
    Checked checked = checked(binary, sums, null, null);
    assertNull(checked.release.binary.sha256);
    assertNull(checked.release.sums.sha256);
    assertArrayEquals(binary, YtDlpUpdate.verifiedZipapp(checked.release, checked.source));
  }

  @Test
  public void refusesAChecksumFileThatDisagreesWithItsDigest() throws Exception {
    byte[] binary = zipapp("__version__ = '" + VERSION + "'\n");
    byte[] sums = sumsFile(sha256(binary), "yt-dlp");
    Checked checked = checked(binary, sums, "sha256:" + sha256(binary), "sha256:" + repeat('0', 64));
    try {
      YtDlpUpdate.verifiedZipapp(checked.release, checked.source);
      fail("Accepted a checksum file that does not match its digest");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("does not match its GitHub digest"));
    }
    assertEquals(java.util.Collections.singletonList(SUMS_URL), checked.source.requested);
  }

  @Test
  public void refusesAZipappThatDisagreesWithTheChecksumFile() throws Exception {
    byte[] binary = zipapp("__version__ = '" + VERSION + "'\n");
    byte[] sums = sumsFile(repeat('a', 64), "yt-dlp");
    Checked checked = checked(binary, sums, null, null);
    try {
      YtDlpUpdate.verifiedZipapp(checked.release, checked.source);
      fail("Accepted a zipapp that does not match SHA2-256SUMS");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("SHA2-256SUMS lists"));
    }
  }

  @Test
  public void refusesAZipappThatDisagreesWithItsGitHubDigest() throws Exception {
    byte[] binary = zipapp("__version__ = '" + VERSION + "'\n");
    byte[] sums = sumsFile(sha256(binary), "yt-dlp");
    Checked checked = checked(binary, sums, "sha256:" + repeat('b', 64), "sha256:" + sha256(sums));
    try {
      YtDlpUpdate.verifiedZipapp(checked.release, checked.source);
      fail("Accepted a zipapp that does not match its GitHub digest");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("GitHub lists"));
    }
  }

  @Test
  public void refusesAZipappWhoseEmbeddedVersionDisagrees() throws Exception {
    byte[] binary = zipapp("__version__ = '2026.03.03'\n");
    byte[] sums = sumsFile(sha256(binary), "yt-dlp");
    Checked checked = checked(binary, sums, null, null);
    try {
      YtDlpUpdate.verifiedZipapp(checked.release, checked.source);
      fail("Accepted a zipapp tagged differently from version.py");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("version.py says 2026.03.03"));
    }
  }

  @Test
  public void refusesAnUnreadableZipapp() throws Exception {
    byte[] binary = new byte[] {1, 2, 3, 4};
    byte[] sums = sumsFile(sha256(binary), "yt-dlp");
    Checked checked = checked(binary, sums, null, null);
    try {
      YtDlpUpdate.verifiedZipapp(checked.release, checked.source);
      fail("Accepted bytes that are not a zipapp");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("Not a yt-dlp zipapp"));
    }
    byte[] missing = zipapp(null);
    Checked noVersion = checked(missing, sumsFile(sha256(missing), "yt-dlp"), null, null);
    try {
      YtDlpUpdate.verifiedZipapp(noVersion.release, noVersion.source);
      fail("Accepted a zipapp with no version.py");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("missing yt_dlp/version.py"));
    }
    byte[] unnamed = zipapp("CHANNEL = 'stable'\n");
    Checked noTag = checked(unnamed, sumsFile(sha256(unnamed), "yt-dlp"), null, null);
    try {
      YtDlpUpdate.verifiedZipapp(noTag.release, noTag.source);
      fail("Accepted a version.py with no release tag");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("no YYYY.MM.DD"));
    }
  }

  @Test
  public void readsAVersionFileAtTheSizeLimitAndRefusesOnePastIt() throws Exception {
    StringBuilder body = new StringBuilder("__version__ = '" + VERSION + "'\n");
    while (body.length() < 16384) body.append('#');
    assertEquals(16384, body.length());
    byte[] binary = zipapp(body.toString());
    Checked atLimit = checked(binary, sumsFile(sha256(binary), "yt-dlp"), null, null);
    assertArrayEquals(binary, YtDlpUpdate.verifiedZipapp(atLimit.release, atLimit.source));
    body.append('#');
    byte[] over = zipapp(body.toString());
    Checked past = checked(over, sumsFile(sha256(over), "yt-dlp"), null, null);
    try {
      YtDlpUpdate.verifiedZipapp(past.release, past.source);
      fail("Accepted a version.py past the read limit");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("larger than 16384"));
    }
  }

  @Test
  public void refusesOversizedOrEmptyDownloads() throws Exception {
    YtDlpUpdate.Release release =
        new YtDlpUpdate.Release(
            VERSION,
            new YtDlpUpdate.Asset(YtDlpUpdate.ASSET, BINARY_URL, null),
            new YtDlpUpdate.Asset(YtDlpUpdate.SUMS, SUMS_URL, null));
    byte[] exact = new byte[YtDlpUpdate.SUMS_CAP];
    try {
      YtDlpUpdate.verifiedZipapp(release, url -> exact);
      fail("Accepted a checksum file with no yt-dlp line");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("no checksum"));
    }
    byte[] over = new byte[YtDlpUpdate.SUMS_CAP + 1];
    try {
      YtDlpUpdate.verifiedZipapp(release, url -> over);
      fail("Accepted a checksum file past the cap");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("larger than"));
    }
    byte[] sums = sumsFile(repeat('c', 64), "yt-dlp");
    byte[] huge = new byte[YtDlpUpdate.BINARY_CAP + 1];
    try {
      YtDlpUpdate.verifiedZipapp(
          release,
          url -> SUMS_URL.equals(url) ? sums : huge);
      fail("Accepted a zipapp past the cap");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("yt-dlp is larger"));
    }
    try {
      YtDlpUpdate.verifiedZipapp(release, url -> null);
      fail("Accepted an empty download");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("empty"));
    }
    try {
      YtDlpUpdate.verifiedZipapp(null, url -> sums);
      fail("Accepted a missing release");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("required"));
    }
    try {
      YtDlpUpdate.verifiedZipapp(release, null);
      fail("Accepted a missing download source");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("required"));
    }
  }

  @Test
  public void parsesOnlyStableDateTaggedReleasesWithBothHttpsAssets() throws Exception {
    byte[] binary = zipapp("__version__ = '" + VERSION + "'\n");
    byte[] sums = sumsFile(sha256(binary), "yt-dlp");
    assertEquals(VERSION, checked(binary, sums, null, null).release.version);
    assertRejected(releaseJson("nightly", false, false, bothAssets(null, null)), "YYYY.MM.DD");
    assertRejected(releaseJson(VERSION, true, false, bothAssets(null, null)), "not a stable release");
    assertRejected(releaseJson(VERSION, false, true, bothAssets(null, null)), "not a stable release");
    JSONArray binaryOnly = new JSONArray();
    binaryOnly.put(asset(YtDlpUpdate.ASSET, BINARY_URL, null));
    assertRejected(releaseJson(VERSION, false, false, binaryOnly), "no yt-dlp or SHA2-256SUMS");
    JSONArray http = new JSONArray();
    http.put(asset(YtDlpUpdate.ASSET, "http://github.com/yt-dlp/yt-dlp/releases/download/yt-dlp", null));
    http.put(asset(YtDlpUpdate.SUMS, SUMS_URL, null));
    assertRejected(releaseJson(VERSION, false, false, http), "not an HTTPS download");
    assertRejected(releaseJson(VERSION, false, false, bothAssets("md5:abcd", null)), "digest is not SHA-256");
    assertRejected(releaseJson(VERSION, false, false, bothAssets("sha256:abcd", null)), "not a SHA-256 hex");
    assertRejected(null, "missing or larger");
    assertRejected("{", "unreadable");
    char[] atCap = new char[YtDlpUpdate.RELEASE_CAP];
    java.util.Arrays.fill(atCap, 'x');
    assertRejected(new String(atCap), "unreadable");
    char[] pastCap = new char[YtDlpUpdate.RELEASE_CAP + 1];
    java.util.Arrays.fill(pastCap, 'x');
    assertRejected(new String(pastCap), "larger than");
  }

  @Test
  public void installsOnlyWhenTheReleaseIsNewer() {
    assertTrue(YtDlpUpdate.needsInstall(null, VERSION));
    assertTrue(YtDlpUpdate.needsInstall("2026.03.03", VERSION));
    assertFalse(YtDlpUpdate.needsInstall(VERSION, VERSION));
    assertFalse(YtDlpUpdate.needsInstall("2026.03.05", VERSION));
  }

  @Test
  public void ignoresChecksumLinesForOtherFiles() throws Exception {
    try {
      YtDlpUpdate.publishedSha256("zz  yt-dlp\n" + repeat('1', 64) + "  yt-dlp extra\n" + repeat('2', 64) + "  yt-dlp.exe\n", "yt-dlp");
      fail("Accepted a checksum file that does not name yt-dlp");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("no checksum for yt-dlp"));
    }
  }

  private static void assertRejected(String json, String message) {
    try {
      YtDlpUpdate.stable(json);
      fail("Accepted release JSON: " + message);
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains(message));
    }
  }

  private static Checked checked(byte[] binary, byte[] sums, String binaryDigest, String sumsDigest)
      throws Exception {
    JSONArray assets = new JSONArray();
    assets.put("skip");
    JSONObject nameless = new JSONObject();
    nameless.put("name", "yt-dlp");
    assets.put(nameless);
    assets.put(asset("yt-dlp.tar.gz", "https://github.com/yt-dlp/yt-dlp/releases/download/2026.03.04/yt-dlp.tar.gz", null));
    assets.put(asset(YtDlpUpdate.ASSET, BINARY_URL, binaryDigest));
    assets.put(asset(YtDlpUpdate.SUMS, SUMS_URL, sumsDigest));
    YtDlpUpdate.Release release = YtDlpUpdate.stable(releaseJson(VERSION, false, false, assets));
    Map<String, byte[]> files = new LinkedHashMap<>();
    files.put(BINARY_URL, binary);
    files.put(SUMS_URL, sums);
    return new Checked(release, new MapBytes(files));
  }

  private static String releaseJson(String tag, boolean draft, boolean prerelease, JSONArray assets)
      throws JSONException {
    JSONObject root = new JSONObject();
    root.put("tag_name", tag);
    root.put("draft", draft);
    root.put("prerelease", prerelease);
    root.put("assets", assets);
    return root.toString();
  }

  private static JSONArray bothAssets(String binaryDigest, String sumsDigest) throws JSONException {
    JSONArray assets = new JSONArray();
    assets.put(asset(YtDlpUpdate.ASSET, BINARY_URL, binaryDigest));
    assets.put(asset(YtDlpUpdate.SUMS, SUMS_URL, sumsDigest));
    return assets;
  }

  private static JSONObject asset(String name, String url, String digest) throws JSONException {
    JSONObject item = new JSONObject();
    item.put("name", name);
    if (url != null) item.put("browser_download_url", url);
    if (digest != null) item.put("digest", digest);
    return item;
  }

  private static byte[] sumsFile(String hash, String name) {
    return (hash + "  " + name + "\n").getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] zipapp(String versionPy) throws IOException {
    ByteArrayOutputStream raw = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(raw)) {
      zip.putNextEntry(new ZipEntry("yt_dlp/__main__.py"));
      zip.closeEntry();
      if (versionPy != null) {
        zip.putNextEntry(new ZipEntry("yt_dlp/version.py"));
        zip.write(versionPy.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write("#!/usr/bin/env python3\n".getBytes(StandardCharsets.UTF_8));
    out.write(raw.toByteArray());
    return out.toByteArray();
  }

  private static String sha256(byte[] data) throws Exception {
    byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
    StringBuilder out = new StringBuilder(hash.length * 2);
    for (byte value : hash) out.append(String.format(Locale.ROOT, "%02x", value & 255));
    return out.toString();
  }

  private static String repeat(char value, int count) {
    StringBuilder out = new StringBuilder(count);
    for (int i = 0; i < count; i++) out.append(value);
    return out.toString();
  }

  private static final class Checked {
    final YtDlpUpdate.Release release;
    final MapBytes source;

    Checked(YtDlpUpdate.Release release, MapBytes source) {
      this.release = release;
      this.source = source;
    }
  }

  private static final class MapBytes implements YtDlpUpdate.Bytes {
    final Map<String, byte[]> files;
    final List<String> requested = new ArrayList<>();

    MapBytes(Map<String, byte[]> files) {
      this.files = files;
    }

    @Override
    public byte[] get(String url) {
      requested.add(url);
      return files.get(url);
    }
  }
}
