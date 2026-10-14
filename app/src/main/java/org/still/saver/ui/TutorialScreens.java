// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import org.still.saver.R;

/**
 * What the tutorial phone's screen shows, as pure functions of the step and its age in seconds:
 * another app's post and the share sheet, Still's save sheet, Still's home with its link field,
 * and the library. Drawn in screen units, {@link #W} wide with y down from the top-left of the
 * screen opening. A tap dot glides from control to control and ripples on each press; the
 * terracotta media is the one lit accent.
 */
final class TutorialScreens {
  static final float W = FigureGeometry.SCREEN_W, H = FigureGeometry.SCREEN_H;

  /** The tap envelope's attack and decay, and how long its ripple takes to spread. */
  static final float ATTACK = 0.08f, DECAY = 0.3f, RING_DECAY = 0.14f, SPREAD = 0.2f;

  private static final float[][] TAPS = {{1.5f, 3.1f, 4.8f}, {1.3f, 3f, 4.7f}, {}};
  private static final float[] SECONDS = {6.9f, 6.7f, 2.8f};
  private static final float[] STILL = {3.1f + ATTACK, 3f + ATTACK, Motion.END};
  private static final long[] REST_MS = {1800, 1800, 3600};

  private static final float SIDE = 4.2f, SHARE_TOP = H - 46, SAVE_TOP = H - 62;
  private static final float FIELD_Y = 22.75f, ICONS_Y = 25, GRID_Y = 45, GAP = 1.4f;
  private static final float TILE = (W - 2 * SIDE - 2 * GAP) / 3;
  private static final float[] APPS_X = {W / 8, 3 * W / 8, 5 * W / 8, 7 * W / 8};
  private static final int STILL_APP = 1;

  /** Where each tap lands: Share, Still, Save; then Paste, the arrow, Save. */
  private static final float[][][] TARGETS = {
    {{W - 7, 94}, {APPS_X[STILL_APP], SHARE_TOP + ICONS_Y}, {W / 2, SAVE_TOP + 52}},
    {{W - 11.6f, FIELD_Y}, {W - 8.6f, FIELD_Y}, {W / 2, SAVE_TOP + 52}},
    {},
  };

  private final Theme t;
  private final Drawable mark, icon;
  private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint word = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF box = new RectF();
  private final Path path = new Path();
  private final int otherBg, otherLine, otherInk, systemSheet, pressTint, dotTint;
  private final int[] apps, tiles;

  TutorialScreens(Context context, Theme theme) {
    t = theme;
    mark = context.getTheme().getDrawable(R.drawable.ic_mark).mutate();
    icon = context.getTheme().getDrawable(R.drawable.ic_still).mutate();
    line.setStyle(Paint.Style.STROKE);
    line.setStrokeCap(Paint.Cap.ROUND);
    line.setStrokeJoin(Paint.Join.ROUND);
    for (Paint paint : new Paint[] {text, word}) {
      paint.setLinearText(true);
      paint.setSubpixelText(true);
      paint.setTextAlign(Paint.Align.CENTER);
    }
    text.setTypeface(Fonts.medium());
    word.setTypeface(Fonts.display(context));
    word.setTextAlign(Paint.Align.LEFT);
    boolean dark = t.dark;
    otherBg = dark ? 0xff131313 : 0xffffffff;
    otherLine = dark ? 0xff3b3b3b : 0xffdadada;
    otherInk = dark ? 0xffc9c9c9 : 0xff3f3f3f;
    systemSheet = dark ? 0xff222222 : 0xfff3f3f3;
    pressTint = dark ? 0x33ffffff : 0x1f000000;
    dotTint = dark ? 0xffffffff : 0xff141413;
    apps =
        dark
            ? new int[] {0xff3c4046, 0, 0xff45413b, 0xff3c433d}
            : new int[] {0xffcdd2d8, 0, 0xffd8d2c8, 0xffcbd4cc};
    float[] mixes = {0.2f, 0.34f, 0.12f, 0.26f, 0.4f, 0.17f, 0.3f, 0.22f};
    tiles = new int[mixes.length];
    for (int i = 0; i < mixes.length; i++)
      tiles[i] = Theme.mix(t.raised, t.tertiary, mixes[i] * (dark ? 0.55f : 1));
  }

