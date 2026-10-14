// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.still.saver.ui.Format;
import org.still.saver.ui.LocalFrames;
import org.still.saver.ui.Motion;
import org.still.saver.ui.Sheet;
import org.still.saver.ui.Snackbar;
import org.still.saver.ui.Theme;
import org.still.saver.ui.Thumbnails;
import org.still.saver.ui.Ui;

/**
 * Full-bleed viewer for local files. Supports pinch and double-tap zoom, panning while zoomed, and
 * horizontal swiping between saved images and videos. Tap to show or hide the controls.
 */
public final class PlayerActivity extends Activity {
  private static final int BLACK = 0xff141413, WHITE = 0xfffaf9f5;
  private static final float MIN_SCALE = 1f, DOUBLE_TAP_SCALE = 2.5f, MAX_SCALE = 4f;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final List<Item> items = new ArrayList<>();
  private final Map<Integer, Page> pages = new HashMap<>();
  private int currentIndex, pendingSeekPosition;
  private VideoView video;
  private Drawable animation;
  private boolean destroyed, resumed, chromeVisible = true, prepared;
  private Ui ui;
  private Sheet sheet;
  private Snackbar snackbar;
  private EntryActions actions;
  private DownloadStore.Entry entry;
  private Pager pager;
  private LinearLayout top, bottom, controls, actionsRow;
  private TextView titleView, time;
  private ImageView playPause;
  private SeekBar seek;
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

  private static final class Item {
    final DownloadStore.Entry entry;
    final Uri uri;
    final String mime;
    final String title;

    Item(DownloadStore.Entry entry, Uri uri, String mime, String title) {
      this.entry = entry;
      this.uri = uri;
      this.mime = mime == null ? "" : mime;
      this.title = title == null ? "" : title;
    }

    static Item fromEntry(DownloadStore.Entry e) {
      return new Item(e, e.uri, e.mime, EntryActions.name(e));
    }

