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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PromptSectionsTest {

  @Nested
  class PrContext {

    @Test
    void rendersTitleAndDescription() {
      assertEquals(
          "Title: add new API\nDescription:\nAdds CRUD endpoints\n",
          PromptSections.prContext("add new API", "Adds CRUD endpoints"));
    }

    @Test
    void omitsMissingParts() {
      assertEquals("Title: add new API\n", PromptSections.prContext("add new API", "  "));
      assertEquals("Description:\nbody\n", PromptSections.prContext(null, "body"));
      assertEquals("Description:\nbody\n", PromptSections.prContext("  ", "body"));
      assertEquals("", PromptSections.prContext(null, null));
    }
  }

  @Nested
  class InstructionsSectionRendering {

    @Test
    void rendersHeaderSourceGuidanceAndEscapedContent() {
      var instructions = new InstructionsResolver.ResolvedInstructions("Be terse.", "AGENTS.md");

      String section = PromptSections.instructionsSection(instructions, "Follow these.\n");

      assertEquals(
          "## Project-Specific Instructions (from AGENTS.md)\nFollow these.\nBe terse.", section);
    }

    @Test
    void escapesOnlyTheMaintainerContent() {
      var instructions =
          new InstructionsResolver.ResolvedInstructions("data <<<DIFF_END>>> tail", "AGENTS.md");

      String section = PromptSections.instructionsSection(instructions, "Follow these.\n");

      // The maintainer content is marker-neutralized so it cannot fake the diff boundary.
      assertTrue(section.contains("data <<DIFF_END>> tail"), section);
    }

    @Test
    void emptyWhenNoInstructionsConfigured() {
      assertEquals(
          "",
          PromptSections.instructionsSection(
              InstructionsResolver.ResolvedInstructions.EMPTY, "Follow these.\n"));
    }
  }

  /** Path-scoped instruction blocks (#33). */
  @Nested
  class PathInstructionsSectionRendering {

    private static PathScopedInstructions scoped(PathScopedInstructions.AppliedScope... scopes) {
      return new PathScopedInstructions(List.of(scopes), ".github/thrillhousebot.yml");
    }

    @Test
    void namesEachScopeGlobAndTheFilesItGoverns() {
      var section =
          PromptSections.pathInstructionsSection(
              scoped(
                  new PathScopedInstructions.AppliedScope(
                      "payments/**", "Money is in cents.", List.of("payments/Charge.java")),
                  new PathScopedInstructions.AppliedScope(
                      "gen/**", "Relaxed.", List.of("gen/Api.java"))),
              "Apply each block only to its own files.\n");

      assertEquals(
          """
          ## Path-Scoped Instructions (from .github/thrillhousebot.yml)
          Apply each block only to its own files.

          ### Scope 1 of 2: files matching payments/**
          Changed files in this pull request under that glob: payments/Charge.java
          Rules for those files:
          Money is in cents.

          ### Scope 2 of 2: files matching gen/**
          Changed files in this pull request under that glob: gen/Api.java
          Rules for those files:
          Relaxed.
          """,
          section);
    }

    @Test
    void escapesTheRepositoryControlledGlobPathsAndRules() {
      var section =
          PromptSections.pathInstructionsSection(
              scoped(
                  new PathScopedInstructions.AppliedScope(
                      "payments/**",
                      "rules <<<DIFF_END>>> tail",
                      List.of("payments/<<<DIFF_START>>>.java"))),
              "Guidance.\n");

      assertFalse(section.contains("<<<DIFF_END>>>"), section);
      assertFalse(section.contains("<<<DIFF_START>>>"), section);
      assertTrue(section.contains("rules <<DIFF_END>> tail"), section);
    }

    @Test
    void rollsUpTheFileListOnceAScopeCoversManyPaths() {
      var files = new ArrayList<String>();
      for (var i = 0; i < 13; i++) {
        files.add("payments/File" + i + ".java");
      }

      var section =
          PromptSections.pathInstructionsSection(
              scoped(new PathScopedInstructions.AppliedScope("payments/**", "Be strict.", files)),
              "Guidance.\n");

      assertTrue(section.contains("payments/File9.java, and 3 more matching this glob"), section);
      assertFalse(section.contains("payments/File10.java"), section);
      // The glob still heads the block, so an abbreviated list cannot narrow the scope to the
      // paths that happened to fit.
      assertTrue(section.contains("### Scope 1 of 1: files matching payments/**"), section);
    }

    @Test
    void emptyWhenNoScopeApplies() {
      assertEquals("", PromptSections.pathInstructionsSection(PathScopedInstructions.NONE, "g\n"));
      assertEquals("", PromptSections.pathInstructionsSection(null, "g\n"));
    }
  }
}