  float seconds(int step) {
    return SECONDS[step];
  }

  /** The age shown when animations are off: the moment that tells the step's story. */
  float still(int step) {
    return STILL[step];
  }

  long rest(int step) {
    return REST_MS[step];
  }

  /** How hard the screen is being pressed, from the taps' envelopes. */
  float press(int step, float age) {
    float sum = 0;
    for (float at : TAPS[step]) sum += Motion.burst(age - at, ATTACK, 0.14f);
    return sum;
  }

  private static float tap(int step, int index, float age) {
    return Motion.burst(age - TAPS[step][index], ATTACK, 0.22f);
  }

  /** Rises to 1 from {@code in}, and back to 0 from {@code out}. */
  private static float sheet(float age, float in, float out) {
    return Motion.settled(age - in, 0.22f) - Motion.settled(age - out, 0.2f);
  }

  void draw(Canvas c, int step, float age, boolean calm) {
    switch (step) {
      case TutorialFigure.SHARE:
        share(c, age);
        break;
      case TutorialFigure.PASTE:
        paste(c, age);
        break;
      default:
        library(c, age);
    }
    dot(c, step, age, calm);
  }

  // --- Step 1: a post in another app, the share sheet, then Still's save sheet over the post.

  private void share(Canvas c, float age) {
    float shared = Motion.settled(age - 1.6f, 0.2f) - Motion.settled(age - 3.25f, 0.16f);
    float saving = sheet(age, 3.35f, 5.55f);
    float scrim = Motion.smooth(1.55f, 1.95f, age) * (1 - Motion.smooth(5.5f, 6f, age));
    post(c, tap(TutorialFigure.SHARE, 0, age));
    status(c, otherInk);
    shade(c, scrim);
    if (shared > 0.001f) {
      float top = SHARE_TOP + (1 - shared) * 50;
      sheetShape(c, top, systemSheet, otherLine);
      round(c, SIDE + 0.4f, top + 6, SIDE + 7.4f, top + 13, 1.2f, t.accent);
      rule(c, SIDE + 10.2f, top + 8.3f, 22, 1.4f, otherInk);
      rule(c, SIDE + 10.2f, top + 11.4f, 14, 1.1f, otherLine);
      float pressed = tap(TutorialFigure.SHARE, 1, age);
      for (int i = 0; i < APPS_X.length; i++) app(c, i, APPS_X[i], top + ICONS_Y, i == STILL_APP ? pressed : 0);
    }
    if (saving > 0.001f) saveSheet(c, SAVE_TOP + (1 - saving) * 68, age, 4.9f, tap(TutorialFigure.SHARE, 2, age));
    handle(c, otherInk);
  }

  private void post(Canvas c, float pressed) {
    fill.setColor(otherBg);
    c.drawRect(-2, -2, W + 2, H + 2, fill);
    fill.setColor(otherLine);
    c.drawCircle(SIDE + 3.8f, 15, 3.6f, fill);
    rule(c, SIDE + 9.8f, 13.6f, 17, 1.5f, otherInk);
    rule(c, SIDE + 9.8f, 17.2f, 10, 1.1f, otherLine);
    photo(c, SIDE, 22.5f, W - SIDE, 79, 2.2f, 1);
    rule(c, SIDE, 84.2f, 38, 1.1f, otherLine);
    rule(c, SIDE, 87.5f, 24, 1.1f, otherLine);
    line.setStrokeWidth(0.55f);
    line.setColor(otherLine);
    c.drawCircle(SIDE + 2.2f, 94, 1.9f, line);
    round(c, SIDE + 7.4f, 92.1f, SIDE + 11.6f, 95.9f, 1.2f, otherLine, true);
    float[] at = TARGETS[TutorialFigure.SHARE][0];
    if (pressed > 0.004f) {
      fill.setColor(scale(pressTint, pressed));
      c.drawCircle(at[0], at[1], 4.2f, fill);
    }
    shareGlyph(c, at[0], at[1]);
    fill.setColor(otherLine);
    c.drawCircle(SIDE + 3.8f, 107, 3.6f, fill);
    rule(c, SIDE + 9.8f, 105.6f, 15, 1.5f, otherLine);
  }

