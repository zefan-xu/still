// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import org.still.saver.ui.Sheet;
import org.still.saver.ui.Theme;
import org.still.saver.ui.Ui;

/**
 * The share target. A sheet over the app that shared the link: look up, choose, save, and get
 * back to what you were doing. Downloads continue after the sheet closes.
 */
public final class ShareActivity extends Activity implements SaveSession.Host {
  private static final long ADDED_DISMISS_MS = 2500;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private Ui ui;
  private SaveSession session;
  private Sheet sheet;
  private ResultsSheet results;
  private boolean closing;
  private final Runnable autoClose = this::close;

  @Override
  public void onCreate(Bundle state) {
    Theme theme = Theme.resolve(this);
    setTheme(theme.dark ? R.style.Still_Share_Dark : R.style.Still_Share);
    super.onCreate(state);
    ui = new Ui(this, theme);
    View content = new View(this);
    FrameLayout root = ui.screen(content);
    root.setBackgroundColor(0);
    sheet = new Sheet(ui, root);
    results = new ResultsSheet(ui, sheet, true);
    setContentView(root);
    root.requestApplyInsets();
    Object retained = getLastNonConfigurationInstance();
    if (retained instanceof SaveSession) session = (SaveSession) retained;
    else {
      // Rotation keeps the retained session; only a new share (or a restored process) looks up.
      session = new SaveSession(this);
      session.find(getIntent().getStringExtra(Intent.EXTRA_TEXT));
    }
    root.post(() -> session.attach(this));
  }

  @Override
  public void onSessionChanged(SaveSession s) {
    if (closing) return;
    handler.removeCallbacks(autoClose);
    if (s.phase() == SaveSession.Phase.IDLE) {
      close();
      return;
    }
    if (s.phase() == SaveSession.Phase.READY && s.takeAutoSave()) {
      s.save(this);
      return;
    }
    View view =
        results.build(
            s,
            new ResultsSheet.Actions() {
              @Override
              public void save() {
                session.save(ShareActivity.this);
              }

              @Override
              public void cancel() {
                session.cancelLookup();
              }

              @Override
              public void close() {
                ShareActivity.this.close();
              }

              @Override
              public void openApp() {
                startActivity(
                    new Intent(ShareActivity.this, MainActivity.class)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                ShareActivity.this.close();
              }

              @Override
              public void openTelegram() {
                startActivity(
                    TelegramWebActivity.intent(ShareActivity.this, session.submitted()));
              }
            });
    if (sheet.isShowing() && results.redraw()) sheet.update(view);
    else sheet.show(view);
    sheet.setOnDismiss(this::close);
    if (s.phase() == SaveSession.Phase.ADDED) handler.postDelayed(autoClose, ADDED_DISMISS_MS);
  }

  private void close() {
    if (closing) return;
    closing = true;
    handler.removeCallbacks(autoClose);
    sheet.setOnDismiss(null);
    sheet.dismiss();
    finish();
    overridePendingTransition(0, 0);
  }

  /** Test hook. */
  SaveSession session() {
    return session;
  }

  @Override
  public Object onRetainNonConfigurationInstance() {
    return session;
  }

  @Override
  public void onBackPressed() {
    if (!sheet.back()) close();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request == SaveSession.STORAGE_REQUEST)
      session.onPermissionResult(this, StoragePermission.granted(grants));
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    if (isChangingConfigurations()) session.detach();
    else session.dispose();
    super.onDestroy();
  }
}
