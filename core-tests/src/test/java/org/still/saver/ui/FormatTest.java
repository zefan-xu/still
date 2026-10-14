// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.ui;

import static org.junit.Assert.*;

import org.junit.Test;

public final class FormatTest {
  @Test
  public void durationsRoundToSecondsAndAddHoursOnlyWhenNeeded() {
    assertEquals("", Format.duration(0));
    assertEquals("0:01", Format.duration(500));
    assertEquals("0:42", Format.duration(42_200));
    assertEquals("10:05", Format.duration(605_000));
    assertEquals("1:00:00", Format.duration(3_600_000));
  }

  @Test
  public void sizesUseTheSmallestReadableUnit() {
    assertEquals("512 B", Format.size(512));
    assertEquals("12 KB", Format.size(12 * 1024));
    assertEquals("1.5 MB", Format.size(1572864));
    assertEquals("2.00 GB", Format.size(2L * 1024 * 1024 * 1024));
    assertEquals("", Format.size(-1));
  }

  @Test
  public void sizeUnitsChangeExactlyAtEachBinaryBoundary() {
    assertEquals("0 B", Format.size(0));
    assertEquals("1023 B", Format.size(1023));
    assertEquals("1 KB", Format.size(1024));
    assertTrue(Format.size(1024 * 1024 - 1).endsWith(" KB"));
    assertEquals("1.0 MB", Format.size(1024 * 1024));
    assertTrue(Format.size(1024L * 1024 * 1024 - 1).endsWith(" MB"));
    assertEquals("1.00 GB", Format.size(1024L * 1024 * 1024));
  }

  @Test
  public void resolutionNeedsBothDimensionsAndUsesTheShortSideForVideo() {
    assertEquals("1p", Format.resolution(1, 1, true));
    assertEquals("", Format.resolution(1, 0, false));
    assertEquals("", Format.resolution(-4, 4, false));
    assertEquals("1 × 1", Format.resolution(1, 1, false));
  }

  @Test
  public void unknownKindsAreCapitalizedAndEmptyKindsAreMedia() {
    assertEquals("Media", Format.kind(""));
    assertEquals("Audio", Format.kind("audio"));
    assertEquals("GIF", Format.kind("ANIMATED_GIF"));
    assertEquals("Photo", Format.kind("Image"));
  }

  @Test
  public void resolutionAndKindLabels() {
    assertEquals("1080p", Format.resolution(1920, 1080, true));
    assertEquals("720p", Format.resolution(720, 1280, true));
    assertEquals("4096 × 2304", Format.resolution(4096, 2304, false));
    assertEquals("", Format.resolution(0, 720, true));
    assertEquals("Video", Format.kind("video"));
    assertEquals("GIF", Format.kind("gif"));
    assertEquals("Photo", Format.kind("photo"));
    assertEquals("1 item", Format.count(1, "item"));
    assertEquals("3 items", Format.count(3, "item"));
  }
}
