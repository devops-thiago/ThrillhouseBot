/*
 * Copyright 2026 Thiago Gonzaga
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.thiagogonzaga.thrillhousebot.review.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The finding titles and status entries a failed review answer holds, read by a tolerant JSON scan
 * rather than a parser, because the answer is one the parser refused (#1079). Only the keys of an
 * object directly inside the {@code findings} array (or a top-level array, #960) count as titles,
 * and only those directly inside {@code previous_findings_status} count as statuses, so JSON quoted
 * in a description, or a nested object with its own {@code title}, counts for nothing. Trailing
 * commas, prose between documents and several documents are read through.
 *
 * <p>A scan that ends inside a string or with a bracket left open lost track of the structure (a
 * quote left unescaped can do that); it reports itself not {@link #readable()}, and the caller
 * falls back to a stricter reading.
 */
record AnswerKeys(List<String> titles, int statuses, boolean readable) {

  static final String FINDINGS_KEY = "findings";
  static final String STATUSES_KEY = "previous_findings_status";
  private static final String ROOT = "";

  AnswerKeys {
    titles = List.copyOf(titles);
  }

  static AnswerKeys of(String body) {
    return new Scan(body).run();
  }

  /** How many finding titles the scan found. */
  int titleCount() {
    return titles.size();
  }

  /**
   * One open bracket: an object, or an array with the key whose value it is ({@code ""} at root).
   */
  private record Frame(boolean array, String key) {}

  /** One pass over an answer; not reused. */
  private static final class Scan {

    private final String body;
    private final Deque<Frame> frames = new ArrayDeque<>();
    private final List<String> titles = new ArrayList<>();
    private int statuses;
    private String lastKey;
    private boolean titleValueNext;
    private int at;

    Scan(String body) {
      this.body = body;
    }

    AnswerKeys run() {
      var lost = false;
      while (at < body.length() && !lost) {
        var c = body.charAt(at);
        lost = c == '"' ? !string() : !punctuation(c);
      }
      return new AnswerKeys(titles, statuses, !lost && frames.isEmpty());
    }

    /** Reads the string at {@link #at}; false when it never closes. */
    private boolean string() {
      var end = closingQuote(at + 1);
      if (end < 0) {
        return false;
      }
      var literal = unescaped(body.substring(at + 1, end));
      at = end + 1;
      if (isKey()) {
        key(literal);
      } else {
        if (titleValueNext) {
          titles.add(literal);
        }
        titleValueNext = false;
      }
      return true;
    }

    private void key(String literal) {
      lastKey = literal;
      var element = elementOf();
      titleValueNext = FINDINGS_KEY.equals(element) && "title".equals(literal);
      if (STATUSES_KEY.equals(element) && "status".equals(literal)) {
        statuses++;
      }
    }

    /** Reads one character outside a string; false on a bracket that closes nothing. */
    private boolean punctuation(char c) {
      at++;
      if (c == '[') {
        frames.push(new Frame(true, arrayKey()));
      } else if (c == '{') {
        frames.push(new Frame(false, null));
      } else if ((c == ']' || c == '}') && frames.poll() == null) {
        return false;
      }
      if (!Character.isWhitespace(c) && c != ':') {
        titleValueNext = false;
      }
      return true;
    }

    /** The key an array opened now is the value of: the last key in an object, else none. */
    private String arrayKey() {
      var top = frames.peek();
      if (top == null) {
        return ROOT;
      }
      return top.array() ? "" : lastKey;
    }

    /** Whether the string just read is an object key: a colon follows it inside an object. */
    private boolean isKey() {
      var top = frames.peek();
      if (top == null || top.array()) {
        return false;
      }
      var i = at;
      while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
        i++;
      }
      return i < body.length() && body.charAt(i) == ':';
    }

    /**
     * The array key whose elements the current object is: {@link #FINDINGS_KEY} for an object
     * directly inside the findings array or a top-level array, {@link #STATUSES_KEY} for one inside
     * the statuses array, {@code null} otherwise.
     */
    private String elementOf() {
      var iterator = frames.iterator();
      iterator.next();
      if (!iterator.hasNext()) {
        return null;
      }
      var parent = iterator.next();
      if (!parent.array()) {
        return null;
      }
      if (ROOT.equals(parent.key()) && !iterator.hasNext()) {
        return FINDINGS_KEY;
      }
      return FINDINGS_KEY.equals(parent.key()) || STATUSES_KEY.equals(parent.key())
          ? parent.key()
          : null;
    }

    /** The index of the quote closing a string opened before {@code from}, or -1. */
    private int closingQuote(int from) {
      var i = from;
      while (i < body.length()) {
        var c = body.charAt(i);
        if (c == '"') {
          return i;
        }
        i += c == '\\' ? 2 : 1;
      }
      return -1;
    }

    /** A string literal's content with its escapes resolved as far as a title needs. */
    private static String unescaped(String literal) {
      var out = new StringBuilder(literal.length());
      var i = 0;
      while (i < literal.length()) {
        var c = literal.charAt(i);
        // A literal never ends on a lone backslash: closingQuote skips the character after one.
        if (c == '\\') {
          out.append(escaped(literal.charAt(i + 1)));
          i += 2;
        } else {
          out.append(c);
          i++;
        }
      }
      return out.toString();
    }

    private static char escaped(char code) {
      return switch (code) {
        case 'n' -> '\n';
        case 't' -> '\t';
        default -> code;
      };
    }
  }
}
