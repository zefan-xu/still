// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.io.IOException;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A canonical public or private Telegram message link, independent of authentication state. */
public final class TelegramLink {
  private static final Pattern PUBLIC_PATH =
      Pattern.compile("/([A-Za-z][A-Za-z0-9_]{0,31})/([1-9][0-9]{0,19})/?");
  private static final Pattern PRIVATE_PATH =
      Pattern.compile("/c/([1-9][0-9]{0,19})/([1-9][0-9]{0,19})/?");

  public final String canonicalUrl;
  public final String channel;
  public final String messageId;
  public final boolean privateChannel;

  private TelegramLink(
      String canonicalUrl, String channel, String messageId, boolean privateChannel) {
    this.canonicalUrl = canonicalUrl;
    this.channel = channel;
    this.messageId = messageId;
    this.privateChannel = privateChannel;
  }

  /** The parsed Telegram message in {@code input}, or null when it names another platform. */
  public static TelegramLink from(String input) {
    try {
      String url = PublicLinkParser.parse(input);
      URI uri = URI.create(url);
      if (!"t.me".equalsIgnoreCase(uri.getHost())) return null;
      Matcher privatePost = PRIVATE_PATH.matcher(uri.getRawPath());
      if (privatePost.matches())
        return new TelegramLink(url, privatePost.group(1), privatePost.group(2), true);
      Matcher publicPost = PUBLIC_PATH.matcher(uri.getRawPath());
      if (publicPost.matches())
        return new TelegramLink(url, publicPost.group(1), publicPost.group(2), false);
      return null;
    } catch (IOException | IllegalArgumentException ignored) {
      return null;
    }
  }

  /** WebK's hash route for this message, used only inside the authenticated WebView. */
  public String webHash() {
    return (privateChannel ? "#-" + channel : "#@" + channel) + "?post=" + messageId;
  }

  /** A stable, privacy-safe filename stem for the saved video. */
  public String filenameStem() {
    return "telegram_" + channel.replaceAll("[^A-Za-z0-9_-]", "_") + "_" + messageId;
  }
}
