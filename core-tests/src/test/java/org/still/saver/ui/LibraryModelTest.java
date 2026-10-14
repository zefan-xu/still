// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.ui;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.junit.Test;

public final class LibraryModelTest {
  private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
  private static final long NOW = at(2026, Calendar.OCTOBER, 12, 15);

  private static long at(int year, int month, int day, int hour) {
    Calendar c = Calendar.getInstance(UTC, Locale.ROOT);
    c.clear();
    c.set(year, month, day, hour, 0);
    return c.getTimeInMillis();
  }

  private static LibraryModel.Item saved(String key, long created) {
    return new LibraryModel.Item(
        key, "Title " + key, key + ".mp4", "video/mp4", created, LibraryModel.State.SAVED, 100, "Saved");
  }

  private static LibraryModel.Item working(String key, LibraryModel.State state, int progress) {
    return new LibraryModel.Item(key, "", key + ".mp4", "video/mp4", NOW, state, progress, "Queued");
  }

  private static List<String> describe(List<LibraryModel.Row> rows) {
    List<String> out = new ArrayList<>();
    for (LibraryModel.Row row : rows) {
      StringBuilder line = new StringBuilder(row.type.name());
      if (row.text != null) line.append(' ').append(row.text);
      for (LibraryModel.Item item : row.items) line.append(' ').append(item.key);
      out.add(line.toString());
    }
    return out;
  }

  @Test
  public void groupsWorkThenSavedItemsByDayInThreeColumns() {
    List<LibraryModel.Item> items =
        Arrays.asList(
            working("w1", LibraryModel.State.ACTIVE, 40),
            saved("t1", at(2026, Calendar.OCTOBER, 12, 9)),
            saved("t2", at(2026, Calendar.OCTOBER, 12, 0)),
            working("f1", LibraryModel.State.FAILED, -1),
            saved("w2", at(2026, Calendar.OCTOBER, 11, 23)),
            saved("w3", at(2026, Calendar.OCTOBER, 6, 0)),
            saved("e1", at(2026, Calendar.OCTOBER, 5, 23)),
            saved("legacy", 0));
    List<LibraryModel.Row> rows = LibraryModel.build(items, "", NOW, UTC, Locale.ROOT, 3);
    assertEquals(
        Arrays.asList(
            "HEADER In progress",
            "PROGRESS w1",
            "PROGRESS f1",
            "HEADER Today",
            "GRID t1 t2",
            "HEADER This week",
            "GRID w2 w3",
            "HEADER Earlier",
            "GRID e1 legacy"),
        describe(rows));
  }

  @Test
  public void chunksLongGroupsAndLabelsUndatedHistoryAsSaved() {
    List<LibraryModel.Item> items = new ArrayList<>();
    for (int i = 0; i < 7; i++) items.add(saved("old" + i, 0));
    assertEquals(
        Arrays.asList(
            "HEADER Saved", "GRID old0 old1 old2", "GRID old3 old4 old5", "GRID old6"),
        describe(LibraryModel.build(items, null, NOW, UTC, Locale.ROOT, 3)));
  }

  @Test
  public void emptyLibraryAndEmptySearchAreDistinct() {
    assertEquals(
        Collections.singletonList("EMPTY " + LibraryModel.EMPTY_LIBRARY),
        describe(
            LibraryModel.build(
                Collections.<LibraryModel.Item>emptyList(), "", NOW, UTC, Locale.ROOT, 3)));
    assertEquals(
        Collections.singletonList("EMPTY " + LibraryModel.EMPTY_SEARCH),
        describe(
            LibraryModel.build(
                Collections.singletonList(saved("a", NOW)), "zzz", NOW, UTC, Locale.ROOT, 3)));
  }

  @Test
  public void searchMatchesTitleAndFilenameCaseInsensitivelyAsListRows() {
    List<LibraryModel.Item> items =
        Arrays.asList(saved("aurora", NOW), saved("ocean", NOW), working("AURORA-2", LibraryModel.State.ACTIVE, 5));
    assertEquals(
        Arrays.asList("LIST aurora", "LIST AURORA-2"),
        describe(LibraryModel.build(items, "  AuRoRa ", NOW, UTC, Locale.ROOT, 3)));
  }

