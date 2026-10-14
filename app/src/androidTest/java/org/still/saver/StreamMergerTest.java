// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.still.saver.core.VideoSupport;

/** Merges DASH-style fragmented tracks, as YouTube serves them, with Android's own muxer. */
@RunWith(AndroidJUnit4.class)
public final class StreamMergerTest {
  private static final String MP4 = "video/mp4";
  private static final String WEBM = "video/webm";
  private final Context test = InstrumentationRegistry.getInstrumentation().getContext();
  private final File directory =
      new File(
          InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
          "merge-test");

  @After
  public void cleanUp() {
    File[] files = directory.listFiles();
    if (files != null) for (File file : files) file.delete();
    directory.delete();
  }

  @Test
  public void mergesFragmentedH264VideoAndAacAudioIntoOnePlayableMp4FromAndroid10()
      throws Exception {
    if (!mergesH264()) return;
    File output = new File(directory, "merged.mp4");
    File video = asset("split-video.mp4");
    File audio = asset("split-audio.m4a");
    StreamMerger.merge(video, audio, output, MP4, () -> false);
    assertMerged(
        output, video, audio, MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_AUDIO_AAC);
  }

  /**
   * The AAC encoder's priming samples sit before zero on Android versions that apply edit lists.
   */
  @Test
  public void keepsAudioSamplesThatStartBeforeZero() throws Exception {
    if (!mergesH264()) return;
    File output = new File(directory, "primed.mp4");
    File video = asset("split-video.mp4");
    File audio = asset("primed-audio.m4a");
    StreamMerger.merge(video, audio, output, MP4, () -> false);
    assertMerged(
        output, video, audio, MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_AUDIO_AAC);
  }

  @Test
  public void mergesAv1VideoAndAacAudioIntoOnePlayableMp4FromAndroid14() throws Exception {
    boolean muxes =
        DeviceVideoSupport.get().canMerge(VideoSupport.MP4, VideoSupport.AV1, VideoSupport.AAC);
    assertEquals(Build.VERSION.SDK_INT >= 34, muxes);
    if (!muxes) return;
    File output = new File(directory, "merged-av1.mp4");
    File video = asset("split-video-av1.mp4");
    File audio = asset("split-audio.m4a");
    StreamMerger.merge(video, audio, output, MP4, () -> false);
    assertMerged(
        output, video, audio, MediaFormat.MIMETYPE_VIDEO_AV1, MediaFormat.MIMETYPE_AUDIO_AAC);
  }

