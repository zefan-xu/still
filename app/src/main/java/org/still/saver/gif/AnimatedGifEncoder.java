// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.gif;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * A streaming GIF89a encoder. Each frame uses the same opaque 3-3-2 RGB palette. The caller owns
 * the stream; finishing this encoder does not close it.
 */
public final class AnimatedGifEncoder {
  private final OutputStream output;
  private final int width;
  private final int height;
  private boolean finished;
  private int frames;

  /**
   * @param repeat 0 loops forever; a positive value is the loop count.
   */
  public AnimatedGifEncoder(OutputStream output, int width, int height, int repeat)
      throws IOException {
    if (output == null
        || width < 1
        || height < 1
        || width > 65535
        || height > 65535
        || (long) width * height > Integer.MAX_VALUE
        || repeat < 0
        || repeat > 65535) {
      throw new IllegalArgumentException("Invalid GIF dimensions or loop count");
    }
    this.output = output;
    this.width = width;
    this.height = height;
    output.write("GIF89a".getBytes(StandardCharsets.US_ASCII));
    writeShort(width);
    writeShort(height);
    output.write(0xf7); // Global palette, eight-bit color resolution, 256 entries.
    output.write(0); // Background palette index.
    output.write(0); // Unspecified pixel aspect ratio.
    for (int index = 0; index < 256; index++) {
      output.write(((index >>> 5) & 7) * 255 / 7);
      output.write(((index >>> 2) & 7) * 255 / 7);
      output.write((index & 3) * 255 / 3);
    }
    output.write(new byte[] {0x21, (byte) 0xff, 11});
    output.write("NETSCAPE2.0".getBytes(StandardCharsets.US_ASCII));
    output.write(3);
    output.write(1);
    writeShort(repeat);
    output.write(0);
  }

  /** Adds a full frame. Alpha is ignored; delay is measured in 1/100 second. */
  public void addFrame(int[] argb, int delayCentiseconds) throws IOException {
    if (finished) throw new IllegalStateException("GIF is already finished");
    if (argb == null
        || argb.length != width * height
        || delayCentiseconds < 1
        || delayCentiseconds > 65535) {
      throw new IllegalArgumentException("Invalid frame pixels or delay");
    }
    output.write(new byte[] {0x21, (byte) 0xf9, 4, 4}); // Keep the full frame.
    writeShort(delayCentiseconds);
    output.write(0); // No transparent palette entry.
    output.write(0);
    output.write(0x2c);
    writeShort(0);
    writeShort(0);
    writeShort(width);
    writeShort(height);
    output.write(0); // No local palette or interlacing.
    output.write(8); // Minimum LZW code size for the 256-color palette.
    writeImageData(argb);
    frames++;
  }

  public void finish() throws IOException {
    if (finished) return;
    if (frames == 0) throw new IllegalStateException("GIF needs at least one frame");
    output.write(0x3b);
    output.flush();
    finished = true;
  }

  private void writeImageData(int[] pixels) throws IOException {
    final int clear = 256;
    final int end = 257;
    int nextCode = 258;
    int codeSize = 9;
    Map<Integer, Integer> dictionary = new HashMap<>(4096);
    CodeWriter writer = new CodeWriter(output);
    writer.write(clear, codeSize);
    int prefix = paletteIndex(pixels[0]);
    for (int index = 1; index < pixels.length; index++) {
      int suffix = paletteIndex(pixels[index]);
      int key = (prefix << 8) | suffix;
      Integer existing = dictionary.get(key);
      if (existing != null) {
        prefix = existing;
        continue;
      }
      writer.write(prefix, codeSize);
      // The decoder creates a dictionary entry only after reading the next code.
      // Changing width after this write keeps the encoder one entry ahead.
      if (nextCode == (1 << codeSize) && codeSize < 12) codeSize++;
      if (nextCode < 4096) dictionary.put(key, nextCode++);
      // A full dictionary remains usable; GIF permits freezing it until clear.
      prefix = suffix;
    }
    writer.write(prefix, codeSize);
    if (nextCode == (1 << codeSize) && codeSize < 12) codeSize++;
    writer.write(end, codeSize);
    writer.finish();
  }

  private static int paletteIndex(int argb) {
    return ((argb >>> 16) & 0xe0) | ((argb >>> 11) & 0x1c) | ((argb >>> 6) & 3);
  }

  private void writeShort(int value) throws IOException {
    output.write(value & 255);
    output.write((value >>> 8) & 255);
  }

  /** LSB-first codes packed into GIF data sub-blocks of at most 255 bytes. */
  private static final class CodeWriter {
    private final OutputStream output;
    private final byte[] block = new byte[255];
    private int blockSize;
    private int bits;
    private int bitCount;

    CodeWriter(OutputStream output) {
      this.output = output;
    }

    void write(int code, int width) throws IOException {
      bits |= code << bitCount;
      bitCount += width;
      while (bitCount >= 8) {
        append(bits & 255);
        bits >>>= 8;
        bitCount -= 8;
      }
    }

    void finish() throws IOException {
      if (bitCount > 0) append(bits & 255);
      flushBlock();
      output.write(0);
    }

    private void append(int value) throws IOException {
      block[blockSize++] = (byte) value;
      if (blockSize == block.length) flushBlock();
    }

    private void flushBlock() throws IOException {
      if (blockSize == 0) return;
      output.write(blockSize);
      output.write(block, 0, blockSize);
      blockSize = 0;
    }
  }
}
