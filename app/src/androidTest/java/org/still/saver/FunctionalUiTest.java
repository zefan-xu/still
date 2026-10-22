// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.still.saver.core.Media;
import org.still.saver.core.Post;
import org.still.saver.core.Variant;

/** Offline button-driven flows; resolved-post fixtures test admission without network transfers. */
@RunWith(AndroidJUnit4.class)
public final class FunctionalUiTest {
  @Test
  public void emptyInputAndClipboardValidationRecoverWithoutDownloads() throws Exception {
    try (TestFixtures f = new TestFixtures();
        ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
      onView(withHint("Paste a link")).perform(click(), pressImeActionButton());
      TestFixtures.await(
          "empty input feedback",
          () -> feedback(screen).equals("Paste or share a post link first."));
      onView(withHint("Paste a link")).check(matches(withText("")));
      assertTrue(DownloadStore.list(f.app).isEmpty());
      ClipboardManager clipboard =
          (ClipboardManager) f.app.getSystemService(Context.CLIPBOARD_SERVICE);
      AtomicReference<ClipData> original = new AtomicReference<>();
      screen.onActivity(
          a -> {
            original.set(clipboard.getPrimaryClip());
            clipboard.setPrimaryClip(
                ClipData.newPlainText("Test", "https://example.com/not-a-supported-post"));
          });
      try {
        onView(withContentDescription("Paste")).perform(click());
        onView(withContentDescription("Find media")).perform(click());
        TestFixtures.await(
            "invalid clipboard feedback",
            () -> feedback(screen).startsWith("Use a supported HTTPS post link"));
        onView(withHint("Paste a link"))
            .check(matches(withText("https://example.com/not-a-supported-post")));
        onView(withContentDescription("Find media")).check(matches(isEnabled()));
        assertTrue(DownloadStore.list(f.app).isEmpty());
      } finally {
        screen.onActivity(
            a -> {
              if (original.get() == null) clipboard.clearPrimaryClip();
              else clipboard.setPrimaryClip(original.get());
            });
      }
    }
  }

  @Test
  public void incomingShareShowsTheProblemOverTheSourceAppWithoutDownloads() throws Exception {
    try (TestFixtures f = new TestFixtures();
        ActivityScenario<ShareActivity> screen =
            ActivityScenario.launch(
                new Intent(f.app, ShareActivity.class)
                    .setAction(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "https://example.com/invalid"))) {
      TestFixtures.await(
          "invalid share feedback",
          () -> shareMessage(screen).startsWith("Use a supported HTTPS post link"));
      onView(withText("Couldn’t get this post")).check(matches(isDisplayed()));
      onView(withText("Open Still")).check(matches(isDisplayed()));
      assertTrue(DownloadStore.list(f.app).isEmpty());
    }
  }

  @Test
  public void settingsPersistThroughRecreation() throws Exception {
    try (TestFixtures f = new TestFixtures();
        ActivityScenario<SettingsActivity> screen =
            ActivityScenario.launch(SettingsActivity.class)) {
      onView(withText("Choose for each item")).perform(scrollTo(), click());
      onView(withText("Smallest available")).perform(click());
      onView(withText("Save automatically")).perform(scrollTo(), click());
      onView(withText("Wi-Fi downloads only")).perform(scrollTo(), click());
      onView(withText("0 at a time")).perform(scrollTo(), click());
      onView(withText("3")).perform(click());
      onView(withText("Follow system")).perform(scrollTo(), click());
      onView(withText("Dark")).perform(click());
      screen.recreate();
      assertEquals("smallest", f.prefs.getString("quality", ""));
      assertTrue(f.prefs.getBoolean("auto", false));
      assertTrue(f.prefs.getBoolean("wifi", false));
      assertEquals(3, f.prefs.getInt("parallel", -1));
      assertEquals("dark", f.prefs.getString("theme", ""));
      onView(withText("Smallest available")).perform(scrollTo()).check(matches(isDisplayed()));
      onView(withText("Save automatically")).perform(scrollTo()).check(matches(isChecked()));
    }
  }

