// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A thin rounded progress line. Determinate progress eases between updates; the indeterminate
 * sweep runs only while animations are enabled.
 */
public final class ProgressLine extends View {
  private static final float SWEEP_SECONDS = 1.4f, EASE_SECONDS = 0.3f;
  private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();
  private final Motion.State shown = new Motion.State(0);
  private final Motion.Loop loop = new Motion.Loop(this, this::step);
  private int progress = -1;
  private boolean placed;
  private float phase;

  public ProgressLine(Context context, Theme theme) {
    super(context);
    track.setColor(theme.hairline);
    fill.setColor(theme.accentStrong);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  /** Picks up where a line it replaces left off, so a rebuilt row does not restart. */
  public void continueFrom(ProgressLine previous) {
    if (previous == null || previous == this) return;
    shown.x = previous.shown.x;
    shown.v = previous.shown.v;
    placed = previous.placed;
    phase = previous.phase;
  }

  /** 0–100, or a negative value for indeterminate. */
  public void setProgress(int value) {
    int clamped = value < 0 ? -1 : Math.min(100, value);
    if (clamped == progress) return;
    progress = clamped;
    if (clamped >= 0 && !placed) {
      shown.x = clamped;
      shown.v = 0;
      placed = true;
    }
    loop.start();
    invalidate();
  }

  private boolean step(float dt, boolean calm) {
    boolean moving;
    if (progress >= 0) moving = Motion.settle(shown, progress, dt, EASE_SECONDS, calm);
    else if (calm) {
      phase = 0;
      moving = false;
    } else {
      phase = (phase + dt / SWEEP_SECONDS) % 1;
      moving = isShown();
    }
    invalidate();
    return moving;
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    loop.start();
  }

  @Override
  public void onVisibilityAggregated(boolean isVisible) {
    super.onVisibilityAggregated(isVisible);
    if (isVisible) loop.start();
  }

  @Override
  protected void onDetachedFromWindow() {
    loop.stop();
    super.onDetachedFromWindow();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    float w = getWidth(), h = getHeight(), r = h / 2;
    rect.set(0, 0, w, h);
    canvas.drawRoundRect(rect, r, r, track);
    if (progress >= 0) {
      rect.set(0, 0, w * shown.x / 100f, h);
    } else {
      float segment = w * 0.3f;
      float start = (w + segment) * Motion.smooth(0, 1, phase) - segment;
      rect.set(Math.max(0, start), 0, Math.min(w, start + segment), h);
    }
    if (rect.width() > 0) canvas.drawRoundRect(rect, r, r, fill);
  }
}
