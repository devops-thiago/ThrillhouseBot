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

import dev.thiagogonzaga.thrillhousebot.LogSafe;
import io.quarkus.logging.Log;
import java.util.Locale;
import java.util.Objects;

/**
 * Applies the verifier's judgment of a kept finding's suggestion and side claims (#1080), the same
 * call that rules on the finding. Round 17 posted correct findings with broken suggestions: a
 * pagination loop initialised so it never runs (scala ThrillhouseBot-test#248), a Dockerfile {@code
 * COPY} renamed while the {@code ENTRYPOINT} kept the old path (go #247); and side claims the diff
 * contradicts beside a real defect (zig #250, c #253).
 *
 * <p>Each part is applied on its own and fails open: {@code replace} puts the corrected code in
 * {@code suggestion_new} and leaves {@code suggestion_old}, so the suggestion is still placed on
 * the lines it quotes; {@code drop} removes {@code suggestion_new} and keeps the finding; a cut
 * removes a sentence only when the description holds it exactly and something else stays. A
 * missing, unknown or empty part changes nothing.
 */
final class FindingRevisions {

  static final String REPLACE = "replace";
  static final String DROP = "drop";

  /** The shortest text a cut may remove, so a stray word or quote is never cut. */
  static final int MIN_CUT_CHARS = 12;

  private FindingRevisions() {}

  /** {@code finding} with the verdict's suggestion judgment and cuts applied. */
  static ReviewResponse.Finding revised(
      ReviewResponse.Finding finding, VerificationResponse.Verdict verdict) {
    if (verdict == null) {
      return finding;
    }
    var suggestionNew = revisedSuggestion(finding, verdict);
    var description = finding.description();
    for (var sentence : verdict.cut()) {
      description = withoutSentence(finding, description, sentence);
    }
    if (Objects.equals(description, finding.description())
        && Objects.equals(suggestionNew, finding.suggestionNew())) {
      return finding;
    }
    return new ReviewResponse.Finding(
        finding.risk(),
        finding.confidence(),
        finding.file(),
        finding.line(),
        finding.title(),
        description,
        finding.suggestionOld(),
        suggestionNew);
  }

  /**
   * The finding's {@code suggestion_new} after the verdict: the corrected code for {@code replace}
   * with non-blank code that differs, {@code null} for {@code drop}, and the finding's own for
   * anything else, including a finding with no suggestion to judge.
   */
  private static String revisedSuggestion(
      ReviewResponse.Finding finding, VerificationResponse.Verdict verdict) {
    var current = finding.suggestionNew();
    if (current == null || current.isBlank() || verdict.suggestion() == null) {
      return current;
    }
    var decision = verdict.suggestion().strip().toLowerCase(Locale.ROOT);
    if (DROP.equals(decision)) {
      Log.infof(
          "Verifier dropped the suggestion of finding '%s' (%s:%d): %s",
          LogSafe.oneLine(finding.title()),
          LogSafe.oneLine(finding.file()),
          finding.line(),
          LogSafe.oneLine(String.valueOf(verdict.reason())));
      return null;
    }
    var corrected = verdict.suggestionNew();
    if (REPLACE.equals(decision)
        && corrected != null
        && !corrected.isBlank()
        && !corrected.equals(current)) {
      Log.infof(
          "Verifier corrected the suggestion of finding '%s' (%s:%d): %s",
          LogSafe.oneLine(finding.title()),
          LogSafe.oneLine(finding.file()),
          finding.line(),
          LogSafe.oneLine(String.valueOf(verdict.reason())));
      return corrected;
    }
    return current;
  }

  /**
   * {@code description} without {@code sentence}, when the description holds it exactly as whole
   * sentences, it is at least {@link #MIN_CUT_CHARS} long, and the description says something once
   * it is gone; otherwise {@code description} unchanged. The spaces that separated the sentence
   * from the next one, or from the one before when it ended its line, go with it; every line break
   * stays.
   */
  static String withoutSentence(
      ReviewResponse.Finding finding, String description, String sentence) {
    var cut = sentence.strip();
    if (description == null || cut.length() < MIN_CUT_CHARS) {
      return description;
    }
    var at = description.indexOf(cut);
    if (at < 0 || !isSentence(description, at, cut)) {
      return description;
    }
    var before = description.substring(0, at);
    var after = description.substring(at + cut.length());
    after = after.substring(spaces(after));
    if (after.isEmpty() || after.startsWith("\n") || after.startsWith("\r")) {
      before = before.substring(0, before.length() - trailingSpaces(before));
    }
    var rest = before + after;
    if (rest.isBlank()) {
      return description;
    }
    Log.infof(
        "Verifier cut a claim the material contradicts from finding '%s' (%s:%d): \"%s\"",
        LogSafe.oneLine(finding.title()),
        LogSafe.oneLine(finding.file()),
        finding.line(),
        LogSafe.oneLine(cut));
    return rest;
  }

  /**
   * Whether {@code cut}, found at {@code at}, is whole sentences of {@code description}: it starts
   * the text or a line, or follows a sentence's end and a space, and it ends where a sentence ends
   * ({@link SentenceBreaks}: not on the period of an abbreviation, a decimal or a dotted name). A
   * fragment cut from inside a sentence would leave the rest of that sentence unreadable.
   */
  private static boolean isSentence(String description, int at, String cut) {
    var previous = description.substring(0, at).stripTrailing();
    var starts =
        previous.isEmpty()
            || description.charAt(at - 1) == '\n'
            || (previous.length() < at
                && SentenceBreaks.endsSentenceAt(description, previous.length()));
    return starts && SentenceBreaks.endsSentenceAt(description, at + cut.length());
  }

  /** How many spaces {@code text} starts with. */
  private static int spaces(String text) {
    var count = 0;
    while (count < text.length() && text.charAt(count) == ' ') {
      count++;
    }
    return count;
  }

  /** How many spaces {@code text} ends with. */
  private static int trailingSpaces(String text) {
    var count = 0;
    while (count < text.length() && text.charAt(text.length() - 1 - count) == ' ') {
      count++;
    }
    return count;
  }
}
