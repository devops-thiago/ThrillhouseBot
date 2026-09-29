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

import dev.thiagogonzaga.thrillhousebot.review.ai.AiResponses.ModelLane;
import dev.thiagogonzaga.thrillhousebot.review.ai.TruncationReport.Stop;
import org.junit.jupiter.api.Test;

/**
 * Pins how a length stop is reported against the cap its request licensed (#895): the figures are
 * always stated, and the setting is offered as the remedy only when the billed completion reached
 * it.
 */
class TruncationReportTest {

  private static final String ACTIVE_SETTING = "thrillhousebot.ai.models.\"m\".max-output-tokens";

  private static TruncationReport active(Integer licensed, Integer prompt, Integer completion) {
    return new TruncationReport(
        ModelLane.ACTIVE,
        new ResponseCap(ModelLane.ACTIVE, licensed, ACTIVE_SETTING),
        prompt,
        completion);
  }

  @Test
  void aStopThatReachedTheCapAdvisesRaisingTheSettingThatSuppliedIt() {
    var report = active(8192, 1000, 8192);

    assertEquals(Stop.AT_CAP, report.stop());
    assertTrue(report.advisesASettingChange());
    assertEquals(
        "licensed max_tokens=8192 (from "
            + ACTIVE_SETTING
            + "); billed 8192 completion tokens,"
            + " 1000 prompt tokens",
        report.figures(TruncationReport.PLAIN));
    assertEquals(
        "The stop reached the licensed cap, so raise "
            + ACTIVE_SETTING
            + ", or leave it unset to use the provider default.",
        report.remedy(TruncationReport.PLAIN));
  }

  @Test
  void aStopShortOfTheCapSaysItIsAProviderBoundAndDoesNotAdviseRaisingIt() {
    // Production, #895: max_tokens=96000 on the request, completion_tokens=65536 billed.
    var report = active(96_000, 163_342, 65_536);

    assertEquals(Stop.SHORT_OF_CAP, report.stop());
    assertFalse(report.advisesASettingChange());
    assertEquals(
        "The provider stopped 30464 tokens short of the licensed cap: that is a provider-side"
            + " bound, and raising `"
            + ACTIVE_SETTING
            + "` will not move it.",
        report.remedy(TruncationReport.MARKDOWN));
  }

  @Test
  void withoutUsageTheReportSaysSoAndKeepsTheLanesStandingAdvice() {
    var report = active(8192, null, null);

    assertEquals(Stop.USAGE_UNREPORTED, report.stop());
    assertTrue(report.advisesASettingChange());
    assertEquals(
        "licensed max_tokens=8192 (from "
            + ACTIVE_SETTING
            + "); usage not reported by the provider",
        report.figures(TruncationReport.PLAIN));
    assertEquals(
        "Raise the active model's max-output-tokens, or leave it unset to use the provider"
            + " default.",
        report.remedy(TruncationReport.PLAIN));
  }

  @Test
  void aStopWithNoCapSentNamesTheSettingThatWouldLicenseMore() {
    var report = active(null, 5000, 32_768);

    assertEquals(Stop.NO_CAP_SENT, report.stop());
    assertTrue(report.advisesASettingChange());
    assertEquals(
        "no max_tokens sent (`"
            + ACTIVE_SETTING
            + "` is unset, so the provider default applied); billed 32768 completion tokens, 5000"
            + " prompt tokens",
        report.figures(TruncationReport.MARKDOWN));
    assertEquals(
        "No cap was sent, so the provider's own default stopped the call at 32768 completion"
            + " tokens; setting "
            + ACTIVE_SETTING
            + " above that licenses more, if the model allows it.",
        report.remedy(TruncationReport.PLAIN));
  }

  @Test
  void aReportWithUsageButNoRecordedCapFallsBackToTheLanesAdvice() {
    var report = new TruncationReport(ModelLane.CONCISE, null, null, 4096);

    assertEquals(Stop.CAP_UNRECORDED, report.stop());
    assertEquals(
        "licensed cap not recorded; billed 4096 completion tokens, prompt tokens not reported",
        report.figures(TruncationReport.PLAIN));
    assertTrue(
        report
            .remedy(TruncationReport.MARKDOWN)
            .startsWith(
                "This call runs on the concise named model, so raise"
                    + " `REVIEW_CONCISE_MAX_OUTPUT_TOKENS`"),
        report.remedy(TruncationReport.MARKDOWN));
  }

  @Test
  void aConciseStopAtItsCapExplainsWhyTheActiveModelsSettingIsNotTheOne() {
    var report =
        new TruncationReport(
            ModelLane.CONCISE,
            new ResponseCap(ModelLane.CONCISE, 8192, "REVIEW_CONCISE_MAX_OUTPUT_TOKENS"),
            20_000,
            8192);

    assertEquals(
        "The stop reached the licensed cap, so raise REVIEW_CONCISE_MAX_OUTPUT_TOKENS (this call"
            + " runs on the concise named model; the active model's max-output-tokens does not cap"
            + " it), or set it empty to drop the cap and use the provider default (unset, it falls"
            + " back to 8192).",
        report.remedy(TruncationReport.PLAIN));
  }

  @Test
  void aConciseLaneWithNoCapSentSaysTheSettingIsEmptyNotUnset() {
    // An unset REVIEW_CONCISE_MAX_OUTPUT_TOKENS falls back to 8192; only an empty value sends no
    // cap, so the figures must describe the state the operator actually put it in.
    var report =
        new TruncationReport(
            ModelLane.CONCISE,
            new ResponseCap(ModelLane.CONCISE, null, "REVIEW_CONCISE_MAX_OUTPUT_TOKENS"),
            3000,
            16_384);

    assertEquals(
        "no max_tokens sent (REVIEW_CONCISE_MAX_OUTPUT_TOKENS is set empty, so the provider"
            + " default applied); billed 16384 completion tokens, 3000 prompt tokens",
        report.figures(TruncationReport.PLAIN));
  }

  @Test
  void theConciseLanesStandingAdviceDoesNotSayUnsetDropsTheCap() {
    var report = new TruncationReport(ModelLane.CONCISE, null, null, null);

    assertEquals(
        "This call runs on the concise named model, so raise REVIEW_CONCISE_MAX_OUTPUT_TOKENS (the"
            + " active model's max-output-tokens does not cap it), or set it empty to drop the cap"
            + " and use the provider default (unset, it falls back to 8192).",
        report.remedy(TruncationReport.PLAIN));
  }

  @Test
  void describeJoinsTheFiguresAndTheRemedy() {
    var report = active(8192, 1000, 8192);

    assertEquals(
        "Cap and usage: "
            + report.figures(TruncationReport.PLAIN)
            + ". "
            + report.remedy(TruncationReport.PLAIN),
        report.describe(TruncationReport.PLAIN));
  }
}
