// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.gif;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.still.saver.DownloadStore;
import org.still.saver.MainActivity;
import org.still.saver.R;

/**
 * Converts locally downloaded videos to GIFs, one frame at a time. GIF export intentionally uses
 * the first 60 seconds at 8 fps, up to 360 pixels on either side, and a fixed 256-color palette to
 * bound CPU, memory, and output size.
 */
public final class GifService extends Service {
  public static final int FRAMES_PER_SECOND = 8;
  public static final int MAX_DURATION_MS = 60_000;
  public static final int MAX_DIMENSION = 360;
  private static final String CHANNEL_ID = "gif_conversion";
  private static final String ACTION_CANCEL = "org.still.saver.gif.CANCEL";
  private static final String EXTRA_TITLE = "title";
  private static final String EXTRA_JOB_ID = "job_id";
  private static final int NOTIFICATION_ID = 4202;
  private static volatile String activeJobId;
  private static volatile boolean running;
  private static volatile int currentProgress;
  private static volatile String currentStatus = "";
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private boolean busy;
  private String jobId;
  private String activeTitle;

  public static boolean isRunning() {
    return running;
  }

  public static int getProgress() {
    return currentProgress;
  }

  /** Retains the last result after conversion ends so the visible app can display it. */
  public static String getStatus() {
    return currentStatus;
  }

