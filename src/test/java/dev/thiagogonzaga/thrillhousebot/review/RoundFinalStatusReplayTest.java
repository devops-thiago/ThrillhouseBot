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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Replays follow-up rounds in which the code, not the model, settles a previous finding (#1409), as
 * a large private PR's rounds 5 to 8 did on v0.9.2 (sessions 10483, 10487, 10490 and 10501; their
 * stored responses are not available, so the rounds are rebuilt from the issue's account of them).
 * Each round runs the status screen, the open-thread guard and the verdict over the rounds before
 * it, then stores its response as the orchestrator does ({@link ReviewResult#recordedResponse}).
 */
class RoundFinalStatusReplayTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final BotIdentity BOT = BotIdentity.from(List.of("thrillhousebot[bot]"));
  private static final GitHubReviewClient.ReviewResponse.User BOT_USER =
      new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]");
  private static final GitHubReviewClient.ReviewResponse.User MAINTAINER =
      new GitHubReviewClient.ReviewResponse.User("maintainer");

  private static final String FILE = "src/main/java/app/Ledger.java";

  private static final CiStatusEvaluator.CiEvaluation CI_CLEAR =
      new CiStatusEvaluator.CiEvaluation(List.of(), false);

  private static final DiffBudgetPlanner.BudgetPlan PLAN =
      new DiffBudgetPlanner.BudgetPlan(
          List.of(), List.of(), List.of(), true, null, null, null, null);

  /** Every finding's flagged line. */
  private static final String PATCH =
      """
      @@ -10,0 +10,6 @@
      +long credit = account.balance() - amount;
      +String note = "reversal of " + id;
      +int retries = config.retries();
      +cache.put(key, value);
      +log.info("posted " + entry);
      +return ledger.total();\
      """;

  /** {@link #PATCH} once the first finding's line was deleted. */
  private static final String PATCH_WITHOUT_CREDIT =
      """
      @@ -10,0 +10,5 @@
      +String note = "reversal of " + id;
      +int retries = config.retries();
      +cache.put(key, value);
      +log.info("posted " + entry);
      +return ledger.total();\
      """;

  private static ReviewResponse.Finding finding(int line, String title, String anchor) {
    return new ReviewResponse.Finding(
        "high", "high", FILE, line, title, title + ", which callers rely on.", anchor, null);
  }

  /** The finding the code settles while the model still reports it unresolved. */
  private static final ReviewResponse.Finding SETTLED =
      finding(10, "Credit subtracts the amount twice", "long credit = account.balance() - amount;");

  private static final ReviewResponse.Finding KEPT =
      finding(
          11, "Reversal note concatenates an untrusted id", "String note = \"reversal of \" + id;");

  private static final ReviewResponse.Finding ROUND_TWO =
      finding(12, "Retries read without a lower bound", "int retries = config.retries();");

  private static final ReviewResponse.Finding ROUND_THREE =
      finding(13, "Cache entry is never evicted", "cache.put(key, value);");

  private static final ReviewResponse.Finding ROUND_FOUR =
      finding(14, "Log line concatenates the whole entry", "log.info(\"posted \" + entry);");

  private static final GitHubCommentClient.IssueComment CLEAR =
      new GitHubCommentClient.IssueComment(
          900L,
          "@thrillhousebot resolved `" + FILE + ":10` — " + SETTLED.title(),
          MAINTAINER,
          "MEMBER");

  private static final ReviewResponse ROUND_ONE =
      new ReviewResponse(List.of(SETTLED, KEPT), List.of(), null);

  private final VerdictBuilder builder =
      new VerdictBuilder(
          new PrSummaryGenerator(false),
          new FollowUpAnalyzer(MAPPER),
          BOT,
          BlockingStrictness.BALANCED);

  private record Round(ReviewResult result, ReviewResponse stored) {}

  /** What a round sees besides its prior rounds: its head's patch and the PR conversation. */
  private record Head(String patch, List<GitHubCommentClient.IssueComment> conversation) {}

  private static final Head CLEARED = new Head(PATCH, List.of(CLEAR));
  private static final Head QUIET = new Head(PATCH, List.of());
  private static final Head DELETED = new Head(PATCH_WITHOUT_CREDIT, List.of());

  private static String keyOf(ReviewResponse.Finding finding) {
    return SuggestionFormatter.findingKey(finding.file(), finding.line(), finding.title());
  }

  private static List<GitHubReviewClient.PullRequestComment> threads(
      List<ReviewResponse> roundsNewestFirst) {
    var comments = new ArrayList<GitHubReviewClient.PullRequestComment>();
    for (var round : roundsNewestFirst) {
      for (var i = 0; i < round.findings().size(); i++) {
        var finding = round.findings().get(i);
        comments.add(
            new GitHubReviewClient.PullRequestComment(
                1000L + comments.size(),
                null,
                finding.file(),
                "**"
                    + finding.title()
                    + "**\n\n"
                    + SuggestionFormatter.findingMarker(
                        i + 1, finding.file(), finding.line(), finding.title()),
                BOT_USER,
                null));
      }
    }
    return comments;
  }

  private static ReviewContextLoader.ReviewContext context(
      List<ReviewResponse> priorRounds, Head head) {
    var jsons = priorRounds.stream().map(round -> MAPPER.valueToTree(round).toString()).toList();
    var patches = Map.of(FILE, head.patch());
    var files = List.of(new FileDiff(FILE, "modified", 6, 0, 6, head.patch()));
    return new ReviewContextLoader.ReviewContext(
            files,
            "",
            "",
            0,
            List.of(),
            jsons,
            priorRounds,
            false,
            true,
            jsons.get(0),
            threads(priorRounds),
            "",
            new InstructionsResolver.ResolvedInstructions("", ""),
            PathScopedInstructions.NONE,
            List.of(),
            "",
            "",
            "",
            "",
            files,
            () -> new DiffLineResolver(patches),
            null,
            head.conversation(),
            List.of())
        .withVerifiedWriters(KnownWriters.ALL);
  }

  /** The model's answer: {@code unresolved} for every finding the prompt lists, keyed. */
  private static List<ReviewResponse.PreviousFindingStatus> unresolvedForEvery(
      PriorListing listing) {
    var statuses = new ArrayList<ReviewResponse.PreviousFindingStatus>();
    for (var i = 0; i < listing.findings().size(); i++) {
      statuses.add(
          new ReviewResponse.PreviousFindingStatus(
              i + 1, "unresolved", "still there", keyOf(listing.findings().get(i))));
    }
    return statuses;
  }

  private Round replay(
      List<ReviewResponse> priorRounds, Head head, List<ReviewResponse.Finding> raised) {
    var ctx = context(priorRounds, head);
    var lineResolver = ctx.lineResolver();
    var listing = FollowUpAnalyzer.priorListing(ctx, () -> lineResolver, BOT);
    var raw = new ReviewResponse(raised, unresolvedForEvery(listing), null);
    var screened = FollowUpAnalyzer.screenModelStatuses(raw, ctx, lineResolver, BOT);
    var guarded =
        FollowUpAnalyzer.withoutOpenThreadDuplicates(
            screened,
            priorRounds,
            ctx.inlineComments(),
            lineResolver,
            VerdictBuilder.renameTargets(ctx.files()),
            BOT);
    var result = builder.build(ctx, guarded, CI_CLEAR, PLAN);
    return new Round(result, result.recordedResponse(guarded));
  }

  /**
   * Key {@code 0ed70eff}: the model answered {@code unresolved} on round 6, the code resolved it
   * (here a maintainer's clear), and rounds 7 and 8 closed and named it again.
   */
  @Test
  void aFindingTheCodeResolvedIsNotClosedOrCountedAgainInLaterRounds() {
    var r2 = replay(List.of(ROUND_ONE), CLEARED, List.of(ROUND_TWO));
    assertEquals(1, r2.result().resolvedPreviousCount(), "round 2 applies the clear");
    assertEquals("resolved", r2.stored().previousFindingsStatus().get(0).status());
    assertEquals(keyOf(SETTLED), r2.stored().previousFindingsStatus().get(0).key());

    var r3 = replay(List.of(r2.stored(), ROUND_ONE), CLEARED, List.of(ROUND_THREE));
    assertEquals(0, r3.result().resolvedPreviousCount());
    assertTrue(r3.stored().closedEarlierRound().isEmpty());

    var r4 = replay(List.of(r3.stored(), r2.stored(), ROUND_ONE), CLEARED, List.of(ROUND_FOUR));
    assertEquals(0, r4.result().resolvedPreviousCount());
    assertTrue(r4.stored().closedEarlierRound().isEmpty());
  }

  /** Keys {@code c0a86304} and {@code aa71f04e}: superseded on round 6, again on 7 and 8. */
  @Test
  void aFindingTheCodeSupersededIsNotSupersededAgainInLaterRounds() {
    var r2 = replay(List.of(ROUND_ONE), DELETED, List.of(ROUND_TWO));
    assertEquals(1, r2.result().supersededPreviousCount(), "its line left the diff");
    assertEquals("superseded", r2.stored().previousFindingsStatus().get(0).status());

    var r3 = replay(List.of(r2.stored(), ROUND_ONE), DELETED, List.of(ROUND_THREE));
    assertEquals(0, r3.result().supersededPreviousCount());
    assertTrue(r3.stored().closedEarlierRound().isEmpty());

    var r4 = replay(List.of(r3.stored(), r2.stored(), ROUND_ONE), DELETED, List.of(ROUND_FOUR));
    assertEquals(0, r4.result().supersededPreviousCount());
  }

  /** A round the code reopened stays open for the next: the stored status is the final one. */
  @Test
  void theStoredStatusIsTheRoundsFinalStatusNotTheModels() {
    var answer =
        new ReviewResponse(
            List.of(ROUND_TWO),
            List.of(
                new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still", keyOf(SETTLED)),
                new ReviewResponse.PreviousFindingStatus(2, "unresolved", "still", keyOf(KEPT))),
            null);
    var ctx = context(List.of(ROUND_ONE), CLEARED);
    var result = builder.build(ctx, answer, CI_CLEAR, PLAN);

    var stored = result.recordedResponse(answer);

    assertEquals(
        List.of("resolved", "unresolved"),
        stored.previousFindingsStatus().stream()
            .map(ReviewResponse.PreviousFindingStatus::status)
            .toList());
    assertEquals(answer.findings(), stored.findings());
  }

  /**
   * A status keeps the first key the model echoed under its id; an entry without a key, or a
   * repeated id, does not replace it.
   */
  @Test
  void theStoredStatusKeepsTheFirstKeyEchoedUnderItsId() {
    var answer =
        new ReviewResponse(
            List.of(ROUND_TWO),
            List.of(
                new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still", null),
                new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still", keyOf(SETTLED)),
                new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still", keyOf(KEPT)),
                new ReviewResponse.PreviousFindingStatus(2, "unresolved", "still", null)),
            null);
    var ctx = context(List.of(ROUND_ONE), CLEARED);
    var result = builder.build(ctx, answer, CI_CLEAR, PLAN);

    var stored = result.recordedResponse(answer);

    assertEquals(
        Arrays.asList(keyOf(SETTLED), keyOf(SETTLED), keyOf(SETTLED), null),
        stored.previousFindingsStatus().stream()
            .map(ReviewResponse.PreviousFindingStatus::key)
            .toList());
  }
}
