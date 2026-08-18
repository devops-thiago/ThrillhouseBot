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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import org.junit.jupiter.api.Test;

/** Unit tests for the PR-context bound applied on the verification call (#736). */
class PrContextBudgetTest {

  /** The shipped per-call input budget: {@code 48000 * 0.9 - 8192}. */
  private static final int SHIPPED_BUDGET = 35_008;

  private final TokenCounter counter = new TokenCounter();

  /** A GitHub-maximum description, as ordinary prose. */
  private static String maximumDescription() {
    return "The author's stated intent for this change. ".repeat(1525);
  }

  @Test
  void keepsTheFenceAroundWhatSurvivesAndDisclosesTheCut() {
    var block = PromptTemplateEscaper.fence(PromptSections.prContext("T", maximumDescription()));

    var bounded = PrContextBudget.bound(block, SHIPPED_BUDGET, counter);

    var fenceLine = block.substring(0, block.indexOf('\n'));
    assertTrue(counter.estimateTokens(bounded) <= SHIPPED_BUDGET / 10);
    assertTrue(bounded.startsWith(fenceLine + "\n"));
    assertTrue(bounded.endsWith(PrContextBudget.TRUNCATION_NOTICE));
    assertTrue(bounded.contains("\n" + fenceLine + "\n" + PrContextBudget.TRUNCATION_NOTICE));
    assertTrue(bounded.contains("Title: T\nDescription:\nThe author's stated intent"));
  }

  @Test
  void boundsAnUnfencedBlockWithoutInventingAFence() {
    var block = PromptSections.prContext("T", maximumDescription());

    var bounded = PrContextBudget.bound(block, SHIPPED_BUDGET, counter);

    assertFalse(bounded.contains(PromptTemplateEscaper.fencePrefix()));
    assertTrue(bounded.startsWith("Title: T\nDescription:\n"));
    assertTrue(bounded.endsWith("\n" + PrContextBudget.TRUNCATION_NOTICE));
    assertTrue(counter.estimateTokens(bounded) <= SHIPPED_BUDGET / 10);
  }

  @Test
  void boundsABlockTooShortToCarryAFence() {
    // A two-line block has no fence to preserve — the whole of it is content, and the bound still
    // has to hold. Driven by a budget small enough that an ordinary block overruns its share.
    var block = "Title: T\nDescription: " + "budget ".repeat(200);

    var bounded = PrContextBudget.bound(block, 1500, counter);

    assertTrue(bounded.startsWith("Title: T\nDescription: budget"));
    assertTrue(bounded.endsWith("\n" + PrContextBudget.TRUNCATION_NOTICE));
    assertFalse(bounded.contains(PromptTemplateEscaper.fencePrefix()));
  }

  @Test
  void leavesABlockWithinItsShareUntouched() {
    var block = PromptTemplateEscaper.fence(PromptSections.prContext("T", "Fixes the parser."));

    assertSame(block, PrContextBudget.bound(block, SHIPPED_BUDGET, counter));
  }

  @Test
  void leavesEveryBlockUntouchedWhenBudgetingIsDisabled() {
    // The planner reports Integer.MAX_VALUE for an explicit max-input-tokens <= 0. That is the
    // operator asking for no cap, and this is not the place to reintroduce one.
    var block = PromptTemplateEscaper.fence(PromptSections.prContext("T", maximumDescription()));

    assertSame(block, PrContextBudget.bound(block, Integer.MAX_VALUE, counter));
    assertSame("", PrContextBudget.bound("", Integer.MAX_VALUE, counter));
  }

  @Test
  void cutsOnACodePointBoundary() {
    // The cut must never split a surrogate pair: a lone half is not text the model can read, and
    // the block is handed straight to the prompt.
    var block = PromptSections.prContext("T", "😀".repeat(20_000));

    var bounded = PrContextBudget.bound(block, SHIPPED_BUDGET, counter);

    var body = bounded.substring(0, bounded.length() - PrContextBudget.TRUNCATION_NOTICE.length());
    assertEquals(
        body,
        new String(
            body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            java.nio.charset.StandardCharsets.UTF_8));
  }
}
