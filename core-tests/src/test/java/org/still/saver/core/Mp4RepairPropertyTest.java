// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * Repair edits a downloaded file in place, so it must change exactly the reserved ctts flags under
 * moov/trak/mdia/minf/stbl and nothing else, for any layout, including damaged files.
 */
public class Mp4RepairPropertyTest {
  private static final String[] PATH = {"moov", "trak", "mdia", "minf", "stbl"};
  private static final String[] FILLER = {"free", "skip", "udta", "mvhd", "tkhd", "hdlr", "stsd", "stsz", "meta"};

  @Property(tries = 1500)
  void repairsExactlyTheSampleTableFlagsAndIsIdempotent(@ForAll("mp4") Mp4 mp4) throws Exception {
    byte[] file = mp4.bytes.clone();
    assertEquals(mp4.describe(), mp4.dirty.size(), Mp4Repair.repair(new Memory(file)));
    byte[] expected = mp4.bytes.clone();
    for (int flags : mp4.dirty) for (int i = 0; i < 3; i++) expected[flags + i] = 0;
    assertArrayEquals(mp4.describe(), expected, file);
    assertEquals(0, Mp4Repair.repair(new Memory(file)));
  }

  @Property(tries = 1500)
  void truncatedFilesOnlyEverLoseFlagBits(
      @ForAll("mp4") Mp4 mp4, @ForAll @IntRange(min = 0, max = 100) int keepPercent) throws Exception {
    byte[] file = java.util.Arrays.copyOf(mp4.bytes, mp4.bytes.length * keepPercent / 100);
    assertOnlyZeroedFlags(file);
  }

  @Property(tries = 3000)
  void arbitraryBytesNeverThrowOrGainNonZeroBytes(@ForAll @Size(max = 700) byte[] bytes) throws Exception {
    assertOnlyZeroedFlags(bytes);
  }

  @Property(tries = 1500)
  void boxShapedNoiseNeverThrowsOrGainsNonZeroBytes(@ForAll("noise") byte[] bytes) throws Exception {
    assertOnlyZeroedFlags(bytes);
  }

  private static void assertOnlyZeroedFlags(byte[] file) throws IOException {
    byte[] original = file.clone();
    int repaired = Mp4Repair.repair(new Memory(file));
    int changed = 0;
    for (int i = 0; i < file.length; i++) {
      if (file[i] == original[i]) continue;
      assertEquals("Repair may only clear bytes", 0, file[i]);
      changed++;
    }
    assertTrue(changed <= 3 * repaired);
    assertEquals(0, Mp4Repair.repair(new Memory(file)));
  }

  @Provide
  Arbitrary<Mp4> mp4() {
    return Arbitraries.randomValue(Mp4::generate);
  }

  /** Box headers with plausible types and sizes, sprinkled over random bytes. */
  @Provide
  Arbitrary<byte[]> noise() {
    return Arbitraries.randomValue(
        random -> {
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          int boxes = random.nextInt(12);
          String[] types = {"moov", "trak", "mdia", "minf", "stbl", "ctts", "mdat", "free"};
          for (int i = 0; i < boxes; i++) {
            long size;
            switch (random.nextInt(5)) {
              case 0: size = 0; break;
              case 1: size = 1; break;
              case 2: size = random.nextInt(16); break;
              case 3: size = 0xffffffffL; break;
              default: size = 8 + random.nextInt(64);
            }
            write32(out, size);
            out.write(types[random.nextInt(types.length)].getBytes(StandardCharsets.ISO_8859_1), 0, 4);
            if (size == 1) {
              long large = random.nextBoolean() ? random.nextLong() : 16 + random.nextInt(64);
              write32(out, large >>> 32);
              write32(out, large);
            }
            byte[] junk = new byte[random.nextInt(24)];
            random.nextBytes(junk);
            out.write(junk, 0, junk.length);
          }
          return out.toByteArray();
        });
  }

  /** A generated file plus the absolute offsets of every flag triplet repair must clear. */
  static final class Mp4 {
    final byte[] bytes;
    final List<Integer> dirty;
    final String layout;

    private Mp4(byte[] bytes, List<Integer> dirty, String layout) {
      this.bytes = bytes;
      this.dirty = dirty;
      this.layout = layout;
    }

    String describe() {
      return layout + " (" + bytes.length + " bytes)";
    }

    static Mp4 generate(Random random) {
      List<Built> top = new ArrayList<>();
      for (int i = random.nextInt(3); i > 0; i--) top.add(filler(random, 0));
      Built moov = level(random, 0);
      Built mdat = leaf("mdat", decoyMedia(random), random);
      boolean moovFirst = random.nextBoolean();
      top.add(moovFirst ? moov : mdat);
      top.add(moovFirst ? mdat : moov);
      // A final box may declare size 0, meaning "to the end of the file".
      if (random.nextInt(4) == 0) top.set(top.size() - 1, top.get(top.size() - 1).extendsToEnd());
      return Built.concat(top).toMp4();
    }

    /** Builds PATH[depth] with random siblings and, at the sample table, ctts boxes. */
    private static Built level(Random random, int depth) {
      List<Built> children = new ArrayList<>();
      int count = depth == PATH.length - 1 ? 1 + random.nextInt(4) : 1 + random.nextInt(3);
      for (int i = 0; i < count; i++) {
        int pick = random.nextInt(10);
        if (depth == PATH.length - 1)
          children.add(pick < 5 ? ctts(random, true) : pick < 6 ? shortCtts(random) : filler(random, depth + 1));
        else if (pick < 6) children.add(level(random, depth + 1));
        else if (pick < 8) children.add(filler(random, depth + 1));
        else children.add(ctts(random, false)); // Not in a sample table: must be left alone.
      }
      return Built.concat(children).wrap(PATH[depth], random.nextInt(4) == 0);
    }

