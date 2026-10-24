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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
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
        "Two (dimensions 4&5) here.|Two here.",
        "Three (dimensions 4,5 or 8) here.|Three here.",
        "Three (dimensions 4 & 5 and 8) here.|Three here.",
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

    // The earlier rounds' findings still open (#917) survive the rebuild, scrubbed too.
    var carried =
        new Finding(RiskLevel.HIGH, "go/store.go", 9, "Open race", "(dimension 10) Race.", "", "");
    var withOpen =
        new ReviewResult(
            List.of(leaked),
            0,
            0,
            1,
            0,
            RiskLevel.MEDIUM,
            ReviewState.COMMENT,
            false,
            "## Summary",
            List.of(),
            List.of(),
            0,
            false,
            true,
            null,
            0,
            List.of(carried));
    var scrubbedOpen = PromptLabelScrubber.scrub(withOpen).openPreviousFindings();
    assertEquals(1, scrubbedOpen.size());
    assertEquals("Race.", scrubbedOpen.getFirst().description());

    // A leak only in a carried finding is still scrubbed, not passed through unchanged.
    var cleanRound =
        new Finding(RiskLevel.MEDIUM, "go/calendar.go", 32, "No retry", "Gap.", "", "");
    var onlyCarriedLeaks =
        new ReviewResult(
            List.of(cleanRound),
            0,
            0,
            1,
            0,
            RiskLevel.MEDIUM,
            ReviewState.COMMENT,
            false,
            "## Summary",
            List.of(),
            List.of(),
            0,
            false,
            true,
            null,
            0,
            List.of(carried));
    assertEquals(
        "Race.",
        PromptLabelScrubber.scrub(onlyCarriedLeaks)
            .openPreviousFindings()
            .getFirst()
            .description());

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
          Tags are fine; [L12] still applies. | Tags are fine; a maintainer's earlier decision still applies.
          Is it fine? [L12] says yes. | Is it fine? A maintainer's earlier decision says yes.
          x.[L12] holds | x.a maintainer's earlier decision holds
          its [L12] says so | its earlier maintainer decision says so
          this [L1]/[L2] pair still holds | this earlier maintainer decisions pair still holds
          tags stay (not [L12]) here | tags stay (not a maintainer's earlier decision) here
          - ([L12]) Tags hold | - Tags hold
          ok (per [L12]). | ok.
          # ThrillhouseBot-test#156 comments 4149744690 and 4149744783, #158.
          confirmed by the repository's config-key definitions section (which lists only X) | confirmed by the repository's configuration code (which lists only X)
          The repository's config-key definitions section for ARTWORK_DIR gives | The repository's configuration code for ARTWORK_DIR gives
          quoted in the repository's config key definitions section and in the diff | quoted in the repository's configuration code and in the diff
          Config key definitions section: line 3. | Configuration code: line 3.
          shown in the "Config key definitions from the repository" section. | shown in the repository's configuration code.
          The Config key definitions from the repository block shows it. | The repository's configuration code shows it.
          line 3 of the config-key definitions sections reads it | line 3 of the configuration code reads it
          the configuration key definitions section lists four keys | the configuration code lists four keys
          the repository's "Config key definitions from the repository" block shows it | the repository's configuration code shows it
          its config key definitions from the repository section | its configuration code
          as in "Config key definitions from the repository", the key | as in the repository's configuration code, the key
          "Config key definitions from the repository" states it. | The repository's configuration code states it.
          shown in Config key definitions from the repository. | shown in the repository's configuration code.
          in the repository's Config-key definitions section. | in the repository's configuration code.
          Fine. Config-key definitions section agrees. | Fine. Configuration code agrees.
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

  @Test
  void anArticleOrDeterminerWrappedOntoTheLineBeforeAnIdIsStillSwapped() {
    assertEquals(
        "the maintainer's earlier decision reasoning fails here",
        PromptLabelScrubber.scrub("the\n[L12] reasoning fails here"));
    // A determiner stays where it is, wrap included; only the id is rewritten.
    assertEquals(
        "its\nearlier maintainer decision says so",
        PromptLabelScrubber.scrub("its\n[L12] says so"));
    assertEquals(
        "the maintainer's earlier decision holds",
        PromptLabelScrubber.scrub("the\u00A0[L12] holds"));
    // A paragraph break is not a wrap: the article ends its own paragraph and stays.
    assertEquals(
        "Ends with the\n\nA maintainer's earlier decision holds.",
        PromptLabelScrubber.scrub("Ends with the\n\n[L12] holds."));
  }

  // #975, ThrillhouseBot-test#207 inline 4152163062.
  static final String CONFIDENCE_RULE_LEAK =
      "The CI run on this commit already completed with a failing test, so the suite itself will"
          + " confirm; I am rating this claim at the required arithmetic-claim confidence until"
          + " that log names the test.";

  static final String CONFIDENCE_RULE_CLEAN =
      "The CI run on this commit already completed with a failing test, so the suite itself will"
          + " confirm.";

  @Test
  void confidenceRuleClauseAfterAStandaloneHyphenGoesWithIt() {
    assertEquals(
        CONFIDENCE_RULE_CLEAN,
        PromptLabelScrubber.scrub(
            "The CI run on this commit already completed with a failing test, so the suite itself"
                + " will confirm - I am rating this claim at the required arithmetic-claim"
                + " confidence until that log names the test."));
  }

  @Test
  void hyphenJoinedToAWordOrOpeningTheTextIsNotASeparator() {
    // Joined to the word before it, the hyphen is part of that word; the clause stays.
    var joined =
        "It fails x-I am rating this claim at the required arithmetic-claim confidence until the"
            + " log names it.";
    assertEquals(joined, PromptLabelScrubber.scrub(joined));
    // A tab before the standalone hyphen separates as a space does.
    assertEquals(
        "It fails.",
        PromptLabelScrubber.scrub(
            "It fails\t- I am rating this claim at the required arithmetic-claim confidence."));
    // A hyphen opening a later line is that line's bullet, not a separator for the line above.
    assertEquals(
        "Findings:\n-",
        PromptLabelScrubber.scrub(
                "Findings:\n- I am rating this claim at the required arithmetic-claim confidence.")
            .stripTrailing());
    // A trailing hyphen on a word ("re- I am…") is that word's, so the clause is not detached at
    // it.
    var trailing =
        "It fails on re- I am rating this claim at the required arithmetic-claim confidence.";
    assertEquals(trailing, PromptLabelScrubber.scrub(trailing));
    // A hyphen opening the text is a list marker, not a separator: the marker stays and only the
    // rating sentence after it goes.
    assertEquals(
        "-",
        PromptLabelScrubber.scrub(
                "- I am rating this claim at the required arithmetic-claim confidence.")
            .strip());
  }

  @Test
  void providedWithThisReviewIsRemovedAtALineStartAndAcrossAWrap() {
    assertEquals(
        "The quote above is from issue #117.",
        PromptLabelScrubber.scrub(
            "The quote above is from issue #117\nas provided with this review."));
    assertEquals(
        "Findings follow.\nThe criterion is unmet.",
        PromptLabelScrubber.scrub(
            "Findings follow.\nAs provided with this review, the criterion is unmet."));
    assertEquals(
        "The criterion is unmet.",
        PromptLabelScrubber.scrub("As provided with this review the criterion is unmet."));
    // Opening a sentence mid-line: the previous sentence keeps its full stop.
    assertEquals(
        "The suite fails. The criterion is unmet.",
        PromptLabelScrubber.scrub(
            "The suite fails. As provided with this review, the criterion is unmet."));
    // As a parenthetical, the note goes whole.
    assertEquals(
        "The quote above says the parser must reject it.",
        PromptLabelScrubber.scrub(
            "The quote above (as provided with this review) says the parser must reject it."));
    // Already capitalized after the note: nothing to change but the note itself.
    assertEquals(
        "README.md is renamed.",
        PromptLabelScrubber.scrub("As provided with this review, README.md is renamed."));
  }

  // #986: the placements that left "(as," or "Note:," behind.
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          the quote above (as provided with this review, the parser must reject it) says so | the quote above (the parser must reject it) says so
          the quote above (provided with this review, the parser must reject it) says so | the quote above (the parser must reject it) says so
          the quote above (as provided with this review the parser must reject it) says so | the quote above (the parser must reject it) says so
          the quote above (as provided with this reviewer in mind) says so | the quote above (as provided with this reviewer in mind) says so
          Note: as provided with this review, the parser must reject it. | Note: the parser must reject it.
          Note: As provided with this review, the parser must reject it. | Note: The parser must reject it.
          X — as provided with this review, Y | X — Y
          X – as provided with this review, Y | X – Y
          X - as provided with this review, Y | X - Y
          X — provided with this review, Y | X — Y
          as provided with this review, the parser must reject it. | the parser must reject it.
          as provided with this review the parser must reject it. | the parser must reject it.
          """)
  void providedWithThisReviewAfterAColonDashParenthesisOrAtTheStartGoesCleanly(
      String leaked, String clean) {
    var scrubbed = PromptLabelScrubber.scrub(leaked);
    assertEquals(clean, scrubbed);
    // No fragment of the note survives: neither its "as" nor a comma left where it stood.
    assertFalse(scrubbed.matches("(?s).*(?:\\bas,|[:—–(-],|^,).*"), scrubbed);
    assertEquals(clean, PromptLabelScrubber.scrub(clean));
  }

  @Test
  void providedWithThisReviewAfterAnOpenerKeepsTheTextBeforeTheNote() {
    // A note leading a parenthetical goes with the blanks around it; a mid-line note at the text
    // start has nothing a comma could close, so no comma is left in its place.
    assertEquals("(the rest)", PromptLabelScrubber.scrub("( provided to this review, the rest)"));
    assertEquals(" the rest", PromptLabelScrubber.scrub(" \tprovided with this review, the rest"));
  }

  // #987, PR #977 review thread r4152742157.
  @Test
  void anIndentedBulletKeepsItsMarkerWhenTheRatingSentenceGoes() {
    assertEquals(
        "Off-by-one:\n  -",
        PromptLabelScrubber.scrub(
            "Off-by-one:\n  - I am rating this claim at the required arithmetic-claim confidence."));
    assertEquals(
        "Off-by-one:\n\t-",
        PromptLabelScrubber.scrub(
            "Off-by-one:\n\t- I am rating this claim at the required arithmetic-claim confidence."));
    // After text on the same line, an indented line's standalone hyphen still separates.
    assertEquals(
        "Off-by-one:\n  It fails.",
        PromptLabelScrubber.scrub(
            "Off-by-one:\n  It fails - I am rating this claim at the required arithmetic-claim"
                + " confidence."));
  }

  @Test
  void confidenceRuleClauseKeepsWhatFollowsItsComma() {
    assertEquals(
        "The suite will confirm, so check the logs for the failure.",
        PromptLabelScrubber.scrub(
            "The suite will confirm; I am rating this claim at the required confidence, so check"
                + " the logs for the failure."));
    // A colon or dash after the clause ends it too, so what follows is kept.
    assertEquals(
        "The suite will confirm: check the logs for the failure.",
        PromptLabelScrubber.scrub(
            "The suite will confirm; I am rating this claim at the required confidence: check the"
                + " logs for the failure."));
    // Opening a sentence that goes on past a comma, the narration carries more and stays.
    var goesOn =
        "I am rating this claim at the required confidence, so check the logs for the failure.";
    assertEquals(goesOn, PromptLabelScrubber.scrub(goesOn));
  }

  @Test
  void ratingLookalikesThatAreNotTheRuleStay() {
    for (var text :
        List.of(
            // Not a rule's qualifier.
            "I am holding this claim at the current confidence until the log names the test.",
            // "confidence" in a later sentence, not this clause.
            "I am rating this claim at the required level. Its confidence is low.",
            // A longer word, not "confidence".
            "I am rating this claim at the required confidences listed below.",
            // Not a rating verb.
            "I am grading this claim at the required confidence for the release notes.",
            // "confidence" after a comma belongs to the next clause, not the rating.
            "I am rating this claim at the required level, and I have high confidence in it.",
            // No "confidence" at all.
            "I am rating this claim at the required level for now.",
            // A clause that runs on for more than 200 characters without ending is not narration.
            "I am rating this claim at the required confidence "
                + "and then this sentence keeps going ".repeat(8)
                + "until it finally ends.",
            // "confidence" too far on to belong to the clause.
            "I am rating this claim at the required level, which the team agreed on after the"
                + " incident last spring and wrote into the runbook, with confidence")) {
      assertEquals(text, PromptLabelScrubber.scrub(text));
    }
  }

  // #975, ThrillhouseBot-test#207 round-3 inline 4152219435.
  static final String IGNORE_LIST_LEAK =
      "The repository does contain a rust/Cargo.lock — the changed-files list for this PR names"
          + " it, 'rust/Cargo.lock (excluded from review scope by the ignore list)' — but the"
          + " build context never copies it.";

  static final String IGNORE_LIST_CLEAN =
      "The repository does contain a rust/Cargo.lock — the changed-files list for this PR names"
          + " it, 'rust/Cargo.lock' — but the build context never copies it.";

  // #975, ThrillhouseBot-test#204 threads 4152146989, 4152147418, 4152147644.
  static final String SUPPLIED_FROM_LEAK =
      "The repository's config key definition for POLL_INTERVAL (supplied from"
          + " kotlin/src/main/kotlin/pod/Config.kt) is \"val pollIntervalSeconds: Long\".";

  static final String SUPPLIED_FROM_CLEAN =
      "The repository's definition of POLL_INTERVAL (in kotlin/src/main/kotlin/pod/Config.kt) is"
          + " \"val pollIntervalSeconds: Long\".";

  // #975, ThrillhouseBot-test#204 round-1 review 5375331841.
  static final String PROVIDED_WITH_REVIEW_LEAK =
      "Note the acceptance-criteria quote above is from issue #117 as provided with this review.";

  static final String PROVIDED_WITH_REVIEW_CLEAN =
      "Note the acceptance-criteria quote above is from issue #117.";

  @Test
  void everyRound15PhraseIsRemovedOrRewritten() {
    assertEquals(CONFIDENCE_RULE_CLEAN, PromptLabelScrubber.scrub(CONFIDENCE_RULE_LEAK));
    assertEquals(IGNORE_LIST_CLEAN, PromptLabelScrubber.scrub(IGNORE_LIST_LEAK));
    assertEquals(SUPPLIED_FROM_CLEAN, PromptLabelScrubber.scrub(SUPPLIED_FROM_LEAK));
    assertEquals(PROVIDED_WITH_REVIEW_CLEAN, PromptLabelScrubber.scrub(PROVIDED_WITH_REVIEW_LEAK));
    for (var clean :
        List.of(
            CONFIDENCE_RULE_CLEAN,
            IGNORE_LIST_CLEAN,
            SUPPLIED_FROM_CLEAN,
            PROVIDED_WITH_REVIEW_CLEAN)) {
      assertEquals(clean, PromptLabelScrubber.scrub(clean));
    }
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          It fails. I am rating this claim at the required arithmetic-claim confidence until CI names the test. Fix it. | It fails. Fix it.
          It fails. I'm keeping this finding at the capped confidence for counting claims. | It fails.
          It fails — I am rating this claim at the required confidence. | It fails.
          It fails, I have held it to the maximum confidence the rule allows. | It fails.
          I rate this at the required low confidence.\\nNext line. | \\nNext line.
          It fails. I am rating this claim at the required low confidence | It fails.
          It fails. I rate it at the required low confidence\\nNext. | It fails.\\nNext.
          Per issue #117 as provided with this review, the parser must reject it. | Per issue #117, the parser must reject it.
          Config key definition for ARTWORK_DIR: line 4. | Definition of ARTWORK_DIR: line 4.
          the configuration key definitions of A and B agree | the definitions of A and B agree
          ARTWORK_DIR (supplied from `Config.kt`) defaults to a path | ARTWORK_DIR (in `Config.kt`) defaults to a path
          the criterion, as supplied to this review, says so | the criterion says so
          the coverage report provided with this review lists line 3 | the coverage report lists line 3
          go.sum (omitted by the ignore list) also changes | go.sum also changes
          """)
  void round15VariantsAreRemovedOrRewritten(String leaked, String clean) {
    var in = leaked.replace("\\n", "\n");
    var out = clean.replace("\\n", "\n");
    assertEquals(out, PromptLabelScrubber.scrub(in));
    assertEquals(out, PromptLabelScrubber.scrub(out));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "I rated it at medium confidence because CI had not finished.",
        "The value is supplied from the environment (supplied from the caller).",
        "The secret is provided with the request, not with this build.",
        "Files excluded by the ignore list still count toward the total.",
        "The config key definitions in Config.java read four variables.",
        "and I am rating this claim at the required confidence as a matter of course"
      })
  void round15NearMissesAreLeftAlone(String text) {
    assertEquals(text, PromptLabelScrubber.scrub(text));
  }

  @Test
  void round15PhrasesAreScrubbedOnEveryPostedSurface() {
    var response =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    "high",
                    "medium",
                    "rust/src/lib.rs",
                    12,
                    "Off-by-one",
                    CONFIDENCE_RULE_LEAK,
                    "let start = x + 1;",
                    "let start = x;")),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", IGNORE_LIST_LEAK)),
            new ReviewResponse.Summary(
                1,
                0,
                1,
                0,
                0,
                PROVIDED_WITH_REVIEW_LEAK,
                SUPPLIED_FROM_LEAK,
                List.of(IGNORE_LIST_LEAK),
                List.of(),
                List.of(new ReviewResponse.FileSummary("rust/src/lib.rs", CONFIDENCE_RULE_LEAK)),
                ""));

    var scrubbed = PromptLabelScrubber.scrub(response);

    assertEquals(CONFIDENCE_RULE_CLEAN, scrubbed.findings().getFirst().description());
    assertEquals(IGNORE_LIST_CLEAN, scrubbed.previousFindingsStatus().getFirst().note());
    var summary = scrubbed.summary();
    assertEquals(PROVIDED_WITH_REVIEW_CLEAN, summary.overallAssessment());
    assertEquals(SUPPLIED_FROM_CLEAN, summary.prPurpose());
    assertEquals(List.of(IGNORE_LIST_CLEAN), summary.descriptionGaps());
    assertEquals(CONFIDENCE_RULE_CLEAN, summary.fileSummaries().getFirst().summary());

    // The publisher's guard and a conversational reply go through the same text scrub.
    assertEquals(
        "## Summary\n" + PROVIDED_WITH_REVIEW_CLEAN,
        PromptLabelScrubber.scrubMarkdown("## Summary\n" + PROVIDED_WITH_REVIEW_LEAK, List.of()));
    assertEquals(
        SUPPLIED_FROM_CLEAN, PromptLabelScrubber.scrub(SUPPLIED_FROM_LEAK, Set.of("Config.kt")));
  }

  /** The dimension-number separator as an alternation, before it became a character class. */
  private static final Pattern ORIGINAL_NUMBER_SEPARATOR =
      Pattern.compile(" ?(?:,|/|&) ?| and | or ");

  @Test
  void numberSeparatorSplitsAsTheAlternationDid() {
    String[] parts = {"4", "10", " ", ",", "/", "&", "and", "or", " and ", " or ", "x", ";"};
    var random = new Random(975);
    for (var n = 0; n < 20_000; n++) {
      var numbers = new StringBuilder();
      for (var k = random.nextInt(10); k > 0; k--) {
        numbers.append(parts[random.nextInt(parts.length)]);
      }
      var s = numbers.toString();
      assertEquals(
          List.of(ORIGINAL_NUMBER_SEPARATOR.split(s, -1)),
          List.of(PromptLabelScrubber.NUMBER_SEPARATOR.split(s, -1)),
          s);
    }
  }

  // #964 item 5, angular #179 inline 4151111263.
  static final String WAIT_LEAK =
      "the sink is `return this.sanitizer.bypassSecurityTrustHtml(renderShiftNotes(shift.signups));`"
          + " feeding `[innerHTML]=\"notesFor(shift)\"` (line 24 — wait, the binding is"
          + " `<div class=\"notes\" [innerHTML]=\"notesFor(shift)\"></div>`). Upstream, the note is"
          + " not escaped.";

  static final String WAIT_CLEAN =
      "the sink is `return this.sanitizer.bypassSecurityTrustHtml(renderShiftNotes(shift.signups));`"
          + " feeding `[innerHTML]=\"notesFor(shift)\"` (the binding is"
          + " `<div class=\"notes\" [innerHTML]=\"notesFor(shift)\"></div>`). Upstream, the note is"
          + " not escaped.";

  // #964 round 15 (b), java #197 review 5375359719.
  static final String RESTART_LEAK =
      "line 28 `.header(\"Authorization\", \"Bearer \" + apiToken)`. BedReporter... rather, grep"
          + " across the added files confirms BED_API_TOKEN appears only in the Dockerfile and the"
          + " docs example; no `System.getenv(\"BED_API_TOKEN\")` call exists anywhere.";

  static final String RESTART_CLEAN =
      "line 28 `.header(\"Authorization\", \"Bearer \" + apiToken)`. Grep"
          + " across the added files confirms BED_API_TOKEN appears only in the Dockerfile and the"
          + " docs example; no `System.getenv(\"BED_API_TOKEN\")` call exists anywhere.";

  @Test
  void selfCorrectionsKeepTheCorrectionAndLoseWhatItCorrected() {
    assertEquals(WAIT_CLEAN, PromptLabelScrubber.scrub(WAIT_LEAK));
    assertEquals(RESTART_CLEAN, PromptLabelScrubber.scrub(RESTART_LEAK));
    assertEquals(WAIT_CLEAN, PromptLabelScrubber.scrub(WAIT_CLEAN));
    assertEquals(RESTART_CLEAN, PromptLabelScrubber.scrub(RESTART_CLEAN));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          The binding is on line 24 — wait, it is on line 25. | The binding is on line 24 — it is on line 25.
          The binding is on line 24 - Wait, it is on line 25. | The binding is on line 24 - it is on line 25.
          (line 24 – wait, line 25) holds the binding. | (line 25) holds the binding.
          BedReporter… rather, grep confirms it. | Grep confirms it.
          It fails. Main... actually, the handler reads it. | It fails. The handler reads it.
          Done.\\nBedReporter... wait, grep confirms it. | Done.\\nGrep confirms it.
          main.tsx mounts the app and hosts the two confirmed findings | main.tsx mounts the app and hosts the two findings
          Confirmed findings: two. | Findings: two.
          a confirmed finding on line 4 | a finding on line 4
          Scheduled executor that uploads due episodes; no findings in this batch | Scheduled executor that uploads due episodes
          Scheduled executor that uploads due episodes (no findings in this batch) | Scheduled executor that uploads due episodes
          Scheduled executor that uploads due episodes — no new findings for this batch. | Scheduled executor that uploads due episodes.
          Uploads due episodes. No findings in this batch. | Uploads due episodes.
          No findings in this batch. Uploads due episodes. | Uploads due episodes.
          (no findings in this batch) Uploads due episodes. | Uploads due episodes.
          Line one.\\nNo findings in this batch\\nLine three. | Line one.\\n\\nLine three.
          Two findings were raised in this batch on the scheduler. | Two findings were raised on the scheduler.
          the findings from this batch | the findings
          There are no findings in this batch yet. | There are no findings yet.
          There are no issues in this batch yet. | There are no issues yet.
          + BedReporter... rather, grep confirms it. | + Grep confirms it.
          Done. - BedReporter... rather, grep confirms it. | Done. - Grep confirms it.
          BedReporter... Rather, grep confirms it. | Grep confirms it.
          It fails. Main… Actually, the handler reads it. | It fails. The handler reads it.
          It fails. `foo.bar()`... rather, grep confirms it. | It fails. Grep confirms it.
          - `foo.bar()`… Wait, grep confirms it. | - Grep confirms it.
          Uploads due episodes. No findings in this batch! Next. | Uploads due episodes. Next.
          Uploads due episodes. No findings in this batch? Next. | Uploads due episodes. Next.
          Uploads due episodes. No findings in this batch: none. | Uploads due episodes. none.
          X (no findings in this batch), then uploads. | X, then uploads.
          X. (no findings in this batch). Uploads due episodes. | X. Uploads due episodes.
          X. (no findings in this batch) Uploads due episodes. | X. Uploads due episodes.
          Uploads due episodes - no findings in this batch. | Uploads due episodes.
          - no findings in this batch | - no findings
          Notes:\\n  - no findings in this batch | Notes:\\n  - no findings
          -no findings in this batch | -no findings
          Uploads-no findings in this batch | Uploads-no findings
          1. BedReporter... rather, grep confirms it. | 1. Grep confirms it.
          Notes:\\n  - BedReporter... rather, grep confirms it. | Notes:\\n  - Grep confirms it.
          > BedReporter... rather, grep confirms it. | > Grep confirms it.
          It fails. notesFor(shift.signups)... rather, grep confirms it. | It fails. Grep confirms it.
          (no findings in this batch). Uploads due episodes. | Uploads due episodes.
          (no findings in this batch), then uploads. | then uploads.
          Done (no findings in this batch | Done (no findings
          Done (no findings in this batch so far) | Done (no findings so far)
          Uploads. No findings in this batch | Uploads.
          Uploads.\\nNo findings in this batch | Uploads.\\n
          No findings in this batch were raised on the scheduler. | No findings were raised on the scheduler.
          (no findings in this batch) no findings in this batch here | no findings here
          """)
  void scratchTextAndOtherStagesVocabularyAreRemoved(String leaked, String clean) {
    var out = clean.replace("\\n", "\n");
    var scrubbed = PromptLabelScrubber.scrub(leaked.replace("\\n", "\n"));
    assertEquals(out, scrubbed);
    // None of the working-note markers survives in the scrubbed text.
    assertFalse(
        scrubbed.matches("(?is).*(?:\\bwait,|\\brather,|\\bactually,|confirmed finding|batch).*"),
        scrubbed);
    assertEquals(out, PromptLabelScrubber.scrub(out));
  }

  /**
   * #1075: the summary call's count of the findings its prompt listed (ThrillhouseBot-test#254,
   * 6125936933) and the verifier's CI note restated in a finding (#245, 4248908866).
   */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          CI fails the check 'test', which no finding among the three verified above explains. | CI fails the check 'test', which no finding explains.
          No finding among the 3 confirmed findings above explains it. | No finding explains it.
          None among the verified below names it. | None among the verified below names it.
          Because the failing job is already measured on this commit, the bug is confirmed rather than counted. | Because the failing job is already measured on this commit, the bug is confirmed.
          The failure is measured, not predicted. | The failure is measured.
          It is Confirmed instead of counted. | It is Confirmed.
          Among the three verified above, the shortlist finding explains it. | The shortlist finding explains it.
          It fails.\\nAmong the three verified above, the second is the one. | It fails.\\nThe second is the one.
          It fails. Among the 3 confirmed findings above the second explains it. | It fails. The second explains it.
          Among the three verified above. | .
          - Among the three verified above, the second is the one. | - The second is the one.
          Among the verified findings below, two are false. | Among the verified findings below, two are false.
          Among the accounts verified above, two are locked. | Among the accounts verified above, two are locked.
          CI fails the check 'test', which no finding among the many verified above explains. | CI fails the check 'test', which no finding explains.
          CI fails the check, which no finding among the fifteen verified above explains. | CI fails the check, which no finding explains.
          CI fails the check, which no finding among the three\\nverified above explains. | CI fails the check, which no finding explains.
          The bug is confirmed rather\\nthan counted. | The bug is confirmed.
          CI fails the check, which no finding\\namong the three verified above explains. | CI fails the check, which no finding explains.
          It fails,\\namong the three verified above the second explains it. | It fails, the second explains it.
          \\nAmong the three verified above, the second explains it. | \\nThe second explains it.
          The row is among the 1234 verified above. | The row is among the 1234 verified above.
          The row is among the old verified above. | The row is among the old verified above.
          """)
  void theCountOfVerifiedFindingsAndTheCiNoteAreRemoved(String leaked, String clean) {
    var expected = clean.replace("\\n", "\n");
    var scrubbed = PromptLabelScrubber.scrub(leaked.replace("\\n", "\n"));
    assertEquals(expected, scrubbed);
    assertFalse(
        scrubbed.matches("(?is).*(?:three verified|rather than counted|not predicted).*"),
        scrubbed);
    assertEquals(expected, PromptLabelScrubber.scrub(expected));
  }

  /** Ordinary sentences using "verified" or "confirmed" are left alone (#1075). */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "The fix is verified by the added test above.",
        "The race is confirmed by the failing test.",
        "Each verified signature is counted once.",
        "Among the verified accounts above, two are locked.",
        "Two users among the accounts verified above are locked.",
        "The value is computed, not counted twice.",
        "`among the three verified above` is the literal the parser expects."
      })
  void ordinaryVerifiedAndConfirmedSentencesStay(String text) {
    assertEquals(
        text,
        PromptLabelScrubber.scrub(text),
        "an ordinary use of verified or confirmed is not the summary call's count (#1075)");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "The job processes every record in this batch before committing.",
        "BatchImporter validates imported batches and rejects this batch of imports.",
        "Two findings in this batch of imports look actionable.",
        "It reports no findings in this batch of imports.",
        "The confirmed bug is on line 4, and the unconfirmed findings are listed below.",
        "Wait for the latch before reading the queue.",
        "The loop must wait, then retry once.",
        "The loop retries — wait, then retry once.",
        "Sends with backoff (5s — wait, then retry once more)",
        "The worker blocks — waiting, not spinning — until notified.",
        "The values run 1...10, rather than 0...9.",
        "It reads the header... and then the body.",
        "BedReporter... however, grep confirms it.",
        "It calls `foo.bar()`... rather, grep confirms it.",
        "`foo.bar()`... however, grep confirms it.",
        "It fails fast... rather, it surfaces the error to the caller.",
        // A dotted token mid-clause is part of that clause, not a broken-off start.
        "It calls foo.bar()... rather, grep confirms it.",
        // Two stacked markers, or an ordered one, are not the single marker a restart may follow.
        "> * BedReporter... rather, grep confirms it.",
        "1) BedReporter... rather, grep confirms it.",
        "The retry helps... actually, it makes things worse.",
        "(line 24 — waits for the lock)"
      })
  void scratchTextNearMissesAreLeftAlone(String text) {
    assertEquals(text, PromptLabelScrubber.scrub(text));
  }

  @Test
  void aRestartAfterACodeSpanInsideAFencedBlockStays() {
    var fenced = "```\n`foo.bar()`... rather, grep confirms it.\n```";
    assertEquals(fenced, PromptLabelScrubber.scrub(fenced));
    // After the fence closes, the same line is prose again.
    assertEquals(
        fenced + "\nGrep confirms it.",
        PromptLabelScrubber.scrub(fenced + "\n`foo.bar()`... rather, grep confirms it."));
  }

  @Test
  void scratchTextIsScrubbedOnEveryPostedSurface() {
    var response =
        new ReviewResponse(
            List.of(
                new ReviewResponse.Finding(
                    "critical",
                    "low",
                    "angular/src/app/shifts.component.ts",
                    24,
                    "XSS",
                    WAIT_LEAK,
                    null,
                    null)),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", RESTART_LEAK)),
            new ReviewResponse.Summary(
                1,
                1,
                0,
                0,
                0,
                RESTART_LEAK,
                "",
                List.of(),
                List.of(),
                List.of(
                    new ReviewResponse.FileSummary(
                        "react/src/main.tsx",
                        "Mounts the app and hosts the two confirmed findings"),
                    new ReviewResponse.FileSummary(
                        "kotlin/src/main/kotlin/pod/Scheduler.kt",
                        "Uploads due episodes; no findings in this batch")),
                ""));

    var scrubbed = PromptLabelScrubber.scrub(response);

    assertEquals(WAIT_CLEAN, scrubbed.findings().getFirst().description());
    assertEquals(RESTART_CLEAN, scrubbed.previousFindingsStatus().getFirst().note());
    assertEquals(RESTART_CLEAN, scrubbed.summary().overallAssessment());
    assertEquals(
        List.of("Mounts the app and hosts the two findings", "Uploads due episodes"),
        scrubbed.summary().fileSummaries().stream()
            .map(ReviewResponse.FileSummary::summary)
            .toList());
  }

  @ParameterizedTest
  @ValueSource(strings = {"onOrderClose", "apiToken", "handler_v2", "retry2"})
  void aRecoveredIdentifierKeepsItsOriginalCase(String identifier) {
    for (var start : List.of("notesFor(shift.signups)", "`foo.bar()`")) {
      var expected = identifier + " handles it.";
      assertEquals(expected, PromptLabelScrubber.scrub(start + "... wait, " + expected));
      assertEquals(expected, PromptLabelScrubber.scrub(expected));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"~~~", "```"})
  void aRestartInsideEitherFenceStyleStaysByteExact(String fence) {
    var block =
        fence
            + "java\n`foo.bar()`... rather,grep confirms it.\n"
            + "notesFor(shift.signups)... wait,onOrderClose handles it.\n"
            + fence;
    // Spaces after the connector also make these valid restart candidates outside code.
    block = block.replace(",grep", ", grep").replace(",onOrderClose", ", onOrderClose");
    assertEquals(block, PromptLabelScrubber.scrub(block));
    assertEquals(
        block + "\nGrep confirms it.",
        PromptLabelScrubber.scrub(block + "\n`foo.bar()`... rather, grep confirms it."));
    assertEquals(
        block.substring(0, block.lastIndexOf(fence)),
        PromptLabelScrubber.scrub(block.substring(0, block.lastIndexOf(fence))));
  }

  @Test
  void anotherFenceStyleInsideABlockDoesNotChangeItsBoundary() {
    var block = "~~~\n```\n`foo.bar()`... rather, grep confirms it.\n~~~";
    assertEquals(
        block + "\nGrep confirms it.",
        PromptLabelScrubber.scrub(block + "\n`foo.bar()`... rather, grep confirms it."));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Subsequent runs confirmed findings in the scheduler module.",
        "The suite confirmed findings from the earlier round.",
        "Tests confirmed findings before the release."
      })
  void confirmedAsAVerbKeepsTheSentenceMeaning(String text) {
    assertEquals(text, PromptLabelScrubber.scrub(text));
  }

  @ParameterizedTest
  @ValueSource(strings = {"````", "~~~~"})
  void shorterMarkersInsideALongerFenceRemainCode(String fence) {
    var shorter = fence.substring(0, 3);
    var block = fence + "\n" + shorter + "\n`foo.bar()`... rather, grep confirms it.\n" + fence;
    assertEquals(block, PromptLabelScrubber.scrub(block));
    assertEquals(
        block + "\nGrep confirms it.",
        PromptLabelScrubber.scrub(block + "\n`foo.bar()`... rather, grep confirms it."));
    assertEquals(
        fence + "\n" + shorter + "\n`foo.bar()`... rather, grep confirms it.",
        PromptLabelScrubber.scrub(
            fence + "\n" + shorter + "\n`foo.bar()`... rather, grep confirms it."));
  }

  @Test
  void codeSubjectsKeepConfirmedAsAVerbAndSeparateLabelsStillCleanUp() {
    assertEquals(
        "`Tests` confirmed findings before release.",
        PromptLabelScrubber.scrub("`Tests` confirmed findings before release."));
    assertEquals(
        "`Tests`\nFindings: two.", PromptLabelScrubber.scrub("`Tests`\nConfirmed findings: two."));
    assertEquals("Hosts 2 findings.", PromptLabelScrubber.scrub("Hosts 2 confirmed findings."));
    assertEquals("Hosts two findings.", PromptLabelScrubber.scrub("Hosts two confirmed findings."));
    assertEquals("Contains findings.", PromptLabelScrubber.scrub("Contains confirmed findings."));
  }

  @Test
  void aRecoveryAtTheEndOfTextStillCapitalizesProse() {
    assertEquals("Grep", PromptLabelScrubber.scrub("notesFor(shift.signups)... wait, grep"));
    assertEquals("g", PromptLabelScrubber.scrub("g"));
    assertEquals(
        "The [tests] confirmed findings.",
        PromptLabelScrubber.scrub("The [tests] confirmed findings."));
  }

  @ParameterizedTest
  @ValueSource(strings = {"```", "~~~"})
  void markerTextInsideCodeCannotCloseABlock(String fence) {
    var block =
        fence
            + "java\nvar marker = \""
            + fence
            + "\";\n"
            + fence
            + "still-code\n`foo.bar()`... rather, grep confirms it.\n\t"
            + fence
            + " \t\r\n";
    assertEquals(block, PromptLabelScrubber.scrub(block));
    assertEquals(
        block + "Grep confirms it.",
        PromptLabelScrubber.scrub(block + "`foo.bar()`... rather, grep confirms it."));
    var quoted = "> " + fence + "\n> `foo.bar()`... rather, grep confirms it.\n> " + fence;
    assertEquals(
        quoted + "\nGrep confirms it.",
        PromptLabelScrubber.scrub(quoted + "\n`foo.bar()`... rather, grep confirms it."));
  }

  @Test
  void inlineMultiBacktickCodeKeepsItsBoundary() {
    assertEquals(
        "Uses ```Confirmed findings```. Findings: two.",
        PromptLabelScrubber.scrub("Uses ```Confirmed findings```. Confirmed findings: two."));
  }

  @ParameterizedTest
  @ValueSource(strings = {"````", "~~~~"})
  void listAndQuoteContextsKeepTheirOwnFenceBoundary(String fence) {
    for (var opener : List.of("- ", "* ", "+ ", "12. ", "1) ", "> - ")) {
      var quote = opener.startsWith(">") ? "> " : "";
      var block =
          opener
              + fence
              + "java\n"
              + quote
              + "  "
              + fence.substring(0, 3)
              + "\n"
              + quote
              + "  "
              + (fence.startsWith("~") ? "````" : "~~~~")
              + "\n"
              + quote
              + "  > "
              + fence
              + "\n"
              + quote
              + "  `foo.bar()`... rather, grep confirms it.\n"
              + quote
              + "  "
              + fence;
      assertEquals(block, PromptLabelScrubber.scrub(block));
      assertEquals(
          block + "\nGrep confirms it.",
          PromptLabelScrubber.scrub(block + "\n`foo.bar()`... rather, grep confirms it."));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"```", "~~~"})
  void aQuotedMarkerDoesNotCloseAPlainBlock(String fence) {
    var block = fence + "\n> " + fence + "\n`foo.bar()`... rather, grep confirms it.\n" + fence;
    assertEquals(block, PromptLabelScrubber.scrub(block));
  }

  @Test
  void inlineMultiBacktickCodeAtAListOrLineStartStillEnds() {
    for (var prefix : List.of("", "- ", "> ", "42 ", "42", "1.", ". ", "-. ")) {
      var code = prefix + "```Confirmed findings```";
      assertEquals(
          code + "\nFindings: two.",
          PromptLabelScrubber.scrub(code + "\nConfirmed findings: two."));
      assertEquals(
          code + ". Findings: two.",
          PromptLabelScrubber.scrub(code + ". Confirmed findings: two."));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"This", "These", "Those", "Some", "Any", "All", "That"})
  void aPronounSubjectKeepsConfirmedAsItsVerb(String subject) {
    var text = subject + " confirmed findings in the scheduler module.";
    assertEquals(text, PromptLabelScrubber.scrub(text));
    assertEquals(
        subject + " confirmed findings: two.",
        PromptLabelScrubber.scrub(subject + " confirmed findings: two."));
  }

  @Test
  void definiteAndCountedFindingLabelsStillLoseTheVerifierAdjective() {
    assertEquals(
        "The findings are listed.",
        PromptLabelScrubber.scrub("The confirmed findings are listed."));
    assertEquals("No findings.", PromptLabelScrubber.scrub("No confirmed findings."));
    assertEquals("Hosts two findings.", PromptLabelScrubber.scrub("Hosts two confirmed findings."));
  }

  @ParameterizedTest
  @CsvSource(
      value = {
        "- Confirmed findings: two.|- Findings: two.",
        "**Confirmed findings:** two|**Findings:** two",
        "*Confirmed findings:* two|*Findings:* two",
        "→ Confirmed findings.|→ Findings."
      },
      delimiter = '|')
  void standaloneLabelsAfterMarkdownPrefixesStillCleanUp(String text, String expected) {
    assertEquals(expected, PromptLabelScrubber.scrub(text));
  }

  @ParameterizedTest
  @ValueSource(strings = {"**The suite**", "_This_", "The run →"})
  void aFormattedSubjectStillKeepsConfirmedAsItsVerb(String subject) {
    var text = subject + " confirmed findings in the scheduler module.";
    assertEquals(text, PromptLabelScrubber.scrub(text));
  }

  @Test
  void markupKeepsTheCodeSubjectAndSentenceBoundaryDecisions() {
    assertEquals(
        "`Tests` **confirmed findings**.",
        PromptLabelScrubber.scrub("`Tests` **confirmed findings**."));
    assertEquals(
        "`Tests`\n**Findings:** two.",
        PromptLabelScrubber.scrub("`Tests`\n**Confirmed findings:** two."));
    assertEquals(
        "Done. **Findings:** two.",
        PromptLabelScrubber.scrub("Done. **Confirmed findings:** two."));
  }

  @ParameterizedTest
  @ValueSource(strings = {"```", "```plain"})
  void anUnclosedBacktickInfoLineStaysByteExact(String text) {
    assertEquals(text, PromptLabelScrubber.scrub(text));
  }

  // #1195, round 19: ThrillhouseBot-test#297 discussion_r4336428935.
  @Test
  void theWithheldListReadsAsTheFilesNotShown() {
    assertEquals(
        "the PR adds every file in the service (the only file not shown is the README rename)",
        PromptLabelScrubber.scrub(
            "the PR adds every file in the service (the withheld list contains only the README"
                + " rename)"));
    assertEquals(
        "The only file not shown is the README rename.",
        PromptLabelScrubber.scrub("The withheld list contains only the README rename."));
    assertEquals(
        "the only files not shown are renamed files in this PR.",
        PromptLabelScrubber.scrub("the withheld list contains only renamed files in this PR."));
    assertEquals(
        "the only files not shown are 3 renames",
        PromptLabelScrubber.scrub("the withheld list has only 3 renames"));
    assertEquals(
        "the only files not shown are two lockfiles.",
        PromptLabelScrubber.scrub("the withheld list has only two lockfiles."));
    assertEquals(
        "the only file not shown is this; it is a rename",
        PromptLabelScrubber.scrub("the withheld list has only this; it is a rename"));
    assertEquals(
        "the only file not shown is 1 rename, pom.xml",
        PromptLabelScrubber.scrub("the withheld list has only 1 rename, pom.xml"));
    assertEquals(
        "the only file not shown is the class",
        PromptLabelScrubber.scrub("the withheld list has only the class"));
    assertEquals(
        "the only file not shown is the status page",
        PromptLabelScrubber.scrub("the withheld list has only the status page"));
    assertEquals(
        "the only file not shown is the bus",
        PromptLabelScrubber.scrub("the withheld list has only the bus"));
    assertEquals(
        "the only file not shown is us",
        PromptLabelScrubber.scrub("the withheld list has only us"));
    assertEquals(
        "the only file not shown is ", PromptLabelScrubber.scrub("the withheld list has only "));
    assertEquals(
        "The list of files not shown names it.",
        PromptLabelScrubber.scrub("The withheld list names it."));
    assertEquals(
        "Withheld lists aside, files not shown stay.",
        PromptLabelScrubber.scrub("Withheld lists aside, withheld paths stay."));
  }

  // ThrillhouseBot-test#295 discussion_r4336550626.
  @Test
  void aWithheldPathReadsAsAFileNotShown() {
    assertEquals(
        "The Dockerfile's only file not shown in this change is a README rename, so no unshown"
            + " source can carry the feature either.",
        PromptLabelScrubber.scrub(
            "The Dockerfile's only withheld path in this change is a README rename, so no unshown"
                + " source can carry the feature either."));
    assertEquals("File not shown: pom.xml.", PromptLabelScrubber.scrub("Withheld path: pom.xml."));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "the token is withheld from logs",
        "The payment is withheld until the list is signed.",
        "the withheld-path rules in the prompt",
        "verify the layer before trusting it",
        "the exact layer to verify is the gateway",
        "Name the exact layer to verify in the gateway config.",
        "see `description_gaps` in ReviewResponse",
        "description_gaps is never null after parsing",
        "it reads into description_gaps field by field",
        "see description_gaps.size() for the count",
        "see description_gaps in ReviewResponse for the exact field list.",
        "see description_gaps of Summary.descriptionGaps",
        "see description_gaps from parse() here",
        "see description_gaps in pr_purpose too",
        "the summary should carry the total cost",
        "If the summary should carry the description gap, add it."
      })
  void ordinaryWithheldVerifyAndKeyProseStays(String text) {
    assertEquals(text, PromptLabelScrubber.scrub(text));
  }

  // ThrillhouseBot-test#293 summary 6315203252.
  @Test
  void aCitedResponseKeyReadsAsTheSectionAReaderSees() {
    assertEquals(
        "other described behaviors are contradicted or only claimed by the description — see"
            + " Description vs. Implementation.",
        PromptLabelScrubber.scrub(
            "other described behaviors are contradicted or only claimed by the description — see"
                + " description_gaps."));
    assertEquals(
        "Listed in the gaps already addressed, and noted in the status of earlier findings.",
        PromptLabelScrubber.scrub(
            "Listed in addressed_gaps, and noted in previous_findings_status."));
    assertEquals(
        "See Description vs. Implementation. It lists both.",
        PromptLabelScrubber.scrub("See description_gaps. It lists both."));
    assertEquals(
        "See Description vs. Implementation in the summary.",
        PromptLabelScrubber.scrub("See description_gaps in the summary."));
    assertEquals(
        "See Description vs. Implementation in .",
        PromptLabelScrubber.scrub("See description_gaps in ."));
  }

  // ThrillhouseBot-test#296 discussion_r4336552968.
  @Test
  void aClauseRoutingTheGapToTheSummaryGoes() {
    assertEquals(
        "This trace inverts the description's claim.",
        PromptLabelScrubber.scrub(
            "This trace inverts the description's claim; the summary should carry the description"
                + " gap."));
    assertEquals(
        "This trace inverts the claim. It fails.",
        PromptLabelScrubber.scrub(
            "This trace inverts the claim. The summary must list the description gaps. It fails."));
    assertEquals(
        "This trace inverts the claim.",
        PromptLabelScrubber.scrub(
            "This trace inverts the claim. The summary should carry this description gap."));
  }

  // ThrillhouseBot-test#292 discussion_r4336427157.
  @Test
  void theEchoedLayerImperativeGoesAndWhatItIntroducedOpensTheSentence() {
    assertEquals(
        "Sanitization is not shown. Check whether the gateway actually restricts plate to a safe"
            + " character set.",
        PromptLabelScrubber.scrub(
            "Sanitization is not shown. Name the exact layer to verify: check whether the gateway"
                + " actually restricts plate to a safe character set."));
    assertEquals(
        "- Check the gateway.",
        PromptLabelScrubber.scrub(
            "- name in the description the exact layer to verify — check the gateway."));
    assertEquals(
        "x: Name the exact layer to verify: y",
        PromptLabelScrubber.scrub("x: Name the exact layer to verify: y"));
    assertEquals("", PromptLabelScrubber.scrub("Name the exact layer to verify:"));
  }

  @Test
  void theNewRulesReachFindingsAndTheSummaryOverview() {
    var finding =
        new Finding(
            RiskLevel.MEDIUM,
            Confidence.HIGH,
            "scala/build.sbt",
            3,
            "Withheld path is a rename",
            "the withheld list contains only the README rename",
            null,
            null);
    var scrubbed = PromptLabelScrubber.scrubFindings(List.of(finding)).getFirst();
    assertEquals("File not shown is a rename", scrubbed.title());
    assertEquals("the only file not shown is the README rename", scrubbed.description());
    assertEquals(
        "See Description vs. Implementation.",
        PromptLabelScrubber.scrubMarkdown("See description_gaps.", List.of(finding)));
  }

  @Test
  void theNewRulesAreIdempotent() {
    for (var text :
        List.of(
            "the withheld list contains only the README rename",
            "see description_gaps.",
            "A; the summary should carry the description gap.",
            "Name the exact layer to verify: check it.")) {
      var once = PromptLabelScrubber.scrub(text);
      assertEquals(once, PromptLabelScrubber.scrub(once));
    }
  }
}
