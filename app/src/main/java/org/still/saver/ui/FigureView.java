// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * A figure painted from layers that share one viewport, back to front, scaled uniformly to fit.
 * A {@link Clip} poses the layers as a pure function of time: an offset and an opacity for the
 * parts that move. The clip plays when the
 * view attaches, replays a while after it ends and on tap, and holds its still frame when
 * animations are off. Before a replay or a new clip, the lifted layers fade out where they are
 * and the other moving parts glide back to the clip's opening pose, so nothing pops.
 */
// Built only in code: the layers and viewport width have no XML attribute form.
@SuppressLint("ViewConstructor")
public final class FigureView extends View {
  /** Poses the layers at an age in seconds; {@link Motion#END} is the pose once it is over. */
  public interface Clip {
    /** Seconds until the pose stops changing. */
    float seconds();

    /** The age shown when animations are off. */
    float still();

    void pose(float age, Pose pose);
  }

  /** Each layer's offset in viewport units and opacity. */
  public static final class Pose {
    final float[] x, y, alpha;

    Pose(int layers) {
      x = new float[layers];
      y = new float[layers];
      alpha = new float[layers];
      reset();
    }

    void reset() {
      java.util.Arrays.fill(x, 0);
      java.util.Arrays.fill(y, 0);
      java.util.Arrays.fill(alpha, 1);
    }

    public void move(int layer, float dx, float dy) {
      x[layer] = dx;
      y[layer] = dy;
    }

    public void alpha(int layer, float value) {
      alpha[layer] = value;
    }
  }

  private static final long TOUR_MS = 6000;
  private static final float DROP_SECONDS = 0.15f, LIFT_TAU = 0.05f, PARK_TAU = 0.08f;

  private final Drawable[] layers;
  private final boolean[] lifted;
  private final float viewportWidth;
  private final Pose shown, next;
  private final Motion.Loop loop = new Motion.Loop(this, this::step);
  private final Runnable tour = this::replay;
  private Clip clip, pending;
  private float age, lift = 1, unit;
  private boolean lifting, visible;

  /** {@code viewportWidth} is the drawables' {@code android:viewportWidth}. */
  public FigureView(Context context, float viewportWidth, Drawable... layers) {
    super(context);
    this.viewportWidth = viewportWidth;
    this.layers = new Drawable[layers.length];
    for (int i = 0; i < layers.length; i++) this.layers[i] = layers[i].mutate();
    lifted = new boolean[layers.length];
    shown = new Pose(layers.length);
    next = new Pose(layers.length);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    setOnClickListener(v -> replay());
  }

  /**
   * The empty-state figure: {@code part} drops into place between {@code back} and {@code front}
   * after travelling {@code dx, dy} viewport units.
   */
  public static FigureView drop(
      Context context,
      Drawable back,
      Drawable part,
      Drawable front,
      float viewportWidth,
      float dx,
      float dy) {
    FigureView view = new FigureView(context, viewportWidth, back, part, front);
    view.setLifted(1);
    view.setClip(
        new Clip() {
          @Override
          public float seconds() {
            return 12 * DROP_SECONDS;
          }

          @Override
          public float still() {
            return Motion.END;
          }

          @Override
          public void pose(float age, Pose pose) {
            float away = 1 - Motion.settled(age, DROP_SECONDS);
            pose.move(1, -away * dx, -away * dy);
            pose.alpha(1, Motion.smooth(0, 0.3f, 1 - away));
          }
        });
    return view;
  }

  /** Layers that fade out before a replay or a new clip instead of gliding back. */
  public void setLifted(int... indices) {
    for (int index : indices) lifted[index] = true;
  }

  /** Shows {@code value}, lifting the current pose away first while the view is animating. */
  public void setClip(Clip value) {
    if (value == clip && pending == null) return;
    removeCallbacks(tour);
    if (clip == null || !isAttachedToWindow() || Motion.calm(getContext())) {
      clip = value;
      pending = null;
      lifting = false;
      lift = 1;
      age = 0;
      settleNow(Motion.calm(getContext()) || !isAttachedToWindow() ? clip.still() : 0);
      if (isAttachedToWindow()) loop.start();
      return;
    }
    pending = value;
    lifting = true;
    loop.start();
  }

  /** Plays the clip again from the start once the current play has finished. */
  public void replay() {
    removeCallbacks(tour);
    if (clip == null || Motion.calm(getContext()) || lifting || age < clip.seconds()) return;
    lifting = true;
    loop.start();
  }

