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

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Locale;

/** Formats suggestions as GitHub ```suggestion blocks for click-to-apply. */
@ApplicationScoped
public class SuggestionFormatter {

  private static final String CODE_FENCE_CLOSE = "\n```\n";

  /**
   * Wraps suggestion_old and suggestion_new in a GitHub suggestion block.
   *
   * <p>Example output: ```suggestion PreparedStatement stmt = conn.prepareStatement("SELECT * FROM
   * users WHERE id = ?"); stmt.setInt(1, userId); ```
   */
  public String formatSuggestionBlock(String suggestionOld, String suggestionNew) {
    if (suggestionOld == null || suggestionNew == null) return "";
    return "\n```suggestion\n" + suggestionNew.stripTrailing() + CODE_FENCE_CLOSE;
  }

  /**
   * Hidden marker tying an inline comment to its finding's 1-based position in the persisted AI
   * response, so follow-up reviews can match threads deterministically even when two findings share
   * a title. Invisible in GitHub's rendering.
   */
  public static String findingMarker(int findingId) {
    return "<!-- thrillhousebot:finding=" + findingId + " -->";
  }

  /**
   * Builds the body of an {@code /add-docs} inline comment: a short header naming the symbol,
   * followed by the documentation as a committable suggestion block. The suggestion block
   * reproduces the original declaration line so applying it only inserts documentation.
   */
  public String formatDocComment(String symbol, String suggestionOld, String suggestionNew) {
    var sb = new StringBuilder("**📝 Documentation");
    if (symbol != null && !symbol.isBlank()) {
      sb.append(" for `").append(symbol.strip()).append("`");
    }
    sb.append("**\n");
    sb.append(formatSuggestionBlock(suggestionOld, suggestionNew));
    return sb.toString();
  }

  /**
   * Builds an {@code /add-docs} comment that flags an undocumented symbol without a committable
   * suggestion — used when the documentation cannot be anchored as one (e.g. a multi-line
   * declaration that does not map cleanly onto the diff). It states the missing-docs problem and
   * shows the drafted documentation as a plain block to add manually, so the gap is still surfaced.
   */
  public String formatDocNote(String symbol, String suggestionNew) {
    var sb = new StringBuilder("**📝 Documentation");
    if (symbol != null && !symbol.isBlank()) {
      sb.append(" for `").append(symbol.strip()).append("`");
    }
    sb.append("**\n");
    sb.append("This symbol is missing documentation. Suggested:\n");
    sb.append(CODE_FENCE_CLOSE)
        .append(suggestionNew == null ? "" : suggestionNew.stripTrailing())
        .append(CODE_FENCE_CLOSE);
    return sb.toString();
  }

  /**
   * Builds a full review comment body for a single finding. Includes the risk emoji, title,
   * description, and suggestion block (if applicable).
   */
  public String formatReviewComment(Finding finding) {
    return formatReviewComment(finding, true);
  }

  /** Builds a review comment body, optionally omitting suggestion blocks (e.g. after a 422). */
  public String formatReviewComment(Finding finding, boolean includeSuggestion) {
    return formatReviewComment(finding, includeSuggestion, 0);
  }

  /** Variant that appends the hidden finding marker when {@code findingId} is positive. */
  public String formatReviewComment(Finding finding, boolean includeSuggestion, int findingId) {
    var sb = new StringBuilder();
    sb.append("**")
        .append(finding.risk().toEmoji())
        .append(" ")
        .append(finding.risk().name())
        .append(" — ")
        .append(finding.title())
        .append("**");
    if (finding.confidence() != Confidence.HIGH) {
      sb.append(" _(")
          .append(finding.confidence().name().toLowerCase(Locale.ROOT))
          .append(" confidence — verify before acting)_");
    }
    sb.append("\n\n");
    sb.append(finding.description()).append("\n");

    if (includeSuggestion && finding.hasSuggestion()) {
      sb.append(formatSuggestionBlock(finding.suggestionOld(), finding.suggestionNew()));
    }

    if (findingId > 0) {
      sb.append("\n").append(findingMarker(findingId)).append("\n");
    }

    return sb.toString();
  }
}
