// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.still.saver.core.Post;
import org.still.saver.core.Variant;

/** Durable admission queue shared by Android downloads and scoped-cookie transfers. */
public final class DownloadStore {
  static final long COOKIE_TRANSFER_ID = -3;

  private DownloadStore() {}

  public static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("still", Context.MODE_PRIVATE);
  }

  private static DownloadManager manager(Context c) {
    return (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
  }

  public static final class Entry {
    final JSONObject data;
    public final String key, title, filename, mime, url, source, detail, author, thumb;
    public final long id;
    /** Wall-clock time the entry was added; 0 for entries recorded before 1.1. */
    public final long created;
    public int status, reason;
    public long bytes, total;
    public Uri uri;
    public boolean unavailable;
    /** Set when this inspection repaired the file; the caller persists the entry. */
    boolean repairedNow;

    Entry(JSONObject d) {
      data = d;
      key = d.optString("key");
      title = d.optString("title");
      filename = d.optString("filename");
      mime = d.optString("mime", "video/mp4");
      url = d.optString("url");
      source = d.optString("source");
      detail = d.optString("detail");
      author = d.optString("author");
      thumb = d.optString("thumb");
      created = d.optLong("created", 0);
      id = d.optLong("id", -1);
      status = id == -2 ? DownloadManager.STATUS_FAILED : DownloadManager.STATUS_PENDING;
      if (id == 0) {
        status = DownloadManager.STATUS_SUCCESSFUL;
        uri = Uri.parse(d.optString("uri"));
      } else if (id == COOKIE_TRANSFER_ID) {
        status = d.optInt("customStatus", DownloadManager.STATUS_PENDING);
        reason = d.optInt("reason");
        bytes = d.optLong("bytes");
        total = d.optLong("total", -1);
        if (status == DownloadManager.STATUS_SUCCESSFUL && d.has("uri"))
          uri = Uri.parse(d.optString("uri"));
      }
    }

    public boolean active() {
      return status == DownloadManager.STATUS_RUNNING
          || status == DownloadManager.STATUS_PENDING
          || status == DownloadManager.STATUS_PAUSED;
    }

    public String description(Context c) {
      if (unavailable) return c.getString(R.string.status_unavailable);
      if (!detail.isEmpty()) return detail;
      if (id == -1) return c.getString(R.string.status_queued);
      if (id == -2) return c.getString(R.string.status_not_started);
      if (status == DownloadManager.STATUS_SUCCESSFUL)
        return c.getString(
            mime.equals("image/gif") ? R.string.status_gif_saved : R.string.status_saved);
      if (status == DownloadManager.STATUS_RUNNING)
        return total > 0
            ? c.getString(R.string.status_downloading_percent, bytes * 100 / total)
            : c.getString(R.string.status_downloading);
      if (status == DownloadManager.STATUS_PENDING) return c.getString(R.string.status_starting);
      if (status == DownloadManager.STATUS_PAUSED)
        return c.getString(
            reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI
                ? R.string.status_waiting_wifi
                : id == COOKIE_TRANSFER_ID
                    ? R.string.status_paused_still
                    : R.string.status_paused_android);
      if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE)
        return c.getString(R.string.status_no_space);
      if (reason == DownloadManager.ERROR_FILE_ALREADY_EXISTS)
        return c.getString(R.string.status_file_exists);
      if (reason == DownloadManager.ERROR_DEVICE_NOT_FOUND)
        return c.getString(R.string.status_no_storage);
      return c.getString(R.string.status_failed);
    }
  }

  private static JSONArray read(Context c) {
    try {
      return new JSONArray(prefs(c).getString("downloads", "[]"));
    } catch (JSONException e) {
      throw new IllegalStateException(c.getString(R.string.library_read_error), e);
    }
  }

  private static void write(Context c, JSONArray a) {
    if (!prefs(c).edit().putString("downloads", a.toString()).commit())
      throw new IllegalStateException(c.getString(R.string.library_write_error));
  }

  private static Entry inspect(Context c, JSONObject d) {
    Entry e = new Entry(d);
    if (e.id > 0) {
      try (Cursor cursor = manager(c).query(new DownloadManager.Query().setFilterById(e.id))) {
        if (cursor != null && cursor.moveToFirst()) {
          e.status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
          e.reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
          e.bytes =
              cursor.getLong(
                  cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
          e.total =
              cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
          if (e.status == DownloadManager.STATUS_SUCCESSFUL)
            e.uri = manager(c).getUriForDownloadedFile(e.id);
          // Repair before anything reads the file: list() is how the UI first sees it finished.
          if (e.status == DownloadManager.STATUS_SUCCESSFUL
              && e.uri != null
              && "video/mp4".equals(e.mime)
              && !d.optBoolean("repaired")) {
            try {
              MediaRepair.repair(c, e.uri);
            } catch (IOException | SecurityException error) {
              // The file stays exactly as downloaded and can still be shared or opened elsewhere.
              Log.e("StillMediaRepair", "Could not repair " + e.uri, error);
            }
            try {
              d.put("repaired", true);
            } catch (JSONException impossible) {
              throw new IllegalStateException(impossible);
            }
            e.repairedNow = true;
          }
        } else {
          e.status = DownloadManager.STATUS_FAILED;
        }
      }
    }
    if (e.status == DownloadManager.STATUS_SUCCESSFUL && !readable(c, e.uri)) {
      e.status = DownloadManager.STATUS_FAILED;
      e.unavailable = true;
    }
    return e;
  }

  private static boolean readable(Context c, Uri uri) {
    if (uri == null) return false;
    try (ParcelFileDescriptor descriptor = c.getContentResolver().openFileDescriptor(uri, "r")) {
      return descriptor != null;
    } catch (IOException | SecurityException e) {
      return false;
    }
  }

  public static synchronized List<Entry> list(Context c) {
    JSONArray a = read(c);
    List<Entry> out = new ArrayList<>();
    boolean repaired = false;
    for (int i = a.length() - 1; i >= 0; i--) {
      JSONObject d = a.optJSONObject(i);
      if (d == null) continue;
      Entry e = inspect(c, d);
      repaired |= e.repairedNow;
      out.add(e);
    }
    if (repaired) write(c, a);
    return out;
  }

  /** The current state of one entry; throws if it was removed. */
  public static synchronized Entry find(Context c, String key) {
    JSONArray a = read(c);
    for (int i = 0; i < a.length(); i++) {
      JSONObject d = a.optJSONObject(i);
      if (d != null && key.equals(d.optString("key"))) {
        Entry e = inspect(c, d);
        if (e.repairedNow) write(c, a);
        return e;
      }
    }
    throw new IllegalStateException(c.getString(R.string.library_missing_error));
  }

  public static synchronized boolean enqueue(Context c, Post post, int index, Variant v) {
    for (Entry e : list(c)) if (e.url.equals(v.url) && e.active()) return false;
    try {
      JSONObject d = new JSONObject();
      String key = UUID.randomUUID().toString();
      String extension =
          v.mimeType.equals("image/png")
              ? ".png"
              : v.mimeType.equals("image/webp")
                  ? ".webp"
                  : v.mimeType.equals("image/gif")
                      ? ".gif"
                      : v.mimeType.startsWith("image/")
                          ? ".jpg"
                          : v.mimeType.equals("video/webm") ? ".webm" : ".mp4";
      String name =
          "still_"
              + post.id.replaceAll("[^A-Za-z0-9_-]", "_")
              + "_"
              + (index + 1)
              + "_"
              + key.substring(0, 8)
              + extension;
      String thumb = post.media.get(index).thumbnail;
      d.put("key", key)
          .put("created", System.currentTimeMillis())
          .put("author", post.author)
          .put("title", post.title)
          .put("url", v.url)
          .put("source", post.sourceUrl)
          .put("mime", v.mimeType)
          .put("filename", name)
          .put("id", -1)
          .put("scopedCookies", v.scopedCookies)
          .put("headers", new JSONObject(v.headers));
      if (v.audioUrl != null) d.put("audioUrl", v.audioUrl);
      if (thumb != null) d.put("thumb", thumb);
      JSONArray a = read(c);
      a.put(d);
      write(c, a);
      pump(c);
      return true;
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  public static synchronized void pump(Context c) {
    JSONArray a = read(c);
    int active = 0;
    for (int i = 0; i < a.length(); i++) {
      JSONObject d = a.optJSONObject(i);
      if (d != null
          && (d.optLong("id", -1) > 0 || d.optLong("id", -1) == COOKIE_TRANSFER_ID)
          && inspect(c, d).active()) active++;
    }
    int limit = prefs(c).getInt("parallel", 2);
    boolean changed = false;
    for (int i = 0; i < a.length() && active < limit; i++) {
      JSONObject d = a.optJSONObject(i);
      if (d == null || d.optLong("id", -1) != -1) continue;
      try {
        if (customTransfer(d)) {
          d.put("id", COOKIE_TRANSFER_ID)
              .put("token", UUID.randomUUID().toString())
              .put("customStatus", DownloadManager.STATUS_PENDING)
              .put("bytes", 0)
              .put("total", -1)
              .put("attempts", 0)
              .put("retryAt", 0)
              .put("detail", "")
              .put("baseFilename", d.optString("baseFilename", d.optString("filename")))
              .put("wifi", prefs(c).getBoolean("wifi", false))
              .put("folder", folder(c));
          active++;
          changed = true;
          write(c, a);
          continue;
        }
        String sub = folder(c);
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(d.getString("url")));
        r.setTitle(d.optString("filename"))
            .setDescription(c.getString(R.string.status_manager_description))
            .setMimeType(d.optString("mime"))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, sub + "/" + d.getString("filename"));
        boolean wifi = prefs(c).getBoolean("wifi", false);
        r.setAllowedNetworkTypes(
            wifi
                ? DownloadManager.Request.NETWORK_WIFI
                : DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);
        r.setAllowedOverMetered(!wifi);
        r.setAllowedOverRoaming(false);
        JSONObject headers = d.optJSONObject("headers");
        if (headers != null)
          for (String h : new String[] {"User-Agent", "Referer"})
            if (headers.has(h)) r.addRequestHeader(h, headers.getString(h));
        r.allowScanningByMediaScanner();
        long id = manager(c).enqueue(r);
        d.put("id", id).put("folder", sub);
        active++;
        changed = true;
        // Commit every admitted job so a process death cannot lose its manager ID.
        write(c, a);
      } catch (Exception e) {
        try {
          d.put("id", -2);
          changed = true;
        } catch (JSONException ignored) {
          /* fixed key */
        }
      }
    }
    if (changed) write(c, a);
    boolean cookieWork = false;
    for (int i = 0; i < a.length(); i++) {
      JSONObject d = a.optJSONObject(i);
      if (d != null
          && ((customTransfer(d) && d.optLong("id") == -1)
              || (d.optLong("id") == COOKIE_TRANSFER_ID && new Entry(d).active()))) {
        cookieWork = true;
        break;
      }
    }
    if (cookieWork && !CookieDownloadService.ensureStarted(c)) {
      for (int i = 0; i < a.length(); i++) {
        JSONObject d = a.optJSONObject(i);
        if (d != null && d.optLong("id") == COOKIE_TRANSFER_ID && new Entry(d).active()) {
          try {
            d.put("customStatus", DownloadManager.STATUS_PAUSED)
                .put("detail", c.getString(R.string.status_paused_open_still));
          } catch (JSONException e) {
            throw new IllegalStateException(e);
          }
        }
      }
      write(c, a);
    }
  }

  /** Scoped cookies and separate audio tracks need Still's own transfer, not DownloadManager. */
  private static boolean customTransfer(JSONObject d) {
    return !d.optString("scopedCookies").isEmpty() || !d.optString("audioUrl").isEmpty();
  }

  public static String folder(Context c) {
    return prefs(c).getString("folder", "Still");
  }

  public static synchronized void retry(Context c, Entry entry) {
    JSONArray a = read(c);
    for (int i = 0; i < a.length(); i++) {
      JSONObject d = a.optJSONObject(i);
      if (d != null && entry.key.equals(d.optString("key"))) {
        long currentId = d.optLong("id", -1);
        if (currentId == 0)
          throw new IllegalStateException(c.getString(R.string.library_retry_local_error));
        Entry current = inspect(c, d);
        if (current.status == DownloadManager.STATUS_SUCCESSFUL)
          throw new IllegalStateException(c.getString(R.string.library_retry_finished_error));
        if (currentId > 0) manager(c).remove(currentId);
        if (currentId == COOKIE_TRANSFER_ID) {
          CookieDownloadService.cancel(entry.key, d.optString("token"));
          deleteCookieOutput(c, d);
        }
        try {
          d.put("id", -1);
          d.remove("uri");
          d.remove("path");
          d.remove("detail");
        } catch (JSONException e) {
          throw new IllegalStateException(e);
        }
      }
    }
    write(c, a);
    pump(c);
  }

  public static synchronized void remove(Context c, Entry e, boolean deleteFile) {
    JSONArray a = read(c), remaining = new JSONArray();
    for (int i = 0; i < a.length(); i++) {
      JSONObject d = a.optJSONObject(i);
      if (d == null) continue;
      if (!e.key.equals(d.optString("key"))) {
        remaining.put(d);
        continue;
      }
      Entry current = inspect(c, d);
      if (!deleteFile && current.active())
        throw new IllegalStateException(c.getString(R.string.library_remove_active_error));
      // Removing history must never call the manager's file-deleting remove API.
      if (current.id > 0 && deleteFile) deleteManagerOutput(c, current, d);
      if (current.id == COOKIE_TRANSFER_ID && deleteFile) {
        CookieDownloadService.cancel(current.key, d.optString("token"));
        deleteCookieOutput(c, d);
      }
      if (current.id == 0 && deleteFile) {
        if (current.uri != null) deleteUri(c, current.uri);
        deleteByDisplayName(c, current.filename, current.mime);
      }
    }
    write(c, remaining);
    pump(c);
  }

  private static void deleteManagerOutput(Context c, Entry current, JSONObject d) {
    String mediaUri = null;
    String localUri = null;
    try (Cursor cursor = manager(c).query(new DownloadManager.Query().setFilterById(current.id))) {
      if (cursor != null && cursor.moveToFirst()) {
        int mediaCol = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIAPROVIDER_URI);
        if (mediaCol >= 0) mediaUri = cursor.getString(mediaCol);
        int localCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
        if (localCol >= 0) localUri = cursor.getString(localCol);
      }
    } catch (RuntimeException ignored) {
    }
    File localFile = null;
    if (localUri != null && localUri.startsWith("file://")) {
      String path = Uri.parse(localUri).getPath();
      if (path != null && !path.isEmpty()) localFile = new File(path);
    }
    String sub = d.optString("folder", folder(c));
    File fallbackFile =
        current.filename.isEmpty()
            ? null
            : new File(
                new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    sub),
                current.filename);
    // Delete owned MediaStore rows BEFORE manager.remove(), which clears owner_package_name
    // without removing the public Downloads file on Android 10+.
    if (mediaUri != null && !mediaUri.isEmpty()) deleteUri(c, Uri.parse(mediaUri));
    deleteByDisplayName(c, current.filename, current.mime);
    if (localFile != null && !localFile.getName().equals(current.filename))
      deleteByDisplayName(c, localFile.getName(), current.mime);
    if (current.uri != null) deleteUri(c, current.uri);
    if (localFile != null) localFile.delete();
    if (fallbackFile != null) fallbackFile.delete();
    try {
      manager(c).remove(current.id);
    } catch (RuntimeException ignored) {
    }
    if (localFile != null) localFile.delete();
    if (fallbackFile != null) fallbackFile.delete();
  }

  private static void deleteCookieOutput(Context c, JSONObject d) {
    String uri = d.optString("uri");
    if (!uri.isEmpty()) deleteUri(c, Uri.parse(uri));
    deleteByDisplayName(c, d.optString("filename"), d.optString("mime"));
    String path = d.optString("path");
    if (!path.isEmpty()) new File(path).delete();
  }

  private static void deleteUri(Context c, Uri uri) {
    if (uri == null) return;
    try {
      if ("content".equals(uri.getScheme())) {
        c.getContentResolver().delete(uri, null, null);
      } else if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
        new File(uri.getPath()).delete();
      }
    } catch (RuntimeException ignored) {
    }
  }

  private static void deleteByDisplayName(Context c, String filename, String mime) {
    if (filename == null || filename.isEmpty()) return;
    String where = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
    String[] args = new String[] {filename};
    if (Build.VERSION.SDK_INT >= 29) {
      try {
        c.getContentResolver().delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, where, args);
      } catch (RuntimeException ignored) {
      }
    }
    Uri mediaCollection =
        mime != null && mime.startsWith("image/")
            ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    try {
      c.getContentResolver().delete(mediaCollection, where, args);
    } catch (RuntimeException ignored) {
    }
  }

  static final class CookieJob {
    final Entry entry;
    final String token, cookies, audioUrl, folder;
    final JSONObject headers;
    final boolean wifi;
    final long retryAt;
    final int attempts;

    CookieJob(JSONObject d) {
      entry = new Entry(d);
      token = d.optString("token");
      cookies = d.optString("scopedCookies");
      audioUrl = d.optString("audioUrl");
      folder = d.optString("folder", "Still");
      headers = d.optJSONObject("headers");
      wifi = d.optBoolean("wifi");
      retryAt = d.optLong("retryAt");
      attempts = d.optInt("attempts");
    }
  }

  static synchronized List<CookieJob> cookieJobs(Context c) {
    JSONArray a = read(c);
    List<CookieJob> out = new ArrayList<>();
    for (int i = 0; i < a.length(); i++) {
      JSONObject d = a.optJSONObject(i);
      if (d != null && d.optLong("id") == COOKIE_TRANSFER_ID && new Entry(d).active())
        out.add(new CookieJob(d));
    }
    return out;
  }

  static synchronized boolean hasCookieWork(Context c) {
    for (Entry e : list(c))
      if (e.active() && (e.id == COOKIE_TRANSFER_ID || customTransfer(e.data)))
        return true;
    return false;
  }

  private static JSONObject cookieRow(JSONArray a, String key, String token) {
    for (int i = 0; i < a.length(); i++) {
      JSONObject d = a.optJSONObject(i);
      if (d != null
          && key.equals(d.optString("key"))
          && d.optLong("id") == COOKIE_TRANSFER_ID
          && token.equals(d.optString("token"))
          && new Entry(d).active()) return d;
    }
    return null;
  }

  static synchronized boolean cookieCurrent(Context c, String key, String token) {
    return cookieRow(read(c), key, token) != null;
  }

  static synchronized boolean updateCookie(
      Context c,
      String key,
      String token,
      int status,
      int reason,
      long bytes,
      long total,
      String detail,
      long retryAt,
      int attempts) {
    JSONArray a = read(c);
    JSONObject d = cookieRow(a, key, token);
    if (d == null) return false;
    try {
      d.put("customStatus", status)
          .put("reason", reason)
          .put("bytes", bytes)
          .put("total", total)
          .put("detail", detail)
          .put("retryAt", retryAt)
          .put("attempts", attempts);
      write(c, a);
      return true;
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  static synchronized boolean stageCookieOutput(
      Context c, String key, String token, Uri uri, String legacyPath) {
    JSONArray a = read(c);
    JSONObject d = cookieRow(a, key, token);
    if (d == null) return false;
    try {
      d.put("uri", uri.toString()).put("path", legacyPath == null ? "" : legacyPath);
      if (legacyPath != null) d.put("filename", new File(legacyPath).getName());
      write(c, a);
      return true;
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  static synchronized boolean finishCookie(
      Context c, String key, String token, Uri uri, long bytes) {
    JSONArray a = read(c);
    JSONObject d = cookieRow(a, key, token);
    if (d == null) return false;
    try {
      d.put("uri", uri.toString())
          .put("customStatus", DownloadManager.STATUS_SUCCESSFUL)
          .put("bytes", bytes)
          .put("total", bytes)
          .put("detail", "")
          .put("retryAt", 0);
      write(c, a);
      return true;
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Process restart resumes custom jobs from byte zero, never publishing an old partial file. */
  static synchronized void recoverCookieJobs(Context c) {
    JSONArray a = read(c);
    boolean changed = false;
    for (int i = 0; i < a.length(); i++) {
      JSONObject d = a.optJSONObject(i);
      if (d == null || d.optLong("id") != COOKIE_TRANSFER_ID || !new Entry(d).active()) continue;
      try {
        deleteCookieOutput(c, d);
        d.remove("uri");
        d.remove("path");
        d.put("token", UUID.randomUUID().toString())
            .put("customStatus", DownloadManager.STATUS_PENDING)
            .put("bytes", 0)
            .put("total", -1)
            .put("detail", "")
            .put("retryAt", 0);
        changed = true;
      } catch (Exception e) {
        throw new IllegalStateException("Could not recover the interrupted download", e);
      }
    }
    if (changed) write(c, a);
  }

  public static synchronized void recordLocal(
      Context c, Uri uri, String title, String filename, String mime) {
    try {
      JSONArray a = read(c);
      a.put(
          new JSONObject()
              .put("key", UUID.randomUUID().toString())
              .put("created", System.currentTimeMillis())
              .put("id", 0)
              .put("uri", uri.toString())
              .put("title", title)
              .put("filename", filename)
              .put("mime", mime));
      write(c, a);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }
}
