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
      "@@ -10,0 +10,4 @@\n"
          + "+String sql = \"SELECT * FROM t WHERE id = \" + input;\n"
          + "+int total = count * price;\n"
          + "+log(user.name());\n"
          + "+String unused = \"\";";

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

  private final ObjectMapper mapper = new ObjectMapper();

  private final GitHubCommentClient commentClient = mock(GitHubCommentClient.class);

  private final VerdictBuilder builder =
      new VerdictBuilder(
          new PrSummaryGenerator(false),
          new FollowUpAnalyzer(mapper),
          BOT,
          BlockingStrictness.BALANCED);

  private final ReviewPublisher publisher =
      new ReviewPublisher(
          mock(GitHubReviewClient.class),
          commentClient,
          mock(ReviewThreadService.class),
          mock(SuggestionFormatter.class),
          mock(FollowUpAnalyzer.class),
          mock(PrLabeler.class),
          mock(ThrillhouseConfig.class),
          BOT);

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

  /** Round one: a first review, which creates the summary comment. Returns the posted body. */
  private String publishFirstRound() {
    var ctx = context(List.of(), List.of(), true, false);
    var result = builder.build(ctx, ROUND_ONE, CI_CLEAR, plan);
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
    var jsons = new ArrayList<String>();
    for (var round : priorRounds) {
      jsons.add(mapper.valueToTree(round).toString());
    }
    var file = new FileDiff(FILE, "modified", 4, 0, 4, PATCH);
    return new ReviewContextLoader.ReviewContext(
        List.of(file),
        "",
        "",
        0,
        List.of(),
        jsons,
        priorRounds,
        firstReview,
        hasContext,
        jsons.isEmpty() ? null : jsons.get(0),
        List.of(),
        "",
        new InstructionsResolver.ResolvedInstructions("", ""),
        PathScopedInstructions.NONE,
        List.of(),
        "",
        "",
        "",
        "",
        List.of(file),
        () -> new DiffLineResolver(Map.of(FILE, PATCH)),
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
