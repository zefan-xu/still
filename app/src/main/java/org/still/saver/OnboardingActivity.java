// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.still.saver.core.Platforms;
import org.still.saver.ui.Motion;
import org.still.saver.ui.Sheet;
import org.still.saver.ui.Theme;
import org.still.saver.ui.TutorialFigure;
import org.still.saver.ui.Ui;

/**
 * A first-run tutorial over one animated figure: share a post to Still from any app, or paste a
 * link here, and either way it lands in the library. It opens once, over the home screen, on a
 * launcher start with nothing saved yet; the share sheet never waits for it. Settings replays it.
 */
public final class OnboardingActivity extends Activity {
  static final String SEEN = "onboarded";
  private static final String STEP = "step";
  private static final int LAST = TutorialFigure.STEPS - 1;
  private static final int[] TITLES = {
    R.string.onboarding_share_title,
    R.string.onboarding_paste_title,
    R.string.onboarding_library_title
  };

  private Theme theme;
  private Ui ui;
  private Sheet sheet;
  private TutorialFigure figure;
  private final View[] pages = new View[TutorialFigure.STEPS];
  private final Motion.Value[] dots = new Motion.Value[TutorialFigure.STEPS];
  private LinearLayout dotsRow;
  private TextView skip, paste, next;
  private Motion.Value turn, ending;
  private int step = -1, leaving = -1, direction = 1;
  private GestureDetector swipe;

  /** True when this launch should open the tutorial; anyone with saved files has used Still. */
  static boolean due(Context context, Intent launch) {
    if (launch == null || !Intent.ACTION_MAIN.equals(launch.getAction())) return false;
    SharedPreferences prefs = DownloadStore.prefs(context);
    if (prefs.getBoolean(SEEN, false)) return false;
    if (!"[]".equals(prefs.getString("downloads", "[]"))) {
      markSeen(context);
      return false;
    }
    return true;
  }

  static void markSeen(Context context) {
    DownloadStore.prefs(context).edit().putBoolean(SEEN, true).apply();
  }

  @Override
  public void onCreate(Bundle state) {
    theme = Theme.apply(this);
    super.onCreate(state);
    ui = new Ui(this, theme);
    setTitle(R.string.onboarding_window_title);
    build();
    int restored = state == null ? 0 : state.getInt(STEP, 0);
    show(Math.max(0, Math.min(LAST, restored)), false);
  }

  private void build() {
    skip = ui.quietButton(getString(R.string.onboarding_skip), this::skip);
    skip.setMinHeight(ui.dp(48));
    LinearLayout top = ui.row();
    top.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    top.setPadding(ui.dp(16), ui.dp(8), ui.dp(12), 0);
    top.addView(skip);

    figure = new TutorialFigure(this);
    FrameLayout stage = new FrameLayout(this);
    stage.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
    stage.addView(figure, new FrameLayout.LayoutParams(-1, -1));

    // Every page is laid out at once, so the tallest sets the height and turning never reflows.
    FrameLayout stack = new FrameLayout(this);
    for (int i = 0; i < pages.length; i++) {
      pages[i] = page(i);
      pages[i].setVisibility(View.INVISIBLE);
      stack.addView(pages[i], new FrameLayout.LayoutParams(-1, -2));
    }
    LinearLayout story = ui.column();
    story.addView(stack);
    story.addView(dots());
    story.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

    paste = ui.quietButton(getString(R.string.onboarding_paste), this::paste);
    paste.setMinHeight(ui.dp(48));
    next = ui.primaryButton(getString(R.string.onboarding_next), this::advance);
    next.setMinWidth(ui.dp(132));
    LinearLayout bottom = ui.row();
    bottom.setPadding(ui.dp(14), ui.dp(16), ui.dp(24), ui.dp(16));
    bottom.addView(paste);
    bottom.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    bottom.addView(next);
    ending = new Motion.Value(bottom, 0, 0.08f, this::placeEnding);

    View content;
    if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
      LinearLayout side = ui.column();
      side.addView(top);
      side.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
      side.addView(story);
      side.addView(bottom);
      LinearLayout row = ui.row();
      row.addView(stage, new LinearLayout.LayoutParams(0, -1, 1));
      row.addView(side, new LinearLayout.LayoutParams(0, -1, 1));
      content = row;
    } else {
      LinearLayout column = ui.column();
      column.addView(top);
      column.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
      column.addView(story);
      column.addView(bottom);
      content = column;
    }
    FrameLayout root = ui.screen(content);
    sheet = new Sheet(ui, root);
    turn = new Motion.Value(root, 1, 0.1f, this::placePages);
    setContentView(root);
    root.requestApplyInsets();

