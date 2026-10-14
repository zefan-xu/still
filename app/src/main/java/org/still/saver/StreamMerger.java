// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;

/**
 * Copies one video and one audio track into a single MP4, or WebM for VP9 and Opus, without
 * re-encoding either.
 */
final class StreamMerger {
  private static final int MIN_BUFFER_BYTES = 1024 * 1024;
  private static final int MAX_BUFFER_BYTES = 64 * 1024 * 1024;

  /** Samples copied between cancellation checks; even a 4K frame is well under a megabyte. */
  private static final int CHECK_EVERY = 32;

  /**
   * The streams can't be read or written into the container, so another quality may work.
   * MediaMuxer reports a full disk the same way.
   */
  static final class Unmergeable extends IOException {
    Unmergeable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** {@code cancelled} returned true; the partial output must be discarded. */
  static final class Cancelled extends IOException {
    Cancelled() {
      super("Combining video and audio was cancelled");
    }
  }

  private StreamMerger() {}

  /**
   * Samples are written in timestamp order so the output interleaves its tracks for playback. A
   * {@code mimeType} of video/webm writes WebM; anything else writes MP4. When either track starts
   * before zero, such as AAC priming, both are shifted by the same amount so the muxer never gets a
   * negative time and the tracks stay in sync.
   */
  static void merge(File video, File audio, File output, String mimeType, BooleanSupplier cancelled)
      throws IOException {
    MediaExtractor videoSource = new MediaExtractor();
    MediaExtractor audioSource = new MediaExtractor();
    MediaMuxer muxer = null;
    boolean started = false;
    boolean written = false;
    try {
      MediaFormat videoFormat;
      MediaFormat audioFormat;
      try {
        videoSource.setDataSource(video.getAbsolutePath());
        audioSource.setDataSource(audio.getAbsolutePath());
        videoFormat = selectTrack(videoSource, "video/");
        audioFormat = selectTrack(audioSource, "audio/");
      } catch (IOException | RuntimeException e) {
        throw new Unmergeable("Could not read the downloaded video or audio", e);
      }
      muxer =
          new MediaMuxer(
              output.getAbsolutePath(),
              "video/webm".equals(mimeType)
                  ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                  : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
      int videoTrack = muxer.addTrack(videoFormat);
      int audioTrack = muxer.addTrack(audioFormat);
      muxer.start();
      started = true;
      ByteBuffer buffer =
          ByteBuffer.allocateDirect(Math.max(inputSize(videoFormat), inputSize(audioFormat)));
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      boolean videoLeft = videoSource.getSampleTrackIndex() >= 0;
      boolean audioLeft = audioSource.getSampleTrackIndex() >= 0;
      long shift = 0;
      if (videoLeft) shift = Math.max(shift, -videoSource.getSampleTime());
      if (audioLeft) shift = Math.max(shift, -audioSource.getSampleTime());
      for (long copied = 0; videoLeft || audioLeft; copied++) {
        if (copied % CHECK_EVERY == 0
            && (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()))
          throw new Cancelled();
        boolean takeVideo =
            videoLeft && (!audioLeft || videoSource.getSampleTime() <= audioSource.getSampleTime());
        MediaExtractor source = takeVideo ? videoSource : audioSource;
        int size;
        while (true) {
          try {
            size = source.readSampleData(buffer, 0);
            break;
          } catch (IllegalArgumentException tooSmall) {
            if (buffer.capacity() >= MAX_BUFFER_BYTES) throw tooSmall;
            buffer = ByteBuffer.allocateDirect(buffer.capacity() * 2);
          }
        }
        boolean more = size >= 0;
        if (more) {
          int flags =
              (source.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                  ? MediaCodec.BUFFER_FLAG_KEY_FRAME
                  : 0;
          // A B-frame may still sort before the first sample; zero keeps it valid for the muxer.
          info.set(0, size, Math.max(0, source.getSampleTime() + shift), flags);
          muxer.writeSampleData(takeVideo ? videoTrack : audioTrack, buffer, info);
          more = source.advance() && source.getSampleTrackIndex() >= 0;
        }
        if (takeVideo) videoLeft = more;
        else audioLeft = more;
      }
      started = false;
      muxer.stop();
      written = true;
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new Unmergeable("Could not combine the video and audio", e);
    } finally {
      if (muxer != null) {
        try {
          if (started) muxer.stop();
        } catch (IllegalStateException ignored) {
          // The output is discarded after a failure.
        }
        try {
          muxer.release();
        } catch (IllegalStateException ignored) {
          // Older versions' release() retries a failed stop() and throws again.
        }
      }
      videoSource.release();
      audioSource.release();
      if (!written) output.delete();
    }
  }

  private static MediaFormat selectTrack(MediaExtractor extractor, String prefix)
      throws IOException {
    for (int i = 0; i < extractor.getTrackCount(); i++) {
      MediaFormat format = extractor.getTrackFormat(i);
      String mime = format.getString(MediaFormat.KEY_MIME);
      if (mime != null && mime.startsWith(prefix)) {
        extractor.selectTrack(i);
        return format;
      }
    }
    throw new IOException("The downloaded stream has no " + prefix.replace("/", "") + " track");
  }

  private static int inputSize(MediaFormat format) {
    int size =
        format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
            ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            : 0;
    return Math.min(MAX_BUFFER_BYTES, Math.max(MIN_BUFFER_BYTES, size));
  }
}