  private void shareGlyph(Canvas c, float x, float y) {
    line.setColor(otherInk);
    line.setStrokeWidth(0.5f);
    c.drawLine(x - 1.5f, y, x + 1.5f, y - 1.7f, line);
    c.drawLine(x - 1.5f, y, x + 1.5f, y + 1.7f, line);
    fill.setColor(otherInk);
    c.drawCircle(x - 1.5f, y, 0.85f, fill);
    c.drawCircle(x + 1.5f, y - 1.7f, 0.85f, fill);
    c.drawCircle(x + 1.5f, y + 1.7f, 0.85f, fill);
  }

  /** A muted app icon with a plain glyph, or Still's own launcher icon. */
  private void app(Canvas c, int index, float x, float y, float pressed) {
    float r = 4.5f;
    if (pressed > 0.004f) {
      round(c, x - r - 1.6f, y - r - 1.6f, x + r + 1.6f, y + r + 1.6f, 3, scale(pressTint, pressed));
    }
    if (index == STILL_APP) {
      icon.setBounds(0, 0, 100, 100);
      int save = c.save();
      c.translate(x - r - 0.5f, y - r - 0.5f);
      c.scale((2 * r + 1) / 100, (2 * r + 1) / 100);
      icon.draw(c);
      c.restoreToCount(save);
      text.setTextSize(2.5f);
      text.setColor(otherInk);
      c.drawText("Still", x, y + r + 4, text);
      return;
    }
    fill.setColor(apps[index]);
    c.drawCircle(x, y, r, fill);
    int glyph = scale(t.dark ? 0xff8d8d8d : 0xffffffff, 0.9f);
    if (index == 0) round(c, x - 2, y - 1.6f, x + 2, y + 1.4f, 1.2f, glyph);
    else if (index == 2) {
      fill.setColor(glyph);
      c.drawCircle(x, y, 1.8f, fill);
    } else {
      path.reset();
      path.moveTo(x, y - 2);
      path.lineTo(x + 2, y + 1.5f);
      path.lineTo(x - 2, y + 1.5f);
      path.close();
      fill.setColor(glyph);
      c.drawPath(path, fill);
    }
    rule(c, x - 3, y + r + 3.1f, 6, 1, otherLine);
  }

  // --- Step 2: Still's home; Paste puts a link in the field, the arrow finds it, Save keeps it.

  private void paste(Canvas c, float age) {
    float cleared = Motion.smooth(5.8f, 6.2f, age);
    float typed = Motion.smooth(1.4f, 2.1f, age) * (1 - cleared);
    float linked = Motion.settled(age - 2.05f, 0.08f) * (1 - cleared);
    float found = sheet(age, 3.1f, 5.45f);
    float scrim = Motion.smooth(3.05f, 3.45f, age) * (1 - Motion.smooth(5.4f, 5.9f, age));
    home(c, typed, linked, tap(TutorialFigure.PASTE, 0, age), tap(TutorialFigure.PASTE, 1, age));
    grid(c, 0, 0, 0);
    status(c, t.ink);
    shade(c, scrim);
    if (found > 0.001f) saveSheet(c, SAVE_TOP + (1 - found) * 68, age, 4.8f, tap(TutorialFigure.PASTE, 2, age));
    handle(c, t.ink);
  }

