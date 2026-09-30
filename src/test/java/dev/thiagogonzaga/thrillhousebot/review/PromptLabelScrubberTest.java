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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #918 — the prompt's own block labels, section names and example locator, as the round-9 corpus
 * posted them, and the ordinary text the guard must leave alone.
 */
class PromptLabelScrubberTest {

  // ThrillhouseBot-test#110 review 5367793034.
  @Test
  void aLeadingNumberedLabelGoesAndTheSentenceStillStartsWithACapital() {
    assertEquals(
        "Artifact-name mismatch. rust/Cargo.toml added in this same PR declares",
        PromptLabelScrubber.scrub(
            "Dimension 7 artifact-name mismatch. rust/Cargo.toml added in this same PR declares"));
    assertEquals(
        "Documentation completeness. The doc row added here reads",
        PromptLabelScrubber.scrub(
            "Dimension 10 documentation completeness. The doc row added here reads"));
    assertEquals(
        "Supply-chain class. Lines 1, 7 and 10 read",
        PromptLabelScrubber.scrub("Dimension 2 supply-chain class. Lines 1, 7 and 10 read"));
  }

  @Test
  void aLabelOpeningAClauseOrATableCellGoesToo() {
    assertEquals(
        "- **HIGH:** COPY names roombook-server — Artifact-name mismatch.",
        PromptLabelScrubber.scrub(
            "- **HIGH:** COPY names roombook-server — Dimension 7 artifact-name mismatch."));
    assertEquals(
        "| `docs/CONFIG-RUST.md` | Documentation gap |",
        PromptLabelScrubber.scrub("| `docs/CONFIG-RUST.md` | dimension 10 documentation gap |"));
    assertEquals(
        "- The trace inverts the stated intent.",
        PromptLabelScrubber.scrub("- (dimension 9) The trace inverts the stated intent."));
  }

  @Test
  void aParentheticalLabelGoesWithTheSpaceBeforeIt() {
    assertEquals(
        "Comment-contradiction. Lines 60-61 assert",
        PromptLabelScrubber.scrub("Comment-contradiction (dimension 4). Lines 60-61 assert"));
    assertEquals(
        "Parsing-rule probe on `fn field(body: &str, key: &str) -> Option<String>` (lines 21-23)",
        PromptLabelScrubber.scrub(
            "Parsing-rule probe (heuristic section) on `fn field(body: &str, key: &str) ->"
                + " Option<String>` (lines 21-23)"));
    // ThrillhouseBot-test#102 thread 4145979664.
    assertEquals(
        "This is a description-intent / trace mismatch: the end-to-end behavior",
        PromptLabelScrubber.scrub(
            "This is a description-intent / trace mismatch (dimension 9): the end-to-end"
                + " behavior"));
    assertEquals(
        "Three classes at once.",
        PromptLabelScrubber.scrub("Three classes at once (dimensions 4, 5 and 8)."));
    assertEquals(
        "A stale comment.",
        PromptLabelScrubber.scrub("A stale comment (COMMENT CONTRADICTS CODE)."));
  }

  // ThrillhouseBot-test#110 thread 4145869815.
  @Test
  void anAsideQuotingTheGuidanceGoes() {
    assertEquals(
        "(The repo's Cargo.lock pins the dependency set but does not pin images.)",
        PromptLabelScrubber.scrub(
            "(The repo's Cargo.lock pins the dependency set but, as class guidance notes, does not"
                + " pin images.)"));
  }

  // ThrillhouseBot-test#103 thread 4145983681.
  @Test
  void aRestatedPromptRuleGoes() {
    assertEquals(
        "This contradicts the linked issue #92's acceptance criterion: \"Unconfirmed holds are"
            + " released\"",
        PromptLabelScrubber.scrub(
            "This contradicts the linked issue #92's acceptance criterion, which this PR must"
                + " quote: \"Unconfirmed holds are released\""));
  }

