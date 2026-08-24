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

import org.junit.jupiter.api.Test;

/**
 * Pins the {@code /improve} prompt's buildability guidance so a future edit cannot silently revert
 * it (#772).
 *
 * <p>Covers the constraints that #590 gave {@code /generate-tests} and that never crossed to this
 * sibling command: a committable suggestion must not move a mutated local into a lambda (Java's
 * effectively-final capture rule and its equivalents), must not derive an element count from a
 * fixed-capacity array's size (the declared capacity, not the initialised length), and must state
 * why deleting an existing local or guard is safe rather than labelling a deliberate workaround
 * "redundant".
 *
 * <p>These assertions are intentionally coarse — they check the intent survives, not the exact
 * wording; an intentional rewording should update the matching anchor. Whether the model actually
 * <em>acts</em> on the guidance is an eval question, not a unit-test one; this is the cheap
 * deterministic guard that the guidance is still being sent.
 */
class PrImproveAssistantPromptsContentTest {

  private static void assertContains(String needle, String why) {
    assertTrue(
        PrImproveAssistantPrompts.SYSTEM.contains(needle),
        why + " — missing marker: \"" + needle + "\"");
  }

  @Test
  void promptRequiresSuggestionsToBuildAndPreserveBehavior() {
    assertContains(
        "Every suggestion must still build and behave the same after commit",
        "the buildability contract from #590 must apply to /improve too (#772)");
    assertContains(
        "compiles but silently changes behavior, is worse than no suggestion",
        "a silent behavioral break is the worst failure mode named in #772");
  }

  @Test
  void promptForbidsMovingAMutatedLocalIntoALambda() {
    assertContains(
        "Never move a local variable into a lambda or closure when the variable is assigned",
        "inlining a mutated local into a lambda is a compile error (#772, case 1)");
    assertContains(
        "effectively final",
        "the capture rule the Java case tripped over must be named (#772, case 1)");
    assertContains(
        "stable snapshot of a mutated variable is NOT redundant",
        "the deleted locals were the author's workaround for the capture rule (#772, case 1)");
  }

  @Test
  void promptForbidsDerivingACountFromAFixedCapacityArray() {
    assertContains(
        "Never derive an element count from a fixed-capacity array's size",
        "sizeof-derived counts silently disable features on padded arrays (#772, case 2)");
    assertContains(
        "sizeof(array)/sizeof(array[0])",
        "the exact C idiom that broke custom units must be named (#772, case 2)");
    assertContains(
        "the declared capacity",
        "the derivation yields capacity, not initialised length (#772, case 2)");
  }

  @Test
  void promptRequiresJustifyingTheRemovalOfExistingCode() {
    assertContains(
        "removes an existing local, guard, or check must state in its",
        "a deletion needs an argument for why the code became unnecessary (#772)");
    assertContains(
        "if you cannot say what",
        "the default for an unexplained deletion is to not propose it (#772)");
  }
}
