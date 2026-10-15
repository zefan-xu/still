// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;
import org.still.saver.core.Media;
import org.still.saver.core.Platforms;
import org.still.saver.core.Post;
import org.still.saver.core.Variant;
import org.still.saver.ui.Format;
import org.still.saver.ui.Motion;
import org.still.saver.ui.ProgressLine;
import org.still.saver.ui.Sheet;
import org.still.saver.ui.Thumbnails;
import org.still.saver.ui.Ui;

/** The post preview: what was found, what will be saved, and one button to save it. */
final class ResultsSheet {
  interface Actions {
    void save();

    void cancel();

    void close();

    /** Share mode only: continue in the full app. */
    void openApp();
  }

  private final Ui ui;
  private final Sheet sheet;
  private final boolean share;
  private int expanded = -1, toggled = -1;
  private Post shownPost;
  private Object screen;
  private boolean redraw;

  ResultsSheet(Ui ui, Sheet sheet, boolean share) {
    this.ui = ui;
    this.sheet = sheet;
    this.share = share;
  }

  View build(SaveSession session, Actions actions) {
    if (session.post() != shownPost) {
      shownPost = session.post();
      expanded = -1;
    }
    Object next = session.phase() == SaveSession.Phase.READY ? session.post() : session.phase();
    redraw = next == screen;
    screen = next;
    try {
      switch (session.phase()) {
        case LOOKING_UP:
          return lookingUp(actions);
        case ADDED:
          return added(session, actions);
        case ERROR:
          return error(session, actions);
        default:
          return ready(session, actions);
      }
    } finally {
      toggled = -1;
    }
  }

  /** True when the last build re-rendered the screen before it; show it with {@link Sheet#update}. */
  boolean redraw() {
    return redraw;
  }

  private View lookingUp(Actions actions) {
    LinearLayout box =
        sheet.frame(
            ui.activity.getString(R.string.save_finding_title),
            ui.activity.getString(R.string.save_finding_body));
    LinearLayout inner = padded();
    View skeleton = new View(ui.activity);
    skeleton.setBackground(ui.shape(ui.theme.raised, 16, 0));
    inner.addView(skeleton, new LinearLayout.LayoutParams(-1, ui.dp(200)));
    ui.gap(inner, 16);
    ProgressLine line = new ProgressLine(ui.activity, ui.theme);
    line.setProgress(-1);
    inner.addView(line, new LinearLayout.LayoutParams(-1, ui.dp(3)));
    ui.gap(inner, 12);
    inner.addView(
        ui.secondaryButton(ui.activity.getString(R.string.action_cancel), actions::cancel),
        new LinearLayout.LayoutParams(-1, -2));
    box.addView(inner);
    return box;
  }

  private View added(SaveSession session, Actions actions) {
    LinearLayout box = ui.column();
    box.setPadding(ui.dp(24), ui.dp(16), ui.dp(24), ui.dp(4));
    box.setGravity(Gravity.CENTER_HORIZONTAL);
    ImageView check = ui.icon(R.drawable.ic_check_circle, ui.theme.accentStrong, 44);
    box.addView(check);
    Motion.play(
        check,
        0.7f,
        age -> {
          float grow = age == Motion.END ? 1 : Motion.smooth(0, 0.12f, age);
          float scale = 0.7f + 0.3f * grow + 0.1f * Motion.burst(age, 0.12f, 0.14f);
          check.setAlpha(grow);
          check.setScaleX(scale);
          check.setScaleY(scale);
        });
    ui.gap(box, 12);
    TextView title =
        ui.title(
            ui.activity.getString(
                session.added() == 0 ? R.string.save_already_title : R.string.save_added_title),
            22);
    title.setTag("sheet-title");
    title.setGravity(Gravity.CENTER);
    box.addView(title);
    ui.gap(box, 6);
    TextView detail =
        ui.secondary(
            session.added() == 0
                ? ui.activity.getString(R.string.save_already_body)
                : ui.activity
                    .getResources()
                    .getQuantityString(
                        R.plurals.save_added_body, session.added(), session.added()));
    detail.setGravity(Gravity.CENTER);
    box.addView(detail);
    ui.gap(box, 20);
    box.addView(
        ui.primaryButton(ui.activity.getString(R.string.save_view), actions::openApp),
        new LinearLayout.LayoutParams(-1, -2));
    ui.gap(box, 8);
    box.addView(
        ui.secondaryButton(ui.activity.getString(R.string.action_done), actions::close),
        new LinearLayout.LayoutParams(-1, -2));
    return box;
  }

