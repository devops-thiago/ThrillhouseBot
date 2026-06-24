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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
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
}
