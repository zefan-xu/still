// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.ui;

import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Calm confirmation at the bottom of the screen; replaces toasts inside the app. */
public final class Snackbar {
  private final Ui ui;
  private final FrameLayout host;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private LinearLayout bar;
  private TextView text;
  private final Runnable hide = this::hide;

  public Snackbar(Ui ui, FrameLayout host) {
    this.ui = ui;
    this.host = host;
  }

  public void show(String message) {
    show(message, null, null);
  }

  public void show(String message, String action, Runnable run) {
    hide();
    Theme t = ui.theme;
    bar = ui.row();
    bar.setPadding(ui.dp(18), ui.dp(6), ui.dp(6), ui.dp(6));
    bar.setMinimumHeight(ui.dp(52));
    bar.setBackground(ui.shape(t.ink, 14, 0));
    bar.setElevation(ui.dp(6));
    text = ui.body(message == null ? "The action could not be completed." : message);
    text.setTextColor(t.onInk);
    text.setTextSize(15);
    text.setMaxLines(3);
    text.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    bar.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
    if (action != null) {
      TextView button =
          ui.quietButton(
              action,
              () -> {
                hide();
                run.run();
              });
      button.setTextColor(t.dark ? 0xff9e4a2e : 0xffe8a58c);
      bar.addView(button);
    }
    FrameLayout.LayoutParams params =
        new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    params.setMargins(ui.dp(16), 0, ui.dp(16), ui.insetBottom() + ui.dp(16));
    host.addView(bar, params);
    bar.setAlpha(0);
    bar.setTranslationY(ui.dp(16));
    bar.animate().alpha(1).translationY(0).setDuration(180).start();
    handler.postDelayed(hide, action == null ? 3500 : 5000);
  }

  public String current() {
    return bar == null ? null : text.getText().toString();
  }

  public void hide() {
    handler.removeCallbacks(hide);
    if (bar != null) host.removeView(bar);
    bar = null;
    text = null;
  }
}
