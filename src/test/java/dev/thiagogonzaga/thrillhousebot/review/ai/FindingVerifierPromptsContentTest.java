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
 * Content guards on the verifier prompt's carve-outs. Prompt text is not executable, so what these
 * pin is that a carve-out the corpus needed is still present and still says the thing it was added
 * to say — the rejection ground it exempts, and the direction it must not reopen.
 */
class FindingVerifierPromptsContentTest {

  private static void assertContains(String haystack, String needle, String why) {
    assertTrue(haystack.contains(needle), why + " — missing marker: \"" + needle + "\"");
  }

  @Test
  void verifierCarvesOutADemonstratedInjectionSink() {
    String sys = FindingVerifierPrompts.SYSTEM;
    assertContains(
        sys,
        "An injection-sink finding",
        "the verifier must judge a demonstrated injection sink on its own terms (#605)");
    assertContains(
        sys,
        "dangerouslySetInnerHTML",
        "the carve-out must name the framework HTML-injection escape hatches it covers");
    assertContains(
        sys,
        "is NOT remembered framework behavior",
        "a sink and tainted value both visible must not be demoted as remembered behavior (#605)");
    assertContains(
        sys,
        "routing and rendering semantics",
        "the carve-out must name the rejection ground it exempts, which reads exactly this way");
    assertContains(
        sys,
        "lowers CONFIDENCE, never the risk",
        "an unshown mitigating layer must move confidence, not severity (#570)");
  }

  @Test
  void injectionSinkCarveOutStillRejectsASinkTheMaterialNeutralizes() {
    String sys = FindingVerifierPrompts.SYSTEM;
    assertContains(
        sys,
        "Reject it only when the provided material shows the neutralizing",
        "the carve-out must keep the rejection path for a sink the material shows is sanitized");
    assertContains(
        sys,
        "not attacker-influenced",
        "a sink fed a literal must stay rejectable, so the carve-out is not a blanket exemption");
  }

  @Test
  void verifierDoesNotTreatStandardLibrarySemanticsAsUnestablished() {
    String sys = FindingVerifierPrompts.SYSTEM;
    assertContains(
        sys,
        "This ground also does NOT cover documented semantics of the language or its own",
        "the verifier must not reject a finding for resting on standard-library semantics (#589)");
    assertContains(
        sys,
        "List.max or head on an empty collection throws",
        "the carve-out must keep the Scala #21 regression example that was destroyed downstream");
    assertContains(
        sys,
        "established by the provided material\": judge it on whether the material shows the",
        "the carve-out must name the rejection wording it overrides (#589)");
    assertContains(
        sys,
        "triggering case (the empty collection, the out-of-range index) can reach the call",
        "such a finding must be judged on reachability of the triggering case, not on recall");
  }

  @Test
  void standardLibraryCarveOutKeepsTheGroundForRepoStateAndUnshownCallers() {
    String sys = FindingVerifierPrompts.SYSTEM;
    assertContains(
        sys,
        "state not shown (files, solution or project files, manifests), for unshown callers,",
        "repo state must stay a valid rejection ground — the C# #24 half of #589 is defensible");
    assertContains(
        sys,
        "and for unshown configuration; those genuinely are outside the material.",
        "unshown callers and configuration must stay valid rejection grounds (#589)");
  }

  @Test
  void severityCalibrationExemptsBothDemonstrableClassesFromTheMediumCap() {
    String sys = FindingVerifierPrompts.SYSTEM;
    assertContains(
        sys,
        "semantics is not one of those, so that cap does not apply to it either",
        "the medium/low cap must not apply to a standard-library semantics claim (#589)");
    assertContains(
        sys,
        "visible in the provided material is likewise demonstrable here and is not capped",
        "the medium/low cap must not apply to a demonstrated injection sink (#605)");
  }

  @Test
  void verifierDoesNotTreatBuildToolConventionsAsUnestablished() {
    String sys = FindingVerifierPrompts.SYSTEM;
    assertContains(
        sys,
        "This ground likewise does NOT cover",
        "the verifier must not reject a finding for resting on build-tool conventions (#646)");
    assertContains(
        sys,
        "documented conventions of a build tool or framework that the provided material itself",
        "the carve-out must name the class it covers: conventions the material itself configures");
    assertContains(
        sys,
        "<base>-all.jar",
        "the kotlin #56 regression rested on Shadow's default naming, so it must be named");
    assertContains(
        sys,
        "a lockfile-based install step requires the lockfile to be committed",
        "the python #51 regression rested on lockfile requirements, so it must be named");
    assertContains(
        sys,
        "a framework CLI runs only the targets its project",
        "the angular #61 regression rested on CLI architect targets, so it must be named");
    assertContains(
        sys,
        "not on whether the diff restates the tool's documentation",
        "such a finding is judged on the build file shown, not on the diff proving the tool");
  }

  @Test
  void verifierJudgesAnArtifactReferenceFindingOnTheProducingSideItIsShown() {
    String sys = FindingVerifierPrompts.SYSTEM;
    assertContains(
        sys,
        "An artifact-reference finding",
        "the verifier must judge a build/run instruction naming an unproduced path (#646)");
    assertContains(
        sys,
        "asserts a NON-EXISTENCE, so no quoted line",
        "the carve-out must name why the class cannot satisfy the quote/establish grounds");
    assertContains(
        sys,
        "lowers CONFIDENCE only and is never a rejection",
        "an unshown pre-existing file must move confidence, not delete the finding (#646)");
    assertContains(
        sys,
        "invent unshown material that rescues the code",
        "the unshown-material grounds must not be run in reverse to rescue the code (#646)");
    assertContains(
        sys,
        "\"Not in the diff\" describes the window",
        "the material window is not the world — the framing recorded on #636");
  }

  @Test
  void artifactReferenceCarveOutKeepsItsRejectionPathsAndTheUnshownArtifactCap() {
    String sys = FindingVerifierPrompts.SYSTEM;
    assertContains(
        sys,
        "producing step or the committed file that satisfies the reference,",
        "a reference the material shows satisfied must stay rejectable (#646)");
    assertContains(
        sys,
        "rests on nothing",
        "a finding naming neither a build file nor a committed filename must stay rejectable");
    assertContains(
        sys,
        "That cap is about an artifact whose CONTENTS or BEHAVIOR you cannot see, not",
        "the unshown-artifact severity cap must not swallow an artifact-reference finding (#646)");
  }
}
