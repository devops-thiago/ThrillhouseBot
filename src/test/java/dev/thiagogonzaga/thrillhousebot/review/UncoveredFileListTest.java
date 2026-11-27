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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class UncoveredFileListTest {

  private static List<String> files(String prefix, int count) {
    return IntStream.rangeClosed(1, count).mapToObj(i -> prefix + i + ".java").toList();
  }

  private static ReviewResult.TruncationDetail failed(
      List<String> names, Map<String, String> causes) {
    return new ReviewResult.TruncationDetail(
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        names,
        SummaryDegradation.NONE,
        VerificationCoverage.EMPTY,
        causes);
  }

  @Test
  void nothingIsListedWhenTheBannerAlreadyNamesEveryFile() {
    assertEquals("", UncoveredFileList.section(failed(files("a", 10), Map.of())));
  }

  @Test
  void eachCauseGetsItsOwnClauseAndItsOwnNameLimit() {
    var detail =
        failed(
            List.of("slow.java", "down.java", "unknown.java"),
            Map.of(
                "slow.java",
                CallFailureCause.TIMED_OUT,
                "down.java",
                "the AI provider returned HTTP 503"));

    var clause = ReviewResult.coverageGapClause(3, detail);

    assertTrue(clause.contains("did not complete: the call timed out (`slow.java`)"), clause);
    assertTrue(
        clause.contains("did not complete: the AI provider returned HTTP 503 (`down.java`)"),
        clause);
    assertTrue(clause.contains("did not complete (`unknown.java`)"), clause);
    assertEquals("", UncoveredFileList.section(detail));
  }

  @Test
  void everyClassIsListedWithItsReasonOnceOneOfThemIsElided() {
    var detail =
        new ReviewResult.TruncationDetail(
            files("omitted", 11),
            List.of("clipped.java"),
            List.of("logo.png"),
            List.of("ceiling.java"),
            List.of("cut.java"),
            List.of("failed.java"),
            SummaryDegradation.NONE,
            VerificationCoverage.EMPTY,
            Map.of());

    var section = UncoveredFileList.section(detail);

    assertTrue(section.contains("<summary>All 16 files not fully reviewed</summary>"), section);
    assertTrue(section.contains("- `failed.java`: not reviewed, the call failed"), section);
    assertTrue(section.contains("- `ceiling.java`: not reviewed, token spend ceiling"), section);
    assertTrue(section.contains("- `omitted11.java`: omitted, review budget"), section);
    assertTrue(section.contains("- `logo.png`: no diff content from GitHub"), section);
    assertTrue(section.contains("- `cut.java`: partially reviewed, response cut"), section);
    assertTrue(section.contains("- `clipped.java`: partially analyzed, review budget"), section);
  }

  @Test
  void aVeryLongListEndsInACountAndAFileNameCannotBreakOutOfItsCodeSpan() {
    var names = new java.util.ArrayList<String>();
    names.add("evil`\n</details>.java");
    names.addAll(files("f", UncoveredFileList.MAX_ENTRIES + 4));

    var section = UncoveredFileList.section(failed(names, Map.of()));

    assertTrue(section.contains("- `evil </details>.java`: not reviewed"), section);
    assertTrue(section.contains("- and 5 more\n"), section);
    assertFalse(section.contains("f" + (UncoveredFileList.MAX_ENTRIES + 4) + ".java"), section);
  }

  @Test
  void aPlanKeepsTheCausesItWasBuiltWithAndADetailWithoutAnyReportsNone() {
    var plan =
        new DiffBudgetPlanner.BudgetPlan(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            new java.util.HashMap<>(Map.of("a.java", CallFailureCause.TIMED_OUT)));

    assertEquals(Map.of("a.java", CallFailureCause.TIMED_OUT), plan.callFailureCauses());
    assertEquals(Map.of(), failed(List.of("a.java"), null).callFailureCauses());
  }
}
