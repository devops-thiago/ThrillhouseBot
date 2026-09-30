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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * Pins when the step-down repeat's guard calls content deliberation with no answer begun (#893).
 */
class InlinedDeliberationGuardTest {

  private static final String PROSE = "Let me think about this change. ".repeat(300);

  @Test
  void contentBelowTheBoundIsNotJudged() {
    var guard = new InlinedDeliberationGuard();

    assertTrue(guard.check(null).isEmpty());
    assertEquals(
        OptionalInt.of(PROSE.length()),
        guard.check(PROSE),
        "a call not yet judged is judged when the bound is reached");
  }

  @Test
  void proseWithNoObjectOpeningIsStoppedOnceAndNeverAgain() {
    var guard = new InlinedDeliberationGuard();

    assertFalse(guard.decided());
    assertEquals(OptionalInt.of(PROSE.length()), guard.check(PROSE));
    assertTrue(guard.decided());
    assertTrue(guard.check(PROSE).isEmpty(), "the guard decides once per call");
  }

  @Test
  void anAnswerOpeningAnywhereInTheContentLetsTheCallRun() {
    var guard = new InlinedDeliberationGuard();

    assertTrue(guard.check("Sure.\n```json\n{ \"findings\" : [" + PROSE).isEmpty());
    assertTrue(guard.check(PROSE).isEmpty(), "once the answer opened, nothing is judged again");
  }

  @Test
  void codeBracesWithoutAQuotedKeyAreNotAnAnswer() {
    var guard = new InlinedDeliberationGuard();
    var swift = "```swift\nfunc load() { guard ready else { return } }\n```\n" + PROSE;

    assertEquals(OptionalInt.of(swift.length()), guard.check(swift));
  }
}
