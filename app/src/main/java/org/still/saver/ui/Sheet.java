// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.ui;

import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import org.still.saver.R;

/**
 * A bottom sheet drawn inside the screen's root. One sheet at a time: {@link #replace} swaps the
 * content (an action list becoming its confirmation) instead of stacking dialogs.
 */
public final class Sheet {
  public interface Validator {
    /** Returns an error to show under the field, or null to accept. */
    String check(String value);
  }

  public interface Choice {
    void chosen(int index);
  }

  public static final class Action {
    final String label;
    final int icon;
    final boolean destructive;
    final Runnable run;

    public Action(String label, int icon, boolean destructive, Runnable run) {
      this.label = label;
      this.icon = icon;
      this.destructive = destructive;
      this.run = run;
    }
  }

  private static final float OPEN_SECONDS = 0.08f, CLOSE_SECONDS = 0.06f;
  private final Ui ui;
  private final FrameLayout host;
  private View scrim;
  private LinearLayout panel;
  private ScrollView scroll;
  private Motion.Value opening;
  private Motion.Loop swap, leaving;
  private View leavingPanel, leavingScrim;
  private Runnable onDismiss;
  private boolean cancelable = true;

  public Sheet(Ui ui, FrameLayout host) {
    this.ui = ui;
    this.host = host;
    // Edge-to-edge windows do not resize for the keyboard; keep the sheet above it.
    ui.onInsetsChanged(
        () -> {
          if (panel != null)
            panel.setPadding(0, ui.dp(10), 0, ui.insetBottom() + ui.dp(12));
        });
  }

  public boolean isShowing() {
    return panel != null;
  }

  public void setOnDismiss(Runnable listener) {
    onDismiss = listener;
  }

  public void setCancelable(boolean value) {
    cancelable = value;
  }

  /** Back press handling for the hosting activity. */
  public boolean back() {
    if (!isShowing()) return false;
    if (cancelable) dismiss();
    return true;
  }

