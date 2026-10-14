// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import org.still.saver.DownloadStore;
import org.still.saver.R;

/** Warm, quiet palette. Text tokens meet WCAG AA on bg and surface; accent is for fills only. */
public final class Theme {
  public final boolean dark;
  public final int bg, raised, surface, ink, secondary, tertiary, hairline;
  public final int accent, accentStrong, error, onInk, scrim;

  private Theme(boolean dark) {
    this.dark = dark;
    bg = dark ? 0xff1f1e1d : 0xfffaf9f5;
    raised = dark ? 0xff30302e : 0xfff0eee6;
    surface = dark ? 0xff262624 : 0xffffffff;
    ink = dark ? 0xfffaf9f5 : 0xff141413;
    secondary = dark ? 0xffb7b5a9 : 0xff5e5d59;
    tertiary = dark ? 0xff9c9a90 : 0xff737269;
    hairline = dark ? 0xff3d3d3a : 0xffe8e6dc;
    accent = 0xffd97757;
    accentStrong = dark ? 0xffd97757 : 0xffc6613f;
    error = dark ? 0xffed8f7a : 0xffb54a35;
    onInk = dark ? 0xff1f1e1d : 0xfffaf9f5;
    scrim = dark ? 0x99000000 : 0x66141413;
  }

  /** Reads the stored appearance on every call so a change in Settings is never missed. */
  public static Theme resolve(Context context) {
    String theme = DownloadStore.prefs(context).getString("theme", "system");
    boolean dark =
        "dark".equals(theme)
            || ("system".equals(theme)
                && (context.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES);
    return new Theme(dark);
  }

  /** Call before {@code super.onCreate}. */
  public static Theme apply(Activity activity) {
    Theme theme = resolve(activity);
    activity.setTheme(theme.dark ? R.style.AppThemeDark : R.style.AppTheme);
    return theme;
  }

  public static Theme viewer() {
    return new Theme(true);
  }
}
