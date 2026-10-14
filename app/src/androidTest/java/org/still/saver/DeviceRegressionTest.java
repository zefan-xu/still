// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import static org.junit.Assert.*;

import android.app.DownloadManager;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.still.saver.core.ErrorCode;
import org.still.saver.core.Media;
import org.still.saver.core.Platforms;
import org.still.saver.core.Post;
import org.still.saver.core.Variant;

/** Offline regressions for Android-owned state and activity recreation. */
@RunWith(AndroidJUnit4.class)
public final class DeviceRegressionTest {
  private Context context() {
    return InstrumentationRegistry.getInstrumentation().getTargetContext();
  }

  @Test
  public void everyCoreCodeHasItsNamedString() {
    Resources res = context().getResources();
    for (ErrorCode code : ErrorCode.values())
      assertEquals(
          "error_" + code.name().toLowerCase(Locale.ROOT),
          res.getResourceEntryName(CoreText.message(code)));
    for (Platforms.Kind kind : Platforms.Kind.values())
      assertEquals(
          "home_match_" + kind.name().toLowerCase(Locale.ROOT),
          res.getResourceEntryName(CoreText.kind(kind)));
    for (Platforms.Platform platform : Platforms.ALL)
      assertEquals(
          "links_" + platform.name.toLowerCase(Locale.ROOT) + "_summary",
          res.getResourceEntryName(CoreText.summary(platform)));
  }

  @Test
  public void deletedLocalFileIsUnavailableAndCannotBecomeNetworkRetry() throws Exception {
    Context c = context();
    File file = File.createTempFile("still-library-test", ".gif", c.getCacheDir());
    try (FileOutputStream stream = new FileOutputStream(file)) {
      stream.write(new byte[] {'G', 'I', 'F', '8', '9', 'a'});
    }
    DownloadStore.recordLocal(c, Uri.fromFile(file), "Local test", file.getName(), "image/gif");
    DownloadStore.Entry saved = DownloadStore.list(c).get(0);
    assertEquals(DownloadManager.STATUS_SUCCESSFUL, saved.status);
    assertTrue(file.delete());
    DownloadStore.Entry missing = DownloadStore.list(c).get(0);
    try {
      assertEquals(DownloadManager.STATUS_FAILED, missing.status);
      assertTrue(missing.unavailable);
      assertTrue(missing.description(c).contains("unavailable"));
      try {
        DownloadStore.retry(c, missing);
        fail("A local export has no network URL to retry");
      } catch (IllegalStateException expected) {
        assertEquals(0, DownloadStore.list(c).get(0).id);
      }
    } finally {
      DownloadStore.remove(c, saved, false);
      file.delete();
    }
  }

  @Test
  public void cookieProgressAndCompletedStateSurviveReconstruction() throws Exception {
    Context c = context();
    try (LibraryFixture library = new LibraryFixture(c);
        SavedFixture file = cacheFixture(c)) {
      JSONObject row = cookieRow(DownloadManager.STATUS_RUNNING);
      library.seed(row.put("bytes", 6).put("total", 12));
      DownloadStore.Entry running = DownloadStore.list(c).get(0);
      assertEquals(DownloadStore.COOKIE_TRANSFER_ID, running.id);
      assertTrue(running.active());
      assertEquals(6, running.bytes);
      assertEquals(12, running.total);
      assertTrue(running.description(c).contains("50%"));

      assertTrue(
          DownloadStore.updateCookie(
              c,
              running.key,
              row.getString("token"),
              DownloadManager.STATUS_PAUSED,
              DownloadManager.PAUSED_QUEUED_FOR_WIFI,
              6,
              12,
              "",
              12345,
              2));
      DownloadStore.Entry paused = DownloadStore.list(c).get(0);
      assertEquals(DownloadManager.STATUS_PAUSED, paused.status);
      assertTrue(paused.active());
      assertEquals("Waiting for Wi-Fi", paused.description(c));
      assertEquals(12345, paused.data.getLong("retryAt"));
      assertEquals(2, paused.data.getInt("attempts"));

      assertTrue(DownloadStore.finishCookie(c, running.key, row.getString("token"), file.uri, 12));
      DownloadStore.Entry saved = DownloadStore.list(c).get(0);
      assertEquals(DownloadManager.STATUS_SUCCESSFUL, saved.status);
      assertFalse(saved.active());
      assertEquals(12, saved.bytes);
      assertEquals(12, saved.total);
      assertEquals(file.uri, saved.uri);
      file.delete();
      DownloadStore.Entry missing = DownloadStore.list(c).get(0);
      assertEquals(DownloadManager.STATUS_FAILED, missing.status);
      assertTrue(missing.unavailable);
    }
  }

