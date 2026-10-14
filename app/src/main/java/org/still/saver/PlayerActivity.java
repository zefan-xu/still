// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;
import org.still.saver.ui.Format;
import org.still.saver.ui.LocalFrames;
import org.still.saver.ui.Sheet;
import org.still.saver.ui.Snackbar;
import org.still.saver.ui.Theme;
import org.still.saver.ui.Ui;

/** Full-bleed viewer for local files. Tap to show or hide the controls. */
public final class PlayerActivity extends Activity {
  private static final int BLACK = 0xff141413, WHITE = 0xfffaf9f5;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private VideoView video;
  private Drawable animation;
  private boolean destroyed, resumed, chromeVisible = true, prepared;
  private Ui ui;
  private Sheet sheet;
  private Snackbar snackbar;
  private EntryActions actions;
  private DownloadStore.Entry entry;
  private LinearLayout top, bottom;
  private ImageView playPause;
  private SeekBar seek;
  private TextView time;
  private final Runnable tick =
      new Runnable() {
        @Override
        public void run() {
          updatePosition();
          if (chromeVisible && video != null && video.isPlaying()) handler.postDelayed(this, 250);
        }
      };
  private final Runnable autoHide =
      () -> {
        if (video != null && video.isPlaying()) setChrome(false);
      };

  @Override
  public void onCreate(Bundle state) {
    setTheme(R.style.Still_Viewer);
    super.onCreate(state);
    ui = new Ui(this, Theme.viewer());
    FrameLayout stage = new FrameLayout(this);
    stage.setBackgroundColor(BLACK);
    FrameLayout root = ui.screen(stage);
    root.setBackgroundColor(BLACK);
    sheet = new Sheet(ui, root);
    snackbar = new Snackbar(ui, root);
    String key = getIntent().getStringExtra("entry");
    if (key != null) {
      entry = DownloadStore.find(this, key);
      actions =
          new EntryActions(
              this,
              sheet,
              new EntryActions.Listener() {
                @Override
                public void changed() {
                  try {
                    entry = DownloadStore.find(PlayerActivity.this, entry.key);
                  } catch (IllegalStateException removed) {
                    finish();
                  }
                }

                @Override
                public void message(String text) {
                  snackbar.show(text);
                }
              });
    }
    String mime = getIntent().getType();
    if (mime != null && mime.startsWith("video/")) buildVideo(stage, state);
    else buildImage(stage);
    stage.setOnClickListener(v -> setChrome(!chromeVisible));
    buildChrome(stage, mime != null && mime.startsWith("video/"));
    ui.onInsetsChanged(
        () -> {
          stage.setPadding(0, 0, 0, 0);
          top.setPadding(ui.dp(4), ui.insetTop() + ui.dp(4), ui.dp(16), ui.dp(24));
          bottom.setPadding(ui.dp(12), ui.dp(32), ui.dp(12), ui.insetBottom() + ui.dp(8));
        });
    setContentView(root);
    root.requestApplyInsets();
  }

