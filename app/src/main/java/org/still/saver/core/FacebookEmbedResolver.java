// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.io.IOException;
import java.net.URLEncoder;

/** Fallback for MP4s Facebook exposes through its documented anonymous video embed. */
public final class FacebookEmbedResolver {
  private final MediaResolver.Transport transport;

  public FacebookEmbedResolver() {
    this(new PublicEmbedTransport());
  }

  public FacebookEmbedResolver(MediaResolver.Transport transport) {
    if (transport == null) throw new IllegalArgumentException("A transport is required");
    this.transport = transport;
  }

  public Post resolve(String input) throws IOException {
    String source = PublicLinkParser.parse(input);
    if (FacebookEmbedParser.videoId(source) == null)
      throw new IOException("This Facebook embed needs a video post with a numeric ID.");
    String embed =
        "https://www.facebook.com/plugins/video.php?href="
            + URLEncoder.encode(source, "UTF-8")
            + "&show_text=0&width=500";
    MediaResolver.Response response = transport.get(embed);
    PublicEmbedTransport.checkBody(response, "Facebook");
    return FacebookEmbedParser.parse(response.body, source);
  }
}
