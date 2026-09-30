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
 * pattern can accept a different value of the same prefix and length; that reads the finding as
 * present, the holding direction every presence check here leans to.
 */
final class RedactedAnchor {

  /**
   * The two forms the scrub writes: a matched or assigned value's {@link SecretScanner#redact}
   * form, and the fixed replacement for a private key's base64 body.
   */
  private static final Pattern MARKER =
      Pattern.compile(
          "\\[redacted: (.{0,4}?)…, (\\d{1,4}) chars]|"
              + Pattern.quote(SecurityScan.PEM_MATERIAL_REDACTION));

  /** What {@link SecurityScan} scrubs as private key material. */
  private static final String PEM_MATERIAL_SHAPE = "[A-Za-z0-9+/=]{40,}";

  /** A redacted value's characters after its shown prefix: no whitespace, no quote. */
  private static final String VALUE_CHARACTER = "[^\\s\"'`]";

  private RedactedAnchor() {}

  /** A matcher for one trimmed anchor line against a trimmed right-side diff line. */
  static Predicate<String> lineMatcher(String anchorLine) {
    var marker = MARKER.matcher(anchorLine);
    var regex = new StringBuilder();
    int last = 0;
    while (marker.find()) {
      var shape = shapeOf(marker);
      if (shape != null) {
        regex.append(Pattern.quote(anchorLine.substring(last, marker.start()))).append(shape);
        last = marker.end();
      }
    }
    if (regex.isEmpty()) {
      return anchorLine::equals;
    }
    regex.append(Pattern.quote(anchorLine.substring(last)));
    var pattern = Pattern.compile(regex.toString());
    return line -> pattern.matcher(line).matches();
  }

  /** The regex one marker stands for, or {@code null} when it cannot be a scrub's output. */
  private static String shapeOf(Matcher marker) {
    var length = marker.group(2);
    if (length == null) {
      return PEM_MATERIAL_SHAPE;
    }
    var prefix = marker.group(1);
    int rest = Integer.parseInt(length) - prefix.length();
    if (rest < 0) {
      return null;
    }
    return Pattern.quote(prefix) + VALUE_CHARACTER + "{" + rest + "}";
  }
}
