// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Programmatic components shared by every screen. Hairlines and whitespace instead of bordered
 * cards; a single dark primary action; serif only for app-authored headings.
 */
public final class Ui {
  /** Corner radius shared by library tiles and row thumbnails, in dp. */
  public static final float TILE_RADIUS = 12;

  public final Activity activity;
  public final Theme theme;
  private int insetTop, insetBottom;
  private final java.util.List<Runnable> insetsListeners = new java.util.ArrayList<>();

  public Ui(Activity activity, Theme theme) {
    this.activity = activity;
    this.theme = theme;
  }

  public int dp(float value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }

  public int insetTop() {
    return insetTop;
  }

  public int insetBottom() {
    return insetBottom;
  }

  /** Runs after every inset change, including the keyboard opening and closing. */
  public void onInsetsChanged(Runnable listener) {
    insetsListeners.add(listener);
  }

  /**
   * Edge-to-edge root. The first child is the padded content layer; sheets and snackbars are
   * added above it and read the insets themselves.
   */
  public FrameLayout screen(View content) {
    FrameLayout root = new FrameLayout(activity);
    root.setBackgroundColor(theme.bg);
    root.addView(content, new FrameLayout.LayoutParams(-1, -1));
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          insetTop = insets.getSystemWindowInsetTop();
          insetBottom = insets.getSystemWindowInsetBottom();
          content.setPadding(
              insets.getSystemWindowInsetLeft(),
              insetTop,
              insets.getSystemWindowInsetRight(),
              insetBottom);
          for (Runnable listener : insetsListeners) listener.run();
          return insets;
        });
    return root;
  }

  public LinearLayout column() {
    LinearLayout l = new LinearLayout(activity);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  public LinearLayout row() {
    LinearLayout l = new LinearLayout(activity);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  public void gap(LinearLayout parent, int height) {
    View space = new View(activity);
    parent.addView(space, new LinearLayout.LayoutParams(1, dp(height)));
  }

  public View hairline() {
    View line = new View(activity);
    line.setBackgroundColor(theme.hairline);
    line.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f))));
    return line;
  }

  private TextView text(String value, float size, int color) {
    return style(new TextView(activity), value, size, color);
  }

  private <T extends TextView> T style(T t, String value, float size, int color) {
    t.setText(value);
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
    t.setTextColor(color);
    t.setFontFeatureSettings("kern");
    t.setTypeface(Fonts.sans());
    return t;
  }

  /** Screen titles and empty states. */
  public TextView display(String value) {
    TextView t = text(value, 32, theme.ink);
    t.setTypeface(Fonts.display(activity));
    t.setLineSpacing(0, 1.05f);
    t.setLetterSpacing(-0.01f);
    return t;
  }

  /** Sheet titles and the wordmark at small sizes. */
  public TextView title(String value, float size) {
    TextView t = text(value, size, theme.ink);
    t.setTypeface(Fonts.serif(activity));
    t.setLineSpacing(0, 1.1f);
    return t;
  }

  public TextView body(String value) {
    TextView t = text(value, 16, theme.ink);
    t.setLineSpacing(dp(2), 1);
    return t;
  }

  public TextView secondary(String value) {
    TextView t = text(value, 15, theme.secondary);
    t.setLineSpacing(dp(2), 1);
    return t;
  }

  public TextView caption(String value) {
    TextView t = text(value, 13, theme.tertiary);
    t.setLineSpacing(dp(1), 1);
    return t;
  }

  public TextView label(String value, float size) {
    TextView t = text(value, size, theme.ink);
    t.setTypeface(Fonts.medium());
    return t;
  }

  public TextView sectionHeader(String value) {
    TextView t = text(value, 14, theme.secondary);
    t.setTypeface(Fonts.medium());
    t.setPadding(0, dp(20), 0, dp(10));
    if (android.os.Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
    return t;
  }

  public GradientDrawable shape(int color, float radius, int stroke) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    if (stroke != 0) d.setStroke(Math.max(1, dp(1)), stroke);
    return d;
  }

  public Drawable ripple(Drawable content, Drawable mask) {
    return new RippleDrawable(
        ColorStateList.valueOf(theme.dark ? 0x1ffaf9f5 : 0x14141413), content, mask);
  }

  private TextView button(String label, Drawable background, int color, Runnable click) {
    return button(label(label, 16, color), background, click);
  }

  private TextView button(TextView t, Drawable background, Runnable click) {
    t.setGravity(Gravity.CENTER);
    t.setSingleLine();
    t.setEllipsize(TextUtils.TruncateAt.END);
    t.setPadding(dp(20), 0, dp(20), 0);
    t.setMinHeight(dp(52));
    t.setBackground(background);
    t.setFocusable(true);
    t.setClickable(true);
    t.setOnClickListener(v -> click.run());
    return t;
  }

  private TextView label(String value, float size, int color) {
    TextView t = label(value, size);
    t.setTextColor(color);
    return t;
  }

  /** The one obvious action on a screen or sheet. */
  public TextView primaryButton(String label, Runnable click) {
    PressButton t = style(new PressButton(activity), label, 16, theme.onInk);
    t.setTypeface(Fonts.medium());
    button(t, ripple(shape(theme.ink, 26, 0), null), click);
    t.setStateListAnimator(null);
    return t;
  }

  /** Sinks to 0.98 while pressed, following the press with an exponential ease. */
  private static final class PressButton extends TextView {
    private final Motion.Loop loop = new Motion.Loop(this, this::step);
    private float scale = 1, target = 1;

    PressButton(android.content.Context context) {
      super(context);
    }

    @Override
    public void setPressed(boolean pressed) {
      super.setPressed(pressed);
      if (loop == null) return; // Called from the TextView constructor.
      target = pressed ? 0.98f : 1;
      loop.start();
    }

    private boolean step(float dt, boolean calm) {
      scale = Motion.follow(scale, target, dt, 0.06f, calm);
      setScaleX(scale);
      setScaleY(scale);
      return scale != target;
    }
  }

  public TextView secondaryButton(String label, Runnable click) {
    return button(label, ripple(shape(0, 26, theme.hairline), shape(theme.ink, 26, 0)), theme.ink, click);
  }

  /** Text-only action for inline rows. */
  public TextView quietButton(String label, Runnable click) {
    TextView t = button(label, ripple(null, shape(theme.ink, 20, 0)), theme.ink, click);
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
    t.setMinHeight(dp(44));
    t.setPadding(dp(14), 0, dp(14), 0);
    return t;
  }

  public ImageView icon(int res, int tint, int sizeDp) {
    ImageView image = new ImageView(activity);
    image.setImageResource(res);
    image.setImageTintList(ColorStateList.valueOf(tint));
    image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    image.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
    image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return image;
  }

  public ImageView iconButton(int res, String description, Runnable click) {
    ImageView image = icon(res, theme.ink, 48);
    image.setPadding(dp(12), dp(12), dp(12), dp(12));
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setContentDescription(description);
    image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    image.setBackground(ripple(null, shape(theme.ink, 24, 0)));
    image.setFocusable(true);
    image.setClickable(true);
    image.setOnClickListener(v -> click.run());
    return image;
  }

  public EditText field(String hint) {
    EditText field = new EditText(activity);
    field.setHint(hint);
    field.setTextColor(theme.ink);
    field.setHintTextColor(theme.tertiary);
    field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
    field.setTypeface(Fonts.sans());
    field.setSingleLine();
    field.setInputType(InputType.TYPE_CLASS_TEXT);
    field.setPadding(dp(16), dp(14), dp(16), dp(14));
    field.setMinHeight(dp(52));
    field.setBackground(shape(theme.raised, 14, 0));
    return field;
  }

  /** Small pill; {@code click} may be null for a static badge. */
  public TextView chip(String label, Runnable click) {
    TextView t = label(label, 14);
    t.setGravity(Gravity.CENTER);
    t.setSingleLine();
    t.setPadding(dp(14), dp(6), dp(14), dp(6));
    t.setMinHeight(dp(36));
    if (click != null) {
      t.setBackground(ripple(shape(theme.raised, 18, 0), null));
      t.setFocusable(true);
      t.setClickable(true);
      t.setOnClickListener(v -> click.run());
    } else t.setBackground(shape(theme.raised, 18, 0));
    return t;
  }

  /** Settings row: title with the current value underneath. */
  public LinearLayout valueRow(String title, String value, Runnable click) {
    LinearLayout row = row();
    row.setPadding(dp(24), dp(14), dp(16), dp(14));
    row.setMinimumHeight(dp(64));
    LinearLayout texts = column();
    texts.addView(body(title));
    if (value != null && !value.isEmpty()) {
      TextView v = secondary(value);
      v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
      texts.addView(v);
    }
    row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    if (click != null) {
      row.addView(icon(org.still.saver.R.drawable.ic_chevron_right, theme.tertiary, 24));
      row.setBackground(ripple(null, shape(theme.ink, 0, 0)));
      row.setFocusable(true);
      row.setClickable(true);
      row.setOnClickListener(v -> click.run());
    }
    return row;
  }

  /** Keeps {@code Switch.setText(title)} so accessibility services read one control. */
  public LinearLayout switchRow(
      String title,
      String description,
      boolean checked,
      CompoundButton.OnCheckedChangeListener listener) {
    LinearLayout box = column();
    box.setPadding(dp(24), dp(12), dp(24), dp(14));
    Switch control = new Switch(activity);
    control.setText(title);
    control.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
    control.setTextColor(theme.ink);
    control.setTypeface(Fonts.sans());
    control.setMinHeight(dp(48));
    control.setChecked(checked);
    control.setOnCheckedChangeListener(listener);
    box.addView(control, new LinearLayout.LayoutParams(-1, -2));
    if (description != null) {
      TextView d = secondary(description);
      d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
      d.setPadding(0, 0, dp(64), 0);
      box.addView(d);
    }
    return box;
  }

  public static ViewGroup.LayoutParams match() {
    return new ViewGroup.LayoutParams(-1, -2);
  }
}
