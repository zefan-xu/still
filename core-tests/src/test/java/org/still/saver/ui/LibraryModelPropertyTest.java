// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.ui;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/** The library screen is built from this model; no file may vanish, repeat or jump groups. */
public class LibraryModelPropertyTest {
  private static final long NOW = 1_791_900_000_000L; // October 2026
  private static final long DAY = 86_400_000L;

  @Property(tries = 1500)
  void everyItemAppearsExactlyOnceInOrderWithinItsSection(
      @ForAll("libraries") List<LibraryModel.Item> items,
      @ForAll @IntRange(min = 1, max = 6) int columns,
      @ForAll("zones") TimeZone zone) {
    List<LibraryModel.Row> rows = LibraryModel.build(items, "", NOW, zone, Locale.ROOT, columns);
    List<LibraryModel.Item> shown = new ArrayList<>();
    for (LibraryModel.Row row : rows) {
      if (row.type == LibraryModel.Type.GRID) assertTrue(row.items.size() >= 1 && row.items.size() <= columns);
      if (row.type == LibraryModel.Type.PROGRESS) assertEquals(1, row.items.size());
      if (row.type == LibraryModel.Type.HEADER || row.type == LibraryModel.Type.EMPTY) assertTrue(row.items.isEmpty());
      for (LibraryModel.Item item : row.items) {
        assertEquals(row.type == LibraryModel.Type.PROGRESS, item.state != LibraryModel.State.SAVED);
        shown.add(item);
      }
    }
    assertEquals(items.size(), shown.size());
    assertEquals(new HashSet<>(items), new HashSet<>(shown));
    // Within each state the original (newest-first) order is kept.
    assertEquals(filter(items, false), filter(shown, false));
    assertEquals(items.isEmpty(), rows.get(0).type == LibraryModel.Type.EMPTY);
  }

  @Property(tries = 1500)
  void sectionsAppearInTimeOrderAndHeadersAreNeverEmpty(
      @ForAll("libraries") List<LibraryModel.Item> items, @ForAll @IntRange(min = 1, max = 6) int columns) {
    List<LibraryModel.Row> rows = LibraryModel.build(items, "", NOW, TimeZone.getTimeZone("UTC"), Locale.ROOT, columns);
    List<String> headers = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      if (rows.get(i).type != LibraryModel.Type.HEADER) continue;
      headers.add(rows.get(i).text);
      assertTrue("Header without content", i + 1 < rows.size() && rows.get(i + 1).type != LibraryModel.Type.HEADER);
    }
    List<String> order = java.util.Arrays.asList("In progress", "Today", "This week", "Earlier", "Saved");
    List<Integer> positions = new ArrayList<>();
    for (String header : headers) positions.add(order.indexOf(header));
    List<Integer> sorted = new ArrayList<>(positions);
    java.util.Collections.sort(sorted);
    assertEquals(sorted, positions);
    assertFalse(headers.contains("Earlier") && headers.contains("Saved"));
  }

  @Property(tries = 1500)
  void rowIdsAreUniqueAndStableAcrossRebuilds(
      @ForAll("libraries") List<LibraryModel.Item> items, @ForAll @IntRange(min = 1, max = 6) int columns) {
    List<LibraryModel.Row> first = LibraryModel.build(items, "", NOW, TimeZone.getTimeZone("UTC"), Locale.ROOT, columns);
    List<LibraryModel.Row> second =
        LibraryModel.build(new ArrayList<>(items), "", NOW, TimeZone.getTimeZone("UTC"), Locale.ROOT, columns);
    Set<Long> ids = new HashSet<>();
    for (int i = 0; i < first.size(); i++) {
      assertTrue("Duplicate row id", ids.add(first.get(i).id()));
      assertEquals(first.get(i).id(), second.get(i).id());
    }
    assertEquals(LibraryModel.signature(first), LibraryModel.signature(second));
  }

  @Property(tries = 1500)
  void searchShowsExactlyTheMatchingItems(
      @ForAll("libraries") List<LibraryModel.Item> items, @ForAll("queries") String query) {
    List<LibraryModel.Row> rows = LibraryModel.build(items, query, NOW, TimeZone.getTimeZone("UTC"), Locale.ROOT, 3);
    String needle = query.trim().toLowerCase(Locale.ROOT);
    List<LibraryModel.Item> expected = new ArrayList<>();
    for (LibraryModel.Item item : items)
      if (needle.isEmpty() || (item.title + " " + item.filename).toLowerCase(Locale.ROOT).contains(needle)) expected.add(item);
    if (needle.isEmpty()) return; // An empty query shows the grouped library, covered above.
    List<LibraryModel.Item> shown = new ArrayList<>();
    for (LibraryModel.Row row : rows) {
      if (row.type == LibraryModel.Type.LIST) shown.add(row.item());
      else assertEquals(LibraryModel.Type.EMPTY, row.type);
    }
    assertEquals(expected, shown);
  }

  private static List<LibraryModel.Item> filter(List<LibraryModel.Item> items, boolean saved) {
    List<LibraryModel.Item> out = new ArrayList<>();
    for (LibraryModel.Item item : items) if ((item.state == LibraryModel.State.SAVED) == saved) out.add(item);
    return out;
  }

  @Provide
  Arbitrary<List<LibraryModel.Item>> libraries() {
    Arbitrary<Long> created =
        Arbitraries.oneOf(
            Arbitraries.just(0L),
            Arbitraries.longs().between(NOW - 30 * DAY, NOW),
            Arbitraries.longs().between(NOW - 8 * DAY, NOW - 5 * DAY),
            Arbitraries.longs().between(NOW - 2 * DAY, NOW));
    Arbitrary<LibraryModel.State> state =
        Arbitraries.frequencyOf(
            net.jqwik.api.Tuple.of(6, Arbitraries.just(LibraryModel.State.SAVED)),
            net.jqwik.api.Tuple.of(1, Arbitraries.just(LibraryModel.State.ACTIVE)),
            net.jqwik.api.Tuple.of(1, Arbitraries.just(LibraryModel.State.FAILED)));
    Arbitrary<String> word = Arbitraries.of("Aurora", "ocean", "Cat", "clip", "Earthrise", "", "Saturn rings");
    return Combinators.combine(word, word, created, state, Arbitraries.integers().between(-1, 100))
        .as((title, file, when, st, progress) -> new Object[] {title, file, when, st, progress})
        .list()
        .ofMaxSize(40)
        .map(
            raw -> {
              List<LibraryModel.Item> items = new ArrayList<>();
              for (int i = 0; i < raw.size(); i++) {
                Object[] r = raw.get(i);
                items.add(
                    new LibraryModel.Item(
                        "key-" + i,
                        (String) r[0],
                        r[1] + "-" + i + ".mp4",
                        "video/mp4",
                        (Long) r[2],
                        (LibraryModel.State) r[3],
                        (Integer) r[4],
                        "status"));
              }
              return items;
            });
  }

  @Provide
  Arbitrary<TimeZone> zones() {
    return Arbitraries.of("UTC", "Asia/Tokyo", "America/Los_Angeles", "Pacific/Kiritimati", "Pacific/Pago_Pago", "Asia/Kathmandu")
        .map(TimeZone::getTimeZone);
  }

  @Provide
  Arbitrary<String> queries() {
    return Arbitraries.oneOf(
        Arbitraries.of("", "  ", "aurora", "AURORA", " cat ", "mp4", "-3", "zzz", "saturn r"),
        Arbitraries.strings().alpha().ofMaxLength(4));
  }
}
