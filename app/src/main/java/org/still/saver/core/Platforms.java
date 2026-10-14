// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The supported platforms in display order: names, hosts, media kinds and link shapes. */
public final class Platforms {
  public static final class Platform {
    public final String name;
    /** What can be saved, for example "Videos, GIFs, photos". */
    public final String kinds;
    /** Link shapes without the scheme, for example "instagram.com/p/…". */
    public final List<String> examples;

    private final List<String> hosts;

    private Platform(String name, String kinds, List<String> hosts, String... examples) {
      this.name = name;
      this.kinds = kinds;
      this.hosts = hosts;
      this.examples = Collections.unmodifiableList(Arrays.asList(examples));
    }

    boolean owns(String host) {
      for (String h : hosts) if (host.equals(h) || host.endsWith("." + h)) return true;
      return this == PINTEREST && PublicLinkParser.isPinterestHost(host);
    }
  }

  /** A link that Still can read. */
  public static final class Match {
    public final Platform platform;
    /** What the link points at: "post", "reel", "video", "Short", "photo post", "pin" or "link". */
    public final String kind;
    /** The canonical link the resolvers receive. */
    public final String url;

    private Match(Platform platform, String kind, String url) {
      this.platform = platform;
      this.kind = kind;
      this.url = url;
    }

    /** For example "Instagram reel". */
    public String label() {
      return platform.name + " " + kind;
    }
  }

  public static final Platform X =
      new Platform(
          "X",
          "Videos, GIFs, photos",
          Arrays.asList(
              "x.com", "twitter.com", "fxtwitter.com", "fixupx.com", "vxtwitter.com", "twimg.com"),
          "x.com/…/status/…",
          "twitter.com/…/status/…");
  public static final Platform INSTAGRAM =
      new Platform(
          "Instagram",
          "Posts, reels, carousels",
          Collections.singletonList("instagram.com"),
          "instagram.com/p/…",
          "/reel/…",
          "/tv/…");
  public static final Platform TIKTOK =
      new Platform(
          "TikTok",
          "Videos, photo slideshows",
          Collections.singletonList("tiktok.com"),
          "tiktok.com/@…/video/…",
          "/photo/…",
          "vm.tiktok.com/…");
  public static final Platform FACEBOOK =
      new Platform(
          "Facebook",
          "Videos only",
          Arrays.asList("facebook.com", "fb.watch"),
          "facebook.com/watch?v=…",
          "/reel/…",
          "fb.watch/…");
  public static final Platform PINTEREST =
      new Platform(
          "Pinterest",
          "Pins (video, photo)",
          Arrays.asList("pinterest.com", "pin.it"),
          "pinterest.com/pin/…",
          "pin.it/…");
  public static final Platform THREADS =
      new Platform(
          "Threads",
          "Photos, videos",
          Arrays.asList("threads.net", "threads.com"),
          "threads.net/@…/post/…");
  public static final Platform BLUESKY =
      new Platform(
          "Bluesky",
          "Photos, videos",
          Collections.singletonList("bsky.app"),
          "bsky.app/profile/…/post/…");
  public static final Platform YOUTUBE =
      new Platform(
          "YouTube",
          "Videos, Shorts (up to 4K)",
          Arrays.asList("youtube.com", "youtu.be"),
          "youtube.com/watch?v=…",
          "/shorts/…",
          "youtu.be/…");

  public static final List<Platform> ALL =
      Collections.unmodifiableList(
          Arrays.asList(X, INSTAGRAM, TIKTOK, FACEBOOK, PINTEREST, THREADS, BLUESKY, YOUTUBE));

  /** What Still cannot read, in the words the app shows. */
  public static final List<String> LIMITS =
      Collections.unmodifiableList(
          Arrays.asList(
              "Public posts only",
              "No login-required or private posts",
              "No streaming-only video",
              "No Facebook photos"));

  private Platforms() {}

  /** "X, Instagram, … Bluesky and YouTube", with {@code last} before the final name. */
  public static String names(String last) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < ALL.size(); i++) {
      if (i > 0) out.append(i == ALL.size() - 1 ? " " + last + " " : ", ");
      out.append(ALL.get(i).name);
    }
    return out.toString();
  }

  /** The platform and kind of the first supported link in {@code text}, or null. */
  public static Match detect(String text) {
    String url;
    try {
      url = PublicLinkParser.parse(text);
    } catch (IOException unsupported) {
      return null;
    }
    URI uri = URI.create(url);
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    String path = uri.getRawPath() == null ? "" : uri.getRawPath();
    for (Platform platform : ALL)
      if (platform.owns(host)) return new Match(platform, kind(platform, host, path), url);
    return null;
  }

  private static String kind(Platform platform, String host, String path) {
    if (platform == X) return host.endsWith("twimg.com") ? "media link" : "post";
    if (platform == INSTAGRAM)
      return path.matches(".*/reels?/.*") ? "reel" : path.contains("/tv/") ? "video" : "post";
    if (platform == TIKTOK)
      return path.contains("/video/") ? "video" : path.contains("/photo/") ? "photo post" : "link";
    if (platform == FACEBOOK) {
      if (path.startsWith("/reel/") || path.startsWith("/share/r/")) return "reel";
      if (host.endsWith("fb.watch")
          || path.startsWith("/watch")
          || path.startsWith("/share/v/")
          || path.contains("video")) return "video";
      return "post";
    }
    if (platform == PINTEREST) return host.endsWith("pin.it") ? "link" : "pin";
    if (platform == YOUTUBE) return path.startsWith("/shorts/") ? "Short" : "video";
    return "post";
  }
}
