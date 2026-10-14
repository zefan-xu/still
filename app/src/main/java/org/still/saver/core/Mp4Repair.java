// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.io.IOException;

/**
 * X's GIF MP4s set the reserved flags of the composition-offset box (ctts) to 1. ISO/IEC 14496-12
 * requires 0, and Android's MP4 extractor rejects the whole file, so it neither plays nor yields a
 * frame. Zeroing those three bytes is the only change made; every other byte is untouched.
 */
public final class Mp4Repair {
  /** Positional access to the saved file. */
  public interface File {
    long size() throws IOException;

    /** Reads exactly {@code buffer.length} bytes at {@code position}. */
    void read(long position, byte[] buffer) throws IOException;

    void write(long position, byte[] bytes) throws IOException;
  }

  private static final String[] PATH = {"moov", "trak", "mdia", "minf", "stbl"};

  private Mp4Repair() {}

  /** Returns the number of ctts boxes repaired; 0 for files that need nothing. */
  public static int repair(File file) throws IOException {
    return walk(file, 0, file.size(), 0);
  }

  private static int walk(File file, long start, long end, int depth) throws IOException {
    int repaired = 0;
    long position = start;
    byte[] header = new byte[8];
    while (position + 8 <= end) {
      file.read(position, header);
      long size = u32(header, 0);
      String type = new String(header, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
      int headerSize = 8;
      if (size == 1) {
        if (position + 16 > end) break;
        byte[] large = new byte[8];
        file.read(position + 8, large);
        size = u32(large, 0) << 32 | u32(large, 4);
        headerSize = 16;
      } else if (size == 0) size = end - position;
      if (size < headerSize || position + size > end) break; // Not a box layout we understand.
      long body = position + headerSize;
      if (depth < PATH.length && type.equals(PATH[depth])) {
        repaired += walk(file, body, position + size, depth + 1);
      } else if (depth == PATH.length && type.equals("ctts") && size >= headerSize + 4) {
        byte[] versionAndFlags = new byte[4];
        file.read(body, versionAndFlags);
        if (versionAndFlags[1] != 0 || versionAndFlags[2] != 0 || versionAndFlags[3] != 0) {
          file.write(body + 1, new byte[3]);
          repaired++;
        }
      }
      if (depth == 0 && type.equals("moov")) break; // Media data follows; nothing else to fix.
      position += size;
    }
    return repaired;
  }

  private static long u32(byte[] bytes, int offset) {
    return (bytes[offset] & 0xffL) << 24
        | (bytes[offset + 1] & 0xffL) << 16
        | (bytes[offset + 2] & 0xffL) << 8
        | (bytes[offset + 3] & 0xffL);
  }
}
