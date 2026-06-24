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

import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;

/**
 * Renders the untrusted-context prompt sections shared by the review path and the on-request
 * commands ({@code /describe}, {@code /changelog}, {@code /add-docs}), so each section's format
 * lives in one place instead of being copied per command.
 */
public final class PromptSections {

  private PromptSections() {}

  /** Title and author-description block the model checks the implementation against. */
  public static String prContext(String title, String description) {
    var sb = new StringBuilder();
    if (title != null && !title.isBlank()) {
      sb.append("Title: ").append(title.strip()).append('\n');
    }
    if (description != null && !description.isBlank()) {
      sb.append("Description:\n").append(description.strip()).append('\n');
    }
    return sb.toString();
  }

  /**
   * Pre-rendered repository-instructions section — header, source attribution, and the caller's
   * command-specific {@code guidance} line(s) — with only the maintainer-provided content escaped,
   * so the template needs a single variable. Empty when no instructions are configured. The {@code
   * guidance} string carries its own trailing newline.
   */
  public static String instructionsSection(
      InstructionsResolver.ResolvedInstructions instructions, String guidance) {
    if (!instructions.isPresent()) {
      return "";
    }
    return "## Project-Specific Instructions (from "
        + instructions.source()
        + ")\n"
        + guidance
        + PromptTemplateEscaper.escape(instructions.content());
  }
}
