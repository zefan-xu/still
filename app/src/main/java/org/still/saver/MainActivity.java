// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.still.saver.core.Platforms;
import org.still.saver.gif.GifService;
import org.still.saver.ui.LibraryModel;
import org.still.saver.ui.Motion;
import org.still.saver.ui.ProgressLine;
import org.still.saver.ui.Sheet;
import org.still.saver.ui.Snackbar;
import org.still.saver.ui.Theme;
import org.still.saver.ui.Ui;

/** Home: paste a link at the top, everything you have saved below. */
public final class MainActivity extends Activity implements SaveSession.Host {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService refresher = Executors.newSingleThreadExecutor();
  private Theme theme;
  private Ui ui;
  private SaveSession session;
  private Sheet sheet;
  private Snackbar snackbar;
  private ResultsSheet results;
  private EntryActions actions;
  private LibraryAdapter adapter;
  private EditText input, search;
  private LinearLayout header, searchBar, composer, status;
  private TextView paste, hint;
  private ImageView go;
  private Motion.Value linked, hintFade;
  private TextView statusText;
  private ProgressLine statusLine;
  private TextView statusCancel, statusLinks;
  /** Opens with the link field focused; sent by the tutorial's "Paste a link". */
  static final String EXTRA_COMPOSE = "org.still.saver.extra.COMPOSE";

  private boolean resumed, searching, resultsShowing, composing;
  private String filter = "", dismissedGif = "", hintState;
  private int refreshTicket, appliedTicket;

  private final Runnable refresh =
      new Runnable() {
        @Override
        public void run() {
          if (!resumed) return;
          int ticket = ++refreshTicket;
          refresher.execute(
              () -> {
                List<DownloadStore.Entry> entries;
                try {
                  DownloadStore.pump(MainActivity.this);
                  entries = DownloadStore.list(MainActivity.this);
                } catch (RuntimeException e) {
                  handler.post(() -> message(e.getMessage()));
                  return;
                }
                handler.post(() -> apply(ticket, entries));
              });
          handler.postDelayed(this, 2000);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    theme = Theme.apply(this);
    super.onCreate(state);
    ui = new Ui(this, theme);
    Object retained = getLastNonConfigurationInstance();
    session = retained instanceof SaveSession ? (SaveSession) retained : new SaveSession(this);
    build();
    actions = new EntryActions(this, sheet, actionsListener());
    actions.restoreState(state);
    if (state != null) {
      input.setText(state.getString("input", ""));
      settleComposer();
      filter = state.getString("filter", "");
      if (state.getBoolean("searching", false)) setSearching(true);
    } else if (OnboardingActivity.due(this, getIntent())) {
      startActivity(new Intent(this, OnboardingActivity.class));
      overridePendingTransition(0, 0);
    } else {
      composing = getIntent().getBooleanExtra(EXTRA_COMPOSE, false);
    }
    refreshNow();
    session.attach(this);
  }

  /** Puts the cursor in the link field with the keyboard up, as the tutorial promised. */
  private void compose() {
    composing = false;
    getIntent().removeExtra(EXTRA_COMPOSE);
    if (searching) setSearching(false);
    input.requestFocus();
    InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
    input.post(() -> keyboard.showSoftInput(input, 0));
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus && composing) compose();
  }

  private EntryActions.Listener actionsListener() {
    return new EntryActions.Listener() {
      @Override
      public void changed() {
        refreshNow();
      }

      @Override
      public void message(String text) {
        MainActivity.this.message(text);
      }
    };
  }

