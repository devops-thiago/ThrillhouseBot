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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link TokenCounter} — the jtokkit-backed estimate behind diff budgeting. */
class TokenCounterTest {

  private final TokenCounter counter = new TokenCounter();

  @Test
  void emptyAndNullCountZero() {
    assertEquals(0, counter.estimateTokens(null));
    assertEquals(0, counter.estimateTokens(""));
  }

  @Test
  void countsRealBpeTokensNotCharacters() {
    String code =
        """
        public record DiffBatch(List<FileDiff> files, int estimatedTokens) {
          boolean fits(int budget) { return estimatedTokens <= budget; }
        }
        """;
    int tokens = counter.estimateTokens(code);
    assertTrue(tokens > 10, "expected a non-trivial token count, got " + tokens);
    assertTrue(tokens < code.length(), "tokens must be fewer than characters, got " + tokens);
    double charsPerToken = (double) code.length() / tokens;
    assertTrue(
        charsPerToken > 1.5 && charsPerToken < 8.0,
        "implausible chars/token ratio: " + charsPerToken);
  }

  @Test
  void isDeterministicAndMonotonic() {
    String unit = "the quick brown fox jumps over the lazy dog\n";
    int once = counter.estimateTokens(unit);
    assertEquals(once, counter.estimateTokens(unit), "same input must give the same estimate");
    assertTrue(once > 0);
    assertTrue(
        counter.estimateTokens(unit.repeat(5)) > once, "more text must estimate more tokens");
  }
}
