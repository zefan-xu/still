// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.still.saver.core.Post;
import org.still.saver.gif.GifService;

/** Fixture-owned state/files only. Close activities before restoring application preferences. */
final class TestFixtures implements AutoCloseable {
  final Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
  final SharedPreferences prefs = DownloadStore.prefs(app);
  final Map<String, ?> original = prefs.getAll();
  final String folder = "StillTests_" + UUID.randomUUID().toString().substring(0, 8);
  final List<Uri> files = new ArrayList<>();
  final Set<String> originalGifCache = gifCacheNames();

  TestFixtures() {
    this(0);
  }

  TestFixtures(int parallel) {
    org.junit.Assume.assumeTrue(
        "Local workflow fixtures require API 29+", Build.VERSION.SDK_INT >= 29);
    assertFalse("An export is already active; use an isolated test device", GifService.isRunning());
    try {
      assertTrue(
          prefs
              .edit()
              .clear()
              .putString("downloads", "[]")
              .putString("folder", folder)
              .putInt("parallel", parallel)
              .putBoolean("auto", false)
              .putBoolean(OnboardingActivity.SEEN, true)
              .commit());
      app.getContentResolver().call(FixtureControlProvider.BASE, "reset", null, null);
      app.getContentResolver().call(FixtureControlProvider.BASE, "grant", null, null);
    } catch (RuntimeException | AssertionError error) {
      restorePreferences();
      throw error;
    }
  }

  Uri video(String title) throws Exception {
    return media("sample.mp4", title, title + ".mp4", "video/mp4");
  }

  /** Publishes a test-APK asset into the fixture folder and records it in the library. */
  Uri media(String asset, String title, String filename, String mime) throws Exception {
    ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
    values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
    if (Build.VERSION.SDK_INT >= 29)
      values.put(
          MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folder);
    Uri uri =
        app.getContentResolver()
            .insert(
                Build.VERSION.SDK_INT >= 29
                    ? MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    : MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values);
    assertNotNull(uri);
    files.add(uri);
    try (InputStream input =
            InstrumentationRegistry.getInstrumentation()
                .getContext()
                .getAssets()
                .open(asset);
        OutputStream output = app.getContentResolver().openOutputStream(uri)) {
      assertNotNull(output);
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }
    DownloadStore.recordLocal(app, uri, title, filename, mime);
    return uri;
  }

  Uri image(String title) throws Exception {
    ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, title + ".png");
    values.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
    if (Build.VERSION.SDK_INT >= 29)
      values.put(
          MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folder);
    Uri uri =
        app.getContentResolver()
            .insert(
                Build.VERSION.SDK_INT >= 29
                    ? MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values);
    assertNotNull(uri);
    files.add(uri);
    android.graphics.Bitmap bitmap =
        android.graphics.Bitmap.createBitmap(320, 240, android.graphics.Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(0xff2c5e4f);
    try (OutputStream output = app.getContentResolver().openOutputStream(uri)) {
      assertNotNull(output);
      assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
    } finally {
      bitmap.recycle();
    }
    DownloadStore.recordLocal(app, uri, title, title + ".png", "image/png");
    return uri;
  }

  byte[] bytes(Uri uri) throws Exception {
    try (InputStream input = app.getContentResolver().openInputStream(uri);
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
      assertNotNull(input);
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
      return output.toByteArray();
    }
  }

  Set<String> gifCacheNames() {
    String[] names = app.getCacheDir().list();
    assertNotNull(names);
    return java.util.Arrays.stream(names)
        .filter(n -> n.startsWith("still-gif-"))
        .collect(java.util.stream.Collectors.toSet());
  }

  long publishedFiles() {
    if (Build.VERSION.SDK_INT < 29) return 0;
    try (Cursor cursor =
        app.getContentResolver()
            .query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    .buildUpon()
                    .appendQueryParameter("includePending", "1")
                    .build(),
                new String[] {MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                new String[] {Environment.DIRECTORY_DOWNLOADS + "/" + folder + "/"},
                null)) {
      assertNotNull(cursor);
      return cursor.getCount();
    }
  }