  private void buildVideo(FrameLayout stage, Bundle state) {
    video = new VideoView(this);
    stage.addView(video, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
    video.setVideoURI(getIntent().getData());
    video.setOnPreparedListener(
        player -> {
          prepared = true;
          if (state != null) video.seekTo(state.getInt("position", 0));
          seek.setMax(Math.max(1, video.getDuration()));
          updatePosition();
          if (resumed) play();
        });
    video.setOnCompletionListener(
        player -> {
          updatePlayPause();
          setChrome(true);
        });
    video.setOnErrorListener(
        (p, a, b) -> {
          snackbar.show(
              "The video could not be played. Share it to another player or download it again.");
          return true;
        });
  }

  private void buildImage(FrameLayout stage) {
    ImageView image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    stage.addView(image, new FrameLayout.LayoutParams(-1, -1));
    new Thread(
            () -> {
              try {
                Drawable drawable = LocalFrames.drawable(this, getIntent().getData(), 1600);
                runOnUiThread(
                    () -> {
                      if (destroyed) return;
                      image.setImageDrawable(drawable);
                      if (Build.VERSION.SDK_INT >= 28 && drawable instanceof AnimatedImageDrawable) {
                        animation = drawable;
                        if (resumed) ((AnimatedImageDrawable) animation).start();
                      }
                    });
              } catch (Exception | OutOfMemoryError e) {
                runOnUiThread(
                    () -> {
                      if (!destroyed) snackbar.show("The image is unavailable. Download it again.");
                    });
              }
            },
            "still-image")
        .start();
  }

  private ImageView whiteIcon(int res, String description, Runnable click) {
    ImageView button = ui.iconButton(res, description, click);
    button.setImageTintList(ColorStateList.valueOf(WHITE));
    return button;
  }

  private void buildChrome(FrameLayout stage, boolean isVideo) {
    top = ui.row();
    top.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x99000000, 0x00000000}));
    top.addView(whiteIcon(R.drawable.ic_arrow_back, "Back", this::finish));
    String title = getIntent().getStringExtra("title");
    TextView name = ui.label(title == null ? "" : title, 16);
    name.setTextColor(WHITE);
    name.setSingleLine();
    name.setEllipsize(TextUtils.TruncateAt.END);
    top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    stage.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

    bottom = ui.column();
    bottom.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xb3000000, 0x00000000}));
    bottom.setClickable(true);
    if (isVideo) {
      LinearLayout controls = ui.row();
      playPause = whiteIcon(R.drawable.ic_play_arrow, "Play", this::togglePlay);
      controls.addView(playPause);
      seek = new SeekBar(this);
      seek.setProgressTintList(ColorStateList.valueOf(0xffd97757));
      seek.setThumbTintList(ColorStateList.valueOf(WHITE));
      seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x66faf9f5));
      seek.setContentDescription("Playback position");
      seek.setOnSeekBarChangeListener(
          new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
              if (fromUser && prepared) {
                video.seekTo(progress);
                updatePosition();
              }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
              handler.removeCallbacks(autoHide);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
              scheduleHide();
            }
          });
      controls.addView(seek, new LinearLayout.LayoutParams(0, -2, 1));
      time = ui.caption("0:00");
      time.setTextColor(WHITE);
      time.setPadding(ui.dp(4), 0, ui.dp(8), 0);
      controls.addView(time);
      bottom.addView(controls);
    }
    if (entry != null) {
      LinearLayout row = ui.row();
      row.setPadding(0, ui.dp(4), 0, 0);
      addAction(row, R.drawable.ic_share, "Share", () -> actions.share(entry));
      if (EntryActions.video(entry))
        addAction(row, R.drawable.ic_gif_box, "Make GIF", () -> actions.makeGif(entry));
      if (!entry.source.isEmpty())
        addAction(row, R.drawable.ic_open_in_new, "Original post", () -> actions.openSource(entry));
      addAction(row, R.drawable.ic_delete, "Delete", () -> actions.confirmRemove(entry, true));
      bottom.addView(row);
    }
    stage.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
  }

  private void addAction(LinearLayout row, int icon, String label, Runnable click) {
    LinearLayout button = ui.column();
    button.setGravity(Gravity.CENTER_HORIZONTAL);
    button.setPadding(0, ui.dp(8), 0, ui.dp(8));
    ImageView image = ui.icon(icon, WHITE, 24);
    button.addView(image);
    TextView text = ui.caption(label);
    text.setTextColor(WHITE);
    // The column stretches its children; centre the words under the icon, not at the start.
    text.setGravity(Gravity.CENTER);
    text.setPadding(0, ui.dp(4), 0, 0);
    button.addView(text);
    button.setBackground(ui.ripple(null, ui.shape(WHITE, 12, 0)));
    button.setFocusable(true);
    button.setClickable(true);
    button.setContentDescription(label);
    button.setOnClickListener(
        v -> {
          if (video != null && video.isPlaying()) {
            video.pause();
            updatePlayPause();
          }
          click.run();
        });
    row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
  }

  private void togglePlay() {
    if (video.isPlaying()) {
      video.pause();
      handler.removeCallbacks(autoHide);
    } else play();
    updatePlayPause();
  }

  private void play() {
    video.start();
    updatePlayPause();
    handler.removeCallbacks(tick);
    handler.post(tick);
    scheduleHide();
  }

  private void updatePlayPause() {
    if (playPause == null) return;
    boolean playing = video.isPlaying();
    playPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
    playPause.setContentDescription(playing ? "Pause" : "Play");
  }

  private void updatePosition() {
    if (video == null || !prepared) return;
    int position = video.getCurrentPosition();
    seek.setProgress(position);
    time.setText(Format.duration(position) + " / " + Format.duration(video.getDuration()));
  }

  private void scheduleHide() {
    handler.removeCallbacks(autoHide);
    handler.postDelayed(autoHide, 3000);
  }

  private void setChrome(boolean visible) {
    chromeVisible = visible;
    for (View bar : new View[] {top, bottom}) {
      bar.animate().cancel();
      if (visible) {
        bar.setVisibility(View.VISIBLE);
        bar.animate().alpha(1).setDuration(150).start();
      } else
        bar.animate()
            .alpha(0)
            .setDuration(150)
            .withEndAction(() -> bar.setVisibility(View.GONE))
            .start();
    }
    immersive(!visible);
    if (visible) {
      handler.removeCallbacks(tick);
      handler.post(tick);
      scheduleHide();
    }
  }

  @SuppressWarnings("deprecation")
  private void immersive(boolean hidden) {
    if (Build.VERSION.SDK_INT >= 30) {
      WindowInsetsController controller = getWindow().getInsetsController();
      if (controller == null) return;
      controller.setSystemBarsBehavior(
          WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
      if (hidden) controller.hide(WindowInsets.Type.systemBars());
      else controller.show(WindowInsets.Type.systemBars());
    } else {
      getWindow()
          .getDecorView()
          .setSystemUiVisibility(
              hidden
                  ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                      | View.SYSTEM_UI_FLAG_FULLSCREEN
                      | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                      | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  : View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    if (video != null) state.putInt("position", video.getCurrentPosition());
  }

  @Override
  protected void onPause() {
    resumed = false;
    if (video != null) video.pause();
    handler.removeCallbacks(tick);
    handler.removeCallbacks(autoHide);
    if (Build.VERSION.SDK_INT >= 28 && animation instanceof AnimatedImageDrawable)
      ((AnimatedImageDrawable) animation).stop();
    super.onPause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    if (video != null) play();
    if (Build.VERSION.SDK_INT >= 28 && animation instanceof AnimatedImageDrawable)
      ((AnimatedImageDrawable) animation).start();
  }

  @Override
  public void onBackPressed() {
    if (!sheet.back()) super.onBackPressed();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request == EntryActions.STORAGE_REQUEST && actions != null)
      actions.onPermissionResult(StoragePermission.granted(grants));
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    handler.removeCallbacksAndMessages(null);
    snackbar.hide();
    if (video != null) video.stopPlayback();
    super.onDestroy();
  }
}
