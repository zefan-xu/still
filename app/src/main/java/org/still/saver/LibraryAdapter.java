// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.still.saver.ui.Format;
import org.still.saver.ui.LibraryModel;
import org.still.saver.ui.ProgressLine;
import org.still.saver.ui.Thumbnails;
import org.still.saver.ui.Ui;

/** Home list rows: section headers, in-progress rows, a three-column grid, and search rows. */
final class LibraryAdapter extends BaseAdapter {
  static final String GIF_KEY = "gif";
  static final int COLUMNS = 3;

  interface Callbacks {
    void open(DownloadStore.Entry entry);

    void menu(DownloadStore.Entry entry);

    /** Inline Cancel or Retry on an in-progress row. */
    void inline(DownloadStore.Entry entry);

    void cancelGif();

    void dismissGif();
  }

  private final Ui ui;
  private final Callbacks callbacks;
  private List<LibraryModel.Row> rows = Collections.emptyList();
  private Map<String, DownloadStore.Entry> entries = new HashMap<>();
  private String signature = "";

  LibraryAdapter(Ui ui, Callbacks callbacks) {
    this.ui = ui;
    this.callbacks = callbacks;
  }

  /** Returns true when the visible content changed. */
  boolean update(List<LibraryModel.Row> next, Map<String, DownloadStore.Entry> byKey) {
    String nextSignature = LibraryModel.signature(next);
    entries = byKey;
    if (nextSignature.equals(signature)) return false;
    rows = next;
    signature = nextSignature;
    notifyDataSetChanged();
    return true;
  }

  @Override
  public int getCount() {
    return rows.size();
  }

  @Override
  public LibraryModel.Row getItem(int position) {
    return rows.get(position);
  }

  @Override
  public long getItemId(int position) {
    return rows.get(position).id();
  }

  @Override
  public boolean hasStableIds() {
    return true;
  }

  @Override
  public int getViewTypeCount() {
    return LibraryModel.Type.values().length;
  }

  @Override
  public int getItemViewType(int position) {
    return rows.get(position).type.ordinal();
  }

  @Override
  public boolean isEnabled(int position) {
    return false; // Rows handle their own clicks; the list itself has no selection.
  }

  @Override
  public View getView(int position, View convert, ViewGroup parent) {
    LibraryModel.Row row = rows.get(position);
    switch (row.type) {
      case HEADER:
        return header(row.text);
      case PROGRESS:
        return progress(row.item());
      case GRID:
        return grid(row, convert);
      case LIST:
        return listRow(row.item());
      default:
        return empty(row.text);
    }
  }

