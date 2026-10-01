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
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * #982, the sequence ThrillhouseBot#980 went through: the scan raises two secret findings, a
 * maintainer declines both on their threads and the next round accepts the declines, and the round
 * after that, on the same head, raised both again as new HIGH threads and requested changes. Each
 * round runs the real {@link SecurityScan} merge from the review's context, then {@link
 * VerdictBuilder} and {@link FollowUpAnalyzer}, and is persisted and read back as JSON the way the
 * next round reads it.
 */
class DeclinedScanFindingAcrossRoundsTest {

  private static final String SCANNER = "src/main/java/app/SecretScanner.java";
  private static final String FIXTURE = "src/test/java/app/LearningTextTest.java";

  private static final BotIdentity BOT = BotIdentity.from(List.of("thrillhousebot[bot]"));

  private static final CiStatusEvaluator.CiEvaluation CI_CLEAR =
      new CiStatusEvaluator.CiEvaluation(List.of(), false);

  private final FakeCredentials fake = new FakeCredentials(982);

  private final String tokenEnd = fake.genericSecret(32);

  private final String clientSecret = fake.genericSecret(40);

  private final ObjectMapper mapper = new ObjectMapper();

  private final SecurityScan scan = new SecurityScan(true, false, 3.5, List.of());

  private final VerdictBuilder builder =
      new VerdictBuilder(
          new PrSummaryGenerator(false),
          new FollowUpAnalyzer(mapper),
          BOT,
          BlockingStrictness.BALANCED);

  private final DiffBudgetPlanner.BudgetPlan plan =
      new DiffBudgetPlanner.BudgetPlan(
          List.of(), List.of(), List.of(), true, null, null, null, null);

  private List<FileDiff> head(String secret) {
    return List.of(
        added(
            SCANNER,
            "final class SecretScanner {",
            "  private static final String TOKEN_END = \"" + tokenEnd + "\";",
            "}"),
        added(
            FIXTURE,
            "class LearningTextTest {",
            "  String body = \"client_secret\";",
            "  String client_secret = \"" + secret + "\";",
            "}"));
  }

  @Test
  void aDeclineTheBotAcceptedIsNotRaisedAgainOnTheSameHead() throws Exception {
    var files = head(clientSecret);
    var roundOne = roundOne(files);
    var threads = declinedThreads(roundOne);

    // Round two: the model reads the two replies and reports both justified; the scan, still
    // matching both lines, keeps the maintainer's verdict.
    var reported =
        new ReviewResponse(
            List.of(),
            List.of(
                new ReviewResponse.PreviousFindingStatus(1, "justified", "a regex, not a key"),
                new ReviewResponse.PreviousFindingStatus(2, "justified", "a test fixture")),
            null);
    var roundTwoCtx = context(files, List.of(roundOne), threads);
    var roundTwo = persisted(scan.merge(reported, roundTwoCtx, BOT));
    assertTrue(roundTwo.findings().isEmpty());
    var roundTwoResult = builder.build(roundTwoCtx, roundTwo, CI_CLEAR, plan);
    assertEquals(
        List.of("justified", "justified"),
        roundTwoResult.previousStatuses().stream()
            .map(ReviewResult.PreviousFindingStatus::status)
            .toList());
    assertEquals(ReviewState.APPROVE, roundTwoResult.reviewState());

    // Round three, `/review` on the same head: the model has nothing to add.
    var roundThreeCtx = context(files, List.of(roundTwo, roundOne), threads);
    var roundThree =
        persisted(scan.merge(new ReviewResponse(List.of(), List.of(), null), roundThreeCtx, BOT));
    assertTrue(roundThree.findings().isEmpty(), "raised again: " + roundThree.findings());
    var result = builder.build(roundThreeCtx, roundThree, CI_CLEAR, plan);
    assertEquals(ReviewState.APPROVE, result.reviewState());
    assertTrue(result.openPreviousFindings().isEmpty(), result.openPreviousFindings().toString());

    // And a fourth round still raises nothing: the decline is remembered, not spent.
    var roundFourCtx = context(files, List.of(roundThree, roundTwo, roundOne), threads);
    assertTrue(
        scan.merge(new ReviewResponse(List.of(), List.of(), null), roundFourCtx, BOT)
            .findings()
            .isEmpty());
  }

  @Test
  void theVerdictFollowsTheFindingsThatRemain() throws Exception {
    var files = head(clientSecret);
    var roundOne = roundOne(files);
    var threads = declinedThreads(roundOne);
    var roundTwo =
        persisted(
            scan.merge(
                new ReviewResponse(
                    List.of(),
                    List.of(
                        new ReviewResponse.PreviousFindingStatus(1, "justified", "a regex"),
                        new ReviewResponse.PreviousFindingStatus(2, "justified", "a fixture")),
                    null),
                context(files, List.of(roundOne), threads),
                BOT));

    // Round three raises one model finding of its own: that finding alone decides the verdict.
    var own =
        new ReviewResponse.Finding(
            "high",
            "high",
            SCANNER,
            1,
            "Scanner class is never registered with the pipeline",
            "The new scanner is not wired into the review pipeline, so it never runs.",
            "final class SecretScanner {",
            null);
    var ctx = context(files, List.of(roundTwo, roundOne), threads);
    var roundThree = scan.merge(new ReviewResponse(List.of(own), List.of(), null), ctx, BOT);

    assertEquals(List.of(own), roundThree.findings());
    var result = builder.build(ctx, roundThree, CI_CLEAR, plan);
    assertEquals(ReviewState.REQUEST_CHANGES, result.reviewState());
    assertEquals(1, result.totalFindings());
    assertTrue(result.openPreviousFindings().isEmpty(), result.openPreviousFindings().toString());
  }

