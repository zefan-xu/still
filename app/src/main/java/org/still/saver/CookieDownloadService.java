// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.still.saver.core.ByteRanges;
import org.still.saver.core.ExtractorCookies;

/**
 * HTTPS transfers whose anonymous extractor cookies must be scoped on every redirect, and videos
 * whose separate audio track is downloaded alongside and merged before publishing.
 */
public final class CookieDownloadService extends Service {
  private static final String CHANNEL = "scoped_downloads";
  private static final int NOTIFICATION_ID = 4203;

  /** A one-byte range: its Content-Range reports the length of a file downloaded in ranges. */
  private static final String FIRST_BYTE = "bytes=0-0";

  private static final int RANGE_RETRIES = 3;

  /** Doubles after each failed attempt at the same range. */
  private static final long RANGE_RETRY_MS = 1000;

  private static volatile CookieDownloadService instance;
  private static boolean starting;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService workers = Executors.newFixedThreadPool(4);
  private final Map<String, Transfer> transfers = new ConcurrentHashMap<>();
  private volatile boolean stopping;
  private boolean initialized;

  /** Starts immediately while a queued cookie download still has a visible caller. */
  static synchronized boolean ensureStarted(Context context) {
    if ((instance != null && !instance.stopping) || starting) return true;
    starting = true;
    try {
      Intent intent = new Intent(context, CookieDownloadService.class);
      if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
      else context.startService(intent);
      return true;
    } catch (RuntimeException e) {
      starting = false;
      return false; // Background launch restrictions: Library explains how to resume.
    }
  }

  static void cancel(String key, String token) {
    CookieDownloadService service = instance;
    if (service == null) return;
    Transfer transfer = service.transfers.get(key);
    if (transfer != null && token.equals(transfer.job.token)) transfer.cancel();
  }

