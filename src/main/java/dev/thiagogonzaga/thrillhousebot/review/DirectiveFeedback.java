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

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * What a {@code resolved} or {@code declined} comment names among the open findings on record
 * (#1463), so its acknowledgement says which ones matched instead of leaving a mistyped title to be
 * found out from a review that changes nothing. It reads the stored rows and applies the review's
 * own naming rule; the review still decides.
 */
final class DirectiveFeedback {

  /** Findings listed per group before the rest become a count. */
  static final int MAX_LISTED = 20;

  private DirectiveFeedback() {}

  /**
   * The acknowledgement for {@code body}, or empty when no open finding is on record, so nothing
   * can be said about what the comment names.
   *
   * @param clear {@code true} for {@code resolved}, {@code false} for {@code declined}
   */
  static Optional<String> ack(String body, boolean clear, Collection<FindingLedger.Row> rows) {
    var open =
        rows.stream()
            .filter(row -> FindingLedger.OPEN.equals(row.status().value()))
            .map(FindingLedger.Row::subject)
            .filter(s -> s.file() != null && s.title() != null && !s.title().isBlank())
            .toList();
    if (open.isEmpty()) {
      return Optional.empty();
    }
    var naming = FollowUpAnalyzer.CommentNaming.of(body);
    var matched = open.stream().filter(s -> naming.names(s.file(), s.line(), s.title())).toList();
    var matchedLocators = new HashSet<String>();
    matched.forEach(s -> matchedLocators.add(locator(s)));
    var locatorOnly =
        open.stream()
            .filter(s -> !matchedLocators.contains(locator(s)))
            .filter(s -> naming.namesPathAndLine(s.file(), s.line(), s.title()))
            .toList();
    var ack = new StringBuilder();
    if (matched.isEmpty()) {
      ack.append(
          OutdatedOutputHider.NO_OPEN_FINDING_OPENING + " by both its `path:line` and title.");
    } else {
      ack.append(
          clear
              ? OutdatedOutputHider.NEXT_REVIEW_OPENING
                  + "close these, named by `path:line` and title:"
              : OutdatedOutputHider.NEXT_REVIEW_OPENING
                  + "record these as declined, checking each reason against the"
                  + " reviewed code:");
      list(ack, matched);
    }
    if (!locatorOnly.isEmpty()) {
      ack.append("\n\nNamed by `path:line` but not by title, so not matched. The title on record:");
      list(ack, locatorOnly);
    } else if (matched.isEmpty()) {
      ack.append(" Copy both from the summary comment.");
    }
    return Optional.of(ack.append("\n\nAnything not matched stays open.").toString());
  }

  private static String locator(FindingLedger.Subject subject) {
    return subject.file() + ":" + subject.line();
  }

  private static void list(StringBuilder ack, List<FindingLedger.Subject> subjects) {
    ack.append('\n');
    subjects.stream()
        .limit(MAX_LISTED)
        .forEach(
            s ->
                ack.append("\n- `")
                    .append(MarkdownSafe.inlineCode(s.file()))
                    .append(':')
                    .append(s.line())
                    .append("` — ")
                    .append(MarkdownSafe.inline(s.title())));
    if (subjects.size() > MAX_LISTED) {
      ack.append("\n- and ").append(subjects.size() - MAX_LISTED).append(" more");
    }
  }
}
