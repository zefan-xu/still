// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/** Photos and complete MP4s rendered by the ordinary public Threads embed, without a session. */
public final class ThreadsEmbedResolver {
  private final MediaResolver.Transport transport;

  public ThreadsEmbedResolver() {
    this(new PublicEmbedTransport());
  }

  public ThreadsEmbedResolver(MediaResolver.Transport transport) {
    if (transport == null) throw new IllegalArgumentException("A transport is required");
    this.transport = transport;
  }

  public Post resolve(String input) throws IOException {
    String source = canonical(input);
    MediaResolver.Response response = transport.get(source + "/embed");
    PublicEmbedTransport.checkBody(response, "Threads");
    return parse(response.body, source);
  }

  static Post parse(String html, String input) throws IOException {
    String source = canonical(input);
    String path = URI.create(source).getPath();
    String[] segments = path.split("/");
    String id = segments[3];
    String author = segments[1];
    Document document = Jsoup.parse(html, source);
    boolean matching = false;
    for (Element anchor : document.select(".LinkContainer a[href]")) {
      try {
        if (source.equals(canonical(anchor.attr("href")))) matching = true;
      } catch (IOException ignored) {
        /* Only the requested post can identify its embed. */
      }
    }
    Elements targets = document.select(".OuterContainerFull");
    if (!matching || targets.size() != 1) {
      if (document.body().text().toLowerCase(Locale.ROOT).contains("thread not available")) {
        throw new UserError(ErrorCode.THREADS_UNAVAILABLE);
      }
      throw new UserError(ErrorCode.THREADS_NO_EMBED);
    }
    Element target = targets.first();
    List<Media> media = new ArrayList<>();
    Set<String> attached = new HashSet<>();
    // Videos and photos in the order the post shows them; avatars and badges sit elsewhere.
    for (Element element :
        target.select("video, .SingleInnerMediaContainer img, .MediaScrollImageContainer img")) {
      if (element.tagName().equals("img")) {
        if (element.parents().is("video")) continue;
        String url = element.attr("src"); // Jsoup decodes attribute entities once.
        String mimeType = photoType(url);
        if (mimeType != null && attached.add(URI.create(url).getPath())) {
          Variant variant = new Variant(url, "Original image", mimeType, 0, 0, 0);
          String preview = LinkParser.safePreviewUri(url) == null ? null : url;
          media.add(new Media("photo", java.util.Collections.singletonList(variant), preview, 0));
        }
      } else {
        List<Variant> variants = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        add(element, variants, seen);
        for (Element sourceTag : element.select("source[src]")) {
          add(sourceTag, variants, seen);
          if (variants.size() >= 20) break;
        }
        if (!variants.isEmpty() && attached.add(URI.create(variants.get(0).url).getPath()))
          media.add(new Media("video", variants, poster(element), 0));
      }
      if (media.size() >= 20) break;
    }
    if (media.isEmpty())
      throw new UserError(ErrorCode.THREADS_NO_MEDIA);
    Element caption = target.selectFirst(".TextContentContainerFull .BodyTextContainer");
    String title = caption == null ? "" : caption.text().trim();
    if (title.isEmpty()) title = "Threads post by " + author;
    if (title.length() > 1000) title = title.substring(0, 1000);
    return new Post(id, title, author, source, media);
  }

  /** The player's poster frame, from the same CDNs that serve the video. */
  private static String poster(Element video) {
    String value = video.attr("poster"); // Jsoup decodes attribute entities once.
    URI uri = LinkParser.safePreviewUri(value);
    if (uri == null) return null;
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    return host.endsWith(".cdninstagram.com") || host.endsWith(".fbcdn.net") ? value : null;
  }

  /** Post photos come from the same CDNs as the post's videos. */
  private static String photoType(String value) {
    URI uri = LinkParser.safeHttpsUri(value);
    if (uri == null || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    if (!(host.endsWith(".cdninstagram.com") || host.endsWith(".fbcdn.net"))) return null;
    String path = uri.getPath().toLowerCase(Locale.ROOT);
    if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
    if (path.endsWith(".webp")) return "image/webp";
    if (path.endsWith(".png")) return "image/png";
    return null;
  }

  private static void add(Element element, List<Variant> variants, Set<String> seen) {
    String value = element.attr("src"); // Jsoup decodes attribute entities once.
    URI uri = LinkParser.safeHttpsUri(value);
    if (uri == null || (uri.getPort() != -1 && uri.getPort() != 443)) return;
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    if (!(host.endsWith(".cdninstagram.com") || host.endsWith(".fbcdn.net"))) return;
    if (!uri.getPath().toLowerCase(Locale.ROOT).endsWith(".mp4")) return;
    String type = element.attr("type");
    if (!type.isEmpty() && !"video/mp4".equalsIgnoreCase(type)) return;
    if (seen.add(value)) variants.add(new Variant(value, "MP4", "video/mp4", 0, 0, 0));
  }

  static String canonical(String input) throws IOException {
    String parsed = PublicLinkParser.parse(input);
    URI uri = URI.create(parsed);
    String host = uri.getHost();
    if (!("threads.com".equals(host)
        || "www.threads.com".equals(host)
        || "threads.net".equals(host)
        || "www.threads.net".equals(host)))
      throw new UserError(ErrorCode.THREADS_LINK);
    String path = uri.getRawPath();
    if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
    int authorEnd = path.indexOf('/', 1);
    path = path.substring(0, authorEnd).toLowerCase(Locale.ROOT) + path.substring(authorEnd);
    return "https://www.threads.com" + path;
  }
}
