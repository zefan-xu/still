// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.still.saver.core.MediaResolver;
import org.still.saver.core.Platforms;
import org.still.saver.ui.Sheet;
import org.still.saver.ui.Snackbar;
import org.still.saver.ui.Theme;
import org.still.saver.ui.Ui;

/** Grouped preferences. Every change is saved immediately; pickers open as sheets. */
public final class SettingsActivity extends Activity {
  static final String[] QUALITY_LABELS = {
    "Choose for each item", "Best available", "Smallest available"
  };
  static final String[] QUALITY_VALUES = {"ask", "best", "smallest"};
  static final String[] THEME_LABELS = {"Follow system", "Light", "Dark"};
  static final String[] THEME_VALUES = {"system", "light", "dark"};
  static final String DEFAULT_API = "https://api.fxtwitter.com";

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private Ui ui;
  private Sheet sheet;
  private Snackbar snackbar;
  private LinearLayout list;
  private ScrollView scroll;
  private String extractorVersion;
  private boolean updating;

  @Override
  public void onCreate(Bundle state) {
    Theme theme = Theme.apply(this);
    super.onCreate(state);
    ui = new Ui(this, theme);
    LinearLayout content = ui.column();
    LinearLayout top = ui.row();
    top.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), 0);
    top.addView(ui.iconButton(R.drawable.ic_arrow_back, "Back", this::finish));
    content.addView(top);
    scroll = new ScrollView(this);
    list = ui.column();
    list.setPadding(0, 0, 0, ui.dp(32));
    scroll.addView(list);
    content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    FrameLayout root = ui.screen(content);
    sheet = new Sheet(ui, root);
    snackbar = new Snackbar(ui, root);
    setContentView(root);
    root.requestApplyInsets();
    render();
    loadExtractorVersion();
  }

  private SharedPreferences prefs() {
    return DownloadStore.prefs(this);
  }

  private void render() {
    int scrollY = scroll.getScrollY();
    list.removeAllViews();
    TextView title = ui.display("Settings");
    title.setPadding(ui.dp(24), ui.dp(4), ui.dp(24), ui.dp(8));
    list.addView(title);

    section("Saving");
    list.addView(
        ui.valueRow(
            "Default quality",
            label(QUALITY_LABELS, QUALITY_VALUES, prefs().getString("quality", "ask")),
            () ->
                choose(
                    "Default quality",
                    QUALITY_LABELS,
                    QUALITY_VALUES,
                    "quality",
                    "ask",
                    false)));
    list.addView(
        ui.switchRow(
            "Save automatically",
            "Save at your default quality right after you share or submit a link.",
            prefs().getBoolean("auto", false),
            (b, checked) -> prefs().edit().putBoolean("auto", checked).apply()));
    list.addView(ui.valueRow("Folder", "Downloads/" + DownloadStore.folder(this), this::editFolder));

    section("Downloads");
    list.addView(
        ui.switchRow(
            "Wi-Fi downloads only",
            "Applies to new downloads. Finding media and previews can still use mobile data.",
            prefs().getBoolean("wifi", false),
            (b, checked) -> prefs().edit().putBoolean("wifi", checked).apply()));
    int parallel = prefs().getInt("parallel", 2);
    list.addView(
        ui.valueRow(
            "At a time",
            parallel + " at a time",
            () ->
                sheet.choice(
                    "Downloads at a time",
                    new String[] {"1", "2", "3", "4"},
                    parallel - 1,
                    index -> {
                      prefs().edit().putInt("parallel", index + 1).apply();
                      render();
                    })));

    section("Appearance");
    list.addView(
        ui.valueRow(
            "Theme",
            label(THEME_LABELS, THEME_VALUES, prefs().getString("theme", "system")),
            () -> choose("Theme", THEME_LABELS, THEME_VALUES, "theme", "system", true)));

    section("Sources");
    list.addView(
        ui.valueRow(
            "Supported links",
            Platforms.ALL.size() + " platforms · public posts only",
            () -> sheet.show(SupportedLinks.build(ui, sheet))));
    String api = prefs().getString("api", DEFAULT_API);
    list.addView(ui.valueRow("X metadata service", URI.create(api).getHost(), this::editApi));
    list.addView(
        ui.valueRow(
            "On-device extractor",
            updating
                ? "Checking the official yt-dlp release…"
                : extractorVersion == null ? "yt-dlp" : "yt-dlp " + extractorVersion,
            updating ? null : this::confirmUpdate));

    section("About");
    list.addView(ui.valueRow("Still " + version(), "Open source · GPL-3.0-or-later", null));
    list.addView(ui.valueRow("Open-source licenses", null, this::licenses));
    TextView note =
        ui.caption(
            "No ads, analytics, purchases or accounts. Preferences and history stay on this"
                + " device. Public media only: login-required, deleted and protected posts may not"
                + " be available.");
    note.setPadding(ui.dp(24), ui.dp(16), ui.dp(24), 0);
    list.addView(note);
    scroll.post(() -> scroll.scrollTo(0, scrollY));
  }

  private void section(String title) {
    TextView header = ui.sectionHeader(title);
    header.setPadding(ui.dp(24), ui.dp(28), ui.dp(24), ui.dp(4));
    list.addView(header);
  }

  private static String label(String[] labels, String[] values, String value) {
    for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return labels[i];
    throw new IllegalStateException("Unknown setting value: " + value);
  }

  private void choose(
      String title,
      String[] labels,
      String[] values,
      String key,
      String defaultValue,
      boolean recreate) {
    String current = prefs().getString(key, defaultValue);
    int selected = 0;
    for (int i = 0; i < values.length; i++) if (values[i].equals(current)) selected = i;
    sheet.choice(
        title,
        labels,
        selected,
        index -> {
          prefs().edit().putString(key, values[index]).apply();
          if (recreate) recreate();
          else render();
        });
  }

  private EditText formField(String value, int inputType) {
    EditText field = ui.field("");
    field.setInputType(inputType);
    field.setText(value);
    field.setSelection(field.length());
    return field;
  }

  private void editFolder() {
    EditText field = formField(DownloadStore.folder(this), InputType.TYPE_CLASS_TEXT);
    sheet.form(
        "Folder inside Downloads",
        "New saves go here. Existing files stay where they are.",
        field,
        "Save",
        name ->
            name.matches("[A-Za-z0-9 _-]{1,60}")
                ? null
                : "Use 1–60 letters, numbers, spaces, _ or -",
        ignored -> {
          prefs().edit().putString("folder", field.getText().toString().trim()).apply();
          render();
        });
  }

  private void editApi() {
    EditText field =
        formField(
            prefs().getString("api", DEFAULT_API),
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    sheet.form(
        "X metadata service",
        "FxTwitter is an open-source public service. You can use a compatible self-hosted HTTPS"
            + " endpoint.",
        field,
        "Save",
        value -> {
          try {
            new MediaResolver(value);
            return null;
          } catch (IllegalArgumentException e) {
            return "Enter an HTTPS endpoint without credentials or query parameters";
          }
        },
        ignored -> {
          prefs().edit().putString("api", field.getText().toString().trim()).apply();
          render();
        });
  }

  private void loadExtractorVersion() {
    worker.execute(
        () -> {
          String version = ExtractorRuntime.installedVersion(getApplicationContext());
          handler.post(
              () -> {
                if (isDestroyed()) return;
                extractorVersion = version;
                render();
              });
        });
  }

  private void confirmUpdate() {
    sheet.confirm(
        "Update the extractor?",
        "Still downloads the latest official yt-dlp release from GitHub. Update when a supported"
            + " site stops working.",
        "Not now",
        "Update",
        false,
        this::updateExtractor);
  }

  private void updateExtractor() {
    updating = true;
    render();
    worker.execute(
        () -> {
          String result;
          try {
            ExtractorRuntime.update(getApplicationContext());
            result = "The extractor is up to date.";
          } catch (Exception e) {
            result = "Update failed. Check your connection and try again.";
          }
          String version = ExtractorRuntime.installedVersion(getApplicationContext());
          String message = result;
          handler.post(
              () -> {
                if (isDestroyed()) return;
                updating = false;
                extractorVersion = version;
                render();
                snackbar.show(message);
              });
        });
  }

  private String version() {
    try {
      return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (PackageManager.NameNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }

  private void licenses() {
    String[][] components = {
      {"Still", "LICENSE"},
      {"Source Serif 4 (SIL OFL 1.1)", "licenses/SourceSerif4-OFL-1.1.txt"},
      {"Material Symbols (Apache-2.0)", "licenses/Apache-2.0.txt"},
      {"youtubedl-android (GPL-3.0)", "licenses/youtubedl-android-GPL-3.0.txt"},
      {"yt-dlp (Unlicense)", "licenses/yt-dlp-Unlicense.txt"},
      {"jsoup (MIT)", "licenses/jsoup-MIT.txt"},
      {"Python", "licenses/Python.txt"},
      {"QuickJS (MIT)", "licenses/QuickJS-MIT.txt"},
      {"Third-party notices", "licenses/THIRD_PARTY_NOTICES.md"},
    };
    List<Sheet.Action> actions = new ArrayList<>();
    for (String[] component : components)
      actions.add(new Sheet.Action(component[0], 0, false, () -> license(component[0], component[1])));
    sheet.actions("Open-source licenses", actions);
  }

  private void license(String title, String asset) {
    String text;
    try (InputStream input = getAssets().open("licenses/" + asset.replaceFirst("^licenses/", ""));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
      text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Missing packaged license " + asset, e);
    }
    LinearLayout box = sheet.frame(title, null);
    TextView body = ui.caption(text);
    body.setTextIsSelectable(true);
    body.setPadding(ui.dp(24), 0, ui.dp(24), ui.dp(8));
    box.addView(body);
    sheet.show(box);
  }

  @Override
  public void onBackPressed() {
    if (!sheet.back()) super.onBackPressed();
  }

  @Override
  protected void onDestroy() {
    worker.shutdownNow();
    handler.removeCallbacksAndMessages(null);
    snackbar.hide();
    super.onDestroy();
  }
}
