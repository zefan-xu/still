// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import static org.junit.Assert.*;

import android.app.DownloadManager;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.still.saver.core.Post;
import org.still.saver.core.PublicPageResolver;
import org.still.saver.core.Variant;

/** Offline native-version check and two opt-in live smoke checks on an isolated emulator. */
@RunWith(AndroidJUnit4.class)
public final class DeviceSmokeTest {
  private Context context() {
    return InstrumentationRegistry.getInstrumentation().getTargetContext();
  }

  @Test
  public void bundledPythonStartsAndRunsYtDlp() throws Exception {
    YoutubeDLRequest request =
        new YoutubeDLRequest("https://bsky.app/profile/bsky.app/post/3l3vgf77uco2g");
    request.addOption("--version");
    String version =
        ExtractorRuntime.execute(context(), request, "still-version-smoke").getOut().trim();
    assertTrue(
        "Expected a yt-dlp version, got: " + version,
        version.matches("\\d{4}\\.\\d{2}\\.\\d{2}.*"));
    assertTrue(
        "Cached extractor was not upgraded",
        version.compareTo(ExtractorRuntime.BUNDLED_VERSION) >= 0);
  }

  @Test
  public void extractsPublicBlueskyVideoOnDevice() throws Exception {
    liveOnly();
    Post post =
        new PublicPageResolver(context(), "https://api.fxtwitter.com")
            .resolve("https://bsky.app/profile/bsky.app/post/3l3vgf77uco2g");
    assertFalse(post.media.isEmpty());
    Variant video = post.media.get(0).chooseVariant("best");
    assertTrue(video.url.startsWith("https://"));
    assertEquals("video/mp4", video.mimeType);
  }

  @Test
  public void resolvesAndSavesPublicTwitterVideoUsingAndroid() throws Exception {
    liveOnly();
    try (TestFixtures fixture = new TestFixtures(2)) {
      savesPublicTwitterVideo(fixture.app);
    }
  }

  private void savesPublicTwitterVideo(Context c) throws Exception {
    Post post =
        new PublicPageResolver(c, "https://api.fxtwitter.com")
            .resolve("https://x.com/i/status/1841206275088290279");
    assertFalse(post.media.isEmpty());
    Variant video = post.media.get(0).chooseVariant("smallest");
    assertEquals("video/mp4", video.mimeType);
    boolean originalWifi = DownloadStore.prefs(c).getBoolean("wifi", false);
    DownloadStore.prefs(c).edit().putBoolean("wifi", false).commit();
    assertTrue(DownloadStore.enqueue(c, post, 0, video));
    DownloadStore.Entry added = DownloadStore.list(c).get(0);
    try {
      assertTrue(added.id > 0);
      long deadline = System.currentTimeMillis() + 90_000;
      while (System.currentTimeMillis() < deadline) {
        DownloadStore.Entry current = null;
        for (DownloadStore.Entry row : DownloadStore.list(c))
          if (row.key.equals(added.key)) current = row;
        assertNotNull(current);
        if (current.status == DownloadManager.STATUS_SUCCESSFUL) {
          assertNotNull(current.uri);
          try (java.io.InputStream input = c.getContentResolver().openInputStream(current.uri)) {
            byte[] header = new byte[12];
            assertEquals(12, input.read(header));
            assertEquals(
                "ftyp", new String(header, 4, 4, java.nio.charset.StandardCharsets.US_ASCII));
          }
          // A Restart dialog may hold a paused snapshot after Android completes the file.
          added.status = DownloadManager.STATUS_PAUSED;
          try {
            DownloadStore.retry(c, added);
            fail("Restarting a completed transfer must keep the saved file");
          } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("finished"));
          }
          // The removal menu may also hold that snapshot.
          DownloadStore.remove(c, added, false);
          assertFalse(DownloadStore.list(c).stream().anyMatch(row -> row.key.equals(added.key)));
          try (java.io.InputStream kept = c.getContentResolver().openInputStream(current.uri)) {
            assertNotNull("Removing history must retain the completed file", kept);
            assertTrue(kept.read() >= 0);
          }
          return;
        }
        assertNotEquals(
            "Download failed: " + current.description(),
            DownloadManager.STATUS_FAILED,
            current.status);
        Thread.sleep(500);
      }
      fail("The public video did not finish downloading within 90 seconds");
    } finally {
      // Own DownloadManager ID remains available after the history-retention assertion.
      for (DownloadStore.Entry entry : DownloadStore.list(c))
        if (entry.key.equals(added.key)) DownloadStore.remove(c, entry, true);
      ((DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE)).remove(added.id);
      DownloadStore.prefs(c).edit().putBoolean("wifi", originalWifi).commit();
    }
  }

  private static void liveOnly() {
    Assume.assumeTrue(
        "Enable livePlatforms=true",
        "true".equals(InstrumentationRegistry.getArguments().getString("livePlatforms")));
  }
}
