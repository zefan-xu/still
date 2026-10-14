// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

public class Mp4RepairTest {
  @Test
  public void zeroesOnlyTheReservedCttsFlagsInsideTheSampleTable() throws Exception {
    byte[] ctts = box("ctts", new byte[] {0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 20});
    byte[] file =
        concat(
            box("ftyp", "iso5\0\0\2\0iso6mp41".getBytes(StandardCharsets.ISO_8859_1)),
            box("moov", nest(ctts, "stbl", "minf", "mdia", "trak")),
            box("mdat", "ctts\0\0\0\1 is media, not a box".getBytes(StandardCharsets.ISO_8859_1)));
    byte[] original = file.clone();
    Memory memory = new Memory(file);
    assertEquals(1, Mp4Repair.repair(memory));
    int flags = indexOf(file, "ctts") + 5;
    assertArrayEquals(new byte[3], Arrays.copyOfRange(file, flags, flags + 3));
    int changed = 0;
    for (int i = 0; i < file.length; i++) if (file[i] != original[i]) changed++;
    assertEquals("Only the low flags byte was set", 1, changed);
    assertEquals(0, Mp4Repair.repair(memory));
  }

  @Test
  public void leavesStandardAndUnparseableFilesUntouched() throws Exception {
    byte[] standard =
        concat(
            box("ftyp", new byte[8]),
            box(
                "moov",
                nest(box("ctts", new byte[] {1, 0, 0, 0, 0, 0, 0, 0}), "stbl", "minf", "mdia", "trak")));
    byte[] copy = standard.clone();
    assertEquals(0, Mp4Repair.repair(new Memory(standard)));
    assertArrayEquals(copy, standard);

    byte[] truncated = Arrays.copyOf(copy, copy.length - 5);
    byte[] before = truncated.clone();
    assertEquals(0, Mp4Repair.repair(new Memory(truncated)));
    assertArrayEquals(before, truncated);

    byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 16, 'J', 'F', 'I', 'F'};
    assertEquals(0, Mp4Repair.repair(new Memory(jpeg.clone())));
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
      if (position + buffer.length > bytes.length) throw new IOException("Past the end");
      System.arraycopy(bytes, (int) position, buffer, 0, buffer.length);
    }

    @Override
    public void write(long position, byte[] data) {
      System.arraycopy(data, 0, bytes, (int) position, data.length);
    }
  }

  private static byte[] nest(byte[] inner, String... parents) {
    for (String parent : parents) inner = box(parent, inner);
    return inner;
  }

  private static byte[] box(String type, byte[] body) {
    int size = 8 + body.length;
    return concat(
        new byte[] {(byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size},
        type.getBytes(StandardCharsets.ISO_8859_1),
        body);
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) out.write(part, 0, part.length);
    return out.toByteArray();
  }

  private static int indexOf(byte[] data, String text) {
    byte[] needle = text.getBytes(StandardCharsets.ISO_8859_1);
    outer:
    for (int i = 0; i + needle.length <= data.length; i++) {
      for (int j = 0; j < needle.length; j++) if (data[i + j] != needle[j]) continue outer;
      return i;
    }
    return -1;
  }
}
