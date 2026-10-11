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

  @ParameterizedTest
  @ValueSource(
      strings = {
        "The description says the README moves unchanged, while the change's rename reads"
            + " 'docs/RUST-V15.md is the new home'.",
        "The description says the README moves unchanged even though the change's rename reads"
            + " 'docs/RUST-V15.md is the new home'.",
        "The description says the README moves unchanged although the rename reads"
            + " 'docs/RUST-V15.md is the new home'.",
        "The description says the README moves unchanged yet the rename reads"
            + " 'docs/RUST-V15.md is the new home'."
      })
  void aCueInAnEarlierClauseDoesNotAttributeTheQuote(String gap) {
    assertEquals(List.of(gap), DescriptionGapScreen.screen(List.of(gap), RUST_159, ""));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "The description never mentions 'the Redis cache layer', which the change adds.",
        "The description does not state 'the Redis cache layer' anywhere.",
        "The description doesn't say 'the Redis cache layer' is added.",
        "The description fails to mention 'the Redis cache layer'.",
        "The description leaves out 'the Redis cache layer' entirely.",
        "The description omits 'the Redis cache layer'.",
        "Not mentioned in the description: 'the Redis cache layer'.",
        "Nothing in the description covers 'the Redis cache layer'.",
        "The PR body does not say 'the Redis cache layer' anywhere.",
        "The PR says nothing about 'the Redis cache layer'."
      })
  void aQuoteTheDescriptionIsSaidToLackIsNotTakenForItsClaim(String gap) {
    assertEquals(List.of(gap), DescriptionGapScreen.screen(List.of(gap), RUST_159, ""));
  }

  @Test
  void aNegationAboutTheCodeDoesNotUnsayWhatTheDescriptionIsSaidToSay() {
    // The negation sits before the cue, and the verb introduces the quote: the quote is the
    // description's claim, so a misquote of it is dropped.
    var gap =
        "The code doesn't do what the description says: 'emails are sent the instant an episode"
            + " goes live'.";

    assertEquals(List.of(), DescriptionGapScreen.screen(List.of(gap), RUST_159, ""));
  }

  @Test
  void aLongRunBetweenTheCueAndTheQuoteKeepsTheCue() {
    var gap =
        "The description states as its relocation guarantee that subscribers keep the old"
            + " documentation path only until the rename lands with content unchanged and the"
            + " quote reads 'the README moves to docs/RUST-VEV10.md unchanged'.";

    assertEquals(List.of(), DescriptionGapScreen.screen(List.of(gap), RUST_159, ""));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "The PR's description reads 'the README moves to docs/RUST-V8.md'.",
        "The pull request states 'the README moves to docs/RUST-V8.md'.",
        "The PR title says 'the README moves to docs/RUST-V8.md'.",
        "The PR body claims that 'the README moves to docs/RUST-V8.md'.",
        "The author promises 'the README moves to docs/RUST-V8.md'."
      })
  void everyCueAttributesTheQuoteToTheDescription(String gap) {
    assertEquals(List.of(), DescriptionGapScreen.screen(List.of(gap), RUST_159, ""));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "The page's <title> element reads 'Bookings dashboard home' on every route.",
        "The response body reads 'retry after thirty seconds' on a conflict.",
        "The author's code caps caching at 'CACHE_TTL_SECONDS', but the description promises"
            + " unbounded caching.",
        "The description column renders raw markup ('<b>bold</b>') on every card."
      })
  void aBareTitleBodyAuthorOrDescriptionDoesNotAttributeAQuote(String gap) {
    assertFalse(DescriptionGapScreen.misquotes(gap, DescriptionGapScreen.normalize(RUST_159)));
  }

  @Test
  void anElidedSubjectStillAttributesTheQuote() {
    var gap = "Claims 'the README moves to docs/RUST-V8.md' while the rename is elsewhere.";

    assertEquals(List.of(), DescriptionGapScreen.screen(List.of(gap), RUST_159, ""));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "The description states: 'the README moves to docs/RUST-V8.md'.",
        "The description states, 'the README moves to docs/RUST-V8.md'.",
        "The description says that, 'the README moves to docs/RUST-V8.md'."
      })
  void aColonOrCommaAfterTheVerbKeepsTheCueWithItsQuote(String gap) {
    assertEquals(List.of(), DescriptionGapScreen.screen(List.of(gap), RUST_159, ""));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "The description states the path, the code reads 'docs/RUST-V15.md is the new home'.",
        "The description names the moved README, 'docs/RUST-V15.md is the new home' is the"
            + " rename's own line."
      })
  void aCommaThatDoesNotFollowTheVerbStillBreaksTheClause(String gap) {
    assertEquals(List.of(gap), DescriptionGapScreen.screen(List.of(gap), RUST_159, ""));
  }

  @Test
  void anOmissionPartMayReQuoteTheEndOfThePartBeforeIt() {
    var source = DescriptionGapScreen.normalize("The cache is bounded by TTL.");

    assertTrue(DescriptionGapScreen.appearsIn("The cache is bounded … bounded by TTL", source));
    assertFalse(DescriptionGapScreen.appearsIn("bounded by TTL … the cache", source));
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
    var gap = "The description says the store is 'persistent', but it is an in-memory map.";

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
        "Scope is narrower than the change: only the scheduler is covered.",
        "The PR body says the cache is bounded; it is not.",
        "The pull request's title names only the scheduler; the change also adds a CLI.",
        "The PR says it adds retries; no retry loop exists.",
        "Acceptance criteria require rejecting past dates; nothing does.",
        "The Dockerfile the change adds is never mentioned.",
        "Unmentioned: the change also adds a CI workflow.",
        "Says the cache is bounded; it is not.",
        "The author caps the retry budget at three attempts; the change has no cap."
      })
  void aGapCitingTheDescriptionOrALinkedIssueIsKept(String gap) {
    assertTrue(DescriptionGapScreen.cites(gap), gap);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "The configuration doc's comment mentions TRACKED_PORTS with a space separator while the"
            + " parser splits on commas.",
        "The page <title> is set after the content loads, so the tab reads blank at first.",
        "The response body is parsed as JSON even when the content type is text."
      })
  void aGapNamingNeitherTheDescriptionNorAnIssueIsNotCiting(String gap) {
    // A bare "mentions", "title" or "body" names the change's own documents or payloads.
    assertFalse(DescriptionGapScreen.cites(gap), gap);
  }

  @Test
  void anUnchangedListIsReturnedAsItIs() {
    var gaps = List.of("The description promises retries; none exist.");

    assertSame(gaps, DescriptionGapScreen.screen(gaps, RUST_159, ""));
  }

  /** A description whose retry behaviour a run-together quote can misstate (#1064). */
  private static final String RETRIES =
      """
      Title: feat: retry the registry call
      Description:
      Retries three times on failure. Set `retryBudget` to change it.
      """;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "The description promises 'retriesforever', but the code stops after three.",
        "The description states 'retryForever'; the loop is bounded.",
        "The description says 'retryBudgetForever' is the setting."
      })
  void aRunTogetherOrCamelCasedMisquoteIsDropped(String gap) {
    assertEquals(List.of(), DescriptionGapScreen.screen(List.of(gap), RETRIES, ""));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "The description promises 'retriesthree', but the code retries twice.",
        "The description says 'retryBudget' defaults to three; nothing reads it.",
        "The description says 'retrybudget' is configurable; nothing reads it."
      })
  void aRunTogetherQuoteThePrTextHoldsIsKept(String gap) {
    assertEquals(List.of(gap), DescriptionGapScreen.screen(List.of(gap), RETRIES, ""));
  }

  @Test
  void anOrdinaryWordIsNotTakenForTwoRunTogether() {
    var source = DescriptionGapScreen.normalize("the theory of retries");

    assertFalse(DescriptionGapScreen.runTogether("theory", source));
    assertFalse(DescriptionGapScreen.runTogether("retriesx", source));
    assertTrue(DescriptionGapScreen.runTogether("retriesforever", source));
    assertTrue(DescriptionGapScreen.runTogether("foreverretries", source));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "'retries forever' is what the description promises; the code retries three times.",
        "The code retries three times; 'retries forever' is what the PR says.",
        "'the README moves to docs/RUST-V8.md' was what the PR body claims."
      })
  void aMisquoteAttributedAfterTheQuoteIsDropped(String gap) {
    assertEquals(List.of(), DescriptionGapScreen.screen(List.of(gap), RUST_159, ""));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "'retries forever' is what the code does, not what the description says.",
        "'retries forever' is not what the description says; it is the code's own default.",
        "'retries forever' is what the description never says.",
        "'the README moves to docs/RUST-V10.md unchanged' is what the description says, and it"
            + " does not."
      })
  void aQuoteTheTrailingClauseDoesNotAttributeOrThatHoldsIsKept(String gap) {
    assertEquals(List.of(gap), DescriptionGapScreen.screen(List.of(gap), RUST_159, ""));
  }

  @Test
  void anOmissionIsCoveredOnlyWhenTheDescriptionNowHoldsTheQuotedPassage() {
    var source = DescriptionGapScreen.normalize(RUST_159);

    assertTrue(
        DescriptionGapScreen.omissionCovered(
            "The description never mentions 'the bookings service'.", source));
    assertTrue(
        DescriptionGapScreen.omissionCovered(
            "The PR says nothing about 'the bookings service'.", source));
    assertTrue(
        DescriptionGapScreen.omissionCovered(
            "The description never mentions \"the bookings service\".", source));
    assertFalse(
        DescriptionGapScreen.omissionCovered(
            "The description never mentions 'the Redis cache layer'.", source));
    // The code's side, and a claim rather than an omission, are not omissions of the description.
    assertFalse(
        DescriptionGapScreen.omissionCovered(
            "The code never calls 'the bookings service'.", source));
    assertFalse(
        DescriptionGapScreen.omissionCovered(
            "The description says 'the bookings service' is new.", source));
    assertFalse(
        DescriptionGapScreen.omissionCovered("The description never mentions 'bookings'.", source));
  }
}
