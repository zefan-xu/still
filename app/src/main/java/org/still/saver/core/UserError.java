// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.io.IOException;
import java.util.Arrays;

/** A failure the app explains to the user; the message names the code for logs only. */
public class UserError extends IOException {
  public final ErrorCode code;
  private final Object[] args;

  public UserError(ErrorCode code, Object... args) {
    this(null, code, args);
  }

  public UserError(Throwable cause, ErrorCode code, Object... args) {
    super(args.length == 0 ? code.name() : code.name() + " " + Arrays.toString(args), cause);
    if (args.length != code.args)
      throw new IllegalArgumentException(code + " takes " + code.args + " arguments");
    this.code = code;
    this.args = args.clone();
  }

  public Object[] args() {
    return args.clone();
  }
}
