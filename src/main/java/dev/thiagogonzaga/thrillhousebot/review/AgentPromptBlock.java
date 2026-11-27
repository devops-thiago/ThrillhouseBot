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

import dev.thiagogonzaga.thrillhousebot.github.CommentBodyLimit;

/**
 * Optional collapsed "Agent prompt" block on inline findings (#1432). Built from fields the
 * pipeline already holds — no extra model call.
 *
 * <p>The block goes ahead of the comment's hidden finding marker, so the marker stays the last
 * thing in the comment: the file name printed here is the pull request author's text.
 *
 * <p>The block repeats the description's first paragraph, cut at {@value #MAX_PROBLEM_CHARS}
 * characters, and is left off a comment it would push past GitHub's comment-body limit: the cap
 * applied at the client would otherwise cut the comment inside the {@code <details>} element and
 * leave it open.
 */
final class AgentPromptBlock {

  /** Longest problem paragraph repeated in the block, before escaping. */
  static final int MAX_PROBLEM_CHARS = 1_000;

  /** Room kept under the limit for the {@code line=} field a removed-line marker adds (#1360). */
  static final int MARKER_RESERVE = 32;

  private AgentPromptBlock() {}

  static String withAgentPrompt(String body, Finding finding, boolean includeSuggestion) {
    if (body == null) {
      return null;
    }
    var block = render(finding, includeSuggestion);
    if (block.isEmpty()) {
      return body;
    }
    var withBlock = SuggestionFormatter.withBlockBeforeMarker(body, block);
    return withBlock.length() > CommentBodyLimit.MAX_LENGTH - MARKER_RESERVE ? body : withBlock;
  }

  static String render(Finding finding, boolean includeSuggestion) {
    var file = finding.file();
    if (file == null || file.isBlank()) {
      return "";
    }
    var safeFile = MarkdownSafe.inline(file);
    var codeFile = MarkdownSafe.inlineCode(file);
    var line = finding.line();
    var title = MarkdownSafe.inline(finding.title());
    var problem = firstParagraph(finding.description());
    var recommended = recommendedFix(finding, includeSuggestion);
    return "<details><summary>Agent prompt</summary>\n\n"
        + "Fix this review finding in `"
        + codeFile
        + "` (line "
        + line
        + ").\n\n"
        + "Problem: "
        + title
        + ". "
        + problem
        + "\n\n"
        + "Scope: "
        + safeFile
        + ":"
        + line
        + ". Do not change other files unless the fix requires it.\n\n"
        + "Recommended fix: "
        + recommended
        + "\n"
        + "</details>";
  }

  private static String firstParagraph(String description) {
    if (description == null || description.isBlank()) {
      return "(no description)";
    }
    var trimmed = description.strip();
    var breakAt = trimmed.indexOf("\n\n");
    var paragraph = breakAt < 0 ? trimmed : trimmed.substring(0, breakAt);
    return MarkdownSafe.inline(capped(MarkdownSafe.oneLine(paragraph)));
  }

  /**
   * {@code paragraph} cut to {@value #MAX_PROBLEM_CHARS} characters at the last space before the
   * limit, or at the limit when there is none, with an ellipsis; never splitting a surrogate pair.
   */
  static String capped(String paragraph) {
    if (paragraph.length() <= MAX_PROBLEM_CHARS) {
      return paragraph;
    }
    var end = paragraph.lastIndexOf(' ', MAX_PROBLEM_CHARS);
    if (end <= 0) {
      end = MAX_PROBLEM_CHARS;
      if (Character.isHighSurrogate(paragraph.charAt(end - 1))) {
        end--;
      }
    }
    return paragraph.substring(0, end).stripTrailing() + "…";
  }

  private static String recommendedFix(Finding finding, boolean includeSuggestion) {
    if (includeSuggestion && finding.hasSuggestion()) {
      return "apply the suggested change in the comment above";
    }
    return "follow the description; add tests if the change is behavioural";
  }
}