  static boolean hasText(ActivityScenario<?> screen, String text) {
    java.util.concurrent.atomic.AtomicBoolean found =
        new java.util.concurrent.atomic.AtomicBoolean();
    screen.onActivity(
        a -> {
          java.util.ArrayList<android.view.View> views = new java.util.ArrayList<>();
          a.getWindow()
              .getDecorView()
              .findViewsWithText(
                  views,
                  text,
                  android.view.View.FIND_VIEWS_WITH_TEXT
                      | android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
          found.set(!views.isEmpty());
        });
    return found.get();
  }

  void assertPublished(Uri uri, String mime) {
    assertEquals(mime, app.getContentResolver().getType(uri));
    try (Cursor cursor =
        app.getContentResolver()
            .query(uri, new String[] {MediaStore.MediaColumns.IS_PENDING}, null, null, null)) {
      assertNotNull(cursor);
      assertTrue(cursor.moveToFirst());
      assertEquals(0, cursor.getInt(0));
    }
  }

  static void assertPlayerLaunch(Runnable button, Uri uri, String mime) {
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    java.util.concurrent.atomic.AtomicReference<Intent> request =
        new java.util.concurrent.atomic.AtomicReference<>();
    Instrumentation.ActivityMonitor monitor =
        new Instrumentation.ActivityMonitor() {
          @Override
          public Instrumentation.ActivityResult onStartActivity(Intent intent) {
            if (intent.getComponent() == null
                || !PlayerActivity.class.getName().equals(intent.getComponent().getClassName()))
              return null;
            request.set(intent);
            return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
          }
        };
    instrumentation.addMonitor(monitor);
    try {
      button.run();
    } finally {
      instrumentation.removeMonitor(monitor);
    }
    assertNotNull("Open did not launch the player", request.get());
    assertEquals(uri, request.get().getData());
    assertEquals(mime, request.get().getType());
    assertTrue((request.get().getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
  }

  static Object field(Object target, String name) {
    try {
      Field f = target.getClass().getDeclaredField(name);
      f.setAccessible(true);
      return f.get(target);
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  static void showPost(MainActivity activity, Post post) {
    activity.session().showResolvedForTest(post);
  }

  interface Condition {
    boolean ready() throws Exception;
  }

  static void await(String description, Condition condition) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 20_000;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (condition.ready()) return;
      SystemClock.sleep(50);
    }
    fail("Timed out: " + description);
  }

  @Override
  public void close() throws Exception {
    try {
      // Provider gates are released before waiting for workers; a retained descriptor cannot hang
      // cleanup.
      app.getContentResolver().call(FixtureControlProvider.BASE, "release", null, null);
      if (GifService.isRunning()) {
        GifService.cancel(app);
        await("GIF worker cleanup", () -> !GifService.isRunning());
      }
      await("fixture GIF cache cleanup", () -> gifCacheNames().equals(originalGifCache));
      for (DownloadStore.Entry entry : DownloadStore.list(app))
        DownloadStore.remove(app, entry, true);
      for (Uri uri : files) app.getContentResolver().delete(uri, null, null);
      if (Build.VERSION.SDK_INT >= 29)
        app.getContentResolver()
            .delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    .buildUpon()
                    .appendQueryParameter("includePending", "1")
                    .build(),
                MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                new String[] {Environment.DIRECTORY_DOWNLOADS + "/" + folder + "/"});
      for (android.content.UriPermission grant :
          app.getContentResolver().getPersistedUriPermissions())
        if (FixtureDocumentsProvider.TREE.equals(grant.getUri()))
          app.getContentResolver()
              .releasePersistableUriPermission(
                  grant.getUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
      app.getContentResolver().call(FixtureControlProvider.BASE, "revoke", null, null);
    } finally {
      restorePreferences();
    }
  }

  private void restorePreferences() {
    restorePreferences(prefs, original);
  }

  @SuppressWarnings("unchecked")
  static void restorePreferences(SharedPreferences prefs, Map<String, ?> original) {
    SharedPreferences.Editor edit = prefs.edit().clear();
    for (Map.Entry<String, ?> item : original.entrySet()) {
      Object v = item.getValue();
      String k = item.getKey();
      if (v instanceof String) edit.putString(k, (String) v);
      else if (v instanceof Boolean) edit.putBoolean(k, (Boolean) v);
      else if (v instanceof Integer) edit.putInt(k, (Integer) v);
      else if (v instanceof Long) edit.putLong(k, (Long) v);
      else if (v instanceof Float) edit.putFloat(k, (Float) v);
      else if (v instanceof Set) edit.putStringSet(k, (Set<String>) v);
    }
    assertTrue(edit.commit());
  }
}
