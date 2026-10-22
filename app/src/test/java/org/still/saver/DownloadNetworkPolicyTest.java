// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DownloadNetworkPolicyTest {
  @Test
  public void wifiOnlyAllowsOnlyUnmeteredWifi() {
    assertTrue(DownloadNetworkPolicy.allows(true, true, true, false, false));
    assertFalse(DownloadNetworkPolicy.allows(true, true, false, false, false));
    assertFalse(DownloadNetworkPolicy.allows(true, true, true, true, false));
    assertFalse(DownloadNetworkPolicy.allows(true, true, true, false, true));
  }

  @Test
  public void anyConnectedNonRoamingNetworkWorksOtherwise() {
    assertTrue(DownloadNetworkPolicy.allows(false, true, false, true, false));
    assertFalse(DownloadNetworkPolicy.allows(false, false, true, false, false));
    assertFalse(DownloadNetworkPolicy.allows(false, true, true, false, true));
  }
}