  private void home(Canvas c, float typed, float linked, float pastePressed, float goPressed) {
    fill.setColor(t.bg);
    c.drawRect(-2, -2, W + 2, H + 2, fill);
    float markH = 5.6f * mark.getIntrinsicHeight() / Math.max(1, mark.getIntrinsicWidth());
    mark.setBounds(0, 0, 100, Math.round(100 * markH / 5.6f));
    int save = c.save();
    c.translate(SIDE, 13.2f - markH * 0.78f);
    c.scale(0.056f, 0.056f);
    mark.draw(c);
    c.restoreToCount(save);
    word.setTextSize(5.4f);
    word.setColor(t.ink);
    c.drawText("Still", SIDE + 7, 13.6f, word);

    round(c, SIDE, FIELD_Y - 4.75f, W - SIDE, FIELD_Y + 4.75f, 4.75f, t.raised);
    if (typed < 1) rule(c, SIDE + 3.6f, FIELD_Y, 15, 1.2f, scale(t.tertiary, 1 - Motion.smooth(0, 0.15f, typed)));
    if (typed > 0) {
      float end = SIDE + 3.6f + 25 * typed;
      rule(c, SIDE + 3.6f, FIELD_Y, end - SIDE - 3.6f, 1.2f, t.ink);
      float caret = Motion.smooth(0, 0.1f, typed) * (1 - Motion.smooth(0.9f, 1, linked));
      if (caret > 0.004f) {
        line.setStrokeWidth(0.4f);
        line.setColor(scale(t.accentStrong, caret));
        c.drawLine(end + 1, FIELD_Y - 2.2f, end + 1, FIELD_Y + 2.2f, line);
      }
    }
    float[] pasteAt = TARGETS[TutorialFigure.PASTE][0], goAt = TARGETS[TutorialFigure.PASTE][1];
    if (linked < 1) {
      if (pastePressed > 0.004f) {
        round(c, pasteAt[0] - 5, FIELD_Y - 3.4f, pasteAt[0] + 5, FIELD_Y + 3.4f, 3.4f, scale(pressTint, pastePressed));
      }
      int save2 = c.save();
      float s = 1 - 0.4f * linked;
      c.scale(s, s, pasteAt[0], FIELD_Y);
      text.setTextSize(2.9f);
      text.setColor(scale(t.ink, 1 - linked));
      c.drawText("Paste", pasteAt[0], FIELD_Y + 1.05f, text);
      c.restoreToCount(save2);
    }
    if (linked > 0) {
      int save3 = c.save();
      float s = 0.6f + 0.4f * linked;
      c.scale(s, s, goAt[0], goAt[1]);
      fill.setColor(scale(Theme.mix(t.ink, t.tertiary, 0.45f * goPressed), linked));
      c.drawCircle(goAt[0], goAt[1], 3.5f, fill);
      line.setStrokeWidth(0.55f);
      line.setColor(scale(t.onInk, linked));
      float x = goAt[0], y = goAt[1];
      c.drawLine(x - 1.6f, y, x + 1.5f, y, line);
      c.drawLine(x + 0.2f, y - 1.3f, x + 1.5f, y, line);
      c.drawLine(x + 0.2f, y + 1.3f, x + 1.5f, y, line);
      c.restoreToCount(save3);
    }
    rule(c, SIDE + 1.2f, 31.6f, 38, 1, t.hairline);
    rule(c, SIDE + 1.2f, 34.6f, 24, 1, t.hairline);
  }

  // --- Step 3: the library; the new photo drops into the first slot and the rest move along.

  private void library(Canvas c, float age) {
    home(c, 0, 0, 0, 0);
    grid(c, Motion.settled(age - 1, 0.18f), Motion.settled(age - 1.2f, 0.2f), Motion.smooth(1.15f, 1.45f, age));
    status(c, t.ink);
    handle(c, t.ink);
  }

