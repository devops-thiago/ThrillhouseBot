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

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The hidden, machine-readable view of the pull request's open findings, written into the summary
 * comment beside {@link PrSummaryGenerator#SUMMARY_MARKER} (#1433).
 *
 * <p>The block is one line, {@code <!-- thrillhousebot:findings v=1 [...] -->}, so a consumer reads
 * it with {@code gh api} and one pattern, whatever prose surrounds it. It is a view over {@link
 * PrSummaryGenerator.CurrentState}, the set the summary's counts and lists already render, and
 * never a source of truth of its own. It carries identifiers and enums only: no title, description
 * or suggestion, so nothing model-written enters it.
 *
 * <p>Each entry holds {@code key} ({@link SuggestionFormatter#findingKey}, the one in the inline
 * marker), {@code file}, {@code line}, {@code risk} and {@code confidence} (lower case), {@code
 * status} (always {@code open}: the set holds nothing else), {@code thread} (an inline thread
 * exists, so a consumer can reply on it; {@code false} means listed under "Things to double-check",
 * cleared through {@code @thrillhousebot resolved}), {@code hasSuggestion} and {@code carried}
 * (open since an earlier review). Entries run most severe first.
 *
 * <p>The array is bounded at {@value #MAX_ENTRIES} entries, so a pathological pull request cannot
 * push the comment over GitHub's size limit; when it is cut, the header says how many were left out
 * ({@code omitted=N}) before the array.
 */
final class FindingsManifest {

  /** Opens the line; the version is the integration contract's, bumped on a breaking change. */
  static final String MARKER_PREFIX = "<!-- thrillhousebot:findings v=1";

  private static final String MARKER_SUFFIX = " -->";

  /** Entries kept: about 160 bytes each, far inside GitHub's 65,536-character comment limit. */
  static final int MAX_ENTRIES = 200;

  private static final Comparator<Finding> MOST_SEVERE_FIRST =
      Comparator.comparing((Finding f) -> riskOf(f)).thenComparing(Finding::confidence);

  private FindingsManifest() {}

  /**
   * The manifest line for what the summary renders. An empty set renders {@code []}, so a consumer
   * can tell "nothing open" from "a comment posted before the manifest existed".
   */
  static String render(PrSummaryGenerator.CurrentState state) {
    var carried = state.carried();
    var ordered = state.result().findings().stream().sorted(MOST_SEVERE_FIRST).toList();
    var kept = ordered.size() > MAX_ENTRIES ? ordered.subList(0, MAX_ENTRIES) : ordered;
    var omitted = ordered.size() - kept.size();
    var header = omitted == 0 ? MARKER_PREFIX : MARKER_PREFIX + " omitted=" + omitted;
    return header + " " + entries(kept, carried) + MARKER_SUFFIX;
  }

  private static String entries(List<Finding> findings, Set<Finding> carried) {
    return findings.stream()
        .map(f -> entry(f, carried.contains(f)))
        .collect(Collectors.joining(",", "[", "]"));
  }

  private static String entry(Finding finding, boolean carried) {
    return "{\"key\":\""
        + SuggestionFormatter.findingKey(finding.file(), finding.line(), finding.title())
        + "\",\"file\":\""
        + escape(finding.file())
        + "\",\"line\":"
        + finding.line()
        + ",\"risk\":\""
        + riskOf(finding).name().toLowerCase(Locale.ROOT)
        + "\",\"confidence\":\""
        + finding.confidence().name().toLowerCase(Locale.ROOT)
        + "\",\"status\":\"open\",\"thread\":"
        + finding.postsInline()
        + ",\"hasSuggestion\":"
        + finding.hasSuggestion()
        + ",\"carried\":"
        + carried
        + "}";
  }

  /** A finding with no risk is graded low, as {@link Finding#postsInline} and the summary do. */
  private static RiskLevel riskOf(Finding finding) {
    return finding.risk() == null ? RiskLevel.LOW : finding.risk();
  }

  /**
   * A JSON string body that can sit inside an HTML comment: quotes, backslashes and control
   * characters are escaped, and so are {@code <}, {@code >} and {@code &}, so a path can never
   * write {@code -->} and close the comment early. The file path is the only free text in an entry.
   */
  private static String escape(String text) {
    if (text == null) {
      return "";
    }
    var out = new StringBuilder(text.length() + 8);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20 || c == '<' || c == '>' || c == '&') {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }
}