  private View error(SaveSession session, Actions actions) {
    LinearLayout box =
        sheet.frame(ui.activity.getString(R.string.save_error_title), session.message());
    if (!session.submitted().isEmpty() && Platforms.detect(session.submitted()) == null) {
      TextView links =
          ui.quietButton(
              ui.activity.getString(R.string.links_see),
              () -> sheet.replace(SupportedLinks.build(ui, sheet)));
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
      params.setMarginStart(ui.dp(10));
      box.addView(links, params);
    }
    LinearLayout buttons = ui.row();
    buttons.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(4));
    buttons.addView(
        ui.secondaryButton(ui.activity.getString(R.string.save_open_still), actions::openApp),
        new LinearLayout.LayoutParams(0, -2, 1));
    View space = new View(ui.activity);
    buttons.addView(space, new LinearLayout.LayoutParams(ui.dp(12), 1));
    buttons.addView(
        ui.primaryButton(ui.activity.getString(R.string.action_close), actions::close),
        new LinearLayout.LayoutParams(0, -2, 1));
    box.addView(buttons);
    return box;
  }

  private View ready(SaveSession session, Actions actions) {
    Post post = session.post();
    LinearLayout box = ui.column();
    box.setPadding(0, ui.dp(8), 0, ui.dp(4));
    LinearLayout head = padded();
    TextView author =
        ui.title(
            post.author.isEmpty() ? ui.activity.getString(R.string.save_media_found) : post.author,
            20);
    author.setTag("sheet-title");
    author.setTypeface(org.still.saver.ui.Fonts.medium());
    author.setTextSize(17);
    head.addView(author);
    if (!post.title.isEmpty()) {
      ui.gap(head, 4);
      TextView caption = ui.secondary(post.title);
      caption.setMaxLines(3);
      caption.setEllipsize(TextUtils.TruncateAt.END);
      head.addView(caption);
    }
    box.addView(head);
    ui.gap(box, 16);
    boolean ask = "ask".equals(session.quality());
    int count = post.media.size();
    if (count == 1) {
      LinearLayout single = padded();
      single.addView(tile(session, 0, false), new LinearLayout.LayoutParams(-1, ui.dp(240)));
      ui.gap(single, 10);
      single.addView(meta(session, 0, ask));
      box.addView(single);
    } else {
      HorizontalScrollView scroller = new HorizontalScrollView(ui.activity);
      scroller.setHorizontalScrollBarEnabled(false);
      scroller.setClipToPadding(false);
      scroller.setPadding(ui.dp(24), 0, ui.dp(12), 0);
      LinearLayout strip = ui.row();
      strip.setGravity(Gravity.TOP);
      for (int i = 0; i < count; i++) {
        LinearLayout item = ui.column();
        item.addView(tile(session, i, true), new LinearLayout.LayoutParams(ui.dp(148), ui.dp(184)));
        ui.gap(item, 8);
        item.addView(meta(session, i, ask), new LinearLayout.LayoutParams(ui.dp(148), -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.rightMargin = ui.dp(12);
        strip.addView(item, params);
      }
      scroller.addView(strip);
      box.addView(scroller);
    }
    if (ask && expanded >= 0 && expanded < count) box.addView(qualityOptions(session, expanded));
    LinearLayout footer = padded();
    if (session.message() != null) {
      ui.gap(footer, 14);
      TextView problem = ui.caption(session.message());
      problem.setTextColor(ui.theme.error);
      problem.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
      footer.addView(problem);
    }
    ui.gap(footer, 18);
    footer.addView(
        ui.primaryButton(saveLabel(ui.activity, session), actions::save),
        new LinearLayout.LayoutParams(-1, -2));
    ui.gap(footer, 10);
    TextView where =
        ui.caption(
            ui.activity.getString(R.string.save_destination, DownloadStore.folder(ui.activity)));
    where.setGravity(Gravity.CENTER);
    footer.addView(where, new LinearLayout.LayoutParams(-1, -2));
    box.addView(footer);
    return box;
  }

  static String saveLabel(Context context, SaveSession session) {
    Post post = session.post();
    int included = session.includedCount();
    if (post.media.size() == 1) {
      String kind = Format.kind(post.media.get(0).kind);
      switch (kind) {
        case "video":
          return context.getString(R.string.save_one_video);
        case "gif":
          return context.getString(R.string.save_one_gif);
        case "photo":
          return context.getString(R.string.save_one_photo);
        case "":
          return context.getString(R.string.save_one_media);
        default:
          return context.getString(R.string.save_one_other, kind.toLowerCase(Locale.ROOT));
      }
    }
    return context.getResources().getQuantityString(R.plurals.save_items, included, included);
  }

  /** "Video", "GIF", "Photo"; the start of a line or a name such as "Video 1". */
  private String kindLabel(String kind) {
    switch (Format.kind(kind)) {
      case "video":
        return ui.activity.getString(R.string.kind_video);
      case "gif":
        return ui.activity.getString(R.string.kind_gif);
      case "photo":
        return ui.activity.getString(R.string.kind_photo);
      case "":
        return ui.activity.getString(R.string.kind_media);
      default:
        return Format.capitalized(kind);
    }
  }

  /** The kind inside a sentence, such as "Quality for video 1". */
  private String kindInline(String kind) {
    switch (Format.kind(kind)) {
      case "video":
        return ui.activity.getString(R.string.kind_video_inline);
      case "gif":
        return ui.activity.getString(R.string.kind_gif_inline);
      case "photo":
        return ui.activity.getString(R.string.kind_photo_inline);
      case "":
        return ui.activity.getString(R.string.kind_media_inline);
      default:
        return kind.toLowerCase(Locale.ROOT);
    }
  }

  private FrameLayout tile(SaveSession session, int index, boolean selectable) {
    Media media = session.post().media.get(index);
    FrameLayout frame = new FrameLayout(ui.activity);
    frame.setBackground(ui.shape(ui.theme.raised, 16, 0));
    frame.setClipToOutline(true);
    ImageView glyph = ui.icon(glyph(media.kind), ui.theme.tertiary, 32);
    frame.addView(glyph, new FrameLayout.LayoutParams(ui.dp(32), ui.dp(32), Gravity.CENTER));
    ImageView preview = new ImageView(ui.activity);
    preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
    preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    frame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
    if (media.thumbnail != null)
      Thumbnails.get(ui.activity).bindRemote(preview, media.thumbnail, Thumbnails.Sizes.PREVIEW, null);
    if (media.durationMs > 0) frame.addView(badge(Format.duration(media.durationMs)), corner(Gravity.BOTTOM | Gravity.END));
    else if ("gif".equals(media.kind))
      frame.addView(
          badge(ui.activity.getString(R.string.library_gif_badge)),
          corner(Gravity.BOTTOM | Gravity.END));
    String name =
        ui.activity.getString(R.string.save_media_name, kindLabel(media.kind), index + 1);
    if (selectable) {
      boolean included = session.included(index);
      ImageView check =
          ui.icon(
              included ? R.drawable.ic_check_circle : R.drawable.ic_radio_button_unchecked,
              included ? ui.theme.accent : 0xffffffff,
              28);
      check.setBackground(ui.shape(included ? 0xffffffff : 0x66000000, 14, 0));
      frame.addView(check, corner(Gravity.TOP | Gravity.END));
      float dim = included ? 1 : 0.45f;
      if (index == toggled) {
        new Motion.Value(preview, included ? 0.45f : 1, 0.1f, preview::setAlpha).to(dim);
        Motion.play(
            check,
            0.6f,
            age -> {
              float scale = 1 + 0.16f * Motion.burst(age, 0.06f, 0.12f);
              check.setScaleX(scale);
              check.setScaleY(scale);
            });
      } else preview.setAlpha(dim);
      frame.setContentDescription(
          ui.activity.getString(R.string.save_include_description, index + 1, name));
      frame.setFocusable(true);
      frame.setClickable(true);
      frame.setSelected(included);
      frame.setForeground(ui.ripple(null, ui.shape(ui.theme.ink, 16, 0)));
      frame.setOnClickListener(
          v -> {
            toggled = index;
            session.toggle(index);
          });
    } else frame.setContentDescription(name);
    return frame;
  }

  private TextView badge(String text) {
    TextView badge = ui.label(text, 12);
    badge.setTextColor(0xffffffff);
    badge.setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3));
    badge.setBackground(ui.shape(0x99000000, 10, 0));
    return badge;
  }

  private FrameLayout.LayoutParams corner(int gravity) {
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, gravity);
    params.setMargins(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
    return params;
  }

  private View meta(SaveSession session, int index, boolean ask) {
    Media media = session.post().media.get(index);
    Variant variant = session.chosen(index);
    LinearLayout line = ui.row();
    boolean video = !media.kind.equals("photo");
    String resolution = Format.resolution(variant.width, variant.height, video);
    String text =
        resolution.isEmpty() || ask
            ? kindLabel(media.kind)
            : ui.activity.getString(R.string.save_media_meta, kindLabel(media.kind), resolution);
    TextView kind = ui.caption(text);
    kind.setSingleLine();
    kind.setEllipsize(TextUtils.TruncateAt.END);
    line.addView(kind, new LinearLayout.LayoutParams(0, -2, 1));
    if (ask && media.variants.size() > 1) {
      TextView chip =
          ui.chip(
              variant.label + "  ▾",
              () -> {
                expanded = expanded == index ? -1 : index;
                session.refresh();
              });
      chip.setContentDescription(
          ui.activity.getString(R.string.save_quality_description, index + 1));
      chip.setTextSize(13);
      line.addView(chip);
    } else if (ask) {
      TextView only = ui.caption(variant.label);
      line.addView(only);
    }
    return line;
  }

  private View qualityOptions(SaveSession session, int index) {
    Media media = session.post().media.get(index);
    LinearLayout box = padded();
    ui.gap(box, 14);
    TextView heading =
        ui.caption(
            ui.activity.getString(R.string.save_quality_title, kindInline(media.kind), index + 1));
    box.addView(heading);
    ui.gap(box, 8);
    HorizontalScrollView scroller = new HorizontalScrollView(ui.activity);
    scroller.setHorizontalScrollBarEnabled(false);
    LinearLayout chips = ui.row();
    Variant current = session.chosen(index);
    for (Variant variant : media.variants) {
      TextView chip =
          ui.chip(
              variant.label,
              () -> {
                expanded = -1;
                session.choose(index, variant);
              });
      if (variant == current) {
        chip.setBackground(ui.shape(ui.theme.ink, 18, 0));
        chip.setTextColor(ui.theme.onInk);
        chip.setSelected(true);
      }
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
      params.rightMargin = ui.dp(8);
      chips.addView(chip, params);
    }
    scroller.addView(chips);
    box.addView(scroller);
    return box;
  }

  private LinearLayout padded() {
    LinearLayout box = ui.column();
    box.setPadding(ui.dp(24), 0, ui.dp(24), 0);
    return box;
  }

  static int glyph(String kind) {
    return "photo".equals(kind) ? R.drawable.ic_photo : "gif".equals(kind) ? R.drawable.ic_gif_box : R.drawable.ic_videocam;
  }
}
