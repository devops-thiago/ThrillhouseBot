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

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Dropping description gaps that misquote the description or cite nothing it says (#946). */
class DescriptionGapScreenTest {

  /** ThrillhouseBot-test#159's PR text, as the summary call read it. */
  private static final String RUST_159 =
      """
      Title: feat(rust): add the bookings service
      Description:
      Adds the bookings service.

      The README moves to `docs/RUST-V10.md` unchanged.
      """;

  /** The round-12 bullet: it misquotes the path and then reports its own misquote. */
  private static final String MISQUOTE =
      "PR description claims '[t]he README moves to docs/RUST-VEV10.md unchanged' — the rename is"
          + " to docs/RUST-V10.md";

  @Test
  void aGapQuotingAPassageTheDescriptionDoesNotHoldIsDropped() {
    assertEquals(List.of(), DescriptionGapScreen.screen(List.of(MISQUOTE), RUST_159, ""));
  }

  @Test
  void theSameGapQuotingTheDescriptionExactlyIsKept() {
    // The editorial bracket reads as its letter, and the backticks the description puts around the
    // path do not count against the quote.
    var exact =
        "PR description claims '[t]he README moves to docs/RUST-V10.md unchanged' — the rename is"
            + " to 'docs/RUST-V15.md'";

    assertEquals(List.of(exact), DescriptionGapScreen.screen(List.of(exact), RUST_159, ""));
  }

  @Test
  void aQuoteGivingWhatTheChangeDoesIsNotTakenForTheDescriptionsClaim() {
    // The second quote follows the first's end, with no cue between them: it is the code's side.
    var gap =
        "The description says \"the README moves to docs/RUST-V10.md unchanged\", but the change"
            + " also rewrites it: \"bookings are capped per organizer\".";

    assertEquals(List.of(gap), DescriptionGapScreen.screen(List.of(gap), RUST_159, ""));
  }

  @Test
  void curlyQuotesAndCaseAreFoldedBeforeTheQuoteIsLookedUp() {
    var kept = "The PR description states “THE README MOVES TO docs/RUST-V10.md”.";
    var dropped = "The PR description states ‘the README moves to docs/RUST-V8.md’.";

    assertEquals(List.of(kept), DescriptionGapScreen.screen(List.of(kept, dropped), RUST_159, ""));
  }

  @Test
  void anOmissionSplitsTheQuoteIntoPartsThatMustAppearInOrder() {
    var elided = "The description says 'Adds the bookings … moves to docs/RUST-V10.md'.";
    var bracketed = "The description says '[the service] moves to docs/RUST-V10.md'.";
    var reordered = "The description says 'docs/RUST-V10.md … Adds the bookings'.";

    assertEquals(
        List.of(elided, bracketed),
        DescriptionGapScreen.screen(List.of(elided, bracketed, reordered), RUST_159, ""));
  }

  @Test
  void aSingleQuotedWordIsNotJudged() {
    var gap = "The description calls the store 'persistent', but it is an in-memory map.";

    assertEquals(List.of(gap), DescriptionGapScreen.screen(List.of(gap), RUST_159, ""));
  }

  @Test
  void aQuoteFromTheLinkedIssueIsNotAMisquote() {
    var linked =
        """
        Issues this pull request is linked to: #117

        ### Issue #117: Podcast scheduler
        - [ ] Scheduling an episode whose publish time is in the past is rejected
        """;
    var gap =
        "Linked issue #117: the issue's title says 'scheduling an episode whose publish time is in"
            + " the past is rejected' — no validation rejects it.";

    assertEquals(List.of(gap), DescriptionGapScreen.screen(List.of(gap), RUST_159, linked));
  }

  @Test
  void withNoPrTextNothingIsJudgedAMisquote() {
    assertEquals(List.of(MISQUOTE), DescriptionGapScreen.screen(List.of(MISQUOTE), "", ""));
    assertEquals(List.of(MISQUOTE), DescriptionGapScreen.screen(List.of(MISQUOTE), null, ""));
  }

  @Test
  void anApostropheNeitherOpensNorClosesAQuote() {
    var gap = "The PR's description doesn't mention the author's retry budget at all.";

    assertFalse(DescriptionGapScreen.misquotes(gap, DescriptionGapScreen.normalize(RUST_159)));
  }

  @Test
  void aDocsVersusCodeMismatchIsNotADescriptionGap() {
    // scala #176: the PR description never mentions TRACKED_PORTS.
    var docs =
        "The configuration doc's TRACKED_PORTS example uses a space separator while the parser"
            + " splits on commas.";

    assertEquals(List.of(), DescriptionGapScreen.screen(List.of(docs), RUST_159, ""));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "The description promises retries with backoff; no retry loop exists.",
        "Linked issue #113: at most 8 open listings — not implemented.",
        "Claims structured JSON audit logging, but the release handler logs plain text.",
        "States that results are cached, but nothing caches them.",
        "Not mentioned: the change also adds a CI workflow and a Dockerfile.",
        "The stated five-minute cache is not implemented.",
        "Scope is narrower than the change: only the scheduler is covered."
      })
  void aGapCitingTheDescriptionOrALinkedIssueIsKept(String gap) {
    assertTrue(DescriptionGapScreen.cites(gap), gap);
  }

  @Test
  void anUnchangedListIsReturnedAsItIs() {
    var gaps = List.of("The description promises retries; none exist.");

    assertSame(gaps, DescriptionGapScreen.screen(gaps, RUST_159, ""));
  }
}