  @Test
  public void staleCookieCallbacksCannotOverwriteReplacementOrCompletedAttempt() throws Exception {
    Context c = context();
    try (LibraryFixture library = new LibraryFixture(c);
        SavedFixture file = cacheFixture(c)) {
      JSONObject row = cookieRow(DownloadManager.STATUS_RUNNING);
      String key = row.getString("key"), token = row.getString("token");
      String oldToken = UUID.randomUUID().toString();
      library.seed(row);
      String before = DownloadStore.prefs(c).getString("downloads", "");
      assertFalse(DownloadStore.cookieCurrent(c, key, oldToken));
      assertTrue(DownloadStore.cookieCurrent(c, key, token));
      assertFalse(
          DownloadStore.updateCookie(
              c, key, oldToken, DownloadManager.STATUS_FAILED, 0, 99, 99, "Old error", 0, 9));
      assertFalse(DownloadStore.stageCookieOutput(c, key, oldToken, file.uri, file.path()));
      assertFalse(DownloadStore.finishCookie(c, key, oldToken, file.uri, 99));
      assertEquals(before, DownloadStore.prefs(c).getString("downloads", ""));

      assertTrue(
          DownloadStore.updateCookie(
              c, key, token, DownloadManager.STATUS_RUNNING, 0, 6, 12, "", 0, 0));
      assertTrue(DownloadStore.stageCookieOutput(c, key, token, file.uri, file.path()));
      assertTrue(DownloadStore.finishCookie(c, key, token, file.uri, 12));
      before = DownloadStore.prefs(c).getString("downloads", "");
      assertFalse(DownloadStore.cookieCurrent(c, key, token));
      assertFalse(
          DownloadStore.updateCookie(
              c, key, token, DownloadManager.STATUS_FAILED, 0, 6, 12, "Late error", 0, 1));
      assertFalse(DownloadStore.stageCookieOutput(c, key, token, file.uri, file.path()));
      assertFalse(DownloadStore.finishCookie(c, key, token, file.uri, 6));
      assertEquals(before, DownloadStore.prefs(c).getString("downloads", ""));
      DownloadStore.Entry saved = DownloadStore.list(c).get(0);
      assertEquals(DownloadManager.STATUS_SUCCESSFUL, saved.status);
      assertEquals(12, saved.bytes);
      assertEquals(file.uri, saved.uri);
    }
  }

  @Test
  public void cookieHistoryRemovalRetainsFileAndRejectsLateCompletion() throws Exception {
    Context c = context();
    try (LibraryFixture library = new LibraryFixture(c);
        SavedFixture file = cacheFixture(c)) {
      JSONObject row = completedCookieRow(file);
      library.seed(row);
      DownloadStore.Entry saved = DownloadStore.list(c).get(0);
      DownloadStore.remove(c, saved, false);
      assertTrue(DownloadStore.list(c).isEmpty());
      file.assertReadable();
      assertFalse(DownloadStore.finishCookie(c, saved.key, row.getString("token"), file.uri, 12));
      assertTrue(DownloadStore.list(c).isEmpty());
      file.assertReadable();
    }
  }

  @Test
  public void cookieDeleteRemovesOwnedMediaStoreFileAndHistory() throws Exception {
    Assume.assumeTrue(Build.VERSION.SDK_INT >= 29);
    Context c = context();
    try (LibraryFixture library = new LibraryFixture(c);
        SavedFixture file = mediaStoreFixture(c);
        SavedFixture managerFile = mediaStoreFixture(c)) {
      JSONObject row = completedCookieRow(file);
      library.seed(row);
      DownloadStore.Entry saved = DownloadStore.list(c).get(0);
      DownloadStore.remove(c, saved, true);
      assertTrue(DownloadStore.list(c).isEmpty());
      file.assertUnavailable();
      assertFalse(DownloadStore.finishCookie(c, saved.key, row.getString("token"), file.uri, 12));
      assertTrue(DownloadStore.list(c).isEmpty());

      JSONObject managerRow =
          new JSONObject()
              .put("key", UUID.randomUUID().toString())
              .put("id", 424242L)
              .put("title", "Manager download")
              .put("filename", managerFile.name)
              .put("folder", "StillTests")
              .put("mime", "video/mp4")
              .put("url", "https://cdn.example.com/manager.mp4");
      library.seed(managerRow);
      DownloadStore.Entry managerSaved = DownloadStore.list(c).get(0);
      DownloadStore.remove(c, managerSaved, false);
      assertTrue(DownloadStore.list(c).isEmpty());
      managerFile.assertReadable();

      library.seed(managerRow);
      managerSaved = DownloadStore.list(c).get(0);
      DownloadStore.remove(c, managerSaved, true);
      assertTrue(DownloadStore.list(c).isEmpty());
      managerFile.assertUnavailable();
    }
  }

