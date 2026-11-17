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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The finding titles and status entries a failed review answer holds, read by a tolerant JSON scan
 * rather than a parser, because the answer is one the parser refused (#1079). Only the keys of an
 * object directly inside the top-level object's {@code findings} array (or a top-level array, #960)
 * count as titles, and only those directly inside its {@code previous_findings_status} count as
 * statuses, so JSON quoted in a description, a nested object with its own {@code title}, or an
 * array of either name nested deeper counts for nothing. Trailing commas, prose between documents
 * and several documents are read through.
 *
 * <p>Each such element's own string and number fields are kept too, in order ({@link #findings},
 * {@link #statusEntries}), so a repaired answer can be held to the file, line and id the failed
 * answer wrote (#1322).
 *
 * <p>A scan that ends inside a string or with a bracket left open lost track of the structure (a
 * quote left unescaped can do that); it reports itself not {@link #readable()}, and the caller does
 * not trust what it read.
 */
record AnswerKeys(
    List<String> titles,
    int statuses,
    boolean readable,
    List<Map<String, String>> findings,
    List<Map<String, String>> statusEntries) {

  static final String FINDINGS_KEY = "findings";
  static final String STATUSES_KEY = "previous_findings_status";
  private static final String ROOT = "";

  AnswerKeys {
    titles = List.copyOf(titles);
    findings = findings.stream().map(Map::copyOf).toList();
    statusEntries = statusEntries.stream().map(Map::copyOf).toList();
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
    private final List<Map<String, String>> findings = new ArrayList<>();
    private final List<Map<String, String>> statusEntries = new ArrayList<>();
    private int statuses;
    private String lastKey;
    private boolean titleValueNext;
    private int at;

    /** The fields of the findings or statuses element open now, or {@code null}. */
    private Map<String, String> element;

    /** Where {@link #element} goes when it closes. */
    private List<Map<String, String>> elementList;

    /** How many brackets are open while {@link #element} is the innermost one. */
    private int elementDepth;

    /** The key of {@link #element} whose value comes next, or {@code null}. */
    private String valueKey;

    Scan(String body) {
      this.body = body;
    }

    AnswerKeys run() {
      var lost = false;
      while (at < body.length() && !lost) {
        var c = body.charAt(at);
        if (c == '"') {
          lost = !string();
        } else if (valueKey != null && (c == '-' || Character.isDigit(c))) {
          number();
        } else {
          lost = !punctuation(c);
        }
      }
      return new AnswerKeys(titles, statuses, !lost && frames.isEmpty(), findings, statusEntries);
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
        if (valueKey != null) {
          element.put(valueKey, literal);
        }
        titleValueNext = false;
        valueKey = null;
      }
      return true;
    }

    /** Reads the number at {@link #at} as the value of {@link #valueKey}. */
    private void number() {
      var start = at;
      while (at < body.length() && isNumberChar(body.charAt(at))) {
        at++;
      }
      element.put(valueKey, body.substring(start, at));
      valueKey = null;
      titleValueNext = false;
    }

    private static boolean isNumberChar(char c) {
      return Character.isDigit(c) || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E';
    }

    private void key(String literal) {
      lastKey = literal;
      var kind = elementOf();
      titleValueNext = FINDINGS_KEY.equals(kind) && "title".equals(literal);
      if (STATUSES_KEY.equals(kind) && "status".equals(literal)) {
        statuses++;
      }
      valueKey = element != null && frames.size() == elementDepth ? literal : null;
    }

    /** Reads one character outside a string; false on a bracket that closes nothing. */
    private boolean punctuation(char c) {
      at++;
      if (c == '[') {
        frames.push(new Frame(true, arrayKey()));
      } else if (c == '{') {
        frames.push(new Frame(false, null));
        openElement();
      } else if (c == ']' || c == '}') {
        closeElement();
        if (frames.poll() == null) {
          return false;
        }
      }
      if (!Character.isWhitespace(c) && c != ':') {
        titleValueNext = false;
        valueKey = null;
      }
      return true;
    }

    /** Starts recording the object just opened when it is a findings or statuses element. */
    private void openElement() {
      var kind = elementOf();
      if (kind == null) {
        return;
      }
      element = new HashMap<>();
      elementList = FINDINGS_KEY.equals(kind) ? findings : statusEntries;
      elementDepth = frames.size();
    }

    /** Keeps the element being recorded when the bracket about to close is its own. */
    private void closeElement() {
      if (element != null && frames.size() == elementDepth) {
        elementList.add(element);
        element = null;
      }
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
     * directly inside a top-level array, or inside the findings array of a top-level object; {@link
     * #STATUSES_KEY} for one inside the statuses array of a top-level object; {@code null}
     * otherwise. An array of the same name nested anywhere deeper (a finding quoting an example
     * answer) counts for nothing.
     */
    private String elementOf() {
      var path = List.copyOf(frames);
      if (path.size() < 2 || !path.get(1).array()) {
        return null;
      }
      var key = path.get(1).key();
      if (path.size() == 2) {
        // The array is the document itself: a bare array of findings (#960).
        return FINDINGS_KEY;
      }
      var named = FINDINGS_KEY.equals(key) || STATUSES_KEY.equals(key);
      return path.size() == 3 && named ? key : null;
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

    /**
     * A closed string literal's content with its escapes resolved as far as a title needs. A closed
     * literal never ends in a lone backslash: {@link #closingQuote} skips the character after each
     * one.
     */
    static String unescaped(String literal) {
      var out = new StringBuilder(literal.length());
      var i = 0;
      while (i < literal.length()) {
        var c = literal.charAt(i);
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
