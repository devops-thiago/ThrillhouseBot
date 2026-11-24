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

/**
 * Optional collapsed "Agent prompt" block on inline findings (#1432). Built from fields the
 * pipeline already holds — no extra model call.
 */
final class AgentPromptBlock {

  private AgentPromptBlock() {}

  static String withAgentPrompt(String body, Finding finding, boolean includeSuggestion) {
    if (body == null) {
      return null;
    }
    var block = render(finding, includeSuggestion);
    if (block.isEmpty()) {
      return body;
    }
    return body + "\n\n" + block;
  }

  static String render(Finding finding, boolean includeSuggestion) {
    var file = finding.file();
    if (file == null || file.isBlank()) {
      return "";
    }
    var safeFile = MarkdownSafe.inline(file);
    var line = finding.line();
    var title = MarkdownSafe.inline(finding.title());
    var problem = firstParagraph(finding.description());
    var recommended = recommendedFix(finding, includeSuggestion);
    return "<details><summary>Agent prompt</summary>\n\n"
        + "Fix this review finding in `"
        + safeFile
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
    return MarkdownSafe.inline(paragraph.strip());
  }

  private static String recommendedFix(Finding finding, boolean includeSuggestion) {
    if (includeSuggestion && finding.hasSuggestion()) {
      return "apply the suggested change in the comment above";
    }
    return "follow the description; add tests if the change is behavioural";
  }
}