  @Test
  public void mergesVp9VideoAndOpusAudioIntoOnePlayableWebmFromAndroid10() throws Exception {
    boolean muxes =
        DeviceVideoSupport.get().canMerge(VideoSupport.WEBM, VideoSupport.VP9, VideoSupport.OPUS);
    assertEquals(Build.VERSION.SDK_INT >= 29, muxes);
    if (!muxes) return;
    File output = new File(directory, "merged.webm");
    File video = asset("split-video.webm");
    File audio = asset("split-audio.webm");
    StreamMerger.merge(video, audio, output, WEBM, () -> false);

    byte[] magic = new byte[4];
    try (InputStream in = new FileInputStream(output)) {
      assertEquals(4, in.read(magic));
    }
    assertArrayEquals("EBML header", new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3}, magic);
    assertMerged(
        output, video, audio, MediaFormat.MIMETYPE_VIDEO_VP9, MediaFormat.MIMETYPE_AUDIO_OPUS);
  }

  @Test
  public void stopsAndRemovesTheOutputWhenCancelled() throws Exception {
    if (!mergesH264()) return;
    File video = asset("split-video.mp4");
    File audio = asset("split-audio.m4a");
    File output = new File(directory, "cancelled.mp4");
    try {
      StreamMerger.merge(video, audio, output, MP4, () -> true);
      fail("Merged after cancellation");
    } catch (StreamMerger.Cancelled expected) {
      /* expected */
    }
    assertFalse("Cancelled output left behind", output.exists());

    AtomicInteger checks = new AtomicInteger();
    try {
      StreamMerger.merge(video, audio, output, MP4, () -> checks.incrementAndGet() > 1);
      fail("Merged after cancellation partway through");
    } catch (StreamMerger.Cancelled expected) {
      /* expected */
    }
    assertEquals("Kept merging after the cancellation was seen", 2, checks.get());
    assertFalse("Cancelled output left behind", output.exists());
  }

  @Test
  public void rejectsInputsWithoutTheExpectedTracks() throws Exception {
    File video = asset("split-video.mp4");
    File output = new File(directory, "rejected.mp4");
    try {
      StreamMerger.merge(video, video, output, MP4, () -> false);
      fail("Merged a video file as the audio track");
    } catch (StreamMerger.Unmergeable expected) {
      assertTrue(
          String.valueOf(expected.getCause()),
          String.valueOf(expected.getCause()).contains("audio"));
    }
    File text = new File(directory, "not-media.m4a");
    try (OutputStream out = new FileOutputStream(text)) {
      out.write("not media".getBytes("UTF-8"));
    }
    try {
      StreamMerger.merge(video, text, output, MP4, () -> false);
      fail("Merged a text file");
    } catch (StreamMerger.Unmergeable expected) {
      /* expected */
    }
    File webm = new File(directory, "rejected.webm");
    try {
      StreamMerger.merge(video, asset("split-audio.m4a"), webm, WEBM, () -> false);
      fail("Wrote H.264 and AAC into WebM");
    } catch (StreamMerger.Unmergeable expected) {
      /* expected */
    }
    assertFalse("Rejected output left behind", output.exists());
    assertFalse("Rejected output left behind", webm.exists());
  }

  /** Android 7.0's MP4 writer rejects the B-frames in these H.264 assets, as in YouTube's. */
  private static boolean mergesH264() {
    boolean muxes =
        DeviceVideoSupport.get().canMerge(VideoSupport.MP4, VideoSupport.H264, VideoSupport.AAC);
    assertEquals(Build.VERSION.SDK_INT >= 29, muxes);
    return muxes;
  }

  /**
   * Each output track must hold every sample of its input; a track can exist yet be empty, which
   * {@code METADATA_KEY_HAS_AUDIO} does not notice.
   */
  private static void assertMerged(
      File output, File video, File audio, String videoMime, String audioMime) throws Exception {
    Map<String, Integer> merged = samples(output);
    assertEquals(merged.toString(), 2, merged.size());
    int videoSamples = samples(video).get(videoMime);
    int audioSamples = samples(audio).get(audioMime);
    assertTrue("No input samples", videoSamples > 0 && audioSamples > 0);
    assertEquals("Video samples", Integer.valueOf(videoSamples), merged.get(videoMime));
    assertEquals("Audio samples", Integer.valueOf(audioSamples), merged.get(audioMime));
    MediaMetadataRetriever metadata = new MediaMetadataRetriever();
    try {
      metadata.setDataSource(output.getAbsolutePath());
      assertEquals("yes", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
      assertEquals(
          "320", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
      assertEquals(
          "240", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
      long duration =
          Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
      assertTrue("Duration " + duration, duration >= 2800 && duration <= 3300);
      Bitmap frame = metadata.getFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
      assertNotNull("Android could not decode a merged frame", frame);
      frame.recycle();
    } finally {
      metadata.release();
    }
  }

  /** Samples per track MIME type, read back the way a player would. */
  private static Map<String, Integer> samples(File file) throws IOException {
    MediaExtractor extractor = new MediaExtractor();
    try {
      extractor.setDataSource(file.getAbsolutePath());
      for (int i = 0; i < extractor.getTrackCount(); i++) extractor.selectTrack(i);
      Map<String, Integer> counts = new HashMap<>();
      Map<String, Long> first = new HashMap<>();
      for (int track; (track = extractor.getSampleTrackIndex()) >= 0; extractor.advance()) {
        String mime = extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME);
        Integer count = counts.get(mime);
        counts.put(mime, count == null ? 1 : count + 1);
        if (!first.containsKey(mime)) first.put(mime, extractor.getSampleTime());
      }
      Log.i("StillMergeTest", file.getName() + " samples " + counts + " first times " + first);
      return counts;
    } finally {
      extractor.release();
    }
  }

  private File asset(String name) throws IOException {
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("No test directory");
    File file = new File(directory, name);
    try (InputStream in = test.getAssets().open(name);
        OutputStream out = new FileOutputStream(file)) {
      byte[] buffer = new byte[16384];
      for (int count; (count = in.read(buffer)) != -1; ) out.write(buffer, 0, count);
    }
    return file;
  }
}
