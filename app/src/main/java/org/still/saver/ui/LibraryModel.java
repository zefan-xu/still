// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.ui;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Turns library entries into the rows the home list shows: an in-progress section, then saved
 * items as a grid grouped by date. Searching switches to plain list rows. Pure Java.
 */
public final class LibraryModel {
  public enum State {
    ACTIVE,
    FAILED,
    SAVED
  }

  public enum Type {
    HEADER,
    PROGRESS,
    GRID,
    LIST,
    EMPTY
  }

  public static final class Item {
    public final String key, title, filename, mime, status;
    public final long created;
    public final State state;
    /** 0–100, or negative when the size is unknown. */
    public final int progress;

    public Item(
        String key,
        String title,
        String filename,
        String mime,
        long created,
        State state,
        int progress,
        String status) {
      this.key = key;
      this.title = title;
      this.filename = filename;
      this.mime = mime;
      this.created = created;
      this.state = state;
      this.progress = progress;
      this.status = status;
    }

    public String name() {
      return title.isEmpty() ? filename : title;
    }

    String signature() {
      return key + '|' + state + '|' + progress + '|' + status + '|' + title + '|' + mime;
    }
  }

  public static final class Row {
    public final Type type;
    public final String text;
    public final List<Item> items;

    Row(Type type, String text, List<Item> items) {
      this.type = type;
      this.text = text;
      this.items = items;
    }

    public Item item() {
      return items.get(0);
    }

    /** Stable across refreshes so the list keeps scroll position and recycled views. */
    public long id() {
      StringBuilder key = new StringBuilder(type.name()).append(':');
      if (text != null) key.append(text);
      for (Item item : items) key.append(item.key).append(',');
      long hash = 1125899906842597L;
      for (int i = 0; i < key.length(); i++) hash = 31 * hash + key.charAt(i);
      return hash;
    }

    String signature() {
      StringBuilder out = new StringBuilder(type.name()).append(':').append(text);
      for (Item item : items) out.append('[').append(item.signature()).append(']');
      return out.toString();
    }
  }

  public static final String EMPTY_LIBRARY = "library", EMPTY_SEARCH = "search";

  /** Header row keys; the app shows each as a localized title. */
  public static final String SECTION_PROGRESS = "progress",
      SECTION_TODAY = "today",
      SECTION_WEEK = "week",
      SECTION_EARLIER = "earlier",
      SECTION_SAVED = "saved";

  private LibraryModel() {}

  public static List<Row> build(
      List<Item> items, String filter, long now, TimeZone zone, Locale locale, int columns) {
    List<Row> rows = new ArrayList<>();
    String query = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
    if (!query.isEmpty()) {
      for (Item item : items)
        if ((item.title + " " + item.filename).toLowerCase(Locale.ROOT).contains(query))
          rows.add(new Row(Type.LIST, null, Collections.singletonList(item)));
      if (rows.isEmpty()) rows.add(new Row(Type.EMPTY, EMPTY_SEARCH, Collections.<Item>emptyList()));
      return rows;
    }
    List<Item> working = new ArrayList<>();
    List<Item> today = new ArrayList<>(), week = new ArrayList<>(), earlier = new ArrayList<>();
    Calendar calendar = Calendar.getInstance(zone, locale);
    calendar.setTimeInMillis(now);
    calendar.set(Calendar.HOUR_OF_DAY, 0);
    calendar.set(Calendar.MINUTE, 0);
    calendar.set(Calendar.SECOND, 0);
    calendar.set(Calendar.MILLISECOND, 0);
    long startOfToday = calendar.getTimeInMillis();
    calendar.add(Calendar.DAY_OF_YEAR, -6);
    long startOfWeek = calendar.getTimeInMillis();
    for (Item item : items) {
      if (item.state != State.SAVED) working.add(item);
      else if (item.created >= startOfToday) today.add(item);
      else if (item.created >= startOfWeek) week.add(item);
      else earlier.add(item); // Includes entries from before 1.1, which have no date.
    }
    if (!working.isEmpty()) {
      rows.add(new Row(Type.HEADER, SECTION_PROGRESS, Collections.<Item>emptyList()));
      for (Item item : working)
        rows.add(new Row(Type.PROGRESS, null, Collections.singletonList(item)));
    }
    grid(rows, SECTION_TODAY, today, columns);
    grid(rows, SECTION_WEEK, week, columns);
    grid(
        rows,
        today.isEmpty() && week.isEmpty() ? SECTION_SAVED : SECTION_EARLIER,
        earlier,
        columns);
    if (rows.isEmpty()) rows.add(new Row(Type.EMPTY, EMPTY_LIBRARY, Collections.<Item>emptyList()));
    return rows;
  }

  private static void grid(List<Row> rows, String section, List<Item> items, int columns) {
    if (items.isEmpty()) return;
    rows.add(new Row(Type.HEADER, section, Collections.<Item>emptyList()));
    for (int i = 0; i < items.size(); i += columns)
      rows.add(
          new Row(
              Type.GRID,
              null,
              Collections.unmodifiableList(
                  new ArrayList<>(items.subList(i, Math.min(items.size(), i + columns))))));
  }

  /** Changes only when something visible changes, so refreshes do not rebind needlessly. */
  public static String signature(List<Row> rows) {
    StringBuilder out = new StringBuilder();
    for (Row row : rows) out.append(row.signature()).append('\n');
    return out.toString();
  }
}