  @Test
  public void todayStartsAtLocalMidnightWhateverTheCurrentTimeOfDay() {
    Calendar c = Calendar.getInstance(UTC, Locale.ROOT);
    c.clear();
    c.set(2026, Calendar.OCTOBER, 12, 23, 59, 59);
    c.set(Calendar.MILLISECOND, 999);
    long lateEvening = c.getTimeInMillis();
    List<LibraryModel.Item> items =
        Arrays.asList(
            saved("midnight", at(2026, Calendar.OCTOBER, 12, 0)),
            saved("before", at(2026, Calendar.OCTOBER, 12, 0) - 1));
    assertEquals(
        Arrays.asList("HEADER Today", "GRID midnight", "HEADER This week", "GRID before"),
        describe(LibraryModel.build(items, "", lateEvening, UTC, Locale.ROOT, 3)));
  }

  @Test
  public void groupsFollowTheDeviceTimeZone() {
    TimeZone tokyo = TimeZone.getTimeZone("Asia/Tokyo");
    // 15:00 UTC is already 00:00 the next day in Tokyo, so a 14:00 UTC item is "yesterday" there.
    List<LibraryModel.Item> items = Collections.singletonList(saved("a", at(2026, Calendar.OCTOBER, 12, 14)));
    assertEquals(Arrays.asList("HEADER Today", "GRID a"), describe(LibraryModel.build(items, "", NOW, UTC, Locale.ROOT, 3)));
    assertEquals(
        Arrays.asList("HEADER This week", "GRID a"), describe(LibraryModel.build(items, "", NOW, tokyo, Locale.ROOT, 3)));
  }

  @Test
  public void gridRowsHoldExactlyTheColumnCount() {
    List<LibraryModel.Item> items = new ArrayList<>();
    for (int i = 0; i < 4; i++) items.add(saved("i" + i, NOW));
    assertEquals(
        Arrays.asList("HEADER Today", "GRID i0 i1 i2 i3"),
        describe(LibraryModel.build(items, "", NOW, UTC, Locale.ROOT, 4)));
    assertEquals(
        Arrays.asList("HEADER Today", "GRID i0", "GRID i1", "GRID i2", "GRID i3"),
        describe(LibraryModel.build(items, "", NOW, UTC, Locale.ROOT, 1)));
  }

  @Test
  public void namesFallBackToTheFilenameAndIdsDifferBetweenRows() {
    LibraryModel.Item untitled =
        new LibraryModel.Item("k", "", "clip.mp4", "video/mp4", NOW, LibraryModel.State.SAVED, 100, "");
    assertEquals("clip.mp4", untitled.name());
    assertEquals("Title a", saved("a", NOW).name());
    List<LibraryModel.Row> rows =
        LibraryModel.build(Arrays.asList(saved("a", NOW), saved("b", 0)), "", NOW, UTC, Locale.ROOT, 3);
    assertSame(rows.get(1).items.get(0), rows.get(1).item());
    java.util.Set<Long> ids = new java.util.HashSet<>();
    for (LibraryModel.Row row : rows) assertTrue("Duplicate id for " + row.text, ids.add(row.id()));
    assertNotEquals(0, rows.get(0).id());
  }

  @Test
  public void signatureAndIdsTrackVisibleChangesOnly() {
    List<LibraryModel.Item> before =
        Arrays.asList(working("w", LibraryModel.State.ACTIVE, 10), saved("s", NOW));
    List<LibraryModel.Item> same =
        Arrays.asList(working("w", LibraryModel.State.ACTIVE, 10), saved("s", NOW));
    List<LibraryModel.Item> progressed =
        Arrays.asList(working("w", LibraryModel.State.ACTIVE, 11), saved("s", NOW));
    List<LibraryModel.Row> a = LibraryModel.build(before, "", NOW, UTC, Locale.ROOT, 3);
    List<LibraryModel.Row> b = LibraryModel.build(same, "", NOW, UTC, Locale.ROOT, 3);
    List<LibraryModel.Row> c = LibraryModel.build(progressed, "", NOW, UTC, Locale.ROOT, 3);
    assertEquals(LibraryModel.signature(a), LibraryModel.signature(b));
    assertNotEquals(LibraryModel.signature(a), LibraryModel.signature(c));
    for (int i = 0; i < a.size(); i++) assertEquals(a.get(i).id(), c.get(i).id());
  }
}
