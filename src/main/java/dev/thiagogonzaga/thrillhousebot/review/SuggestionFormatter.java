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

  private static final Pattern FINDING_MARKER_PATTERN =
      Pattern.compile("<!--\\s*thrillhousebot:finding=(\\d+)\\s*-->");

  /** What a code-fence info string may look like — a language tag, nothing else. */
  private static final Pattern LANGUAGE_TAG_PATTERN = Pattern.compile("[a-z0-9+#._-]{1,20}");

  /**
   * Wraps suggestion_old and suggestion_new in a GitHub suggestion block. The model-supplied code
   * is routed through {@link MarkdownSafe#suggestionBlock}, so a fence inside it widens the block
   * rather than closing it early.
   *
   * <p>Example output: ```suggestion PreparedStatement stmt = conn.prepareStatement("SELECT * FROM
   * users WHERE id = ?"); stmt.setInt(1, userId); ```
   */
  public String formatSuggestionBlock(String suggestionOld, String suggestionNew) {
    if (suggestionOld == null || suggestionNew == null) return "";
    return MarkdownSafe.suggestionBlock(suggestionNew);
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
      sb.append(" for `").append(MarkdownSafe.inlineCode(symbol)).append("`");
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
      sb.append(" for `").append(MarkdownSafe.inlineCode(symbol)).append("`");
    }
    sb.append("**\n");
    sb.append("This symbol is missing documentation. Suggested:\n");
    sb.append(MarkdownSafe.fencedBlock(suggestionNew));
    return sb.toString();
  }

  /**
   * Builds the body of an {@code /improve} inline comment: the improvement's title (and category,
   * when the model supplied one), its rationale, and the rewritten code as a committable suggestion
   * block.
   */
  public String formatImprovementComment(
      String title, String category, String rationale, String suggestionOld, String suggestionNew) {
    var sb = new StringBuilder("**✨ Improvement");
    if (title != null && !title.isBlank()) {
      sb.append(" — ").append(MarkdownSafe.inline(title));
    }
    sb.append("**");
    if (category != null && !category.isBlank()) {
      sb.append(" `").append(MarkdownSafe.inlineCode(category)).append("`");
    }
    sb.append("\n\n");
    if (rationale != null && !rationale.isBlank()) {
      sb.append(MarkdownSafe.inline(rationale)).append("\n");
    }
    sb.append(formatSuggestionBlock(suggestionOld, suggestionNew));
    return sb.toString();
  }

  /**
   * Builds the copy-paste rendering of an {@code /improve} item that could not be anchored onto the
   * diff, so no committable suggestion is possible: the same header and rationale, followed by the
   * proposed code as a plain block to apply by hand.
   */
  public String formatImprovementBlock(
      String title,
      String category,
      String rationale,
      String file,
      int line,
      String suggestionNew) {
    var sb = new StringBuilder("**");
    sb.append(title == null || title.isBlank() ? "Improvement" : MarkdownSafe.inline(title))
        .append("**");
    if (category != null && !category.isBlank()) {
      sb.append(" `").append(MarkdownSafe.inlineCode(category)).append("`");
    }
    if (file != null && !file.isBlank()) {
      sb.append(" — `").append(MarkdownSafe.inlineCode(file)).append(":").append(line).append("`");
    }
    sb.append("\n");
    if (rationale != null && !rationale.isBlank()) {
      sb.append("\n").append(MarkdownSafe.inline(rationale)).append("\n");
    }
    sb.append(MarkdownSafe.fencedBlock(suggestionNew));
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
   * <p>Every field here is model output, so each is routed through {@link MarkdownSafe}: the source
   * through {@link MarkdownSafe#fencedBlock(String, String)} (the fence widens past any backtick
   * run so a fenced block inside the test cannot break out), the path through {@link
   * MarkdownSafe#inlineCode} and the "covers" note through {@link MarkdownSafe#inline}, so a diff
   * that prompt-injects the model cannot restructure the bot's comment.
   */
  public String formatGeneratedTestFile(String path, String language, String covers, String code) {
    var sb = new StringBuilder("### `").append(MarkdownSafe.inlineCode(path)).append("`\n");
    if (covers != null && !covers.isBlank()) {
      sb.append(MarkdownSafe.inline(covers)).append('\n');
    }
    return sb.append(MarkdownSafe.fencedBlock(code, languageTag(language))).toString();
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
   * Model-supplied prose flattened to a single line. Retained as a thin delegate to {@link
   * MarkdownSafe#oneLine} for the on-request generators that splice their own model prose into a
   * comment body; new render sites should call {@link MarkdownSafe} directly.
   */
  static String oneLine(String value) {
    return MarkdownSafe.oneLine(value);
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
        .append(MarkdownSafe.inline(finding.title()))
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
