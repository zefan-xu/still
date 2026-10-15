// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
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
  private static final String RETRY = "retry", GIF = "gif";
  private static final String PENDING_ACTION = "storageAction", PENDING_KEY = "storageEntry";

  private final Activity activity;
  private final Sheet sheet;
  private final Listener listener;
  private String pendingAction, pendingKey;

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
      listener.message(text(R.string.entry_missing));
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
      listener.message(text(R.string.entry_unavailable));
      return;
    }
    Intent send =
        new Intent(Intent.ACTION_SEND)
            .setType(e.mime)
            .putExtra(Intent.EXTRA_STREAM, e.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    send.setClipData(ClipData.newRawUri(text(R.string.entry_clip_label), e.uri));
    try {
      activity.startActivity(Intent.createChooser(send, text(R.string.entry_share_title)));
    } catch (RuntimeException ex) {
      listener.message(text(R.string.entry_share_error));
    }
  }

  void openSource(DownloadStore.Entry e) {
    if (e.source.isEmpty()) {
      listener.message(text(R.string.entry_no_source));
      return;
    }
    try {
      activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(e.source)));
    } catch (RuntimeException ex) {
      listener.message(text(R.string.entry_open_error));
    }
  }

  void menu(DownloadStore.Entry e) {
    List<Sheet.Action> actions = new ArrayList<>();
    if (saved(e)) {
      actions.add(
          new Sheet.Action(
              text(R.string.action_share), R.drawable.ic_share, false, () -> share(e)));
      if (video(e))
        actions.add(
            new Sheet.Action(
                text(R.string.entry_make_gif), R.drawable.ic_gif_box, false, () -> makeGif(e)));
    } else if (e.id != 0 && (!e.active() || e.status == DownloadManager.STATUS_PAUSED))
      actions.add(
          new Sheet.Action(
              text(e.active() ? R.string.action_restart : R.string.action_retry),
              R.drawable.ic_refresh,
              false,
              () -> retry(e)));
    if (!e.source.isEmpty())
      actions.add(
          new Sheet.Action(
              text(R.string.entry_open_source),
              R.drawable.ic_open_in_new,
              false,
              () -> openSource(e)));
    if (e.active())
      actions.add(
          new Sheet.Action(
              text(R.string.entry_cancel), R.drawable.ic_close, true, () -> confirmCancel(e)));
    else {
      actions.add(
          new Sheet.Action(
              text(R.string.entry_remove),
              R.drawable.ic_close,
              false,
              () -> confirmRemove(e, false)));
      actions.add(
          new Sheet.Action(
              text(R.string.entry_delete),
              R.drawable.ic_delete,
              true,
              () -> confirmRemove(e, true)));
    }
    sheet.actions(name(e), actions);
  }

  void confirmCancel(DownloadStore.Entry e) {
    sheet.confirm(
        text(R.string.entry_cancel_title),
        text(R.string.entry_cancel_body),
        text(R.string.action_keep),
        text(R.string.entry_cancel),
        true,
        () -> remove(e, true));
  }

  void promptDelete(DownloadStore.Entry e) {
    LinearLayout box =
        sheet.frame(
            text(R.string.entry_remove_or_delete_title),
            activity.getString(
                R.string.entry_remove_or_delete_body, DownloadStore.folder(activity)));
    box.addView(
        sheet.actionRow(
            new Sheet.Action(
                text(R.string.entry_remove),
                R.drawable.ic_close,
                false,
                () -> {
                  sheet.dismiss();
                  remove(e, false);
                })));
    box.addView(
        sheet.actionRow(
            new Sheet.Action(
                text(R.string.entry_delete),
                R.drawable.ic_delete,
                true,
                () -> {
                  sheet.dismiss();
                  remove(e, true);
                })));
    box.addView(
        sheet.actionRow(new Sheet.Action(text(R.string.action_keep), 0, false, sheet::dismiss)));
    sheet.show(box);
  }

  void confirmRemove(DownloadStore.Entry e, boolean delete) {
    sheet.confirm(
        text(delete ? R.string.entry_delete_title : R.string.entry_remove_title),
        delete
            ? text(R.string.entry_delete_body)
            : activity.getString(R.string.entry_remove_body, DownloadStore.folder(activity)),
        text(R.string.action_keep),
        text(delete ? R.string.action_delete : R.string.action_remove),
        delete,
        () -> remove(e, delete));
  }

  private void remove(DownloadStore.Entry e, boolean delete) {
    try {
      DownloadStore.remove(activity, e, delete);
      if (e.uri != null) Thumbnails.get(activity).forget(e.uri);
      listener.changed();
    } catch (RuntimeException ex) {
      listener.message(activity.getString(R.string.entry_remove_error, ex.getMessage()));
    }
  }

  void retry(DownloadStore.Entry e) {
    Runnable run = () -> withStorage(RETRY, e);
    if (e.active())
      sheet.confirm(
          text(R.string.entry_restart_title),
          text(R.string.entry_restart_body),
          text(R.string.entry_keep_waiting),
          text(R.string.action_restart),
          false,
          run);
    else run.run();
  }

  void makeGif(DownloadStore.Entry e) {
    sheet.confirm(
        text(R.string.entry_gif_title),
        text(R.string.entry_gif_body),
        text(R.string.action_not_now),
        text(R.string.entry_gif_action),
        false,
        () -> withStorage(GIF, e));
  }

  private void withStorage(String action, DownloadStore.Entry e) {
    if (StoragePermission.ensure(activity, STORAGE_REQUEST)) perform(action, e);
    else {
      pendingAction = action;
      pendingKey = e.key;
    }
  }

  private void perform(String action, DownloadStore.Entry e) {
    if (GIF.equals(action)) {
      try {
        GifService.start(activity, e.uri, name(e));
        listener.message(text(R.string.entry_gif_started));
        listener.changed();
      } catch (RuntimeException error) {
        listener.message(activity.getString(R.string.entry_gif_error, error.getMessage()));
      }
      return;
    }
    try {
      DownloadStore.retry(activity, e);
      listener.changed();
    } catch (RuntimeException error) {
      listener.message(error.getMessage());
    }
  }

  /** The storage prompt outlives a rotation, so the action waiting for it is kept by entry key. */
  void saveState(Bundle state) {
    state.putString(PENDING_ACTION, pendingAction);
    state.putString(PENDING_KEY, pendingKey);
  }

  void restoreState(Bundle state) {
    if (state == null) return;
    pendingAction = state.getString(PENDING_ACTION);
    pendingKey = state.getString(PENDING_KEY);
  }

  /** Forward from {@code onRequestPermissionsResult}. */
  void onPermissionResult(boolean granted) {
    String action = pendingAction, key = pendingKey;
    pendingAction = pendingKey = null;
    if (action == null || key == null) return;
    if (!granted) {
      listener.message(text(R.string.storage_denied));
      return;
    }
    DownloadStore.Entry current;
    try {
      current = DownloadStore.find(activity, key);
    } catch (IllegalStateException removed) {
      listener.message(removed.getMessage());
      return;
    }
    perform(action, current);
  }

  private String text(int resource) {
    return activity.getString(resource);
  }
}
