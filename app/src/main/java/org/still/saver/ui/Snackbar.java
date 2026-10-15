// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.ui;

import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.still.saver.R;

/** Calm confirmation at the bottom of the screen; replaces toasts inside the app. */
public final class Snackbar {
  private final Ui ui;
  private final FrameLayout host;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private LinearLayout bar;
  private TextView text;
  private Motion.Value entering;
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
    text = ui.body(message == null ? ui.activity.getString(R.string.snackbar_fallback) : message);
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
      button.setTextColor(t.onInkAccent);
      bar.addView(button);
    }
    FrameLayout.LayoutParams params =
        new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    params.setMargins(ui.dp(16), 0, ui.dp(16), ui.insetBottom() + ui.dp(16));
    host.addView(bar, params);
    View shown = bar;
    float lift = ui.dp(16);
    entering =
        new Motion.Value(
            shown,
            0,
            0.08f,
            p -> {
              shown.setAlpha(p);
              shown.setTranslationY((1 - p) * lift);
            });
    entering.to(1);
    handler.postDelayed(hide, action == null ? 3500 : 5000);
  }

  public String current() {
    return bar == null ? null : text.getText().toString();
  }

  /** Clears at once; the old bar fades out in the host's overlay, where it takes no input. */
  public void hide() {
    handler.removeCallbacks(hide);
    if (entering != null) entering.stop();
    entering = null;
    if (bar != null) {
      View leaving = bar;
      float from = leaving.getAlpha();
      host.getOverlay().add(leaving);
      Motion.play(
          host,
          0.24f,
          age -> {
            if (age == Motion.END) host.getOverlay().remove(leaving);
            else leaving.setAlpha(from * (1 - Motion.smooth(0, 0.18f, age)));
          });
    }
    bar = null;
    text = null;
  }
}
