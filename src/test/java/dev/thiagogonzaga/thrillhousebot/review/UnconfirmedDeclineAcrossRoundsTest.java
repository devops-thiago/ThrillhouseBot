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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Follow-up rounds on a pull request whose only open finding was answered on its thread by someone
 * who cannot decline it, with the model reading that reply as a decline. Each round runs the
 * verdict over the rounds before it and stores its response as the orchestrator does ({@link
 * ReviewResult#recordedResponse}), so what one round decides is what the next one replays.
 */
class UnconfirmedDeclineAcrossRoundsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final BotIdentity BOT = BotIdentity.from(List.of("thrillhousebot[bot]"));
  private static final String FILE = "src/main/java/app/Ledger.java";
  private static final String PATCH =
      """
      @@ -10,0 +10,1 @@
      +long credit = account.balance() - amount;\
      """;

  private static final ReviewResponse.Finding OPEN =
      new ReviewResponse.Finding(
          "high",
          "high",
          FILE,
          10,
          "Credit subtracts the amount twice",
          "The amount is already deducted by the caller.",
          "long credit = account.balance() - amount;",
          null);

  private static final ReviewResponse ROUND_ONE =
      new ReviewResponse(List.of(OPEN), List.of(), null);

  /** The only login GitHub confirmed write access for in these reviews. */
  private static final VerifiedWriters WRITERS = VerifiedWriters.of(List.of("maintainer"));

  private static final CiStatusEvaluator.CiEvaluation CI_CLEAR =
      new CiStatusEvaluator.CiEvaluation(List.of(), false);

  private static final DiffBudgetPlanner.BudgetPlan PLAN =
      new DiffBudgetPlanner.BudgetPlan(
          List.of(), List.of(), List.of(), true, null, null, null, null);

  private final VerdictBuilder builder =
      new VerdictBuilder(
          new PrSummaryGenerator(false),
          new FollowUpAnalyzer(MAPPER),
          BOT,
          BlockingStrictness.BALANCED);

  private record Round(ReviewResult result, ReviewResponse stored) {}

  private static GitHubReviewClient.PullRequestComment root() {
    return new GitHubReviewClient.PullRequestComment(
        1000L,
        null,
        FILE,
        "**"
            + OPEN.title()
            + "**\n\n"
            + SuggestionFormatter.findingMarker(1, OPEN.file(), OPEN.line(), OPEN.title()),
        new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]"),
        null);
  }

  private static GitHubReviewClient.PullRequestComment reply(
      long id, String login, String association, String body) {
    return new GitHubReviewClient.PullRequestComment(
        id, 1000L, FILE, body, new GitHubReviewClient.ReviewResponse.User(login), association);
  }

  private static ReviewContextLoader.ReviewContext context(
      List<ReviewResponse> priorRounds, List<GitHubReviewClient.PullRequestComment> replies) {
    var jsons = priorRounds.stream().map(round -> MAPPER.valueToTree(round).toString()).toList();
    return context(jsons, priorRounds, replies);
  }

  private static ReviewContextLoader.ReviewContext context(
      List<String> jsons,
      List<ReviewResponse> priorRounds,
      List<GitHubReviewClient.PullRequestComment> replies) {
    var files = List.of(new FileDiff(FILE, "modified", 1, 0, 1, PATCH));
    var comments = new ArrayList<GitHubReviewClient.PullRequestComment>();
    comments.add(root());
    comments.addAll(replies);
    return new ReviewContextLoader.ReviewContext(
            files,
            "",
            "",
            0,
            List.of(),
            jsons,
            priorRounds,
            false,
            !jsons.isEmpty(),
            jsons.isEmpty() ? null : jsons.get(0),
            comments,
            "",
            new InstructionsResolver.ResolvedInstructions("", ""),
            PathScopedInstructions.NONE,
            List.of(),
            "",
            "",
            "",
            "",
            files,
            () -> new DiffLineResolver(Map.of(FILE, PATCH)),
            null,
            List.of(),
            List.of())
        .withVerifiedWriters(WRITERS);
  }

  /** A round that raises nothing new and reports the open finding declined, as the model did. */
  private Round roundReportingTheFindingDeclined(
      List<ReviewResponse> priorRounds, List<GitHubReviewClient.PullRequestComment> replies) {
    var ctx = context(priorRounds, replies);
    var answer =
        new ReviewResponse(
            List.of(),
            List.of(
                new ReviewResponse.PreviousFindingStatus(
                    1,
                    "justified",
                    "the author says this is by design",
                    SuggestionFormatter.findingKey(OPEN.file(), OPEN.line(), OPEN.title()))),
            null);
    var screened = FollowUpAnalyzer.screenModelStatuses(answer, ctx, ctx.lineResolver(), BOT);
    var result = builder.build(ctx, screened, CI_CLEAR, PLAN);
    return new Round(result, result.recordedResponse(screened));
  }

  private static long justified(ReviewResult result) {
    return result.previousStatuses().stream()
        .filter(status -> "justified".equals(status.status()))
        .count();
  }

  @ParameterizedTest(name = "a reply from a {1} ({0})")
  @CsvSource({
    "pr-author, CONTRIBUTOR",
    "first-timer, FIRST_TIME_CONTRIBUTOR",
    "read-only-member, MEMBER",
    "triage-collaborator, COLLABORATOR"
  })
  void aReplyFromSomeoneWithoutConfirmedWriteAccessNeverLetsTheBotApprove(
      String login, String association) {
    var replies =
        List.of(reply(1001L, login, association, "Won't fix. This is intentional, by design."));

    var round = roundReportingTheFindingDeclined(List.of(ROUND_ONE), replies);

    assertNotEquals(ReviewState.APPROVE, round.result().reviewState());
    assertEquals(1, round.result().unresolvedPreviousCount());
    assertEquals(0, justified(round.result()));
    var stored = round.stored().previousFindingsStatus();
    assertEquals(1, stored.size());
    assertEquals("unresolved", stored.get(0).status(), "the next round replays it as open");
    assertTrue(stored.get(0).note().startsWith(RebuttalContradiction.UNCONFIRMED_LEAD_IN));
  }

  @Test
  void theFindingStaysOpenRoundAfterRoundUntilSomeoneWithWriteAccessDeclinesIt() {
    var authorReply =
        reply(1001L, "pr-author", "CONTRIBUTOR", "Won't fix. This is intentional, by design.");

    var r2 = roundReportingTheFindingDeclined(List.of(ROUND_ONE), List.of(authorReply));
    assertNotEquals(ReviewState.APPROVE, r2.result().reviewState());

    var r3 =
        roundReportingTheFindingDeclined(List.of(r2.stored(), ROUND_ONE), List.of(authorReply));
    assertNotEquals(ReviewState.APPROVE, r3.result().reviewState(), "asking again changes nothing");
    assertEquals(1, r3.result().unresolvedPreviousCount());

    var maintainerReply =
        reply(1002L, "maintainer", "MEMBER", "Agreed, accepted for this release.");
    var r4 =
        roundReportingTheFindingDeclined(
            List.of(r3.stored(), r2.stored(), ROUND_ONE), List.of(authorReply, maintainerReply));
    assertEquals(ReviewState.APPROVE, r4.result().reviewState());
    assertEquals(0, r4.result().unresolvedPreviousCount());
    assertEquals(1, justified(r4.result()));
  }

  /** A round that raises nothing new and reports one status, screened and judged as in a run. */
  private ReviewResult roundReporting(
      ReviewContextLoader.ReviewContext ctx, List<ReviewResponse.PreviousFindingStatus> statuses) {
    var answer = new ReviewResponse(List.of(), statuses, null);
    var screened = FollowUpAnalyzer.screenModelStatuses(answer, ctx, ctx.lineResolver(), BOT);
    return builder.build(ctx, screened, CI_CLEAR, PLAN);
  }

  private static List<ReviewResponse.PreviousFindingStatus> status(int id, String status) {
    return List.of(
        new ReviewResponse.PreviousFindingStatus(id, status, "the author says it is by design"));
  }

  @ParameterizedTest(name = "justified under number {0}")
  @CsvSource({"0", "2", "7"})
  void aDeclineOnANumberOutsideThePreviousRoundClosesAndCountsNothing(int id) {
    var replies =
        List.of(reply(1001L, "pr-author", "CONTRIBUTOR", "Won't fix. This is intentional."));
    var ctx = context(List.of(ROUND_ONE), replies);

    var result = roundReporting(ctx, status(id, "justified"));

    assertNotEquals(ReviewState.APPROVE, result.reviewState());
    assertEquals(1, result.unresolvedPreviousCount(), "the finding it did not name stays held");
    assertEquals(0, justified(result));
  }

  @Test
  void aDeclineReportedWhenNoStoredRoundCanBeReadNamesNothingAndChangesNothing() {
    // Every stored response is unreadable, so there is no previous round to number: the bot's
    // thread is still open and someone without write access answered it.
    var unreadable = new FollowUpAnalyzer(MAPPER).parsePreviousResponses(List.of("{not json"));
    var replies =
        List.of(reply(1001L, "pr-author", "CONTRIBUTOR", "Won't fix. This is intentional."));
    var ctx = context(List.of("{not json"), unreadable, replies);
    assertTrue(ctx.previousFindingsList().isEmpty());

    var declined = roundReporting(ctx, status(1, "justified"));
    var stillOpen = roundReporting(ctx, status(1, "unresolved"));
    var silent = roundReporting(ctx, List.of());

    // The status screen drops a status that names no listed finding before the verdict reads it,
    // so a "justified" here is the same as saying nothing: it is not counted and it closes nothing
    // an "unresolved" would have held.
    assertTrue(declined.previousStatuses().isEmpty(), declined.previousStatuses().toString());
    assertEquals(0, justified(declined));
    assertEquals(ReviewState.COMMENT, declined.reviewState(), "nothing was confirmed closed");
    assertEquals(silent.reviewState(), declined.reviewState());
    assertEquals(stillOpen.reviewState(), declined.reviewState());
    assertEquals(stillOpen.previousStatuses(), declined.previousStatuses());
  }

  /** The stored rounds as the loader reads them, each JSON parsed once, newest first. */
  private static ReviewContextLoader.ReviewContext storedContext(List<String> jsons) {
    return context(jsons, new FollowUpAnalyzer(MAPPER).parsePreviousResponses(jsons), List.of());
  }

  private static String json(ReviewResponse round) {
    return MAPPER.valueToTree(round).toString();
  }

  @Test
  void aCleanRoundIsHeldAtCommentWhenNoStoredRoundCanBeRead() {
    var ctx = storedContext(List.of("{not json", " "));
    assertTrue(ctx.priorRoundsUnreadable());

    var result = roundReporting(ctx, List.of());

    assertEquals(ReviewState.COMMENT, result.reviewState());
    assertTrue(result.priorRoundsUnreadable());
    assertEquals(0, result.unresolvedPreviousCount());
    var summary = result.summaryMarkdown();
    assertTrue(summary.startsWith(ReviewResult.PRIOR_ROUNDS_UNREADABLE_NOTICE), summary);
    assertTrue(summary.contains("No new issues found in this round."), summary);
    assertFalse(summary.contains(PrSummaryGenerator.ZERO_ISSUES_MESSAGE), summary);
    assertEquals(
        "No new issues found. " + ReviewResult.PRIOR_ROUNDS_UNREADABLE_BRIEF,
        VerdictBuilder.checkSummaryForResult(result));
  }

  @Test
  void aStatusReportedWhenNoStoredRoundCanBeReadDoesNotLiftTheHold() {
    var ctx = storedContext(List.of("{not json"));

    assertEquals(ReviewState.COMMENT, roundReporting(ctx, status(1, "resolved")).reviewState());
    assertEquals(ReviewState.COMMENT, roundReporting(ctx, status(1, "justified")).reviewState());
  }

  @Test
  void aCleanFirstReviewStillApproves() {
    var ctx = storedContext(List.of());
    assertFalse(ctx.priorRoundsUnreadable());

    var result = roundReporting(ctx, List.of());

    assertEquals(ReviewState.APPROVE, result.reviewState());
    assertFalse(result.priorRoundsUnreadable());
    assertFalse(
        result.summaryMarkdown().contains(ReviewResult.PRIOR_ROUNDS_UNREADABLE_LEAD_IN),
        result.summaryMarkdown());
  }

  @Test
  void aCleanRoundAfterAReadableRoundThatRaisedNothingStillApproves() {
    var ctx = storedContext(List.of(json(new ReviewResponse(List.of(), List.of(), null))));
    assertFalse(ctx.priorRoundsUnreadable());

    var result = roundReporting(ctx, List.of());

    assertEquals(ReviewState.APPROVE, result.reviewState());
    assertFalse(result.priorRoundsUnreadable());
  }

  @Test
  void oneReadableRoundBesideAnUnreadableOneIsJudgedOnTheReadableRoundAlone() {
    // The readable round's open finding is held by the backstop, as before.
    var open = storedContext(List.of("{not json", json(ROUND_ONE)));
    assertFalse(open.priorRoundsUnreadable());
    var held = roundReporting(open, List.of());
    assertNotEquals(ReviewState.APPROVE, held.reviewState());
    assertEquals(1, held.unresolvedPreviousCount());
    assertFalse(held.priorRoundsUnreadable());

    // A readable round that raised nothing leaves nothing to hold, as before.
    var clean =
        storedContext(List.of(json(new ReviewResponse(List.of(), List.of(), null)), "{not json"));
    var approved = roundReporting(clean, List.of());
    assertEquals(ReviewState.APPROVE, approved.reviewState());
    assertFalse(approved.priorRoundsUnreadable());
  }

  @Test
  void aRoundHeldOnCiAndOnUnreadableRoundsIsNotLeftForCiAloneToApprove() {
    var ctx = storedContext(List.of("{not json"));
    var pending =
        new CiStatusEvaluator.CiEvaluation(
            List.of(new ReviewResult.CiCheck("build", "check-run", "pending", null)), false);
    var screened =
        FollowUpAnalyzer.screenModelStatuses(
            new ReviewResponse(List.of(), List.of(), null), ctx, ctx.lineResolver(), BOT);

    var result = builder.build(ctx, screened, pending, PLAN);

    assertEquals(ReviewState.COMMENT, result.reviewState());
    assertTrue(result.ciHoldsApproval());
    assertTrue(result.priorRoundsUnreadable());
    // The CI revisit posts the approval a round held on CI alone earned; this one earned none.
    assertFalse(result.heldOnCiOnly());
    var checkSummary = VerdictBuilder.checkSummaryForResult(result);
    assertTrue(checkSummary.startsWith("No new issues found, but 1 "), checkSummary);
    assertTrue(checkSummary.endsWith(ReviewResult.PRIOR_ROUNDS_UNREADABLE_BRIEF), checkSummary);
    assertTrue(
        result.summaryMarkdown().startsWith(ReviewResult.PRIOR_ROUNDS_UNREADABLE_NOTICE),
        result.summaryMarkdown());
  }

  @Test
  void aPartialReviewHeldOnUnreadableRoundsNamesBothOnTheCheckRun() {
    var partial =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            null,
            ReviewState.COMMENT,
            false,
            "",
            List.of(),
            List.of(),
            2,
            false,
            true,
            null,
            0,
            List.of(),
            true);

    var checkSummary = VerdictBuilder.checkSummaryForResult(partial);

    assertTrue(
        checkSummary.contains("this is a partial review, so approval is held."), checkSummary);
    assertTrue(checkSummary.endsWith(ReviewResult.PRIOR_ROUNDS_UNREADABLE_BRIEF), checkSummary);
  }
}