  @Test
  public void folderAndEndpointRejectUnsafeValuesThenAcceptValidEdits() throws Exception {
    try (TestFixtures f = new TestFixtures();
        ActivityScenario<SettingsActivity> screen =
            ActivityScenario.launch(SettingsActivity.class)) {
      onView(withText("Downloads/" + f.folder)).perform(scrollTo(), click());
      onView(isAssignableFrom(EditText.class)).perform(replaceText("../outside"));
      // Short screens with a tall keyboard put Save below the sheet's fold, as a user would scroll.
      onView(withText("Save")).perform(scrollTo(), click());
      assertEquals(f.folder, DownloadStore.folder(f.app));
      onView(withText("Use 1–60 letters, numbers, spaces, _ or -")).check(matches(isDisplayed()));
      onView(isAssignableFrom(EditText.class)).perform(replaceText(f.folder + "_Edited"));
      onView(withText("Save")).perform(scrollTo(), click());
      assertEquals(f.folder + "_Edited", DownloadStore.folder(f.app));
      onView(withText("X metadata service")).perform(scrollTo(), click());
      onView(isAssignableFrom(EditText.class))
          .perform(replaceText("https://user:secret@example.com"));
      onView(withText("Save")).perform(scrollTo(), click());
      assertFalse(f.prefs.contains("api"));
      onView(withText("Enter an HTTPS endpoint without credentials or query parameters"))
          .check(matches(isDisplayed()));
      onView(isAssignableFrom(EditText.class))
          .perform(replaceText("https://example.com/public-api"));
      onView(withText("Save")).perform(scrollTo(), click());
      screen.recreate();
      assertEquals("https://example.com/public-api", f.prefs.getString("api", ""));
      onView(withText("example.com")).perform(scrollTo()).check(matches(isDisplayed()));
    }
  }

  @Test
  public void qualitySelectionSaveAllAndDuplicateSuppressionUseTheChosenUrls() throws Exception {
    try (TestFixtures f = new TestFixtures();
        ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
      Variant best =
          new Variant("https://video.twimg.com/best.mp4", "720p", "video/mp4", 1000, 1280, 720);
      Variant small =
          new Variant("https://video.twimg.com/small.mp4", "180p", "video/mp4", 100, 320, 180);
      Variant photo =
          new Variant("https://pbs.twimg.com/media/fixture.jpg", "Photo", "image/jpeg", 0, 32, 24);
      Post post =
          new Post(
              "42",
              "Offline selection",
              "Fixture",
              "https://x.com/i/status/42",
              Arrays.asList(
                  new Media("video", Arrays.asList(best, small)),
                  new Media("photo", Collections.singletonList(photo))));
      screen.onActivity(a -> TestFixtures.showPost(a, post));
      onView(withContentDescription("Quality for media 1")).perform(scrollTo(), click());
      onView(withText("180p")).perform(scrollTo(), click());
      onView(withContentDescription(startsWith("Include media 2"))).perform(scrollTo(), click());
      onView(withText("Save 1 item")).perform(scrollTo(), click());
      assertEquals(1, DownloadStore.list(f.app).size());
      assertEquals(small.url, DownloadStore.list(f.app).get(0).url);
      // A second pass keeps the smaller video's admission and adds the photo.
      screen.onActivity(a -> TestFixtures.showPost(a, post));
      onView(withContentDescription("Quality for media 1")).perform(scrollTo(), click());
      onView(withText("180p")).perform(scrollTo(), click());
      onView(withText("Save 2 items")).perform(scrollTo(), click());
      assertEquals(2, DownloadStore.list(f.app).size());
      assertTrue(DownloadStore.list(f.app).stream().anyMatch(e -> photo.url.equals(e.url)));
      assertTrue(DownloadStore.list(f.app).stream().allMatch(e -> e.id == -1));
      screen.onActivity(a -> TestFixtures.showPost(a, post));
      onView(withContentDescription("Quality for media 1")).perform(scrollTo(), click());
      onView(withText("180p")).perform(scrollTo(), click());
      onView(withText("Save 2 items")).perform(scrollTo(), click());
      assertEquals(2, DownloadStore.list(f.app).size());
    }
  }

