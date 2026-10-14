// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver;

import android.content.Context;
import org.still.saver.core.ErrorCode;
import org.still.saver.core.Platforms;
import org.still.saver.core.UserError;

/** Words what the core reports: error codes, platform summaries and link kinds. */
public final class CoreText {
  static final int[] LIMITS = {
    R.string.links_limit_public,
    R.string.links_limit_login,
    R.string.links_limit_streaming,
    R.string.links_limit_facebook_photos
  };

  private CoreText() {}

  /** The message for {@code error}, or {@code fallback} when it has none. */
  public static String message(Context c, Throwable error, int fallback) {
    if (error instanceof UserError) {
      UserError known = (UserError) error;
      Object[] args =
          known.code == ErrorCode.LINK_UNSUPPORTED
              ? new Object[] {Platforms.names(c.getString(R.string.list_or))}
              : known.args();
      return c.getString(message(known.code), args);
    }
    String detail = error.getMessage();
    return detail == null || detail.trim().isEmpty() ? c.getString(fallback) : detail;
  }

  /** For example "Instagram reel". */
  public static String label(Context c, Platforms.Match match) {
    return c.getString(kind(match.kind), match.platform.name);
  }

  /** What can be saved from {@code platform}, for example "Videos, GIFs, photos". */
  public static String summary(Context c, Platforms.Platform platform) {
    return c.getString(summary(platform));
  }

  static int summary(Platforms.Platform platform) {
    if (platform == Platforms.X) return R.string.links_x_summary;
    if (platform == Platforms.INSTAGRAM) return R.string.links_instagram_summary;
    if (platform == Platforms.TIKTOK) return R.string.links_tiktok_summary;
    if (platform == Platforms.FACEBOOK) return R.string.links_facebook_summary;
    if (platform == Platforms.PINTEREST) return R.string.links_pinterest_summary;
    if (platform == Platforms.THREADS) return R.string.links_threads_summary;
    if (platform == Platforms.BLUESKY) return R.string.links_bluesky_summary;
    if (platform == Platforms.YOUTUBE) return R.string.links_youtube_summary;
    throw new IllegalArgumentException(platform.name);
  }

  static int kind(Platforms.Kind kind) {
    switch (kind) {
      case POST:
        return R.string.home_match_post;
      case REEL:
        return R.string.home_match_reel;
      case VIDEO:
        return R.string.home_match_video;
      case SHORT:
        return R.string.home_match_short;
      case PHOTO_POST:
        return R.string.home_match_photo_post;
      case PIN:
        return R.string.home_match_pin;
      case MEDIA_LINK:
        return R.string.home_match_media_link;
      case LINK:
        return R.string.home_match_link;
    }
    throw new IllegalArgumentException(kind.name());
  }

