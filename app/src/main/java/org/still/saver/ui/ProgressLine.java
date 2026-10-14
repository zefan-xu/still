// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** A thin rounded progress line. Indeterminate sweeps only while animators are enabled. */
public final class ProgressLine extends View {
  private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();
  private int progress = -1;
  private float phase;
  private ValueAnimator sweep;

  public ProgressLine(Context context, Theme theme) {
    super(context);
    track.setColor(theme.hairline);
    fill.setColor(theme.accentStrong);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  /** 0–100, or a negative value for indeterminate. */
  public void setProgress(int value) {
    int clamped = value < 0 ? -1 : Math.min(100, value);
    if (clamped == progress) return;
    progress = clamped;
    updateSweep();
    invalidate();
  }

  private static boolean animatorsEnabled() {
    return Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled();
  }

  private void updateSweep() {
    boolean wanted = progress < 0 && isAttachedToWindow() && animatorsEnabled();
    if (wanted && sweep == null) {
      sweep = ValueAnimator.ofFloat(0, 1);
      sweep.setDuration(1400);
      sweep.setRepeatCount(ValueAnimator.INFINITE);
      sweep.setInterpolator(new LinearInterpolator());
      sweep.addUpdateListener(
          a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
          });
      sweep.start();
    } else if (!wanted && sweep != null) {
      sweep.cancel();
      sweep = null;
    }
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    updateSweep();
  }

  @Override
  protected void onDetachedFromWindow() {
    if (sweep != null) {
      sweep.cancel();
      sweep = null;
    }
    super.onDetachedFromWindow();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    float w = getWidth(), h = getHeight(), r = h / 2;
    rect.set(0, 0, w, h);
    canvas.drawRoundRect(rect, r, r, track);
    if (progress >= 0) {
      rect.set(0, 0, w * progress / 100f, h);
    } else {
      float segment = w * 0.3f;
      float start = (w + segment) * phase - segment;
      rect.set(Math.max(0, start), 0, Math.min(w, start + segment), h);
    }
    if (rect.width() > 0) canvas.drawRoundRect(rect, r, r, fill);
  }
}
