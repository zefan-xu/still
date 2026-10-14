// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

public class UserErrorTest {
  private static final Pattern ARGUMENT = Pattern.compile("%(\\d+)\\$[sd]");

  @Test
  public void everyCodeHasAnEnglishStringTakingItsArguments() {
    for (ErrorCode code : ErrorCode.values()) {
      int expected = code == ErrorCode.LINK_UNSUPPORTED ? code.args + 1 : code.args;
      assertEquals(code.name(), expected, arguments(English.string(English.name(code))));
    }
    for (Platforms.Kind kind : Platforms.Kind.values()) {
      String name = "home_match_" + kind.name().toLowerCase(Locale.ROOT);
      assertEquals(name, 1, arguments(English.string(name)));
    }
  }

  @Test
  public void keepsTheCodeArgumentsAndCauseAndNamesThemForLogs() {
    IOException cause = new IOException("socket closed");
    UserError error = new UserError(cause, ErrorCode.STATUS, "Instagram", 500);
    assertSame(ErrorCode.STATUS, error.code);
    assertSame(cause, error.getCause());
    assertArrayEquals(new Object[] {"Instagram", 500}, error.args());
    error.args()[0] = "changed";
    assertEquals("Instagram", error.args()[0]);
    assertEquals("STATUS [Instagram, 500]", error.getMessage());
    assertEquals("Could not load this Instagram post (status 500).", English.message(error));

    UserError plain = new UserError(ErrorCode.CANCELLED);
    assertNull(plain.getCause());
    assertEquals(0, plain.args().length);
    assertEquals("CANCELLED", plain.getMessage());
    assertEquals("Media extraction was cancelled.", English.message(plain));
  }

  @Test
  public void rejectsTheWrongNumberOfArguments() {
    try {
      new UserError(ErrorCode.RATE_LIMITED);
      fail("Accepted a missing platform");
    } catch (IllegalArgumentException expected) {
      assertEquals("RATE_LIMITED takes 1 arguments", expected.getMessage());
    }
    try {
      new UserError(ErrorCode.CANCELLED, "extra");
      fail("Accepted an extra argument");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("CANCELLED"));
    }
  }

  @Test
  public void englishKeepsEscapedQuotesAndOtherMessages() {
    assertEquals(
        "Copy a post's link, paste it into the field at the top of Still and tap the arrow.",
        English.string("onboarding_paste_body"));
    assertEquals("plain", English.message(new IOException("plain")));
  }

  private static int arguments(String text) {
    Set<String> seen = new HashSet<>();
    Matcher m = ARGUMENT.matcher(text);
    while (m.find()) seen.add(m.group(1));
    return seen.size();
  }
}