  static int message(ErrorCode code) {
    switch (code) {
      case LINK_EMPTY:
        return R.string.error_link_empty;
      case LINK_TOO_LONG:
        return R.string.error_link_too_long;
      case LINK_NOT_HTTPS:
        return R.string.error_link_not_https;
      case LINK_UNSUPPORTED:
        return R.string.error_link_unsupported;
      case X_LINK_EMPTY:
        return R.string.error_x_link_empty;
      case X_LINK_UNSUPPORTED:
        return R.string.error_x_link_unsupported;
      case CANCELLED:
        return R.string.error_cancelled;
      case RATE_LIMITED:
        return R.string.error_rate_limited;
      case LOGIN_REQUIRED:
        return R.string.error_login_required;
      case UNAVAILABLE:
        return R.string.error_unavailable;
      case STATUS:
        return R.string.error_status;
      case PAGE_TOO_LARGE:
        return R.string.error_page_too_large;
      case EMBED_INVALID_URL:
        return R.string.error_embed_invalid_url;
      case EMBED_INVALID_REDIRECT:
        return R.string.error_embed_invalid_redirect;
      case EMBED_REDIRECTED_AWAY:
        return R.string.error_embed_redirected_away;
      case EMBED_TOO_MANY_REDIRECTS:
        return R.string.error_embed_too_many_redirects;
      case EMBED_TOO_LARGE:
        return R.string.error_embed_too_large;
      case EMBED_EMPTY:
        return R.string.error_embed_empty;
      case EMBED_STATUS:
        return R.string.error_embed_status;
      case EMBED_TIMEOUT:
        return R.string.error_embed_timeout;
      case SERVICE_UNREACHABLE:
        return R.string.error_service_unreachable;
      case SERVICE_EMPTY:
        return R.string.error_service_empty;
      case SERVICE_TOO_LARGE:
        return R.string.error_service_too_large;
      case SERVICE_UNREADABLE:
        return R.string.error_service_unreadable;
      case SERVICE_POST_UNAVAILABLE:
        return R.string.error_service_post_unavailable;
      case SERVICE_NO_MEDIA:
        return R.string.error_service_no_media;
      case SERVICE_RATE_LIMITED:
        return R.string.error_service_rate_limited;
      case SERVICE_PRIVATE:
        return R.string.error_service_private;
      case SERVICE_NOT_FOUND:
        return R.string.error_service_not_found;
      case SERVICE_DOWN:
        return R.string.error_service_down;
      case SERVICE_STATUS:
        return R.string.error_service_status;
      case SERVICE_NOT_HTTPS:
        return R.string.error_service_not_https;
      case SERVICE_INVALID_REDIRECT:
        return R.string.error_service_invalid_redirect;
      case SERVICE_UNSAFE_REDIRECT:
        return R.string.error_service_unsafe_redirect;
      case SERVICE_TOO_MANY_REDIRECTS:
        return R.string.error_service_too_many_redirects;
      case EXTRACTOR_EMPTY:
        return R.string.error_extractor_empty;
      case EXTRACTOR_UNREADABLE:
        return R.string.error_extractor_unreadable;
      case EXTRACTOR_TIMEOUT:
        return R.string.error_extractor_timeout;
      case EXTRACTOR_RATE_LIMITED:
        return R.string.error_extractor_rate_limited;
      case EXTRACTOR_LOGIN:
        return R.string.error_extractor_login;
      case EXTRACTOR_UNSUPPORTED:
        return R.string.error_extractor_unsupported;
      case EXTRACTOR_START_FAILED:
        return R.string.error_extractor_start_failed;
      case EXTRACTOR_FAILED:
        return R.string.error_extractor_failed;
      case NO_COMPLETE_VIDEO:
        return R.string.error_no_complete_video;
      case YOUTUBE_LIVE:
        return R.string.error_youtube_live;
      case YOUTUBE_NO_FORMAT:
        return R.string.error_youtube_no_format;
      case YOUTUBE_BOT_CHECK:
        return R.string.error_youtube_bot_check;
      case INSTAGRAM_LINK:
        return R.string.error_instagram_link;
      case INSTAGRAM_TOO_MANY_REDIRECTS:
        return R.string.error_instagram_too_many_redirects;
      case INSTAGRAM_REDIRECTED_AWAY:
        return R.string.error_instagram_redirected_away;
      case INSTAGRAM_LOGIN_WALL:
        return R.string.error_instagram_login_wall;
      case INSTAGRAM_LIMITED:
        return R.string.error_instagram_limited;
      case INSTAGRAM_UNEXPECTED:
        return R.string.error_instagram_unexpected;
      case INSTAGRAM_LOGIN_REQUIRED:
        return R.string.error_instagram_login_required;
      case INSTAGRAM_MISSING:
        return R.string.error_instagram_missing;
      case INSTAGRAM_NO_MEDIA:
        return R.string.error_instagram_no_media;
      case TIKTOK_SHORT_LINK_NO_POST:
        return R.string.error_tiktok_short_link_no_post;
      case TIKTOK_SHORT_LINK_AWAY:
        return R.string.error_tiktok_short_link_away;
      case TIKTOK_MISSING:
        return R.string.error_tiktok_missing;
      case TIKTOK_UNEXPECTED:
        return R.string.error_tiktok_unexpected;
      case TIKTOK_UNAVAILABLE:
        return R.string.error_tiktok_unavailable;
      case TIKTOK_NO_PHOTOS:
        return R.string.error_tiktok_no_photos;
      case FACEBOOK_NO_ID:
        return R.string.error_facebook_no_id;
      case FACEBOOK_NEEDS_ID:
        return R.string.error_facebook_needs_id;
      case FACEBOOK_EMBED_TOO_LARGE:
        return R.string.error_facebook_embed_too_large;
      case FACEBOOK_UNAVAILABLE:
        return R.string.error_facebook_unavailable;
      case FACEBOOK_NO_FILE:
        return R.string.error_facebook_no_file;
      case PINTEREST_NOT_PIN:
        return R.string.error_pinterest_not_pin;
      case PINTEREST_TOO_MANY_REDIRECTS:
        return R.string.error_pinterest_too_many_redirects;
      case PINTEREST_UNEXPECTED:
        return R.string.error_pinterest_unexpected;
      case PINTEREST_UNAVAILABLE:
        return R.string.error_pinterest_unavailable;
      case PINTEREST_NO_IMAGE:
        return R.string.error_pinterest_no_image;
      case THREADS_LINK:
        return R.string.error_threads_link;
      case THREADS_UNAVAILABLE:
        return R.string.error_threads_unavailable;
      case THREADS_NO_EMBED:
        return R.string.error_threads_no_embed;
      case THREADS_NO_MEDIA:
        return R.string.error_threads_no_media;
      case BLUESKY_LINK:
        return R.string.error_bluesky_link;
      case BLUESKY_UNEXPECTED:
        return R.string.error_bluesky_unexpected;
      case BLUESKY_NO_MEDIA:
        return R.string.error_bluesky_no_media;
      case BLUESKY_NO_PHOTO:
        return R.string.error_bluesky_no_photo;
    }
    throw new IllegalArgumentException(code.name());
  }
}