  @Test
  void aNewValueOnTheDeclinedLineIsRaisedAgain() throws Exception {
    var roundOne = roundOne(head(clientSecret));
    var threads = declinedThreads(roundOne);
    var roundTwo =
        persisted(
            scan.merge(
                new ReviewResponse(
                    List.of(),
                    List.of(
                        new ReviewResponse.PreviousFindingStatus(1, "justified", "a regex"),
                        new ReviewResponse.PreviousFindingStatus(2, "justified", "a fixture")),
                    null),
                context(head(clientSecret), List.of(roundOne), threads),
                BOT));

    // A push puts another value on the fixture's line, with the same prefix and length, so the
    // finding's title — all a secret finding shows of its value — is the same as the declined one.
    var swapped = clientSecret.substring(0, 4) + fake.genericSecret(36);
    var files = head(swapped);
    var ctx = context(files, List.of(roundTwo, roundOne), threads);
    var roundThree = scan.merge(new ReviewResponse(List.of(), List.of(), null), ctx, BOT);

    assertEquals(1, roundThree.findings().size(), roundThree.findings().toString());
    var raised = roundThree.findings().get(0);
    assertEquals(FIXTURE, raised.file());
    assertEquals(roundOne.findings().get(1).title(), raised.title());
    var result = builder.build(ctx, roundThree, CI_CLEAR, plan);
    assertEquals(ReviewState.REQUEST_CHANGES, result.reviewState());
  }

  @Test
  void aFindingRaisedAgainBeforeTheFixIsClosedWithTheDeclineItRepeats() throws Exception {
    // What ThrillhouseBot#980 holds now: round three posted both findings again on new threads.
    // The next round reports those justified under the standing decline instead of holding them.
    var files = head(clientSecret);
    var roundOne = roundOne(files);
    var threads = declinedThreads(roundOne);
    var roundTwo =
        persisted(
            new ReviewResponse(
                List.of(),
                List.of(
                    new ReviewResponse.PreviousFindingStatus(1, "justified", "a regex"),
                    new ReviewResponse.PreviousFindingStatus(2, "justified", "a fixture")),
                null));
    var roundThree = persisted(new ReviewResponse(roundOne.findings(), List.of(), null));

    var ctx = context(files, List.of(roundThree, roundTwo, roundOne), threads);
    var roundFour =
        scan.merge(
            new ReviewResponse(
                List.of(),
                List.of(new ReviewResponse.PreviousFindingStatus(1, "unresolved", "still there")),
                null),
            ctx,
            BOT);

    assertTrue(roundFour.findings().isEmpty());
    assertEquals(
        List.of(
            new ReviewResponse.PreviousFindingStatus(
                1, "justified", SecurityScan.DECLINE_STANDS_NOTE),
            new ReviewResponse.PreviousFindingStatus(
                2, "justified", SecurityScan.DECLINE_STANDS_NOTE)),
        roundFour.previousFindingsStatus());
    var result = builder.build(ctx, roundFour, CI_CLEAR, plan);
    assertEquals(ReviewState.APPROVE, result.reviewState());
  }

  /** Round one: the scan raises both findings. Returns the response as persisted. */
  private ReviewResponse roundOne(List<FileDiff> files) throws Exception {
    var raised =
        persisted(
            scan.merge(
                new ReviewResponse(List.of(), List.of(), null),
                scan.scan(files),
                List.of(),
                Set.of()));
    assertEquals(
        List.of(SCANNER, FIXTURE),
        raised.findings().stream().map(ReviewResponse.Finding::file).toList());
    assertEquals("high", raised.findings().get(0).risk());
    return raised;
  }

  /** Each round-one finding's thread, with the maintainer's decline as its one reply. */
  private static List<GitHubReviewClient.PullRequestComment> declinedThreads(
      ReviewResponse roundOne) {
    var threads = new ArrayList<GitHubReviewClient.PullRequestComment>();
    for (int i = 0; i < roundOne.findings().size(); i++) {
      var posted = roundOne.findings().get(i);
      long root = 100L + i;
      threads.add(
          new GitHubReviewClient.PullRequestComment(
              root,
              null,
              posted.file(),
              new SuggestionFormatter()
                  .formatReviewComment(Finding.fromAiResponse(posted), true, i + 1),
              new GitHubReviewClient.ReviewResponse.User("thrillhousebot[bot]")));
      threads.add(
          new GitHubReviewClient.PullRequestComment(
              200L + i,
              root,
              posted.file(),
              "Not a credential: it is a fixed test value. Declining.",
              new GitHubReviewClient.ReviewResponse.User("maintainer"),
              "OWNER"));
    }
    return threads;
  }

  private ReviewResponse persisted(ReviewResponse response) throws Exception {
    return mapper.readValue(mapper.writeValueAsString(response), ReviewResponse.class);
  }

  private ReviewContextLoader.ReviewContext context(
      List<FileDiff> files,
      List<ReviewResponse> priorRounds,
      List<GitHubReviewClient.PullRequestComment> threads)
      throws Exception {
    var jsons = new ArrayList<String>();
    for (var round : priorRounds) {
      jsons.add(mapper.writeValueAsString(round));
    }
    var patches = new HashMap<String, String>();
    files.forEach(file -> patches.put(file.filename(), file.patch()));
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
        () -> new DiffLineResolver(patches),
        null,
        List.of(),
        List.of(),
        SupersededFindingsCarryover.Carried.NONE,
        "",
        true);
  }

  private static FileDiff added(String name, String... lines) {
    var sb = new StringBuilder("@@ -0,0 +1," + lines.length + " @@\n");
    for (var line : lines) {
      sb.append('+').append(line).append('\n');
    }
    return new FileDiff(name, "added", lines.length, 0, lines.length, sb.toString());
  }
}
