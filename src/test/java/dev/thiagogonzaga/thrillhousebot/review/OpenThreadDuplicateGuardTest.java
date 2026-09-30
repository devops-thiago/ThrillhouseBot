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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * #939: a follow-up round after one that raised something new must not post a second thread for a
 * finding an earlier round left open. The effective previous round is the newest round that raised
 * anything, so once round two raises one finding, round one's open findings are no longer numbered
 * for the model or known to the scan; these pin the guard, the prompt section and the set they both
 * read.
 */
class OpenThreadDuplicateGuardTest {

  private static final BotIdentity BOT = BotIdentity.from(List.of("thrillhousebot[bot]"));

  private static final String READING = "rust/src/reading.rs";
  private static final String CLIENT = "rust/src/client.rs";

  private static final String PATCH =
      """
      @@ -0,0 +1,6 @@
      +pub fn recent_average(r: &[f64], n: usize) -> f64 {
      +    let tail = &r[r.len() - n + 1..];
      +    tail.iter().sum::<f64>() / n as f64
      +}
      +pub fn dedupe(v: Vec<u32>) -> Vec<u32> { let mut o = vec![]; for x in v { if !o.contains(&x) { o.push(x) } } o }
      +pub fn is_excursion(t: f64) -> bool { t > 8.0 }
      """;

  private static final String CLIENT_PATCH =
      """
      @@ -0,0 +1,2 @@
      +pub fn sync_sensors() { let page = fetch(1); store(page); }
      +fn fetch(p: u32) -> Vec<u8> { vec![] }
      """;

  private final DiffLineResolver resolver =
      new DiffLineResolver(Map.of(READING, PATCH, CLIENT, CLIENT_PATCH));

  /** Round one's findings: ids 1 and 2 in that round. */
  private final ReviewResponse.Finding offByOne =
      finding(
          READING,
          2,
          "recent_average off-by-one returns last n-1 readings and panics on empty input",
          "let tail = &r[r.len() - n + 1..];");

  private final ReviewResponse.Finding pagination =
      finding(
          CLIENT,
          1,
          "sync_sensors fetches only the first registry page",
          "pub fn sync_sensors() { let page = fetch(1); store(page); }");

  /** Round two's one new finding: id 1 in that round. */
  private final ReviewResponse.Finding quadratic =
      finding(
          READING,
          5,
          "dedupe is O(n^2) over a collection documented as up to 2M samples",
          "pub fn dedupe(v: Vec<u32>) -> Vec<u32> { let mut o = vec![]; for x in v { if !o.contains(&x) {"
              + " o.push(x) } } o }");

  private final ReviewResponse roundOne =
      new ReviewResponse(List.of(offByOne, pagination), List.of(), null);

  private final ReviewResponse roundTwo =
      new ReviewResponse(
          List.of(quadratic),
          List.of(
              new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there"),
              new ReviewResponse.PreviousFindingStatus(2, "unresolved", "still there")),
          null);

  /** Newest first, as the context carries them. */
  private final List<ReviewResponse> priors = List.of(roundTwo, roundOne);

  /** One bot thread per posted finding, each with its own round's marker. */
  private final List<GitHubReviewClient.PullRequestComment> threads =
      List.of(
          thread(11, READING, "🟠 HIGH", offByOne.title(), 1),
          thread(12, CLIENT, "🟠 HIGH", pagination.title(), 2),
          thread(21, READING, "🟡 MEDIUM", quadratic.title(), 1));

  /** The model's round three: both earlier findings reworded, one genuinely new. */
  private final ReviewResponse.Finding offByOneAgain =
      finding(
          READING,
          2,
          "recent_average is off by one and panics on empty input",
          "let tail = &r[r.len() - n + 1..];");

  private final ReviewResponse.Finding paginationAgain =
      finding(
          CLIENT,
          1,
          "sync_sensors fetches only the first registry page",
          "pub fn sync_sensors() { let page = fetch(1); store(page); }");

