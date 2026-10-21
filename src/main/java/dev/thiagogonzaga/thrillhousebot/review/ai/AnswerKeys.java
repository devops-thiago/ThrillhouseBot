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

  static final String FINDINGS = "findings";
  static final String STATUSES = "previous_findings_status";
  private static final String ROOT = "";

  AnswerKeys {
    titles = List.copyOf(titles);
  }

  /**
   * One open bracket: an object, or an array with the key whose value it is ({@code ""} at root).
   */
  private record Frame(boolean array, String key) {}

  static AnswerKeys of(String body) {
    var frames = new ArrayDeque<Frame>();
    var titles = new ArrayList<String>();
    var statuses = 0;
    String lastKey = null;
    var titleValueNext = false;
    var i = 0;
    while (i < body.length()) {
      var c = body.charAt(i);
      if (c == '"') {
        var end = closingQuote(body, i + 1);
        if (end < 0) {
          return new AnswerKeys(titles, statuses, false);
        }
        var literal = unescaped(body.substring(i + 1, end));
        i = end + 1;
        if (isKey(body, i, frames)) {
          lastKey = literal;
          var element = elementOf(frames);
          titleValueNext = FINDINGS.equals(element) && "title".equals(literal);
          if (STATUSES.equals(element) && "status".equals(literal)) {
            statuses++;
          }
          continue;
        }
        if (titleValueNext) {
          titles.add(literal);
        }
        titleValueNext = false;
        continue;
      }
      if (c == '[') {
        var top = frames.peek();
        frames.push(new Frame(true, top == null ? ROOT : top.array() ? "" : lastKey));
      } else if (c == '{') {
        frames.push(new Frame(false, null));
      } else if (c == ']' || c == '}') {
        if (frames.poll() == null) {
          return new AnswerKeys(titles, statuses, false);
        }
      }
      if (!Character.isWhitespace(c) && c != ':') {
        titleValueNext = false;
      }
      i++;
    }
    return new AnswerKeys(titles, statuses, frames.isEmpty());
  }

  /** How many finding titles the scan found. */
  int titleCount() {
    return titles.size();
  }

  /** Whether the string just read is an object key: a colon follows it inside an object. */
  private static boolean isKey(String body, int from, Deque<Frame> frames) {
    var top = frames.peek();
    if (top == null || top.array()) {
      return false;
    }
    var i = from;
    while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
      i++;
    }
    return i < body.length() && body.charAt(i) == ':';
  }

  /**
   * The array key whose elements the current object is: {@link #FINDINGS} for an object directly
   * inside the findings array or a top-level array, {@link #STATUSES} for one inside the statuses
   * array, {@code null} otherwise.
   */
  private static String elementOf(Deque<Frame> frames) {
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
      return FINDINGS;
    }
    return FINDINGS.equals(parent.key()) || STATUSES.equals(parent.key()) ? parent.key() : null;
  }

  /** The index of the quote closing a string opened before {@code from}, or -1. */
  private static int closingQuote(String body, int from) {
    var i = from;
    while (i < body.length()) {
      var c = body.charAt(i);
      if (c == '\\') {
        i += 2;
      } else if (c == '"') {
        return i;
      } else {
        i++;
      }
    }
    return -1;
  }

  /** A string literal's content with its escapes resolved as far as a title needs. */
  private static String unescaped(String literal) {
    if (literal.indexOf('\\') < 0) {
      return literal;
    }
    var out = new StringBuilder(literal.length());
    var i = 0;
    while (i < literal.length()) {
      var c = literal.charAt(i);
      if (c == '\\' && i + 1 < literal.length()) {
        out.append(
            switch (literal.charAt(i + 1)) {
              case 'n' -> '\n';
              case 't' -> '\t';
              default -> literal.charAt(i + 1);
            });
        i += 2;
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }
}
