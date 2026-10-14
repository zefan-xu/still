// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.ui;

import android.content.Context;
import android.graphics.Typeface;

/** Source Serif 4 for app-authored headings; post content stays in the system sans. */
public final class Fonts {
  private static Typeface display, serif;
  private static final Typeface SANS = Typeface.create("sans-serif", Typeface.NORMAL);
  private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

  private Fonts() {}

  /** Large titles and the wordmark (28 sp and up). */
  public static synchronized Typeface display(Context context) {
    if (display == null)
      display =
          Typeface.createFromAsset(
              context.getApplicationContext().getAssets(), "fonts/SourceSerif4Display-Regular.ttf");
    return display;
  }

  /** Sheet and section titles (17–24 sp). */
  public static synchronized Typeface serif(Context context) {
    if (serif == null)
      serif =
          Typeface.createFromAsset(
              context.getApplicationContext().getAssets(), "fonts/SourceSerif4-Semibold.ttf");
    return serif;
  }

  public static Typeface sans() {
    return SANS;
  }

  public static Typeface medium() {
    return MEDIUM;
  }
}