  /**
   * Today's tiles and this week's. As {@code moved} goes to 1 today's move along one slot, the
   * last of a row sliding out and in again on the next, and the new photo drops into the first.
   */
  private void grid(Canvas c, float moved, float dropped, float alpha) {
    rule(c, SIDE, GRID_Y - 4, 8, 1.3f, t.secondary);
    for (int a = 0; a < 5; a++) {
      int b = a + 1, i = a;
      if (a / 3 == b / 3) {
        tile(c, slotX(a) + (slotX(b) - slotX(a)) * moved, slotY(a), tiles[i], 1);
      } else {
        float shift = TILE * 0.6f;
        tile(c, slotX(a) + shift * moved, slotY(a), tiles[i], 1 - Motion.smooth(0, 0.7f, moved));
        tile(c, slotX(b) - shift * (1 - moved), slotY(b), tiles[i], Motion.smooth(0.3f, 1, moved));
      }
    }
    if (alpha > 0.004f) {
      int save = c.save();
      c.translate(0, -10 * (1 - dropped));
      photoTile(c, slotX(0), slotY(0), alpha);
      c.restoreToCount(save);
    }
    float week = GRID_Y + 2 * (TILE + GAP) + 4.4f;
    rule(c, SIDE, week, 12, 1.3f, t.secondary);
    for (int i = 0; i < 3; i++) tile(c, slotX(i), week + 4, tiles[5 + i], 1);
  }

  private static float slotX(int slot) {
    return SIDE + (slot % 3) * (TILE + GAP);
  }

  private static float slotY(int slot) {
    return GRID_Y + (slot / 3) * (TILE + GAP);
  }

  private void tile(Canvas c, float x, float y, int color, float alpha) {
    if (alpha <= 0.004f) return;
    round(c, x, y, x + TILE, y + TILE, 1.2f, scale(color, alpha));
  }

  private void photoTile(Canvas c, float x, float y, float alpha) {
    photo(c, x, y, x + TILE, y + TILE, 1.2f, alpha);
  }

  // --- Shared parts.

  /** Still's save sheet: the post's author, its photo and Save, which turns to a check. */
  private void saveSheet(Canvas c, float top, float age, float savedAt, float pressed) {
    sheetShape(c, top, t.surface, t.hairline);
    rule(c, SIDE + 0.4f, top + 8, 13, 1.4f, t.ink);
    rule(c, SIDE + 0.4f, top + 11.4f, 32, 1.1f, t.hairline);
    photo(c, SIDE + 0.4f, top + 15, SIDE + 25.4f, top + 45, 1.6f, 1);
    float done = Motion.smooth(savedAt, savedAt + 0.22f, age);
    float y = top + 52;
    round(c, SIDE + 0.4f, y - 4, W - SIDE - 0.4f, y + 4, 4, Theme.mix(t.ink, t.tertiary, 0.4f * pressed));
    if (done < 1) {
      text.setTextSize(3);
      text.setColor(scale(t.onInk, 1 - done));
      c.drawText("Save", W / 2, y + 1.08f, text);
    }
    if (done > 0) {
      line.setStrokeWidth(0.6f);
      line.setColor(scale(t.onInk, done));
      float s = 0.7f + 0.3f * done;
      path.reset();
      path.moveTo(W / 2 - 1.8f * s, y);
      path.lineTo(W / 2 - 0.5f * s, y + 1.3f * s);
      path.lineTo(W / 2 + 2 * s, y - 1.3f * s);
      c.drawPath(path, line);
    }
  }

  /** The terracotta photo: the media being saved, with a sun and a hill so it reads as one. */
  private void photo(Canvas c, float l, float top, float r, float b, float radius, float alpha) {
    round(c, l, top, r, b, radius, scale(t.accent, alpha));
    int save = c.save();
    box.set(l, top, r, b);
    path.reset();
    path.addRoundRect(box, radius, radius, Path.Direction.CW);
    c.clipPath(path);
    float w = r - l, h = b - top;
    fill.setColor(scale(Theme.mix(t.accent, 0xffffffff, 0.3f), alpha));
    c.drawCircle(r - w * 0.27f, top + h * 0.3f, Math.min(w, h) * 0.11f, fill);
    path.reset();
    path.moveTo(l, b - h * 0.22f);
    path.cubicTo(l + w * 0.3f, b - h * 0.48f, l + w * 0.55f, b - h * 0.2f, r, b - h * 0.36f);
    path.lineTo(r, b);
    path.lineTo(l, b);
    path.close();
    fill.setColor(scale(t.accentStrong, alpha));
    c.drawPath(path, fill);
    c.restoreToCount(save);
  }

