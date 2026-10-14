// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import static org.junit.Assert.*;

import android.app.DownloadManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.still.saver.core.Media;
import org.still.saver.core.Post;
import org.still.saver.core.PublicPageResolver;
import org.still.saver.core.Variant;

/**
 * Opt-in public-network probes for photo, carousel, and GIF posts. Every item (up to four) is
 * downloaded through the app's queue and decoded by Android: photos as bitmaps, videos as frames.
 */
@RunWith(AndroidJUnit4.class)
public final class LivePostDownloadTest {
  @Test
  public void xPhotosAndVideoInOnePost() throws Exception {
    verify("x-mixed", "video,photo,photo", "https://x.com/i/status/1623739803874349067");
  }

  @Test
  public void xGif() throws Exception {
    verify("x-gif", "gif", "https://x.com/i/status/1575199173472927762");
  }

  @Test
  public void instagramPhotoCarousel() throws Exception {
    verify(
        "instagram-carousel",
        "photo,photo",
        "https://www.instagram.com/p/DePlAyED5AA/?rpxt=MzRlODBiNWFlZA==");
  }

  @Test
  public void blueskyPhoto() throws Exception {
    verify("bluesky-photo", "photo", "https://bsky.app/profile/bsky.app/post/3mv3shqdfuc2e");
  }

  @Test
  public void pinterestImagePin() throws Exception {
    verify(
        "pinterest-photo",
        "photo",
        "https://in.pinterest.com/pin/chewy-chocolate-chip-cookies-a-picture-recipe--560276009885574297/");
  }

  @Test
  public void tiktokPhotoSlideshow() throws Exception {
    verify(
        "tiktok-photo",
        "photo,photo,photo",
        "https://www.tiktok.com/@anamiriamyepez/photo/7498924643227536645");
  }

  @Test
  public void threadsPhotoCarousel() throws Exception {
    verify(
        "threads-photos",
        "photo,photo,photo,photo,photo,photo,photo",
        "https://www.threads.com/@nasa/post/DeSYGIdFUvp");
  }

  @Test
  public void facebookPublisherVideo() throws Exception {
    verify("facebook-publisher", "video", "https://www.facebook.com/cnn/videos/10155529876156509/");
  }

  /** {@code kinds} lists every item the post must contain, in order. */
  private void verify(String name, String kinds, String source) throws Exception {
    Assume.assumeTrue(
        "Enable with the instrumentation argument livePlatforms=true",
        "true".equals(InstrumentationRegistry.getArguments().getString("livePlatforms")));
    try (TestFixtures fixture = new TestFixtures(2)) {
      Context context = fixture.app;
      boolean originalWifi = DownloadStore.prefs(context).getBoolean("wifi", false);
      JSONObject result = new JSONObject().put("platform", name).put("source", source);
      JSONArray items = new JSONArray();
      result.put("items", items);
      long started = SystemClock.elapsedRealtime();
      try {
        Post post = new PublicPageResolver(context, "https://api.fxtwitter.com").resolve(source);
        StringBuilder found = new StringBuilder();
        for (Media media : post.media) found.append(found.length() == 0 ? "" : ",").append(media.kind);
        result.put("kinds", found.toString()).put("author", post.author);
        assertEquals("Media kinds", kinds, found.toString());
        DownloadStore.prefs(context).edit().putBoolean("wifi", false).commit();
        Post probe =
            new Post(
                UUID.randomUUID().toString(), "Live " + name, post.author, post.sourceUrl, post.media);
        for (int i = 0; i < Math.min(4, post.media.size()); i++) {
          Media media = post.media.get(i);
          Variant variant = media.chooseVariant("highest");
          JSONObject item =
              new JSONObject().put("kind", media.kind).put("mime", variant.mimeType);
          items.put(item);
          assertTrue("Queue rejected item " + i, DownloadStore.enqueue(context, probe, i, variant));
          DownloadStore.Entry admitted = DownloadStore.list(context).get(0);
          try {
            DownloadStore.Entry saved = awaitDownload(context, admitted.key);
            File copy = new File(context.getFilesDir(), "live-" + name + ".media");
            long bytes = copy(context, saved, copy);
            assertTrue("Empty/invalid payload for item " + i, bytes > 1024);
            item.put("bytes", bytes).put("saved_mime", saved.mime);
            if (variant.mimeType.startsWith("image/")) {
              BitmapFactory.Options bounds = new BitmapFactory.Options();
              bounds.inJustDecodeBounds = true;
              BitmapFactory.decodeFile(copy.getAbsolutePath(), bounds);
              assertTrue(
                  "Android could not decode image " + i, bounds.outWidth > 0 && bounds.outHeight > 0);
              item.put("width", bounds.outWidth).put("height", bounds.outHeight);
            } else {
              MediaMetadataRetriever metadata = new MediaMetadataRetriever();
              try {
                metadata.setDataSource(copy.getAbsolutePath());
                Bitmap frame =
                    metadata.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                assertNotNull("Android could not decode a frame of item " + i, frame);
                item.put("width", frame.getWidth()).put("height", frame.getHeight());
                frame.recycle();
              } finally {
                metadata.release();
              }
            }
            item.put("passed", true);
            assertTrue(copy.delete());
          } finally {
            DownloadStore.remove(context, admitted, true);
          }
        }
        result.put("passed", true);
      } catch (Exception | AssertionError failure) {
        result.put("passed", false).put("error", failure.toString());
        throw failure;
      } finally {
        DownloadStore.prefs(context).edit().putBoolean("wifi", originalWifi).commit();
        result.put("elapsed_ms", SystemClock.elapsedRealtime() - started);
        try (FileOutputStream report =
            new FileOutputStream(new File(context.getFilesDir(), "live-" + name + ".json"))) {
          report.write(new JSONArray().put(result).toString(2).getBytes(StandardCharsets.UTF_8));
        }
        Log.i("StillLiveProbe", result.toString());
      }
    }
  }

  private static long copy(Context context, DownloadStore.Entry saved, File copy) throws Exception {
    long bytes = 0;
    try (InputStream input = context.getContentResolver().openInputStream(saved.uri);
        FileOutputStream output = new FileOutputStream(copy)) {
      assertNotNull(input);
      byte[] buffer = new byte[32768];
      int count;
      while ((count = input.read(buffer)) != -1) {
        output.write(buffer, 0, count);
        bytes += count;
      }
    }
    return bytes;
  }

  private static DownloadStore.Entry awaitDownload(Context context, String key) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 90_000;
    while (SystemClock.elapsedRealtime() < deadline) {
      DownloadStore.Entry current = null;
      for (DownloadStore.Entry row : DownloadStore.list(context))
        if (key.equals(row.key)) current = row;
      assertNotNull("Download record disappeared", current);
      if (current.status == DownloadManager.STATUS_SUCCESSFUL) return current;
      assertNotEquals(
          "Download failed (reason " + current.reason + "): " + current.description(),
          DownloadManager.STATUS_FAILED,
          current.status);
      Thread.sleep(250);
    }
    throw new AssertionError("Download did not finish within 90 seconds");
  }
}