  @Test
  public void staleCookieRetryKeepsNowCompletedFile() throws Exception {
    Context c = context();
    try (LibraryFixture library = new LibraryFixture(c);
        SavedFixture file = cacheFixture(c)) {
      JSONObject row = cookieRow(DownloadManager.STATUS_FAILED);
      library.seed(row);
      DownloadStore.Entry earlierFailure = DownloadStore.list(c).get(0);
      library.seed(
          row.put("customStatus", DownloadManager.STATUS_SUCCESSFUL)
              .put("uri", file.uri.toString())
              .put("path", file.path())
              .put("bytes", 12)
              .put("total", 12));
      String before = DownloadStore.prefs(c).getString("downloads", "");
      try {
        DownloadStore.retry(c, earlierFailure);
        fail("Retry must preserve a file that completed after the card was rendered");
      } catch (IllegalStateException expected) {
        assertEquals(before, DownloadStore.prefs(c).getString("downloads", ""));
        assertEquals(DownloadManager.STATUS_SUCCESSFUL, DownloadStore.list(c).get(0).status);
        file.assertReadable();
      }
    }
  }

  @Test
  public void cookieRecoveryRotatesAttemptAndRemovesInterruptedOutput() throws Exception {
    Context c = context();
    try (LibraryFixture library = new LibraryFixture(c);
        SavedFixture file = cacheFixture(c)) {
      JSONObject row = cookieRow(DownloadManager.STATUS_RUNNING);
      String key = row.getString("key"), oldToken = row.getString("token");
      library.seed(
          row.put("bytes", 6)
              .put("total", 12)
              .put("uri", file.uri.toString())
              .put("path", file.path()));
      DownloadStore.recoverCookieJobs(c);
      DownloadStore.Entry recovered = DownloadStore.list(c).get(0);
      assertEquals(DownloadManager.STATUS_PENDING, recovered.status);
      assertEquals(0, recovered.bytes);
      assertEquals(-1, recovered.total);
      assertFalse(recovered.data.has("uri"));
      assertFalse(recovered.data.has("path"));
      assertNotEquals(oldToken, recovered.data.getString("token"));
      assertFalse(DownloadStore.cookieCurrent(c, key, oldToken));
      assertFalse(DownloadStore.finishCookie(c, key, oldToken, file.uri, 12));
      file.assertUnavailable();
    }
  }

