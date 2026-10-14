// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.widget.VideoView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.still.saver.gif.GifService;

/** Real synthetic media, service publication/cleanup, and Android playback lifecycle. */
@RunWith(AndroidJUnit4.class)
public final class MediaWorkflowTest {
  @Test
  public void exportButtonPublishesAnAnimatedGifAndRetainsItsSource() throws Exception {
    try (TestFixtures f = new TestFixtures()) {
      Uri source = f.video("Export fixture");
      byte[] original = f.bytes(source);
      try (ActivityScenario<MainActivity> screen =
          ActivityScenario.launch(MainActivity.class)) {
        onView(withContentDescription("Export fixture")).perform(longClick());
        onView(withText("Make a GIF")).perform(click());
        onView(withText("Make GIF")).perform(click());
        TestFixtures.await(
            "published GIF",
            () -> DownloadStore.list(f.app).stream().anyMatch(e -> "image/gif".equals(e.mime)));
        TestFixtures.await("finished GIF service", () -> !GifService.isRunning());
        DownloadStore.Entry gif =
            DownloadStore.list(f.app).stream()
                .filter(e -> "image/gif".equals(e.mime))
                .findFirst()
                .get();
        assertEquals("image/gif", f.app.getContentResolver().getType(gif.uri));
        try (Cursor c =
            f.app
                .getContentResolver()
                .query(gif.uri, new String[] {MediaStore.Downloads.IS_PENDING}, null, null, null)) {
          assertNotNull(c);
          assertTrue(c.moveToFirst());
          assertEquals(0, c.getInt(0));
        }
        AnimatedImageDrawable decoded =
            (AnimatedImageDrawable)
                ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(f.app.getContentResolver(), gif.uri));
        assertEquals(360, decoded.getIntrinsicWidth());
        assertEquals(270, decoded.getIntrinsicHeight());
        assertEquals(AnimatedImageDrawable.REPEAT_INFINITE, decoded.getRepeatCount());
        int[] animation = animationMetadata(f.bytes(gif.uri));
        assertEquals(64, animation[0]);
        assertEquals(800, animation[1]);
        assertArrayEquals(original, f.bytes(source));
        assertEquals(2, DownloadStore.list(f.app).size());
        assertTrue(GifService.getStatus().startsWith("GIF saved"));
      }
    }
  }

  @Test
  public void cancelWhileSourceOpeningLeavesNoOutputOrLibraryEntry() throws Exception {
    try (TestFixtures f = new TestFixtures();
        ActivityScenario<MainActivity> screen =
            ActivityScenario.launch(MainActivity.class)) {
      f.app.getContentResolver().call(FixtureControlProvider.BASE, "block", "video", null);
      screen.onActivity(a -> GifService.start(a, document("video"), "Cancelled fixture"));
      TestFixtures.await(
          "blocked running conversion",
          () ->
              GifService.isRunning()
                  && f.app
                          .getContentResolver()
                          .call(FixtureControlProvider.BASE, "state", null, null)
                          .getInt("opened")
                      > 0);
      assertEquals(0, f.publishedFiles());
      assertTrue(DownloadStore.list(f.app).isEmpty());
      TestFixtures.await(
          "Library cancellation action", () -> TestFixtures.hasText(screen, "Cancel GIF export"));
      // Cancellation uses the real Library action, without interacting with a notification.
      onView(withContentDescription("Cancel GIF export")).perform(click());
      f.app.getContentResolver().call(FixtureControlProvider.BASE, "release", null, null);
      TestFixtures.await(
          "cancelled export",
          () ->
              !GifService.isRunning() && "GIF conversion cancelled".equals(GifService.getStatus()));
      assertTrue(DownloadStore.list(f.app).isEmpty());
      assertEquals(0, f.publishedFiles());
      assertEquals(f.originalGifCache, f.gifCacheNames());
    }
  }

  @Test
  public void invalidVideoReportsFailureWithoutPublishing() throws Exception {
    try (TestFixtures f = new TestFixtures();
        ActivityScenario<MainActivity> screen = ActivityScenario.launch(MainActivity.class)) {
      f.app.getContentResolver().call(FixtureControlProvider.BASE, "block", "unsupported", null);
      screen.onActivity(a -> GifService.start(a, document("unsupported"), "Invalid fixture"));
      TestFixtures.await(
          "new conversion source open",
          () ->
              GifService.isRunning()
                  && f.app
                          .getContentResolver()
                          .call(FixtureControlProvider.BASE, "state", null, null)
                          .getInt("opened")
                      > 0);
      f.app.getContentResolver().call(FixtureControlProvider.BASE, "release", null, null);
      TestFixtures.await(
          "new failure result",
          () ->
              !GifService.isRunning()
                  && GifService.getStatus().startsWith("GIF conversion failed:"));
      assertTrue(DownloadStore.list(f.app).isEmpty());
      assertEquals(0, f.publishedFiles());
    }
  }

  @Test
  public void localVideoStartsPausesAndResumes() throws Exception {
    try (TestFixtures f = new TestFixtures()) {
      Uri source = f.video("Playback fixture");
      try (ActivityScenario<PlayerActivity> screen =
          ActivityScenario.launch(
              new Intent(f.app, PlayerActivity.class).setDataAndType(source, "video/mp4"))) {
        TestFixtures.await("video playback", () -> playing(screen));
        screen.moveToState(Lifecycle.State.STARTED);
        assertFalse(playing(screen));
        screen.moveToState(Lifecycle.State.RESUMED);
        TestFixtures.await("resumed video", () -> playing(screen));
      }
    }
  }

  @Test
  public void delayedAnimatedImageDoesNotStartWhilePaused() throws Exception {
    try (TestFixtures f = new TestFixtures()) {
      f.app.getContentResolver().call(FixtureControlProvider.BASE, "block", "animation", null);
      try (ActivityScenario<PlayerActivity> screen =
          ActivityScenario.launch(
              new Intent(f.app, PlayerActivity.class)
                  .setDataAndType(document("animation"), "image/gif"))) {
        TestFixtures.await(
            "image opening gate",
            () ->
                f.app
                        .getContentResolver()
                        .call(FixtureControlProvider.BASE, "state", null, null)
                        .getInt("opened")
                    > 0);
        screen.moveToState(Lifecycle.State.STARTED);
        f.app.getContentResolver().call(FixtureControlProvider.BASE, "release", null, null);
        TestFixtures.await("decoded animated image", () -> animation(screen) != null);
        assertFalse(animation(screen).isRunning());
        screen.moveToState(Lifecycle.State.RESUMED);
        TestFixtures.await("resumed animation", () -> animation(screen).isRunning());
      }
    }
  }

  private static Uri document(String id) {
    return DocumentsContract.buildDocumentUriUsingTree(FixtureDocumentsProvider.TREE, id);
  }

  private static boolean playing(ActivityScenario<PlayerActivity> screen) {
    AtomicBoolean playing = new AtomicBoolean();
    screen.onActivity(a -> playing.set(((VideoView) TestFixtures.field(a, "video")).isPlaying()));
    return playing.get();
  }

  private static AnimatedImageDrawable animation(ActivityScenario<PlayerActivity> screen) {
    java.util.concurrent.atomic.AtomicReference<AnimatedImageDrawable> result =
        new java.util.concurrent.atomic.AtomicReference<>();
    screen.onActivity(a -> result.set((AnimatedImageDrawable) TestFixtures.field(a, "animation")));
    return result.get();
  }

  /** Walk GIF blocks, counting image frames and accumulated graphic-control delays. */
  private static int[] animationMetadata(byte[] bytes) {
    assertTrue(bytes.length > 13);
    assertEquals("GIF89a", new String(bytes, 0, 6, java.nio.charset.StandardCharsets.US_ASCII));
    int position = 13, frames = 0, delay = 0;
    if ((bytes[10] & 128) != 0) position += 3 * (1 << ((bytes[10] & 7) + 1));
    while (position < bytes.length) {
      int type = bytes[position++] & 255;
      if (type == 0x3b) return new int[] {frames, delay};
      if (type == 0x21) {
        int label = bytes[position++] & 255;
        if (label == 0xf9) {
          assertEquals(4, bytes[position] & 255);
          delay += (bytes[position + 2] & 255) | ((bytes[position + 3] & 255) << 8);
        }
      } else if (type == 0x2c) {
        frames++;
        int packed = bytes[position + 8] & 255;
        position += 9;
        if ((packed & 128) != 0) position += 3 * (1 << ((packed & 7) + 1));
        position++; // LZW minimum code size.
      } else {
        fail("Unexpected GIF block: " + type);
      }
      int count;
      while ((count = bytes[position++] & 255) != 0) position += count;
    }
    throw new AssertionError("Missing GIF trailer");
  }
}
