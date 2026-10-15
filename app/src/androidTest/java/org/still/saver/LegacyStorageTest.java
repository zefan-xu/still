// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.VideoView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.still.saver.core.Media;
import org.still.saver.core.Post;
import org.still.saver.core.Variant;
import org.still.saver.gif.GifService;
import org.still.saver.ui.Fonts;
import org.still.saver.ui.LocalFrames;
import org.still.saver.ui.Thumbnails;

/**
 * Android 7–9 storage: the runtime prompt, files written straight into Downloads, their
 * thumbnails, the viewer's system-bar flags and the bundled fonts. Android stops the app when
 * storage access is revoked, so {@code scripts/test.py legacy} revokes it before the suite; tests
 * run in name order and only the first may see the prompt.
 */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(maxSdkVersion = 28)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public final class LegacyStorageTest {
  private static final String STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE;
  private static final String PROMPT = "com.android.packageinstaller:id/";
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context app = instrumentation.getTargetContext();

  @Test
  public void asksForStorageAcrossRotationBeforeSavingOrRetrying() throws Exception {
    for (String permission : new String[] {Manifest.permission.READ_EXTERNAL_STORAGE, STORAGE})
      assertNotEquals(
          "Android skips the prompt while any storage permission is granted; run through"
              + " scripts/test.py legacy",
          PackageManager.PERMISSION_GRANTED,
          app.checkSelfPermission(permission));
    try (Fixture f = new Fixture()) {
      Post post = post("28", "https://video.twimg.com/legacy-prompt.mp4");
      Post failed = post("29", "https://video.twimg.com/legacy-retry.mp4");
      assertTrue(DownloadStore.enqueue(app, failed, 0, failed.media.get(0).variants.get(0)));
      JSONArray rows = new JSONArray(f.prefs.getString("downloads", "[]"));
      rows.getJSONObject(0).put("id", -2);
      assertTrue(f.prefs.edit().putString("downloads", rows.toString()).commit());
      String retryKey = DownloadStore.list(app).get(0).key;

      try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
        screen.onActivity(a -> TestFixtures.showPost(a, post));
        onView(withText("Save video")).perform(scrollTo(), click());
        f.awaitPrompt();
        MainActivity before = activity(screen);
        f.rotate(screen, Surface.ROTATION_90);
        assertNotSame("Library recreated behind the prompt", before, activity(screen));
        f.answer("permission_deny_button");
        TestFixtures.await(
            "denial explained on the rotated screen",
            () -> StoragePermission.DENIED.equals(activity(screen).session().message()));
        assertTrue(TestFixtures.hasText(screen, StoragePermission.DENIED));
        assertEquals(1, DownloadStore.list(app).size());
        pressBack();
        f.rotate(screen, Surface.ROTATION_0);

        onView(allOf(withText("Retry"), isDisplayed())).perform(click());
        f.awaitPrompt();
        MainActivity portrait = activity(screen);
        f.rotate(screen, Surface.ROTATION_90);
        assertNotSame("Library recreated behind the prompt", portrait, activity(screen));
        f.answer("permission_allow_button");
        TestFixtures.await(
            "retry admitted after access was granted",
            () -> DownloadStore.find(app, retryKey).id == -1);
        assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(STORAGE));
        f.rotate(screen, Surface.ROTATION_0);

        screen.onActivity(a -> TestFixtures.showPost(a, post));
        onView(withText("Save video")).perform(scrollTo(), click());
        TestFixtures.await("save admitted without asking again", () -> DownloadStore.list(app).size() == 2);
        assertNull(f.promptButton("permission_allow_button"));
        DownloadStore.Entry saved = DownloadStore.list(app).get(0);
        assertEquals(post.media.get(0).variants.get(0).url, saved.url);
        assertEquals(-1, saved.id);
      }
    }
  }

  @Test
  public void exportsGifsIntoDownloadsWithThumbnails() throws Exception {
    try (Fixture f = new Fixture()) {
      f.grant();
      Uri video = f.legacyVideo("Legacy source");
      try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
        onView(withContentDescription("Legacy source")).perform(longClick());
        onView(withText("Make a GIF")).perform(click());
        onView(withText("Make GIF")).perform(click());
        TestFixtures.await("finished GIF service", () -> gif() != null && !GifService.isRunning());
      }
      assertEquals("GIF saved to Downloads/" + f.folder, GifService.getStatus());
      DownloadStore.Entry gif = gif();
      assertEquals("image/gif", app.getContentResolver().getType(gif.uri));
      File published = new File(dataPath(gif.uri));
      assertEquals(f.directory, published.getParentFile());
      assertEquals(gif.filename, published.getName());
      assertTrue(published.length() > 0);
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(published.getPath(), bounds);
      assertEquals(360, bounds.outWidth);
      assertEquals(270, bounds.outHeight);
      assertEquals(2, DownloadStore.list(app).size());

      assertTrue(LocalFrames.image(app.getContentResolver(), gif.uri, Thumbnails.Sizes.TILE).getWidth() > 0);
      LocalFrames.Frame frame = LocalFrames.video(app, video, Thumbnails.Sizes.TILE);
      assertTrue(frame.durationMs > 0);
      assertTrue(Math.max(frame.bitmap.getWidth(), frame.bitmap.getHeight()) <= Thumbnails.Sizes.TILE);
      for (DownloadStore.Entry entry : DownloadStore.list(app)) {
        Thumbnails.Thumb thumb = bindLocal(entry);
        assertTrue(entry.mime, thumb.bitmap.getWidth() > 0);
        assertEquals(entry.mime, entry.mime.startsWith("video/"), thumb.durationMs > 0);
      }
    }
  }

  @Test
  public void hidesViewerSystemBarsWithLegacyFlags() throws Exception {
    int hidden = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
    try (Fixture f = new Fixture()) {
      f.grant();
      Uri video = f.legacyVideo("Legacy viewer");
      try (ActivityScenario<PlayerActivity> screen =
          ActivityScenario.launch(
              new Intent(app, PlayerActivity.class).setDataAndType(video, "video/mp4"))) {
        TestFixtures.await("playback hides the system bars", () -> (flags(screen) & hidden) == hidden);
        screen.onActivity(a -> assertTrue(((VideoView) TestFixtures.field(a, "video")).isPlaying()));
        screen.onActivity(a -> a.stageView().performClick());
        TestFixtures.await("a tap shows them again", () -> (flags(screen) & hidden) == 0);
      }
    }
  }

  @Test
  public void loadsBundledSerifFontsInsteadOfTheSystemFallback() throws Exception {
    Typeface display = Fonts.display(app), serif = Fonts.serif(app);
    for (Typeface font : new Typeface[] {display, serif}) {
      assertNotSame(Typeface.DEFAULT, font);
      assertNotEquals(width(Typeface.DEFAULT), width(font), 0.5f);
      assertNotEquals(width(Typeface.SERIF), width(font), 0.5f);
    }
    assertNotEquals(width(display), width(serif), 0.5f);
    try (Fixture f = new Fixture();
        ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
      screen.onActivity(
          a -> {
            ArrayList<View> found = new ArrayList<>();
            a.getWindow().getDecorView().findViewsWithText(found, "Still", View.FIND_VIEWS_WITH_TEXT);
            boolean wordmark = false;
            for (View view : found)
              wordmark |= view instanceof TextView && ((TextView) view).getTypeface() == display;
            assertTrue("The wordmark uses the display serif", wordmark);
          });
    }
  }

  private static float width(Typeface typeface) {
    Paint paint = new Paint();
    paint.setTextSize(100);
    paint.setTypeface(typeface);
    return paint.measureText("Still saves every post");
  }

  private static Post post(String id, String url) {
    Variant variant = new Variant(url, "720p", "video/mp4", 1000, 1280, 720);
    return new Post(
        id,
        "Legacy fixture " + id,
        "Fixture",
        "https://x.com/i/status/" + id,
        Collections.singletonList(new Media("video", Collections.singletonList(variant))));
  }

  private static MainActivity activity(ActivityScenario<MainActivity> screen) {
    AtomicReference<MainActivity> current = new AtomicReference<>();
    screen.onActivity(current::set);
    return current.get();
  }

  private static int flags(ActivityScenario<PlayerActivity> screen) {
    AtomicReference<Integer> flags = new AtomicReference<>();
    screen.onActivity(a -> flags.set(a.getWindow().getDecorView().getSystemUiVisibility()));
    return flags.get();
  }

  private DownloadStore.Entry gif() {
    for (DownloadStore.Entry entry : DownloadStore.list(app))
      if ("image/gif".equals(entry.mime)) return entry;
    return null;
  }

  private String dataPath(Uri uri) {
    try (Cursor cursor =
        app.getContentResolver()
            .query(uri, new String[] {MediaStore.MediaColumns.DATA}, null, null, null)) {
      assertNotNull(cursor);
      assertTrue(cursor.moveToFirst());
      return cursor.getString(0);
    }
  }

  private Thumbnails.Thumb bindLocal(DownloadStore.Entry entry) throws Exception {
    AtomicReference<Thumbnails.Thumb> loaded = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () ->
            Thumbnails.get(app)
                .bindLocal(
                    new ImageView(app), entry.uri, entry.mime, Thumbnails.Sizes.ROW, loaded::set));
    TestFixtures.await("thumbnail for " + entry.filename, () -> loaded.get() != null);
    return loaded.get();
  }

  /** Library state and a Downloads folder owned by one test; restores rotation and preferences. */
  private final class Fixture implements AutoCloseable {
    final SharedPreferences prefs = DownloadStore.prefs(app);
    final Map<String, ?> original = prefs.getAll();
    final String folder = "StillTests_" + UUID.randomUUID().toString().substring(0, 8);
    final File directory =
        new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folder);
    final UiAutomation automation = instrumentation.getUiAutomation();
    final int rotation = display();
    final boolean autoRotate =
        Settings.System.getInt(app.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0)
            == 1;

    Fixture() {
      assertFalse("An export is already active; use an isolated test device", GifService.isRunning());
      assertTrue(
          prefs
              .edit()
              .clear()
              .putString("downloads", "[]")
              .putString("folder", folder)
              .putInt("parallel", 0)
              .putBoolean("auto", false)
              .putBoolean(OnboardingActivity.SEEN, true)
              .commit());
    }

    /** Grants the whole storage group, as the prompt does; write access alone never remounts. */
    void grant() {
      for (String permission : new String[] {Manifest.permission.READ_EXTERNAL_STORAGE, STORAGE}) {
        if (app.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
          automation.grantRuntimePermission(app.getPackageName(), permission);
        assertEquals(PackageManager.PERMISSION_GRANTED, app.checkSelfPermission(permission));
      }
    }

    /** Writes the sample video into the folder the way Still publishes files before Android 10. */
    Uri legacyVideo(String title) throws Exception {
      // Android remounts external storage for a running app shortly after a grant.
      TestFixtures.await("writable Downloads", () -> directory.isDirectory() || directory.mkdirs());
      File file = new File(directory, title + ".mp4");
      ContentValues values = new ContentValues();
      values.put(MediaStore.MediaColumns.DATA, file.getAbsolutePath());
      values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
      values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
      Uri uri = app.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
      assertNotNull(uri);
      try (InputStream input = instrumentation.getContext().getAssets().open("sample.mp4");
          OutputStream output = app.getContentResolver().openOutputStream(uri)) {
        assertNotNull(output);
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1; ) output.write(buffer, 0, count);
      }
      DownloadStore.recordLocal(app, uri, title, file.getName(), "video/mp4");
      return uri;
    }

    int display() {
      return ((WindowManager) app.getSystemService(Context.WINDOW_SERVICE))
          .getDefaultDisplay()
          .getRotation();
    }

    /** Waits for {@code screen} to be recreated for the new orientation, even behind the prompt. */
    void rotate(ActivityScenario<MainActivity> screen, int target) throws Exception {
      int orientation =
          target == Surface.ROTATION_0 || target == Surface.ROTATION_180
              ? Configuration.ORIENTATION_PORTRAIT
              : Configuration.ORIENTATION_LANDSCAPE;
      assertTrue(automation.setRotation(target));
      TestFixtures.await("display rotation " + target, () -> display() == target);
      TestFixtures.await(
          "screen recreated for rotation " + target,
          () -> activity(screen).getResources().getConfiguration().orientation == orientation);
      instrumentation.waitForIdleSync();
    }

    AccessibilityNodeInfo promptButton(String id) {
      AccessibilityNodeInfo root = automation.getRootInActiveWindow();
      if (root == null) return null;
      List<AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByViewId(PROMPT + id);
      return buttons.isEmpty() ? null : buttons.get(0);
    }

    void awaitPrompt() throws Exception {
      TestFixtures.await("storage prompt", () -> promptButton("permission_allow_button") != null);
    }

    void answer(String id) throws Exception {
      awaitPrompt();
      AtomicBoolean clicked = new AtomicBoolean();
      TestFixtures.await(
          "storage prompt " + id,
          () -> {
            AccessibilityNodeInfo button = promptButton(id);
            if (button != null && !clicked.get())
              clicked.set(button.performAction(AccessibilityNodeInfo.ACTION_CLICK));
            return clicked.get() && promptButton(id) == null;
          });
    }

    @Override
    public void close() throws Exception {
      try {
        if (promptButton("permission_deny_button") != null) answer("permission_deny_button");
        if (GifService.isRunning()) {
          GifService.cancel(app);
          TestFixtures.await("GIF worker cleanup", () -> !GifService.isRunning());
        }
        for (DownloadStore.Entry entry : DownloadStore.list(app)) {
          DownloadStore.remove(app, entry, true);
          if (entry.uri != null) Thumbnails.get(app).forget(entry.uri);
        }
        File[] left = directory.listFiles();
        if (left != null) for (File file : left) assertTrue(file.delete());
        assertTrue(!directory.exists() || directory.delete());
      } finally {
        automation.setRotation(rotation);
        if (autoRotate) automation.setRotation(UiAutomation.ROTATION_UNFREEZE);
        TestFixtures.restorePreferences(prefs, original);
      }
    }
  }
}