    boolean isVideo() {
      return mime.startsWith("video/");
    }
  }

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
    actions =
        new EntryActions(
            this,
            sheet,
            new EntryActions.Listener() {
              @Override
              public void changed() {
                if (entry == null) return;
                try {
                  entry = DownloadStore.find(PlayerActivity.this, entry.key);
                  items.set(currentIndex, Item.fromEntry(entry));
                  updateChrome();
                } catch (IllegalStateException removed) {
                  removeCurrentPage();
                }
              }

              @Override
              public void message(String text) {
                snackbar.show(text);
              }
            });
    loadItems(state);
    pager = new Pager(this);
    stage.addView(pager, new FrameLayout.LayoutParams(-1, -1));
    buildChrome(stage);
    selectPage(currentIndex);
    ui.onInsetsChanged(
        () -> {
          stage.setPadding(0, 0, 0, 0);
          top.setPadding(ui.dp(4), ui.insetTop() + ui.dp(4), ui.dp(16), ui.dp(24));
          bottom.setPadding(ui.dp(12), ui.dp(32), ui.dp(12), ui.insetBottom() + ui.dp(8));
        });
    setContentView(root);
    root.requestApplyInsets();
  }

  private void loadItems(Bundle state) {
    items.clear();
    pendingSeekPosition = state != null ? state.getInt("position", 0) : 0;
    String requestedKey =
        state != null && state.containsKey("entry")
            ? state.getString("entry")
            : getIntent().getStringExtra("entry");
    Uri intentUri = getIntent().getData();
    String intentMime = getIntent().getType();
    String intentTitle = getIntent().getStringExtra("title");

    List<DownloadStore.Entry> saved = new ArrayList<>();
    for (DownloadStore.Entry e : DownloadStore.list(this)) {
      if (EntryActions.saved(e) && e.uri != null) saved.add(e);
    }

    int start = -1;
    if (requestedKey != null) {
      for (int i = 0; i < saved.size(); i++) {
        if (requestedKey.equals(saved.get(i).key)) {
          start = i;
          break;
        }
      }
    }
    if (start < 0 && intentUri != null) {
      for (int i = 0; i < saved.size(); i++) {
        if (intentUri.equals(saved.get(i).uri)) {
          start = i;
          break;
        }
      }
    }
    if (start >= 0) {
      for (DownloadStore.Entry e : saved) items.add(Item.fromEntry(e));
      currentIndex = start;
    } else if (requestedKey != null) {
      try {
        DownloadStore.Entry e = DownloadStore.find(this, requestedKey);
        if (e.uri != null) items.add(Item.fromEntry(e));
      } catch (IllegalStateException ignored) {
      }
      currentIndex = 0;
    }
    if (items.isEmpty()) {
      items.add(new Item(null, intentUri, intentMime, intentTitle));
      currentIndex = 0;
    }
    if (state != null && state.containsKey("index") && requestedKey == null) {
      int savedIndex = state.getInt("index", 0);
      if (savedIndex >= 0 && savedIndex < items.size()) currentIndex = savedIndex;
    }
    entry = items.get(currentIndex).entry;
  }

  private void removeCurrentPage() {
    for (Page page : pages.values()) page.release();
    pages.clear();
    items.remove(currentIndex);
    if (items.isEmpty()) {
      finish();
      return;
    }
    currentIndex = Math.min(currentIndex, items.size() - 1);
    pager.resetOffset();
    selectPage(currentIndex);
  }

  private void selectPage(int nextIndex) {
    currentIndex = nextIndex;
    entry = items.get(currentIndex).entry;
    int min = Math.max(0, currentIndex - 1);
    int max = Math.min(items.size() - 1, currentIndex + 1);
    for (Iterator<Map.Entry<Integer, Page>> it = pages.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<Integer, Page> e = it.next();
      int idx = e.getKey();
      if (idx < min || idx > max) {
        e.getValue().release();
        it.remove();
      }
    }
    for (int i = min; i <= max; i++) {
      if (!pages.containsKey(i)) {
        Page page = new Page(i, items.get(i));
        pages.put(i, page);
        pager.addView(page.view, new FrameLayout.LayoutParams(-1, -1));
      }
    }
    for (Page page : pages.values()) {
      if (page.pageIndex != currentIndex) page.deactivate();
    }
    Page active = pages.get(currentIndex);
    if (active != null) active.activate();
    pager.layoutPages();
    updateChrome();
  }

  private final class Page {
    final int pageIndex;
    final Item item;
    final FrameLayout view = new FrameLayout(PlayerActivity.this);
    final ImageView image = new ImageView(PlayerActivity.this);
    VideoView pageVideo;
    Drawable pageAnimation;
    Bitmap videoPreview;
    boolean pagePrepared, previewLoading, released;
    float scale = 1f, tx = 0f, ty = 0f;
    float targetScale = 1f, targetTx = 0f, targetTy = 0f;
    final Motion.State scaleState = new Motion.State(1f);
    final Motion.State txState = new Motion.State(0f);
    final Motion.State tyState = new Motion.State(0f);
    final Motion.Loop zoomLoop;

    Page(int pageIndex, Item item) {
      this.pageIndex = pageIndex;
      this.item = item;
      view.setClipChildren(true);
      view.setClipToPadding(true);
      image.setScaleType(ImageView.ScaleType.FIT_CENTER);
      image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      view.addView(image, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
      zoomLoop =
          new Motion.Loop(
              view,
              (dt, calm) -> {
                boolean a = Motion.settle(scaleState, targetScale, dt, 0.08f, calm);
                boolean b = Motion.settle(txState, targetTx, dt, 0.08f, calm);
                boolean c = Motion.settle(tyState, targetTy, dt, 0.08f, calm);
                scale = scaleState.x;
                tx = txState.x;
                ty = tyState.x;
                clampPan();
                applyTransform();
                return a || b || c;
              });
      if (!item.isVideo()) loadImage();
      else if (pageIndex != currentIndex) ensureVideoPreview();
    }

    private void loadImage() {
      new Thread(
              () -> {
                try {
                  Drawable drawable = LocalFrames.drawable(PlayerActivity.this, item.uri, 2048);
                  runOnUiThread(
                      () -> {
                        if (destroyed || released) return;
                        image.setImageDrawable(drawable);
                        clampPan();
                        applyTransform();
                        if (Build.VERSION.SDK_INT >= 28
                            && drawable instanceof AnimatedImageDrawable) {
                          pageAnimation = drawable;
                          if (pageIndex == currentIndex) {
                            animation = drawable;
                            if (resumed) ((AnimatedImageDrawable) animation).start();
                          }
                        }
                      });
                } catch (Exception | OutOfMemoryError e) {
                  runOnUiThread(
                      () -> {
                        if (!destroyed && !released && pageIndex == currentIndex)
                          snackbar.show("The image is unavailable. Download it again.");
                      });
                }
              },
              "still-image")
          .start();
    }

    private void ensureVideoPreview() {
      if (videoPreview != null || previewLoading || item.uri == null) return;
      previewLoading = true;
      new Thread(
              () -> {
                try {
                  LocalFrames.Frame frame =
                      LocalFrames.video(PlayerActivity.this, item.uri, Thumbnails.Sizes.PREVIEW);
                  runOnUiThread(
                      () -> {
                        previewLoading = false;
                        if (destroyed || released) return;
                        videoPreview = frame.bitmap;
                        image.setImageBitmap(videoPreview);
                        clampPan();
                        applyTransform();
                      });
                } catch (Exception | OutOfMemoryError ignored) {
                  runOnUiThread(() -> previewLoading = false);
                }
              },
              "still-video-preview")
          .start();
    }

    void activate() {
      if (item.isVideo()) {
        animation = null;
        image.setVisibility(View.GONE);
        if (pageVideo == null) {
          pageVideo = new VideoView(PlayerActivity.this);
          view.addView(pageVideo, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
          applyTransform();
          VideoView activeVideo = pageVideo;
          pageVideo.setVideoURI(item.uri);
          pageVideo.setOnPreparedListener(
              player -> {
                if (destroyed || released || pageVideo != activeVideo) return;
                pagePrepared = true;
                if (pageIndex == currentIndex) {
                  prepared = true;
                  if (pendingSeekPosition > 0) {
                    activeVideo.seekTo(pendingSeekPosition);
                    pendingSeekPosition = 0;
                  }
                  seek.setMax(Math.max(1, activeVideo.getDuration()));
                  updatePosition();
                  if (resumed) play();
                }
              });
          pageVideo.setOnCompletionListener(
              player -> {
                if (pageIndex == currentIndex) {
                  updatePlayPause();
                  setChrome(true);
                }
              });
          pageVideo.setOnErrorListener(
              (p, a, b) -> {
                if (pageIndex == currentIndex)
                  snackbar.show(
                      "The video could not be played. Share it to another player or download it again.");
                return true;
              });
        }
        video = pageVideo;
        prepared = pagePrepared;
        if (resumed) play();
      } else {
        video = null;
        prepared = false;
        animation = pageAnimation;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(autoHide);
        if (Build.VERSION.SDK_INT >= 28 && animation instanceof AnimatedImageDrawable) {
          if (resumed) ((AnimatedImageDrawable) animation).start();
          else ((AnimatedImageDrawable) animation).stop();
        }
      }
    }

    void deactivate() {
      resetZoom();
      if (item.isVideo()) {
        if (pageVideo != null) {
          if (video == pageVideo) {
            video = null;
            prepared = false;
          }
          pageVideo.stopPlayback();
          view.removeView(pageVideo);
          pageVideo = null;
          pagePrepared = false;
        }
        image.setVisibility(View.VISIBLE);
        ensureVideoPreview();
      } else if (Build.VERSION.SDK_INT >= 28 && pageAnimation instanceof AnimatedImageDrawable) {
        ((AnimatedImageDrawable) pageAnimation).stop();
      }
    }

    void release() {
      released = true;
      zoomLoop.stop();
      if (pageVideo != null) {
        if (video == pageVideo) {
          video = null;
          prepared = false;
        }
        pageVideo.stopPlayback();
        pageVideo = null;
      }
      if (Build.VERSION.SDK_INT >= 28 && pageAnimation instanceof AnimatedImageDrawable) {
        ((AnimatedImageDrawable) pageAnimation).stop();
      }
      pager.removeView(view);
    }

    private float contentWidth() {
      int vw = view.getWidth(), vh = view.getHeight();
      if (vw <= 0 || vh <= 0) return 0f;
      if (item.isVideo() && pageVideo != null && pageVideo.getWidth() > 0 && pageVideo.getHeight() > 0)
        return pageVideo.getWidth();
      Drawable d = image.getDrawable();
      if (d != null && d.getIntrinsicWidth() > 0 && d.getIntrinsicHeight() > 0) {
        float fit = Math.min((float) vw / d.getIntrinsicWidth(), (float) vh / d.getIntrinsicHeight());
        return d.getIntrinsicWidth() * fit;
      }
      return vw;
    }

    private float contentHeight() {
      int vw = view.getWidth(), vh = view.getHeight();
      if (vw <= 0 || vh <= 0) return 0f;
      if (item.isVideo() && pageVideo != null && pageVideo.getWidth() > 0 && pageVideo.getHeight() > 0)
        return pageVideo.getHeight();
      Drawable d = image.getDrawable();
      if (d != null && d.getIntrinsicWidth() > 0 && d.getIntrinsicHeight() > 0) {
        float fit = Math.min((float) vw / d.getIntrinsicWidth(), (float) vh / d.getIntrinsicHeight());
        return d.getIntrinsicHeight() * fit;
      }
      return vh;
    }

    float maxTx(float s) {
      if (s <= 1f) return 0f;
      return Math.max(0f, contentWidth() * (s - 1f) / 2f);
    }

    float maxTy(float s) {
      if (s <= 1f) return 0f;
      return Math.max(0f, contentHeight() * (s - 1f) / 2f);
    }

    boolean canPanHorizontally(float dx) {
      if (scale <= 1.01f) return false;
      float mx = maxTx(scale);
      if (dx > 0) return tx < mx - 0.5f;
      if (dx < 0) return tx > -mx + 0.5f;
      return false;
    }

    void clampPan() {
      if (scale <= 1f) {
        scale = 1f;
        tx = 0f;
        ty = 0f;
        return;
      }
      float mx = maxTx(scale), my = maxTy(scale);
      tx = Math.max(-mx, Math.min(mx, tx));
      ty = Math.max(-my, Math.min(my, ty));
    }

    private void syncAnimState() {
      scaleState.x = targetScale = scale;
      txState.x = targetTx = tx;
      tyState.x = targetTy = ty;
      scaleState.v = txState.v = tyState.v = 0f;
    }

    void zoomAt(float nextScale, float focusX, float focusY, float panX, float panY) {
      zoomLoop.stop();
      nextScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, nextScale));
      float fx = focusX - view.getWidth() / 2f;
      float fy = focusY - view.getHeight() / 2f;
      float ratio = scale > 0 ? nextScale / scale : 1f;
      tx = fx + panX - (fx - tx) * ratio;
      ty = fy + panY - (fy - ty) * ratio;
      scale = nextScale;
      clampPan();
      syncAnimState();
      applyTransform();
    }

    void panBy(float dx, float dy) {
      zoomLoop.stop();
      tx += dx;
      ty += dy;
      clampPan();
      syncAnimState();
      applyTransform();
    }

    void toggleDoubleTapZoom(float tapX, float tapY) {
      zoomLoop.stop();
      syncAnimState();
      if (scale > 1.01f || targetScale > 1.01f) {
        targetScale = 1f;
        targetTx = 0f;
        targetTy = 0f;
      } else {
        targetScale = DOUBLE_TAP_SCALE;
        float fx = tapX - view.getWidth() / 2f;
        float fy = tapY - view.getHeight() / 2f;
        float ratio = targetScale / scale;
        float mx = maxTx(targetScale), my = maxTy(targetScale);
        targetTx = Math.max(-mx, Math.min(mx, fx - (fx - tx) * ratio));
        targetTy = Math.max(-my, Math.min(my, fy - (fy - ty) * ratio));
      }
      zoomLoop.start();
    }

    void resetZoom() {
      zoomLoop.stop();
      scale = targetScale = scaleState.x = 1f;
      tx = targetTx = txState.x = 0f;
      ty = targetTy = tyState.x = 0f;
      scaleState.v = txState.v = tyState.v = 0f;
      applyTransform();
    }

    void applyTransform() {
      image.setScaleX(scale);
      image.setScaleY(scale);
      image.setTranslationX(tx);
      image.setTranslationY(ty);
      if (pageVideo != null) {
        pageVideo.setScaleX(scale);
        pageVideo.setScaleY(scale);
        pageVideo.setTranslationX(tx);
        pageVideo.setTranslationY(ty);
      }
    }
  }

  private final class Pager extends FrameLayout {
    private static final int MODE_NONE = 0, MODE_PAN = 1, MODE_SWIPE = 2, MODE_PINCH = 3;
    private final GestureDetector gestureDetector;
    private final Motion.State settleState = new Motion.State(0f);
    private final Motion.Loop settleLoop;
    private final int touchSlop;
    private final int minFlingVelocity;
    private VelocityTracker velocityTracker;
    private int mode = MODE_NONE;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private float downX,
        downY,
        lastX,
        lastY,
        lastFocusX,
        lastFocusY,
        pinchSpan,
        pinchStartSpan,
        dragOffset;
    private boolean pinching, wasPinching, doubleTapped;

    Pager(Context context) {
      super(context);
      setClipChildren(true);
      setClipToPadding(true);
      ViewConfiguration config = ViewConfiguration.get(context);
      touchSlop = config.getScaledTouchSlop();
      minFlingVelocity = config.getScaledMinimumFlingVelocity();
      settleLoop =
          new Motion.Loop(
              this,
              (dt, calm) -> {
                boolean moving = Motion.settle(settleState, 0f, dt, 0.08f, calm);
                dragOffset = settleState.x;
                layoutPages();
                return moving;
              });
      gestureDetector =
          new GestureDetector(
              context,
              new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                  return true;
                }

                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                  if (!wasPinching && mode == MODE_NONE) performClick();
                  return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                  if (mode == MODE_SWIPE) return false;
                  doubleTapped = true;
                  Page page = pages.get(currentIndex);
                  if (page != null) {
                    page.toggleDoubleTapZoom(e.getX(), e.getY());
                    if (chromeVisible) scheduleHide();
                  }
                  return true;
                }
              });
    }

    void resetOffset() {
      settleLoop.stop();
      dragOffset = settleState.x = settleState.v = 0f;
    }

    private float pageSpan() {
      int width = getWidth();
      if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;
      return width + ui.dp(16);
    }

    void layoutPages() {
      float span = pageSpan();
      for (Page page : pages.values()) {
        page.view.setTranslationX((page.pageIndex - currentIndex) * span + dragOffset);
      }
    }

    private boolean canSwipe(float dx) {
      return (dx > 0 && currentIndex > 0) || (dx < 0 && currentIndex < items.size() - 1);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
      super.onSizeChanged(w, h, oldw, oldh);
      layoutPages();
      Page page = pages.get(currentIndex);
      if (page != null) {
        page.clampPan();
        page.applyTransform();
      }
    }

    @Override
    public boolean performClick() {
      super.performClick();
      setChrome(!chromeVisible);
      return true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
      return true;
    }

    // A tap is only confirmed after the double-tap timeout, so performClick runs from
    // onSingleTapConfirmed rather than inside this method.
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
      int action = event.getActionMasked();
      if (action == MotionEvent.ACTION_DOWN) {
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
        else velocityTracker.clear();
      }
      if (velocityTracker != null) velocityTracker.addMovement(event);

      if (event.getPointerCount() == 1) {
        gestureDetector.onTouchEvent(event);
      }

      switch (action) {
        case MotionEvent.ACTION_DOWN:
          if (settleLoop.running()) settleLoop.finish();
          activePointerId = event.getPointerId(0);
          downX = lastX = event.getX(0);
          downY = lastY = event.getY(0);
          mode = MODE_NONE;
          pinching = false;
          wasPinching = false;
          doubleTapped = false;
          break;

        case MotionEvent.ACTION_POINTER_DOWN:
          if (mode != MODE_SWIPE && event.getPointerCount() >= 2) {
            mode = MODE_PINCH;
            wasPinching = true;
            pinchSpan = pointerSpan(event);
            pinchStartSpan = pinchSpan;
            lastFocusX = pointerFocusX(event);
            lastFocusY = pointerFocusY(event);
            pinching = false;
          }
          break;

        case MotionEvent.ACTION_MOVE:
          if (event.getPointerCount() >= 2 && mode != MODE_SWIPE) {
            mode = MODE_PINCH;
            wasPinching = true;
            float span = pointerSpan(event);
            float focusX = pointerFocusX(event);
            float focusY = pointerFocusY(event);
            if (!pinching && Math.abs(span - pinchStartSpan) > touchSlop * 0.5f) {
              pinching = true;
            }
            if (pinching && pinchSpan > 1f && span > 1f) {
              Page page = pages.get(currentIndex);
              if (page != null) {
                page.zoomAt(
                    page.scale * (span / pinchSpan),
                    focusX,
                    focusY,
                    focusX - lastFocusX,
                    focusY - lastFocusY);
                if (chromeVisible) scheduleHide();
              }
            }
            pinchSpan = span;
            lastFocusX = focusX;
            lastFocusY = focusY;
            break;
          }
          if (mode == MODE_PINCH || event.getPointerCount() > 1) break;
          if (doubleTapped) break;
          int index = event.findPointerIndex(activePointerId);
          if (index < 0) {
            activePointerId = event.getPointerId(0);
            index = 0;
            downX = lastX = event.getX(0);
            downY = lastY = event.getY(0);
          }
          float x = event.getX(index);
          float y = event.getY(index);
          float dx = x - lastX;
          float dy = y - lastY;
          float totalDx = x - downX;
          float totalDy = y - downY;
          lastX = x;
          lastY = y;
          Page page = pages.get(currentIndex);
          if (page == null) break;
          if (mode == MODE_NONE && Math.hypot(totalDx, totalDy) > touchSlop) {
            if (page.scale > 1.01f) {
              if (wasPinching
                  || Math.abs(totalDy) >= Math.abs(totalDx)
                  || page.canPanHorizontally(totalDx)
                  || !canSwipe(totalDx)) {
                mode = MODE_PAN;
              } else {
                mode = MODE_SWIPE;
              }
            } else if (!wasPinching && Math.abs(totalDx) > Math.abs(totalDy)) {
              mode = MODE_SWIPE;
            }
          }
          if (mode == MODE_PAN) {
            page.panBy(dx, dy);
            if (chromeVisible) scheduleHide();
          } else if (mode == MODE_SWIPE) {
            float next = dragOffset + dx;
            boolean edge =
                (next > 0 && currentIndex == 0)
                    || (next < 0 && currentIndex == items.size() - 1);
            dragOffset += edge ? dx * 0.25f : dx;
            layoutPages();
            if (chromeVisible) scheduleHide();
          }
          break;

        case MotionEvent.ACTION_POINTER_UP:
          int pointerIndex = event.getActionIndex();
          int pointerId = event.getPointerId(pointerIndex);
          if (event.getPointerCount() > 2) {
            int i0 = pointerIndex == 0 ? 1 : 0;
            int i1 = pointerIndex <= 1 ? 2 : 1;
            float pdx = event.getX(i1) - event.getX(i0);
            float pdy = event.getY(i1) - event.getY(i0);
            pinchSpan = (float) Math.hypot(pdx, pdy);
            lastFocusX = (event.getX(i0) + event.getX(i1)) * 0.5f;
            lastFocusY = (event.getY(i0) + event.getY(i1)) * 0.5f;
          } else if (event.getPointerCount() == 2) {
            int remaining = 1 - pointerIndex;
            activePointerId = event.getPointerId(remaining);
            downX = lastX = event.getX(remaining);
            downY = lastY = event.getY(remaining);
            pinching = false;
            if (mode == MODE_PINCH) mode = MODE_NONE;
          } else if (pointerId == activePointerId) {
            int remaining = pointerIndex == 0 ? 1 : 0;
            activePointerId = event.getPointerId(remaining);
            downX = lastX = event.getX(remaining);
            downY = lastY = event.getY(remaining);
          }
          break;

        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          if (mode == MODE_SWIPE) {
            float vx = 0f;
            if (velocityTracker != null && action == MotionEvent.ACTION_UP) {
              velocityTracker.computeCurrentVelocity(1000);
              vx = velocityTracker.getXVelocity(activePointerId);
            }
            finishSwipe(vx);
          }
          if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
          }
          activePointerId = MotionEvent.INVALID_POINTER_ID;
          mode = MODE_NONE;
          pinching = false;
          wasPinching = false;
          break;
      }
      return true;
    }

    private float pointerSpan(MotionEvent event) {
      float dx = event.getX(1) - event.getX(0);
      float dy = event.getY(1) - event.getY(0);
      return (float) Math.hypot(dx, dy);
    }

    private float pointerFocusX(MotionEvent event) {
      return (event.getX(0) + event.getX(1)) * 0.5f;
    }

    private float pointerFocusY(MotionEvent event) {
      return (event.getY(0) + event.getY(1)) * 0.5f;
    }

    private void finishSwipe(float velocityX) {
      int width = Math.max(1, getWidth());
      float span = pageSpan();
      int target = currentIndex;
      if (dragOffset > 0 && currentIndex > 0) {
        if (dragOffset > width * 0.2f || (dragOffset > ui.dp(20) && velocityX > minFlingVelocity)) {
          target = currentIndex - 1;
        }
      } else if (dragOffset < 0 && currentIndex < items.size() - 1) {
        if (dragOffset < -width * 0.2f
            || (dragOffset < -ui.dp(20) && velocityX < -minFlingVelocity)) {
          target = currentIndex + 1;
        }
      }
      if (target != currentIndex) {
        int delta = target - currentIndex;
        dragOffset += delta * span;
        selectPage(target);
      }
      settleState.x = dragOffset;
      float maxV = Math.abs(dragOffset) / 0.08f;
      settleState.v = Math.max(-maxV, Math.min(maxV, velocityX));
      settleLoop.start();
    }
  }

  private ImageView whiteIcon(int res, String description, Runnable click) {
    ImageView button = ui.iconButton(res, description, click);
    button.setImageTintList(ColorStateList.valueOf(WHITE));
    return button;
  }

  private void buildChrome(FrameLayout stage) {
    top = ui.row();
    top.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x99000000, 0x00000000}));
    top.addView(whiteIcon(R.drawable.ic_arrow_back, "Back", this::finish));
    titleView = ui.label("", 16);
    titleView.setTextColor(WHITE);
    titleView.setSingleLine();
    titleView.setEllipsize(TextUtils.TruncateAt.END);
    top.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1));
    stage.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

    bottom = ui.column();
    bottom.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xb3000000, 0x00000000}));
    bottom.setClickable(true);
    controls = ui.row();
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
            if (fromUser && prepared && video != null) {
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

    actionsRow = ui.row();
    actionsRow.setPadding(0, ui.dp(4), 0, 0);
    bottom.addView(actionsRow);
    stage.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
  }

  private void updateChrome() {
    if (titleView == null) return;
    Item current = items.get(currentIndex);
    titleView.setText(current.title);
    if (current.isVideo()) {
      controls.setVisibility(View.VISIBLE);
      if (!prepared) {
        seek.setProgress(0);
        time.setText("0:00");
      } else {
        updatePosition();
      }
      updatePlayPause();
    } else {
      controls.setVisibility(View.GONE);
    }
    actionsRow.removeAllViews();
    if (entry != null) {
      actionsRow.setVisibility(View.VISIBLE);
      addAction(actionsRow, R.drawable.ic_share, "Share", () -> actions.share(entry));
      if (EntryActions.video(entry))
        addAction(actionsRow, R.drawable.ic_gif_box, "Make GIF", () -> actions.makeGif(entry));
      if (!entry.source.isEmpty())
        addAction(
            actionsRow, R.drawable.ic_open_in_new, "Original post", () -> actions.openSource(entry));
      addAction(actionsRow, R.drawable.ic_delete, "Delete", () -> actions.confirmRemove(entry, true));
    } else {
      actionsRow.setVisibility(View.GONE);
    }
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
    if (video == null) return;
    if (video.isPlaying()) {
      video.pause();
      handler.removeCallbacks(autoHide);
    } else play();
    updatePlayPause();
  }

  private void play() {
    if (video == null) return;
    video.start();
    updatePlayPause();
    handler.removeCallbacks(tick);
    handler.post(tick);
    scheduleHide();
  }

  private void updatePlayPause() {
    if (playPause == null) return;
    boolean playing = video != null && video.isPlaying();
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

  /** Test hooks. */
  int currentIndex() {
    return currentIndex;
  }

  int pageCount() {
    return items.size();
  }

  Uri currentUri() {
    return items.get(currentIndex).uri;
  }

  float currentScale() {
    Page page = pages.get(currentIndex);
    return page == null ? 1f : page.scale;
  }

  float currentPanX() {
    Page page = pages.get(currentIndex);
    return page == null ? 0f : page.tx;
  }

  float currentPanY() {
    Page page = pages.get(currentIndex);
    return page == null ? 0f : page.ty;
  }

  View stageView() {
    return pager;
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
    if (entry != null) state.putString("entry", entry.key);
    state.putInt("index", currentIndex);
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
    for (Page page : pages.values()) page.release();
    pages.clear();
    if (video != null) video.stopPlayback();
    super.onDestroy();
  }
}
