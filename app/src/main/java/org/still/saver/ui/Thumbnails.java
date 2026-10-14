// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.still.saver.core.PreviewImages;

/**
 * Bounded thumbnails for library tiles and result previews. Local frames are cached in
 * memory and on disk; remote previews stay in memory only. A failed load leaves the tile's
 * explicit "no preview" state in place.
 */
public final class Thumbnails {
  private static final String TAG = "StillThumbnails";
  private static final long DISK_LIMIT = 20L * 1024 * 1024;
  private static Thumbnails instance;

  public static final class Thumb {
    public final Bitmap bitmap;
    public final long durationMs;

    Thumb(Bitmap bitmap, long durationMs) {
      this.bitmap = bitmap;
      this.durationMs = durationMs;
    }
  }

  public interface Callback {
    void loaded(Thumb thumb);
  }

  private interface Loader {
    Thumb load() throws Exception;
  }

  private final Context context;
  private final File directory;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService local =
      Executors.newFixedThreadPool(Build.VERSION.SDK_INT >= 27 ? 2 : 1);
  private final ExecutorService remote = Executors.newFixedThreadPool(2);
  private final AtomicInteger pending = new AtomicInteger();
  /** Keys that could not be decoded; not retried until {@link #forget}, so rebinds stay cheap. */
  private final java.util.Set<String> failed =
      java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
  private final Object idle = new Object();
  private final LruCache<String, Thumb> memory =
      new LruCache<String, Thumb>((int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 8)) {
        @Override
        protected int sizeOf(String key, Thumb value) {
          return value.bitmap.getByteCount();
        }
      };

  private Thumbnails(Context context) {
    this.context = context.getApplicationContext();
    directory = new File(this.context.getCacheDir(), "thumbs");
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new IllegalStateException("Could not create the thumbnail cache");
    local.execute(this::prune);
  }

  public static synchronized Thumbnails get(Context context) {
    if (instance == null) instance = new Thumbnails(context);
    return instance;
  }

  /** Saved files. {@code mime} chooses the decoder; videos also report duration. */
  public void bindLocal(ImageView view, Uri uri, String mime, int px, Callback callback) {
    String key = "local:" + uri + ":" + px;
    boolean video = mime != null && mime.startsWith("video/");
    bind(
        view,
        key,
        local,
        () -> {
          Thumb cached = readDisk(key);
          if (cached != null) return cached;
          Thumb made;
          if (video) {
            LocalFrames.Frame frame = LocalFrames.video(context, uri, px);
            made = new Thumb(frame.bitmap, frame.durationMs);
          } else made = new Thumb(LocalFrames.image(context.getContentResolver(), uri, px), 0);
          writeDisk(key, made);
          return made;
        },
        callback);
  }

  /** Pre-download previews from the platform CDN; never cached on disk. */
  public void bindRemote(ImageView view, String url, int px, Callback callback) {
    String key = "remote:" + url + ":" + px;
    bind(view, key, remote, () -> new Thumb(decode(PreviewImages.fetch(url), px), 0), callback);
  }

  public void clear(ImageView view) {
    view.setTag(null);
    view.setImageDrawable(null);
  }

  /** Drops cached frames after the file is removed from the library. */
  public void forget(Uri uri) {
    String prefix = "local:" + uri + ":";
    for (String key : memory.snapshot().keySet()) if (key.startsWith(prefix)) memory.remove(key);
    for (String key : failed) if (key.startsWith(prefix)) failed.remove(key);
    local.execute(
        () -> {
          for (int px : new int[] {Sizes.TILE, Sizes.ROW, Sizes.PREVIEW}) {
            File file = file(prefix + px);
            if (file.exists() && !file.delete()) Log.w(TAG, "Could not delete " + file);
          }
        });
  }

  /** Test hook: blocks until queued loads finish. */
  public void idleForTest() throws InterruptedException {
    synchronized (idle) {
      while (pending.get() > 0) idle.wait(100);
    }
  }

