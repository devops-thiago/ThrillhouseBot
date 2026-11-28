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
package dev.thiagogonzaga.thrillhousebot.review;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches one line of a finding's content anchor (its persisted {@code suggestion_old}) against a
 * right-side diff line when the anchor carries a redaction (#932).
 *
 * <p>The security scan scrubs every credential literal out of a model finding's stored text, anchor
 * included ({@link SecurityScan.Scrubber}), so a finding that quoted a secret is persisted with
 * {@code [redacted: zKXq…, 40 chars]} where the literal stood. The diff still carries the literal,
 * so an exact comparison never finds that line again and the next round reads the finding as gone.
 * This matcher compares the redacted form instead: each marker stands for any literal the scrub
 * would have rewritten to that same marker — the shown prefix followed by the remaining characters
 * of the stated length, none of them whitespace or a quote — and the text around it must match
 * exactly. That is the diff line scrubbed the same way, judged without ever holding the literal:
 * the anchor keeps only what the scrub already let through, and no value is stored, logged, or
 * rebuilt here.
 *
 * <p>A line with no marker matches by plain equality, exactly as before. A malformed marker (a
 * length shorter than its own prefix) is left as literal text, so it matches only itself. The
 * matcher can accept a different value of the same prefix and length; that reads the finding as
 * present, the holding direction every presence check here leans to.
 */
final class RedactedAnchor {

  /**
   * The two forms the scrub writes: a matched or assigned value's {@link SecretScanner#redact}
   * form, whose shown prefix is never longer than {@link SecretScanner#REDACTED_PREFIX}, and the
   * fixed replacement for a private key's base64 body.
   */
  private static final Pattern MARKER =
      Pattern.compile(
          "\\[redacted: (.{0,"
              + SecretScanner.REDACTED_PREFIX
              + "}?)…, (\\d{1,9}) chars]|"
              + Pattern.quote(SecurityScan.PEM_MATERIAL_REDACTION));

  /** The shortest base64 run {@link SecurityScan} scrubs as private key material. */
  private static final int KEY_MATERIAL_MIN = 40;

  /** What no redacted value holds after its shown prefix: the scrub's whitespace, and a quote. */
  private static final String NOT_IN_A_VALUE = " \t\n\u000B\f\r\"'`";

  /** One piece of an anchor line, in the order the line holds them. */
  private sealed interface Piece {}

  /** Text that stands in the diff line as it stands in the anchor. */
  private record Text(String text) implements Piece {}

  /** A redacted value's characters after its shown prefix, counted as the scrub counted them. */
  private record Value(int characters) implements Piece {}

  /** Private key material: a base64 run of {@code min} characters or more. */
  private record KeyMaterial(long min) implements Piece {}

  private RedactedAnchor() {}

  /**
   * A matcher for one trimmed anchor line against a trimmed right-side diff line.
   *
   * <p>The anchor is the model's text, so it may hold any number of markers in any order. The diff
   * line is read once per piece of the anchor whatever those are: each piece takes the places the
   * pieces before it can end at and gives the places it can end at, so a line is never read again
   * for each way two markers could share a run of characters between them.
   */
  static Predicate<String> lineMatcher(String anchorLine) {
    var marker = MARKER.matcher(anchorLine);
    var pieces = new ArrayList<Piece>();
    int last = 0;
    while (marker.find()) {
      var shape = shapeOf(marker);
      if (shape != null) {
        addText(pieces, anchorLine.substring(last, marker.start()));
        if (shape instanceof Value) {
          addText(pieces, marker.group(1));
        }
        add(pieces, shape);
        last = marker.end();
      }
    }
    if (pieces.isEmpty()) {
      return anchorLine::equals;
    }
    addText(pieces, anchorLine.substring(last));
    return line -> matches(pieces, line);
  }

  /** What one marker stands for, or {@code null} when it cannot be a scrub's output. */
  private static Piece shapeOf(Matcher marker) {
    var length = marker.group(2);
    if (length == null) {
      return new KeyMaterial(KEY_MATERIAL_MIN);
    }
    int rest = Integer.parseInt(length) - marker.group(1).length();
    return rest < 0 ? null : new Value(rest);
  }

  /**
   * Adds text as a piece of its own, never joined to the text before it: a value's shown prefix
   * that starts on the second half of a pair does not make a whole character with a first half the
   * anchor holds before the marker.
   */
  private static void addText(List<Piece> pieces, String text) {
    if (!text.isEmpty()) {
      pieces.add(new Text(text));
    }
  }

  /** Key material right after key material is one run, as long as the two together or longer. */
  private static void add(List<Piece> pieces, Piece shape) {
    if (shape instanceof KeyMaterial more
        && !pieces.isEmpty()
        && pieces.getLast() instanceof KeyMaterial before) {
      pieces.set(pieces.size() - 1, new KeyMaterial(before.min() + more.min()));
    } else {
      pieces.add(shape);
    }
  }

  /** Whether {@code pieces}, one after the other, are the whole of {@code line}. */
  private static boolean matches(List<Piece> pieces, String line) {
    // Nearly every diff line is some other line, and where it starts or ends is enough to tell.
    if ((pieces.getFirst() instanceof Text first && !line.startsWith(first.text()))
        || (pieces.getLast() instanceof Text end && !line.endsWith(end.text()))) {
      return false;
    }
    // Where in the line the pieces read so far can end.
    var ends = new boolean[line.length() + 1];
    ends[0] = true;
    for (var piece : pieces) {
      var next = new boolean[line.length() + 1];
      boolean any =
          switch (piece) {
            case Text text -> afterText(line, text.text(), ends, next);
            case Value value -> afterValue(line, value.characters(), ends, next);
            case KeyMaterial material -> afterKeyMaterial(line, material.min(), ends, next);
          };
      if (!any) {
        return false;
      }
      ends = next;
    }
    return ends[line.length()];
  }

  /** Marks in {@code ends} where {@code text} ends when it starts at one of {@code starts}. */
  private static boolean afterText(String line, String text, boolean[] starts, boolean[] ends) {
    boolean any = false;
    for (int at = 0; at + text.length() <= line.length(); at++) {
      int end = at + text.length();
      // Half of a pair in the anchor is not the first half of a whole pair in the line.
      if (starts[at] && line.startsWith(text, at) && !insidePair(line, end)) {
        ends[end] = true;
        any = true;
      }
    }
    return any;
  }

  /** Whether {@code at}, where a text of one character or more ends, splits a pair. */
  private static boolean insidePair(String line, int at) {
    return at < line.length()
        && Character.isHighSurrogate(line.charAt(at - 1))
        && Character.isLowSurrogate(line.charAt(at));
  }

  /**
   * Marks in {@code ends} where a value of {@code characters} code points ends. A start and its end
   * move through the line together, that many characters apart, and the start counts when nothing
   * between the two is whitespace or a quote.
   */
  private static boolean afterValue(String line, int characters, boolean[] starts, boolean[] ends) {
    int end = 0;
    // Where the last character before the end that no value holds starts.
    int lastOther = -1;
    for (int read = 0; read < characters; read++) {
      if (end == line.length()) {
        return false;
      }
      lastOther = inAValue(line.codePointAt(end)) ? lastOther : end;
      end = line.offsetByCodePoints(end, 1);
    }
    boolean any = false;
    int start = 0;
    while (true) {
      if (starts[start] && lastOther < start) {
        ends[end] = true;
        any = true;
      }
      if (end == line.length()) {
        return any;
      }
      lastOther = inAValue(line.codePointAt(end)) ? lastOther : end;
      end = line.offsetByCodePoints(end, 1);
      start = line.offsetByCodePoints(start, 1);
    }
  }

  private static boolean inAValue(int codePoint) {
    return NOT_IN_A_VALUE.indexOf(codePoint) < 0;
  }

  /**
   * Marks in {@code ends} where key material ends: anywhere in a base64 run that is {@code min}
   * characters or more past the earliest start the run holds.
   */
  private static boolean afterKeyMaterial(String line, long min, boolean[] starts, boolean[] ends) {
    boolean any = false;
    int earliest = -1;
    for (int at = 0; at <= line.length(); at++) {
      if (at > 0 && !base64(line.charAt(at - 1))) {
        earliest = -1;
      }
      if (earliest < 0 && starts[at]) {
        earliest = at;
      }
      if (earliest >= 0 && at - earliest >= min) {
        ends[at] = true;
        any = true;
      }
    }
    return any;
  }

  private static boolean base64(char c) {
    return (c >= 'A' && c <= 'Z')
        || (c >= 'a' && c <= 'z')
        || (c >= '0' && c <= '9')
        || c == '+'
        || c == '/'
        || c == '=';
  }
}
