// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Only advances our durable queue after a completion owned by this application. */
public final class DownloadReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
    for (DownloadStore.Entry e : DownloadStore.list(context))
      if (e.id == id) {
        DownloadStore.pump(context);
        break;
      }
  }
}