  private void sheetShape(Canvas c, float top, int color, int handle) {
    round(c, -2, top, W + 2, H + 12, 4.5f, color);
    rule(c, W / 2 - 4, top + 2.6f, 8, 0.9f, handle);
  }

  private void shade(Canvas c, float amount) {
    if (amount <= 0.004f) return;
    fill.setColor(scale(t.scrim, 0.75f * amount));
    c.drawRect(-2, -2, W + 2, H + 2, fill);
  }

  /** The status bar's clock and battery. */
  private void status(Canvas c, int ink) {
    rule(c, SIDE + 0.2f, 3.4f, 5.4f, 1.3f, ink);
    round(c, W - SIDE - 4.6f, 2.5f, W - SIDE - 0.4f, 4.3f, 0.6f, ink, true);
    round(c, W - SIDE - 4.1f, 3, W - SIDE - 1.6f, 3.8f, 0.3f, ink);
  }

  /** The gesture bar along the bottom edge. */
  private void handle(Canvas c, int ink) {
    rule(c, W / 2 - 8, H - 2.2f, 16, 0.9f, scale(ink, 0.55f));
  }

  /**
   * The tap dot: it fades in on the first control, glides to each next one between taps, presses
   * with a burst and sends out a ripple, then fades once the last tap is done.
   */
  private void dot(Canvas c, int step, float age, boolean calm) {
    float[] taps = TAPS[step];
    if (taps.length == 0) return;
    float[][] at = TARGETS[step];
    int last = taps.length - 1;
    float shown = Motion.smooth(taps[0] - 0.55f, taps[0] - 0.2f, age) * (1 - Motion.smooth(taps[last] + 0.35f, taps[last] + 0.8f, age));
    if (shown <= 0.004f) return;
    float x = at[0][0], y = at[0][1];
    for (int i = 0; i < last; i++) {
      if (age <= taps[i]) break;
      float glide = Motion.smooth(taps[i] + 0.4f, taps[i + 1] - 0.18f, age);
      x = at[i][0] + (at[i + 1][0] - at[i][0]) * glide;
      y = at[i][1] + (at[i + 1][1] - at[i][1]) * glide;
    }
    float press = 0;
    for (int i = 0; i < taps.length; i++) {
      float since = age - taps[i];
      press = Math.max(press, Motion.burst(since, ATTACK, DECAY));
      float ring = Motion.burst(since, ATTACK, RING_DECAY);
      if (ring <= 0.004f) continue;
      float reach = calm ? 1 : 1 - (float) Math.exp(-since / SPREAD);
      line.setStrokeWidth(0.45f);
      line.setColor(scale(dotTint, 0.42f * ring * shown));
      c.drawCircle(at[i][0], at[i][1], 2.3f + 4.2f * (calm ? 0.45f : reach), line);
    }
    fill.setColor(scale(dotTint, (0.16f + 0.16f * press) * shown));
    c.drawCircle(x, y, 2.3f * (1 - 0.12f * press), fill);
  }

  // --- Drawing helpers, in screen units.

  /** A rounded rule line standing for text, from {@code x} along {@code length}, centred on y. */
  private void rule(Canvas c, float x, float y, float length, float thick, int color) {
    round(c, x, y - thick / 2, x + length, y + thick / 2, thick / 2, color);
  }

  private void round(Canvas c, float l, float top, float r, float b, float radius, int color) {
    round(c, l, top, r, b, radius, color, false);
  }

  private void round(Canvas c, float l, float top, float r, float b, float radius, int color, boolean outline) {
    box.set(l, top, r, b);
    Paint paint = outline ? line : fill;
    if (outline) line.setStrokeWidth(0.4f);
    paint.setColor(color);
    c.drawRoundRect(box, radius, radius, paint);
  }

  /** {@code color} with its alpha multiplied by {@code by}. */
  private static int scale(int color, float by) {
    int alpha = Math.round(((color >>> 24) & 0xff) * Math.max(0, Math.min(1, by)));
    return (alpha << 24) | (color & 0xffffff);
  }
}
