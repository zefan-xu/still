// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import android.content.Context;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.still.saver.DeviceVideoSupport;
import org.still.saver.ExtractorRuntime;

/** Resolves supported public posts. Call on a background thread; extraction may take a minute. */
public final class PublicPageResolver {
  private final Context context;
  private final MediaResolver twitter;

  public PublicPageResolver(Context context, String apiBase) {
    this.context = context.getApplicationContext();
    this.twitter = new MediaResolver(apiBase);
  }

  public Post resolve(String input) throws IOException {
    String sourceUrl = PublicLinkParser.parse(input);
    TelegramLink telegram = TelegramLink.from(sourceUrl);
    if (telegram != null && telegram.privateChannel)
      throw new UserError(ErrorCode.TELEGRAM_AUTH_REQUIRED);
    String sourceHost = java.net.URI.create(sourceUrl).getHost();
    if ("threads.com".equals(sourceHost)
        || "www.threads.com".equals(sourceHost)
        || "threads.net".equals(sourceHost)
        || "www.threads.net".equals(sourceHost))
      return new ThreadsEmbedResolver().resolve(sourceUrl);
    // The bundled extractor reads only videos. Instagram's post page lists every item; the other
    // platforms are asked what the post holds, and only videos continue to the extractor.
    if (InstagramResolver.handles(sourceUrl)) return new InstagramResolver().resolve(sourceUrl);
    if (BlueskyResolver.handles(sourceUrl)) {
      Post photos = new BlueskyResolver().resolve(sourceUrl);
      if (photos != null) return photos;
    }
    if (PinterestResolver.handles(sourceUrl)) {
      Post photos = new PinterestResolver().resolve(sourceUrl);
      if (photos != null) return photos;
    }
    if (TikTokPhotoResolver.handles(sourceUrl)) {
      TikTokPhotoResolver tiktok = new TikTokPhotoResolver();
      String photoLink = tiktok.photoLink(sourceUrl);
      if (photoLink != null) return tiktok.resolve(photoLink);
    }
    IOException twitterError = null;
    LinkParser.Parsed twitterLink = null;
    try {
      twitterLink = LinkParser.parse(input);
    } catch (IOException ignored) {
      /* Another supported public platform. */
    }
    if (twitterLink != null) {
      try {
        return twitter.resolve(input);
      } catch (IOException e) {
        twitterError = e;
        if (twitterLink.directUrl != null) throw e;
      }
    }
    try {
      return extract(sourceUrl);
    } catch (IOException error) {
      if (Thread.currentThread().isInterrupted()) throw error;
      if (FacebookEmbedParser.videoId(sourceUrl) != null) {
        try {
          return new FacebookEmbedResolver().resolve(sourceUrl);
        } catch (FacebookEmbedParser.UnavailableException unavailable) {
          unavailable.addSuppressed(error);
          throw unavailable;
        } catch (IOException embedError) {
          error.addSuppressed(embedError);
        }
      }
      if (twitterError != null) error.addSuppressed(twitterError);
      throw error;
    }
  }

  private Post extract(String sourceUrl) throws IOException {
    final String processId = "still-" + UUID.randomUUID();
    ExecutorService executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "still-extractor");
              thread.setDaemon(true);
              return thread;
            });
    Future<String> extraction =
        executor.submit(
            () -> {
              YoutubeDLRequest request = new YoutubeDLRequest(sourceUrl);
              request.addOption("--ignore-config");
              request.addOption("--dump-single-json");
              request.addOption("--skip-download");
              request.addOption(wantsPlaylist(sourceUrl) ? "--yes-playlist" : "--no-playlist");
              request.addOption("--playlist-end", 20);
              request.addOption("--socket-timeout", 15);
              request.addOption("--retries", 1);
              request.addOption("--extractor-retries", 1);
              request.addOption("--no-warnings");
              YoutubeDLResponse response = ExtractorRuntime.execute(context, request, processId);
              return response.getOut();
            });
    try {
      return ExtractorJsonParser.parse(
          extraction.get(90, TimeUnit.SECONDS), sourceUrl, DeviceVideoSupport.get());
    } catch (TimeoutException e) {
      extraction.cancel(true);
      YoutubeDL.INSTANCE.destroyProcessById(processId);
      throw new UserError(e, ErrorCode.EXTRACTOR_TIMEOUT);
    } catch (InterruptedException e) {
      extraction.cancel(true);
      YoutubeDL.INSTANCE.destroyProcessById(processId);
      Thread.currentThread().interrupt();
      throw new UserError(e, ErrorCode.CANCELLED);
    } catch (ExecutionException e) {
      throw extractionError(e.getCause());
    } finally {
      executor.shutdownNow();
    }
  }

  /** Telegram message links may contain an album, while the other extractors stay single-post. */
  static boolean wantsPlaylist(String sourceUrl) {
    java.net.URI uri = java.net.URI.create(sourceUrl);
    return "t.me".equalsIgnoreCase(uri.getHost());
  }

  private static IOException extractionError(Throwable cause) {
    String message =
        cause == null || cause.getMessage() == null
            ? ""
            : cause.getMessage().toLowerCase(Locale.ROOT);
    if (message.contains("429") || message.contains("rate limit")) {
      return new UserError(cause, ErrorCode.EXTRACTOR_RATE_LIMITED);
    }
    if (message.contains("not a bot")) {
      return new UserError(cause, ErrorCode.YOUTUBE_BOT_CHECK);
    }
    if (message.contains("login")
        || message.contains("log in")
        || message.contains("sign in")
        || message.contains("cookie")
        || message.contains("private")
        || message.contains("403")) {
      return new UserError(cause, ErrorCode.EXTRACTOR_LOGIN);
    }
    if (message.contains("unsupported url"))
      return new UserError(cause, ErrorCode.EXTRACTOR_UNSUPPORTED);
    if (cause instanceof LinkageError
        || message.contains("initialize")
        || message.contains("permission denied")) {
      return new UserError(cause, ErrorCode.EXTRACTOR_START_FAILED);
    }
    return new UserError(cause, ErrorCode.EXTRACTOR_FAILED);
  }
}
