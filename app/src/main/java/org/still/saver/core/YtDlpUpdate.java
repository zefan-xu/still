// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Checks a yt-dlp stable release before anything is installed. The checksum file is SHA2-256SUMS
 * from the same GitHub release; the zipapp's embedded version must equal the tag. Callers write
 * the returned bytes only after this returns.
 */
public final class YtDlpUpdate {
  public static final String LATEST = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest";
  public static final String ASSET = "yt-dlp";
  public static final String SUMS = "SHA2-256SUMS";
  public static final int RELEASE_CAP = 2 * 1024 * 1024;
  public static final int SUMS_CAP = 64 * 1024;
  public static final int BINARY_CAP = 16 * 1024 * 1024;
  private static final int VERSION_PY_LIMIT = 16384;
  private static final Pattern TAG = Pattern.compile("\\d{4}\\.\\d{2}\\.\\d{2}");
  private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]{64}");
  private static final Pattern VERSION_PY =
      Pattern.compile("(?m)^__version__\\s*=\\s*['\"]([0-9]{4}\\.[0-9]{2}\\.[0-9]{2})['\"]");

  /** Supplies the bytes at an HTTPS URL. Implementations must return a new array or null. */
  public interface Bytes {
    byte[] get(String url) throws IOException;
  }

  /** One release asset. {@code sha256} is the GitHub asset digest without the {@code sha256:} prefix. */
  public static final class Asset {
    public final String name;
    public final String url;
    public final String sha256;

    public Asset(String name, String url, String sha256) {
      this.name = name;
      this.url = url;
      this.sha256 = sha256;
    }
  }

  /** A stable yt-dlp tag plus the zipapp and checksum assets. */
  public static final class Release {
    public final String version;
    public final Asset binary;
    public final Asset sums;

    public Release(String version, Asset binary, Asset sums) {
      this.version = version;
      this.binary = binary;
      this.sums = sums;
    }
  }

  private YtDlpUpdate() {}

  /** True when no readable install exists, or it is older than the release tag. */
  public static boolean needsInstall(String installed, String release) {
    return installed == null || installed.compareTo(release) < 0;
  }

  /** Parses a GitHub release JSON object into the stable zipapp release, or throws. */
  public static Release stable(String json) throws IOException {
    if (json == null || json.length() > RELEASE_CAP) {
      throw new IOException("yt-dlp release JSON is missing or larger than " + RELEASE_CAP + " bytes");
    }
    JSONObject root;
    try {
      root = new JSONObject(json);
    } catch (JSONException ignored) {
      throw new IOException("yt-dlp release JSON is unreadable");
    }
    String version = root.optString("tag_name", "");
    if (root.optBoolean("draft") || root.optBoolean("prerelease")) {
      throw new IOException("yt-dlp " + version + " is not a stable release");
    }
    if (!TAG.matcher(version).matches()) {
      throw new IOException("yt-dlp tag is not a YYYY.MM.DD version");
    }
    Asset binary = null;
    Asset sums = null;
    JSONArray assets = root.optJSONArray("assets");
    if (assets != null) {
      for (int i = 0; i < assets.length(); i++) {
        JSONObject item = assets.optJSONObject(i);
        if (item == null) continue;
        Asset asset = asset(item);
        if (asset == null) continue;
        if (ASSET.equals(asset.name)) binary = asset;
        else if (SUMS.equals(asset.name)) sums = asset;
      }
    }
    if (binary == null || sums == null) {
      throw new IOException("yt-dlp " + version + " has no yt-dlp or SHA2-256SUMS asset");
    }
    return new Release(version, binary, sums);
  }

  /**
   * Downloads the checksum file and the zipapp, and returns the zipapp bytes only when both match
   * the published checksum and the tag. Throws without returning a body the caller should store.
   */
  public static byte[] verifiedZipapp(Release release, Bytes source) throws IOException {
    if (release == null || source == null) {
      throw new IOException("A release and a download source are required");
    }
    byte[] sums = capped(source.get(release.sums.url), SUMS_CAP, SUMS);
    if (release.sums.sha256 != null && !release.sums.sha256.equals(sha256(sums))) {
      throw new IOException(SUMS + " does not match its GitHub digest");
    }
    String expected = publishedSha256(new String(sums, StandardCharsets.UTF_8), ASSET);
    byte[] binary = capped(source.get(release.binary.url), BINARY_CAP, ASSET);
    String actual = sha256(binary);
    if (!expected.equals(actual)) {
      throw new IOException(
          "Downloaded yt-dlp has SHA-256 " + actual + ", but SHA2-256SUMS lists " + expected);
    }
    if (release.binary.sha256 != null && !release.binary.sha256.equals(actual)) {
      throw new IOException(
          "GitHub lists yt-dlp as sha256:" + release.binary.sha256 + ", but the download is " + actual);
    }
    String embedded = zipappVersion(binary);
    if (!release.version.equals(embedded)) {
      throw new IOException("yt_dlp/version.py says " + embedded + ", not " + release.version);
    }
    return binary;
  }

  static String publishedSha256(String sums, String name) throws IOException {
    for (String raw : sums.split("\n")) {
      String[] parts = raw.trim().split("\\s+");
      if (parts.length != 2 || !HEX.matcher(parts[0]).matches()) continue;
      String file = parts[1].startsWith("*") ? parts[1].substring(1) : parts[1];
      if (name.equals(file)) return parts[0].toLowerCase(Locale.ROOT);
    }
    throw new IOException(SUMS + " has no checksum for " + name);
  }

  private static Asset asset(JSONObject item) throws IOException {
    String name = item.optString("name", "");
    String url = item.optString("browser_download_url", "");
    if (name.isEmpty() || url.isEmpty()) return null;
    if (!url.startsWith("https://")) {
      throw new IOException("yt-dlp asset " + name + " is not an HTTPS download");
    }
    String digest = item.optString("digest", "");
    String sha256 = null;
    if (!digest.isEmpty()) {
      if (!digest.startsWith("sha256:")) {
        throw new IOException("yt-dlp asset " + name + " digest is not SHA-256");
      }
      String hex = digest.substring("sha256:".length());
      if (!HEX.matcher(hex).matches()) {
        throw new IOException("yt-dlp asset " + name + " digest is not a SHA-256 hex string");
      }
      sha256 = hex.toLowerCase(Locale.ROOT);
    }
    return new Asset(name, url, sha256);
  }

  private static byte[] capped(byte[] data, int cap, String name) throws IOException {
    if (data == null) throw new IOException(name + " download was empty");
    if (data.length > cap) throw new IOException(name + " is larger than " + cap + " bytes");
    return data;
  }

  private static String sha256(byte[] data) throws IOException {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
      StringBuilder out = new StringBuilder(hash.length * 2);
      for (byte value : hash) out.append(String.format(Locale.ROOT, "%02x", value & 255));
      return out.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IOException("SHA-256 is unavailable", e);
    }
  }

  private static String zipappVersion(byte[] data) throws IOException {
    File temporary = File.createTempFile("still-ytdlp-version-", ".zip");
    try {
      try (FileOutputStream out = new FileOutputStream(temporary)) {
        out.write(data);
      }
      try (ZipFile zip = new ZipFile(temporary)) {
        ZipEntry entry = zip.getEntry("yt_dlp/version.py");
        if (entry == null) throw new IOException("Not a yt-dlp zipapp: missing yt_dlp/version.py");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = zip.getInputStream(entry)) {
          byte[] buffer = new byte[1024];
          int count;
          while ((count = input.read(buffer)) != -1) {
            if (count > VERSION_PY_LIMIT - bytes.size()) {
              throw new IOException("yt_dlp/version.py is larger than " + VERSION_PY_LIMIT + " bytes");
            }
            bytes.write(buffer, 0, count);
          }
        }
        Matcher match =
            VERSION_PY.matcher(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        if (!match.find()) throw new IOException("yt_dlp/version.py has no YYYY.MM.DD __version__");
        return match.group(1);
      }
    } catch (ZipException e) {
      throw new IOException("Not a yt-dlp zipapp", e);
    } finally {
      temporary.delete();
    }
  }
}