  @Test
  public void recreationKeepsResolvedPostAndChosenQualityWithoutRepeatingTheLookup()
      throws Exception {
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    MainActivity original =
        (MainActivity)
            instrumentation.startActivitySync(
                new Intent(context(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    Variant best =
        new Variant("https://video.twimg.com/best.mp4", "720p", "video/mp4", 1000, 1280, 720);
    Variant smaller =
        new Variant("https://video.twimg.com/small.mp4", "360p", "video/mp4", 300, 640, 360);
    Post post =
        new Post(
            "42",
            "Rotation test",
            "Example",
            "https://x.com/i/status/42",
            Collections.singletonList(new Media("video", Arrays.asList(best, smaller))));
    AtomicReference<SaveSession> session = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          session.set(original.session());
          original.session().showResolvedForTest(post);
          original.session().choose(0, smaller);
        });
    instrumentation.waitForIdleSync();
    Instrumentation.ActivityMonitor monitor =
        instrumentation.addMonitor(MainActivity.class.getName(), null, false);
    MainActivity recreated = null;
    try {
      instrumentation.runOnMainSync(original::recreate);
      recreated = (MainActivity) instrumentation.waitForMonitorWithTimeout(monitor, 10000);
      assertNotNull("Activity was not recreated", recreated);
      instrumentation.waitForIdleSync();
      MainActivity current = recreated;
      AtomicReference<String> label = new AtomicReference<>();
      instrumentation.runOnMainSync(
          () -> {
            assertSame(session.get(), current.session());
            assertSame(post, current.session().post());
            assertEquals(SaveSession.Phase.READY, current.session().phase());
            assertEquals(smaller.url, current.session().chosen(0).url);
            label.set(ResultsSheet.saveLabel(current, current.session()));
          });
      assertEquals("Save video", label.get());
    } finally {
      instrumentation.removeMonitor(monitor);
      MainActivity remaining = recreated == null ? original : recreated;
      instrumentation.runOnMainSync(remaining::finish);
    }
  }

  private static JSONObject cookieRow(int status) throws Exception {
    return new JSONObject()
        .put("key", UUID.randomUUID().toString())
        .put("token", UUID.randomUUID().toString())
        .put("id", DownloadStore.COOKIE_TRANSFER_ID)
        .put("customStatus", status)
        .put("title", "Offline cookie regression")
        .put("filename", "offline.mp4")
        .put("mime", "video/mp4")
        .put("url", "https://cdn.example.com/offline.mp4")
        .put("source", "https://www.tiktok.com/@example/video/42")
        .put("scopedCookies", "anonymous=fixture; Domain=cdn.example.com; Path=/")
        .put("bytes", 0)
        .put("total", -1)
        .put("detail", "");
  }

  private static JSONObject completedCookieRow(SavedFixture file) throws Exception {
    return cookieRow(DownloadManager.STATUS_SUCCESSFUL)
        .put("uri", file.uri.toString())
        .put("path", file.path())
        .put("bytes", 12)
        .put("total", 12);
  }

  private static SavedFixture cacheFixture(Context c) throws Exception {
    File file = File.createTempFile("still-cookie-test-", ".mp4", c.getCacheDir());
    try (OutputStream output = new FileOutputStream(file)) {
      output.write(fixtureBytes());
    }
    return new SavedFixture(c, Uri.fromFile(file), file, file.getName());
  }

  private static SavedFixture mediaStoreFixture(Context c) throws Exception {
    String name = "still_cookie_test_" + UUID.randomUUID() + ".mp4";
    ContentValues values = new ContentValues();
    values.put(MediaStore.Downloads.DISPLAY_NAME, name);
    values.put(MediaStore.Downloads.MIME_TYPE, "video/mp4");
    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/StillTests");
    Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
    assertNotNull(uri);
    try (OutputStream output = c.getContentResolver().openOutputStream(uri, "w")) {
      assertNotNull(output);
      output.write(fixtureBytes());
    } catch (Exception error) {
      c.getContentResolver().delete(uri, null, null);
      throw error;
    }
    return new SavedFixture(c, uri, null, name);
  }

  private static byte[] fixtureBytes() {
    return new byte[] {0, 0, 0, 12, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'};
  }

  private static final class LibraryFixture implements AutoCloseable {
    final Context context;
    final String original;

    LibraryFixture(Context context) {
      this.context = context;
      original = DownloadStore.prefs(context).getString("downloads", null);
    }

    void seed(JSONObject row) {
      synchronized (DownloadStore.class) {
        assertTrue(
            DownloadStore.prefs(context)
                .edit()
                .putString("downloads", new JSONArray().put(row).toString())
                .commit());
      }
    }

    @Override
    public void close() {
      synchronized (DownloadStore.class) {
        assertTrue(DownloadStore.prefs(context).edit().putString("downloads", original).commit());
      }
    }
  }

  private static final class SavedFixture implements AutoCloseable {
    final Context context;
    final Uri uri;
    final File file;
    final String name;

    SavedFixture(Context context, Uri uri, File file, String name) {
      this.context = context;
      this.uri = uri;
      this.file = file;
      this.name = name;
    }

    String path() {
      return file == null ? "" : file.getAbsolutePath();
    }

    void assertReadable() throws Exception {
      try (ParcelFileDescriptor descriptor =
          context.getContentResolver().openFileDescriptor(uri, "r")) {
        assertNotNull(descriptor);
      }
    }

    void assertUnavailable() throws Exception {
      try (ParcelFileDescriptor descriptor =
          context.getContentResolver().openFileDescriptor(uri, "r")) {
        assertNull("The deleted fixture remains readable", descriptor);
      } catch (IOException expected) {
        // Missing fixture is the expected result.
      }
    }

    void delete() {
      if (file != null) file.delete();
      else context.getContentResolver().delete(uri, null, null);
    }

    @Override
    public void close() {
      delete();
    }
  }

  private static Field field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

}
