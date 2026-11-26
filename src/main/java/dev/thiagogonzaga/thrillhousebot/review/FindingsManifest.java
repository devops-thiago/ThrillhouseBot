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
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Predicate;

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
 * marker: eight hex digits of a SHA-256 over file, line and title, so the title itself never enters
 * the line), {@code file}, {@code line}, {@code risk} and {@code confidence} (lower case), {@code
 * status} (always {@code open}: the set holds nothing else), {@code thread}, {@code hasSuggestion}
 * and {@code carried} (open since an earlier review). Entries run most severe first.
 *
 * <p>{@code thread} is {@code true} when the finding is expected to have an inline thread: a
 * finding of this round that the review posts inline on a file the pull request changes, within the
 * per-run comment cap, or a carried finding that posts inline. The summary is written before the
 * review posts, so a thread GitHub then refuses, which the review body lists instead, still reads
 * {@code true}. {@code false} means no thread is expected: listed under "Things to double-check",
 * on a file outside the pull request, or past the comment cap; such a finding is cleared through
 * {@code @thrillhousebot resolved}.
 *
 * <p>The array is bounded twice, so a pathological pull request cannot push the comment over
 * GitHub's size limit: at {@value #MAX_ENTRIES} entries, and at {@value #MAX_ARRAY_CHARS} rendered
 * characters, because the path in an entry has no length bound of its own. When it is cut, the
 * header says how many entries were left out ({@code omitted=N}) before the array.
 */
final class FindingsManifest {

  /** Opens the line; the version is the integration contract's, bumped on a breaking change. */
  static final String MARKER_PREFIX = "<!-- thrillhousebot:findings v=1";

  private static final String MARKER_SUFFIX = " -->";

  /**
   * Entries kept: about 160 characters each for a typical path, so this many fit {@link
   * #MAX_ARRAY_CHARS}.
   */
  static final int MAX_ENTRIES = 100;

  /**
   * Rendered array budget: a quarter of GitHub's 65,536-character comment limit, so the manifest
   * cannot crowd out the summary it rides in.
   */
  static final int MAX_ARRAY_CHARS = 16_000;

  private static final Comparator<Finding> MOST_SEVERE_FIRST =
      Comparator.comparing(FindingsManifest::riskOf).thenComparing(Finding::confidence);

  private FindingsManifest() {}

  /**
   * The manifest line for what the summary renders. An empty set renders {@code []}, so a consumer
   * can tell "nothing open" from "a comment posted before the manifest existed".
   */
  static String render(PrSummaryGenerator.CurrentState state) {
    return render(state, Finding::postsInline);
  }

  /**
   * {@link #render(PrSummaryGenerator.CurrentState)}, with {@code thread} decided by {@code
   * thread}.
   */
  static String render(PrSummaryGenerator.CurrentState state, Predicate<Finding> thread) {
    return line(collect(state, thread));
  }

  /** The manifest line carrying {@code open}. */
  static String line(OpenFindings open) {
    var header = open.omitted() == 0 ? MARKER_PREFIX : MARKER_PREFIX + " omitted=" + open.omitted();
    return header + " " + open.entries() + MARKER_SUFFIX;
  }

  /** The entries the manifest lists for {@code state}, within its two limits. */
  static OpenFindings collect(PrSummaryGenerator.CurrentState state, Predicate<Finding> thread) {
    var carried = state.carried();
    var ordered = state.result().findings().stream().sorted(MOST_SEVERE_FIRST).toList();
    var kept = new ArrayList<String>();
    int chars = 2;
    for (Finding finding : ordered) {
      var rendered = entry(finding, carried.contains(finding), thread.test(finding));
      int cost = rendered.length() + 1;
      if (kept.size() == MAX_ENTRIES || chars + cost > MAX_ARRAY_CHARS) {
        break;
      }
      kept.add(rendered);
      chars += cost;
    }
    return new OpenFindings("[" + String.join(",", kept) + "]", ordered.size() - kept.size());
  }

  private static String entry(Finding finding, boolean carried, boolean thread) {
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
        + thread
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
