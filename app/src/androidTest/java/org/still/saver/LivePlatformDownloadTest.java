// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import static org.junit.Assert.*;

import android.app.DownloadManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.still.saver.core.FacebookEmbedResolver;
import org.still.saver.core.Post;
import org.still.saver.core.PublicPageResolver;
import org.still.saver.core.Variant;

/**
 * Opt-in end-to-end public-network probes. Pass livePlatforms=true to the instrumentation runner.
 * Failures are recorded rather than skipped; the service or public sample may have changed. Each
 * success requires a real downloaded video, readable metadata, and a decoded frame.
 */
@RunWith(AndroidJUnit4.class)
public final class LivePlatformDownloadTest {
  @Test
  public void x() throws Exception {
    verify("x", "https://x.com/i/status/1841206275088290279");
  }

  @Test
  public void bluesky() throws Exception {
    verify("bluesky", "https://bsky.app/profile/bsky.app/post/3l3vgf77uco2g");
  }

  @Test
  public void instagram() throws Exception {
    verify(
        "instagram",
        "https://www.instagram.com/reel/Chunk8-jurw/",
        "https://www.instagram.com/reels/Cop84x6u7CP/");
  }

  @Test
  public void tiktok() throws Exception {
    verify(
        "tiktok",
        "https://www.tiktok.com/@hankgreen1/video/7047596209028074758",
        "https://www.tiktok.com/@pokemonlife22/video/7059698374567611694");
  }

  @Test
  public void facebook() throws Exception {
    verify(
        "facebook",
        "https://www.facebook.com/reel/1195289147628387",
        "https://www.facebook.com/cnn/videos/10155529876156509/");
  }

  @Test
  public void facebookVideo() throws Exception {
    verify("facebook-video", "https://www.facebook.com/100033620354545/videos/106560053808006/");
  }

  @Test
  public void facebookEmbed() throws Exception {
    verify(
        "facebook-embed", true, "https://www.facebook.com/100033620354545/videos/106560053808006/");
  }

  @Test
  public void facebookLegacyRoute() throws Exception {
    verify("facebook-legacy", "https://www.facebook.com/video.php?v=106560053808006");
  }

  @Test
  public void threads() throws Exception {
    verify("threads", "https://www.threads.com/@zuck/post/DHV7vTivqWD");
  }

  @Test
  public void threadsAudio() throws Exception {
    verify("threads-audio", "https://www.threads.com/@zuck/post/DMF6uUgx9f9");
  }

  @Test
  public void pinterest() throws Exception {
    verify(
        "pinterest",
        "https://www.pinterest.com/pin/664281013778109217/",
        "https://www.pinterest.com/pin/1084663891475263837/");
  }

  private void verify(String platform, String... candidates) throws Exception {
    verify(platform, false, candidates);
  }

  private void verify(String platform, boolean facebookEmbed, String... candidates)
      throws Exception {
    Assume.assumeTrue(
        "Enable with the instrumentation argument livePlatforms=true",
        "true".equals(InstrumentationRegistry.getArguments().getString("livePlatforms")));
    try (TestFixtures fixture = new TestFixtures(2)) {
      verifyInFixture(platform, facebookEmbed, candidates, fixture.app);
    }
  }

  private void verifyInFixture(
      String platform, boolean facebookEmbed, String[] candidates, Context context)
      throws Exception {
    boolean originalWifi = DownloadStore.prefs(context).getBoolean("wifi", false);
    String override = InstrumentationRegistry.getArguments().getString("postUrl");
    if (override != null) candidates = new String[] {override};
    String quality = InstrumentationRegistry.getArguments().getString("qualityChoice", "highest");
    JSONArray attempts = new JSONArray();
    boolean passed = false;
    for (String source : candidates) {
      long started = SystemClock.elapsedRealtime();
      JSONObject result =
          new JSONObject()
              .put("platform", platform)
              .put("source", source)
              .put("quality", quality)
              .put("resolver", facebookEmbed ? "public-facebook-embed" : "application");
      attempts.put(result);
      DownloadStore.Entry admitted = null;
      try {
        Post post =
            facebookEmbed
                ? new FacebookEmbedResolver().resolve(source)
                : new PublicPageResolver(context, "https://api.fxtwitter.com").resolve(source);
        assertFalse("No media resolved", post.media.isEmpty());
        Variant variant = post.media.get(0).chooseVariant(quality);
        result
            .put("attachments", post.media.size())
            .put("mime", variant.mimeType)
            .put("variant", variant.label);
        assertTrue("Expected video", variant.mimeType.startsWith("video/"));
        DownloadStore.prefs(context).edit().putBoolean("wifi", false).commit();
        // Use distinct local metadata so an earlier probe cannot suppress this admission.
        Post probe =
            new Post(
                UUID.randomUUID().toString(),
                "Live " + platform,
                post.author,
                post.sourceUrl,
                post.media);
        assertTrue("Queue rejected media", DownloadStore.enqueue(context, probe, 0, variant));
        admitted = DownloadStore.list(context).get(0);
        assertTrue("Transfer was not admitted", admitted.id > 0 || admitted.id == -3);
        result.put("transport", admitted.id == -3 ? "scoped-https" : "DownloadManager");
        DownloadStore.Entry saved = awaitDownload(context, admitted.key);
        File copy = new File(context.getFilesDir(), "live-" + platform + ".media");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long bytes = 0;
        try (InputStream input = context.getContentResolver().openInputStream(saved.uri);
            FileOutputStream output = new FileOutputStream(copy)) {
          assertNotNull(input);
          byte[] buffer = new byte[32768];
          int count;
          while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
            digest.update(buffer, 0, count);
            bytes += count;
          }
        }
        assertTrue("Empty/invalid media payload", bytes > 1024);
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
          metadata.setDataSource(copy.getAbsolutePath());
          long duration =
              Long.parseLong(
                  metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
          int width =
              Integer.parseInt(
                  metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
          int height =
              Integer.parseInt(
                  metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
          assertTrue("No video duration", duration > 0);
          assertTrue("No video dimensions", width > 0 && height > 0);
          Bitmap frame = metadata.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
          assertNotNull("Android could not decode a video frame", frame);
          frame.recycle();
          StringBuilder sha = new StringBuilder();
          for (byte value : digest.digest())
            sha.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
          result
              .put("passed", true)
              .put("bytes", bytes)
              .put("duration_ms", duration)
              .put("width", width)
              .put("height", height)
              .put("sha256", sha.toString());
          passed = true;
        } finally {
          metadata.release();
        }
      } catch (Exception | AssertionError failure) {
        result.put("passed", false).put("error", failure.toString());
        Throwable cause = failure.getCause();
        if (cause != null) result.put("cause", cause.toString());
      } finally {
        if (admitted != null) DownloadStore.remove(context, admitted, true);
        DownloadStore.prefs(context).edit().putBoolean("wifi", originalWifi).commit();
        result.put("elapsed_ms", SystemClock.elapsedRealtime() - started);
        try (FileOutputStream report =
            new FileOutputStream(new File(context.getFilesDir(), "live-" + platform + ".json"))) {
          report.write(attempts.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        Log.i("StillLiveProbe", result.toString());
      }
      if (passed) break;
    }
    assertTrue("No verified " + platform + " download: " + attempts, passed);
  }

  private DownloadStore.Entry awaitDownload(Context context, String key) throws Exception {
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