    int slop = ui.dp(48);
    swipe =
        new GestureDetector(
            this,
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onFling(MotionEvent down, MotionEvent up, float vx, float vy) {
                if (down == null || sheet.isShowing()) return false;
                float dx = up.getX() - down.getX(), dy = up.getY() - down.getY();
                if (Math.abs(dx) < slop || Math.abs(dx) < 1.5f * Math.abs(dy)) return false;
                boolean rtl = root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                boolean forward = (dx < 0) != rtl;
                if (forward && step < LAST) show(step + 1, true);
                else if (!forward && step > 0) show(step - 1, true);
                return true;
              }
            });
  }

  private View page(int index) {
    LinearLayout page = ui.column();
    page.setPadding(ui.dp(28), ui.dp(8), ui.dp(28), 0);
    TextView title = ui.display(getString(TITLES[index]));
    title.setTextSize(30);
    if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
    page.addView(title);
    ui.gap(page, 10);
    TextView body = ui.secondary(body(index));
    body.setTextSize(16);
    page.addView(body);
    if (index == TutorialFigure.SAVED) {
      ui.gap(page, 14);
      page.addView(
          ui.caption(
              getString(
                  R.string.onboarding_platforms, Platforms.names(getString(R.string.list_and)))));
      ui.gap(page, 4);
      TextView links =
          ui.quietButton(
              getString(R.string.links_see), () -> sheet.show(SupportedLinks.build(ui, sheet)));
      links.setMinHeight(ui.dp(48));
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
      params.setMarginStart(-ui.dp(14));
      page.addView(links, params);
    }
    return page;
  }

  private String body(int index) {
    switch (index) {
      case TutorialFigure.SHARE:
        return getString(R.string.onboarding_share_body);
      case TutorialFigure.PASTE:
        return getString(R.string.onboarding_paste_body);
      default:
        return getString(R.string.onboarding_library_body, DownloadStore.folder(this));
    }
  }

  private View dots() {
    dotsRow = ui.row();
    dotsRow.setPadding(ui.dp(28), ui.dp(18), ui.dp(28), 0);
    dotsRow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    int rest = Theme.mix(theme.hairline, theme.tertiary, 0.45f);
    for (int i = 0; i < dots.length; i++) {
      View dot = new View(this);
      dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      GradientDrawable fill = ui.shape(rest, 3, 0);
      dot.setBackground(fill);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ui.dp(6), ui.dp(6));
      params.setMarginEnd(ui.dp(6));
      dotsRow.addView(dot, params);
      dots[i] =
          new Motion.Value(
              dot,
              0,
              0.08f,
              p -> {
                params.width = ui.dp(6 + 14 * p);
                dot.setLayoutParams(params);
                fill.setColor(Theme.mix(rest, theme.ink, p));
              });
    }
    return dotsRow;
  }

  private void show(int to, boolean animate) {
    if (to == step) return;
    int from = step;
    step = to;
    direction = from < 0 || to > from ? 1 : -1;
    figure.show(to);
    for (int i = 0; i < dots.length; i++) {
      if (animate) dots[i].to(i == to ? 1 : 0);
      else dots[i].jump(i == to ? 1 : 0);
    }
    dotsRow.setContentDescription(
        getString(R.string.onboarding_step_description, to + 1, TutorialFigure.STEPS));
    next.setText(to == LAST ? R.string.onboarding_start : R.string.onboarding_next);
    if (animate) ending.to(to == LAST ? 1 : 0);
    else ending.jump(to == LAST ? 1 : 0);
    if (leaving >= 0) placeLeft(pages[leaving]);
    leaving = from;
    pages[to].setVisibility(View.VISIBLE);
    if (animate && from >= 0) {
      turn.jump(0);
      turn.to(1);
    } else turn.jump(1);
  }

  /** 0 shows the page being left, 1 the new one; they cross with a short slide. */
  private void placePages(float p) {
    if (step < 0) return;
    float shift = ui.dp(16) * direction;
    if (leaving >= 0) {
      View out = pages[leaving];
      out.setAlpha(1 - Motion.smooth(0, 0.5f, p));
      out.setTranslationX(-shift * p);
      if (p >= 1) {
        placeLeft(out);
        leaving = -1;
      }
    }
    float in = Motion.smooth(0.3f, 1, p);
    View page = pages[step];
    page.setAlpha(in);
    page.setTranslationX(shift * (1 - in));
  }

  private static void placeLeft(View page) {
    page.setVisibility(View.INVISIBLE);
    page.setAlpha(1);
    page.setTranslationX(0);
  }

  /** 1 on the last step: Skip gives way to Paste a link. */
  private void placeEnding(float p) {
    skip.setAlpha(1 - p);
    skip.setVisibility(p < 1 ? View.VISIBLE : View.INVISIBLE);
    paste.setAlpha(p);
    paste.setVisibility(p > 0 ? View.VISIBLE : View.INVISIBLE);
  }

  private void advance() {
    if (step < LAST) show(step + 1, true);
    else leave(null);
  }

  private void skip() {
    leave(null);
  }

  private void paste() {
    leave(
        new Intent(this, MainActivity.class)
            .putExtra(MainActivity.EXTRA_COMPOSE, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
  }

  private void leave(Intent then) {
    markSeen(this);
    if (then != null) startActivity(then);
    finish();
    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    if (swipe != null) swipe.onTouchEvent(event);
    return super.dispatchTouchEvent(event);
  }

  @Override
  public void onBackPressed() {
    if (sheet.back()) return;
    if (step > 0) show(step - 1, true);
    else skip();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (Theme.resolve(this).dark != theme.dark) recreate();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putInt(STEP, step);
  }
}