  @Override
  public void onCreate() {
    super.onCreate();
    synchronized (CookieDownloadService.class) {
      instance = this;
      starting = false;
    }
    if (Build.VERSION.SDK_INT >= 26)
      manager()
          .createNotificationChannel(
              new NotificationChannel(
                  CHANNEL,
                  getString(R.string.download_channel),
                  NotificationManager.IMPORTANCE_LOW));
    Notification notification = notification(getString(R.string.download_preparing), -1);
    if (Build.VERSION.SDK_INT >= 29)
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    else startForeground(NOTIFICATION_ID, notification);
    try {
      DownloadStore.recoverCookieJobs(this);
      File[] old = partialDirectory().listFiles();
      if (old != null) for (File file : old) if (file.getName().endsWith(".part")) file.delete();
      initialized = true;
      main.post(tick);
    } catch (RuntimeException e) {
      stopping = true;
      stopSelf();
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (!initialized) {
      stopSelf(startId);
      return START_NOT_STICKY;
    }
    // A new admission can arrive after the previous empty-queue stop decision.
    stopping = false;
    synchronized (CookieDownloadService.class) {
      starting = false;
    }
    Notification notification = notification(getString(R.string.download_preparing), -1);
    if (Build.VERSION.SDK_INT >= 29)
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    else startForeground(NOTIFICATION_ID, notification);
    main.removeCallbacks(tick);
    main.post(tick);
    return START_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onTimeout(int startId, int type) {
    stopping = true;
    for (DownloadStore.CookieJob job : DownloadStore.cookieJobs(this))
      DownloadStore.updateCookie(
          this,
          job.entry.key,
          job.token,
          DownloadManager.STATUS_PAUSED,
          DownloadManager.PAUSED_WAITING_TO_RETRY,
          0,
          -1,
          getString(R.string.status_paused_open_still),
          0,
          job.attempts);
    for (Transfer transfer : transfers.values()) transfer.cancel();
    stopSelf();
  }

  @Override
  public void onDestroy() {
    stopping = true;
    main.removeCallbacksAndMessages(null);
    for (Transfer transfer : transfers.values()) transfer.cancel();
    workers.shutdownNow();
    synchronized (CookieDownloadService.class) {
      if (instance == this) instance = null;
      starting = false;
    }
    super.onDestroy();
  }

  private final Runnable tick =
      new Runnable() {
        @Override
        public void run() {
          if (stopping) return;
          try {
            DownloadStore.pump(CookieDownloadService.this);
            List<DownloadStore.CookieJob> jobs =
                DownloadStore.cookieJobs(CookieDownloadService.this);
            long now = System.currentTimeMillis();
            for (DownloadStore.CookieJob job : jobs) {
              Transfer previous = transfers.get(job.entry.key);
              if (previous != null) {
                if (!previous.job.token.equals(job.token)) previous.cancel();
                continue;
              }
              int pause = networkPause(job.wifi);
              if (pause != 0) {
                String detail =
                    getString(
                        pause == DownloadManager.PAUSED_QUEUED_FOR_WIFI
                            ? R.string.status_waiting_wifi
                            : R.string.status_waiting_connection);
                if (job.entry.status != DownloadManager.STATUS_PAUSED
                    || !detail.equals(job.entry.detail))
                  DownloadStore.updateCookie(
                      CookieDownloadService.this,
                      job.entry.key,
                      job.token,
                      DownloadManager.STATUS_PAUSED,
                      pause,
                      0,
                      -1,
                      detail,
                      0,
                      job.attempts);
                continue;
              }
              if (job.retryAt > now) continue;
              Transfer transfer = new Transfer(job);
              transfers.put(job.entry.key, transfer);
              workers.execute(transfer);
            }
            synchronized (DownloadStore.class) {
              if (transfers.isEmpty() && !DownloadStore.hasCookieWork(CookieDownloadService.this)) {
                stopping = true;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return;
              }
            }
            String label =
                jobs.isEmpty()
                    ? getString(R.string.download_queued)
                    : jobs.get(0).entry.description(CookieDownloadService.this);
            int progress = -1;
            if (!jobs.isEmpty() && jobs.get(0).entry.total > 0)
              progress = (int) (jobs.get(0).entry.bytes * 100 / jobs.get(0).entry.total);
            manager().notify(NOTIFICATION_ID, notification(label, progress));
          } catch (RuntimeException e) {
            // Keep the service alive; foreground Library operations can surface storage errors.
          }
          main.postDelayed(this, 2000);
        }
      };

  private int networkPause(boolean wifi) {
    ConnectivityManager connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
    NetworkCapabilities capabilities =
        connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
    NetworkInfo info = connectivity.getActiveNetworkInfo();
    boolean internet =
        capabilities != null
            && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            && info != null
            && info.isConnected();
    boolean wifiNetwork =
        capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    boolean roaming = info != null && info.isRoaming();
    if (!DownloadNetworkPolicy.allows(
        false, internet, wifiNetwork, connectivity.isActiveNetworkMetered(), roaming))
      return DownloadManager.PAUSED_WAITING_FOR_NETWORK;
    if (!DownloadNetworkPolicy.allows(
        wifi, internet, wifiNetwork, connectivity.isActiveNetworkMetered(), roaming))
      return DownloadManager.PAUSED_QUEUED_FOR_WIFI;
    return 0;
  }

  private File partialDirectory() {
    return new File(getNoBackupFilesDir(), "cookie-downloads");
  }

  private final class Transfer implements Runnable {
    final DownloadStore.CookieJob job;
    final AtomicBoolean cancelled = new AtomicBoolean();
    volatile HttpURLConnection connection;
    long bytes, total = -1;

    Transfer(DownloadStore.CookieJob job) {
      this.job = job;
    }

    void cancel() {
      cancelled.set(true);
      HttpURLConnection active = connection;
      if (active != null) active.disconnect();
    }

    void checkCurrent() throws Cancelled {
      checkStopped();
      if (!DownloadStore.cookieCurrent(CookieDownloadService.this, job.entry.key, job.token))
        throw new Cancelled();
    }

    /** The in-memory part of {@link #checkCurrent}, cheap enough for every read. */
    void checkStopped() throws Cancelled {
      if (cancelled.get() || stopping || Thread.currentThread().isInterrupted())
        throw new Cancelled();
    }

    /** Whether this attempt was cancelled, stopped, or removed or replaced in the store. */
    boolean stale() {
      try {
        checkCurrent();
        return false;
      } catch (Cancelled cancelledOrReplaced) {
        return true;
      }
    }

    void checkNetwork() throws IOException {
      checkCurrent();
      int pause = networkPause(job.wifi);
      if (pause != 0) throw new NetworkPaused(pause);
    }

    void update(int status, int reason, String detail, long retryAt, int attempts)
        throws Cancelled {
      if (!DownloadStore.updateCookie(
          CookieDownloadService.this,
          job.entry.key,
          job.token,
          status,
          reason,
          bytes,
          total,
          detail,
          retryAt,
          attempts)) throw new Cancelled();
    }

    @Override
    public void run() {
      File partial = new File(partialDirectory(), job.entry.key + "-" + job.token + ".part");
      Published published = null;
      boolean saved = false;
      try {
        checkNetwork();
        if (!partial.getParentFile().isDirectory()
            && !partial.getParentFile().mkdirs()
            && !partial.getParentFile().isDirectory())
          throw new IOException("Could not create temporary download storage");
        update(DownloadManager.STATUS_RUNNING, 0, "", 0, job.attempts);
        download(partial);
        checkCurrent();
        if ("video/mp4".equals(job.entry.mime)) MediaRepair.repair(partial);
        published = publish(partial);
        checkCurrent();
        saved =
            DownloadStore.finishCookie(
                CookieDownloadService.this, job.entry.key, job.token, published.uri, bytes);
        if (!saved) throw new Cancelled();
      } catch (Cancelled ignored) {
        // Cancellation/restart removed this attempt; it must not change the replacement record.
      } catch (NetworkPaused pause) {
        bytes = 0;
        total = -1;
        try {
          update(
              DownloadManager.STATUS_PAUSED,
              pause.reason,
              getString(
                  pause.reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI
                      ? R.string.status_waiting_wifi
                      : R.string.status_waiting_connection),
              0,
              job.attempts);
        } catch (Cancelled ignored) {
          /* replaced attempt */
        }
      } catch (Exception error) {
        if (!cancelled.get() && !stopping) {
          int attempts = job.attempts + 1;
          boolean retry = !(error instanceof PermanentFailure) && attempts < 4;
          bytes = 0;
          total = -1;
          String detail =
              getString(retry ? R.string.status_interrupted : R.string.status_transfer_failed);
          if (error instanceof PermanentFailure)
            detail = getString(((PermanentFailure) error).detail);
          try {
            update(
                retry ? DownloadManager.STATUS_PAUSED : DownloadManager.STATUS_FAILED,
                retry ? DownloadManager.PAUSED_WAITING_TO_RETRY : DownloadManager.ERROR_UNKNOWN,
                detail,
                retry ? System.currentTimeMillis() + attempts * 10000L : 0,
                attempts);
          } catch (Cancelled ignored) {
            /* replaced attempt */
          }
        }
      } finally {
        HttpURLConnection active = connection;
        if (active != null) active.disconnect();
        if (!saved && published != null) published.remove();
        partial.delete();
        transfers.remove(job.entry.key, this);
      }
    }

    private void download(File partial) throws IOException {
      URI target = httpsUri(job.entry.url);
      if (job.audioUrl.isEmpty()) {
        HttpURLConnection request = open(target, null);
        total = request.getContentLengthLong();
        update(DownloadManager.STATUS_RUNNING, 0, "", 0, job.attempts);
        long received = copy(request, partial, false);
        if (received == 0 || (total >= 0 && received != total))
          throw new IOException("The media download was incomplete");
        return;
      }
      URI audio = httpsUri(job.audioUrl);
      File video = new File(partial.getPath() + "-video.part");
      File sound = new File(partial.getPath() + "-audio.part");
      try {
        long videoLength = length(target);
        long audioLength = length(audio);
        total = videoLength + audioLength;
        update(DownloadManager.STATUS_RUNNING, 0, "", 0, job.attempts);
        downloadRanges(target, videoLength, video);
        downloadRanges(audio, audioLength, sound);
        update(
            DownloadManager.STATUS_RUNNING,
            0,
            getString(R.string.status_combining),
            0,
            job.attempts);
        makeRoom(partial, video.length() + sound.length());
        try {
          StreamMerger.merge(video, sound, partial, job.entry.mime, this::stale);
        } catch (StreamMerger.Unmergeable e) {
          checkCurrent();
          throw new PermanentFailure(R.string.status_merge_failed);
        } catch (IOException e) {
          checkCurrent();
          throw new PermanentFailure(R.string.status_merge_write_failed);
        }
        bytes = total = partial.length();
      } finally {
        video.delete();
        sound.delete();
      }
    }

    /**
     * Frees {@code bytes} on {@code file}'s volume, clearing cache if needed. Merging needs Android
     * 10, so older versions are not checked.
     */
    private void makeRoom(File file, long bytes) throws PermanentFailure {
      if (Build.VERSION.SDK_INT < 26) return;
      StorageManager storage = getSystemService(StorageManager.class);
      UUID volume;
      try {
        volume = storage.getUuidForPath(file.getParentFile());
      } catch (IOException | RuntimeException unknown) {
        return; // Let the merge itself report a failure.
      }
      try {
        storage.allocateBytes(volume, bytes);
      } catch (IOException full) {
        throw new PermanentFailure(R.string.status_no_room_to_merge);
      } catch (RuntimeException unknown) {
        // Let the merge itself report a failure.
      }
    }

    /** The full size of a file that is downloaded in ranges. */
    private long length(URI target) throws IOException {
      HttpURLConnection request = open(target, FIRST_BYTE);
      try {
        long length =
            request.getResponseCode() == HttpURLConnection.HTTP_OK
                ? request.getContentLengthLong()
                : rangeLength(request.getHeaderField("Content-Range"));
        if (length <= 0)
          throw new PermanentFailure(R.string.status_no_size);
        return length;
      } finally {
        request.disconnect();
      }
    }

    private long rangeLength(String contentRange) {
      long[] range = ByteRanges.parse(contentRange);
      return range == null ? -1 : range[2];
    }

    /**
     * A failed range is retried in place, keeping the ranges already saved; permanent failures,
     * pauses and cancellation end the download at once.
     */
    private void downloadRanges(URI target, long length, File file) throws IOException {
      if (file.exists() && !file.delete()) throw new IOException("Could not reset a partial file");
      for (long position = 0; position < length; ) {
        for (int failures = 0; ; failures++) {
          try {
            position += downloadRange(target, position, length, file);
            break;
          } catch (PermanentFailure | NetworkPaused | Cancelled stop) {
            throw stop;
          } catch (IOException failure) {
            if (failures >= RANGE_RETRIES) throw failure;
            truncate(file, position);
            backOff(RANGE_RETRY_MS << failures);
          }
        }
      }
    }

    /** Appends the range of {@code target} that starts at {@code position}; returns its size. */
    private long downloadRange(URI target, long position, long length, File file)
        throws IOException {
      HttpURLConnection request = open(target, ByteRanges.request(position, length));
      try {
        long expected;
        if (request.getResponseCode() == HttpURLConnection.HTTP_OK) {
          if (position != 0) throw new IOException("The media server ignored the range");
          expected = length;
        } else {
          long[] range = ByteRanges.parse(request.getHeaderField("Content-Range"));
          if (range != null && range[2] != length)
            throw new PermanentFailure(R.string.status_file_changed);
          if (range == null || range[0] != position)
            throw new IOException("The media server sent an unexpected range");
          expected = range[1] - range[0] + 1;
        }
        long received = copy(request, file, true);
        if (received != expected) throw new IOException("The media download was incomplete");
        return received;
      } finally {
        request.disconnect();
      }
    }

    /** Drops the bytes a failed range appended after {@code position}. */
    private void truncate(File file, long position) throws IOException {
      long extra = file.length() - position;
      if (extra <= 0) return;
      try (RandomAccessFile access = new RandomAccessFile(file, "rw")) {
        access.setLength(position);
      }
      bytes -= extra;
    }

    /** Waits before a retry, ending early when the transfer is cancelled or the network drops. */
    private void backOff(long millis) throws IOException {
      long until = SystemClock.elapsedRealtime() + millis;
      for (long left = millis; left > 0; left = until - SystemClock.elapsedRealtime()) {
        checkNetwork();
        try {
          Thread.sleep(Math.min(left, 250));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new Cancelled();
        }
      }
      checkNetwork();
    }

    private long copy(HttpURLConnection request, File file, boolean append) throws IOException {
      long received = 0;
      long lastUpdate = 0;
      try (InputStream input = request.getInputStream();
          OutputStream output = new FileOutputStream(file, append)) {
        byte[] buffer = new byte[32768];
        int count;
        while ((count = input.read(buffer)) != -1) {
          checkStopped();
          long now = System.currentTimeMillis();
          if (now - lastUpdate >= 1000) checkNetwork();
          output.write(buffer, 0, count);
          received += count;
          bytes += count;
          if (now - lastUpdate >= 1000) {
            update(DownloadManager.STATUS_RUNNING, 0, "", 0, job.attempts);
            lastUpdate = now;
          }
        }
      }
      return received;
    }

    /** Follows HTTPS redirects to a media response: 200, or 206 when a range was requested. */
    private HttpURLConnection open(URI target, String range) throws IOException {
      for (int redirects = 0; redirects <= 5; redirects++) {
        checkNetwork();
        HttpURLConnection request = (HttpURLConnection) target.toURL().openConnection();
        connection = request;
        request.setInstanceFollowRedirects(false);
        request.setConnectTimeout(15000);
        request.setReadTimeout(15000);
        request.setRequestProperty("Accept-Encoding", "identity");
        if (range != null) request.setRequestProperty("Range", range);
        if (job.headers != null)
          for (String header : new String[] {"User-Agent", "Referer"})
            if (job.headers.has(header))
              request.setRequestProperty(header, job.headers.optString(header));
        String cookie =
            ExtractorCookies.forUrl(
                job.cookies, target.toString(), System.currentTimeMillis() / 1000);
        if (!cookie.isEmpty()) request.setRequestProperty("Cookie", cookie);
        int response = request.getResponseCode();
        if (response == 301
            || response == 302
            || response == 303
            || response == 307
            || response == 308) {
          String location = request.getHeaderField("Location");
          request.disconnect();
          if (redirects == 5 || location == null || location.length() > 8192)
            throw new PermanentFailure(R.string.status_bad_redirects);
          try {
            target = httpsUri(target.resolve(location).toString());
          } catch (IllegalArgumentException e) {
            throw new PermanentFailure(R.string.status_invalid_redirect);
          }
          continue;
        }
        if (response != HttpURLConnection.HTTP_OK
            && (range == null || response != HttpURLConnection.HTTP_PARTIAL)) {
          if (response == 408 || response == 429 || response >= 500)
            throw new IOException("Temporary server response");
          throw new PermanentFailure(R.string.status_link_unavailable);
        }
        String type = request.getContentType();
        if (type != null
            && (type.toLowerCase(Locale.ROOT).startsWith("text/")
                || type.toLowerCase(Locale.ROOT).startsWith("application/json")))
          throw new PermanentFailure(R.string.status_not_media);
        return request;
      }
      throw new PermanentFailure(R.string.status_too_many_redirects);
    }

    private Published publish(File partial) throws IOException {
      Uri uri;
      File legacy = null;
      ContentValues values = new ContentValues();
      values.put(MediaStore.MediaColumns.DISPLAY_NAME, job.entry.filename);
      values.put(MediaStore.MediaColumns.MIME_TYPE, job.entry.mime);
      if (Build.VERSION.SDK_INT >= 29) {
        values.put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            Environment.DIRECTORY_DOWNLOADS + "/" + job.folder);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
      } else {
        File directory =
            new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                job.folder);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
          throw new IOException("Could not create the Downloads folder");
        String base = job.entry.data.optString("baseFilename", job.entry.filename);
        int dot = base.lastIndexOf('.');
        String attemptName =
            (dot < 0 ? base : base.substring(0, dot))
                + "_"
                + job.token.substring(0, 8)
                + (dot < 0 ? "" : base.substring(dot));
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, attemptName);
        legacy = new File(directory, attemptName);
        if (legacy.exists()) throw new PermanentFailure(R.string.status_file_exists);
        values.put(MediaStore.MediaColumns.DATA, legacy.getAbsolutePath());
        Uri collection =
            job.entry.mime.startsWith("image/")
                ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        uri = getContentResolver().insert(collection, values);
      }
      if (uri == null) throw new IOException("Could not create the saved media entry");
      Published target = new Published(uri, legacy);
      try {
        if (!DownloadStore.stageCookieOutput(
            CookieDownloadService.this,
            job.entry.key,
            job.token,
            uri,
            legacy == null ? null : legacy.getAbsolutePath())) throw new Cancelled();
        try (InputStream input = new FileInputStream(partial);
            OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
          if (output == null) throw new IOException("Could not open the Downloads destination");
          byte[] buffer = new byte[32768];
          int count;
          while ((count = input.read(buffer)) != -1) {
            checkCurrent();
            output.write(buffer, 0, count);
          }
        }
        checkCurrent();
        if (Build.VERSION.SDK_INT >= 29) {
          ContentValues complete = new ContentValues();
          complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
          if (getContentResolver().update(uri, complete, null, null) != 1)
            throw new IOException("Could not publish the saved media");
        } else {
          MediaScannerConnection.scanFile(
              CookieDownloadService.this,
              new String[] {legacy.getAbsolutePath()},
              new String[] {job.entry.mime},
              null);
        }
        return target;
      } catch (IOException | RuntimeException e) {
        target.remove();
        throw e;
      }
    }
  }

  private final class Published {
    final Uri uri;
    final File legacy;

    Published(Uri uri, File legacy) {
      this.uri = uri;
      this.legacy = legacy;
    }

    void remove() {
      try {
        getContentResolver().delete(uri, null, null);
      } catch (RuntimeException ignored) {
        /* also remove a legacy file below */
      }
      if (legacy != null) legacy.delete();
    }
  }

  private static URI httpsUri(String value) throws PermanentFailure {
    try {
      URI uri = new URI(value);
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getFragment() != null
          || uri.getPort() == 0
          || uri.getPort() > 65535) throw new IllegalArgumentException();
      return uri;
    } catch (Exception e) {
      throw new PermanentFailure(R.string.status_https_only);
    }
  }

  private static class Cancelled extends IOException {}

  private static final class NetworkPaused extends IOException {
    final int reason;

    NetworkPaused(int reason) {
      this.reason = reason;
    }
  }

  /** Ends the transfer with {@link #detail}, a string resource, as the library status. */
  private static final class PermanentFailure extends IOException {
    final int detail;

    PermanentFailure(int detail) {
      this.detail = detail;
    }
  }

  private NotificationManager manager() {
    return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
  }

  private Notification notification(String label, int progress) {
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
            ? new Notification.Builder(this, CHANNEL)
            : new Notification.Builder(this);
    return builder
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(0xffd97757)
        .setContentTitle(getString(R.string.download_notification_title))
        .setContentText(label)
        .setContentIntent(open)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(100, Math.max(0, Math.min(100, progress)), progress < 0)
        .build();
  }
}
