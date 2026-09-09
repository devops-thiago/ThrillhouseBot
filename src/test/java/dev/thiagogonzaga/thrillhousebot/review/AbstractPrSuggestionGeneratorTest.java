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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class AbstractPrSuggestionGeneratorTest {

  /** Minimal concrete subclass that reaches the no-args constructor through the default super(). */
  private static final class ProxyShape extends AbstractPrSuggestionGenerator {}

  @Test
  void noArgsConstructorIsAvailableForTheCdiClientProxy() {
    // The protected no-args constructor exists only so ArC can synthesize the client proxy for the
    // @ApplicationScoped concrete generators; it must stay constructible by a subclass.
    assertNotNull(new ProxyShape());
  }

  @Test
  void sharedPromptOverheadIsTheSameStringForTheSameInputs() {
    // #604: this string is what the planner subtracts from the per-call budget before sizing
    // batches. It used to carry a live fence draw, whose BPE width varies by tens of tokens, so
    // two plans for one input could differ with nothing changed. The fence scaffolding is sized
    // from a fixed-width stand-in now, so the overhead, and with it the plan, follows from the
    // inputs alone.
    var generator = new ProxyShape();
    var inputs =
        new AbstractPrSuggestionGenerator.Inputs("diff", "Title", "Body", "", "sha", List.of());

    assertEquals(
        generator.sharedPromptOverhead("system", "user", inputs),
        generator.sharedPromptOverhead("system", "user", inputs));
  }
}