  /** Call from the visible app; a delayed cancel request cannot cancel a later export. */
  public static void cancel(Context context) {
    if (!running) return;
    context.startService(
        new Intent(context, GifService.class)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_JOB_ID, activeJobId));
  }

  /** Call from the visible app after storage permission is granted on API 23–28. */
  public static void start(Context context, Uri videoUri, String title) {
    if (videoUri == null) throw new IllegalArgumentException("A local video is required");
    Intent intent =
        new Intent(context, GifService.class)
            .setData(videoUri)
            .putExtra(EXTRA_TITLE, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
    else context.startService(intent);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    if (Build.VERSION.SDK_INT >= 26) {
      manager()
          .createNotificationChannel(
              new NotificationChannel(
                  CHANNEL_ID, "GIF conversion", NotificationManager.IMPORTANCE_LOW));
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
      if (busy && jobId.equals(intent.getStringExtra(EXTRA_JOB_ID))) {
        cancelled.set(true);
        currentStatus = "Cancelling GIF conversion…";
        manager().notify(NOTIFICATION_ID, notification(currentStatus, currentProgress, true));
      } else if (!busy) stopSelf(startId);
      return START_NOT_STICKY;
    }
    if (busy) {
      Toast.makeText(this, "A GIF is already being created", Toast.LENGTH_SHORT).show();
      return START_NOT_STICKY;
    }
    if (intent == null || intent.getData() == null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    busy = true;
    cancelled.set(false);
    jobId = UUID.randomUUID().toString();
    activeJobId = jobId;
    currentProgress = 0;
    currentStatus = "Preparing GIF…";
    running = true;
    Uri source = intent.getData();
    String title = intent.getStringExtra(EXTRA_TITLE);
    activeTitle = title == null || title.trim().isEmpty() ? "Saved video" : title;
    Notification starting = notification("Preparing GIF…", 0, true);
    if (Build.VERSION.SDK_INT >= 29) {
      int type =
          Build.VERSION.SDK_INT >= 35
              ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
              : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
      startForeground(NOTIFICATION_ID, starting, type);
    } else startForeground(NOTIFICATION_ID, starting);
    worker.execute(() -> convert(source, activeTitle));
    return START_NOT_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onDestroy() {
    cancelled.set(true);
    if (busy && jobId.equals(activeJobId)) {
      currentStatus = "GIF conversion cancelled";
      running = false;
    }
    worker.shutdownNow();
    super.onDestroy();
  }

  @Override
  public void onTimeout(int startId, int foregroundServiceType) {
    cancelled.set(true);
    stopSelf();
  }

  private void convert(Uri source, String title) {
    File temporary = null;
    Published published = null;
    boolean recorded = false;
    String result;
    try {
      temporary = File.createTempFile("still-gif-", ".gif", getCacheDir());
      encode(source, temporary);
      checkCancelled();
      String filename = filename(title);
      published = publish(temporary, title, filename);
      checkCancelled();
      DownloadStore.recordLocal(this, published.uri, title, filename, "image/gif");
      recorded = true;
      result = "GIF saved to Downloads/" + DownloadStore.folder(this);
    } catch (CancelledException exception) {
      result = "GIF conversion cancelled";
    } catch (OutOfMemoryError error) {
      // API 23–26 can only decode an original-sized frame before scaling it.
      result = "GIF conversion failed: this video is too large for this device";
    } catch (Exception exception) {
      String detail = exception.getMessage();
      if (detail == null || detail.trim().isEmpty()) detail = "Unable to read this video";
      detail = detail.replace('\n', ' ').replace('\r', ' ');
      if (detail.length() > 160) detail = detail.substring(0, 160);
      result = "GIF conversion failed: " + detail;
    } finally {
      if (!recorded && published != null) removePublished(published);
      if (temporary != null) temporary.delete();
    }
    String message = result;
    main.post(
        () -> {
          if (!jobId.equals(activeJobId)) return;
          if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
          else stopForeground(true);
          busy = false;
          currentProgress = 100;
          currentStatus = message;
          running = false;
          manager().notify(NOTIFICATION_ID, notification(message, 100, false));
          stopSelf();
        });
  }

  private void encode(Uri source, File destination) throws Exception {
    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
    try {
      retriever.setDataSource(this, source);
      long duration =
          parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
      if (duration <= 0) throw new IOException("This video has no readable duration");
      duration = Math.min(duration, MAX_DURATION_MS);
      int[] requested = metadataDimensions(retriever);
      int count = (int) ((duration * FRAMES_PER_SECOND + 999) / 1000);
      int width = 0;
      int height = 0;
      int[] pixels = null;
      AnimatedGifEncoder encoder = null;
      int lastProgress = -1;
      try (OutputStream output = new FileOutputStream(destination)) {
        for (int index = 0; index < count; index++) {
          checkCancelled();
          long timeUs = index * 1_000_000L / FRAMES_PER_SECOND;
          Bitmap frame = null;
          Bitmap scaled = null;
          try {
            if (Build.VERSION.SDK_INT >= 27) {
              frame =
                  retriever.getScaledFrameAtTime(
                      timeUs, MediaMetadataRetriever.OPTION_CLOSEST, requested[0], requested[1]);
            } else frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
            if (frame == null) throw new IOException("A video frame could not be decoded");
            checkCancelled();
            if (encoder == null) {
              int[] dimensions = scaledDimensions(frame.getWidth(), frame.getHeight());
              width = dimensions[0];
              height = dimensions[1];
              pixels = new int[width * height];
              encoder = new AnimatedGifEncoder(output, width, height, 0);
            }
            scaled =
                frame.getWidth() == width && frame.getHeight() == height
                    ? frame
                    : Bitmap.createScaledBitmap(frame, width, height, true);
            scaled.getPixels(pixels, 0, width, 0, 0, width, height);
            long startMs = index * 1000L / FRAMES_PER_SECOND;
            long endMs = Math.min(duration, (index + 1) * 1000L / FRAMES_PER_SECOND);
            int delay = (int) Math.max(1, Math.round(endMs / 10.0) - Math.round(startMs / 10.0));
            encoder.addFrame(pixels, delay);
          } finally {
            if (scaled != null && scaled != frame) scaled.recycle();
            if (frame != null) frame.recycle();
          }
          int progress = (index + 1) * 95 / count;
          if (progress != lastProgress) {
            lastProgress = progress;
            updateProgress("Creating GIF: " + progress + "%", progress);
          }
        }
        checkCancelled();
        if (encoder == null) throw new IOException("No video frames were found");
        encoder.finish();
      }
    } finally {
      retriever.release();
    }
  }

  private Published publish(File temporary, String title, String filename) throws Exception {
    updateProgress("Saving GIF…", 95);
    if (Build.VERSION.SDK_INT >= 29) {
      ContentValues values = new ContentValues();
      values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
      values.put(MediaStore.Downloads.MIME_TYPE, "image/gif");
      values.put(
          MediaStore.Downloads.RELATIVE_PATH,
          Environment.DIRECTORY_DOWNLOADS + "/" + DownloadStore.folder(this));
      values.put(MediaStore.Downloads.IS_PENDING, 1);
      Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
      if (uri == null) throw new IOException("Unable to create a Downloads entry");
      Published target = new Published(uri, null);
      try {
        try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
          if (output == null) throw new IOException("Unable to open the GIF destination");
          copy(temporary, output);
        }
        checkCancelled();
        ContentValues complete = new ContentValues();
        complete.put(MediaStore.Downloads.IS_PENDING, 0);
        if (getContentResolver().update(uri, complete, null, null) != 1) {
          throw new IOException("Unable to publish the GIF");
        }
        return target;
      } catch (Exception exception) {
        removePublished(target);
        throw exception;
      }
    }
    File directory =
        new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DownloadStore.folder(this));
    if (!directory.isDirectory() && !directory.mkdirs()) {
      throw new IOException("Unable to create Downloads/Still");
    }
    File file = new File(directory, filename);
    Uri uri = null;
    try {
      try (OutputStream output = new FileOutputStream(file)) {
        copy(temporary, output);
      }
      checkCancelled();
      ContentValues values = new ContentValues();
      values.put(MediaStore.Images.Media.DATA, file.getAbsolutePath());
      values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
      values.put(MediaStore.Images.Media.TITLE, title);
      values.put(MediaStore.Images.Media.MIME_TYPE, "image/gif");
      uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
      if (uri == null) throw new IOException("Unable to register the saved GIF");
      MediaScannerConnection.scanFile(
          this, new String[] {file.getAbsolutePath()}, new String[] {"image/gif"}, null);
      return new Published(uri, file);
    } catch (Exception exception) {
      if (uri != null) removePublished(new Published(uri, file));
      else file.delete();
      throw exception;
    }
  }

  private void copy(File source, OutputStream destination) throws Exception {
    byte[] buffer = new byte[32 * 1024];
    try (FileInputStream input = new FileInputStream(source)) {
      int count;
      while ((count = input.read(buffer)) != -1) {
        checkCancelled();
        destination.write(buffer, 0, count);
      }
    }
  }

  private void removePublished(Published item) {
    try {
      getContentResolver().delete(item.uri, null, null);
    } catch (RuntimeException ignored) {
      /* Still attempt to remove a legacy file below. */
    }
    if (item.file != null) item.file.delete();
  }

  private Notification notification(String text, int progress, boolean ongoing) {
    Intent library =
        new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            library,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder builder =
        Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
    builder
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(0xffd97757)
        .setContentTitle("Making a GIF")
        .setContentText(text)
        .setContentIntent(open)
        .setOngoing(ongoing)
        .setOnlyAlertOnce(true)
        .setAutoCancel(!ongoing);
    if (ongoing) {
      Intent cancel =
          new Intent(this, GifService.class)
              .setAction(ACTION_CANCEL)
              .setData(Uri.parse("still-gif://cancel/" + jobId))
              .putExtra(EXTRA_JOB_ID, jobId);
      PendingIntent stop =
          PendingIntent.getService(
              this,
              NOTIFICATION_ID,
              cancel,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      builder
          .setProgress(100, progress, progress == 0)
          .addAction(
              new Notification.Action.Builder(
                      android.R.drawable.ic_menu_close_clear_cancel, "Cancel", stop)
                  .build());
    }
    return builder.build();
  }

  private NotificationManager manager() {
    return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
  }

  private void updateProgress(String text, int progress) {
    if (cancelled.get() || !jobId.equals(activeJobId)) return;
    currentProgress = progress;
    currentStatus = text;
    manager().notify(NOTIFICATION_ID, notification(text, progress, true));
  }

  private void checkCancelled() throws CancelledException {
    if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancelledException();
  }

  private static int[] metadataDimensions(MediaMetadataRetriever retriever) {
    long width =
        parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
    long height =
        parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
    long rotation =
        parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
    if (rotation == 90 || rotation == 270) {
      long swap = width;
      width = height;
      height = swap;
    }
    if (width <= 0 || height <= 0 || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
      return new int[] {MAX_DIMENSION, MAX_DIMENSION};
    }
    return scaledDimensions((int) width, (int) height);
  }

  private static int[] scaledDimensions(int width, int height) {
    double scale = Math.min(1.0, MAX_DIMENSION / (double) Math.max(width, height));
    return new int[] {
      Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale))
    };
  }

  private static long parseLong(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException exception) {
      return 0;
    }
  }

  private static String filename(String title) {
    String name = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
    if (name.length() > 72) name = name.substring(0, 72);
    if (name.isEmpty()) name = "Still";
    return name + "_" + System.currentTimeMillis() + ".gif";
  }

  private static final class Published {
    final Uri uri;
    final File file;

    Published(Uri uri, File file) {
      this.uri = uri;
      this.file = file;
    }
  }

  private static final class CancelledException extends Exception {}
}