  private void settleNow(float at) {
    pose(clip, at, shown);
    invalidate();
  }

  private static void pose(Clip clip, float at, Pose pose) {
    pose.reset();
    clip.pose(at, pose);
  }

  private boolean step(float dt, boolean calm) {
    if (clip == null) return false;
    if (calm) {
      if (pending != null) clip = pending;
      pending = null;
      lifting = false;
      lift = 1;
      age = clip.seconds();
      settleNow(clip.still());
      return false;
    }
    boolean moving;
    if (lifting) {
      Clip upcoming = pending != null ? pending : clip;
      pose(upcoming, 0, next);
      lift = Motion.follow(lift, 0, dt, LIFT_TAU, false);
      boolean parked = true;
      for (int i = 0; i < layers.length; i++) {
        if (lifted[i]) continue;
        shown.x[i] = Motion.follow(shown.x[i], next.x[i], dt, PARK_TAU, false);
        shown.y[i] = Motion.follow(shown.y[i], next.y[i], dt, PARK_TAU, false);
        shown.alpha[i] = Motion.follow(shown.alpha[i], next.alpha[i], dt, PARK_TAU, false);
        parked &= shown.x[i] == next.x[i] && shown.y[i] == next.y[i] && shown.alpha[i] == next.alpha[i];
      }
      if (lift < 0.01f && parked) {
        clip = upcoming;
        pending = null;
        lifting = false;
        lift = 1;
        age = 0;
        pose(clip, 0, shown);
      }
      moving = true;
    } else {
      age += dt;
      pose(clip, age >= clip.seconds() ? Motion.END : age, shown);
      moving = age < clip.seconds();
    }
    invalidate();
    if (!moving) scheduleTour();
    return moving;
  }

  private void scheduleTour() {
    removeCallbacks(tour);
    if (visible && isAttachedToWindow() && !Motion.calm(getContext())) postDelayed(tour, TOUR_MS);
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (clip == null) return;
    if (Motion.calm(getContext())) {
      settleNow(clip.still());
      return;
    }
    if (pending != null) clip = pending;
    pending = null;
    lifting = false;
    lift = 1;
    age = 0;
    settleNow(0);
    loop.start();
  }

  @Override
  protected void onDetachedFromWindow() {
    removeCallbacks(tour);
    loop.finish();
    super.onDetachedFromWindow();
  }

  @Override
  public void onVisibilityAggregated(boolean isVisible) {
    super.onVisibilityAggregated(isVisible);
    visible = isVisible;
    if (!isVisible) removeCallbacks(tour);
    else if (!loop.running()) scheduleTour();
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
    int iw = Math.max(1, layers[0].getIntrinsicWidth()), ih = Math.max(1, layers[0].getIntrinsicHeight());
    int horizontal = getPaddingLeft() + getPaddingRight();
    int vertical = getPaddingTop() + getPaddingBottom();
    int width = resolveSize(iw + horizontal, widthSpec);
    int height = resolveSize(Math.round((width - horizontal) * ih / (float) iw) + vertical, heightSpec);
    setMeasuredDimension(width, height);
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    float iw = Math.max(1, layers[0].getIntrinsicWidth()), ih = Math.max(1, layers[0].getIntrinsicHeight());
    float aw = w - getPaddingLeft() - getPaddingRight();
    float ah = h - getPaddingTop() - getPaddingBottom();
    float scale = Math.max(0, Math.min(aw / iw, ah / ih));
    float dw = iw * scale, dh = ih * scale;
    int left = Math.round(getPaddingLeft() + (aw - dw) / 2);
    int top = Math.round(getPaddingTop() + (ah - dh) / 2);
    int right = left + Math.round(dw), bottom = top + Math.round(dh);
    for (Drawable layer : layers) layer.setBounds(left, top, right, bottom);
    unit = dw / viewportWidth;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    for (int i = 0; i < layers.length; i++) {
      float alpha = shown.alpha[i] * (lifted[i] ? lift : 1);
      if (alpha <= 0.004f) continue;
      Drawable layer = layers[i];
      layer.setAlpha(Math.round(255 * Math.min(1, alpha)));
      int save = canvas.save();
      canvas.translate(shown.x[i] * unit, shown.y[i] * unit);
      layer.draw(canvas);
      canvas.restoreToCount(save);
    }
  }
}
