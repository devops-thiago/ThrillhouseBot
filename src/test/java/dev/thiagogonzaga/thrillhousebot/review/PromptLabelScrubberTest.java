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
import org.junit.jupiter.params.provider.CsvSource;
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
        "Dimension 3 indexes the batch.",
        "Dimension 5 counts the rows.",
        "Fixes the flaky auth check (security).",
        "Touches existing flows (regressions).",
        "A dependency pin (Security) is fine.",
        "",
      })
  void ordinaryTextAndCodeAreLeftExactlyAsWritten(String text) {
    assertEquals(text, PromptLabelScrubber.scrub(text));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      value = {
        "Stale (see dimension 4).|Stale.",
        "Stale (review dimension 4).|Stale.",
        "Two (dimensions 4 / 5) here.|Two here.",
        "Dimension 7: artifact mismatch.|Artifact mismatch.",
        "* Dimension 4	stale comment.|* Stale comment.",
        "> (dimension 4) Quoted.|> Quoted.",
        "A gap, under dimension 10.|A gap.",
        "It falls under dimension 7, the config check.|It falls under the config check.",
        "under dimension 10 it lands.|\" it lands.\"",
      })
  void labelVariantsAreRemoved(String leaked, String clean) {
    assertEquals(clean, PromptLabelScrubber.scrub(leaked));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Stale (dimension 4 and ) here.",
        "Stale (dimension 4, ) here.",
        "Summary: dimension 7",
        "Stale (dimension 123) here.",
        "Stale (dimension 4a) here.",
        "Stale (a dimension 4) here.",
        "Dimension 7 42 is the answer.",
        "The list ends at Dimension 7",
        "It found dimension 7 errors.",
        "The thunder dimension 7 rumbles.",
        "A visit is dimension 5 minutes.",
        "The dimension 4 thing and foo:dimension 4 bar.",
        "An unclosed ` backtick (dimension 4",
        "```\nuntouched (dimension 4) in an unclosed fence",
        "~~~\n(dimension 4)\n~~~",
      })
  void nearMissesAndCodeAreLeftAlone(String text) {
    assertEquals(text, PromptLabelScrubber.scrub(text));
  }

  @Test
  void aCodeSpanDoesNotRunPastItsLine() {
    assertEquals("`a\nb` c.", PromptLabelScrubber.scrub("`a\nb` c (dimension 4)."));
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
                    "Dimension 1 correctness gap: no bounds check.",
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
    assertEquals("Correctness gap: no bounds check.", finding.description());
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

    // A leaked status note alone: a reopened decline's note is posted in the review body.
    var noteOnly =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            RiskLevel.LOW,
            ReviewState.APPROVE,
            false,
            "",
            List.of(
                new ReviewResult.PreviousFindingStatus(
                    3, "unresolved", "Still wrong (dimension 4).")),
            List.of(),
            0);
    assertEquals(
        "Still wrong.", PromptLabelScrubber.scrub(noteOnly).previousStatuses().getFirst().note());
  }

  // #950, ThrillhouseBot-test#152 thread 4149758246.
  static final String LEARNING_ID_LEAK =
      "Pin both steps to the full commit SHA for the versions you intend, or note that"
          + " tag-following is a deliberate choice for this repo — the base-image digest policy"
          + " decided elsewhere (e.g. [L51], which cites the release pipeline resolving image"
          + " digests) does not do that resolution for CI actions.";

  static final String LEARNING_ID_CLEAN =
      "Pin both steps to the full commit SHA for the versions you intend, or note that"
          + " tag-following is a deliberate choice for this repo — the base-image digest policy"
          + " decided elsewhere (e.g. a maintainer's earlier decision, which cites the release"
          + " pipeline resolving image digests) does not do that resolution for CI actions.";

  // #950, ThrillhouseBot-test#149 review 5372588106.
  static final String SECTION_NAME_LEAK =
      "The definition (Config.java, line 16 of the config-key definition section, mirrored in"
          + " this diff) reads the key as an integer.";

  static final String SECTION_NAME_CLEAN =
      "The definition (Config.java, line 16 of the configuration code, mirrored in this diff)"
          + " reads the key as an integer.";

  @Test
  void bothRound12PhrasesAreRewrittenToPlainWords() {
    assertEquals(LEARNING_ID_CLEAN, PromptLabelScrubber.scrub(LEARNING_ID_LEAK));
    assertEquals(SECTION_NAME_CLEAN, PromptLabelScrubber.scrub(SECTION_NAME_LEAK));
    assertEquals(LEARNING_ID_CLEAN, PromptLabelScrubber.scrub(LEARNING_ID_CLEAN));
    assertEquals(SECTION_NAME_CLEAN, PromptLabelScrubber.scrub(SECTION_NAME_CLEAN));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          # ThrillhouseBot-test#156 comment 4149843646: ids cited together, in parentheses.
          the base-image tag findings the maintainers declined on Dockerfiles ([L51]/[L52]/[L53]), but | the base-image tag findings the maintainers declined on Dockerfiles, but
          declined earlier (see [L12]). | declined earlier.
          The decision [L12] no longer holds. | The decision no longer holds.
          two learnings [L3] and [L4] say so | two learnings say so
          as [L12] states, tags are fine | as a maintainer's earlier decision states, tags are fine
          as [L1], [L2] and [L3] state | as maintainers' earlier decisions state
          [L12] declined this on Dockerfiles. | A maintainer's earlier decision declined this on Dockerfiles.
          Fine. [L12] still applies. | Fine. A maintainer's earlier decision still applies.
          the [L12] reasoning | the maintainer's earlier decision reasoning
          The [L12] reasoning | The maintainer's earlier decision reasoning
          an [L12] reason | a maintainer's earlier decision reason
          per the [L1]/[L2], tags are fine | per maintainers' earlier decisions, tags are fine
          tags are fine (i.e. [L12] holds) | tags are fine (i.e. a maintainer's earlier decision holds)
          cf. [L12] for the reason | cf. a maintainer's earlier decision for the reason
          # ThrillhouseBot-test#156 comments 4149744690 and 4149744783, #158.
          confirmed by the repository's config-key definitions section (which lists only X) | confirmed by the repository's configuration code (which lists only X)
          The repository's config-key definitions section for ARTWORK_DIR gives | The repository's configuration code for ARTWORK_DIR gives
          quoted in the repository's config key definitions section and in the diff | quoted in the repository's configuration code and in the diff
          Config key definitions section: line 3. | Configuration code: line 3.
          shown in the "Config key definitions from the repository" section. | shown in the repository's configuration code.
          The Config key definitions from the repository block shows it. | The repository's configuration code shows it.
          # #154 4149694445, #157 4149687021, #158 4149709454: the prompts' name for their input.
          the only validation in the provided material is queue.zig line 25 | the only validation in the reviewed code is queue.zig line 25
          no intake handler exists anywhere in the provided material — verify it. | no intake handler exists anywhere in the reviewed code — verify it.
          but nothing in the provided material reads that variable. | but nothing in the reviewed code reads that variable.
          The provided material shows no producer. | The reviewed code shows no producer.
          nothing in provided materials builds it | nothing in reviewed code builds it
          """)
  void learningIdsAndContextBlockNamesAreRewritten(String leaked, String clean) {
    assertEquals(clean, PromptLabelScrubber.scrub(leaked));
    assertEquals(clean, PromptLabelScrubber.scrub(clean));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "See [L10](https://github.com/o/r/blob/main/A.java#L10) for the call.",
        "A reference link [L10][1] stays.",
        "matrix[L1] is read before it is set.",
        "The regex `\\[L\\d+\\]` and `[L51]` in code stay.",
        "```\nlog(\"[L51]\")\n```",
        "Ranges such as [L10-L20] stay.",
        "Nothing in the material builds dist/bundle.js.",
        "The vendor-provided material is copied verbatim.",
        "The config key definitions in Config.java read four variables.",
        "Input not in the diff: a sensor_id of `s1; touch /tmp/pwn` passes validation.",
        "no line in the shown sources calls env::var(\"COLDCHAIN_API_TOKEN\")",
        "A [LOW] tag and a [Link] stay."
      })
  void ordinaryBracketsAndMaterialWordsAreLeftAlone(String text) {
    assertEquals(text, PromptLabelScrubber.scrub(text));
  }

  @Test
  void bothRound12PhrasesAreRewrittenOnEveryPostedSurface() {
    var response =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    "medium",
                    "medium",
                    ".github/workflows/ci.yml",
                    9,
                    "Unpinned actions despite [L51]",
                    LEARNING_ID_LEAK,
                    "- uses: actions/checkout@v4 # [L51]",
                    "")),
            List.of(new ReviewResponse.PreviousFindingStatus(2, "unresolved", SECTION_NAME_LEAK)),
            new ReviewResponse.Summary(
                1,
                0,
                0,
                1,
                0,
                SECTION_NAME_LEAK,
                LEARNING_ID_LEAK,
                List.of(SECTION_NAME_LEAK),
                List.of(),
                List.of(new ReviewResponse.FileSummary("Config.java", LEARNING_ID_LEAK)),
                ""));

    var scrubbed = PromptLabelScrubber.scrub(response);

    var finding = scrubbed.findings().getFirst();
    assertEquals("Unpinned actions despite a maintainer's earlier decision", finding.title());
    assertEquals(LEARNING_ID_CLEAN, finding.description());
    assertEquals("- uses: actions/checkout@v4 # [L51]", finding.suggestionOld());
    assertEquals(SECTION_NAME_CLEAN, scrubbed.previousFindingsStatus().getFirst().note());
    var summary = scrubbed.summary();
    assertEquals(SECTION_NAME_CLEAN, summary.overallAssessment());
    assertEquals(LEARNING_ID_CLEAN, summary.prPurpose());
    assertEquals(List.of(SECTION_NAME_CLEAN), summary.descriptionGaps());
    assertEquals(LEARNING_ID_CLEAN, summary.fileSummaries().getFirst().summary());

    // The publisher's guard: a stored round's findings, review body and status notes.
    var result =
        new ReviewResult(
            List.of(
                new Finding(RiskLevel.MEDIUM, "Config.java", 16, "Key", SECTION_NAME_LEAK, "", "")),
            0,
            0,
            1,
            0,
            RiskLevel.MEDIUM,
            ReviewState.COMMENT,
            false,
            "## Summary\n" + LEARNING_ID_LEAK + "\n<!-- thrillhousebot:finding=3 [L51] -->",
            List.of(new ReviewResult.PreviousFindingStatus(3, "unresolved", LEARNING_ID_LEAK)),
            List.of(),
            0);
    var published = PromptLabelScrubber.scrub(result);
    assertEquals(SECTION_NAME_CLEAN, published.findings().getFirst().description());
    assertEquals(
        "## Summary\n" + LEARNING_ID_CLEAN + "\n<!-- thrillhousebot:finding=3 [L51] -->",
        published.summaryMarkdown());
    assertEquals(LEARNING_ID_CLEAN, published.previousStatuses().getFirst().note());
    assertEquals(
        SECTION_NAME_CLEAN,
        PromptLabelScrubber.scrubMarkdown(SECTION_NAME_LEAK, result.findings()));
  }
}
