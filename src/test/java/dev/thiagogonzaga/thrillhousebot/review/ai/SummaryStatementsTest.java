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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SummaryStatementsTest {

  /** The kotlin ThrillhouseBot-test#252 summary: its second sentence is the planted gap. */
  private static final String PURPOSE_252 =
      "Adds a nightly billing job that totals each tenant's usage. It writes a billing_runs summary"
          + " row for every run. Failed tenants are retried once.";

  private static final String DIAGRAM_252 =
      """
      flowchart TD
          A[Nightly trigger] --> B[Total tenant usage]
          B --> C[Record billing_runs summary]
          classDef warn fill:#f96
          C --> D[Retry failed tenants once]
      """;

  private static ReviewResponse.Summary summary(
      String purpose, List<ReviewResponse.FileSummary> files, String diagram) {
    return new ReviewResponse.Summary(
        2,
        0,
        1,
        1,
        0,
        "assessment",
        purpose,
        List.of("a gap"),
        List.of("billing"),
        files,
        diagram,
        List.of("G1"));
  }

  private static ReviewResponse.Summary summary252() {
    return summary(
        PURPOSE_252,
        List.of(
            new ReviewResponse.FileSummary("src/BillingJob.kt", "Totals usage per tenant"),
            new ReviewResponse.FileSummary("src/BillingRepo.kt", "Writes the billing_runs row"),
            new ReviewResponse.FileSummary("README.md", " ")),
        DIAGRAM_252);
  }

  @Test
  void everyStatementGetsAnIdByKind() {
    var statements = SummaryStatements.of(summary252(), true);

    assertEquals(
        """
        [S1] Adds a nightly billing job that totals each tenant's usage.
        [S2] It writes a billing_runs summary row for every run.
        [S3] Failed tenants are retried once.
        [R1] src/BillingJob.kt: Totals usage per tenant
        [R2] src/BillingRepo.kt: Writes the billing_runs row
        [D1] A[Nightly trigger] --> B[Total tenant usage]
        [D2] B --> C[Record billing_runs summary]
        [D3] C --> D[Retry failed tenants once]
        """,
        statements.render());
  }

  @Test
  void aContradictedSentenceIsCutAndTheRestOfTheParagraphIsKept() {
    var statements = SummaryStatements.of(summary252(), true);

    var verified = statements.without(Set.of("S2"));

    assertEquals(
        "Adds a nightly billing job that totals each tenant's usage. Failed tenants are retried"
            + " once.",
        verified.prPurpose());
    assertEquals(summary252().fileSummaries(), verified.fileSummaries());
    assertEquals(DIAGRAM_252, verified.walkthroughDiagram());
  }

  @Test
  void theFirstOrLastSentenceIsCutWithItsBreak() {
    var statements = SummaryStatements.of(summary252(), false);

    assertEquals(
        "It writes a billing_runs summary row for every run. Failed tenants are retried once.",
        statements.without(Set.of("S1")).prPurpose());
    assertEquals(
        "Adds a nightly billing job that totals each tenant's usage. It writes a billing_runs"
            + " summary row for every run.",
        statements.without(Set.of("S3")).prPurpose());
    assertEquals(
        "Adds a nightly billing job that totals each tenant's usage.",
        statements.without(Set.of("S2", "S3")).prPurpose());
  }

  @Test
  void cuttingEverySentenceLeavesNoPurpose() {
    var statements = SummaryStatements.of(summary252(), false);

    assertNull(statements.without(Set.of("S1", "S2", "S3")).prPurpose());
  }

  @Test
  void lineAndParagraphBreaksAroundACutSentenceAreKept() {
    var purpose = "One. Two.\n\nThree. Four.\nFive.";
    var statements = SummaryStatements.of(summary(purpose, List.of(), null), false);

    assertEquals(
        List.of("One.", "Two.", "Three.", "Four.", "Five."),
        statements.statements().stream().map(SummaryStatements.Statement::text).toList());
    assertEquals("One.\n\nThree. Four.\nFive.", statements.without(Set.of("S2")).prPurpose());
    assertEquals("One. Two.\n\nFour.\nFive.", statements.without(Set.of("S3")).prPurpose());
    assertEquals("One. Two.\n\nThree.\nFive.", statements.without(Set.of("S4")).prPurpose());
    assertEquals("One. Two.\n\nThree. Four.", statements.without(Set.of("S5")).prPurpose());
    assertEquals("One. Two.\n\nFive.", statements.without(Set.of("S3", "S4")).prPurpose());
  }

  @Test
  void aCrlfLineBreakEndsASentenceToo() {
    var statements =
        SummaryStatements.of(summary("One.\r\nTwo.\r\nThree.", List.of(), null), false);

    assertEquals(3, statements.statements().size());
    assertEquals("One.\r\nThree.", statements.without(Set.of("S2")).prPurpose());
  }

  @Test
  void abbreviationsDecimalsAndLowerCaseContinuationsDoNotEndASentence() {
    var purpose =
        "Caches responses, e.g. the user list, for 1.5 minutes. Handles \"quoted.\" Next one (see"
            + " notes.) Last!";
    var statements = SummaryStatements.of(summary(purpose, List.of(), null), false);

    assertEquals(
        List.of(
            "Caches responses, e.g. the user list, for 1.5 minutes.",
            "Handles \"quoted.\"",
            "Next one (see notes.)",
            "Last!"),
        statements.statements().stream().map(SummaryStatements.Statement::text).toList());
  }

  @Test
  void aContradictedRowLosesItsFileSummaryAndTheOthersStay() {
    var statements = SummaryStatements.of(summary252(), true);

    var verified = statements.without(Set.of("R2"));

    assertEquals(
        List.of(
            new ReviewResponse.FileSummary("src/BillingJob.kt", "Totals usage per tenant"),
            new ReviewResponse.FileSummary("README.md", " ")),
        verified.fileSummaries());
    assertEquals(PURPOSE_252, verified.prPurpose());
  }

  @Test
  void anyContradictedDiagramLineDropsTheDiagram() {
    var statements = SummaryStatements.of(summary252(), true);

    var verified = statements.without(Set.of("D2"));

    assertNull(verified.walkthroughDiagram());
    assertEquals(PURPOSE_252, verified.prPurpose());
    assertEquals(summary252().fileSummaries(), verified.fileSummaries());
  }

  @Test
  void theDiagramIsNotJudgedWhenItIsNotRendered() {
    var statements = SummaryStatements.of(summary252(), false);

    assertTrue(statements.render().lines().noneMatch(l -> l.startsWith("[D")));
    assertEquals(DIAGRAM_252, statements.without(Set.of("D1")).walkthroughDiagram());
  }

  @Test
  void layoutAndStyleLinesAndFencesAreNotStatements() {
    var diagram =
        """
        ```mermaid
        sequenceDiagram
        %% a comment
        subgraph Billing
        direction LR
        style A fill:#fff
        linkStyle 0 stroke:#f00
        class A warn
        click A callback
        end
        Alice->>Bob: charge
        ```
        """;
    var statements = SummaryStatements.of(summary(null, List.of(), diagram), true);

    assertEquals("[D1] Alice->>Bob: charge\n", statements.render());
  }

  @Test
  void otherFieldsAreCarriedUnchanged() {
    var original = summary252();

    var verified = SummaryStatements.of(original, true).without(Set.of("S2", "R1", "D1"));

    assertEquals(original.totalFindings(), verified.totalFindings());
    assertEquals(original.high(), verified.high());
    assertEquals(original.medium(), verified.medium());
    assertEquals(original.overallAssessment(), verified.overallAssessment());
    assertEquals(original.descriptionGaps(), verified.descriptionGaps());
    assertEquals(original.suggestedLabels(), verified.suggestedLabels());
    assertEquals(original.addressedGaps(), verified.addressedGaps());
  }

  @Test
  void idsAreMatchedWithoutCaseAndUnknownIdsChangeNothing() {
    var original = summary252();
    var statements = SummaryStatements.of(original, true);

    assertSame(original, statements.without(Set.of()));
    assertSame(original, statements.without(Set.of("S9", "R7", "X1")));
    assertNull(statements.without(Set.of("D1")).walkthroughDiagram());
  }

  @Test
  void aSummaryWithNothingToJudgeHasNoStatements() {
    assertTrue(SummaryStatements.of(null, true).isEmpty());
    assertNull(SummaryStatements.of(null, true).without(Set.of("S1")));
    var empty =
        summary(
            "  ",
            List.of(
                new ReviewResponse.FileSummary("a", null),
                new ReviewResponse.FileSummary(null, "")),
            null);
    assertTrue(SummaryStatements.of(empty, true).isEmpty());
    assertTrue(SummaryStatements.of(summary(null, List.of(), "flowchart TD\n"), true).isEmpty());
    assertTrue(SummaryStatements.of(summary(null, List.of(), " \n "), true).isEmpty());
  }

  @Test
  void aRowWithNoPathIsStillJudged() {
    var statements =
        SummaryStatements.of(
            summary(null, List.of(new ReviewResponse.FileSummary(null, "Sorts the rows")), null),
            false);

    assertEquals("[R1] : Sorts the rows\n", statements.render());
    assertEquals(List.of(), statements.without(Set.of("R1")).fileSummaries());
  }

  @Test
  void statementsAreCappedInCountAndLength() {
    var files = new ArrayList<ReviewResponse.FileSummary>();
    for (int i = 0; i < SummaryStatements.MAX_STATEMENTS + 10; i++) {
      files.add(new ReviewResponse.FileSummary("f" + i, "x".repeat(700)));
    }
    var statements = SummaryStatements.of(summary(null, files, null), false);

    assertEquals(SummaryStatements.MAX_STATEMENTS, statements.statements().size());
    assertEquals(
        SummaryStatements.MAX_STATEMENT_CHARS, statements.statements().get(0).text().length());
    // A row past the cap is never judged, so it is never removed.
    var last = "R" + (SummaryStatements.MAX_STATEMENTS + 1);
    assertEquals(files.size(), statements.without(Set.of(last)).fileSummaries().size());
  }

  @Test
  void aStatementSpanningLinesIsSentOnOneLine() {
    var statements =
        SummaryStatements.of(
            summary(null, List.of(new ReviewResponse.FileSummary("a", "one\ntwo")), null), false);

    assertEquals("[R1] a: one two\n", statements.render());
  }

  @Test
  void sentencesPastTheCapAreKeptWhenAnEarlierOneIsCut() {
    var purpose = new StringBuilder();
    for (int i = 1; i <= SummaryStatements.MAX_STATEMENTS + 5; i++) {
      purpose.append("Sentence ").append(i).append(". ");
    }
    var statements = SummaryStatements.of(summary(purpose.toString(), List.of(), null), false);

    var verified = statements.without(Set.of("S1"));

    assertTrue(verified.prPurpose().startsWith("Sentence 2. "), verified.prPurpose());
    assertTrue(
        verified.prPurpose().endsWith("Sentence " + (SummaryStatements.MAX_STATEMENTS + 5) + "."),
        verified.prPurpose());
  }

  @Test
  void whitespaceAroundThePurposeIsNotPartOfASentence() {
    var statements = SummaryStatements.of(summary("  \n  One.  Two.  \n", List.of(), null), false);

    assertEquals("[S1] One.\n[S2] Two.\n", statements.render());
    assertEquals("Two.", statements.without(Set.of("S1")).prPurpose());
    assertEquals("One.", statements.without(Set.of("S2")).prPurpose());
  }

  @Test
  void blankDiagramLinesAndStateDiagramHeadersAreNotStatements() {
    var diagram = "stateDiagram-v2\n\n  [*] --> Billing\n\n  Billing --> [*]\n";
    var statements = SummaryStatements.of(summary(null, List.of(), diagram), true);

    assertEquals("[D1] [*] --> Billing\n[D2] Billing --> [*]\n", statements.render());
    assertTrue(
        SummaryStatements.of(summary("One.", List.of(), null), true).render().startsWith("[S1]"));
  }
}
