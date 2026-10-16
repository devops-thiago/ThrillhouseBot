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
        "The description says 'retrybudget' is configurable; nothing reads it.",
        // The quote spaces out the identifier the description runs together.
        "The description says 'retry budget' defaults to three; nothing reads it."
      })
  void aRunTogetherQuoteThePrTextHoldsIsKept(String gap) {
    assertEquals(List.of(gap), DescriptionGapScreen.screen(List.of(gap), RETRIES, ""));
  }

  @Test
  void aPossessiveInThePrTextStillHoldsTheQuotedIdentifier() {
    var source = DescriptionGapScreen.normalize("Raise `retryBudget`'s default to five.");

    assertFalse(
        DescriptionGapScreen.misquotes("The description says 'retryBudget' is raised.", source));
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
        "'the README moves to docs/RUST-V8.md' was what the PR body claims.",
        // Past-tense attributions, after the quote and before it.
        "'retries forever' was what the description stated.",
        "'retries forever' was what the PR promised.",
        "The description claimed 'retries forever', but the loop is bounded."
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
        "'retries forever' is what the description says the code does not do.",
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

  @ParameterizedTest
  @ValueSource(strings = {"The server's URL is hardcoded.", "The server’s URL is hardcoded."})
  void aPossessiveBetweenCompoundIdentifierWordsStillHoldsTheClaim(String description) {
    var gap = "The description says 'serverUrl' is hardcoded, but the server URL is configurable.";
    assertEquals(List.of(gap), DescriptionGapScreen.screen(List.of(gap), description, ""));
    assertEquals(
        List.of(),
        DescriptionGapScreen.screen(
            List.of("The description says 'serverTimeout' is hardcoded, but it is configurable."),
            description,
            ""));
    assertFalse(DescriptionGapScreen.misquotes(gap, DescriptionGapScreen.normalize(description)));
  }

  /** The #1124 Description vs. Implementation bullet (summary comment 6200091800). */
  static final String SPECULATIVE_1124 =
      "The description says \"Every MUST is Met or N/A\" with the Unmet ones all SHOULD or"
          + " SUGGESTED; a review pass flags that crypto_used_network and version_tags_signed may"
          + " be silver-MUST criteria, in which case the .bestpractices.json answers (Unmet) would"
          + " be rejected by the bestpractices.dev save step the post-merge checklist describes.";

  /** What #1124's description said, as the summary call read it. */
  static final String PR_1124 =
      """
      Title: docs: prepare for the OpenSSF Best Practices silver level
      Description:
      `.bestpractices.json`: proposed status and justification for all 55 silver criteria.
      Every MUST is Met or N/A. The Unmet ones are SHOULD or SUGGESTED.
      Criteria that already have an answer may need editing by hand.
      """;

  @Test
  void theSpeculativeEntryOf1124IsAGuess() {
    assertTrue(DescriptionGapScreen.speculative(SPECULATIVE_1124, PR_1124));
    assertTrue(DescriptionGapScreen.creditsReview(SPECULATIVE_1124));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "The description says retries are capped, but the cap might never apply.",
        "The description says the cache is bounded; the TTL could be ignored by `Cache.java`.",
        "The description says the export is stable; the format may not be what clients parse.",
        "Linked issue #113: at most 8 listings per seller — the cap may be enforced elsewhere.",
        // A hedge ending the bullet.
        "The description says the cap is enforced, and it might.",
        // A condition is a guess even after a consequence word.
        "The description says the cap holds, so the save is rejected in which case it fails.",
        // A consequence word after no stated mismatch does not make the guess a gap.
        "The description says the API is stable, so clients might break.",
        // Modal verbs over another verb, contracted, and the hedging adverbs.
        "The description says retries are capped, but the cap may expire.",
        "The description says the DCO check runs on every push; the workflow could skip forks.",
        "The description says the limit holds; it couldn't be enforced on a retry.",
        "The description says the limit holds; it couldn’t be enforced on a retry.",
        "The description says the export is atomic; it is possibly written in place.",
        // A hedge opening its sentence.
        "The description says the export is atomic. Perhaps the rename is skipped."
      })
  void aHedgeInTheBulletsOwnWordsIsAGuess(String gap) {
    assertTrue(DescriptionGapScreen.speculative(gap, RUST_159));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // A definite, diff-backed gap.
        "The description says the README moves unchanged; `docs/RUST-V10.md` rewrites it.",
        // A definite gap weighing what it causes.
        "The description says retries are capped; `Client.java` retries forever, so callers"
            + " might hang. Queue.java is unbounded, which means data may be dropped.",
        // The hedge is the description's own, quoted.
        "The description says \"tokens may be reused once\", but `Auth.java` rejects a reuse.",
        // A symbol a code span names is not a hedge, nor is a hyphenated name.
        "The description says the `may-retry` flag is respected, but `Client.java` ignores it.",
        "The description says the may-retry flag is respected, but Client.java ignores it.",
        "The description says perhaps-tagged files ship; the release-could list drops them.",
        // The hedge is the issue's criterion, not the bullet's evidence.
        "Linked issue #113: the cache may be bypassed with a header — no changed file reads one.",
        // Nothing follows a dash, so the whole text is the criterion.
        "Linked issue #113: listings might expire past a TTL",
        // "Mighty" and "maybe" are not hedges.
        "The description says the mighty README moves; maybe-tagged files are left out."
      })
  void aStatedGapIsNotAGuess(String gap) {
    assertFalse(DescriptionGapScreen.speculative(gap, RUST_159));
  }

  @Test
  void aHedgeTheDescriptionItselfMakesIsNotTheBulletsGuess() {
    var paraphrased =
        "The description says answers that may be stale are edited, but none are listed.";
    var source = PR_1124 + "Answers that may be stale are kept.";

    assertFalse(DescriptionGapScreen.speculative(paraphrased, source));
    assertTrue(DescriptionGapScreen.speculative(paraphrased, PR_1124));
    // Punctuation around the hedge does not break its context: normalising strips it.
    assertFalse(
        DescriptionGapScreen.speculative(
            "The description says answers may, however, be edited by hand, but none are.",
            "Answers may, however, be edited by hand."));
    // A hedge with no word after it paraphrases nothing, whatever the source holds.
    assertTrue(
        DescriptionGapScreen.speculative(
            "The description says the cache is bounded, and the bound may be.", source));
    // The same hedge and verb about another subject is the bullet's own guess.
    assertTrue(
        DescriptionGapScreen.speculative(
            "The description says the cache is bounded; the retry loop might collapse the pool.",
            "The rate limiter might collapse under sustained load."));
    // A bullet with no full stop is one sentence.
    assertFalse(
        DescriptionGapScreen.speculative(
            "The description says answers that may be stale are edited, but none are", source));
    // A hedge ending its sentence takes no context from the next one.
    assertTrue(
        DescriptionGapScreen.speculative(
            "The description says the cap holds, and the bound might. Be checked against it.",
            "The bound might be checked against the source in the old format."));
    // A hedge's context is read within one sentence of the source.
    assertTrue(
        DescriptionGapScreen.speculative(
            "The description says entries that may be stale are dropped, but none are.",
            "Old answers that may. Be stale entries are dropped."));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "A review pass flags that the DCO check skips merge commits.",
        "The earlier review noted the workflow is unpinned.",
        "The description says the job is pinned, as flagged by the review.",
        "One finding reports the description's claim is wrong.",
        "One finding raises the DCO question; nothing in the diff adds a sign-off.",
        "A finding found that `dco.yml` skips merges.",
        "The review says the description overstates the cap.",
        "The description says every commit is signed; the gap was found by the review.",
        "The description says X; the reviewer found `dco.yml` does Y."
      })
  void aBulletCreditingTheReviewIsRecognised(String gap) {
    assertTrue(DescriptionGapScreen.creditsReview(gap));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "The description says the README moves unchanged; `docs/RUST-V10.md` rewrites it.",
        "The description promises a review step for releases, but no workflow runs one.",
        "The description says findings are posted inline, but `Publisher.java` lists them.",
        // The credit is the description's own words: stated, or quoted.
        "The description says one reviewer reported downtime; `health.sh` reports healthy.",
        "The description says \"a review pass flags every commit\", but `dco.yml` skips merges.",
        "The checklist is done, but `a review pass flags every commit` is not backed here.",
        "The description says one finding reports downtime; `health.sh` passes.",
        "The description says the job was flagged by the review, but `ci.yml` passes.",
        // The criterion of a linked-issue gap is the issue's words.
        "Linked issue #113: a reviewer flags every listing over the cap — no changed file does.",
        // A reviewer named without a verb crediting the claim.
        "The description says a review step gates releases, but no workflow runs one."
      })
  void aBulletNotCreditingTheReviewIsNotRecognised(String gap) {
    assertFalse(DescriptionGapScreen.creditsReview(gap));
  }
}
