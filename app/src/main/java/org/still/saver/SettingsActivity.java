// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.app.Activity;
import android.content.Intent;
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
  static final int[] QUALITY_LABELS = {
    R.string.settings_quality_ask, R.string.settings_quality_best, R.string.settings_quality_smallest
  };
  static final String[] QUALITY_VALUES = {"ask", "best", "smallest"};
  static final int[] THEME_LABELS = {
    R.string.settings_theme_system, R.string.settings_theme_light, R.string.settings_theme_dark
  };
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
    top.addView(ui.iconButton(R.drawable.ic_arrow_back, getString(R.string.action_back), this::finish));
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
    TextView title = ui.display(getString(R.string.settings_title));
    title.setPadding(ui.dp(24), ui.dp(4), ui.dp(24), ui.dp(8));
    list.addView(title);

    section(R.string.settings_saving_title);
    list.addView(
        ui.valueRow(
            getString(R.string.settings_quality),
            label(QUALITY_LABELS, QUALITY_VALUES, prefs().getString("quality", "ask")),
            () ->
                choose(
                    R.string.settings_quality,
                    QUALITY_LABELS,
                    QUALITY_VALUES,
                    "quality",
                    "ask",
                    false)));
    list.addView(
        ui.switchRow(
            getString(R.string.settings_auto),
            getString(R.string.settings_auto_summary),
            prefs().getBoolean("auto", false),
            (b, checked) -> prefs().edit().putBoolean("auto", checked).apply()));
    list.addView(
        ui.valueRow(
            getString(R.string.settings_folder),
            getString(R.string.settings_folder_summary, DownloadStore.folder(this)),
            this::editFolder));

    section(R.string.settings_downloads_title);
    list.addView(
        ui.switchRow(
            getString(R.string.settings_wifi),
            getString(R.string.settings_wifi_summary),
            prefs().getBoolean("wifi", false),
            (b, checked) -> prefs().edit().putBoolean("wifi", checked).apply()));
    int parallel = prefs().getInt("parallel", 2);
    list.addView(
        ui.valueRow(
            getString(R.string.settings_parallel),
            getResources().getQuantityString(R.plurals.settings_parallel_summary, parallel, parallel),
            () ->
                sheet.choice(
                    getString(R.string.settings_parallel_title),
                    new String[] {"1", "2", "3", "4"},
                    parallel - 1,
                    index -> {
                      prefs().edit().putInt("parallel", index + 1).apply();
                      render();
                    })));

    section(R.string.settings_appearance_title);
    list.addView(
        ui.valueRow(
            getString(R.string.settings_theme),
            label(THEME_LABELS, THEME_VALUES, prefs().getString("theme", "system")),
            () ->
                choose(R.string.settings_theme, THEME_LABELS, THEME_VALUES, "theme", "system", true)));

    section(R.string.settings_sources_title);
    list.addView(
        ui.valueRow(
            getString(R.string.links_title),
            getResources()
                .getQuantityString(
                    R.plurals.settings_links_summary, Platforms.ALL.size(), Platforms.ALL.size()),
            () -> sheet.show(SupportedLinks.build(ui, sheet))));
    String api = prefs().getString("api", DEFAULT_API);
    list.addView(
        ui.valueRow(getString(R.string.settings_api), URI.create(api).getHost(), this::editApi));
    list.addView(
        ui.valueRow(
            getString(R.string.settings_extractor),
            updating
                ? getString(R.string.settings_extractor_checking)
                : extractorVersion == null ? "yt-dlp" : "yt-dlp " + extractorVersion,
            updating ? null : this::confirmUpdate));

    section(R.string.settings_about_title);
    list.addView(
        ui.valueRow(
            getString(R.string.settings_tutorial),
            getString(R.string.settings_tutorial_summary),
            () -> startActivity(new Intent(this, OnboardingActivity.class))));
    list.addView(
        ui.valueRow(
            getString(R.string.settings_version, version()),
            getString(R.string.settings_version_summary),
            null));
    list.addView(ui.valueRow(getString(R.string.settings_licenses), null, this::licenses));
    TextView note = ui.caption(getString(R.string.settings_privacy));
    note.setPadding(ui.dp(24), ui.dp(16), ui.dp(24), 0);
    list.addView(note);
    scroll.post(() -> scroll.scrollTo(0, scrollY));
  }

  private void section(int title) {
    TextView header = ui.sectionHeader(getString(title));
    header.setPadding(ui.dp(24), ui.dp(28), ui.dp(24), ui.dp(4));
    list.addView(header);
  }

  private String label(int[] labels, String[] values, String value) {
    for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return getString(labels[i]);
    throw new IllegalStateException("Unknown setting value: " + value);
  }

  private void choose(
      int title,
      int[] labels,
      String[] values,
      String key,
      String defaultValue,
      boolean recreate) {
    String current = prefs().getString(key, defaultValue);
    int selected = 0;
    for (int i = 0; i < values.length; i++) if (values[i].equals(current)) selected = i;
    String[] texts = new String[labels.length];
    for (int i = 0; i < labels.length; i++) texts[i] = getString(labels[i]);
    sheet.choice(
        getString(title),
        texts,
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
        getString(R.string.settings_folder_title),
        getString(R.string.settings_folder_body),
        field,
        getString(R.string.action_save),
        name ->
            name.matches("[A-Za-z0-9 _-]{1,60}")
                ? null
                : getString(R.string.settings_folder_error),
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
        getString(R.string.settings_api),
        getString(R.string.settings_api_body),
        field,
        getString(R.string.action_save),
        value -> {
          try {
            new MediaResolver(value);
            return null;
          } catch (IllegalArgumentException e) {
            return getString(R.string.settings_api_error);
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
        getString(R.string.settings_update_title),
        getString(R.string.settings_update_body),
        getString(R.string.action_not_now),
        getString(R.string.settings_update_action),
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
            result = getString(R.string.settings_update_done);
          } catch (Exception e) {
            result = getString(R.string.settings_update_failed);
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
      {getString(R.string.app_name), "LICENSE"},
      {"Source Serif 4 (SIL OFL 1.1)", "licenses/SourceSerif4-OFL-1.1.txt"},
      {"Material Symbols (Apache-2.0)", "licenses/Apache-2.0.txt"},
      {"youtubedl-android (GPL-3.0)", "licenses/youtubedl-android-GPL-3.0.txt"},
      {"yt-dlp (Unlicense)", "licenses/yt-dlp-Unlicense.txt"},
      {"jsoup (MIT)", "licenses/jsoup-MIT.txt"},
      {"Python", "licenses/Python.txt"},
      {"QuickJS (MIT)", "licenses/QuickJS-MIT.txt"},
      {getString(R.string.settings_licenses_notices), "licenses/THIRD_PARTY_NOTICES.md"},
    };
    List<Sheet.Action> actions = new ArrayList<>();
    for (String[] component : components)
      actions.add(new Sheet.Action(component[0], 0, false, () -> license(component[0], component[1])));
    sheet.actions(getString(R.string.settings_licenses), actions);
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
