// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.content.Context;
import android.system.Os;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Installs the pinned bundled extractor on first use or upgrade, preserving newer user updates. */
public final class ExtractorRuntime {
  public static final String BUNDLED_VERSION = "2026.08.19";
  public static final String BUNDLED_SHA256 =
      "1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6";
  private static final Pattern VERSION =
      Pattern.compile("(?m)^__version__\\s*=\\s*['\"]([0-9]{4}\\.[0-9]{2}\\.[0-9]{2})['\"]");
  private static boolean ready;
  private static final ReentrantLock RUNTIME_LOCK = new ReentrantLock();

  private ExtractorRuntime() {}

  /** Call on a worker thread. Native initialization and copying can take several seconds. */
  public static void init(Context context) throws Exception {
    RUNTIME_LOCK.lockInterruptibly();
    try {
      initLocked(context);
    } finally {
      RUNTIME_LOCK.unlock();
    }
  }

  /** Prevents the upstream updater from replacing the executable while Python is using it. */
  public static YoutubeDLResponse execute(
      Context context, YoutubeDLRequest request, String processId) throws Exception {
    RUNTIME_LOCK.lockInterruptibly();
    try {
      initLocked(context);
      if (Thread.currentThread().isInterrupted())
        throw new InterruptedException("Media extraction was cancelled during initialization");
      return YoutubeDL.INSTANCE.execute(request, processId, null);
    } finally {
      RUNTIME_LOCK.unlock();
    }
  }

  public static void update(Context context) throws Exception {
    RUNTIME_LOCK.lockInterruptibly();
    try {
      initLocked(context);
      YoutubeDL.INSTANCE.updateYoutubeDL(
          context.getApplicationContext(), YoutubeDL.UpdateChannel._STABLE);
    } catch (Exception failure) {
      // A failed upstream copy can remove the old executable. Repair it on next use.
      ready = false;
      throw failure;
    } finally {
      RUNTIME_LOCK.unlock();
    }
  }

  /** The installed extractor's version, or the bundled one before first use. Blocking. */
  public static String installedVersion(Context context) {
    File directory =
        new File(
            new File(context.getApplicationContext().getNoBackupFilesDir(), YoutubeDL.baseName),
            YoutubeDL.ytdlpDirName);
    String version = installedVersion(new File(directory, YoutubeDL.ytdlpBin));
    return version == null || version.compareTo(BUNDLED_VERSION) < 0 ? BUNDLED_VERSION : version;
  }

  private static void initLocked(Context context) throws Exception {
    if (ready) return;
    Context app = context.getApplicationContext();
    File directory =
        new File(new File(app.getNoBackupFilesDir(), YoutubeDL.baseName), YoutubeDL.ytdlpDirName);
    File installed = new File(directory, YoutubeDL.ytdlpBin);
    String version = installedVersion(installed);
    if (version == null || version.compareTo(BUNDLED_VERSION) < 0)
      installBundle(app, directory, installed);
    YoutubeDL.INSTANCE.init(app);
    ready = true;
  }

  private static String installedVersion(File file) {
    if (!file.isFile()) return null;
    try (ZipFile zip = new ZipFile(file)) {
      ZipEntry entry = zip.getEntry("yt_dlp/version.py");
      if (entry == null || entry.getSize() > 16384) return null;
      try (InputStream input = zip.getInputStream(entry);
          ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
          if (bytes.size() + count > 16384) return null;
          bytes.write(buffer, 0, count);
        }
        Matcher match = VERSION.matcher(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        return match.find() ? match.group(1) : null;
      }
    } catch (IOException ignored) {
      return null;
    }
  }

  private static void installBundle(Context context, File directory, File installed)
      throws Exception {
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new IOException("Could not create the extractor directory");
    File temporary = File.createTempFile(".still-yt-dlp-", ".tmp", directory);
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream source = context.getResources().openRawResource(R.raw.ytdlp);
          FileOutputStream destination = new FileOutputStream(temporary)) {
        byte[] buffer = new byte[32768];
        int count;
        while ((count = source.read(buffer)) != -1) {
          destination.write(buffer, 0, count);
          digest.update(buffer, 0, count);
        }
        destination.getFD().sync();
      }
      StringBuilder actual = new StringBuilder();
      for (byte value : digest.digest())
        actual.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
      if (!BUNDLED_SHA256.equals(actual.toString()))
        throw new IOException("The bundled extractor checksum did not match");
      Os.rename(temporary.getAbsolutePath(), installed.getAbsolutePath());
    } finally {
      temporary.delete();
    }
  }
}
