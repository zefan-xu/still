// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.gif;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import org.junit.Test;
import org.w3c.dom.Node;

public final class AnimatedGifEncoderTest {
  @Test
  public void singlePixelIsACompleteReadableGif() throws Exception {
    byte[] gif = encode(1, 1, new int[][] {{0xffea7155}}, new int[] {13});
    BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(gif));
    assertNotNull(decoded);
    assertEquals(1, decoded.getWidth());
    assertEquals(1, decoded.getHeight());
    assertEquals(quantized(0xffea7155), decoded.getRGB(0, 0));
    assertEquals(0x3b, gif[gif.length - 1] & 255);
  }

  @Test
  public void noisyFrameSurvivesEveryLzwCodeWidthAndAFullDictionary() throws Exception {
    int width = 360;
    int height = 240;
    Random random = new Random(417);
    int[] pixels = new int[width * height];
    for (int index = 0; index < pixels.length; index++) pixels[index] = random.nextInt();
    BufferedImage decoded =
        ImageIO.read(
            new ByteArrayInputStream(encode(width, height, new int[][] {pixels}, new int[] {12})));
    assertPixels(pixels, decoded);
  }

  @Test
  public void longRunsAndPaletteBoundariesDecodeCorrectly() throws Exception {
    int width = 256;
    int height = 130;
    int[] pixels = new int[width * height];
    Arrays.fill(pixels, 0xff2288dd);
    for (int index = 0; index < width; index++) {
      pixels[index] =
          0xff000000
              | (((index >>> 5) & 7) * 32 << 16)
              | (((index >>> 2) & 7) * 32 << 8)
              | ((index & 3) * 64);
    }
    BufferedImage decoded =
        ImageIO.read(
            new ByteArrayInputStream(encode(width, height, new int[][] {pixels}, new int[] {1})));
    assertPixels(pixels, decoded);
  }

  @Test
  public void everyAnimatedFrameAndItsTimingCanBeDecodedIndependently() throws Exception {
    int width = 32;
    int height = 24;
    int[][] frames = new int[3][width * height];
    for (int index = 0; index < frames[0].length; index++) {
      frames[0][index] = 0xffff0000;
      frames[1][index] = (index % 2 == 0) ? 0xff00ff00 : 0xff0000ff;
      frames[2][index] = 0xff123456 | (index * 7381);
    }
    int[] delays = {12, 13, 7};
    byte[] gif = encode(width, height, frames, delays);
    ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
    try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(gif))) {
      reader.setInput(input);
      assertEquals(3, reader.getNumImages(true));
      for (int index = 0; index < frames.length; index++) {
        assertPixels(frames[index], reader.read(index));
        Node tree = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0");
        Node control = find(tree, "GraphicControlExtension");
        assertNotNull(control);
        assertEquals(
            Integer.toString(delays[index]),
            control.getAttributes().getNamedItem("delayTime").getNodeValue());
      }
      Node tree = reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0");
      IIOMetadataNode loop = (IIOMetadataNode) find(tree, "ApplicationExtension");
      assertNotNull(loop);
      assertEquals("NETSCAPE", loop.getAttribute("applicationID"));
      assertTrue(Arrays.equals(new byte[] {1, 0, 0}, (byte[]) loop.getUserObject()));
    } finally {
      reader.dispose();
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsWrongFrameSize() throws Exception {
    new AnimatedGifEncoder(new ByteArrayOutputStream(), 2, 2, 0).addFrame(new int[3], 12);
  }

  @Test(expected = IllegalStateException.class)
  public void rejectsEmptyGif() throws Exception {
    new AnimatedGifEncoder(new ByteArrayOutputStream(), 1, 1, 0).finish();
  }

  private static byte[] encode(int width, int height, int[][] frames, int[] delays)
      throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    AnimatedGifEncoder encoder = new AnimatedGifEncoder(output, width, height, 0);
    for (int index = 0; index < frames.length; index++)
      encoder.addFrame(frames[index], delays[index]);
    encoder.finish();
    encoder.finish(); // Finishing twice must not add a second trailer.
    return output.toByteArray();
  }

  private static void assertPixels(int[] expected, BufferedImage decoded) {
    assertNotNull(decoded);
    assertEquals(expected.length, decoded.getWidth() * decoded.getHeight());
    for (int index = 0; index < expected.length; index++) {
      assertEquals(
          "Pixel " + index,
          quantized(expected[index]),
          decoded.getRGB(index % decoded.getWidth(), index / decoded.getWidth()));
    }
  }

  private static int quantized(int color) {
    int red = ((color >>> 21) & 7) * 255 / 7;
    int green = ((color >>> 13) & 7) * 255 / 7;
    int blue = ((color >>> 6) & 3) * 255 / 3;
    return 0xff000000 | (red << 16) | (green << 8) | blue;
  }

  private static Node find(Node parent, String name) {
    if (name.equals(parent.getNodeName())) return parent;
    for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
      Node result = find(child, name);
      if (result != null) return result;
    }
    return null;
  }
}