  @Test
  public void librarySearchKeepRemoveHistoryAndDeleteHaveDifferentFileEffects() throws Exception {
    try (TestFixtures f = new TestFixtures()) {
      Uri aurora = f.video("Aurora"), ocean = f.video("Ocean");
      byte[] original = f.bytes(aurora), originalOcean = f.bytes(ocean);
      try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
        onView(withContentDescription("Search")).perform(click());
        onView(withHint("Search saved files")).perform(replaceText("AURORA"), androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
        onView(withText("Ocean")).check(doesNotExist());
        onView(withText("Aurora")).check(matches(isDisplayed()));
        onView(withContentDescription("More actions for Aurora")).perform(click());
        onView(withText("Delete file")).perform(click());
        onView(withText("Keep")).perform(click());
        assertArrayEquals(original, f.bytes(aurora));
        assertEquals(2, DownloadStore.list(f.app).size());
        TestFixtures.assertPlayerLaunch(
            () -> onView(withText("Aurora")).perform(click()), aurora, "video/mp4");
        onView(withContentDescription("More actions for Aurora")).perform(click());
        onView(withText("Remove from library")).perform(click());
        onView(withText("Remove")).perform(click());
        assertArrayEquals(original, f.bytes(aurora));
        assertEquals(1, DownloadStore.list(f.app).size());
        DownloadStore.recordLocal(f.app, aurora, "Aurora", "Aurora.mp4", "video/mp4");
        screen.recreate();
        onView(withHint("Search saved files")).perform(replaceText("AURORA"), androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
        onView(withContentDescription("More actions for Aurora")).perform(click());
        onView(withText("Delete file")).perform(click());
        onView(withText("Delete")).perform(click());
        try {
          f.bytes(aurora);
          fail("Deleted file is still readable");
        } catch (java.io.FileNotFoundException expected) {
        }
        assertArrayEquals(originalOcean, f.bytes(ocean));
        assertEquals(1, DownloadStore.list(f.app).size());
      }
    }
  }

  @Test
  public void failedDownloadRetryAndQueuedCancelPreserveOtherEntries() throws Exception {
    try (TestFixtures f = new TestFixtures()) {
      Variant variant =
          new Variant("https://video.twimg.com/fixture.mp4", "MP4", "video/mp4", 0, 0, 0);
      Post post =
          new Post(
              "42",
              "Retry fixture",
              "",
              "https://x.com/i/status/42",
              Collections.singletonList(new Media("video", Collections.singletonList(variant))));
      DownloadStore.enqueue(f.app, post, 0, variant);
      String key = DownloadStore.list(f.app).get(0).key;
      Uri control = f.video("Unrelated saved video");
      byte[] controlBytes = f.bytes(control);
      JSONArray rows = new JSONArray(f.prefs.getString("downloads", "[]"));
      rows.getJSONObject(0).put("id", -2);
      f.prefs.edit().putString("downloads", rows.toString()).commit();
      try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
        onView(allOf(withText("Retry"), isDisplayed())).perform(click());
        DownloadStore.Entry retried =
            DownloadStore.list(f.app).stream().filter(e -> key.equals(e.key)).findFirst().get();
        assertEquals(key, retried.key);
        assertEquals(-1, retried.id);
        assertEquals(variant.url, retried.url);
        onView(allOf(withText("Cancel"), isDisplayed())).perform(click());
        onView(withText("Keep")).perform(click());
        assertEquals(2, DownloadStore.list(f.app).size());
        onView(allOf(withText("Cancel"), isDisplayed())).perform(click());
        onView(withText("Cancel download")).perform(click());
        assertEquals(1, DownloadStore.list(f.app).size());
        assertEquals(control, DownloadStore.list(f.app).get(0).uri);
        assertArrayEquals(controlBytes, f.bytes(control));
      }
    }
  }

  @Test
  public void shareChooserCarriesTheSavedUriMimeAndReadGrant() throws Exception {
    try (TestFixtures f = new TestFixtures()) {
      Uri source = f.video("Share fixture");
      AtomicReference<Intent> chooser = new AtomicReference<>();
      Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
      Instrumentation.ActivityMonitor monitor =
          new Instrumentation.ActivityMonitor() {
            @Override
            public Instrumentation.ActivityResult onStartActivity(Intent intent) {
              if (!Intent.ACTION_CHOOSER.equals(intent.getAction())) return null;
              chooser.set(intent);
              return new Instrumentation.ActivityResult(Activity.RESULT_OK, null);
            }
          };
      instrumentation.addMonitor(monitor);
      try (ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
        onView(withContentDescription("Share fixture")).perform(longClick());
        onView(withText("Share")).perform(click());
        assertNotNull(chooser.get());
        Intent send = chooser.get().getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(Intent.ACTION_SEND, send.getAction());
        assertEquals("video/mp4", send.getType());
        assertEquals(source, send.getParcelableExtra(Intent.EXTRA_STREAM));
        assertEquals(source, send.getClipData().getItemAt(0).getUri());
        assertTrue((send.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertTrue(f.bytes(source).length > 1024);
      } finally {
        instrumentation.removeMonitor(monitor);
      }
    }
  }

  private static String feedback(ActivityScenario<MainActivity> screen) {
    AtomicReference<String> text = new AtomicReference<>();
    screen.onActivity(a -> text.set(a.composerMessage()));
    return text.get();
  }

  private static String shareMessage(ActivityScenario<ShareActivity> screen) {
    AtomicReference<String> text = new AtomicReference<>();
    screen.onActivity(
        a -> text.set(a.session().message() == null ? "" : a.session().message()));
    return text.get();
  }
}
