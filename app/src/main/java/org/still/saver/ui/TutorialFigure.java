// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.ui;

import static org.still.saver.ui.FigureGeometry.PHONE_FACING;
import static org.still.saver.ui.FigureGeometry.PHONE_FLOAT;
import static org.still.saver.ui.FigureGeometry.PHONE_H;
import static org.still.saver.ui.FigureGeometry.PHONE_T;
import static org.still.saver.ui.FigureGeometry.PHONE_TILT;
import static org.still.saver.ui.FigureGeometry.PHONE_VIEW_H;
import static org.still.saver.ui.FigureGeometry.PHONE_VIEW_W;
import static org.still.saver.ui.FigureGeometry.SCREEN_BOTTOM;
import static org.still.saver.ui.FigureGeometry.SCREEN_H;
import static org.still.saver.ui.FigureGeometry.SCREEN_R;
import static org.still.saver.ui.FigureGeometry.SCREEN_RECESS;
import static org.still.saver.ui.FigureGeometry.SCREEN_W;
import static org.still.saver.ui.FigureGeometry.WORLD_ORIGIN;
import static org.still.saver.ui.FigureGeometry.WORLD_X;
import static org.still.saver.ui.FigureGeometry.WORLD_Y;
import static org.still.saver.ui.FigureGeometry.WORLD_Z;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import org.still.saver.R;

/**
 * The onboarding figure: an isometric phone floating over its halo, whose screen plays each step
 * for real. A post in another app is shared to Still and saved from Still's sheet; a link is
 * pasted into Still's field and saved; the photo drops into the library. The phone lifts and
 * leans toward the viewer as a step begins, dips under each tap and turns a little between
 * steps. A step plays, rests and plays again while on screen; a new step crossfades in; with
 * animations off each step holds one frame that tells its story.
 *
 * <p>The body and bezel are generated drawables (tools/figures/build.mjs) at the phone's rest
 * pose. Each frame the phone's face plane is projected at its current pose, and the drawables
 * follow the map from the rest face to that one, which is exact on the glass and within a
 * fraction of a unit elsewhere for these small moves.
 */
public final class TutorialFigure extends View {
  public static final int SHARE = 0, PASTE = 1, SAVED = 2, STEPS = 3;

  private static final int[] DESCRIPTIONS = {
    R.string.tutorial_share_description,
    R.string.tutorial_paste_description,
    R.string.tutorial_library_description,
  };

  /** Turn of the phone on each step, in degrees from its rest pose. */
  private static final float[] TURNS = {0, -4, 3};

  private static final float LIFT = 4.5f, LEAN = 3.5f, DIP = 1.1f;
  private static final float TURN_SECONDS = 0.3f, FADE_TAU = 0.09f;

  private final Drawable halo, body, bezel;
  private final TutorialScreens screens;
  private final Motion.Loop loop = new Motion.Loop(this, this::step);
  private final Runnable again = this::replay;
  private final Motion.State turn = new Motion.State(0);
  private final Matrix fit = new Matrix(), rest = new Matrix(), face = new Matrix();
  private final Matrix screen = new Matrix(), follow = new Matrix();
  private final float[] values = new float[9];
  private final float[] u = new float[3], v = new float[3], w = new float[3], o = new float[3];
  private final Path opening = new Path();
  private final RectF bounds = new RectF(-1, -1, SCREEN_W + 1, SCREEN_H + 1);
  private int step = -1, from = -1;
  private float age, fromAge, fade = 1;
  private boolean visible;

  public TutorialFigure(Context context) {
    super(context);
    Resources.Theme theme = context.getTheme();
    halo = theme.getDrawable(R.drawable.fig_phone_halo).mutate();
    body = theme.getDrawable(R.drawable.fig_phone_body).mutate();
    bezel = theme.getDrawable(R.drawable.fig_phone_bezel).mutate();
    screens = new TutorialScreens(context, Theme.resolve(context));
    int width = 1000, height = Math.round(width * PHONE_VIEW_H / PHONE_VIEW_W);
    for (Drawable layer : new Drawable[] {halo, body, bezel}) layer.setBounds(0, 0, width, height);
    opening.addRoundRect(bounds, SCREEN_R + 1, SCREEN_R + 1, Path.Direction.CW);
    pose(0, 0, 0, 0);
    faceMatrix(rest);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    setOnClickListener(view -> replay());
  }

