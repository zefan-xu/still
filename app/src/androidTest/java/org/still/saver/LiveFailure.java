// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sorts a live probe failure into {@code blocked}, when the platform refused this network (rate
 * limits, bot checks, login walls), or {@code broken}, for everything else. scripts/test.py opens
 * the live-check issue only for broken platforms.
 */
final class LiveFailure {
  static final String BLOCKED = "blocked";
  static final String BROKEN = "broken";

  /** Lower-case fragments of the resolvers' refusal messages and of yt-dlp's raw errors. */
  private static final String[] REFUSALS = {
    "rate limit",
    "rate-limit",
    "too many requests",
    "http error 429",
    "not a bot",
    "limiting anonymous requests",
    "asking this network to log in",
  };

  private LiveFailure() {}

  static String category(Throwable failure) {
    Deque<Throwable> pending = new ArrayDeque<>();
    Map<Throwable, Boolean> seen = new IdentityHashMap<>();
    pending.add(failure);
    while (!pending.isEmpty()) {
      Throwable current = pending.remove();
      if (seen.put(current, true) != null) continue;
      String message = String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT);
      for (String refusal : REFUSALS) if (message.contains(refusal)) return BLOCKED;
      if (current.getCause() != null) pending.add(current.getCause());
      for (Throwable suppressed : current.getSuppressed()) pending.add(suppressed);
    }
    return BROKEN;
  }
}
