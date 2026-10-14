// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.still.saver.core.Media;
import org.still.saver.core.Post;
import org.still.saver.core.Variant;
import org.still.saver.ui.Thumbnails;

/**
 * Captures the README screenshots from public-domain NASA media in an isolated library. Pass
 * readmeScreenshots=true; `scripts/test.py screenshots` sets a demo status bar around this test
 * and pulls files/readme/*.png afterwards.
 */
@RunWith(AndroidJUnit4.class)
public final class ReadmeScreenshots {
  private static final String PREVIEW_HOST = "https://images.example.org/still-readme/";

  @Test
  public void capture() throws Exception {
    Assume.assumeTrue(
        "Enable with the instrumentation argument readmeScreenshots=true",
        "true".equals(InstrumentationRegistry.getArguments().getString("readmeScreenshots")));
    File out = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(), "readme");
    deleteTree(out);
    assertTrue(out.mkdirs());
    try (TestFixtures f = new TestFixtures(2)) {
      seedLibrary(f);
      seedPreviews();
      for (String theme : new String[] {"light", "dark"}) {
        assertTrue(f.prefs.edit().putString("theme", theme).commit());
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
          settle(screen);
          capture(out, "library-" + theme);
          onView(withContentDescription("Earthrise from lunar orbit")).perform(longClick());
          settle(screen);
          capture(out, "actions-" + theme);
        }
        try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
          screen.onActivity(a -> TestFixtures.showPost(a, post()));
          settle(screen);
          capture(out, "save-" + theme);
          screen.onActivity(a -> a.session().reset());
        }
        try (ActivityScenario<SettingsActivity> screen = ActivityScenario.launch(SettingsActivity.class)) {
          settle(screen);
          capture(out, "settings-" + theme);
        }
      }
      DownloadStore.Entry pillars =
          DownloadStore.list(f.app).stream().filter(e -> e.title.startsWith("Pillars")).findFirst().get();
      try (ActivityScenario<PlayerActivity> screen =
          ActivityScenario.launch(
              new Intent(f.app, PlayerActivity.class)
                  .setDataAndType(pillars.uri, "image/jpeg")
                  .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))) {
        settle(screen);
        capture(out, "viewer");
      }
    }
    assertEquals(9, out.list().length);
  }

  /** Newest last: two rows today (two videos first), one row earlier this week. */
  private static void seedLibrary(TestFixtures f) throws Exception {
    Object[][] items = {
      {"curiosity.jpg", "Curiosity at Mont Mercou", "image/jpeg", 3},
      {"mars-base.jpg", "First humans on Mars, artist’s concept", "image/jpeg", 3},
      {"aurora-red.jpg", "Aurora borealis from the station", "image/jpeg", 2},
      {"solar-array.jpg", "Earth from Expedition 52", "image/jpeg", 0},
      {"earthset.jpg", "Earthset from Orion", "image/jpeg", 0},
      {"pillars.jpg", "Pillars of Creation, Hubble", "image/jpeg", 0},
      {"saturn.jpg", "Saturn, from Cassini", "image/jpeg", 0},
      {"aurora-pass.mp4", "Aurora over the Indian Ocean", "video/mp4", 0},
      {"earthrise-orbit.mp4", "Earthrise from lunar orbit", "video/mp4", 0},
    };
    for (Object[] item : items) {
      String asset = (String) item[0];
      f.media("readme/" + asset, (String) item[1], asset, (String) item[2]);
    }
    JSONArray library = new JSONArray(f.prefs.getString("downloads", "[]"));
    long now = System.currentTimeMillis();
    for (int i = 0; i < library.length(); i++) {
      JSONObject entry = library.getJSONObject(i);
      int daysAgo = (Integer) items[i][3];
      entry.put("created", now - daysAgo * 86_400_000L - (items.length - i) * 60_000L);
    }
    // Files stay in the fixture's own folder for cleanup; the sheet shows the default folder name.
    assertTrue(f.prefs.edit().putString("downloads", library.toString()).putString("folder", "Still").commit());
  }

  private static Post post() {
    List<Media> media = new ArrayList<>();
    for (String name : new String[] {"earthrise", "earthset", "saturn"})
      media.add(
          new Media(
              "photo",
              Collections.singletonList(
                  new Variant(PREVIEW_HOST + name + "-full.jpg", "2048 px", "image/jpeg", 0, 2048, 2048)),
              PREVIEW_HOST + name + ".jpg",
              0));
    return new Post(
        "readme",
        "Three views of home, 1968 to today. Earthrise from Apollo 8, Earthset from Orion, and"
            + " Saturn from Cassini’s final days.",
        "@nasa",
        "https://www.threads.com/@nasa/post/readme",
        media);
  }

  private static void seedPreviews() throws Exception {
    Thumbnails thumbnails =
        Thumbnails.get(InstrumentationRegistry.getInstrumentation().getTargetContext());
    for (String name : new String[] {"earthrise", "earthset", "saturn"}) {
      try (InputStream input =
          InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("readme/" + name + ".jpg")) {
        Bitmap bitmap = BitmapFactory.decodeStream(input);
        assertNotNull(bitmap);
        thumbnails.putRemoteForTest(PREVIEW_HOST + name + ".jpg", Thumbnails.Sizes.PREVIEW, bitmap);
      }
    }
  }

  private static void settle(ActivityScenario<?> screen) throws Exception {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    Thumbnails.get(InstrumentationRegistry.getInstrumentation().getTargetContext()).idleForTest();
    // Sheet animations and image decodes finish on later frames.
    SystemClock.sleep(1200);
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
  }

  private static void capture(File directory, String name) throws Exception {
    Bitmap shot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    assertNotNull("Screenshot failed: " + name, shot);
    try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
      assertTrue(shot.compress(Bitmap.CompressFormat.PNG, 100, output));
    }
  }

  private static void deleteTree(File file) {
    File[] children = file.listFiles();
    if (children != null) for (File child : children) deleteTree(child);
    file.delete();
  }
}