  private final ReviewResponse.Finding nan =
      finding(
          READING,
          6,
          "NaN and infinite temperatures silently evade excursion detection",
          "pub fn is_excursion(t: f64) -> bool { t > 8.0 }");

  private final ReviewResponse.Finding quadraticAgain =
      finding(
          READING,
          5,
          "dedupe is O(n^2) over a collection documented as up to 2M samples",
          "pub fn dedupe(v: Vec<u32>) -> Vec<u32> { let mut o = vec![]; for x in v { if !o.contains(&x) {"
              + " o.push(x) } } o }");

  @Test
  void aReRaiseOfAFindingAnEarlierRoundLeftOpenOnItsThreadIsDropped() {
    var response =
        new ReviewResponse(
            List.of(offByOneAgain, paginationAgain, nan),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there")),
            null);

    var guarded = guard(response, threads);

    assertEquals(List.of(nan), guarded.findings());
    assertEquals(response.previousFindingsStatus(), guarded.previousFindingsStatus());
  }

  @Test
  void aReRaiseOfTheEffectivePreviousRoundsOpenFindingIsDroppedToo() {
    var guarded = guard(new ReviewResponse(List.of(quadraticAgain, nan), List.of(), null), threads);

    assertEquals(List.of(nan), guarded.findings());
  }

  @Test
  void aMoreSevereRestatementIsPostedButTheSameFindingAtAnySeverityIsNot() {
    // Identity (file, line, title) is PrSummaryGenerator.reRaises, the summary's fold: always a
    // duplicate. A reworded restatement is one only when it is no more severe than the open thread.
    var escalated =
        new ReviewResponse.Finding(
            "critical",
            "high",
            offByOneAgain.file(),
            offByOneAgain.line(),
            offByOneAgain.title(),
            offByOneAgain.description(),
            offByOneAgain.suggestionOld(),
            null);
    var identical =
        new ReviewResponse.Finding(
            "critical",
            "high",
            offByOne.file(),
            offByOne.line(),
            "  " + offByOne.title().toUpperCase(java.util.Locale.ROOT) + " ",
            "Different words entirely.",
            null,
            null);
    var lower =
        new ReviewResponse.Finding(
            "low",
            "high",
            offByOneAgain.file(),
            offByOneAgain.line(),
            offByOneAgain.title(),
            offByOneAgain.description(),
            offByOneAgain.suggestionOld(),
            null);

    var guarded =
        guard(new ReviewResponse(List.of(escalated, identical, lower), List.of(), null), threads);

    assertEquals(List.of(escalated), guarded.findings());
    assertTrue(FollowUpAnalyzer.duplicatesOpenThread(identical, offByOne));
    assertFalse(FollowUpAnalyzer.duplicatesOpenThread(escalated, offByOne));
    assertTrue(FollowUpAnalyzer.duplicatesOpenThread(lower, offByOne));
    assertFalse(FollowUpAnalyzer.duplicatesOpenThread(nan, offByOne));
  }

