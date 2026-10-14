// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.ui;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import java.io.IOException;
import java.io.InputStream;

/**
 * Decodes local content URIs (MediaStore, DownloadManager and document-tree URIs alike) at a
 * bounded size. One path per media type and API level.
 */
public final class LocalFrames {
  private LocalFrames() {}

  public static final class Frame {
    public final Bitmap bitmap;
    public final long durationMs;

    Frame(Bitmap bitmap, long durationMs) {
      this.bitmap = bitmap;
      this.durationMs = durationMs;
    }
  }

  /** Static image (first frame for GIFs), longest side at most {@code maxPx}. */
  public static Bitmap image(ContentResolver resolver, Uri uri, int maxPx) throws IOException {
    if (Build.VERSION.SDK_INT >= 28) {
      return ImageDecoder.decodeBitmap(
          ImageDecoder.createSource(resolver, uri),
          (decoder, info, source) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            float factor = Math.min(1f, (float) maxPx / Math.max(w, h));
            decoder.setTargetSize(
                Math.max(1, Math.round(w * factor)), Math.max(1, Math.round(h * factor)));
          });
    }
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    try (InputStream stream = resolver.openInputStream(uri)) {
      if (stream == null) throw new IOException("The file is unavailable");
      BitmapFactory.decodeStream(stream, null, options);
    }
    if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Invalid image");
    int sample = 1;
    while (Math.max(options.outWidth, options.outHeight) / (sample * 2) >= maxPx) sample *= 2;
    options.inSampleSize = sample;
    options.inJustDecodeBounds = false;
    try (InputStream pixels = resolver.openInputStream(uri)) {
      if (pixels == null) throw new IOException("The file is unavailable");
      Bitmap bitmap = BitmapFactory.decodeStream(pixels, null, options);
      if (bitmap == null) throw new IOException("Invalid image");
      return scale(bitmap, maxPx);
    }
  }

  /** For the viewer: animated where the platform supports it (API 28+). */
  public static Drawable drawable(Context context, Uri uri, int maxPx) throws IOException {
    if (Build.VERSION.SDK_INT >= 28) {
      return ImageDecoder.decodeDrawable(
          ImageDecoder.createSource(context.getContentResolver(), uri),
          (decoder, info, source) -> {
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            float factor = Math.min(1f, (float) maxPx / Math.max(w, h));
            decoder.setTargetSize(
                Math.max(1, (int) (w * factor)), Math.max(1, (int) (h * factor)));
          });
    }
    return new BitmapDrawable(
        context.getResources(), image(context.getContentResolver(), uri, maxPx));
  }

  /** A representative frame (about a third in, at most one second) and the duration. */
  public static Frame video(Context context, Uri uri, int maxPx) throws IOException {
    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
    try {
      retriever.setDataSource(context, uri);
      long duration = parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
      long timeUs = Math.min(1_000_000L, Math.max(0, duration * 1000 / 3));
      Bitmap frame;
      if (Build.VERSION.SDK_INT >= 27) {
        int w = (int) parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
        int h = (int) parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        int rotation =
            (int) parse(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
        if (rotation == 90 || rotation == 270) {
          int swap = w;
          w = h;
          h = swap;
        }
        float factor = w > 0 && h > 0 ? Math.min(1f, (float) maxPx / Math.max(w, h)) : 1f;
        int tw = w > 0 ? Math.max(1, Math.round(w * factor)) : maxPx;
        int th = h > 0 ? Math.max(1, Math.round(h * factor)) : maxPx;
        frame =
            retriever.getScaledFrameAtTime(
                timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th);
      } else {
        frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
      }
      if (frame == null) throw new IOException("The video has no readable frame");
      return new Frame(scale(frame, maxPx), duration);
    } catch (RuntimeException e) {
      throw new IOException("The video could not be read", e);
    } finally {
      try {
        retriever.release();
      } catch (Exception ignored) {
        // Release failures only affect this already-finished read.
      }
    }
  }

  private static Bitmap scale(Bitmap bitmap, int maxPx) {
    int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
    if (longest <= maxPx) return bitmap;
    float factor = (float) maxPx / longest;
    Bitmap scaled =
        Bitmap.createScaledBitmap(
            bitmap,
            Math.max(1, Math.round(bitmap.getWidth() * factor)),
            Math.max(1, Math.round(bitmap.getHeight() * factor)),
            true);
    if (scaled != bitmap) bitmap.recycle();
    return scaled;
  }

  private static long parse(String value) {
    if (value == null) return 0;
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