    private static Built ctts(Random random, boolean repairable) {
      byte[] body = new byte[4 + 4 * random.nextInt(12)];
      random.nextBytes(body);
      if (random.nextBoolean()) body[1] = body[2] = body[3] = 0;
      boolean dirty = body[1] != 0 || body[2] != 0 || body[3] != 0;
      boolean large = random.nextInt(4) == 0;
      List<Integer> offsets = new ArrayList<>();
      if (repairable && dirty) offsets.add((large ? 16 : 8) + 1);
      return new Built(header("ctts", 8 + body.length, large, body), offsets, "ctts" + (dirty ? "*" : ""));
    }

    /** Too short to hold version and flags; the bytes after it belong to the next box. */
    private static Built shortCtts(Random random) {
      byte[] body = new byte[random.nextInt(4)];
      random.nextBytes(body);
      return new Built(header("ctts", 8 + body.length, false, body), new ArrayList<>(), "ctts(short)");
    }

    private static Built filler(Random random, int depth) {
      byte[] body = new byte[random.nextInt(depth == 0 ? 300 : 40)];
      random.nextBytes(body);
      return leaf(FILLER[random.nextInt(FILLER.length)], body, random);
    }

    private static Built leaf(String type, byte[] body, Random random) {
      boolean large = random.nextInt(5) == 0;
      return new Built(header(type, 8 + body.length, large, body), new ArrayList<>(), type);
    }

    /** Media data that happens to contain ctts-shaped bytes, which are not boxes. */
    private static byte[] decoyMedia(Random random) {
      byte[] body = new byte[16 + random.nextInt(600)];
      random.nextBytes(body);
      byte[] fake = "\0\0\0\u0010ctts\0\1\1\1".getBytes(StandardCharsets.ISO_8859_1);
      System.arraycopy(fake, 0, body, random.nextInt(body.length - fake.length), fake.length);
      return body;
    }
  }

  private static final class Built {
    final byte[] bytes;
    final List<Integer> dirty;
    final String layout;

    Built(byte[] bytes, List<Integer> dirty, String layout) {
      this.bytes = bytes;
      this.dirty = dirty;
      this.layout = layout;
    }

    static Built concat(List<Built> parts) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      List<Integer> dirty = new ArrayList<>();
      StringBuilder layout = new StringBuilder();
      for (Built part : parts) {
        for (int offset : part.dirty) dirty.add(out.size() + offset);
        out.write(part.bytes, 0, part.bytes.length);
        layout.append(layout.length() == 0 ? "" : " ").append(part.layout);
      }
      return new Built(out.toByteArray(), dirty, layout.toString());
    }

    Built wrap(String type, boolean large) {
      int headerSize = large ? 16 : 8;
      List<Integer> shifted = new ArrayList<>();
      for (int offset : dirty) shifted.add(headerSize + offset);
      return new Built(header(type, 8 + bytes.length, large, bytes), shifted, type + "[" + layout + "]");
    }

    /** Rewrites the 32-bit size to 0; valid only for the last top-level box. */
    Built extendsToEnd() {
      if (u32(bytes) == 1) return this; // Size 0 is not combined with a large size here.
      byte[] copy = bytes.clone();
      copy[0] = copy[1] = copy[2] = copy[3] = 0;
      return new Built(copy, dirty, layout + "~end");
    }

    Mp4 toMp4() {
      return new Mp4(bytes, dirty, layout);
    }
  }

  /** {@code size} counts a compact 8-byte header; a large header adds its 8-byte size field. */
  private static byte[] header(String type, long size, boolean large, byte[] body) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (large) size += 8;
    write32(out, large ? 1 : size);
    out.write(type.getBytes(StandardCharsets.ISO_8859_1), 0, 4);
    if (large) {
      write32(out, size >>> 32);
      write32(out, size);
    }
    out.write(body, 0, body.length);
    return out.toByteArray();
  }

  private static long u32(byte[] bytes) {
    return (bytes[0] & 0xffL) << 24 | (bytes[1] & 0xffL) << 16 | (bytes[2] & 0xffL) << 8 | (bytes[3] & 0xffL);
  }

  private static void write32(ByteArrayOutputStream out, long value) {
    out.write((int) (value >>> 24));
    out.write((int) (value >>> 16));
    out.write((int) (value >>> 8));
    out.write((int) value);
  }

  private static final class Memory implements Mp4Repair.File {
    final byte[] bytes;

    Memory(byte[] bytes) {
      this.bytes = bytes;
    }

    @Override
    public long size() {
      return bytes.length;
    }

    @Override
    public void read(long position, byte[] buffer) throws IOException {
      if (position < 0 || position + buffer.length > bytes.length) throw new IOException("Read past the end");
      System.arraycopy(bytes, (int) position, buffer, 0, buffer.length);
    }

    @Override
    public void write(long position, byte[] data) throws IOException {
      if (position < 0 || position + data.length > bytes.length) throw new IOException("Write past the end");
      System.arraycopy(data, 0, bytes, (int) position, data.length);
    }
  }
}
