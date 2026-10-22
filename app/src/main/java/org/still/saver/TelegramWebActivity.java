// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.still.saver.core.TelegramLink;
import org.still.saver.ui.Theme;
import org.still.saver.ui.Ui;

/**
 * Telegram Web inside the app's own WebView. The user signs in here, then Still copies the
 * already-authorized video blob into Downloads without exporting cookies or session keys.
 */
public final class TelegramWebActivity extends Activity {
  static final String EXTRA_LINK = "org.still.saver.extra.TELEGRAM_LINK";
  private static final int STORAGE_REQUEST = 43;
  private static final long CAPTURE_IDLE_TIMEOUT_MS = 60000;
  private static final String WEB_HOME = "https://web.telegram.org/k/";
  private static final String CAPTURE_SCRIPT = "telegram_capture.js";

  private final Bridge bridge = new Bridge();
  private final Handler main = new Handler(Looper.getMainLooper());
  private Ui ui;
  private WebView web;
  private TextView status, save;
  private TelegramLink link;
  private volatile String captureToken;
  private volatile long captureGeneration;
  private File captureFile;
  private RandomAccessFile output;
  private long received;
  private long expected;
  private String captureMime;
  private boolean pendingCapture;
  private volatile boolean destroyed;
  private final Runnable captureTimeout =
      () -> {
        long generation;
        synchronized (this) {
          if (captureToken == null) return;
          generation = captureGeneration;
        }
        captureFailed(generation);
      };

  public static Intent intent(Activity activity, String link) {
    Intent intent = new Intent(activity, TelegramWebActivity.class);
    if (link != null) intent.putExtra(EXTRA_LINK, link);
    return intent;
  }

