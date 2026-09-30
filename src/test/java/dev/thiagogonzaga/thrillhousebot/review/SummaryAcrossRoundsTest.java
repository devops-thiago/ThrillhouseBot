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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The summary comment across rounds (#917): each round builds its verdict with the real {@link
 * VerdictBuilder}, {@link FollowUpAnalyzer} and {@link PrSummaryGenerator}, and publishes it
 * through {@link ReviewPublisher#publishSummary} — the first round creating the comment, every
 * later round editing it in place. The edited body must describe the pull request as it stands:
 * every earlier finding still open plus the round's new ones, in the risk counts, Key Findings and
 * "Things to double-check".
 */
class SummaryAcrossRoundsTest {

  private static final String FILE = "src/Main.java";

  private static final BotIdentity BOT = BotIdentity.from(List.of("thrillhousebot[bot]"));

  private static final GitHubReviewClient.ReviewResponse.User BOT_USER =
      new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]");

  private static final CiStatusEvaluator.CiEvaluation CI_CLEAR =
      new CiStatusEvaluator.CiEvaluation(List.of(), false);

  /** The head every round reviews: each finding's anchor is still in it. */
  private static final String PATCH =
      """
      @@ -10,0 +10,4 @@
      +String sql = "SELECT * FROM t WHERE id = " + input;
      +int total = count * price;
      +log(user.name());
      +String unused = "";\
      """;

  private static final ReviewResponse.Finding CRITICAL =
      new ReviewResponse.Finding(
          "critical",
          "high",
          FILE,
          10,
          "SQL injection through string concatenation",
          "The query concatenates request input into SQL text.",
          "String sql = \"SELECT * FROM t WHERE id = \" + input;",
          "use a prepared statement");

  private static final ReviewResponse.Finding HIGH =
      new ReviewResponse.Finding(
          "high",
          "high",
          FILE,
          20,
          "Integer overflow on order total",
          "Multiplying two ints can wrap for large carts.",
          "int total = count * price;",
          "long total = (long) count * price;");

  /** Low confidence at medium risk: routed to "Things to double-check", never inline. */
  private static final ReviewResponse.Finding DOUBLE_CHECK =
      new ReviewResponse.Finding(
          "medium",
          "low",
          FILE,
          30,
          "Possible null user before logging",
          "The logger dereferences a user that may be absent on anonymous requests.",
          "log(user.name());",
          null);

  private static final ReviewResponse.Finding NEW_IN_ROUND_TWO =
      new ReviewResponse.Finding(
          "medium",
          "high",
          FILE,
          40,
          "Dead local variable",
          "The empty string is assigned and never read.",
          "String unused = \"\";",
          null);

  private static final ReviewResponse ROUND_ONE =
      new ReviewResponse(List.of(CRITICAL, HIGH, DOUBLE_CHECK), List.of(), null);

  // #934's shapes. ThrillhouseBot-test#139 (Scala): a HIGH SQL injection and a second finding on
  // the same line. #145 (Node): a null-body CRITICAL and a LOW whose text restates it, one line.

  private static final String REPO = "app/EventRepository.scala";

  private static final String REPO_PATCH =
      """
      @@ -15,0 +15,1 @@
      +sql"SELECT * FROM events WHERE container = $containerId"      """;

  private static final String SERVER = "api/server.js";

  private static final String SERVER_PATCH =
      """
      @@ -32,0 +32,1 @@
      +const name = req.body.name;      """;

  private static final Map<String, String> PATCHES =
      Map.of(FILE, PATCH, REPO, REPO_PATCH, SERVER, SERVER_PATCH);

  private static final ReviewResponse.Finding SQL_INJECTION =
      new ReviewResponse.Finding(
          "high",
          "high",
          REPO,
          15,
          "SQL injection via unvalidated container id in findByContainer",
          "The container id is interpolated into the SQL text of findByContainer without"
              + " validation, so a crafted container id injects SQL.",
          "sql\"SELECT * FROM events WHERE container = $containerId\"",
          null);

  private static final ReviewResponse.Finding UNVALIDATED_ID =
      new ReviewResponse.Finding(
          "medium",
          "high",
          REPO,
          15,
          "Container id is not validated in findByContainer",
          "findByContainer accepts any container id without validation before it reaches the SQL"
              + " text.",
          "sql\"SELECT * FROM events WHERE container = $containerId\"",
          null);

  private static final ReviewResponse.Finding NULL_BODY =
      new ReviewResponse.Finding(
          "critical",
          "high",
          SERVER,
          32,
          "Null request body crashes the server",
          "req.body is dereferenced without a null check, so a request with no JSON body throws"
              + " and crashes the server process.",
          "const name = req.body.name;",
          null);

  private static final ReviewResponse.Finding NULL_BODY_LOW =
      new ReviewResponse.Finding(
          "low",
          "high",
          SERVER,
          32,
          "req.body.name read without a null check",
          "req.body is dereferenced without a null check when a request has no JSON body.",
          "const name = req.body.name;",
          null);

  private static final ReviewResponse SHAPES_ROUND =
      new ReviewResponse(
          List.of(SQL_INJECTION, UNVALIDATED_ID, NULL_BODY, NULL_BODY_LOW), List.of(), null);

  private final ObjectMapper mapper = new ObjectMapper();

  private final GitHubCommentClient commentClient = mock(GitHubCommentClient.class);

  private final VerdictBuilder builder =
      new VerdictBuilder(
          new PrSummaryGenerator(false),
          new FollowUpAnalyzer(mapper),
          BOT,
          BlockingStrictness.BALANCED);

  private final GitHubReviewClient reviewClient = mock(GitHubReviewClient.class);

  private final ReviewPublisher publisher =
      new ReviewPublisher(
          reviewClient,
          commentClient,
          mock(ReviewThreadService.class),
          mock(SuggestionFormatter.class),
          mock(FollowUpAnalyzer.class),
          mock(PrLabeler.class),
          configWithWriteBudget(),
          BOT);

  /** A test PR (one file, +4) is over these thresholds, so only the #933 guard can silence it. */
  private final VerdictBuilder nudgingBuilder =
      new VerdictBuilder(
          new PrSummaryGenerator(false, new LargePrNudge(true, 1, 0)),
          new FollowUpAnalyzer(mapper),
          BOT,
          BlockingStrictness.BALANCED);

  private static final CiStatusEvaluator.CiEvaluation CI_FAILING =
      new CiStatusEvaluator.CiEvaluation(
          List.of(new ReviewResult.CiCheck("test", "check-run", "failing", "failure")), false);

  private final DiffBudgetPlanner.BudgetPlan plan =
      new DiffBudgetPlanner.BudgetPlan(
          List.of(), List.of(), List.of(), true, null, null, null, null);

  @Test
  void aReviewWithNoNewFindingsKeepsEveryStillOpenFindingInTheEditedSummary() {
    var first = publishFirstRound();
    assertRisk(first, 1, 1, 1, 0);

    // /review on the unchanged head: the model reports the two inline findings still open and
    // says nothing about the double-check one, which the backstop holds because its code is still
    // in the diff and nobody cleared it.
    var round =
        new ReviewResponse(
            List.of(),
            List.of(
                new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there"),
                new ReviewResponse.PreviousFindingStatus(2, "unresolved", "still there")),
            null);
    var result = builder.build(followUp(List.of(ROUND_ONE), List.of()), round, CI_CLEAR, plan);
    var edited = publishFollowUp(result, first);

    assertEquals(3, result.unresolvedPreviousCount());
    assertEquals(result.unresolvedPreviousCount(), result.openPreviousFindings().size());
    assertRisk(edited, 1, 1, 1, 0);
    var keyFindings = section(edited, "### Key Findings");
    assertTrue(keyFindings.contains(CRITICAL.title()), edited);
    assertTrue(keyFindings.contains(HIGH.title()), edited);
    assertTrue(keyFindings.contains(PrSummaryGenerator.CARRIED_NOTE), edited);
    assertTrue(section(edited, "### Things to double-check").contains(DOUBLE_CHECK.title()));
    assertTrue(edited.contains("| ⚠️ Still present | 3 |"), edited);
    assertTrue(edited.contains(PrSummaryGenerator.carriedCountNote(3)), edited);
    assertFalse(edited.contains(PrSummaryGenerator.ZERO_ISSUES_MESSAGE), edited);
  }

  @Test
  void aFindingResolvedInRoundTwoLeavesTheSummaryAndANewOneJoinsIt() {
    var first = publishFirstRound();

    var round =
        new ReviewResponse(
            List.of(NEW_IN_ROUND_TWO),
            List.of(
                new ReviewResponse.PreviousFindingStatus(1, "resolved", "now a prepared statement"),
                new ReviewResponse.PreviousFindingStatus(2, "unresolved", "still there"),
                new ReviewResponse.PreviousFindingStatus(3, "unresolved", "still there")),
            null);
    var result = builder.build(followUp(List.of(ROUND_ONE), List.of()), round, CI_CLEAR, plan);
    var edited = publishFollowUp(result, first);

    assertRisk(edited, 0, 1, 2, 0);
    assertFalse(edited.contains(CRITICAL.title()), edited);
    var keyFindings = section(edited, "### Key Findings");
    assertTrue(keyFindings.contains(HIGH.title() + " (`" + FILE + ":20`) "), edited);
    // The round's own finding is not marked as carried; the earlier one is.
    assertTrue(keyFindings.contains(NEW_IN_ROUND_TWO.title() + " (`" + FILE + ":40`)\n"), edited);
    assertTrue(
        keyFindings.contains(
            HIGH.title() + " (`" + FILE + ":20`) " + PrSummaryGenerator.CARRIED_NOTE),
        edited);
    assertTrue(section(edited, "### Things to double-check").contains(DOUBLE_CHECK.title()));

    // The delta comment of the same round counts what the summary lists.
    var delta = FollowUpDeltaSummary.render(result, ROUND_ONE.findings()).orElseThrow();
    assertTrue(delta.contains("**New findings this round:** 1"), delta);
    assertTrue(delta.contains("**Previous findings resolved:** 1"), delta);
    assertTrue(
        delta.contains("**Previous findings still open:** " + result.openPreviousFindings().size()),
        delta);
  }

  @Test
  void aDoubleCheckItemSurvivesRoundsUntilAMaintainerClearsIt() {
    var first = publishFirstRound();
    var quietRound =
        new ReviewResponse(
            List.of(),
            List.of(
                new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there"),
                new ReviewResponse.PreviousFindingStatus(2, "unresolved", "still there")),
            null);

    // Round two found nothing, so round three still reports on round one and the double-check
    // item comes from the backstop's replay of both rounds.
    var second = builder.build(followUp(List.of(ROUND_ONE), List.of()), quietRound, CI_CLEAR, plan);
    var secondBody = publishFollowUp(second, first);
    var third =
        builder.build(
            followUp(List.of(quietRound, ROUND_ONE), List.of()), quietRound, CI_CLEAR, plan);
    var thirdBody = publishFollowUp(third, secondBody);

    assertTrue(section(thirdBody, "### Things to double-check").contains(DOUBLE_CHECK.title()));
    assertRisk(thirdBody, 1, 1, 1, 0);

    var clear =
        new GitHubCommentClient.IssueComment(
            900L,
            "@thrillhousebot resolved `"
                + FILE
                + ":30` — "
                + DOUBLE_CHECK.title()
                + "\n\nAnonymous requests never reach this logger.",
            new GitHubReviewClient.ReviewResponse.User("maintainer"),
            "MEMBER");
    var cleared =
        builder.build(
            followUp(List.of(quietRound, ROUND_ONE), List.of(clear)), quietRound, CI_CLEAR, plan);
    var clearedBody = publishFollowUp(cleared, thirdBody);

    assertFalse(clearedBody.contains("### Things to double-check"), clearedBody);
    assertFalse(clearedBody.contains(DOUBLE_CHECK.title()), clearedBody);
    assertRisk(clearedBody, 1, 1, 0, 0);
  }

  /**
   * A run that stood down because the head moved (#806) hands its findings to the replacement,
   * which folds them into its previous round. They are listed in the summary the replacement posts
   * exactly when that run confirms them still open, like any earlier round's finding.
   */
  @Test
  void findingsCarriedFromASupersededRunAreListedOnceTheReplacementConfirmsThem() {
    var carried =
        new SupersededFindingsCarryover(mapper)
            .merge(List.of(), List.of(), List.of(CRITICAL, DOUBLE_CHECK))
            .parsed();
    var round =
        new ReviewResponse(
            List.of(),
            List.of(
                new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there"),
                new ReviewResponse.PreviousFindingStatus(2, "resolved", "guarded now")),
            null);

    var result = builder.build(context(carried, List.of(), true, true), round, CI_CLEAR, plan);
    assertTrue(publisher.publishSummary("auth", "o", "r", 1, result, false));
    var captor = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(anyString(), anyString(), eq("o"), eq("r"), eq(1), captor.capture());
    var body = captor.getValue().body();

    assertRisk(body, 1, 0, 0, 0);
    assertTrue(section(body, "### Key Findings").contains(CRITICAL.title()), body);
    assertFalse(body.contains(DOUBLE_CHECK.title()), body);
  }

  /**
   * #933: a follow-up round that opened nothing new while earlier findings stay open. Seen on a
   * CI-held `/review` of an unchanged head: the summary carried the "Large PR — no inline findings"
   * note and the review body opened "found no issues in this PR" under CHANGES_REQUESTED. No
   * surface may claim the pull request is clean while anything is open.
   */
  @Test
  void aFollowUpWithNoNewFindingsNeverCallsThePrCleanWhileEarlierFindingsAreOpen() {
    var first = publishFirstRound();
    var round =
        new ReviewResponse(
            List.of(),
            List.of(
                new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there"),
                new ReviewResponse.PreviousFindingStatus(2, "unresolved", "still there")),
            null);

    var result =
        nudgingBuilder.build(followUp(List.of(ROUND_ONE), List.of()), round, CI_FAILING, plan);
    var summary = publishFollowUp(result, first);
    var body = postedReviewBody(result);
    var checkSummary = VerdictBuilder.checkSummaryForResult(result);

    assertEquals(0, result.totalFindings());
    assertEquals(3, result.unresolvedPreviousCount());
    assertEquals(ReviewState.REQUEST_CHANGES, result.reviewState());
    assertFalse(summary.contains(LargePrNudge.NUDGE_HEADING), summary);
    assertFalse(summary.contains("opened no inline findings"), summary);
    assertFalse(summary.contains(PrSummaryGenerator.ZERO_ISSUES_MESSAGE), summary);
    assertTrue(body.startsWith(ReviewResult.unresolvedPreviousMessage(3)), body);
    assertTrue(body.contains(ReviewResult.CI_PENDING_ALSO_LEAD_IN), body);
    assertFalse(body.contains("found no issues"), body);
    assertTrue(checkSummary.contains("3 previous finding(s) remain unresolved"), checkSummary);
    // The delta comment (it posts only on a round that moved something) would count the same
    // set: its "still open" line is the same unresolvedPreviousCount the body states.
    assertEquals(result.unresolvedPreviousCount(), result.openPreviousFindings().size());
  }

  /** The other half of #933: with nothing earlier open, the clean wording is still right. */
  @Test
  void aFollowUpThatClosedEverythingKeepsTheCleanWording() {
    var first = publishFirstRound();
    var round =
        new ReviewResponse(
            List.of(),
            List.of(
                new ReviewResponse.PreviousFindingStatus(1, "resolved", "prepared statement"),
                new ReviewResponse.PreviousFindingStatus(2, "resolved", "widened to long"),
                new ReviewResponse.PreviousFindingStatus(3, "resolved", "null-guarded")),
            null);

    var held =
        nudgingBuilder.build(followUp(List.of(ROUND_ONE), List.of()), round, CI_FAILING, plan);
    assertEquals(0, held.unresolvedPreviousCount());
    assertTrue(held.openPreviousFindings().isEmpty());
    var heldSummary = publishFollowUp(held, first);
    assertTrue(heldSummary.contains(LargePrNudge.NUDGE_HEADING), heldSummary);
    var heldBody = postedReviewBody(held);
    assertTrue(heldBody.startsWith(ReviewResult.NO_ISSUES_CI_PENDING_LEAD_IN), heldBody);
    assertFalse(heldBody.contains("remain unresolved"), heldBody);
    assertFalse(
        VerdictBuilder.checkSummaryForResult(held).contains("remain unresolved"),
        VerdictBuilder.checkSummaryForResult(held));

    var approved =
        nudgingBuilder.build(followUp(List.of(ROUND_ONE), List.of()), round, CI_CLEAR, plan);
    assertEquals(ReviewState.APPROVE, approved.reviewState());
    var approvedSummary = publishFollowUp(approved, heldSummary);
    assertTrue(approvedSummary.contains(PrSummaryGenerator.ZERO_ISSUES_MESSAGE), approvedSummary);
    assertTrue(approvedSummary.contains(LargePrNudge.NUDGE_HEADING), approvedSummary);
    assertEquals(
        PrSummaryGenerator.ZERO_ISSUES_MESSAGE, VerdictBuilder.checkSummaryForResult(approved));
  }

  // #934: the carried set is the still-open set by identity.

  // #947 / #948: a round older than the effective previous one. Once round two raises anything,
  // round one's findings have no id this round's model reports on; only the backstop keeps them.

  private static final ReviewResponse.Finding RACE =
      new ReviewResponse.Finding(
          "high",
          "high",
          FILE,
          11,
          "Concurrent requests race on the shared order total",
          "Every request thread writes the shared total with no synchronization, so concurrent"
              + " requests corrupt it.",
          "int total = count * price;",
          "guard the update with a lock");

  private static final ReviewResponse.Finding LOW_NOTE =
      new ReviewResponse.Finding(
          "low",
          "high",
          FILE,
          13,
          "Placeholder string has no explaining comment",
          "The empty placeholder is not explained.",
          "String unused = \"\";",
          null);

  /** Round one: CRITICAL (1), HIGH (2), a double-check item (3) and a concurrency HIGH (4). */
  private static final ReviewResponse ROUND_ONE_WITH_RACE =
      new ReviewResponse(List.of(CRITICAL, HIGH, DOUBLE_CHECK, RACE), List.of(), null);

  /** Round two raised one finding and reported every round-one finding still open. */
  private static final ReviewResponse ROUND_TWO_RAISED_ONE =
      new ReviewResponse(
          List.of(NEW_IN_ROUND_TWO),
          List.of(
              new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there"),
              new ReviewResponse.PreviousFindingStatus(2, "unresolved", "still there"),
              new ReviewResponse.PreviousFindingStatus(3, "unresolved", "still there"),
              new ReviewResponse.PreviousFindingStatus(4, "unresolved", "still there")),
          null);

  /** Round three: one LOW finding, and round two's finding reported still open. */
  private static final ReviewResponse ROUND_THREE =
      new ReviewResponse(
          List.of(LOW_NOTE),
          List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there")),
          null);

  private static final String WRONG_DECLINE =
      "Declining: this cannot run concurrently. Requests are handled one at a time, so there is"
          + " no race here.";

  /** The bot's root comment for round one's finding {@code id}, as it posts one. */
  private static GitHubReviewClient.PullRequestComment rootComment(
      long commentId, ReviewResponse.Finding finding, int id) {
    return new GitHubReviewClient.PullRequestComment(
        commentId,
        null,
        finding.file(),
        "**🟠 "
            + finding.risk().toUpperCase(java.util.Locale.ROOT)
            + " — "
            + finding.title()
            + "**\n\n"
            + finding.description()
            + "\n\n"
            + SuggestionFormatter.findingMarker(id),
        BOT_USER,
        null);
  }

  private static GitHubReviewClient.PullRequestComment maintainerReply(
      long commentId, long rootId, String body) {
    return new GitHubReviewClient.PullRequestComment(
        commentId,
        rootId,
        FILE,
        body,
        new GitHubReviewClient.ReviewResponse.User("maintainer"),
        "OWNER");
  }

  private ReviewResult roundThree(
      List<GitHubReviewClient.PullRequestComment> threads, String diff) {
    return builder.build(
        followUp(List.of(ROUND_TWO_RAISED_ONE, ROUND_ONE_WITH_RACE), threads, diff),
        ROUND_THREE,
        CI_CLEAR,
        plan);
  }

  private static long statusCount(ReviewResult result, String status) {
    return result.previousStatuses().stream().filter(s -> status.equals(s.status())).count();
  }

  /**
   * #947: the evidence's exact shape. Round one raised a concurrency finding, round two raised
   * something new, a maintainer then declined the round-one finding on its thread with a premise
   * that is wrong, and round three ran. The finding used to leave the summary with no status at
   * all; the decline was not upheld, so it stays open in the counts, Key Findings and the verdict.
   */
  @Test
  void anEarlierRoundFindingWhoseDeclineIsNotUpheldStaysOpen() {
    var first = publishFirstRound(ROUND_ONE_WITH_RACE);
    var threads = List.of(rootComment(104L, RACE, 4), maintainerReply(204L, 104L, WRONG_DECLINE));

    var result = roundThree(threads, PATCH);
    var edited = publishFollowUp(result, first);

    // Carried in: round one's four and round two's one — all five still present.
    assertEquals(5, result.unresolvedPreviousCount());
    assertEquals(0, statusCount(result, "justified"));
    assertEquals(result.unresolvedPreviousCount(), result.openPreviousFindings().size());
    assertTrue(
        result.openPreviousFindings().stream().anyMatch(f -> RACE.title().equals(f.title())));
    assertOpenCount(edited, 5);
    assertTrue(edited.contains("| 💬 Justified | 0 |"), edited);
    assertRisk(edited, 1, 2, 2, 1);
    assertTrue(section(edited, "### Key Findings").contains(RACE.title()), edited);
    assertEquals(ReviewState.REQUEST_CHANGES, result.reviewState());
    // The review body carries every unresolved note that opens with the re-check's lead-in, as it
    // does for a decline on the previous round's finding: it says why the decline was not taken.
    var note =
        result.previousStatuses().stream()
            .filter(s -> "unresolved".equals(s.status()))
            .map(ReviewResult.PreviousFindingStatus::note)
            .filter(n -> n.startsWith(RebuttalContradiction.NOTE_LEAD_IN))
            .findFirst()
            .orElseThrow();
    assertTrue(note.contains("cannot run concurrently"), note);
    assertTrue(note.endsWith("Reply again to keep the decline."), note);
  }

  /**
   * The regression the change must not break: the same wrong decline on a finding of the effective
   * previous round (no round raised anything since), which the model weighs and reports unresolved
   * — rc3's behaviour. The finding is still present, and it counts the same as in the shape above.
   */
  @Test
  void aDeclineTheModelDidNotUpholdOnThePreviousRoundStaysOpen() {
    var first = publishFirstRound(ROUND_ONE_WITH_RACE);
    var threads = List.of(rootComment(104L, RACE, 4), maintainerReply(204L, 104L, WRONG_DECLINE));
    var round =
        new ReviewResponse(
            List.of(LOW_NOTE),
            List.of(
                new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there"),
                new ReviewResponse.PreviousFindingStatus(2, "unresolved", "still there"),
                new ReviewResponse.PreviousFindingStatus(3, "unresolved", "still there"),
                new ReviewResponse.PreviousFindingStatus(4, "unresolved", "the decline is wrong")),
            null);

    var result =
        builder.build(
            followUp(List.of(ROUND_ONE_WITH_RACE), threads, PATCH), round, CI_CLEAR, plan);
    var edited = publishFollowUp(result, first);

    assertEquals(4, result.unresolvedPreviousCount());
    assertOpenCount(edited, 4);
    assertTrue(section(edited, "### Key Findings").contains(RACE.title()), edited);
    assertEquals(ReviewState.REQUEST_CHANGES, result.reviewState());
  }

  /**
   * #947's other half: a decline on an earlier round's finding that stands is reported justified,
   * so the summary's counts still add up to the findings carried in — and its status names no
   * finding of the previous round, so nothing keyed by id resolves or learns from the wrong one.
   */
  @Test
  void anEarlierRoundDeclineThatStandsIsCountedJustified() {
    var first = publishFirstRound(ROUND_ONE_WITH_RACE);
    var threads =
        List.of(
            rootComment(102L, HIGH, 2),
            maintainerReply(
                202L,
                102L,
                "Declining: the storefront caps a cart at 100 items, so this product stays far"
                    + " below the int range."));

    var result = roundThree(threads, PATCH);
    var edited = publishFollowUp(result, first);

    assertEquals(4, result.unresolvedPreviousCount());
    assertEquals(1, statusCount(result, "justified"));
    assertOpenCount(edited, 4);
    assertTrue(edited.contains("| 💬 Justified | 1 |"), edited);
    assertFalse(section(edited, "### Key Findings").contains(HIGH.title()), edited);
    var justified =
        result.previousStatuses().stream()
            .filter(s -> "justified".equals(s.status()))
            .findFirst()
            .orElseThrow();
    assertEquals(FollowUpAnalyzer.EARLIER_ROUND_ID, justified.id());
  }

  /** One push-back, then defer: a second reply keeps a decline the re-check would hold. */
  @Test
  void aSecondReplyKeepsAnEarlierRoundDecline() {
    var threads =
        List.of(
            rootComment(104L, RACE, 4),
            maintainerReply(204L, 104L, WRONG_DECLINE),
            maintainerReply(205L, 104L, "Still declining: the handler is behind a global lock."));

    var result = roundThree(threads, PATCH);

    assertEquals(4, result.unresolvedPreviousCount());
    assertEquals(1, statusCount(result, "justified"));
    assertTrue(
        result.openPreviousFindings().stream().noneMatch(f -> RACE.title().equals(f.title())));
  }

  /**
   * A standing decline's {@code justified} status names no finding, and it must not hold approval:
   * once everything else is closed, a pull request whose only earlier finding was validly declined
   * approves, with the decline still counted.
   */
  @Test
  void anEarlierRoundDeclineThatStandsDoesNotHoldApproval() {
    var roundOne = new ReviewResponse(List.of(RACE), List.of(), null);
    var roundTwo =
        new ReviewResponse(
            List.of(NEW_IN_ROUND_TWO),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there")),
            null);
    var roundThree =
        new ReviewResponse(
            List.of(),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "resolved", "removed")),
            null);
    var threads =
        List.of(
            rootComment(101L, RACE, 1),
            maintainerReply(201L, 101L, WRONG_DECLINE),
            maintainerReply(202L, 101L, "Still declining: the handler holds a global lock."));

    var result =
        builder.build(
            followUp(List.of(roundTwo, roundOne), threads, PATCH), roundThree, CI_CLEAR, plan);

    assertEquals(0, result.unresolvedPreviousCount());
    assertEquals(1, statusCount(result, "justified"));
    assertTrue(result.openPreviousFindings().isEmpty());
    assertEquals(ReviewState.APPROVE, result.reviewState());
  }

  /**
   * #948: a follow-up whose new findings are all below the blocking bar, while an earlier round's
   * CRITICAL is still open and listed as still present. The verdict is computed over the same open
   * set, so it requests changes rather than ending as a comment.
   */
  @Test
  void anEarlierCriticalStillOpenRequestsChangesWhenTheNewFindingsAreLow() {
    var first = publishFirstRound(ROUND_ONE_WITH_RACE);

    var result = roundThree(List.of(), PATCH);
    var edited = publishFollowUp(result, first);

    assertEquals(1, result.totalFindings());
    assertEquals(RiskLevel.LOW, result.highestRisk());
    assertTrue(
        result.openPreviousFindings().stream().anyMatch(f -> CRITICAL.title().equals(f.title())));
    assertRisk(edited, 1, 2, 2, 1);
    assertEquals(ReviewState.REQUEST_CHANGES, result.reviewState());
    assertEquals("failure", VerdictBuilder.conclusionForResult(result));
  }

  private static ReviewResponse allUnresolved(List<ReviewResponse.Finding> raised, int count) {
    var statuses = new ArrayList<ReviewResponse.PreviousFindingStatus>();
    for (var id = 1; id <= count; id++) {
      statuses.add(new ReviewResponse.PreviousFindingStatus(id, "unresolved", "still there"));
    }
    return new ReviewResponse(raised, statuses, null);
  }

  /** The Risk Assessment, "including N" line and "Still present" row all name {@code open}. */
  private static void assertOpenCount(String body, int open) {
    assertTrue(body.contains("| ⚠️ Still present | " + open + " |"), body);
    assertTrue(body.contains(PrSummaryGenerator.carriedCountNote(open)), body);
  }

  @Test
  void distinctFindingsOnOneLineAreBothCarriedWhenTheModelReportsThem() {
    // The precondition that made #917's dedupe fold them: they read as one defect.
    assertTrue(FollowUpAnalyzer.isSameFinding(UNVALIDATED_ID, SQL_INJECTION));
    assertTrue(FollowUpAnalyzer.isSameFinding(NULL_BODY_LOW, NULL_BODY));
    var first = publishFirstRound(SHAPES_ROUND);
    assertRisk(first, 1, 1, 1, 1);

    var result =
        builder.build(
            followUp(List.of(SHAPES_ROUND), List.of()),
            allUnresolved(List.of(), 4),
            CI_CLEAR,
            plan);
    var edited = publishFollowUp(result, first);

    assertEquals(4, result.unresolvedPreviousCount());
    assertEquals(result.unresolvedPreviousCount(), result.openPreviousFindings().size());
    assertRisk(edited, 1, 1, 1, 1);
    assertOpenCount(edited, 4);
    var keyFindings = section(edited, "### Key Findings");
    for (var finding : SHAPES_ROUND.findings()) {
      assertTrue(keyFindings.contains(finding.title()), edited);
    }
    assertTrue(
        keyFindings.contains(
            NULL_BODY.title() + " (`" + SERVER + ":32`) " + PrSummaryGenerator.CARRIED_NOTE),
        edited);
  }

  @Test
  void distinctFindingsOneRoundRaisedAreEachHeldWhenTheModelSaysNothing() {
    var first = publishFirstRound(SHAPES_ROUND);

    var result =
        builder.build(
            followUp(List.of(SHAPES_ROUND), List.of()),
            new ReviewResponse(List.of(), List.of(), null),
            CI_CLEAR,
            plan);
    var edited = publishFollowUp(result, first);

    // Two threads each: the backstop must not fold a round's own findings into one hold.
    assertEquals(4, result.unresolvedPreviousCount());
    // The held CRITICAL is listed as still present, so it blocks as the model's own would (#948).
    assertEquals(ReviewState.REQUEST_CHANGES, result.reviewState());
    assertRisk(edited, 1, 1, 1, 1);
    assertOpenCount(edited, 4);
    assertTrue(section(edited, "### Key Findings").contains(SQL_INJECTION.title()), edited);
    assertTrue(section(edited, "### Key Findings").contains(NULL_BODY.title()), edited);
  }

  @Test
  void aReRaiseRatedHigherLaterIsHeldAtItsHigherSeverity() {
    var reRated =
        new ReviewResponse.Finding(
            "critical",
            "high",
            SERVER,
            32,
            "Missing null check on req.body crashes the server",
            NULL_BODY_LOW.description(),
            NULL_BODY_LOW.suggestionOld(),
            null);
    assertTrue(FollowUpAnalyzer.isSameFinding(reRated, NULL_BODY_LOW));
    var roundOne = new ReviewResponse(List.of(NULL_BODY_LOW), List.of(), null);
    var roundTwo = allUnresolved(List.of(reRated), 1);
    var first = publishFirstRound(roundOne);
    var second =
        publishFollowUp(
            builder.build(followUp(List.of(roundOne), List.of()), roundTwo, CI_CLEAR, plan), first);
    assertRisk(second, 1, 0, 0, 1);

    // Round three says nothing about either: the backstop holds the defect once, and lists it as
    // the critical it was re-rated to rather than the low it was first raised as.
    var result =
        builder.build(
            followUp(List.of(roundTwo, roundOne), List.of()),
            new ReviewResponse(List.of(), List.of(), null),
            CI_CLEAR,
            plan);
    var third = publishFollowUp(result, second);

    assertEquals(1, result.unresolvedPreviousCount());
    assertRisk(third, 1, 0, 0, 0);
    assertOpenCount(third, 1);
    assertTrue(section(third, "### Key Findings").contains(reRated.title()), third);
  }

  @Test
  void theKeyFindingsCapNeverCutsACarriedFindingForALowerOrEqualNewOne() {
    var first = publishFirstRound();
    var fresh = new ArrayList<ReviewResponse.Finding>();
    for (var i = 0; i < 6; i++) {
      fresh.add(
          new ReviewResponse.Finding(
              "high",
              "high",
              FILE,
              100 + i,
              "Fresh high finding " + i,
              "A distinct defect raised this round, number " + i + ".",
              null,
              null));
    }
    fresh.add(
        new ReviewResponse.Finding(
            "medium", "high", FILE, 200, "Fresh medium finding", "A medium one.", null, null));

    var result =
        builder.build(
            followUp(List.of(ROUND_ONE), List.of()), allUnresolved(fresh, 3), CI_CLEAR, plan);
    var edited = publishFollowUp(result, first);

    assertRisk(edited, 1, 7, 2, 0);
    assertOpenCount(edited, 3);
    var keyFindings = section(edited, "### Key Findings");
    assertEquals(ReviewResult.KEY_FINDINGS_COUNT, keyFindings.split("\n- ").length - 1, edited);
    assertTrue(
        keyFindings.contains(
            CRITICAL.title() + " (`" + FILE + ":10`) " + PrSummaryGenerator.CARRIED_NOTE),
        edited);
    // At equal severity the carried high outranks the round's own.
    assertTrue(
        keyFindings.contains(
            HIGH.title() + " (`" + FILE + ":20`) " + PrSummaryGenerator.CARRIED_NOTE),
        edited);
    assertFalse(keyFindings.contains("Fresh medium finding"), edited);
    assertTrue(keyFindings.indexOf(HIGH.title()) < keyFindings.indexOf("Fresh high finding"));
  }

  @Test
  void anIdReportedTwiceOrNamingNoFindingIsNotCountedAsStillPresent() {
    var first = publishFirstRound();
    var round =
        new ReviewResponse(
            List.of(),
            List.of(
                new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there"),
                new ReviewResponse.PreviousFindingStatus(1, "unresolved", "said again"),
                new ReviewResponse.PreviousFindingStatus(2, "unresolved", "still there"),
                new ReviewResponse.PreviousFindingStatus(3, "unresolved", "still there"),
                new ReviewResponse.PreviousFindingStatus(9, "unresolved", "no such finding")),
            null);

    var result = builder.build(followUp(List.of(ROUND_ONE), List.of()), round, CI_CLEAR, plan);
    var edited = publishFollowUp(result, first);

    assertEquals(3, result.unresolvedPreviousCount());
    assertEquals(result.unresolvedPreviousCount(), result.openPreviousFindings().size());
    assertOpenCount(edited, 3);
    assertRisk(edited, 1, 1, 1, 0);
  }

  /**
   * ThrillhouseBot-test#146 (Rust), round three: the round re-raised findings still open from
   * earlier rounds, the summary kept the re-raises, dropped the originals, lost every "(open since
   * an earlier review)" tag and said "including 4" beside a larger "Still present".
   */
  @Test
  void threeRoundsKeepEveryCarriedFindingAndItsTagWhenTheRoundReRaisesThem() {
    var first = publishFirstRound();

    var roundTwo = allUnresolved(List.of(NEW_IN_ROUND_TWO), 3);
    var second = builder.build(followUp(List.of(ROUND_ONE), List.of()), roundTwo, CI_CLEAR, plan);
    var secondBody = publishFollowUp(second, first);
    assertRisk(secondBody, 1, 1, 2, 0);
    assertOpenCount(secondBody, 3);

    // Round three reports on round two, re-raises HIGH exactly (re-rated medium) and CRITICAL
    // reworded a line lower. Round one's findings are held by the backstop.
    var exactReRaise =
        new ReviewResponse.Finding(
            "medium",
            "high",
            FILE,
            HIGH.line(),
            HIGH.title(),
            "Reworded, same defect.",
            HIGH.suggestionOld(),
            null);
    var driftedReRaise =
        new ReviewResponse.Finding(
            "critical",
            "high",
            FILE,
            CRITICAL.line() + 1,
            "Query built by concatenating request input",
            "Request input is concatenated into the SQL string.",
            CRITICAL.suggestionOld(),
            null);
    var roundThree = allUnresolved(List.of(exactReRaise, driftedReRaise), 1);
    var third =
        builder.build(
            followUp(List.of(roundTwo, ROUND_ONE), List.of()), roundThree, CI_CLEAR, plan);
    var thirdBody = publishFollowUp(third, secondBody);

    assertEquals(4, third.unresolvedPreviousCount());
    assertEquals(third.unresolvedPreviousCount(), third.openPreviousFindings().size());
    assertOpenCount(thirdBody, 4);
    // Four still open plus two raised this round, one of which is the same finding again: five,
    // the exact re-raise listed once and at the higher of its two ratings.
    assertRisk(thirdBody, 2, 1, 2, 0);
    var keyFindings = section(thirdBody, "### Key Findings");
    assertTrue(
        keyFindings.contains(
            CRITICAL.title() + " (`" + FILE + ":10`) " + PrSummaryGenerator.CARRIED_NOTE),
        thirdBody);
    assertTrue(
        keyFindings.contains(
            "HIGH:** " + HIGH.title() + " (`" + FILE + ":20`) " + PrSummaryGenerator.CARRIED_NOTE),
        thirdBody);
    assertTrue(
        keyFindings.contains(
            NEW_IN_ROUND_TWO.title() + " (`" + FILE + ":40`) " + PrSummaryGenerator.CARRIED_NOTE),
        thirdBody);
    assertTrue(keyFindings.contains(driftedReRaise.title() + " (`" + FILE + ":11`)\n"), thirdBody);
    assertTrue(
        section(thirdBody, "### Things to double-check")
            .contains(DOUBLE_CHECK.title() + " (`" + FILE + ":30`) "),
        thirdBody);
    assertTrue(
        section(thirdBody, "### Things to double-check").contains(PrSummaryGenerator.CARRIED_NOTE),
        thirdBody);
  }

  /** The body of the review the publisher posts for {@code result}; the latest one posted. */
  private String postedReviewBody(ReviewResult result) {
    publisher.postReview(
        "auth", "o", "r", 1, "sha", result, new DiffLineResolver(Map.of(FILE, PATCH)));
    var captor = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
    verify(reviewClient, atLeastOnce())
        .createReview(anyString(), anyString(), eq("o"), eq("r"), eq(1), captor.capture());
    var reviews = captor.getAllValues();
    return reviews.get(reviews.size() - 1).body();
  }

  private static ThrillhouseConfig configWithWriteBudget() {
    var config = mock(ThrillhouseConfig.class);
    var github = mock(ThrillhouseConfig.GitHubConfig.class);
    when(config.github()).thenReturn(github);
    when(github.writeRetryBudget()).thenReturn(Duration.ofMinutes(5));
    return config;
  }

  /** Round one: a first review, which creates the summary comment. Returns the posted body. */
  private String publishFirstRound() {
    return publishFirstRound(ROUND_ONE);
  }

  /** Round one raising {@code findings}. Returns the posted body. */
  private String publishFirstRound(ReviewResponse findings) {
    var ctx = context(List.of(), List.of(), true, false);
    var result = builder.build(ctx, findings, CI_CLEAR, plan);
    assertTrue(publisher.publishSummary("auth", "o", "r", 1, result, false));
    var captor = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(anyString(), anyString(), eq("o"), eq("r"), eq(1), captor.capture());
    return captor.getValue().body();
  }

  /**
   * A follow-up round: the pull request carries the summary {@code current}, which the round must
   * edit in place rather than post again. Returns the edited body.
   */
  private String publishFollowUp(ReviewResult result, String current) {
    var existing = new GitHubCommentClient.IssueComment(55L, current, BOT_USER);
    when(commentClient.listComments(anyString(), anyString(), eq("o"), eq("r"), eq(1)))
        .thenReturn(List.of(existing));
    publisher.publishSummary("auth", "o", "r", 1, result, false);
    var captor = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient, atLeastOnce())
        .updateComment(anyString(), anyString(), eq("o"), eq("r"), eq(55L), captor.capture());
    // Only the first round creates the comment; no later round posts a second one.
    verify(commentClient, times(1))
        .createComment(anyString(), anyString(), eq("o"), eq("r"), anyInt(), any());
    var edits = captor.getAllValues();
    return edits.get(edits.size() - 1).body();
  }

  /** A follow-up context over {@code priorRounds} (newest first) and the PR conversation. */
  private ReviewContextLoader.ReviewContext followUp(
      List<ReviewResponse> priorRounds, List<GitHubCommentClient.IssueComment> conversation) {
    return context(priorRounds, conversation, false, true);
  }

  private ReviewContextLoader.ReviewContext context(
      List<ReviewResponse> priorRounds,
      List<GitHubCommentClient.IssueComment> conversation,
      boolean firstReview,
      boolean hasContext) {
    return context(priorRounds, conversation, firstReview, hasContext, List.of(), "");
  }

  /**
   * A follow-up context whose pull request carries the review threads {@code threads} and whose
   * review call saw {@code reviewedDiff} — the text a maintainer's decline is re-checked against.
   */
  private ReviewContextLoader.ReviewContext followUp(
      List<ReviewResponse> priorRounds,
      List<GitHubReviewClient.PullRequestComment> threads,
      String reviewedDiff) {
    return context(priorRounds, List.of(), false, true, threads, reviewedDiff);
  }

  private ReviewContextLoader.ReviewContext context(
      List<ReviewResponse> priorRounds,
      List<GitHubCommentClient.IssueComment> conversation,
      boolean firstReview,
      boolean hasContext,
      List<GitHubReviewClient.PullRequestComment> threads,
      String reviewedDiff) {
    var jsons = new ArrayList<String>();
    for (var round : priorRounds) {
      jsons.add(mapper.valueToTree(round).toString());
    }
    var files = new ArrayList<FileDiff>();
    PATCHES.forEach((path, patch) -> files.add(new FileDiff(path, "modified", 4, 0, 4, patch)));
    return new ReviewContextLoader.ReviewContext(
        files,
        reviewedDiff,
        "",
        0,
        List.of(),
        jsons,
        priorRounds,
        firstReview,
        hasContext,
        jsons.isEmpty() ? null : jsons.get(0),
        threads,
        "",
        new InstructionsResolver.ResolvedInstructions("", ""),
        PathScopedInstructions.NONE,
        List.of(),
        "",
        "",
        "",
        "",
        files,
        () -> new DiffLineResolver(PATCHES),
        null,
        conversation,
        List.of());
  }

  private static void assertRisk(String body, int critical, int high, int medium, int low) {
    assertTrue(body.contains("| 🔴 Critical | " + critical + " |"), body);
    assertTrue(body.contains("| 🟠 High | " + high + " |"), body);
    assertTrue(body.contains("| 🟡 Medium | " + medium + " |"), body);
    assertTrue(body.contains("| 🔵 Low | " + low + " |"), body);
  }

  /** The body of one {@code ###} section, up to the next heading or the footer rule. */
  private static String section(String body, String heading) {
    var start = body.indexOf(heading);
    assertTrue(start >= 0, "missing " + heading + " in:\n" + body);
    var from = start + heading.length();
    var next = body.indexOf("\n### ", from);
    var end = next < 0 ? body.indexOf("\n---\n", from) : next;
    return body.substring(from, end < 0 ? body.length() : end);
  }
}