  public void show(int to) {
    if (to == step) return;
    setContentDescription(getContext().getString(DESCRIPTIONS[to]));
    removeCallbacks(again);
    boolean calm = Motion.calm(getContext());
    if (step < 0 || !isAttachedToWindow() || calm) {
      step = to;
      from = -1;
      fade = 1;
      age = calm || !isAttachedToWindow() ? screens.still(to) : 0;
      turn.x = TURNS[to];
      turn.v = 0;
      invalidate();
      if (isAttachedToWindow() && !calm) loop.start();
      return;
    }
    from = step;
    fromAge = age;
    fade = 0;
    step = to;
    age = 0;
    loop.start();
  }

  /** Plays the step again, from a crossfade, once it has finished. */
  private void replay() {
    removeCallbacks(again);
    if (step < 0 || Motion.calm(getContext()) || loop.running() || age < screens.seconds(step)) return;
    from = step;
    fromAge = Motion.END;
    fade = 0;
    age = 0;
    loop.start();
  }

  private boolean step(float dt, boolean calm) {
    if (step < 0) return false;
    if (calm) {
      from = -1;
      fade = 1;
      age = screens.still(step);
      turn.x = TURNS[step];
      turn.v = 0;
      invalidate();
      return false;
    }
    age += dt;
    fade = Motion.follow(fade, 1, dt, FADE_TAU, false);
    if (fade >= 1) from = -1;
    boolean moving = Motion.settle(turn, TURNS[step], dt, TURN_SECONDS, false);
    moving |= age < screens.seconds(step) || from >= 0;
    invalidate();
    if (!moving && visible) {
      removeCallbacks(again);
      postDelayed(again, screens.rest(step));
    }
    return moving;
  }

  /** Rises from 0 as the step begins and settles back: the lift and lean that open a step. */
  private static float rising(float age) {
    return Motion.settled(age, 0.16f) * (1 - Motion.settled(age - 0.5f, 0.22f));
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (step < 0) return;
    from = -1;
    fade = 1;
    if (Motion.calm(getContext())) {
      age = screens.still(step);
      invalidate();
      return;
    }
    age = 0;
    loop.start();
  }

  @Override
  protected void onDetachedFromWindow() {
    removeCallbacks(again);
    loop.finish();
    super.onDetachedFromWindow();
  }

  @Override
  public void onVisibilityAggregated(boolean isVisible) {
    super.onVisibilityAggregated(isVisible);
    visible = isVisible;
    if (!isVisible) removeCallbacks(again);
    else if (!loop.running() && step >= 0 && age >= screens.seconds(step)) postDelayed(again, screens.rest(step));
  }

  @Override
  public CharSequence getAccessibilityClassName() {
    return "android.widget.ImageView";
  }

  /** Tapping replays the motion; screen readers get the description as a picture, not a button. */
  @Override
  public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    info.setClickable(false);
    info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    int width = resolveSize(Math.round(PHONE_VIEW_W * getResources().getDisplayMetrics().density), widthSpec);
    int height = resolveSize(Math.round(width * PHONE_VIEW_H / PHONE_VIEW_W), heightSpec);
    setMeasuredDimension(width, height);
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    float aw = w - getPaddingLeft() - getPaddingRight();
    float ah = h - getPaddingTop() - getPaddingBottom();
    float scale = Math.max(0, Math.min(aw / PHONE_VIEW_W, ah / PHONE_VIEW_H));
    fit.setScale(scale, scale);
    fit.postTranslate(
        getPaddingLeft() + (aw - PHONE_VIEW_W * scale) / 2,
        getPaddingTop() + (ah - PHONE_VIEW_H * scale) / 2);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    if (step < 0) return;
    boolean calm = Motion.calm(getContext());
    float rise = blend(calm ? 0 : rising(age), from >= 0 ? rising(fromAge) : 0);
    float press = blend(calm ? 0 : screens.press(step, age), from >= 0 ? screens.press(from, fromAge) : 0);
    pose(turn.x, LEAN * rise, LIFT * rise, DIP * Math.min(1, press));
    faceMatrix(face);
    rest.invert(follow);
    follow.postConcat(face);

    int base = canvas.save();
    canvas.concat(fit);
    canvas.scale(PHONE_VIEW_W / 1000, PHONE_VIEW_W / 1000);
    int save = canvas.save();
    float spread = 1 - 0.12f * rise;
    float[] centre = FigureGeometry.HALO;
    canvas.scale(spread, spread, centre[0] * 1000 / PHONE_VIEW_W, centre[1] * 1000 / PHONE_VIEW_W);
    halo.setAlpha(Math.round(255 * (1 - 0.3f * rise)));
    halo.draw(canvas);
    canvas.restoreToCount(save);
    canvas.restoreToCount(base);