  @Override
  @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
  protected void onCreate(Bundle state) {
    Theme theme = Theme.apply(this);
    super.onCreate(state);
    ui = new Ui(this, theme);
    String source = getIntent().getStringExtra(EXTRA_LINK);
    link = source == null ? null : TelegramLink.from(source);

    LinearLayout content = ui.column();
    LinearLayout toolbar = ui.row();
    toolbar.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), 0);
    toolbar.addView(
        ui.iconButton(R.drawable.ic_arrow_back, getString(R.string.action_back), this::finish));
    TextView title =
        ui.label(
            getString(link == null ? R.string.telegram_title : R.string.telegram_video_title), 18);
    title.setPadding(ui.dp(8), 0, ui.dp(8), 0);
    toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    save = ui.quietButton(getString(R.string.telegram_save_video), this::requestCapture);
    toolbar.addView(save);
    content.addView(toolbar);

    status = ui.caption(getString(R.string.telegram_sign_in_hint));
    status.setPadding(ui.dp(24), ui.dp(4), ui.dp(24), ui.dp(8));
    content.addView(status);

    web = new WebView(this);
    WebSettings settings = web.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setDatabaseEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    web.setBackgroundColor(theme.bg);
    web.setWebChromeClient(new WebChromeClient());
    web.setWebViewClient(new TelegramClient());
    web.addJavascriptInterface(bridge, "StillTelegram");
    content.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));

    FrameLayout root = ui.screen(content);
    setContentView(root);
    root.requestApplyInsets();
    web.loadUrl(link == null ? WEB_HOME : WEB_HOME + link.webHash());
  }

  private final class TelegramClient extends WebViewClient {
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
      if (allowed(request.getUrl().toString())) return false;
      showStatus(getString(R.string.telegram_external_link_blocked), true);
      return true;
    }

    @Override
    public void onPageFinished(WebView view, String url) {
      super.onPageFinished(view, url);
      if (link == null) return;
      if (url.contains("#")) return;
      web.evaluateJavascript("location.hash = '" + link.webHash() + "';", null);
    }

    @Override
    public void onReceivedSslError(
        WebView view, SslErrorHandler handler, android.net.http.SslError error) {
      handler.cancel();
      showStatus(getString(R.string.telegram_secure_connection_failed), true);
    }
  }

  private static boolean allowed(String value) {
    try {
      URI uri = URI.create(value);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return false;
      String host = uri.getHost().toLowerCase(Locale.ROOT);
      return host.equals("t.me")
          || host.endsWith(".t.me")
          || host.equals("telegram.org")
          || host.endsWith(".telegram.org");
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  private void requestCapture() {
    if (!StoragePermission.ensure(this, STORAGE_REQUEST)) {
      pendingCapture = true;
      return;
    }
    startCapture();
  }

  private synchronized void startCapture() {
    if (destroyed) return;
    if (captureToken != null) return;
    captureGeneration++;
    captureToken = UUID.randomUUID().toString().replace("-", "");
    String script;
    try {
      script = readAsset(CAPTURE_SCRIPT).replace("__STILL_TOKEN__", captureToken);
    } catch (IOException e) {
      captureToken = null;
      showStatus(getString(R.string.telegram_capture_failed), true);
      return;
    }
    save.setEnabled(false);
    showStatus(getString(R.string.telegram_finding_video), false);
    armCaptureTimeout();
    web.evaluateJavascript(
        "window.__stillTelegramCaptureRunning=false;"
            + "if(window.__stillTelegramCaptureAbort)window.__stillTelegramCaptureAbort();"
            + script,
        null);
  }

  private String readAsset(String name) throws IOException {
    try (InputStream input = getAssets().open(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  private void showStatus(String text, boolean error) {
    runOnUiThread(
        () -> {
          status.setText(text);
          status.setTextColor(error ? ui.theme.error : ui.theme.tertiary);
        });
  }

  private void captureFinished(long generation) {
    main.removeCallbacks(captureTimeout);
    main.post(
        () -> {
          synchronized (TelegramWebActivity.this) {
            if (destroyed || generation != captureGeneration) return;
          }
          save.setEnabled(true);
          showStatus(getString(R.string.telegram_saved), false);
        });
  }

  private void captureFailed(long generation) {
    main.removeCallbacks(captureTimeout);
    String failedToken;
    synchronized (this) {
      if (generation != captureGeneration) return;
      failedToken = captureToken;
      captureToken = null;
      closeCapture(false);
    }
    main.post(
        () -> {
          synchronized (TelegramWebActivity.this) {
            if (destroyed || generation != captureGeneration) return;
          }
          if (web != null) {
            web.evaluateJavascript(
                "window.__stillTelegramCaptureRunning=false;"
                    + "if(window.__stillTelegramCaptureAbort)"
                    + "window.__stillTelegramCaptureAbort('"
                    + failedToken
                    + "');",
                null);
          }
          save.setEnabled(true);
          showStatus(getString(R.string.telegram_capture_failed), true);
        });
  }

  private synchronized void closeCapture(boolean keep) {
    if (output != null) {
      try {
        output.close();
      } catch (IOException ignored) {
        /* The result was already invalid. */
      }
      output = null;
    }
    if (!keep && captureFile != null) captureFile.delete();
    captureFile = null;
  }

  private void armCaptureTimeout() {
    main.removeCallbacks(captureTimeout);
    main.postDelayed(captureTimeout, CAPTURE_IDLE_TIMEOUT_MS);
  }

  private final class Bridge {
    private boolean token(String value) {
      return captureToken != null && captureToken.equals(value);
    }

    @JavascriptInterface
    public void begin(String token, String mime, long total) {
      long generation;
      boolean failed = false;
      synchronized (TelegramWebActivity.this) {
        if (!token(token) || destroyed) return;
        generation = captureGeneration;
        if (mime == null || !mime.toLowerCase(Locale.ROOT).startsWith("video/") || total < 0) {
          failed = true;
        } else {
          try {
            closeCapture(false);
            captureMime = mime.toLowerCase(Locale.ROOT).split(";")[0];
            expected = total;
            received = 0;
            captureFile = File.createTempFile("still-telegram-", ".part", getCacheDir());
            output = new RandomAccessFile(captureFile, "rw");
            showStatus(getString(R.string.telegram_downloading), false);
            armCaptureTimeout();
          } catch (IOException e) {
            failed = true;
          }
        }
      }
      if (failed) captureFailed(generation);
    }

    @JavascriptInterface
    public void chunk(String token, String encoded) {
      long generation;
      boolean failed = false;
      synchronized (TelegramWebActivity.this) {
        if (!token(token) || destroyed || output == null || encoded == null) return;
        generation = captureGeneration;
        try {
          byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
          if (bytes.length == 0 || (expected > 0 && received + bytes.length > expected))
            throw new IOException();
          output.write(bytes);
          received += bytes.length;
        } catch (IOException | IllegalArgumentException e) {
          failed = true;
        }
      }
      if (failed) captureFailed(generation);
    }

    @JavascriptInterface
    public void progress(String token, long sent, long total) {
      synchronized (TelegramWebActivity.this) {
        if (!token(token) || destroyed) return;
        main.post(TelegramWebActivity.this::armCaptureTimeout);
        showStatus(getString(R.string.telegram_downloading), false);
      }
    }

    @JavascriptInterface
    public void finish(String token) {
      File completed;
      File closeFailure = null;
      String mime;
      String activeToken;
      long generation;
      synchronized (TelegramWebActivity.this) {
        if (!token(token)) return;
        generation = captureGeneration;
        if (output == null || received <= 0 || (expected > 0 && received != expected)) {
          completed = null;
          mime = null;
          activeToken = null;
        } else {
          completed = captureFile;
          mime = captureMime;
          activeToken = captureToken;
          try {
            output.close();
          } catch (IOException e) {
            closeFailure = completed;
            completed = null;
          }
          output = null;
          captureFile = null;
          captureToken = null;
        }
      }
      if (completed == null) {
        if (closeFailure != null) closeFailure.delete();
        captureFailed(generation);
        return;
      }
      try {
        if ("video/mp4".equals(mime)) MediaRepair.repair(completed);
        if (destroyed || generation != captureGeneration) throw new IOException("Cancelled");
        publish(completed, mime, activeToken, generation);
        completed.delete();
        captureFinished(generation);
      } catch (Exception e) {
        completed.delete();
        captureFailed(generation);
      }
    }

    @JavascriptInterface
    public void fail(String token, String detail) {
      long generation;
      synchronized (TelegramWebActivity.this) {
        if (!token(token)) return;
        generation = captureGeneration;
      }
      captureFailed(generation);
    }
  }

  private void publish(File source, String mime, String token, long generation) throws IOException {
    checkPublication(generation);
    String extension =
        mime.equals("video/webm")
            ? ".webm"
            : mime.equals("video/quicktime")
                ? ".mov"
                : mime.equals("video/x-matroska") ? ".mkv" : ".mp4";
    String stem =
        (link == null ? "telegram_video_" + System.currentTimeMillis() : link.filenameStem())
            + "_"
            + token.substring(0, 6);
    String filename = stem + extension;
    ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
    values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
    File legacy = null;
    Uri uri;
    if (android.os.Build.VERSION.SDK_INT >= 29) {
      values.put(
          MediaStore.MediaColumns.RELATIVE_PATH,
          Environment.DIRECTORY_DOWNLOADS + "/" + DownloadStore.folder(this));
      values.put(MediaStore.MediaColumns.IS_PENDING, 1);
      uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
    } else {
      File directory =
          new File(
              Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
              DownloadStore.folder(this));
      if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
        throw new IOException("Could not create the Downloads folder");
      legacy = new File(directory, filename);
      values.put(MediaStore.MediaColumns.DATA, legacy.getAbsolutePath());
      uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
    }
    if (uri == null) throw new IOException("Could not create the Telegram video");
    boolean published = false;
    try {
      checkPublication(generation);
      try (InputStream input = new FileInputStream(source);
          OutputStream destination = getContentResolver().openOutputStream(uri, "w")) {
        if (destination == null) throw new IOException("Could not open the Telegram video");
        byte[] buffer = new byte[32768];
        int count;
        while ((count = input.read(buffer)) != -1) {
          checkPublication(generation);
          destination.write(buffer, 0, count);
        }
      }
      checkPublication(generation);
      if (android.os.Build.VERSION.SDK_INT >= 29) {
        ContentValues complete = new ContentValues();
        complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
        if (getContentResolver().update(uri, complete, null, null) != 1)
          throw new IOException("Could not publish the Telegram video");
      } else if (legacy != null) {
        MediaScannerConnection.scanFile(this, new String[] {legacy.getAbsolutePath()}, null, null);
      }
      checkPublication(generation);
      DownloadStore.recordLocal(
          this, uri, getString(R.string.telegram_video_title), filename, mime);
      published = true;
    } finally {
      if (!published) {
        try {
          getContentResolver().delete(uri, null, null);
        } catch (RuntimeException ignored) {
          /* A legacy file below is removed separately. */
        }
        if (legacy != null) legacy.delete();
      }
    }
  }

  private void checkPublication(long generation) throws IOException {
    if (destroyed || generation != captureGeneration) throw new IOException("Cancelled");
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request != STORAGE_REQUEST || !pendingCapture) return;
    pendingCapture = false;
    if (StoragePermission.granted(grants)) startCapture();
    else {
      save.setEnabled(true);
      showStatus(getString(R.string.storage_denied), true);
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (web != null) web.onResume();
  }

  @Override
  protected void onPause() {
    if (web != null) web.onPause();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    main.removeCallbacks(captureTimeout);
    synchronized (this) {
      destroyed = true;
      captureToken = null;
      closeCapture(false);
    }
    if (web != null) {
      web.removeJavascriptInterface("StillTelegram");
      web.stopLoading();
      web.destroy();
      web = null;
    }
    super.onDestroy();
  }
}
