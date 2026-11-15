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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Description vs. Implementation bullets the code already answers (#1387), on the bullets the
 * summary of #1342 listed on f0d66055: one restating a finding the author had fixed, and two saying
 * nothing shows code the diff adds by name. A bullet stating a real mismatch stays.
 */
class AnsweredDescriptionGapsTest {

  /** The member-call bullet, listed after 0e6f2e78 fixed the finding it restates. */
  private static final String MEMBER_CALL_GAP =
      "The description says the picked files are \"clipped to what the changed code uses\"; the"
          + " member-call collection attributes calls to every candidate a changed file referred"
          + " to, so a candidate's excerpt can clip to unrelated declarations rather than what the"
          + " changed code uses of that file.";

  /** The finding that bullet restates, as the review posted it on 457c2df8. */
  private static final ReviewResponse.Finding MEMBER_CALL_FINDING =
      new ReviewResponse.Finding(
          "medium",
          "high",
          "src/main/java/dev/thiagogonzaga/thrillhousebot/review/RelatedReferences.java",
          270,
          "Member calls are attributed to every candidate a changed file referred to, so excerpts"
              + " can clip to unrelated declarations",
          "The scan collects member calls per changed file, not per candidate: the members set is"
              + " built once per file and then applied wholesale to all candidates that file"
              + " referred to. A candidate therefore inherits calls made on other types. The PR"
              + " description says the picked files are 'clipped to what the changed code uses'.",
          null,
          null);

  /** The verifier bullet: it names only {@code context_evidence}. */
  private static final String CONTEXT_EVIDENCE_GAP =
      "The description says related-file findings' excerpts are carried as context_evidence; this"
          + " is unconfirmed beyond the changed names — no finding or declared name shows the"
          + " verifier actually consumes the excerpts.";

  /** The linked-issue bullet: it names only {@code PromptTemplateEscaper}. */
  private static final String ESCAPER_GAP =
      "Linked issue #55: the injected related-context section is to be wrapped by"
          + " PromptTemplateEscaper like every untrusted slot — no changed file or declared name"
          + " shows that wrapping (unconfirmed)";

  /** The ignore-list bullet: a scope the author deferred, a real mismatch. */
  private static final String IGNORE_LIST_GAP =
      "The description states the per-repository ignore list is not applied to related files —"
          + " the linked issue #55's bounded-fetch design assumes the existing contents client and"
          + " budget handling, but this scope limitation is explicitly deferred by the author.";

  /**
   * Added lines of #1342 at f0d66055 that name the two pieces, from AGENTS.md and the assembler.
   */
  private static final String ADDED_CODE =
      """
      reaches it as a `context_evidence` note under that field's general rule.
          return RelatedContextPrompts.REVIEW_REQUEST
              + "\\n\\n"
              + PromptTemplateEscaper.fence(relatedContext);
      """;

  @Test
  void aBulletRestatingAFindingTheRoundResolvedLeaves() {
    var gaps = List.of(MEMBER_CALL_GAP, IGNORE_LIST_GAP);

    assertEquals(
        List.of(IGNORE_LIST_GAP),
        DescriptionGapCarryover.withoutResolved(gaps, List.of(MEMBER_CALL_FINDING)));
  }

  @Test
  void withNothingResolvedOrNoGapsTheListIsKeptAsItIs() {
    var gaps = List.of(MEMBER_CALL_GAP);

    assertSame(gaps, DescriptionGapCarryover.withoutResolved(gaps, List.of()));
    assertSame(
        List.<String>of(),
        DescriptionGapCarryover.withoutResolved(List.of(), List.of(MEMBER_CALL_FINDING)));
    assertSame(
        gaps,
        DescriptionGapCarryover.withoutResolved(
            gaps, List.of(finding("Retry budget is not reset after a success", "retry"))));
  }

  @Test
  void aBulletAboutAnotherKeyStaysThoughItsTextOverlapsAResolvedFinding() {
    // #1383: two rows of one configuration table, different settings.
    var resolved =
        finding(
            "`VET_BOARD_REFRESH_INTERVAL` documented with an empty default, but the code defaults"
                + " to 60000",
            "The CONFIG-ANGULAR.md table lists `VET_BOARD_REFRESH_INTERVAL` with an empty default"
                + " column, while config.ts falls back to 60000 when the variable is unset.");
    var gap =
        "The description says the configuration table documents every variable; the"
            + " `VET_BOARD_CLINIC_IDS` documented default is empty, but the code defaults to a"
            + " comma-separated list.";

    // The text alone reads as a restatement; the identifier veto is what keeps it.
    assertTrue(
        SummarySurfaceDeduplicator.restatesAny(
            gap, SummarySurfaceDeduplicator.claimsOf(List.of(Finding.fromAiResponse(resolved)))));
    assertEquals(
        List.of(gap), DescriptionGapCarryover.withoutResolved(List.of(gap), List.of(resolved)));
  }

  @Test
  void anUnconfirmedBulletWhoseNamesTheDiffAddsLeaves() {
    var gaps = List.of(CONTEXT_EVIDENCE_GAP, ESCAPER_GAP, IGNORE_LIST_GAP);

    assertEquals(
        List.of(IGNORE_LIST_GAP), DescriptionGapScreen.withoutAnsweredByDiff(gaps, ADDED_CODE));
  }

  @Test
  void aNameTheDiffOnlyRemovesOrNeverAddsKeepsTheBullet() {
    var gaps = List.of(ESCAPER_GAP, CONTEXT_EVIDENCE_GAP);
    // The added code has neither name; a removed line is not part of it.
    var added =
        VerdictBuilder.addedCode(
            List.of(file("@@ -1 +1 @@\n-  PromptTemplateEscaper.fence(x);\n+  y();")));

    assertSame(gaps, DescriptionGapScreen.withoutAnsweredByDiff(gaps, added));
    assertSame(gaps, DescriptionGapScreen.withoutAnsweredByDiff(gaps, ""));
    assertSame(gaps, DescriptionGapScreen.withoutAnsweredByDiff(gaps, null));
    assertSame(
        List.<String>of(), DescriptionGapScreen.withoutAnsweredByDiff(List.of(), ADDED_CODE));
  }

  @Test
  void oneNameTheDiffDoesNotAddKeepsTheBullet() {
    var gap =
        "The description says the excerpt is fenced by PromptTemplateEscaper and logged by"
            + " RelatedContextAudit; no changed file shows either (unconfirmed).";

    assertFalse(
        DescriptionGapScreen.answeredByDiff(gap, DescriptionGapScreen.codeNames(ADDED_CODE)));
  }

  @Test
  void aBulletStatingItsOwnMismatchBeforeTheUnconfirmedPartStays() {
    var gap =
        "The change omits the PromptTemplateEscaper wrapping the linked issue asks for; nothing"
            + " in the material confirms it (unconfirmed).";

    assertFalse(DescriptionGapScreen.answeredByDiff(gap, Set.of("PromptTemplateEscaper")));
  }

  @Test
  void aBulletStatingItsOwnMismatchAfterTheUnconfirmedPartStays() {
    var gap =
        "Nothing in the material confirms it (unconfirmed); the change omits the"
            + " PromptTemplateEscaper wrapping the linked issue asks for.";

    assertFalse(DescriptionGapScreen.answeredByDiff(gap, Set.of("PromptTemplateEscaper")));
  }

  @Test
  void aBulletThatSaysNothingIsUnconfirmedOrNamesNoCodeStays() {
    var names = DescriptionGapScreen.codeNames(ADDED_CODE);

    assertFalse(
        DescriptionGapScreen.answeredByDiff(
            "The description says PromptTemplateEscaper wraps the section; it wraps a different"
                + " one.",
            names));
    assertFalse(
        DescriptionGapScreen.answeredByDiff(
            "The description says reminders are deduplicated; no changed rule confirms the dedupe"
                + " behavior.",
            names));
  }

  @Test
  void codeNamesReadCodeSpansCamelAndSnakeCaseButNotPlainWords() {
    assertEquals(
        Set.of("PromptTemplateEscaper", "fence", "noteFor", "context_evidence", "RelatedContext"),
        DescriptionGapScreen.codeNames(
            "the escaper `PromptTemplateEscaper.fence` with noteFor, context_evidence and"
                + " RelatedContext, not the plain words Section or excerpt"));
  }

  @Test
  void addedCodeTakesEveryFilesAddedLinesAndSkipsAFileWithoutAPatch() {
    var files =
        List.of(
            file("@@ -1,2 +1,2 @@\n context\n-removed\n+added one"),
            file(null),
            file("@@ -0,0 +1 @@\n+added two"));

    assertEquals("added one\nadded two\n", VerdictBuilder.addedCode(files));
  }

  @Test
  void addedCodeSkipsOnlyTheFileHeadersBeforeTheFirstHunk() {
    var files = List.of(file("--- a/A.java\n+++ b/A.java\n@@ -1 +1,2 @@\n i = 0;\n+++index;"));

    assertEquals("++index;\n", VerdictBuilder.addedCode(files));
  }

  @Test
  void aGapNamingAPlainLowerCaseMethodTheDiffAddsIsAnswered() {
    var gap =
        "Linked issue #55: the injected related-context section is to be wrapped by"
            + " `PromptTemplateEscaper.fence` like every untrusted slot — no changed file or"
            + " declared name shows that wrapping (unconfirmed)";

    assertEquals(List.of(), DescriptionGapScreen.withoutAnsweredByDiff(List.of(gap), ADDED_CODE));
    assertTrue(DescriptionGapScreen.identifiers(ADDED_CODE).contains("fence"));
  }

  @Test
  void theRenderedSectionDropsBothKindsAndKeepsTheRealMismatch() {
    var summary =
        new ReviewResponse.Summary(
            0,
            0,
            0,
            0,
            0,
            "",
            "Reads related code outside the diff.",
            List.of(MEMBER_CALL_GAP, CONTEXT_EVIDENCE_GAP, ESCAPER_GAP, IGNORE_LIST_GAP));
    var round =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            null,
            ReviewState.APPROVE,
            false,
            "",
            List.of(new ReviewResult.PreviousFindingStatus(1, "resolved", "fixed")),
            List.of(),
            0);
    var sources =
        new PrSummaryGenerator.DescriptionSources("", Set.of())
            .withRound(List.of(MEMBER_CALL_FINDING), ADDED_CODE);

    var generator = new PrSummaryGenerator(true);
    var before =
        generator.generate(
            1,
            1,
            0,
            List.of(),
            summary,
            round,
            new PrSummaryGenerator.DescriptionSources("", Set.of()));
    var rendered = generator.generate(1, 1, 0, List.of(), summary, round, sources);

    // Without the round's findings and added lines every bullet renders.
    assertTrue(before.contains("member-call collection"), before);
    assertTrue(before.contains("PromptTemplateEscaper"), before);

    assertTrue(rendered.contains("explicitly deferred by the author"), rendered);
    assertFalse(rendered.contains("member-call collection"), rendered);
    assertFalse(rendered.contains("context_evidence"), rendered);
    assertFalse(rendered.contains("PromptTemplateEscaper"), rendered);
  }

  private static ReviewResponse.Finding finding(String title, String description) {
    return new ReviewResponse.Finding(
        "medium", "high", "docs/A.md", 8, title, description, null, null);
  }

  private static GitHubPullRequestClient.FileDiff file(String patch) {
    return new GitHubPullRequestClient.FileDiff("src/A.java", "modified", 1, 1, 2, patch, null);
  }
}
