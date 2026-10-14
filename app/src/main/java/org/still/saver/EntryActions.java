// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import java.util.ArrayList;
import java.util.List;
import org.still.saver.gif.GifService;
import org.still.saver.ui.Sheet;
import org.still.saver.ui.Thumbnails;

/** What can be done with one library entry, shared by the home screen and the viewer. */
final class EntryActions {
  interface Listener {
    void changed();

    void message(String text);
  }

  static final int STORAGE_REQUEST = 2;

  private final Activity activity;
  private final Sheet sheet;
  private final Listener listener;
  private Runnable afterPermission;

  EntryActions(Activity activity, Sheet sheet, Listener listener) {
    this.activity = activity;
    this.sheet = sheet;
    this.listener = listener;
  }

  static boolean saved(DownloadStore.Entry e) {
    return e.status == DownloadManager.STATUS_SUCCESSFUL;
  }

  static boolean video(DownloadStore.Entry e) {
    return saved(e) && e.mime.startsWith("video/");
  }

  static String name(DownloadStore.Entry e) {
    return e.title.isEmpty() ? e.filename : e.title;
  }

  void open(DownloadStore.Entry e) {
    if (e.uri == null) {
      listener.message("This file is no longer available. Find the post again.");
      return;
    }
    activity.startActivity(
        new Intent(activity, PlayerActivity.class)
            .setDataAndType(e.uri, e.mime)
            .putExtra("title", name(e))
            .putExtra("entry", e.key)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
  }

  void share(DownloadStore.Entry e) {
    if (e.uri == null) {
      listener.message("The saved file is unavailable.");
      return;
    }
    Intent send =
        new Intent(Intent.ACTION_SEND)
            .setType(e.mime)
            .putExtra(Intent.EXTRA_STREAM, e.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    send.setClipData(ClipData.newRawUri("Saved media", e.uri));
    try {
      activity.startActivity(Intent.createChooser(send, "Share saved file"));
    } catch (RuntimeException ex) {
      listener.message("No app is available to share this file.");
    }
  }

  void openSource(DownloadStore.Entry e) {
    if (e.source.isEmpty()) {
      listener.message("This file has no post link.");
      return;
    }
    try {
      activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(e.source)));
    } catch (RuntimeException ex) {
      listener.message("No app can open this link.");
    }
  }

  void menu(DownloadStore.Entry e) {
    List<Sheet.Action> actions = new ArrayList<>();
    if (saved(e)) {
      actions.add(new Sheet.Action("Share", R.drawable.ic_share, false, () -> share(e)));
      if (video(e))
        actions.add(new Sheet.Action("Make a GIF", R.drawable.ic_gif_box, false, () -> makeGif(e)));
    } else if (e.id != 0 && (!e.active() || e.status == DownloadManager.STATUS_PAUSED))
      actions.add(
          new Sheet.Action(
              e.active() ? "Restart" : "Retry", R.drawable.ic_refresh, false, () -> retry(e)));
    if (!e.source.isEmpty())
      actions.add(
          new Sheet.Action("Open original post", R.drawable.ic_open_in_new, false, () -> openSource(e)));
    if (e.active())
      actions.add(
          new Sheet.Action("Cancel download", R.drawable.ic_close, true, () -> confirmCancel(e)));
    else {
      actions.add(
          new Sheet.Action(
              "Remove from library", R.drawable.ic_close, false, () -> confirmRemove(e, false)));
      actions.add(new Sheet.Action("Delete file", R.drawable.ic_delete, true, () -> confirmRemove(e, true)));
    }
    sheet.actions(name(e), actions);
  }

  void confirmCancel(DownloadStore.Entry e) {
    sheet.confirm(
        "Cancel download?",
        "The partial file is discarded.",
        "Keep",
        "Cancel download",
        true,
        () -> remove(e, true));
  }

  void confirmRemove(DownloadStore.Entry e, boolean delete) {
    sheet.confirm(
        delete ? "Delete file?" : "Remove from library?",
        delete
            ? "The file is removed from Downloads."
            : "The saved file stays in Downloads/" + DownloadStore.folder(activity) + ".",
        "Keep",
        delete ? "Delete" : "Remove",
        delete,
        () -> remove(e, delete));
  }

  private void remove(DownloadStore.Entry e, boolean delete) {
    try {
      DownloadStore.remove(activity, e, delete);
      if (e.uri != null) Thumbnails.get(activity).forget(e.uri);
      listener.changed();
    } catch (RuntimeException ex) {
      listener.message("Could not remove the file: " + ex.getMessage());
    }
  }

  void retry(DownloadStore.Entry e) {
    Runnable run =
        () ->
            withStorage(
                () -> {
                  try {
                    DownloadStore.retry(activity, e);
                    listener.changed();
                  } catch (RuntimeException error) {
                    listener.message(error.getMessage());
                  }
                });
    if (e.active())
      sheet.confirm(
          "Restart download?",
          "This starts the file over using your current Wi-Fi setting.",
          "Keep waiting",
          "Restart",
          false,
          run);
    else run.run();
  }

  void makeGif(DownloadStore.Entry e) {
    sheet.confirm(
        "Make a GIF?",
        "Uses the first 60 seconds at 8 fps, up to 360 px. The video stays in your library.",
        "Not now",
        "Make GIF",
        false,
        () ->
            withStorage(
                () -> {
                  try {
                    GifService.start(activity, e.uri, name(e));
                    listener.message("Making a GIF. Follow its progress in your library.");
                    listener.changed();
                  } catch (RuntimeException error) {
                    listener.message("Could not start the GIF. " + error.getMessage());
                  }
                }));
  }

  private void withStorage(Runnable action) {
    if (StoragePermission.ensure(activity, STORAGE_REQUEST)) action.run();
    else afterPermission = action;
  }

  /** Forward from {@code onRequestPermissionsResult}. */
  void onPermissionResult(boolean granted) {
    Runnable pending = afterPermission;
    afterPermission = null;
    if (pending == null) return;
    if (granted) pending.run();
    else listener.message(StoragePermission.DENIED);
  }
}
