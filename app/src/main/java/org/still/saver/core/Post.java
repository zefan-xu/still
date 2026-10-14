// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Post {
  public final String id;
  public final String title;
  public final String author;
  public final String sourceUrl;
  public final List<Media> media;

  public Post(String id, String title, String author, String sourceUrl, List<Media> media) {
    this.id = id;
    this.title = title;
    this.author = author;
    this.sourceUrl = sourceUrl;
    this.media = Collections.unmodifiableList(new ArrayList<>(media));
  }
}