  @Test
  void citationsAfterAVerbGo() {
    assertEquals(
        "This is class (c) - work re-done per iteration.",
        PromptLabelScrubber.scrub("This is dimension 5, class (c) - work re-done per iteration."));
    assertEquals(
        "It is a config-key documentation gap.",
        PromptLabelScrubber.scrub("It is a config-key documentation gap under dimension 10."));
  }

  // ThrillhouseBot-test#108 / #110 round-2 reviews 5367962977 / 5367962482.
  @Test
  void theExampleLocatorBecomesAPlaceholderEvenInsideCode() {
    assertEquals(
        "clear it by commenting `@thrillhousebot resolved <path>:<line> — <the finding's title>`",
        PromptLabelScrubber.scrub(
            "clear it by commenting `@thrillhousebot resolved path/to/File.java:42 — <the"
                + " finding's title>`"));
    assertEquals("see <path> first", PromptLabelScrubber.scrub("see path/to/File.java first"));
  }

  @Test
  void theExampleLocatorIsKeptWhenThePullRequestReallyHasThatFile() {
    var text = "the null check in path/to/File.java:42 is inverted";
    assertEquals(text, PromptLabelScrubber.scrub(text, Set.of("path/to/File.java")));
    // A longer path that merely ends the same way is somebody's real file, never the example.
    var real = "see src/path/to/File.java:42";
    assertEquals(real, PromptLabelScrubber.scrub(real));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "The tensor is reduced along dimension 1 of the output, not dimension 0.",
        "Dimension 1 of the input array is the batch axis.",
        "Dimension 2 is the channel axis, so the slice drops a channel.",
        "Adds a new dimension to the metrics: the dimensions table grows by one row per tenant.",
        "The `dimension 4` string literal is compared case-sensitively.",
        "```\n// Dimension 7: see the heuristic section (dimension 4)\n```",
        "<!-- thrillhousebot:finding=12 dimension 3 -->",
        "The shape (dimension 12) exceeds the configured maximum.",
        "The test covers every section, including the heuristic section of the parser.",
        "Check dimensions 3 x 4 against the layout grid.",
        "",
      })
  void ordinaryTextAndCodeAreLeftExactlyAsWritten(String text) {
    assertEquals(text, PromptLabelScrubber.scrub(text));
  }

  @Test
  void theGuardIsIdempotentAndNullSafe() {
    var once =
        PromptLabelScrubber.scrub(
            "Dimension 7 artifact-name mismatch (dimension 4), which this PR must quote.");
    assertEquals("Artifact-name mismatch.", once);
    assertEquals(once, PromptLabelScrubber.scrub(once));
    assertNull(PromptLabelScrubber.scrub((String) null));
  }

  @Test
  void aFindingKeepsItsInstanceWhenNothingLeakedAndItsCodeFieldsWhenSomethingDid() {
    var clean =
        new Finding(RiskLevel.HIGH, "rust/Dockerfile", 9, "COPY names a binary", "Plain.", "", "");
    assertSame(clean, PromptLabelScrubber.scrub(clean, Set.of()));

    var leaked =
        new Finding(
            RiskLevel.HIGH,
            Confidence.MEDIUM,
            "rust/Dockerfile",
            9,
            "Artifact mismatch (dimension 7)",
            "Dimension 7 artifact-name mismatch.",
            "COPY --from=build /app/target/release/roombook-server /usr/local/bin/",
            "COPY --from=build /app/target/release/roombook /usr/local/bin/");
    var scrubbed = PromptLabelScrubber.scrub(leaked, Set.of());
    assertEquals("Artifact mismatch", scrubbed.title());
    assertEquals("Artifact-name mismatch.", scrubbed.description());
    assertEquals(leaked.suggestionOld(), scrubbed.suggestionOld());
    assertEquals(leaked.suggestionNew(), scrubbed.suggestionNew());
    assertEquals(Confidence.MEDIUM, scrubbed.confidence());
  }

  @Test
  void aModelResponseIsScrubbedInEveryProseFieldAndNowhereElse() {
    var response =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    "high",
                    "medium",
                    "c/linkc.c",
                    12,
                    "Overflow (dimension 1)",
                    "Dimension 1 bounds check missing.",
                    "strcpy(buf, (dimension 1));",
                    "")),
            List.of(
                new ReviewResponse.PreviousFindingStatus(
                    1, "unresolved", "Still open (dimension 4).")),
            new ReviewResponse.Summary(
                1,
                0,
                1,
                0,
                0,
                "Blocks on one defect (dimension 1).",
                "Adds a URL shortener. Dimension 9 trace holds.",
                List.of("The description overstates retries (dimension 9)."),
                List.of("bug"),
                List.of(new ReviewResponse.FileSummary("c/linkc.c", "Core (dimension 1) logic.")),
                "flowchart TD\n  A[\"dimension 1\"]"));

    var scrubbed = PromptLabelScrubber.scrub(response);

    var finding = scrubbed.findings().getFirst();
    assertEquals("Overflow", finding.title());
    assertEquals("Bounds check missing.", finding.description());
    assertEquals("strcpy(buf, (dimension 1));", finding.suggestionOld());
    assertEquals("Still open.", scrubbed.previousFindingsStatus().getFirst().note());
    var summary = scrubbed.summary();
    assertEquals("Blocks on one defect.", summary.overallAssessment());
    assertEquals("Adds a URL shortener. Trace holds.", summary.prPurpose());
    assertEquals(List.of("The description overstates retries."), summary.descriptionGaps());
    assertEquals("Core logic.", summary.fileSummaries().getFirst().summary());
    assertEquals(List.of("bug"), summary.suggestedLabels());
    assertEquals("flowchart TD\n  A[\"dimension 1\"]", summary.walkthroughDiagram());

    var noSummary = new ReviewResponse(List.of(), List.of(), null);
    assertNull(PromptLabelScrubber.scrub(noSummary).summary());
    var clean =
        new ReviewResponse(
            List.of(),
            List.of(),
            new ReviewResponse.Summary(0, 0, 0, 0, 0, "Fine.", "Adds a flag.", List.of()));
    assertSame(clean, PromptLabelScrubber.scrub(clean));
  }

  @Test
  void aReviewResultIsScrubbedInItsFindingsAndItsRenderedSummary() {
    var leaked =
        new Finding(
            RiskLevel.MEDIUM, "go/calendar.go", 32, "No retry", "(dimension 9) Gap.", "", "");
    var result =
        new ReviewResult(
            List.of(leaked),
            0,
            0,
            1,
            0,
            RiskLevel.MEDIUM,
            ReviewState.COMMENT,
            true,
            "## Summary\nA trace mismatch (dimension 9).\n<!-- marker dimension 9 -->",
            List.of(),
            List.of(),
            0);

    var scrubbed = PromptLabelScrubber.scrub(result);

    assertEquals("Gap.", scrubbed.findings().getFirst().description());
    assertEquals(
        "## Summary\nA trace mismatch.\n<!-- marker dimension 9 -->", scrubbed.summaryMarkdown());
    assertEquals(result.reviewState(), scrubbed.reviewState());
    assertEquals(result.mediumCount(), scrubbed.mediumCount());

    var clean =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            RiskLevel.LOW,
            ReviewState.APPROVE,
            true,
            "",
            List.of(),
            List.of(),
            0);
    assertSame(clean, PromptLabelScrubber.scrub(clean));

    // Clean findings under a summary that leaked: the summary alone is rewritten.
    var summaryOnly =
        new ReviewResult(
            List.of(new Finding(RiskLevel.LOW, null, 0, "Plain", "Plain.", "", "")),
            0,
            0,
            0,
            1,
            RiskLevel.LOW,
            ReviewState.COMMENT,
            true,
            "Dimension 4 stale comment.",
            List.of(),
            List.of(),
            0);
    var rewritten = PromptLabelScrubber.scrub(summaryOnly);
    assertEquals("Stale comment.", rewritten.summaryMarkdown());
    assertSame(summaryOnly.findings().getFirst(), rewritten.findings().getFirst());
  }
}
