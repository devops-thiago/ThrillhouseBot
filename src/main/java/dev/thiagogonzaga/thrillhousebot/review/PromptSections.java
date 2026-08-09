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
import java.util.List;

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
   * command-specific {@code guidance} line(s) — with only the maintainer-provided content wrapped
   * in an unforgeable CSPRNG fence, so the template needs a single variable and repository-supplied
   * prose cannot forge a section boundary. Empty when no instructions are configured. The {@code
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
        + PromptTemplateEscaper.fence(instructions.content());
  }

  /** How many matched paths are listed under a scope before the rest are rolled up. */
  private static final int MAX_LISTED_FILES = 10;

  /**
   * Pre-rendered path-scoped-instructions section: one block per scope that matched a file in this
   * review, each naming its glob and the files it governs so the model can never carry one
   * directory's rules over to another. Empty when no scoped rule applies.
   *
   * <p>The glob heads each block and is what the guidance tells the model to scope by; the file
   * list underneath is the changed files it covers, and {@link #formatFiles} may abbreviate it on a
   * large pull request. Naming the glob rather than only the paths is what keeps an abbreviated
   * list from quietly narrowing a scope to its first few files.
   *
   * <p>The globs, paths, and maintainer prose are all repository-controlled and framed as data. The
   * multi-line rule block is wrapped in an unforgeable CSPRNG fence like the global instructions
   * block; the glob and the changed-file list are single inline identifiers that head their block —
   * the model scopes by them — so they are marker-neutralized in place rather than fenced, which
   * would split them across the fence lines and break the "files matching &lt;glob&gt;" contract.
   * The {@code guidance} string carries its own trailing newline.
   */
  public static String pathInstructionsSection(PathScopedInstructions scoped, String guidance) {
    if (scoped == null || scoped.isEmpty()) {
      return "";
    }
    var sb =
        new StringBuilder("## Path-Scoped Instructions (from ")
            .append(scoped.source())
            .append(")\n")
            .append(guidance);
    var total = scoped.scopes().size();
    for (var i = 0; i < total; i++) {
      var scope = scoped.scopes().get(i);
      sb.append("\n### Scope ")
          .append(i + 1)
          .append(" of ")
          .append(total)
          .append(": files matching ")
          .append(PromptTemplateEscaper.escape(scope.glob()))
          .append("\nChanged files in this pull request under that glob: ")
          .append(PromptTemplateEscaper.escape(formatFiles(scope.files())))
          .append("\nRules for those files:\n")
          .append(PromptTemplateEscaper.fence(scope.instructions()))
          .append('\n');
    }
    return sb.toString();
  }

  /**
   * The scope's matched paths, capped so a scope covering a large PR cannot dominate the prompt.
   */
  private static String formatFiles(List<String> files) {
    if (files.size() <= MAX_LISTED_FILES) {
      return String.join(", ", files);
    }
    var listed = String.join(", ", files.subList(0, MAX_LISTED_FILES));
    return listed + ", and " + (files.size() - MAX_LISTED_FILES) + " more matching this glob";
  }
}
