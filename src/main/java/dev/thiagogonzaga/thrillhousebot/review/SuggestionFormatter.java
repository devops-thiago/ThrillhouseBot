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
import java.util.OptionalInt;
import java.util.regex.Pattern;

/** Formats suggestions as GitHub ```suggestion blocks for click-to-apply. */
@ApplicationScoped
public class SuggestionFormatter {

  private static final String CODE_FENCE_CLOSE = "\n```\n";

  private static final Pattern FINDING_MARKER_PATTERN =
      Pattern.compile("<!--\\s*thrillhousebot:finding=(\\d+)\\s*-->");

  /** What a code-fence info string may look like — a language tag, nothing else. */
  private static final Pattern LANGUAGE_TAG_PATTERN = Pattern.compile("[a-z0-9+#._-]{1,20}");

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
   * Parses the 1-based finding index from a comment body that carries {@link #findingMarker(int)},
   * or empty when the marker is absent.
   */
  public static OptionalInt parseFindingMarker(String body) {
    if (body == null || body.isBlank()) {
      return OptionalInt.empty();
    }
    var matcher = FINDING_MARKER_PATTERN.matcher(body);
    if (!matcher.find()) {
      return OptionalInt.empty();
    }
    try {
      return OptionalInt.of(Integer.parseInt(matcher.group(1)));
    } catch (NumberFormatException _) {
      return OptionalInt.empty();
    }
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
   * Builds one proposed-test section of the {@code /generate-tests} comment: the target path as a
   * heading, the one-line "covers" note when the model supplied one, and the test source as a code
   * block tagged with its language.
   *
   * <p>A generated test is normally a whole new file, which has no line in the diff for GitHub to
   * anchor a committable {@code suggestion} block to — those only render inside an inline review
   * comment on a diff line. So the source is presented as a copy-paste block headed by the exact
   * path it belongs at, rather than being forced into the inline-suggestion shape.
   *
   * <p>The fence is widened past the longest backtick run in the source, so a test that itself
   * contains a fenced block cannot break out of the comment.
   */
  public String formatGeneratedTestFile(String path, String language, String covers, String code) {
    var sb = new StringBuilder("### `").append(path == null ? "" : path.strip()).append("`\n");
    if (covers != null && !covers.isBlank()) {
      sb.append(covers.strip()).append('\n');
    }
    var body = code == null ? "" : code.stripTrailing();
    var fence = fenceFor(body);
    return sb.append('\n')
        .append(fence)
        .append(languageTag(language))
        .append('\n')
        .append(body)
        .append('\n')
        .append(fence)
        .append('\n')
        .toString();
  }

  /**
   * A code fence at least one backtick longer than the longest backtick run in {@code code} (never
   * shorter than the usual three), so fenced content inside the block cannot close it early.
   */
  private static String fenceFor(String code) {
    int longest = 0;
    int run = 0;
    for (int i = 0; i < code.length(); i++) {
      run = code.charAt(i) == '`' ? run + 1 : 0;
      longest = Math.max(longest, run);
    }
    return "`".repeat(Math.max(3, longest + 1));
  }

  /**
   * The model-supplied language as a code-fence info string, or empty when it is missing or carries
   * anything but the characters a language tag is made of — the info string shares the fence line,
   * so arbitrary text there would corrupt the block.
   */
  private static String languageTag(String language) {
    if (language == null) {
      return "";
    }
    var tag = language.strip().toLowerCase(Locale.ROOT);
    return LANGUAGE_TAG_PATTERN.matcher(tag).matches() ? tag : "";
  }

  /**
   * Italic disclaimer appended when confidence is below high, so readers know to verify before
   * acting. Empty for {@link Confidence#HIGH}.
   */
  public static String confidenceDisclaimer(Confidence confidence) {
    if (confidence == null || confidence == Confidence.HIGH) {
      return "";
    }
    return "_("
        + confidence.name().toLowerCase(Locale.ROOT)
        + " confidence — verify before acting)_";
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
    String disclaimer = confidenceDisclaimer(finding.confidence());
    if (!disclaimer.isEmpty()) {
      sb.append(' ').append(disclaimer);
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