    drawLayer(canvas, body);
    save = canvas.save();
    canvas.concat(fit);
    screenMatrix(screen);
    canvas.concat(screen);
    canvas.clipPath(opening);
    if (from >= 0) screens.draw(canvas, from, fromAge, calm);
    if (from >= 0) canvas.saveLayerAlpha(bounds, Math.round(255 * fade));
    screens.draw(canvas, step, age, calm);
    canvas.restoreToCount(save);
    drawLayer(canvas, bezel);
  }

  /** Mixes the outgoing step's value into the new one's while they crossfade. */
  private float blend(float now, float before) {
    return from >= 0 ? before + (now - before) * fade : now;
  }

  private void drawLayer(Canvas canvas, Drawable layer) {
    int save = canvas.save();
    canvas.concat(fit);
    canvas.concat(follow);
    canvas.scale(PHONE_VIEW_W / 1000, PHONE_VIEW_W / 1000);
    layer.draw(canvas);
    canvas.restoreToCount(save);
  }

  /**
   * The phone's frame at a pose: u right along the screen, v up it, w out of the glass, and o
   * the middle of the bottom edge of the back, in world units. It turns, leans and lifts about
   * its own centre, and a dip pushes it back along w.
   */
  private void pose(float turnDegrees, float leanDegrees, float lift, float dip) {
    frame(PHONE_FACING, PHONE_TILT);
    float cx = PHONE_H / 2 * v[0] + PHONE_T / 2 * w[0];
    float cy = PHONE_H / 2 * v[1] + PHONE_T / 2 * w[1];
    float cz = PHONE_FLOAT + PHONE_H / 2 * v[2] + PHONE_T / 2 * w[2];
    frame(PHONE_FACING + turnDegrees, PHONE_TILT - leanDegrees);
    cz += lift;
    for (int i = 0; i < 3; i++) {
      float c = i == 0 ? cx : i == 1 ? cy : cz;
      o[i] = c - dip * w[i] - PHONE_H / 2 * v[i] - PHONE_T / 2 * w[i];
    }
  }

  private void frame(float facingDegrees, float tiltDegrees) {
    double f = Math.toRadians(facingDegrees), t = Math.toRadians(tiltDegrees);
    float nx = (float) Math.cos(f), ny = (float) Math.sin(f);
    float st = (float) Math.sin(t), ct = (float) Math.cos(t);
    u[0] = ny;
    u[1] = -nx;
    u[2] = 0;
    v[0] = -nx * st;
    v[1] = -ny * st;
    v[2] = ct;
    w[0] = nx * ct;
    w[1] = ny * ct;
    w[2] = st;
  }

  /** Maps the glass's face coordinates (u right, v up) into the viewport. */
  private void faceMatrix(Matrix out) {
    set(out, u, v, PHONE_T, 0, 0);
  }

  /** Maps screen coordinates (x right, y down from the opening's top-left) into the viewport. */
  private void screenMatrix(Matrix out) {
    set(out, u, v, PHONE_T - SCREEN_RECESS, -SCREEN_W / 2, SCREEN_BOTTOM + SCREEN_H);
    values[1] = -values[1];
    values[4] = -values[4];
    out.setValues(values);
  }

  private void set(Matrix out, float[] a, float[] b, float depth, float a0, float b0) {
    float px = WORLD_ORIGIN[0], py = WORLD_ORIGIN[1];
    for (int i = 0; i < 3; i++) {
      float at = o[i] + depth * w[i] + a0 * a[i] + b0 * b[i];
      px += at * axis(i)[0];
      py += at * axis(i)[1];
    }
    values[0] = project(a, 0);
    values[1] = project(b, 0);
    values[2] = px;
    values[3] = project(a, 1);
    values[4] = project(b, 1);
    values[5] = py;
    values[6] = 0;
    values[7] = 0;
    values[8] = 1;
    out.setValues(values);
  }

  private static float[] axis(int i) {
    return i == 0 ? WORLD_X : i == 1 ? WORLD_Y : WORLD_Z;
  }

  private static float project(float[] vector, int component) {
    return vector[0] * WORLD_X[component] + vector[1] * WORLD_Y[component] + vector[2] * WORLD_Z[component];
  }
}
