// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

/** The connection rules shared by foreground download paths. */
final class DownloadNetworkPolicy {
  private DownloadNetworkPolicy() {}

  static boolean allows(
      boolean wifiOnly, boolean internet, boolean wifi, boolean metered, boolean roaming) {
    if (!internet || roaming) return false;
    return !wifiOnly || (wifi && !metered);
  }
}
