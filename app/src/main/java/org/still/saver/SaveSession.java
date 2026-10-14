// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.still.saver.core.Media;
import org.still.saver.core.Post;
import org.still.saver.core.PublicPageResolver;
import org.still.saver.core.Variant;

/**
 * Lookup → choose → save, shared by the home screen and the share sheet. Main thread only. It is
 * retained across configuration changes, so a rotation never repeats a slow extractor lookup.
 */
final class SaveSession {
  enum Phase {
    IDLE,
    LOOKING_UP,
    READY,
    ADDED,
    ERROR
  }

  interface Host {
    void onSessionChanged(SaveSession session);
  }

  static final int STORAGE_REQUEST = 1;

  private final Context app;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Map<Integer, Variant> selections = new HashMap<>();
  private final Set<Integer> excluded = new HashSet<>();
  private Phase phase = Phase.IDLE;
  private Post post;
  private String message, submitted = "";
  private int added, duplicates, generation;
  private Future<?> lookup;
  private Host host;
  private boolean disposed, autoSave, pendingSave;

  SaveSession(Context context) {
    app = context.getApplicationContext();
  }

  void attach(Host host) {
    this.host = host;
    host.onSessionChanged(this);
  }

  void detach() {
    host = null;
  }

  /** Called when the owning screen is finally destroyed (not for rotation). */
  void dispose() {
    disposed = true;
    generation++;
    host = null;
    if (lookup != null) lookup.cancel(true);
    worker.shutdownNow();
  }

  Phase phase() {
    return phase;
  }

  Post post() {
    return post;
  }

  String message() {
    return message;
  }

  String submitted() {
    return submitted;
  }

  int added() {
    return added;
  }

  int duplicates() {
    return duplicates;
  }

  Map<Integer, Variant> selections() {
    return selections;
  }

  /** Re-renders hosts after a purely visual change. */
  void refresh() {
    changed();
  }

  private void changed() {
    if (host != null) host.onSessionChanged(this);
  }

  void find(String input) {
    if (phase == Phase.LOOKING_UP) return;
    String text = input == null ? "" : input.trim();
    if (text.isEmpty()) {
      phase = Phase.ERROR;
      message = "Paste or share a post link first.";
      changed();
      return;
    }
    int ticket = ++generation;
    submitted = text;
    phase = Phase.LOOKING_UP;
    post = null;
    message = null;
    selections.clear();
    excluded.clear();
    autoSave = false;
    changed();
    String api = DownloadStore.prefs(app).getString("api", "https://api.fxtwitter.com");
    lookup =
        worker.submit(
            () -> {
              try {
                Post found = new PublicPageResolver(app, api).resolve(text);
                main.post(
                    () -> {
                      if (ticket != generation || disposed) return;
                      post = found;
                      phase = Phase.READY;
                      autoSave = DownloadStore.prefs(app).getBoolean("auto", false);
                      changed();
                    });
              } catch (Exception e) {
                main.post(
                    () -> {
                      if (ticket != generation || disposed) return;
                      phase = Phase.ERROR;
                      message =
                          e.getMessage() == null
                              ? "Could not read this post. Try again."
                              : e.getMessage();
                      changed();
                    });
              }
            });
  }

  void cancelLookup() {
    generation++;
    if (lookup != null) lookup.cancel(true);
    reset();
  }

  /** Back to an empty composer; never touches queued downloads. */
  void reset() {
    generation++;
    phase = Phase.IDLE;
    post = null;
    message = null;
    selections.clear();
    excluded.clear();
    autoSave = false;
    pendingSave = false;
    changed();
  }

  /** True once after a lookup when "Save automatically" is on. */
  boolean takeAutoSave() {
    boolean value = autoSave && phase == Phase.READY;
    autoSave = false;
    return value;
  }

  boolean included(int index) {
    return !excluded.contains(index);
  }

  void toggle(int index) {
    if (!excluded.remove(index)) excluded.add(index);
    changed();
  }

  int includedCount() {
    return post == null ? 0 : post.media.size() - excluded.size();
  }

  String quality() {
    return DownloadStore.prefs(app).getString("quality", "ask");
  }

  Variant chosen(int index) {
    Variant picked = selections.get(index);
    return picked != null ? picked : post.media.get(index).chooseVariant(quality());
  }

  void choose(int index, Variant variant) {
    if (!post.media.get(index).variants.contains(variant))
      throw new IllegalArgumentException("The quality does not belong to this media");
    selections.put(index, variant);
    changed();
  }

  /** Queues every included item, requesting storage access first on Android 9 and older. */
  void save(Activity activity) {
    if (post == null || phase != Phase.READY) return;
    if (!StoragePermission.ensure(activity, STORAGE_REQUEST)) {
      pendingSave = true;
      return;
    }
    Post selected = post;
    List<Integer> indexes = new ArrayList<>();
    for (int i = 0; i < selected.media.size(); i++) if (included(i)) indexes.add(i);
    if (indexes.isEmpty()) {
      message = "Choose at least one item to save.";
      changed();
      return;
    }
    int count = 0, skipped = 0;
    try {
      for (int i : indexes)
        if (DownloadStore.enqueue(activity, selected, i, chosen(i))) count++;
        else skipped++;
    } catch (RuntimeException e) {
      message = e.getMessage() == null ? "The download could not be queued." : e.getMessage();
      changed();
      return;
    }
    added = count;
    duplicates = skipped;
    phase = Phase.ADDED;
    message = null;
    changed();
  }

  void onPermissionResult(Activity activity, boolean granted) {
    if (!pendingSave) return;
    pendingSave = false;
    if (granted) save(activity);
    else {
      message = StoragePermission.DENIED;
      changed();
    }
  }

  /** Test hook: show a resolved post without the network. */
  void showResolvedForTest(Post resolved) {
    generation++;
    post = resolved;
    phase = Phase.READY;
    message = null;
    selections.clear();
    excluded.clear();
    autoSave = DownloadStore.prefs(app).getBoolean("auto", false);
    changed();
  }

  static String kindLabel(Media media) {
    return org.still.saver.ui.Format.kind(media.kind);
  }
}
