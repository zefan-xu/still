// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * A three-layer figure whose middle layer travels: back, then the moving part, then front. The
 * part slides from its start to rest with {@link Motion#settle}, replays about every six seconds
 * while on screen and on tap, and stays at rest when animations are off. All layers share one
 * viewport and are scaled uniformly to fit.
 */
public final class FigureView extends View {
  private static final long TOUR_MS = 6000;
  private static final float SLIDE_SECONDS = 0.15f, LIFT_TAU = 0.05f;

  private final Drawable back, part, front;
  private final float viewportWidth, dx, dy;
  private final Motion.State travel = new Motion.State(1);
  private final Motion.Loop loop = new Motion.Loop(this, this::step);
  private final Runnable tour = this::replay;
  private float shown = 1, travelX, travelY;
  private boolean lifting, visible;

  /**
   * {@code dx, dy} is the part's travel from its start to rest, in viewport units; the viewport
   * is assumed to be the drawables' intrinsic size in dp.
   */
  public FigureView(Context context, Drawable back, Drawable part, Drawable front, float dx, float dy) {
    this(
        context,
        back,
        part,
        front,
        back.getIntrinsicWidth() / context.getResources().getDisplayMetrics().density,
        dx,
        dy);
  }

  /** {@code viewportWidth} is the drawables' {@code android:viewportWidth}. */
  public FigureView(
      Context context,
      Drawable back,
      Drawable part,
      Drawable front,
      float viewportWidth,
      float dx,
      float dy) {
    super(context);
    this.back = back;
    this.part = part.mutate();
    this.front = front;
    this.viewportWidth = viewportWidth;
    this.dx = dx;
    this.dy = dy;
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    setOnClickListener(v -> replay());
  }

  /** Slides the part in again from its start. */
  public void replay() {
    removeCallbacks(tour);
    if (Motion.calm(getContext()) || lifting || travel.x < 1) return;
    lifting = true;
    loop.start();
  }

  private boolean step(float dt, boolean calm) {
    boolean moving;
    if (calm) {
      lifting = false;
      travel.x = 1;
      travel.v = 0;
      shown = 1;
      moving = false;
    } else if (lifting) {
      shown = Motion.follow(shown, 0, dt, LIFT_TAU, false);
      if (shown < 0.01f) {
        lifting = false;
        travel.x = 0;
        travel.v = 0;
        shown = 0;
      }
      moving = true;
    } else {
      moving = Motion.settle(travel, 1, dt, SLIDE_SECONDS, false);
      shown = Motion.smooth(0, 0.3f, travel.x);
    }
    invalidate();
    if (!moving) scheduleTour();
    return moving;
  }

  private void scheduleTour() {
    removeCallbacks(tour);
    if (visible && isAttachedToWindow() && !Motion.calm(getContext()))
      postDelayed(tour, TOUR_MS);
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (Motion.calm(getContext())) return;
    travel.x = 0;
    travel.v = 0;
    shown = 0;
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
  protected void onMeasure(int widthSpec, int heightSpec) {
    int iw = Math.max(1, back.getIntrinsicWidth()), ih = Math.max(1, back.getIntrinsicHeight());
    int horizontal = getPaddingLeft() + getPaddingRight();
    int vertical = getPaddingTop() + getPaddingBottom();
    int width = resolveSize(iw + horizontal, widthSpec);
    int height = resolveSize(Math.round((width - horizontal) * ih / (float) iw) + vertical, heightSpec);
    setMeasuredDimension(width, height);
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    float iw = Math.max(1, back.getIntrinsicWidth()), ih = Math.max(1, back.getIntrinsicHeight());
    float aw = w - getPaddingLeft() - getPaddingRight();
    float ah = h - getPaddingTop() - getPaddingBottom();
    float scale = Math.max(0, Math.min(aw / iw, ah / ih));
    float dw = iw * scale, dh = ih * scale;
    int left = Math.round(getPaddingLeft() + (aw - dw) / 2);
    int top = Math.round(getPaddingTop() + (ah - dh) / 2);
    int right = left + Math.round(dw), bottom = top + Math.round(dh);
    back.setBounds(left, top, right, bottom);
    part.setBounds(left, top, right, bottom);
    front.setBounds(left, top, right, bottom);
    travelX = dx * dw / viewportWidth;
    travelY = dy * dw / viewportWidth;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    back.draw(canvas);
    float away = 1 - travel.x;
    int save = canvas.save();
    canvas.translate(-away * travelX, -away * travelY);
    part.setAlpha(Math.round(255 * shown));
    part.draw(canvas);
    canvas.restoreToCount(save);
    front.draw(canvas);
  }
}