  private void build() {
    LinearLayout content = ui.column();
    header = ui.row();
    header.setPadding(ui.dp(24), ui.dp(10), ui.dp(8), ui.dp(4));
    ImageView mark = ui.icon(R.drawable.ic_mark, theme.ink, 28);
    mark.setImageTintList(null);
    mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
    LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28));
    markParams.setMarginEnd(ui.dp(8));
    header.addView(mark, markParams);
    TextView wordmark = ui.title("Still", 28);
    wordmark.setTypeface(org.still.saver.ui.Fonts.display(this));
    if (android.os.Build.VERSION.SDK_INT >= 28) wordmark.setAccessibilityHeading(true);
    header.addView(wordmark, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(ui.iconButton(R.drawable.ic_search, "Search", () -> setSearching(true)));
    header.addView(
        ui.iconButton(
            R.drawable.ic_settings,
            "Settings",
            () -> startActivity(new Intent(this, SettingsActivity.class))));
    content.addView(header);

    searchBar = ui.row();
    searchBar.setPadding(ui.dp(16), ui.dp(8), ui.dp(8), ui.dp(4));
    search = ui.field("Search saved files");
    search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
    search.addTextChangedListener(
        watcher(
            text -> {
              filter = text;
              refreshNow();
            }));
    searchBar.addView(search, new LinearLayout.LayoutParams(0, -2, 1));
    searchBar.addView(ui.iconButton(R.drawable.ic_close, "Close search", () -> setSearching(false)));
    searchBar.setVisibility(View.GONE);
    content.addView(searchBar);

    composer = ui.column();
    composer.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(4));
    LinearLayout box = ui.row();
    GradientDrawable boxBackground = ui.shape(theme.raised, 18, 0);
    box.setBackground(boxBackground);
    box.setPadding(ui.dp(4), ui.dp(2), ui.dp(6), ui.dp(2));
    int ring = Math.max(1, ui.dp(1));
    Motion.Value focus =
        new Motion.Value(
            box,
            0,
            0.12f,
            p -> boxBackground.setStroke(ring, Theme.mix(theme.raised, theme.ink, p)));
    input = new EditText(this);
    input.setHint("Paste a link");
    input.setTextColor(theme.ink);
    input.setHintTextColor(theme.tertiary);
    input.setTextSize(16);
    input.setTypeface(org.still.saver.ui.Fonts.sans());
    input.setBackground(null);
    input.setSingleLine();
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    input.setImeOptions(EditorInfo.IME_ACTION_GO);
    input.setPadding(ui.dp(14), ui.dp(14), ui.dp(8), ui.dp(14));
    input.setOnEditorActionListener(
        (v, actionId, event) -> {
          boolean enter =
              event != null
                  && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                  && event.getAction() == KeyEvent.ACTION_DOWN;
          if (actionId == EditorInfo.IME_ACTION_GO || enter) {
            find();
            return true;
          }
          return false;
        });
    input.setOnFocusChangeListener((v, focused) -> focus.to(focused ? 1 : 0));
    input.addTextChangedListener(watcher(this::edited));
    box.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
    box.addView(trailing(), new LinearLayout.LayoutParams(-2, -2));
    composer.addView(box, new LinearLayout.LayoutParams(-1, -2));
    hint = ui.caption("");
    hint.setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(6));
    hint.setCompoundDrawablePadding(ui.dp(8));
    hint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    hint.setBackground(ui.ripple(null, ui.shape(theme.ink, 10, 0)));
    hint.setOnClickListener(v -> showSupportedLinks());
    hintFade = new Motion.Value(hint, 1, 0.1f, hint::setAlpha);
    composer.addView(hint, new LinearLayout.LayoutParams(-1, -2));
    status = ui.column();
    status.setPadding(ui.dp(6), ui.dp(10), ui.dp(6), 0);
    statusLine = new ProgressLine(this, theme);
    status.addView(statusLine, new LinearLayout.LayoutParams(-1, ui.dp(3)));
    LinearLayout statusRow = ui.row();
    statusText = ui.caption("");
    statusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    statusRow.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1));
    statusCancel = ui.quietButton("Cancel", () -> session.cancelLookup());
    statusRow.addView(statusCancel);
    statusLinks = ui.quietButton("Supported links", this::showSupportedLinks);
    statusRow.addView(statusLinks);
    status.addView(statusRow);
    status.setVisibility(View.GONE);
    composer.addView(status);
    content.addView(composer);

    ListView list = new ListView(this);
    list.setDivider(null);
    list.setSelector(android.R.color.transparent);
    list.setClipToPadding(false);
    list.setPadding(0, ui.dp(4), 0, ui.dp(24));
    list.setItemsCanFocus(true);
    adapter =
        new LibraryAdapter(
            ui,
            new LibraryAdapter.Callbacks() {
              @Override
              public void open(DownloadStore.Entry entry) {
                actions.open(entry);
              }

              @Override
              public void menu(DownloadStore.Entry entry) {
                actions.menu(entry);
              }

              @Override
              public void inline(DownloadStore.Entry entry) {
                if (entry.active()) actions.confirmCancel(entry);
                else actions.retry(entry);
              }

              @Override
              public void cancelGif() {
                GifService.cancel(MainActivity.this);
                refreshNow();
              }

              @Override
              public void dismissGif() {
                dismissedGif = GifService.getStatus();
                refreshNow();
              }

              @Override
              public void supportedLinks() {
                showSupportedLinks();
              }
            });
    list.setAdapter(adapter);
    content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

    FrameLayout root = ui.screen(content);
    sheet = new Sheet(ui, root);
    snackbar = new Snackbar(ui, root);
    results = new ResultsSheet(ui, sheet, false);
    setContentView(root);
    root.requestApplyInsets();
    settleComposer();
  }

  private interface TextChange {
    void changed(String text);
  }

  private static TextWatcher watcher(TextChange change) {
    return new TextWatcher() {
      public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

      public void onTextChanged(CharSequence s, int a, int b, int c) {
        change.changed(s.toString());
      }

      public void afterTextChanged(Editable e) {}
    };
  }

  /** Paste while the field is empty; an arrow to find media once there is a link. */
  private View trailing() {
    FrameLayout frame = new FrameLayout(this);
    paste = ui.quietButton("Paste", this::paste);
    paste.setContentDescription("Paste");
    frame.addView(paste, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
    go = ui.iconButton(R.drawable.ic_arrow_forward, "Find media", this::find);
    go.setImageTintList(android.content.res.ColorStateList.valueOf(theme.onInk));
    GradientDrawable circle = new GradientDrawable();
    circle.setShape(GradientDrawable.OVAL);
    circle.setColor(theme.ink);
    go.setBackground(ui.ripple(circle, null));
    FrameLayout.LayoutParams params =
        new FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.CENTER);
    params.setMargins(ui.dp(4), ui.dp(4), ui.dp(2), ui.dp(4));
    go.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
    frame.addView(go, params);
    linked = new Motion.Value(frame, 0, 0.08f, this::placeTrailing);
    return frame;
  }

  /** 0 shows Paste, 1 the arrow; in between they cross-scale. */
  private void placeTrailing(float p) {
    paste.setAlpha(1 - p);
    paste.setScaleX(1 - 0.4f * p);
    paste.setScaleY(1 - 0.4f * p);
    paste.setVisibility(p < 1 ? View.VISIBLE : View.GONE);
    go.setAlpha(p);
    go.setScaleX(0.6f + 0.4f * p);
    go.setScaleY(0.6f + 0.4f * p);
    go.setVisibility(p > 0 ? View.VISIBLE : View.GONE);
  }

  private void edited(String text) {
    boolean link = !text.isEmpty();
    paste.setEnabled(!link);
    go.setEnabled(link);
    linked.to(link ? 1 : 0);
    updateHint(text, true);
    if (status.getVisibility() == View.VISIBLE && statusLine.getVisibility() != View.VISIBLE)
      showStatus(null, false, false);
  }

  /** Puts the composer in its resting state for the current text, without motion. */
  private void settleComposer() {
    String text = input.getText().toString();
    paste.setEnabled(text.isEmpty());
    go.setEnabled(!text.isEmpty());
    linked.jump(text.isEmpty() ? 0 : 1);
    updateHint(text, false);
  }

  /** Lists the platforms while idle; while a link is typed, says whether Still can read it. */
  private void updateHint(String text, boolean fade) {
    Platforms.Match match = text.trim().isEmpty() ? null : Platforms.detect(text);
    String state = text.trim().isEmpty() ? "" : match == null ? "unsupported" : match.label();
    if (state.equals(hintState)) return;
    hintState = state;
    Drawable dot = null;
    if (state.isEmpty()) {
      String see = "See all";
      SpannableString idle =
          new SpannableString("Works with " + Platforms.names("and") + " · " + see);
      int start = idle.length() - see.length();
      idle.setSpan(
          new ForegroundColorSpan(theme.ink), start, idle.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      hint.setText(idle);
      hint.setTextColor(theme.tertiary);
      hint.setContentDescription(
          "Works with " + Platforms.names("and") + ". See all supported links");
    } else if (match != null) {
      hint.setText(match.label() + " · ready");
      hint.setTextColor(theme.secondary);
      hint.setContentDescription(match.label() + ", ready");
      GradientDrawable accent = new GradientDrawable();
      accent.setShape(GradientDrawable.OVAL);
      accent.setColor(theme.accentStrong);
      accent.setBounds(0, 0, ui.dp(6), ui.dp(6));
      dot = accent;
    } else {
      hint.setText("Not a supported link");
      hint.setTextColor(theme.tertiary);
      hint.setContentDescription("Not a supported link. See supported links");
    }
    hint.setCompoundDrawablesRelative(dot, null, null, null);
    if (fade) {
      hintFade.jump(0);
      hintFade.to(1);
    } else hintFade.jump(1);
  }

  private void showSupportedLinks() {
    sheet.show(SupportedLinks.build(ui, sheet));
  }

  private void setSearching(boolean value) {
    searching = value;
    header.setVisibility(value ? View.GONE : View.VISIBLE);
    composer.setVisibility(value ? View.GONE : View.VISIBLE);
    searchBar.setVisibility(value ? View.VISIBLE : View.GONE);
    InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
    if (value) {
      if (!search.getText().toString().equals(filter)) search.setText(filter);
      search.requestFocus();
      search.post(() -> keyboard.showSoftInput(search, 0));
    } else {
      search.setText("");
      filter = "";
      keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
      refreshNow();
    }
  }

  private void paste() {
    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
    ClipData clip = clipboard.getPrimaryClip();
    if (clip == null || clip.getItemCount() == 0) {
      showStatus("Copy a post link first.", false, true);
      return;
    }
    input.setText(clip.getItemAt(0).coerceToText(this));
    input.setSelection(input.length());
  }

  private void find() {
    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(input.getWindowToken(), 0);
    session.find(input.getText().toString());
  }

  private void showStatus(String text, boolean loading, boolean error) {
    statusLinks.setVisibility(View.GONE);
    hint.setVisibility(text == null ? View.VISIBLE : View.GONE);
    if (text == null) {
      status.setVisibility(View.GONE);
      return;
    }
    status.setVisibility(View.VISIBLE);
    statusText.setText(text);
    statusText.setTextColor(error ? theme.error : theme.secondary);
    statusLine.setVisibility(loading ? View.VISIBLE : View.GONE);
    statusLine.setProgress(-1);
    statusCancel.setVisibility(loading ? View.VISIBLE : View.GONE);
  }

  @Override
  public void onSessionChanged(SaveSession s) {
    switch (s.phase()) {
      case LOOKING_UP:
        showStatus("Looking up post…", true, false);
        hideResults();
        break;
      case ERROR:
        showStatus(s.message(), false, true);
        String text = input.getText().toString();
        if (!text.trim().isEmpty() && Platforms.detect(text) == null)
          statusLinks.setVisibility(View.VISIBLE);
        hideResults();
        break;
      case READY:
        showStatus(null, false, false);
        showResults();
        if (s.takeAutoSave()) s.save(this);
        break;
      case ADDED:
        hideResults();
        showStatus(null, false, false);
        snackbar.show(
            s.added() == 0
                ? "This media is already downloading."
                : "Added · " + org.still.saver.ui.Format.count(s.added(), "item"));
        input.setText("");
        s.reset();
        refreshNow();
        break;
      default:
        showStatus(null, false, false);
        hideResults();
    }
  }

  private void showResults() {
    View view =
        results.build(
            session,
            new ResultsSheet.Actions() {
              @Override
              public void save() {
                session.save(MainActivity.this);
              }

              @Override
              public void cancel() {
                session.cancelLookup();
              }

              @Override
              public void close() {
                session.reset();
              }

              @Override
              public void openApp() {}
            });
    if (resultsShowing && sheet.isShowing()) {
      if (results.redraw()) sheet.update(view);
      else sheet.replace(view);
    } else {
      sheet.show(view);
      resultsShowing = true;
      sheet.setOnDismiss(
          () -> {
            resultsShowing = false;
            if (session.phase() == SaveSession.Phase.READY) session.reset();
          });
    }
  }

  private void hideResults() {
    if (!resultsShowing) return;
    resultsShowing = false;
    sheet.setOnDismiss(null);
    sheet.dismiss();
  }

  private void refreshNow() {
    try {
      apply(++refreshTicket, DownloadStore.list(this));
    } catch (RuntimeException e) {
      message(e.getMessage());
    }
  }

  private void apply(int ticket, List<DownloadStore.Entry> entries) {
    if (ticket < appliedTicket || isDestroyed()) return;
    appliedTicket = ticket;
    List<LibraryModel.Item> items = new ArrayList<>();
    Map<String, DownloadStore.Entry> byKey = new HashMap<>();
    String gifStatus = GifService.getStatus();
    if (GifService.isRunning())
      items.add(
          new LibraryModel.Item(
              LibraryAdapter.GIF_KEY,
              "Making a GIF",
              "",
              "image/gif",
              System.currentTimeMillis(),
              LibraryModel.State.ACTIVE,
              GifService.getProgress() == 0 ? -1 : GifService.getProgress(),
              gifStatus));
    else if (!gifStatus.isEmpty()
        && !gifStatus.startsWith("GIF saved")
        && !gifStatus.equals(dismissedGif))
      items.add(
          new LibraryModel.Item(
              LibraryAdapter.GIF_KEY,
              "GIF not saved",
              "",
              "image/gif",
              System.currentTimeMillis(),
              LibraryModel.State.FAILED,
              -1,
              gifStatus));
    for (DownloadStore.Entry e : entries) {
      byKey.put(e.key, e);
      LibraryModel.State state =
          e.status == DownloadManager.STATUS_SUCCESSFUL
              ? LibraryModel.State.SAVED
              : e.active() ? LibraryModel.State.ACTIVE : LibraryModel.State.FAILED;
      int progress = e.total > 0 ? (int) Math.min(100, e.bytes * 100 / e.total) : -1;
      String detail = e.description();
      if (state == LibraryModel.State.ACTIVE && e.total > 0)
        detail += " · " + org.still.saver.ui.Format.size(e.total);
      items.add(
          new LibraryModel.Item(
              e.key, e.title, e.filename, e.mime, e.created, state, progress, detail));
    }
    adapter.update(
        LibraryModel.build(
            items,
            searching ? filter : "",
            System.currentTimeMillis(),
            TimeZone.getDefault(),
            Locale.getDefault(),
            LibraryAdapter.COLUMNS),
        byKey);
  }

  void message(String text) {
    snackbar.show(text);
  }

  /** Test hooks. */
  SaveSession session() {
    return session;
  }

  String composerMessage() {
    return status.getVisibility() == View.VISIBLE ? statusText.getText().toString() : "";
  }

  String snackbarMessage() {
    return snackbar.current();
  }

  @Override
  public Object onRetainNonConfigurationInstance() {
    return session;
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("input", input.getText().toString());
    state.putBoolean("searching", searching);
    state.putString("filter", filter);
    actions.saveState(state);
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    refreshNow();
    if (intent.getBooleanExtra(EXTRA_COMPOSE, false)) {
      composing = true;
      if (hasWindowFocus()) compose();
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (Theme.resolve(this).dark != theme.dark) {
      recreate();
      return;
    }
    resumed = true;
    refreshNow();
    handler.postDelayed(refresh, 2000);
  }

  @Override
  protected void onPause() {
    resumed = false;
    handler.removeCallbacks(refresh);
    super.onPause();
  }

  @Override
  public void onBackPressed() {
    if (sheet.back()) return;
    if (searching) {
      setSearching(false);
      return;
    }
    super.onBackPressed();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    boolean granted = StoragePermission.granted(grants);
    if (request == SaveSession.STORAGE_REQUEST) session.onPermissionResult(this, granted);
    else if (request == EntryActions.STORAGE_REQUEST) actions.onPermissionResult(granted);
  }

  @Override
  protected void onDestroy() {
    if (isChangingConfigurations()) session.detach();
    else session.dispose();
    refresher.shutdownNow();
    handler.removeCallbacksAndMessages(null);
    snackbar.hide();
    super.onDestroy();
  }
}