  public void show(View content) {
    if (isShowing()) {
      replace(content);
      return;
    }
    // A sheet opened as the last one leaves (an action becoming its confirmation) takes over
    // its scrim and fades in place instead of sliding up again.
    boolean handoff = leaving != null;
    float scrimFrom = handoff ? leavingScrim.getAlpha() : 0;
    endLeaving();
    Theme t = ui.theme;
    scrim = new View(ui.activity);
    scrim.setBackgroundColor(t.scrim);
    scrim.setContentDescription("Close");
    scrim.setOnClickListener(
        v -> {
          if (cancelable) dismiss();
        });
    host.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
    panel = ui.column();
    GradientDrawable background = new GradientDrawable();
    background.setColor(t.surface);
    float r = ui.dp(24);
    background.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
    panel.setBackground(background);
    panel.setClickable(true);
    panel.setPadding(0, ui.dp(10), 0, ui.insetBottom() + ui.dp(12));
    View handle = new View(ui.activity);
    handle.setBackground(ui.shape(t.hairline, 2, 0));
    LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(4));
    handleParams.gravity = Gravity.CENTER_HORIZONTAL;
    handleParams.bottomMargin = ui.dp(6);
    panel.addView(handle, handleParams);
    scroll = new ScrollView(ui.activity);
    scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
    scroll.addView(content);
    panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
    FrameLayout.LayoutParams params =
        new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    params.topMargin = ui.insetTop() + ui.dp(48);
    host.addView(panel, params);
    if (Build.VERSION.SDK_INT >= 28) panel.setAccessibilityPaneTitle("Sheet");
    hideBehind(true);
    View enteringScrim = scrim, enteringPanel = panel;
    float lift = ui.dp(24);
    opening =
        new Motion.Value(
            panel,
            0,
            OPEN_SECONDS,
            p -> {
              enteringScrim.setAlpha(scrimFrom + (1 - scrimFrom) * p);
              if (handoff) {
                enteringPanel.setAlpha(p);
                enteringPanel.setTranslationY((1 - p) * lift);
              } else enteringPanel.setTranslationY((1 - p) * travel(enteringPanel));
            });
    opening.to(1);
    content.post(() -> focusFirst(content));
  }

  /** Swaps in a different screen, crossfading from the old content. */
  public void replace(View content) {
    if (!isShowing()) {
      show(content);
      return;
    }
    finishSwap();
    View old = scroll.getChildAt(0);
    int dx = scroll.getLeft(), dy = scroll.getTop() - scroll.getScrollY();
    scroll.removeAllViews();
    scroll.addView(content);
    scroll.scrollTo(0, 0);
    if (old != null && old.getHeight() > 0) {
      LinearLayout owner = panel;
      old.offsetLeftAndRight(dx);
      old.offsetTopAndBottom(dy);
      owner.getOverlay().add(old);
      swap =
          Motion.play(
              owner,
              0.3f,
              age -> {
                if (age == Motion.END) {
                  owner.getOverlay().remove(old);
                  content.setAlpha(1);
                } else {
                  old.setAlpha(1 - Motion.smooth(0, 0.12f, age));
                  content.setAlpha(Motion.smooth(0.04f, 0.22f, age));
                }
              });
    }
    content.post(() -> focusFirst(content));
  }

  /** Re-renders the same screen in place: no crossfade, and the scroll position is kept. */
  public void update(View content) {
    if (!isShowing()) {
      show(content);
      return;
    }
    finishSwap();
    scroll.removeAllViews();
    scroll.addView(content);
  }

  /**
   * Closes at once: the state, listener and back handling change synchronously, and only the old
   * views slide out in the host's overlay, where they take no input.
   */
  public void dismiss() {
    if (!isShowing()) return;
    finishSwap();
    opening.stop();
    View oldPanel = panel, oldScrim = scrim;
    panel = null;
    scrim = null;
    scroll = null;
    opening = null;
    cancelable = true;
    hideKeyboard(oldPanel);
    hideBehind(false);
    leave(oldPanel, oldScrim);
    Runnable listener = onDismiss;
    onDismiss = null;
    if (listener != null) listener.run();
  }

  private float travel(View view) {
    return view.getHeight() > 0
        ? view.getHeight()
        : ui.activity.getResources().getDisplayMetrics().heightPixels;
  }

  private void leave(View oldPanel, View oldScrim) {
    endLeaving();
    host.getOverlay().add(oldScrim);
    host.getOverlay().add(oldPanel);
    leavingPanel = oldPanel;
    leavingScrim = oldScrim;
    float fromY = oldPanel.getTranslationY(), toY = travel(oldPanel);
    float fromAlpha = oldScrim.getAlpha();
    Motion.State shown = new Motion.State(1);
    leaving =
        new Motion.Loop(
            host,
            (dt, calm) -> {
              boolean moving = Motion.settle(shown, 0, dt, CLOSE_SECONDS, calm) && shown.x > 0.004f;
              oldScrim.setAlpha(fromAlpha * shown.x);
              oldPanel.setTranslationY(fromY + (toY - fromY) * (1 - shown.x));
              if (!moving) endLeaving();
              return moving;
            });
    leaving.start();
  }

  private void endLeaving() {
    if (leaving == null) return;
    leaving.stop();
    leaving = null;
    host.getOverlay().remove(leavingPanel);
    host.getOverlay().remove(leavingScrim);
    leavingPanel = null;
    leavingScrim = null;
  }

  private void finishSwap() {
    if (swap == null) return;
    Motion.Loop running = swap;
    swap = null;
    running.finish();
  }

  private void hideBehind(boolean hidden) {
    View content = host.getChildAt(0);
    if (content != null)
      content.setImportantForAccessibility(
          hidden
              ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
              : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
  }

  private void focusFirst(View content) {
    View first = content.findViewWithTag("sheet-title");
    if (first != null) first.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);
  }

  private void hideKeyboard(View view) {
    InputMethodManager input =
        (InputMethodManager) ui.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
    input.hideSoftInputFromWindow(view.getWindowToken(), 0);
  }

  // ---- Content builders ----

  /** Padded column with a serif title; callers append rows. */
  public LinearLayout frame(String title, String message) {
    LinearLayout box = ui.column();
    box.setPadding(0, ui.dp(8), 0, ui.dp(4));
    if (title != null) {
      TextView heading = ui.title(title, 22);
      heading.setTag("sheet-title");
      heading.setMaxLines(3);
      heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
      heading.setPadding(ui.dp(24), ui.dp(4), ui.dp(24), ui.dp(4));
      if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
      box.addView(heading);
    }
    if (message != null) {
      TextView body = ui.secondary(message);
      body.setPadding(ui.dp(24), ui.dp(6), ui.dp(24), ui.dp(4));
      box.addView(body);
    }
    ui.gap(box, 10);
    return box;
  }

  public LinearLayout actionRow(Action action) {
    LinearLayout row = ui.row();
    row.setPadding(ui.dp(24), 0, ui.dp(24), 0);
    row.setMinimumHeight(ui.dp(56));
    int color = action.destructive ? ui.theme.error : ui.theme.ink;
    if (action.icon != 0) {
      ImageView icon = ui.icon(action.icon, color, 24);
      row.addView(icon);
      View space = new View(ui.activity);
      row.addView(space, new LinearLayout.LayoutParams(ui.dp(18), 1));
    }
    TextView label = ui.body(action.label);
    label.setTextColor(color);
    row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    row.setBackground(ui.ripple(null, ui.shape(ui.theme.ink, 0, 0)));
    row.setFocusable(true);
    row.setClickable(true);
    row.setOnClickListener(v -> action.run.run());
    return row;
  }

  /** Each action runs after the sheet is dismissed, so it may open a confirmation sheet. */
  public void actions(String title, List<Action> actions) {
    LinearLayout box = frame(title, null);
    // Action sheets are often titled by a post caption; keep it a quiet two-line label.
    TextView heading = box.findViewWithTag("sheet-title");
    if (heading != null) {
      heading.setTextSize(18);
      heading.setMaxLines(2);
    }
    for (Action action : actions)
      box.addView(
          actionRow(
              new Action(
                  action.label,
                  action.icon,
                  action.destructive,
                  () -> {
                    dismiss();
                    action.run.run();
                  })));
    show(box);
  }

  public void choice(String title, String[] labels, int selected, Choice choice) {
    LinearLayout box = frame(title, null);
    for (int i = 0; i < labels.length; i++) {
      final int index = i;
      LinearLayout row = ui.row();
      row.setPadding(ui.dp(24), 0, ui.dp(24), 0);
      row.setMinimumHeight(ui.dp(56));
      TextView label = ui.body(labels[i]);
      row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
      if (i == selected) row.addView(ui.icon(R.drawable.ic_check, ui.theme.accentStrong, 24));
      row.setBackground(ui.ripple(null, ui.shape(ui.theme.ink, 0, 0)));
      row.setFocusable(true);
      row.setClickable(true);
      row.setSelected(i == selected);
      row.setContentDescription(labels[i] + (i == selected ? ", selected" : ""));
      row.setOnClickListener(
          v -> {
            dismiss();
            choice.chosen(index);
          });
      box.addView(row);
    }
    show(box);
  }

  public void confirm(
      String title,
      String message,
      String cancelLabel,
      String confirmLabel,
      boolean destructive,
      Runnable confirmed) {
    LinearLayout box = frame(title, message);
    LinearLayout buttons = ui.row();
    buttons.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(4));
    buttons.addView(ui.secondaryButton(cancelLabel, this::dismiss), weighted());
    View space = new View(ui.activity);
    buttons.addView(space, new LinearLayout.LayoutParams(ui.dp(12), 1));
    TextView confirm =
        ui.primaryButton(
            confirmLabel,
            () -> {
              dismiss();
              confirmed.run();
            });
    if (destructive) confirm.setBackground(ui.ripple(ui.shape(ui.theme.error, 26, 0), null));
    if (destructive) confirm.setTextColor(0xffffffff);
    buttons.addView(confirm, weighted());
    box.addView(buttons);
    show(box);
  }

  public void form(
      String title,
      String message,
      EditText field,
      String confirmLabel,
      Validator validator,
      Choice accepted) {
    LinearLayout box = frame(title, message);
    LinearLayout inner = ui.column();
    inner.setPadding(ui.dp(24), ui.dp(4), ui.dp(24), 0);
    inner.addView(field, new LinearLayout.LayoutParams(-1, -2));
    TextView error = ui.caption("");
    error.setTextColor(ui.theme.error);
    error.setPadding(ui.dp(4), ui.dp(8), 0, 0);
    error.setVisibility(View.GONE);
    error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    inner.addView(error);
    ui.gap(inner, 14);
    TextView[] save = new TextView[1];
    save[0] =
        ui.primaryButton(
            confirmLabel,
            () -> {
              String problem = validator.check(field.getText().toString().trim());
              if (problem != null) {
                error.setText(problem);
                error.setVisibility(View.VISIBLE);
                // On short screens the new line can push the button under the keyboard.
                save[0].post(
                    () ->
                        save[0].requestRectangleOnScreen(
                            new android.graphics.Rect(
                                0, 0, save[0].getWidth(), save[0].getHeight() + ui.dp(12)),
                            true));
                return;
              }
              dismiss();
              accepted.chosen(0);
            });
    inner.addView(save[0], new LinearLayout.LayoutParams(-1, -2));
    box.addView(inner);
    show(box);
    field.requestFocus();
    field.post(
        () ->
            ((InputMethodManager)
                    ui.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE))
                .showSoftInput(field, 0));
  }

  private static LinearLayout.LayoutParams weighted() {
    return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
  }
}