  private void bind(
      ImageView view, String key, ExecutorService executor, Loader loader, Callback callback) {
    Thumb cached = memory.get(key);
    if (key.equals(view.getTag())) { // Already showing (or loading) this image.
      if (cached != null && callback != null) callback.loaded(cached);
      return;
    }
    view.setTag(key);
    if (failed.contains(key)) {
      view.setImageDrawable(null);
      return;
    }
    if (cached != null) {
      view.setImageBitmap(cached.bitmap);
      if (callback != null) callback.loaded(cached);
      return;
    }
    view.setImageDrawable(null);
    pending.incrementAndGet();
    executor.execute(
        () -> {
          Thumb thumb = null;
          try {
            thumb = loader.load();
            memory.put(key, thumb);
          } catch (Exception | OutOfMemoryError e) {
            if (failed.size() < 2000) failed.add(key);
            Log.w(TAG, "No preview for " + key.substring(0, Math.min(key.length(), 80)) + ": " + e);
          } finally {
            finished();
          }
          Thumb result = thumb;
          if (result != null)
            main.post(
                () -> {
                  if (!key.equals(view.getTag())) return;
                  fadeIn(view, result.bitmap);
                  if (callback != null) callback.loaded(result);
                });
        });
  }

  /** Fades the drawable, not the view, so callers stay free to dim the view itself. */
  private static void fadeIn(ImageView view, Bitmap bitmap) {
    BitmapDrawable image = new BitmapDrawable(view.getResources(), bitmap);
    view.setImageDrawable(image);
    Motion.play(
        view,
        0.3f,
        age -> image.setAlpha(Math.round(255 * (age == Motion.END ? 1 : Motion.smooth(0, 0.22f, age)))));
  }

  private void finished() {
    if (pending.decrementAndGet() == 0)
      synchronized (idle) {
        idle.notifyAll();
      }
  }

  private static Bitmap decode(byte[] bytes, int px) throws IOException {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    int w = options.outWidth, h = options.outHeight;
    if (w <= 0 || h <= 0 || w > 10000 || h > 10000 || (long) w * h > 40_000_000L)
      throw new IOException("Unsupported preview dimensions");
    int sample = 1;
    while (Math.max(w, h) / (sample * 2) >= px) sample *= 2;
    options.inSampleSize = sample;
    options.inJustDecodeBounds = false;
    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    if (bitmap == null) throw new IOException("Invalid preview image");
    return bitmap;
  }

  private File file(String key) {
    return new File(directory, sha256(key) + ".jpg");
  }

  private Thumb readDisk(String key) {
    File image = file(key);
    if (!image.isFile()) return null;
    Bitmap bitmap = BitmapFactory.decodeFile(image.getPath());
    if (bitmap == null) return null;
    if (!image.setLastModified(System.currentTimeMillis())) Log.w(TAG, "Could not touch " + image);
    return new Thumb(bitmap, duration(image));
  }

  private void writeDisk(String key, Thumb thumb) throws IOException {
    File image = file(key);
    File partial = new File(directory, image.getName() + ".part");
    try (OutputStream out = new FileOutputStream(partial)) {
      thumb.bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
    }
    if (!partial.renameTo(image)) throw new IOException("Could not store the thumbnail");
    File meta = new File(directory, image.getName() + ".ms");
    try (OutputStream out = new FileOutputStream(meta)) {
      out.write(Long.toString(thumb.durationMs).getBytes(StandardCharsets.US_ASCII));
    }
  }

  private long duration(File image) {
    File meta = new File(directory, image.getName() + ".ms");
    if (!meta.isFile()) return 0;
    try (java.io.InputStream in = new java.io.FileInputStream(meta)) {
      byte[] bytes = new byte[32];
      int count = in.read(bytes);
      return count <= 0 ? 0 : Long.parseLong(new String(bytes, 0, count, StandardCharsets.US_ASCII).trim());
    } catch (IOException | NumberFormatException e) {
      return 0;
    }
  }

  private void prune() {
    File[] files = directory.listFiles();
    if (files == null) return;
    // Other threads touch these files while sorting, so the comparator reads a fixed snapshot.
    Map<File, Long> modified = new HashMap<>();
    for (File file : files) modified.put(file, file.lastModified());
    Arrays.sort(files, (a, b) -> Long.compare(modified.get(b), modified.get(a)));
    long total = 0;
    for (File file : files) {
      total += file.length();
      if (total > DISK_LIMIT || file.getName().endsWith(".part"))
        if (!file.delete()) Log.w(TAG, "Could not prune " + file);
    }
  }

  private static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (int i = 0; i < 16; i++) out.append(String.format("%02x", digest[i] & 0xff));
      return out.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Decode sizes in pixels; shared so cache keys line up with {@link #forget}. */
  public static final class Sizes {
    public static final int TILE = 360, ROW = 160, PREVIEW = 480;

    private Sizes() {}
  }
}
