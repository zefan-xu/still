// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.ui;

import static org.still.saver.ui.FigureGeometry.FEED_RISE;
import static org.still.saver.ui.FigureGeometry.PASTE_START;
import static org.still.saver.ui.FigureGeometry.PUSHER_GAP;
import static org.still.saver.ui.FigureGeometry.PUSHER_STROKE;
import static org.still.saver.ui.FigureGeometry.SHARE_START;
import static org.still.saver.ui.FigureGeometry.TUTORIAL_X;
import static org.still.saver.ui.FigureGeometry.TUTORIAL_Y;
import static org.still.saver.ui.FigureGeometry.TUTORIAL_Z;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.view.View;
import org.still.saver.R;

/**
 * The onboarding figure: the empty state's tray and chute, fed from two sides. A print shared
 * from another app rolls out of the covered conveyor on its own; a print you paste is laid on
 * the feed table and pushed in by hand. Either way it drops through the chute into the tray.
 * Each step plays one path and washes the other feed back toward the paper.
 */
public final class TutorialFigure {
  public static final int SHARE = 0, PASTE = 1, SAVED = 2, STEPS = 3;

  /** Indices into the layers in painting order (see the constructor). */
  private static final int SHARE_FEED = 1, PASTE_FEED = 2, HAND = 3, PRINT = 5, HOOD = 7;
  private static final float DROP_SECONDS = 0.15f, PLACE_SECONDS = 0.12f, PLACE_RISE = 4;
  private static final long TOUR_MS = 2600;

  private static final String[] DESCRIPTIONS = {
    "A print rolls out of a covered conveyor on the left, slides over the chute and drops into"
        + " the tray.",
    "A print is laid on the feed table on the right, pushed into the chute by hand and dropped"
        + " into the tray.",
    "A print drops from the chute onto the stack in the tray, where everything you save"
        + " collects.",
  };

  private final FigureView view;
  private final FigureView.Clip[] clips;

  public TutorialFigure(Context context) {
    Resources.Theme theme = context.getTheme();
    int[] ids = {
      R.drawable.fig_tutorial_base,
      R.drawable.fig_tutorial_share,
      R.drawable.fig_tutorial_paste,
      R.drawable.fig_tutorial_hand,
      R.drawable.fig_tutorial_chute,
      R.drawable.fig_tutorial_print,
      R.drawable.fig_tutorial_front,
      R.drawable.fig_tutorial_hood,
    };
    Drawable[] layers = new Drawable[ids.length];
    for (int i = 0; i < ids.length; i++) layers[i] = theme.getDrawable(ids[i]);
    view = new FigureView(context, FigureGeometry.TUTORIAL_WIDTH, layers);
    view.setLifted(PRINT);
    view.setTour(TOUR_MS);
    view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    // Light linework on dark paper loses less to the same wash, so it needs more to step back.
    boolean dark = Theme.resolve(context).dark;
    float aside = dark ? 0.75f : 0.6f, behind = dark ? 0.6f : 0.45f;
    clips = new FigureView.Clip[] {shared(aside), pasted(aside), saved(behind)};
  }

  public FigureView view() {
    return view;
  }

  public void show(int step) {
    view.setContentDescription(DESCRIPTIONS[step]);
    view.setClip(clips[step]);
  }

  /** Moves {@code layer} by a world vector from where it is drawn. */
  private static void at(FigureView.Pose pose, int layer, float x, float y, float z) {
    pose.move(
        layer,
        x * TUTORIAL_X[0] + y * TUTORIAL_Y[0] + z * TUTORIAL_Z[0],
        x * TUTORIAL_X[1] + y * TUTORIAL_Y[1] + z * TUTORIAL_Z[1]);
  }

  /** The print falls from either feed's height to its rest on the stack. */
  private static float fall(float age, float from) {
    return FEED_RISE * (1 - Motion.settled(age - from, DROP_SECONDS));
  }

  /** Out of the hood, along the rollers to the middle of the chute, then down into the tray. */
  private static FigureView.Clip shared(float aside) {
    float slideFrom = 0.35f, slideTo = 2.35f, dropAt = 2.5f;
    return new FigureView.Clip() {
      @Override
      public float seconds() {
        return dropAt + 12 * DROP_SECONDS;
      }

      @Override
      public float still() {
        return 1.3f;
      }

      @Override
      public void pose(float age, FigureView.Pose pose) {
        float slide = Motion.smooth(slideFrom, slideTo, age);
        at(pose, PRINT, SHARE_START * (1 - slide), 0, fall(age, dropAt));
        pose.alpha(PRINT, Motion.smooth(0, 0.4f, age));
        pose.dim(PASTE_FEED, aside);
        pose.dim(HAND, aside);
      }
    };
  }

  /**
   * Laid on the table, met by the pusher and shoved toward the lip; the pusher eases against its
   * stop short of the lip while the print glides on, drops, and the pusher goes home.
   */
  private static FigureView.Clip pasted(float aside) {
    float reachFrom = 0.7f, pushFrom = 1, pushTo = 2.6f, dropAt = 2.75f;
    float homeFrom = 3, homeTo = 4.2f;
    return new FigureView.Clip() {
      @Override
      public float seconds() {
        return dropAt + 12 * DROP_SECONDS;
      }

      @Override
      public float still() {
        return 1.55f;
      }

      @Override
      public void pose(float age, FigureView.Pose pose) {
        float travel = -PASTE_START * Motion.smooth(pushFrom, pushTo, age);
        float lift = PLACE_RISE * (1 - Motion.settled(age, PLACE_SECONDS));
        at(pose, PRINT, 0, PASTE_START + travel, fall(age, dropAt) + lift);
        pose.alpha(PRINT, Motion.smooth(0, 0.3f, age));
        float hand =
            age < pushFrom
                ? PUSHER_GAP * Motion.smooth(reachFrom, pushFrom, age)
                : Motion.softMin(PUSHER_GAP + travel, PUSHER_STROKE, 1.2f);
        at(pose, HAND, 0, hand * (1 - Motion.smooth(homeFrom, homeTo, age)), 0);
        pose.dim(SHARE_FEED, aside);
        pose.dim(HOOD, aside);
      }
    };
  }

  /** Waiting at the top of the chute, then down onto the stack; both feeds step back. */
  private static FigureView.Clip saved(float behind) {
    float dropAt = 0.7f;
    return new FigureView.Clip() {
      @Override
      public float seconds() {
        return dropAt + 12 * DROP_SECONDS;
      }

      @Override
      public float still() {
        return Motion.END;
      }

      @Override
      public void pose(float age, FigureView.Pose pose) {
        at(pose, PRINT, 0, 0, fall(age, dropAt));
        pose.alpha(PRINT, Motion.smooth(0, 0.35f, age));
        pose.dim(SHARE_FEED, behind);
        pose.dim(HOOD, behind);
        pose.dim(PASTE_FEED, behind);
        pose.dim(HAND, behind);
      }
    };
  }
}