  @Test
  void aFindingTheCurrentRoundResolvesIsNotGuarded() {
    // Its thread is about to close, so a finding on the same defect is not a second open thread.
    var response =
        new ReviewResponse(
            List.of(quadraticAgain),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "resolved", "fixed")),
            null);

    assertSame(response, guard(response, threads));
  }

  @Test
  void aFindingALaterRoundClosedIsNotGuarded() {
    var closing =
        new ReviewResponse(
            List.of(quadratic),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "justified", "intended")),
            null);
    var response = new ReviewResponse(List.of(offByOneAgain), List.of(), null);

    assertSame(
        response,
        FollowUpAnalyzer.withoutOpenThreadDuplicates(
            response, List.of(closing, roundOne), threads, resolver, Map.of(), BOT));
  }

  @Test
  void aFindingWhoseCodeLeftTheDiffIsNotGuarded() {
    // The defect may be raised again where it moved; the old thread points at code that is gone.
    var moved =
        new DiffLineResolver(
            Map.of(
                READING, "@@ -0,0 +1,1 @@\n+pub fn recent_average() {}\n", CLIENT, CLIENT_PATCH));
    var response = new ReviewResponse(List.of(offByOneAgain), List.of(), null);

    assertSame(
        response,
        FollowUpAnalyzer.withoutOpenThreadDuplicates(
            response, priors, threads, moved, Map.of(), BOT));
  }

  @Test
  void aFindingThatNeverGotAThreadIsNotGuarded() {
    var response = new ReviewResponse(List.of(offByOneAgain), List.of(), null);

    assertSame(response, guard(response, List.of(threads.get(1), threads.get(2))));
  }

  @Test
  void aThreadOnADifferentFindingThatReusedTheMarkerDoesNotCount() {
    // finding=1 on reading.rs is round two's thread, not round one's off-by-one finding.
    var response = new ReviewResponse(List.of(offByOneAgain), List.of(), null);

    assertSame(response, guard(response, List.of(threads.get(2))));
  }

  @Test
  void aThreadOpenedByAPrePRMarkerCommentIsFoundByItsTitle() {
    var legacy = comment(31, null, READING, "**🟠 HIGH — " + offByOne.title() + "**\n\nno marker");

    var guarded =
        guard(new ReviewResponse(List.of(offByOneAgain), List.of(), null), List.of(legacy));

    assertTrue(guarded.findings().isEmpty());
  }

  @Test
  void theResponseIsReturnedUntouchedWhenThereIsNothingToGuard() {
    var response = new ReviewResponse(List.of(offByOneAgain), List.of(), null);
    var empty = new ReviewResponse(List.of(), List.of(), null);

    assertSame(empty, guard(empty, threads));
    assertSame(response, guard(response, List.of()));
    assertSame(
        response,
        FollowUpAnalyzer.withoutOpenThreadDuplicates(
            response, null, threads, resolver, Map.of(), BOT));
    assertSame(
        response,
        FollowUpAnalyzer.withoutOpenThreadDuplicates(
            response, List.of(), threads, resolver, Map.of(), BOT));
    assertSame(
        response,
        FollowUpAnalyzer.withoutOpenThreadDuplicates(
            response, priors, threads, null, Map.of(), BOT));
  }

  @Test
  void theEarlierRoundsOpenFindingsAreTheOnesTheEffectiveRoundDoesNotNumber() {
    assertEquals(
        List.of(offByOne, pagination),
        FollowUpAnalyzer.openEarlierRoundFindings(priors, resolver, Map.of(), threads, BOT));
    assertEquals(
        List.of(),
        FollowUpAnalyzer.openEarlierRoundFindings(
            List.of(roundOne), resolver, Map.of(), threads, BOT),
        "with one round there is nothing older than the effective one");
    assertEquals(
        List.of(),
        FollowUpAnalyzer.openEarlierRoundFindings(null, resolver, Map.of(), threads, BOT));
    assertEquals(
        List.of(), FollowUpAnalyzer.openEarlierRoundFindings(priors, null, Map.of(), threads, BOT));
  }

  @Test
  void anEarlierFindingWithNoThreadOfItsOwnIsNotListedAsPosted() {
    // Round one's off-by-one never got a thread (summary-only, or refused by GitHub): a re-raise
    // is the only way it can still get one, so nothing may call it already posted.
    assertEquals(
        List.of(pagination),
        FollowUpAnalyzer.openEarlierRoundFindings(
            priors, resolver, Map.of(), List.of(threads.get(1), threads.get(2)), BOT));
  }

  @Test
  void anEarlierFindingALaterRoundResolvedOrWhoseCodeLeftTheDiffIsNotOpen() {
    var resolving =
        new ReviewResponse(
            List.of(quadratic),
            List.of(new ReviewResponse.PreviousFindingStatus(2, "resolved", "paginates now")),
            null);
    var moved =
        new DiffLineResolver(
            Map.of(
                READING, "@@ -0,0 +1,1 @@\n+pub fn recent_average() {}\n", CLIENT, CLIENT_PATCH));

    assertEquals(
        List.of(offByOne),
        FollowUpAnalyzer.openEarlierRoundFindings(
            List.of(resolving, roundOne), resolver, Map.of(), threads, BOT));
    assertEquals(
        List.of(pagination),
        FollowUpAnalyzer.openEarlierRoundFindings(priors, moved, Map.of(), threads, BOT));
  }

  @Test
  void theModelIsToldWhatEarlierRoundsLeftOpen() {
    var analyzer = new FollowUpAnalyzer(new ObjectMapper());

    var context =
        analyzer.buildPreviousFindingsContext(
            roundTwo.findings(),
            true,
            List.of(),
            threads,
            List.of(roundOne),
            BOT,
            Set.of(),
            List.of(offByOne, pagination, offByOne));

    assertTrue(context.startsWith("1. [HIGH] " + READING + ":5 — " + quadratic.title()), context);
    assertTrue(
        context.contains(
            "Still open from earlier rounds, each already posted on its own thread — do NOT raise"
                + " these again and do NOT include them in previous_findings_status:\n- "
                + READING
                + ":2 — "
                + offByOne.title()
                + "\n- "
                + CLIENT
                + ":1 — "
                + pagination.title()
                + "\n"),
        context);
    assertEquals(1, context.split(offByOne.title(), -1).length - 1, "listed once: " + context);
  }

  @Test
  void anEarlierFindingTheAnsweredListOrTheNumberedListCarriesIsNotListedAgain() {
    var analyzer = new FollowUpAnalyzer(new ObjectMapper());
    var answered =
        List.of(
            threads.get(0),
            comment(13, 11L, READING, "It is intended: callers always pass n >= 1.", "maintainer"));

    var context =
        analyzer.buildPreviousFindingsContext(
            List.of(quadratic, paginationAgain),
            true,
            List.of(),
            answered,
            List.of(roundOne),
            BOT,
            Set.of(),
            List.of(offByOne, pagination));

    assertFalse(context.contains("Still open from earlier rounds"), context);
    assertTrue(context.contains("Answered in earlier rounds"), context);
  }

  @Test
  void theSectionRidesTheReviewBodyFallbackToo() {
    var analyzer = new FollowUpAnalyzer(new ObjectMapper());

    var context =
        analyzer.buildPreviousFindingsContext(
            List.of(), false, List.of(), threads, List.of(), BOT, Set.of(), List.of(pagination));

    assertTrue(context.contains("Still open from earlier rounds"), context);
    assertTrue(context.contains(CLIENT + ":1 — " + pagination.title()), context);
  }

  private ReviewResponse guard(
      ReviewResponse response, List<GitHubReviewClient.PullRequestComment> comments) {
    return FollowUpAnalyzer.withoutOpenThreadDuplicates(
        response, priors, comments, resolver, Map.of(), BOT);
  }

  private static ReviewResponse.Finding finding(
      String file, int line, String title, String anchor) {
    return new ReviewResponse.Finding(
        "high", "high", file, line, title, title + ", described.", anchor, null);
  }

  private static GitHubReviewClient.PullRequestComment thread(
      long id, String path, String rating, String title, int marker) {
    return comment(
        id,
        null,
        path,
        "**"
            + rating
            + " — "
            + title
            + "**\n\nWhy it matters.\n\n"
            + SuggestionFormatter.findingMarker(marker));
  }

  private static GitHubReviewClient.PullRequestComment comment(
      long id, Long inReplyTo, String path, String body) {
    return comment(id, inReplyTo, path, body, "thrillhousebot[bot]");
  }

  private static GitHubReviewClient.PullRequestComment comment(
      long id, Long inReplyTo, String path, String body, String author) {
    return new GitHubReviewClient.PullRequestComment(
        id,
        inReplyTo,
        path,
        body,
        new GitHubReviewClient.ReviewResponse.User(author),
        "thrillhousebot[bot]".equals(author) ? null : "OWNER");
  }
}
