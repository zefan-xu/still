// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.util.Locale;
import org.junit.Test;
import org.still.saver.core.ErrorCode;

/** The app's error-code to string-resource map, without a device or a Context. */
public class CoreTextTest {
  @Test
  public void everyErrorCodeMapsToItsStringResource() throws Exception {
    for (ErrorCode code : ErrorCode.values()) {
      String name = "error_" + code.name().toLowerCase(Locale.ROOT);
      Field field = R.string.class.getField(name);
      assertEquals(name, field.getInt(null), CoreText.message(code));
    }
  }
}
