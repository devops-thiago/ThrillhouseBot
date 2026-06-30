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
 * Pins the config/IaC severity-recalibration guidance added for #238 so a future prompt edit cannot
 * silently revert it and re-introduce the suppression. The reviewer was high-precision but
 * suppressed declarative/config/IaC findings (RBAC, container hardening, schema-invalid manifests)
 * because the SECURITY dimension named only application-code threats and severity was anchored on
 * "will fail at runtime". These assertions are intentionally coarse — they check the recalibration
 * intent survives, not exact wording; an intentional rewording should update the matching anchor.
 *
 * <p>The automated LLM eval that checks the model actually <em>acts</em> on this guidance is
 * tracked separately (#113); this is the cheap deterministic guard.
 */
class PrReviewPromptsContentTest {

  private static void assertContains(String haystack, String needle, String why) {
    assertTrue(haystack.contains(needle), why + " — missing marker: \"" + needle + "\"");
  }

  @Test
  void generatorPromptBroadensSecurityToInfraAndConfig() {
    String sys = PrReviewPrompts.SYSTEM;
    assertContains(
        sys, "least privilege", "SECURITY must name over-broad RBAC/IAM (least privilege)");
    assertContains(
        sys, "securityContext", "SECURITY must name container hardening (securityContext)");
    assertContains(
        sys, "automounting", "SECURITY must name service-account token automounting exposure");
  }

  @Test
  void generatorPromptHasConfigIacCorrectnessDimension() {
    String sys = PrReviewPrompts.SYSTEM;
    assertContains(
        sys, "CONFIG / IaC CORRECTNESS", "the config/IaC correctness review dimension must exist");
    assertContains(
        sys,
        "schema validation",
        "config/IaC dimension must cover manifests that fail schema validation");
  }

  @Test
  void generatorPromptRecalibratesSeverityBeyondRuntime() {
    String sys = PrReviewPrompts.SYSTEM;
    assertContains(
        sys,
        "schema/lint/CI validation",
        "severity must let an apply/validation-time failure reach high, not only runtime crashes");
    assertContains(
        sys,
        "not a nitpick",
        "the low-severity exclusion must carve out genuine config/hardening findings");
    assertContains(
        sys,
        "apply, validation, or CI time",
        "the runtime-failure self-check must offer a config-aware defence path");
  }

  @Test
  void verifierPromptDoesNotResuppressDemonstrableConfigFindings() {
    String sys = FindingVerifierPrompts.SYSTEM;
    assertContains(
        sys,
        "config/IaC finding whose breakage is visible",
        "verifier must not reject demonstrable config findings as remembered framework behavior");
    assertContains(
        sys,
        "config/IaC defect whose breakage is visible",
        "verifier severity calibration must let demonstrable config defects stand at high");
  }

  @Test
  void generatorPromptKeepsTheCrossLocationConsistencyGuard() {
    String sys = PrReviewPrompts.SYSTEM;
    // Regression: adding the config/IaC bullet once overwrote this bullet's opening line, orphaning
    // its body and dropping the guard that forces the model to verify both sides of an "X does this
    // but Y does not" claim before flagging it. Both header and body must remain, as one bullet.
    assertContains(
        sys,
        "A claim that two places are inconsistent",
        "the cross-location-inconsistency guard must remain a complete, headed bullet");
    assertContains(
        sys,
        "both places verbatim from the provided material",
        "the guard's body must stay attached to its header, not dangle as a fragment");
  }
}