  private View header(String text) {
    TextView view = ui.sectionHeader(text);
    view.setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(10));
    return view;
  }

  private View empty(String kind) {
    LinearLayout box = ui.column();
    box.setPadding(ui.dp(24), ui.dp(48), ui.dp(24), ui.dp(24));
    if (LibraryModel.EMPTY_SEARCH.equals(kind)) {
      box.addView(ui.title("No files match this search.", 20));
      return box;
    }
    box.addView(ui.display("Nothing saved yet."));
    ui.gap(box, 10);
    box.addView(ui.secondary("Share a post to Still from any app, or paste a link above."));
    ui.gap(box, 18);
    box.addView(
        ui.caption(
            "Works with public posts on X, Instagram, TikTok, Facebook, Pinterest, Threads and"
                + " Bluesky. Files stay on this device."));
    return box;
  }

  private FrameLayout thumb(int sizeDp, String mime) {
    FrameLayout frame = new FrameLayout(ui.activity);
    frame.setBackground(ui.shape(ui.theme.raised, 12, 0));
    frame.setClipToOutline(true);
    ImageView glyph = ui.icon(glyph(mime), ui.theme.tertiary, 22);
    frame.addView(glyph, new FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER));
    ImageView image = new ImageView(ui.activity);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
    frame.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp)));
    return frame;
  }

  private static ImageView image(FrameLayout thumb) {
    return (ImageView) thumb.getChildAt(1);
  }

  static int glyph(String mime) {
    if (mime == null) return R.drawable.ic_photo;
    if (mime.equals("image/gif")) return R.drawable.ic_gif_box;
    return mime.startsWith("video/") ? R.drawable.ic_videocam : R.drawable.ic_photo;
  }

  private View progress(LibraryModel.Item item) {
    boolean gif = GIF_KEY.equals(item.key);
    DownloadStore.Entry entry = gif ? null : entries.get(item.key);
    LinearLayout row = ui.row();
    row.setPadding(ui.dp(24), ui.dp(8), ui.dp(12), ui.dp(8));
    row.setMinimumHeight(ui.dp(72));
    FrameLayout thumb = thumb(56, item.mime);
    if (entry != null && !entry.thumb.isEmpty())
      Thumbnails.get(ui.activity).bindRemote(image(thumb), entry.thumb, Thumbnails.Sizes.ROW, null);
    row.addView(thumb);
    LinearLayout texts = ui.column();
    texts.setPadding(ui.dp(14), 0, ui.dp(8), 0);
    TextView title = ui.label(item.name(), 15);
    title.setSingleLine();
    title.setEllipsize(TextUtils.TruncateAt.END);
    texts.addView(title);
    if (item.state == LibraryModel.State.ACTIVE) {
      ui.gap(texts, 8);
      ProgressLine line = new ProgressLine(ui.activity, ui.theme);
      line.setProgress(item.progress);
      texts.addView(line, new LinearLayout.LayoutParams(-1, ui.dp(3)));
    }
    ui.gap(texts, 6);
    TextView status = ui.caption(item.status);
    status.setMaxLines(2);
    status.setEllipsize(TextUtils.TruncateAt.END);
    if (item.state == LibraryModel.State.FAILED) status.setTextColor(ui.theme.error);
    texts.addView(status);
    row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    if (gif) {
      if (item.state == LibraryModel.State.ACTIVE) {
        TextView cancel = ui.quietButton("Cancel", callbacks::cancelGif);
        cancel.setContentDescription("Cancel GIF export");
        row.addView(cancel);
      } else
        row.addView(ui.iconButton(R.drawable.ic_close, "Dismiss", callbacks::dismissGif));
      return row;
    }
    if (entry == null) return row;
    if (entry.active()) row.addView(ui.quietButton("Cancel", () -> callbacks.inline(entry)));
    else if (entry.id != 0) row.addView(ui.quietButton("Retry", () -> callbacks.inline(entry)));
    row.setBackground(ui.ripple(null, ui.shape(ui.theme.ink, 0, 0)));
    row.setOnClickListener(v -> callbacks.menu(entry));
    row.setContentDescription(item.name() + ", " + item.status);
    return row;
  }

  /** A square cell; height follows the measured width. */
  private static final class Square extends FrameLayout {
    Square(Context context) {
      super(context);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      super.onMeasure(widthSpec, widthSpec);
    }
  }

  private static final class GridHolder {
    final Square[] cells = new Square[COLUMNS];
    final ImageView[] images = new ImageView[COLUMNS];
    final ImageView[] glyphs = new ImageView[COLUMNS];
    final TextView[] badges = new TextView[COLUMNS];
  }

  private View grid(LibraryModel.Row row, View convert) {
    LinearLayout line;
    GridHolder holder;
    if (convert instanceof LinearLayout && convert.getTag() instanceof GridHolder) {
      line = (LinearLayout) convert;
      holder = (GridHolder) convert.getTag();
    } else {
      line = ui.row();
      line.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(4));
      holder = new GridHolder();
      for (int i = 0; i < COLUMNS; i++) {
        Square cell = new Square(ui.activity);
        cell.setBackground(ui.shape(ui.theme.raised, 10, 0));
        cell.setClipToOutline(true);
        ImageView glyph = ui.icon(R.drawable.ic_photo, ui.theme.tertiary, 24);
        cell.addView(glyph, new FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER));
        ImageView image = new ImageView(ui.activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cell.addView(image, new FrameLayout.LayoutParams(-1, -1));
        TextView badge = ui.label("", 11);
        badge.setTextColor(0xffffffff);
        badge.setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(2));
        badge.setBackground(ui.shape(0x99000000, 8, 0));
        FrameLayout.LayoutParams badgeParams =
            new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        badgeParams.setMargins(0, 0, ui.dp(6), ui.dp(6));
        cell.addView(badge, badgeParams);
        cell.setForeground(ui.ripple(null, ui.shape(ui.theme.ink, 10, 0)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2));
        line.addView(cell, params);
        holder.cells[i] = cell;
        holder.images[i] = image;
        holder.glyphs[i] = glyph;
        holder.badges[i] = badge;
      }
      line.setTag(holder);
      line.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
    }
    for (int i = 0; i < COLUMNS; i++) {
      Square cell = holder.cells[i];
      if (i >= row.items.size()) {
        cell.setVisibility(View.INVISIBLE);
        cell.setOnClickListener(null);
        cell.setOnLongClickListener(null);
        Thumbnails.get(ui.activity).clear(holder.images[i]);
        continue;
      }
      LibraryModel.Item item = row.items.get(i);
      DownloadStore.Entry entry = entries.get(item.key);
      cell.setVisibility(View.VISIBLE);
      holder.glyphs[i].setImageResource(glyph(item.mime));
      cell.setContentDescription(item.name());
      TextView badge = holder.badges[i];
      boolean gif = "image/gif".equals(item.mime);
      badge.setText(gif ? "GIF" : "");
      badge.setVisibility(gif ? View.VISIBLE : View.GONE);
      if (entry == null || entry.uri == null) continue;
      Uri uri = entry.uri;
      boolean video = item.mime.startsWith("video/");
      Thumbnails.get(ui.activity)
          .bindLocal(
              holder.images[i],
              uri,
              item.mime,
              Thumbnails.Sizes.TILE,
              thumb -> {
                if (video && thumb.durationMs > 0) {
                  badge.setText(Format.duration(thumb.durationMs));
                  badge.setVisibility(View.VISIBLE);
                }
              });
      cell.setOnClickListener(v -> callbacks.open(entry));
      cell.setOnLongClickListener(
          v -> {
            callbacks.menu(entry);
            return true;
          });
    }
    return line;
  }

  private View listRow(LibraryModel.Item item) {
    DownloadStore.Entry entry = entries.get(item.key);
    LinearLayout row = ui.row();
    row.setPadding(ui.dp(24), ui.dp(8), ui.dp(8), ui.dp(8));
    row.setMinimumHeight(ui.dp(72));
    FrameLayout thumb = thumb(56, item.mime);
    if (entry != null && entry.uri != null && item.state == LibraryModel.State.SAVED)
      Thumbnails.get(ui.activity)
          .bindLocal(image(thumb), entry.uri, item.mime, Thumbnails.Sizes.ROW, null);
    row.addView(thumb);
    LinearLayout texts = ui.column();
    texts.setPadding(ui.dp(14), 0, ui.dp(8), 0);
    TextView title = ui.label(item.name(), 15);
    title.setMaxLines(2);
    title.setEllipsize(TextUtils.TruncateAt.END);
    texts.addView(title);
    TextView name = ui.caption(item.state == LibraryModel.State.SAVED ? item.filename : item.status);
    name.setSingleLine();
    name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    texts.addView(name);
    row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    if (entry == null) return row;
    row.addView(
        ui.iconButton(
            R.drawable.ic_more_horiz,
            "More actions for " + item.name(),
            () -> callbacks.menu(entry)));
    row.setBackground(ui.ripple(null, ui.shape(ui.theme.ink, 0, 0)));
    row.setOnClickListener(
        v -> {
          if (EntryActions.saved(entry)) callbacks.open(entry);
          else callbacks.menu(entry);
        });
    return row;
  }
}
