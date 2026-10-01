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

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether a later finding and an earlier "Things to double-check" item describe the same defect
 * (#961). A later finding that does replaces the earlier item, so this test decides whether an open
 * finding leaves the summary counts.
 *
 * <p>Two findings near each other are not the same defect just because they are close. On a
 * configuration table, adjacent rows document different keys, and their descriptions use the same
 * words ("doc", "operator", "default", the config class). The overlap test {@link
 * FollowUpAnalyzer#isSameFinding} uses for re-raises read the TRACKED_PORTS separator finding on
 * row 8 as the POLL_INTERVAL unit item on row 9 and dropped the item. The opposite also happened.
 * An events-table finding and a schema item on the same line, worded differently, stayed two
 * entries. So this test requires the same file and one of these:
 *
 * <ul>
 *   <li>the same line and the same title: the summary's own fold ({@link
 *       PrSummaryGenerator#reRaises});
 *   <li>a line within {@link FollowUpAnalyzer#DUPLICATE_LINE_TOLERANCE} and similar titles;
 *   <li>the same line, and either the titles share {@value #SHARED_TITLE_WORDS} content words, or
 *       one title names an identifier the other finding's title or description names too.
 * </ul>
 *
 * <p>In all three cases, two titles that each name identifiers (a backticked span, an {@code
 * UPPER_SNAKE} or {@code snake_case} name, a {@code camelCase} name) and share none are never the
 * same defect. Content overlap alone, without the anchor, never counts here. A false match removes
 * an open finding from the counts, while a missed one only lists a defect twice, so ambiguous pairs
 * stay separate.
 */
final class DefectIdentity {

  private DefectIdentity() {}

  /** Content words two same-line titles must share when neither names a common identifier. */
  static final int SHARED_TITLE_WORDS = 2;

  /**
   * Identifier shapes in a finding title: a backticked span, an underscore-joined name in either
   * case, or a {@code camelCase} name. Every quantifier is bounded, as in {@code
   * ConfigKeyContextResolver}, so a crafted title cannot drive the matcher into deep recursion.
   */
  private static final Pattern IDENTIFIER =
      Pattern.compile(
          "`([^`\\n]{1,120})`"
              + "|\\b([A-Za-z][A-Za-z0-9]{0,63}(?:_[A-Za-z0-9]{1,64}){1,16})\\b"
              + "|\\b([a-z][a-z0-9]{0,63}(?:[A-Z][a-z0-9]{0,63}){1,16})\\b");

  /** Whether {@code finding} and {@code prior}, an earlier finding, describe the same defect. */
  static boolean sameDefect(ReviewResponse.Finding finding, ReviewResponse.Finding prior) {
    if (finding.file() == null || !FilePaths.same(finding.file(), prior.file())) {
      return false;
    }
    if (namesDifferentIdentifiers(finding.title(), prior.title())) {
      return false;
    }
    if (PrSummaryGenerator.reRaises(
        Finding.fromAiResponse(finding), Finding.fromAiResponse(prior))) {
      return true;
    }
    if (Math.abs(finding.line() - prior.line()) <= FollowUpAnalyzer.DUPLICATE_LINE_TOLERANCE
        && FindingDeduplicator.titleSimilarity(finding.title(), prior.title())
            >= FindingDeduplicator.TITLE_SIMILARITY_THRESHOLD) {
      return true;
    }
    return finding.line() == prior.line()
        && (sharedTitleWords(finding.title(), prior.title()) >= SHARED_TITLE_WORDS
            || namesIdentifierOf(finding.title(), prior)
            || namesIdentifierOf(prior.title(), finding));
  }

  /**
   * Whether both titles name identifiers and none of them is the same name, so the two findings are
   * about different things: two config keys, two tables, two methods. One name containing the other
   * ({@code poll_interval} and {@code poll_interval_seconds}) counts as the same name.
   */
  static boolean namesDifferentIdentifiers(String title, String otherTitle) {
    var names = identifiers(title);
    var otherNames = identifiers(otherTitle);
    if (names.isEmpty() || otherNames.isEmpty()) {
      return false;
    }
    for (var name : names) {
      for (var other : otherNames) {
        if (name.contains(other) || other.contains(name)) {
          return false;
        }
      }
    }
    return true;
  }

  /** The identifiers {@code title} names, lowercased. */
  static Set<String> identifiers(String title) {
    var names = new HashSet<String>();
    if (title == null) {
      return names;
    }
    var matcher = IDENTIFIER.matcher(title);
    while (matcher.find()) {
      for (var group = 1; group <= matcher.groupCount(); group++) {
        var name = matcher.group(group);
        if (name != null && !name.isBlank()) {
          names.add(name.strip().toLowerCase(Locale.ROOT));
        }
      }
    }
    return names;
  }

  /** Whether {@code finding}'s title or description names an identifier from {@code title}. */
  private static boolean namesIdentifierOf(String title, ReviewResponse.Finding finding) {
    var text =
        (Objects.toString(finding.title(), "") + " " + Objects.toString(finding.description(), ""))
            .toLowerCase(Locale.ROOT);
    return identifiers(title).stream().anyMatch(text::contains);
  }

  private static int sharedTitleWords(String title, String otherTitle) {
    var words = new HashSet<>(SummarySurfaceDeduplicator.claim(title).wordSet());
    words.retainAll(SummarySurfaceDeduplicator.claim(otherTitle).wordSet());
    return words.size();
  }
}
