// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.still.saver.core.ErrorCode;
import org.still.saver.core.UserError;

/**
 * Sorts a live probe failure into {@code blocked}, when the platform refused this network (rate
 * limits, bot checks, login walls), or {@code broken}, for everything else. scripts/test.py opens
 * the live-check issue only for broken platforms.
 */
final class LiveFailure {
  static final String BLOCKED = "blocked";
  static final String BROKEN = "broken";

  /**
   * Core's codes for a refusal aimed at this network. Login-required, private and other status
   * codes are about the post, so they stay broken.
   */
  static final Set<ErrorCode> REFUSAL_CODES =
      EnumSet.of(
          ErrorCode.RATE_LIMITED,
          ErrorCode.SERVICE_RATE_LIMITED,
          ErrorCode.EXTRACTOR_RATE_LIMITED,
          ErrorCode.YOUTUBE_BOT_CHECK,
          ErrorCode.INSTAGRAM_LIMITED,
          ErrorCode.INSTAGRAM_LOGIN_WALL);

  /** Lower-case fragments of yt-dlp's raw errors, which core keeps as causes. */
  private static final String[] REFUSALS = {
    "rate limit", "rate-limit", "too many requests", "http error 429", "not a bot",
  };

  private LiveFailure() {}

  static String category(Throwable failure) {
    Deque<Throwable> pending = new ArrayDeque<>();
    Map<Throwable, Boolean> seen = new IdentityHashMap<>();
    pending.add(failure);
    while (!pending.isEmpty()) {
      Throwable current = pending.remove();
      if (seen.put(current, true) != null) continue;
      if (current instanceof UserError && REFUSAL_CODES.contains(((UserError) current).code))
        return BLOCKED;
      String message = String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT);
      for (String refusal : REFUSALS) if (message.contains(refusal)) return BLOCKED;
      if (current.getCause() != null) pending.add(current.getCause());
      for (Throwable suppressed : current.getSuppressed()) pending.add(suppressed);
    }
    return BROKEN;
  }
}
