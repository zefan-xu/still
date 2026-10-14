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

  @Test
  public void viewerZoomsAndSwipesBetweenSavedImagesAndVideos() throws Exception {
    try (TestFixtures f = new TestFixtures()) {
      Uri first = f.image("First photo");
      Uri second = f.video("Second video");
      Uri third = f.image("Third photo");
      DownloadStore.Entry newest = DownloadStore.list(f.app).get(0);
      assertEquals(third, newest.uri);
      try (ActivityScenario<PlayerActivity> screen =
          ActivityScenario.launch(
              new Intent(f.app, PlayerActivity.class)
                  .setDataAndType(newest.uri, newest.mime)
                  .putExtra("title", newest.title)
                  .putExtra("entry", newest.key))) {
        screen.onActivity(
            a -> {
              assertEquals(3, a.pageCount());
              assertEquals(0, a.currentIndex());
              assertEquals(third, a.currentUri());
              assertEquals(1f, a.currentScale(), 0.01f);
            });
        // Pinch-to-zoom on the image, pan while zoomed, and double-tap to reset.
        screen.onActivity(MediaWorkflowTest::pinchOut);
        screen.onActivity(a -> assertTrue("Pinch should zoom in", a.currentScale() > 1.5f));
        screen.onActivity(MediaWorkflowTest::dragHorizontally);
        screen.onActivity(
            a -> {
              assertEquals("Pan while zoomed should stay on current page", 0, a.currentIndex());
              assertNotEquals("Pan while zoomed should translate image", 0f, a.currentPanX(), 1f);
            });
        screen.onActivity(MediaWorkflowTest::doubleTapCenter);
        screen.onActivity(
            a -> {
              assertEquals(1f, a.currentScale(), 0.01f);
              assertEquals(0f, a.currentPanX(), 0.5f);
            });
        // Double-tap zooms in, and a second double-tap resets back to 1x.
        screen.onActivity(MediaWorkflowTest::doubleTapCenter);
        screen.onActivity(a -> assertEquals(2.5f, a.currentScale(), 0.05f));
        screen.onActivity(MediaWorkflowTest::doubleTapCenter);
        screen.onActivity(a -> assertEquals(1f, a.currentScale(), 0.01f));

        // Swipe left from the newest image to the video, verify playback and video zoom, then swipe
        // on to the oldest image and back.
        screen.onActivity(a -> swipePage(a, true));
        TestFixtures.await("swiped video playback", () -> playing(screen));
        screen.onActivity(
            a -> {
              assertEquals(1, a.currentIndex());
              assertEquals(second, a.currentUri());
            });
        screen.onActivity(MediaWorkflowTest::doubleTapCenter);
        screen.onActivity(a -> assertEquals(2.5f, a.currentScale(), 0.05f));
        screen.onActivity(MediaWorkflowTest::doubleTapCenter);
        screen.onActivity(a -> assertEquals(1f, a.currentScale(), 0.01f));

        screen.onActivity(a -> swipePage(a, true));
        screen.onActivity(
            a -> {
              assertEquals(2, a.currentIndex());
              assertEquals(first, a.currentUri());
              assertNull(TestFixtures.field(a, "video"));
            });
        screen.onActivity(a -> swipePage(a, false));
        TestFixtures.await("resumed video after swiping back", () -> playing(screen));
        screen.onActivity(
            a -> {
              assertEquals(1, a.currentIndex());
              assertEquals(second, a.currentUri());
            });
      }
    }
  }

  private static void pinchOut(PlayerActivity activity) {
    android.view.View stage = activity.stageView();
    float cx = stage.getWidth() / 2f, cy = stage.getHeight() / 2f;
    long downTime = android.os.SystemClock.uptimeMillis();
    android.view.MotionEvent.PointerProperties[] props =
        new android.view.MotionEvent.PointerProperties[] {
          new android.view.MotionEvent.PointerProperties(),
          new android.view.MotionEvent.PointerProperties()
        };
    props[0].id = 0;
    props[0].toolType = android.view.MotionEvent.TOOL_TYPE_FINGER;
    props[1].id = 1;
    props[1].toolType = android.view.MotionEvent.TOOL_TYPE_FINGER;
    android.view.MotionEvent.PointerCoords[] coords =
        new android.view.MotionEvent.PointerCoords[] {
          new android.view.MotionEvent.PointerCoords(),
          new android.view.MotionEvent.PointerCoords()
        };
    coords[0].x = cx - 100f;
    coords[0].y = cy;
    coords[0].pressure = 1f;
    coords[1].x = cx + 100f;
    coords[1].y = cy;
    coords[1].pressure = 1f;
    dispatch(
        stage,
        android.view.MotionEvent.obtain(
            downTime,
            downTime,
            android.view.MotionEvent.ACTION_DOWN,
            1,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN,
            0));
    dispatch(
        stage,
        android.view.MotionEvent.obtain(
            downTime,
            downTime + 10,
            android.view.MotionEvent.ACTION_POINTER_DOWN
                | (1 << android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN,
            0));
    for (int i = 1; i <= 8; i++) {
      coords[0].x = cx - 100f - i * 25f;
      coords[1].x = cx + 100f + i * 25f;
      dispatch(
          stage,
          android.view.MotionEvent.obtain(
              downTime,
              downTime + 10 + i * 16L,
              android.view.MotionEvent.ACTION_MOVE,
              2,
              props,
              coords,
              0,
              0,
              1f,
              1f,
              0,
              0,
              android.view.InputDevice.SOURCE_TOUCHSCREEN,
              0));
    }
    dispatch(
        stage,
        android.view.MotionEvent.obtain(
            downTime,
            downTime + 150,
            android.view.MotionEvent.ACTION_POINTER_UP
                | (1 << android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN,
            0));
    dispatch(
        stage,
        android.view.MotionEvent.obtain(
            downTime,
            downTime + 160,
            android.view.MotionEvent.ACTION_UP,
            1,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN,
            0));
  }

  private static void doubleTapCenter(PlayerActivity activity) {
    android.view.View stage = activity.stageView();
    float cx = stage.getWidth() / 2f, cy = stage.getHeight() / 2f;
    long t = android.os.SystemClock.uptimeMillis();
    dispatch(stage, android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, cx, cy, 0));
    dispatch(stage, android.view.MotionEvent.obtain(t, t + 10, android.view.MotionEvent.ACTION_UP, cx, cy, 0));
    dispatch(stage, android.view.MotionEvent.obtain(t + 60, t + 60, android.view.MotionEvent.ACTION_DOWN, cx, cy, 0));
    dispatch(stage, android.view.MotionEvent.obtain(t + 60, t + 70, android.view.MotionEvent.ACTION_UP, cx, cy, 0));
  }

  private static void dragHorizontally(PlayerActivity activity) {
    android.view.View stage = activity.stageView();
    float cx = stage.getWidth() / 2f, cy = stage.getHeight() / 2f;
    long t = android.os.SystemClock.uptimeMillis();
    dispatch(stage, android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, cx, cy, 0));
    for (int i = 1; i <= 5; i++) {
      dispatch(
          stage,
          android.view.MotionEvent.obtain(
              t, t + i * 16L, android.view.MotionEvent.ACTION_MOVE, cx - i * 30f, cy, 0));
    }
    dispatch(
        stage,
        android.view.MotionEvent.obtain(
            t, t + 100, android.view.MotionEvent.ACTION_UP, cx - 150f, cy, 0));
  }

  private static void swipePage(PlayerActivity activity, boolean toNext) {
    android.view.View stage = activity.stageView();
    float w = stage.getWidth(), cy = stage.getHeight() / 2f;
    float startX = toNext ? w * 0.85f : w * 0.15f;
    float endX = toNext ? w * 0.15f : w * 0.85f;
    long t = android.os.SystemClock.uptimeMillis();
    dispatch(
        stage,
        android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, startX, cy, 0));
    for (int i = 1; i <= 8; i++) {
      float x = startX + (endX - startX) * (i / 8f);
      dispatch(
          stage,
          android.view.MotionEvent.obtain(
              t, t + i * 16L, android.view.MotionEvent.ACTION_MOVE, x, cy, 0));
    }
    dispatch(
        stage,
        android.view.MotionEvent.obtain(
            t, t + 140, android.view.MotionEvent.ACTION_UP, endX, cy, 0));
  }

  private static void dispatch(android.view.View view, android.view.MotionEvent event) {
    try {
      view.dispatchTouchEvent(event);
    } finally {
      event.recycle();
    }
  }

  private static Uri document(String id) {
    return DocumentsContract.buildDocumentUriUsingTree(FixtureDocumentsProvider.TREE, id);
  }

  private static boolean playing(ActivityScenario<PlayerActivity> screen) {
    AtomicBoolean playing = new AtomicBoolean();
    screen.onActivity(
        a -> {
          VideoView video = (VideoView) TestFixtures.field(a, "video");
          playing.set(video != null && video.isPlaying());
        });
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
