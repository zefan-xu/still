// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.still.saver.core.Platforms;
import org.still.saver.ui.Sheet;
import org.still.saver.ui.Ui;

/** Which links Still can read, and what it cannot; names only, no brand marks. */
final class SupportedLinks {
  private SupportedLinks() {}

  static View build(Ui ui, Sheet sheet) {
    LinearLayout box =
        sheet.frame("Supported links", "Share or paste a link to a public post from:");
    for (Platforms.Platform platform : Platforms.ALL) {
      LinearLayout row = ui.column();
      row.setPadding(ui.dp(24), ui.dp(10), ui.dp(24), ui.dp(10));
      TextView name = ui.label(platform.name, 16);
      row.addView(name);
      TextView kinds = ui.secondary(platform.kinds);
      kinds.setTextSize(14);
      row.addView(kinds);
      TextView shapes = ui.caption(TextUtils.join("  ·  ", platform.examples));
      shapes.setPadding(0, ui.dp(2), 0, 0);
      shapes.setContentDescription("Links like " + TextUtils.join(", ", platform.examples));
      row.addView(shapes);
      box.addView(row);
    }
    TextView header = ui.sectionHeader("Not supported");
    header.setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(6));
    box.addView(header);
    for (String limit : Platforms.LIMITS) {
      TextView line = ui.secondary("·  " + limit);
      line.setTextSize(14);
      line.setContentDescription(limit);
      line.setPadding(ui.dp(24), ui.dp(2), ui.dp(24), ui.dp(2));
      box.addView(line);
    }
    LinearLayout footer = ui.column();
    footer.setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(4));
    footer.addView(ui.secondaryButton("Close", sheet::dismiss), new LinearLayout.LayoutParams(-1, -2));
    box.addView(footer);
    return box;
  }
}
