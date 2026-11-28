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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every system prompt says which language to write in (#1467). */
class PromptLanguageRuleTest {

  private static final Map<String, String> SYSTEM_PROMPTS =
      Map.ofEntries(
          Map.entry("review", PrReviewPrompts.SYSTEM),
          Map.entry(
              "routed review",
              PrReviewPrompts.reviewSystemPrompt(EnumSet.noneOf(ReviewDimension.class))),
          Map.entry("summary", PrReviewPrompts.SUMMARY_SYSTEM),
          Map.entry("verifier", FindingVerifierPrompts.SYSTEM),
          Map.entry(
              "routed verifier",
              FindingVerifierPrompts.verifierSystemPrompt(EnumSet.noneOf(ReviewDimension.class))),
          Map.entry("summary verifier", SummaryVerifierPrompts.SYSTEM),
          Map.entry("repair", ResponseRepairPrompts.SYSTEM),
          Map.entry("reply", ReplyAssistantPrompts.SYSTEM),
          Map.entry("changelog", ChangelogAssistantPrompts.SYSTEM),
          Map.entry("changelog merge", ChangelogAssistantPrompts.MERGE_SYSTEM),
          Map.entry("describe", PrDescribeAssistantPrompts.SYSTEM),
          Map.entry("describe synthesis", PrDescribeAssistantPrompts.SYNTHESIS_SYSTEM),
          Map.entry("docs", DocGeneratorPrompts.SYSTEM),
          Map.entry("improve", PrImproveAssistantPrompts.SYSTEM),
          Map.entry("unit tests", UnitTestAssistantPrompts.SYSTEM));

  @Test
  void everySystemPromptAsksForEnglishProseAndVerbatimCodeAndLeadIns() {
    SYSTEM_PROMPTS.forEach(
        (name, prompt) ->
            assertTrue(prompt.contains(PrReviewPrompts.ENGLISH_PROSE_RULE), name + " prompt"));
    assertTrue(PrReviewPrompts.ENGLISH_PROSE_RULE.contains("Write all prose in English"));
    assertTrue(PrReviewPrompts.ENGLISH_PROSE_RULE.contains("every heading or lead-in"));
  }

  @Test
  void theRepairPromptAllowsTranslatingProseAndNoOtherRewording() {
    assertTrue(
        ResponseRepairPrompts.SYSTEM.endsWith(
            "Translating such prose is the only rewording allowed.\n"));
  }
}
