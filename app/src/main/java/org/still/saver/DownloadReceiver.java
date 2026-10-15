// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Advances our queue when Android finishes a download we enqueued. The receiver is not exported;
 * {@code DOWNLOAD_COMPLETE} is a protected system broadcast, so the system can still deliver it.
 * An id that is not already in the queue returns before {@code list} or {@code pump}.
 */
public final class DownloadReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
    if (!DownloadStore.owns(context, id)) return;
    DownloadStore.pump(context);
  }
}
