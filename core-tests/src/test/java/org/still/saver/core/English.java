// SPDX-License-Identifier: GPL-3.0-or-later
package org.still.saver.core;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** The app's English strings, so tests read core errors as the app shows them. */
final class English {
  private static final Map<String, String> STRINGS = load();

  private English() {}

  /** What the app shows for {@code error}; other exceptions keep their own message. */
  static String message(Throwable error) {
    if (!(error instanceof UserError)) return error.getMessage();
    UserError known = (UserError) error;
    Object[] args =
        known.code == ErrorCode.LINK_UNSUPPORTED
            ? new Object[] {Platforms.names("or")}
            : known.args();
    return format(name(known.code), args);
  }

  /** For example "Instagram reel". */
  static String label(Platforms.Match match) {
    return format("home_match_" + match.kind.name().toLowerCase(Locale.ROOT), match.platform.name);
  }

  static String name(ErrorCode code) {
    return "error_" + code.name().toLowerCase(Locale.ROOT);
  }

  static String string(String name) {
    String value = STRINGS.get(name);
    if (value == null) throw new AssertionError("strings.xml has no " + name);
    return value;
  }

  static String format(String name, Object... args) {
    return String.format(Locale.ROOT, string(name), args);
  }

  private static Map<String, String> load() {
    try (InputStream in = English.class.getResourceAsStream("/strings.xml")) {
      NodeList nodes =
          DocumentBuilderFactory.newInstance()
              .newDocumentBuilder()
              .parse(in)
              .getElementsByTagName("string");
      Map<String, String> strings = new HashMap<>();
      for (int i = 0; i < nodes.getLength(); i++) {
        Element node = (Element) nodes.item(i);
        strings.put(node.getAttribute("name"), unescape(node.getTextContent()));
      }
      return strings;
    } catch (Exception e) {
      throw new IllegalStateException("Could not read strings.xml", e);
    }
  }

  private static String unescape(String raw) {
    String text = raw.trim().replaceAll("\\s+", " ");
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c != '\\' || i + 1 == text.length()) {
        out.append(c);
        continue;
      }
      char next = text.charAt(++i);
      out.append(next == 'n' ? '\n' : next == 't' ? '\t' : next);
    }
    return out.toString();
  }
}
