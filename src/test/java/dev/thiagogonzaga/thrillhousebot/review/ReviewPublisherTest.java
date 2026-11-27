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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.CapturedLog;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import dev.thiagogonzaga.thrillhousebot.github.TestScopes;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link ReviewPublisher#publishSummary}'s and {@link
 * ReviewPublisher#followUpDeltaSection}'s posting conditions.
 */
class ReviewPublisherTest {

  private final GitHubCommentClient commentClient = mock(GitHubCommentClient.class);

  private final GitHubReviewClient reviewClient = mock(GitHubReviewClient.class);

  private final ReviewThreadService reviewThreadService = mock(ReviewThreadService.class);

  private final ThrillhouseConfig config = mock(ThrillhouseConfig.class);

  private final ThrillhouseConfig.ReviewConfig reviewConfig =
      mock(ThrillhouseConfig.ReviewConfig.class);
  private final ThrillhouseConfig.GitHubConfig githubConfig =
      mock(ThrillhouseConfig.GitHubConfig.class);

  private final ThrillhouseConfig.FollowUpSummaryConfig followUpSummaryConfig =
      mock(ThrillhouseConfig.FollowUpSummaryConfig.class);

  private final ThrillhouseConfig.AgentNextStepsConfig agentNextStepsConfig =
      mock(ThrillhouseConfig.AgentNextStepsConfig.class);

  private final ReviewPublisher publisher =
      new ReviewPublisher(
          reviewClient,
          commentClient,
          reviewThreadService,
          mock(SuggestionFormatter.class),
          mock(FollowUpAnalyzer.class),
          mock(PrLabeler.class),
          config,
          BotIdentity.of("thrillhousebot"),
          new PendingReviewGuard());

  @BeforeEach
  void stubReviewConfig() {
    reviewStubs();
    when(followUpSummaryConfig.enabled()).thenReturn(false);
  }

  private void followUpSummaryEnabled(boolean enabled) {
    when(followUpSummaryConfig.enabled()).thenReturn(enabled);
  }

  private void reviewStubs() {
    when(config.review()).thenReturn(reviewConfig);
    when(config.github()).thenReturn(githubConfig);
    when(githubConfig.writeRetryBudget()).thenReturn(Duration.ofMinutes(5));
    when(reviewConfig.followUpSummary()).thenReturn(followUpSummaryConfig);
    when(reviewConfig.agentNextSteps()).thenReturn(agentNextStepsConfig);
    when(agentNextStepsConfig.enabled()).thenReturn(false);
  }

  private static ReviewResult followUpResult(List<ReviewResult.PreviousFindingStatus> statuses) {
    return new ReviewResult(
        List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, false, "summary", statuses, List.of(), 0);
  }

  private static final ReviewResult RESOLVED_FOLLOW_UP =
      followUpResult(List.of(new ReviewResult.PreviousFindingStatus(1, "resolved", "fixed")));

  private static final ReviewResult SUPERSEDED_RESULT =
      followUpResult(List.of(new ReviewResult.PreviousFindingStatus(1, "superseded", "code gone")));

  private static ReviewResult approvedOnCi() {
    return followUpResult(List.of());
  }

  @Test
  void refreshingTheCiStateEditsOnlyASummaryWithMarkedSections() {
    var bot = new GitHubReviewClient.ReviewResponse.User("thrillhousebot");
    var marked =
        PrSummaryGenerator.SUMMARY_HEADING
            + "\n\n"
            + CiSummarySections.wrap(CiSummarySections.HOLD, "cannot be approved");
    when(commentClient.listComments(anyString(), anyString(), anyString(), anyString(), anyInt()))
        .thenReturn(List.of(new GitHubCommentClient.IssueComment(77L, marked, bot)))
        .thenReturn(
            List.of(
                new GitHubCommentClient.IssueComment(
                    78L, PrSummaryGenerator.SUMMARY_HEADING + "\n\nunmarked", bot)))
        .thenReturn(List.of());

    publisher.refreshCiState("auth", "o", "r", 1, approvedOnCi());
    publisher.refreshCiState("auth", "o", "r", 1, approvedOnCi());
    publisher.refreshCiState("auth", "o", "r", 1, approvedOnCi());

    var edited = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .updateComment(
            anyString(), anyString(), anyString(), anyString(), eq(77L), edited.capture());
    assertTrue(edited.getValue().body().contains("the held approval was posted"));
    verify(commentClient, never())
        .updateComment(anyString(), anyString(), anyString(), anyString(), eq(78L), any());
  }

  @Test
  void followUpWithSupersededFindingEditsTheExistingSummaryInPlace() {
    var bot = new GitHubReviewClient.ReviewResponse.User("thrillhousebot");
    // Two summary comments (e.g. /summary re-posted one): the newest is the one edited.
    when(commentClient.listComments(anyString(), anyString(), anyString(), anyString(), anyInt()))
        .thenReturn(
            List.of(
                new GitHubCommentClient.IssueComment(
                    66L, PrSummaryGenerator.SUMMARY_HEADING + "\n\nolder", bot),
                new GitHubCommentClient.IssueComment(
                    77L, PrSummaryGenerator.SUMMARY_HEADING + "\n\nstale", bot)));

    assertTrue(publisher.publishSummary("auth", "o", "r", 1, SUPERSEDED_RESULT, false));

    verify(commentClient)
        .updateComment(anyString(), anyString(), anyString(), anyString(), eq(77L), any());
    verify(commentClient, never())
        .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
  }

  @Test
  void followUpWithSupersededFindingIgnoresNewerConversationalHeadingReference() {
    var bot = new GitHubReviewClient.ReviewResponse.User("thrillhousebot");
    when(commentClient.listComments(anyString(), anyString(), anyString(), anyString(), anyInt()))
        .thenReturn(
            List.of(
                new GitHubCommentClient.IssueComment(
                    66L,
                    ReviewResult.truncationNotice(2)
                        + PrSummaryGenerator.SUMMARY_HEADING
                        + "\n\nstale",
                    bot),
                new GitHubCommentClient.IssueComment(
                    77L,
                    "As noted in the "
                        + PrSummaryGenerator.SUMMARY_HEADING
                        + ", this was reviewed.",
                    bot)));

    assertTrue(publisher.publishSummary("auth", "o", "r", 1, SUPERSEDED_RESULT, false));

    verify(commentClient)
        .updateComment(anyString(), anyString(), anyString(), anyString(), eq(66L), any());
    verify(commentClient, never())
        .updateComment(anyString(), anyString(), anyString(), anyString(), eq(77L), any());
  }

  @Test
  void followUpWithSupersededFindingPostsANewSummaryWhenNoneExists() {
    // None of these is the bot's summary: no author, another author, no body, unrelated body.
    var bot = new GitHubReviewClient.ReviewResponse.User("thrillhousebot");
    when(commentClient.listComments(anyString(), anyString(), anyString(), anyString(), anyInt()))
        .thenReturn(
            List.of(
                new GitHubCommentClient.IssueComment(1L, PrSummaryGenerator.SUMMARY_HEADING, null),
                new GitHubCommentClient.IssueComment(
                    2L,
                    PrSummaryGenerator.SUMMARY_HEADING,
                    new GitHubReviewClient.ReviewResponse.User("someone-else")),
                new GitHubCommentClient.IssueComment(3L, null, bot),
                new GitHubCommentClient.IssueComment(4L, "just a reply", bot)));

    assertTrue(publisher.publishSummary("auth", "o", "r", 1, SUPERSEDED_RESULT, false));

    verify(commentClient)
        .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
  }

  private static final GitHubReviewClient.ReviewResponse.User BOT =
      new GitHubReviewClient.ReviewResponse.User("thrillhousebot");

  /** The body the publisher posts for {@code summary}: the marker line, then the summary. */
  private static String posted(String summary) {
    return PrSummaryGenerator.SUMMARY_MARKER + "\n" + EMPTY_MANIFEST + "\n" + summary;
  }

  private static final String EMPTY_MANIFEST = "<!-- thrillhousebot:findings v=1 [] -->";

  @Test
  void publishSummaryWithPartialConfigTreatsAgentNextStepsAsOff() {
    var partial =
        new ReviewPublisher(
            reviewClient,
            commentClient,
            reviewThreadService,
            mock(SuggestionFormatter.class),
            mock(FollowUpAnalyzer.class),
            mock(PrLabeler.class),
            configWithGithubOnly(),
            BotIdentity.of("thrillhousebot"),
            new PendingReviewGuard());
    var firstReview =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            null,
            ReviewState.APPROVE,
            true,
            PrSummaryGenerator.SUMMARY_HEADING,
            List.of(),
            List.of(),
            0);

    assertTrue(partial.publishSummary("auth", "o", "r", 1, firstReview, false));

    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(anyString(), anyString(), eq("o"), eq("r"), eq(1), body.capture());
    assertFalse(body.getValue().body().contains(AgentNextSteps.SECTION_HEADING));
  }

  private static ThrillhouseConfig configWithGithubOnly() {
    var config = mock(ThrillhouseConfig.class);
    var github = mock(ThrillhouseConfig.GitHubConfig.class);
    when(config.github()).thenReturn(github);
    when(github.writeRetryBudget()).thenReturn(Duration.ofMinutes(5));
    return config;
  }

  @Test
  void agentNextStepsAddsHeadMarkerSectionAndProseWhenEnabled() {
    when(agentNextStepsConfig.enabled()).thenReturn(true);
    var head = "abcdef1234567890abcdef1234567890abcdef12";
    var summary = PrSummaryGenerator.SUMMARY_HEADING + "\n\nOverview sentence.";
    var firstReview =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            null,
            ReviewState.APPROVE,
            true,
            summary,
            List.of(),
            List.of(),
            0);

    publisher.publishSummary("auth", "o", "r", 1, firstReview, false, null, head);

    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(anyString(), anyString(), eq("o"), eq("r"), eq(1), body.capture());
    var text = body.getValue().body();
    assertTrue(text.contains(AgentNextSteps.headMarker(head)));
    assertTrue(text.contains(AgentNextSteps.SECTION_HEADING));
    assertTrue(text.contains("Overview sentence."));
    assertTrue(text.indexOf(AgentNextSteps.SECTION_HEADING) < text.indexOf("Overview sentence."));
  }

  @Test
  void agentNextStepsOnTheReviewRequestPathNamesTheHeadAndKeepsCommandHintsVerbatim() {
    when(agentNextStepsConfig.enabled()).thenReturn(true);
    var req =
        new ReviewOrchestrator.ReviewRequest(
            "o",
            "r",
            1,
            "abcdef1234567890abcdef1234567890abcdef12",
            "t",
            "",
            "base",
            "main",
            9L,
            TestScopes.REPOSITORY_ID,
            false);
    var doubleCheck =
        new Finding(RiskLevel.LOW, Confidence.LOW, "src/B.java", 9, "Check me", "d", null, null);
    var result =
        new ReviewResult(
            List.of(doubleCheck),
            0,
            0,
            0,
            1,
            RiskLevel.LOW,
            ReviewState.COMMENT,
            true,
            PrSummaryGenerator.SUMMARY_HEADING + "\n\nOverview sentence.",
            List.of(),
            List.of(),
            0);

    publisher.publishSummary("auth", req, result, null);

    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(anyString(), anyString(), eq("o"), eq("r"), eq(1), body.capture());
    var text = body.getValue().body();
    assertTrue(text.contains("**Head:** `abcdef1`"), text);
    assertTrue(text.contains("- @thrillhousebot resolved `src/B.java:9` — Check me\n"), text);
    assertTrue(text.contains("- `/review` or @thrillhousebot review — "), text);
    assertTrue(
        text.contains("- `@thrillhousebot resolved <path>:<line> — <title>` — mark a finding"),
        text);
  }

  @Test
  void agentNextStepsReadsPriorHeadFromTheLatestSummaryAmongManyComments() {
    when(agentNextStepsConfig.enabled()).thenReturn(true);
    var priorHead = "aaaabbbbccccddddeeeeaaaabbbbccccddddeeeeaaaa0001";
    var newHead = "aaaabbbbccccddddeeeeaaaabbbbccccddddeeeeaaaa0002";
    var priorSummary =
        PrSummaryGenerator.SUMMARY_MARKER
            + "\n"
            + AgentNextSteps.headMarker(priorHead)
            + "\n"
            + EMPTY_MANIFEST
            + "\n"
            + PrSummaryGenerator.SUMMARY_HEADING;
    var comments = new java.util.ArrayList<GitHubCommentClient.IssueComment>();
    for (int i = 0; i < 50; i++) {
      comments.add(new GitHubCommentClient.IssueComment(i, "noise " + i, null));
    }
    comments.add(new GitHubCommentClient.IssueComment(99L, priorSummary, BOT));
    when(commentClient.listComments(anyString(), anyString(), anyString(), anyString(), anyInt()))
        .thenReturn(comments);

    var followUp =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            null,
            ReviewState.APPROVE,
            false,
            PrSummaryGenerator.SUMMARY_HEADING + "\n\nAfter.",
            List.of(),
            List.of(),
            0);

    publisher.publishSummary("auth", "o", "r", 1, followUp, false, null, newHead);

    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .updateComment(anyString(), anyString(), anyString(), anyString(), eq(99L), body.capture());
    assertTrue(body.getValue().body().contains("new since last summary"));
    verify(commentClient, times(1)).listComments(anyString(), anyString(), eq("o"), eq("r"), eq(1));
    verify(commentClient, never())
        .listCommentsPageOnce(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void agentNextStepsKeepsThePriorHeadWhenForceSummaryReposts() {
    when(agentNextStepsConfig.enabled()).thenReturn(true);
    var priorHead = "aaaabbbbccccddddeeeeaaaabbbbccccddddeeeeaaaa0001";
    var newHead = "aaaabbbbccccddddeeeeaaaabbbbccccddddeeeeaaaa0002";
    var priorSummary =
        PrSummaryGenerator.SUMMARY_MARKER
            + "\n"
            + AgentNextSteps.headMarker(priorHead)
            + "\n"
            + EMPTY_MANIFEST
            + "\n"
            + PrSummaryGenerator.SUMMARY_HEADING;
    when(commentClient.listComments(anyString(), anyString(), anyString(), anyString(), anyInt()))
        .thenReturn(List.of(new GitHubCommentClient.IssueComment(99L, priorSummary, BOT)));

    var followUp =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            null,
            ReviewState.APPROVE,
            false,
            PrSummaryGenerator.SUMMARY_HEADING + "\n\nAfter.",
            List.of(),
            List.of(),
            0);

    publisher.publishSummary("auth", "o", "r", 1, followUp, true, null, newHead);

    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(anyString(), anyString(), eq("o"), eq("r"), eq(1), body.capture());
    assertTrue(body.getValue().body().contains("new since last summary"));
    verify(commentClient, times(1)).listComments(anyString(), anyString(), eq("o"), eq("r"), eq(1));
  }

  @Test
  void theSummaryCarriesTheOpenFindingsManifestBetweenTheMarkerAndTheProse() {
    var open =
        new Finding(RiskLevel.HIGH, Confidence.HIGH, "src/A.java", 12, "NPE", "d", null, null);
    var result =
        new ReviewResult(
            List.of(open),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.COMMENT,
            true,
            "summary",
            List.of(),
            List.of(),
            0);

    assertTrue(publisher.publishSummary("auth", "o", "r", 1, result, false));

    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(anyString(), anyString(), eq("o"), eq("r"), eq(1), body.capture());
    var lines = body.getValue().body().lines().toList();
    assertEquals(PrSummaryGenerator.SUMMARY_MARKER, lines.get(0));
    assertEquals(
        "<!-- thrillhousebot:findings v=1 [{\"key\":\""
            + SuggestionFormatter.findingKey("src/A.java", 12, "NPE")
            + "\",\"file\":\"src/A.java\",\"line\":12,\"risk\":\"high\",\"confidence\":\"high\","
            + "\"status\":\"open\",\"thread\":true,\"hasSuggestion\":false,\"carried\":false}]"
            + " -->",
        lines.get(1));
    assertEquals("summary", lines.get(2));
    assertTrue(ReviewContextLoader.isBotSummaryComment(body.getValue().body()));
  }

  private void existingComments(GitHubCommentClient.IssueComment... comments) {
    when(commentClient.listComments(anyString(), anyString(), anyString(), anyString(), anyInt()))
        .thenReturn(List.of(comments));
  }

  @Test
  void firstRoundPostsTheSummaryWithTheMarkerOnItsFirstLine() {
    var firstReview =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            null,
            ReviewState.APPROVE,
            true,
            "summary",
            List.of(),
            List.of(),
            0);

    assertTrue(publisher.publishSummary("auth", "o", "r", 1, firstReview, false));

    // The first round already knows there is no summary to edit (that is what makes it the first),
    // so it posts without listing the conversation.
    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(anyString(), anyString(), eq("o"), eq("r"), eq(1), body.capture());
    assertEquals(posted("summary"), body.getValue().body());
    assertTrue(ReviewContextLoader.isBotSummaryComment(body.getValue().body()));
    verify(commentClient, never())
        .listComments(anyString(), anyString(), anyString(), anyString(), anyInt());
  }

  @Test
  void secondRoundEditsTheExistingSummaryInPlace() {
    // #868: a follow-up round used to leave the first round's summary standing, stale. It now
    // rewrites that comment with the current round's render, marker included, and posts nothing.
    existingComments(
        new GitHubCommentClient.IssueComment(
            5L, posted(ReviewResult.truncationNotice(3) + "round one"), BOT));

    // An ordinary follow-up's edit notifies nobody, so it does not stand in for the round's
    // outcome: the review and the delta comment still post.
    assertFalse(publisher.publishSummary("auth", "o", "r", 1, RESOLVED_FOLLOW_UP, false));

    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .updateComment(anyString(), anyString(), eq("o"), eq("r"), eq(5L), body.capture());
    // The whole body is replaced: the earlier round's truncation banner does not survive an edit
    // whose own round was not truncated.
    assertEquals(posted("summary"), body.getValue().body());
    verify(commentClient, never())
        .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
  }

  @Test
  void followUpFindsASummaryPostedBeforeTheMarkerExistedByItsHeading() {
    existingComments(
        new GitHubCommentClient.IssueComment(
            8L, PrSummaryGenerator.SUMMARY_HEADING + "\n\nposted by an older version", BOT));

    publisher.publishSummary("auth", "o", "r", 1, RESOLVED_FOLLOW_UP, false);

    verify(commentClient)
        .updateComment(anyString(), anyString(), anyString(), anyString(), eq(8L), any());
    verify(commentClient, never())
        .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
  }

  @Test
  void followUpPostsAFreshSummaryWhenTheMarkedCommentWasDeleted() {
    // Only the delta comment and a maintainer's reply are left: neither is the summary.
    existingComments(
        new GitHubCommentClient.IssueComment(
            3L, FollowUpDeltaSummary.DELTA_HEADING + "\n\n- counts", BOT),
        new GitHubCommentClient.IssueComment(
            4L, posted("quoted"), new GitHubReviewClient.ReviewResponse.User("maintainer")));

    assertFalse(publisher.publishSummary("auth", "o", "r", 1, RESOLVED_FOLLOW_UP, false));

    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(anyString(), anyString(), eq("o"), eq("r"), eq(1), body.capture());
    assertEquals(posted("summary"), body.getValue().body());
    verify(commentClient, never())
        .updateComment(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
  }

  @Test
  void refusedEditFallsBackToPostingANewSummary() {
    existingComments(new GitHubCommentClient.IssueComment(5L, posted("round one"), BOT));
    when(commentClient.updateComment(
            anyString(), anyString(), anyString(), anyString(), anyLong(), any()))
        .thenThrow(new WebApplicationException(404));

    // A failed edit must not cost the pull request its current summary.
    assertFalse(publisher.publishSummary("auth", "o", "r", 1, RESOLVED_FOLLOW_UP, false));

    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(anyString(), anyString(), eq("o"), eq("r"), eq(1), body.capture());
    assertEquals(posted("summary"), body.getValue().body());
  }

  @Test
  void followUpThatRendersTheSameSummarySpendsNoWrite() {
    existingComments(new GitHubCommentClient.IssueComment(5L, posted("summary"), BOT));

    assertFalse(publisher.publishSummary("auth", "o", "r", 1, RESOLVED_FOLLOW_UP, false));

    verify(commentClient, never())
        .updateComment(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
    verify(commentClient, never())
        .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
  }

  @Test
  void theOpenFindingsHandedOutAreTheOnesTheSummaryManifestLists() {
    var finding =
        new Finding(RiskLevel.HIGH, Confidence.HIGH, "src/A.java", 12, "NPE", "d", null, null);
    var firstReview =
        new ReviewResult(
            List.of(finding),
            0,
            1,
            0,
            0,
            RiskLevel.HIGH,
            ReviewState.REQUEST_CHANGES,
            true,
            "summary",
            List.of(),
            List.of(),
            0);

    publisher.publishSummary("auth", "o", "r", 1, firstReview, false);
    var open =
        ManifestThreads.openFindings(
            firstReview,
            new ManifestThreads.PostedThreads(
                Set.of(SuggestionFormatter.findingKey("src/A.java", 12, "NPE"))));

    var posted = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(
            anyString(), anyString(), anyString(), anyString(), anyInt(), posted.capture());
    assertEquals(0, open.omitted());
    assertTrue(open.entries().contains("\"file\":\"src/A.java\""));
    assertTrue(
        posted
            .getValue()
            .body()
            .contains("<!-- thrillhousebot:findings v=1 " + open.entries() + " -->"));
  }

  @Test
  void followUpWithNoSummaryToPostTouchesNothing() {
    var blank =
        new ReviewResult(
            List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, false, " ", List.of(), List.of(), 0);

    assertFalse(publisher.publishSummary("auth", "o", "r", 1, blank, false));

    verify(commentClient, never())
        .listComments(anyString(), anyString(), anyString(), anyString(), anyInt());
  }

  @Test
  void followUpDeltaIsAbsentWhenTheFeatureIsOff() {
    followUpSummaryEnabled(false);
    assertEquals("", publisher.followUpDeltaSection(RESOLVED_FOLLOW_UP, false, List.of()));
  }

  @Test
  void followUpDeltaRendersCountsAndNamesWithoutPostingAComment() {
    followUpSummaryEnabled(true);
    var body =
        publisher.followUpDeltaSection(RESOLVED_FOLLOW_UP, false, List.of(THREADLESS_PREVIOUS));
    assertTrue(body.startsWith(FollowUpDeltaSummary.DELTA_HEADING), body);
    assertTrue(body.contains("**Previous findings resolved:** 1"), body);
    assertTrue(body.contains("src/main/java/A.java:42") && body.contains("Unbounded retry"), body);
    verify(commentClient, never())
        .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
  }

  @Test
  void followUpDeltaIsAbsentWhenNothingMoved() {
    followUpSummaryEnabled(true);
    var stalled =
        followUpResult(List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "still")));
    assertEquals("", publisher.followUpDeltaSection(stalled, false, List.of()));
  }

  @Test
  void firstReviewDoesNotGainAFollowUpDeltaEvenWhenSummaryFailed() {
    followUpSummaryEnabled(true);
    var firstReview =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            null,
            ReviewState.APPROVE,
            true,
            "summary",
            List.of(new ReviewResult.PreviousFindingStatus(1, "resolved", "fixed")),
            List.of(),
            0);
    assertEquals("", publisher.followUpDeltaSection(firstReview, false, List.of()));
  }

  @Test
  void successfulSummaryCommandDoesNotGainAFollowUpDelta() {
    followUpSummaryEnabled(true);
    assertEquals("", publisher.followUpDeltaSection(RESOLVED_FOLLOW_UP, true, List.of()));
  }

  /**
   * The threadless finding of #712: raised in an earlier round, published with no inline thread of
   * its own, and closed this round by an {@code @thrillhousebot resolved} directive.
   */
  private static final ReviewResponse.Finding THREADLESS_PREVIOUS =
      new ReviewResponse.Finding(
          "high", "src/main/java/A.java", 42, "Unbounded retry", "d", null, null);

  @Test
  void clearedThreadlessFindingIsNamedOnTheReviewBodyADefaultInstallPosts() {
    // #737: #714 shipped two naming surfaces and a default install reaches neither for the case it
    // called worst. The delta comment that names closures is opt-in and defaults off, and the other
    // surface is a reply on the closed finding's thread — which a threadless finding (#712) does
    // not have, and that is exactly the finding a clearing directive is the only way to close
    // (#709). So the round closed it and nothing anywhere said what closed. The review body is
    // behind no flag and posts on this round regardless, so it carries the names.
    followUpSummaryEnabled(false);
    var req =
        new ReviewOrchestrator.ReviewRequest(
            "o", "r", 1, "sha", "t", "", "base", "main", 9L, TestScopes.REPOSITORY_ID, false);
    // A thread exists on the PR, just not for this finding — so the reply surface is skipped for
    // being unmatched, not for the PR having no inline comments at all.
    var otherThread =
        List.of(
            new GitHubReviewClient.PullRequestComment(
                5L,
                null,
                "src/main/java/B.java",
                "unrelated",
                new GitHubReviewClient.ReviewResponse.User("thrillhousebot")));

    var delta =
        publisher.followUpDeltaSection(RESOLVED_FOLLOW_UP, false, List.of(THREADLESS_PREVIOUS));
    publisher.resolveAddressedThreads(
        "auth",
        req,
        List.of(THREADLESS_PREVIOUS),
        otherThread,
        RESOLVED_FOLLOW_UP.previousStatuses());
    publisher.postReview(
        new ReviewPublisher.PostReviewRequest(
            "auth",
            "o",
            "r",
            1,
            "sha",
            RESOLVED_FOLLOW_UP,
            new DiffLineResolver(Map.of()),
            false,
            List.of(THREADLESS_PREVIOUS)));

    // Neither of #714's surfaces reaches this maintainer: no delta comment, no thread reply.
    assertTrue(delta.isEmpty());
    verify(commentClient, never())
        .createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    verify(reviewClient, never())
        .replyToReviewComment(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong(), any());
    var review = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
    verify(reviewClient)
        .createReview(anyString(), anyString(), eq("o"), eq("r"), eq(1), review.capture());
    var body = review.getValue().body();
    assertTrue(body.contains("ThrillhouseBot closed 1 previous finding(s) this round:"), body);
    assertTrue(body.contains("- `src/main/java/A.java:42` — Unbounded retry"), body);
  }

  @Test
  void summaryOnlyRerunStillPostsWhenASupersededFindingClosedEarlier() {
    when(config.review()).thenReturn(reviewConfig);
    when(config.github()).thenReturn(githubConfig);
    when(githubConfig.writeRetryBudget()).thenReturn(Duration.ofMinutes(5));
    var bot = new GitHubReviewClient.ReviewResponse.User("thrillhousebot");
    var prior = new GitHubReviewClient.ReviewResponse(1L, "", "APPROVED", "sha1", bot);
    when(reviewClient.createReview(
            anyString(), anyString(), anyString(), anyString(), anyInt(), any()))
        .thenReturn(new GitHubReviewClient.ReviewResponse(2L, "", "APPROVE", "sha1", bot));
    publisher.postReview(
        new ReviewPublisher.PostReviewRequest(
            "auth",
            "o",
            "r",
            1,
            "sha1",
            SUPERSEDED_RESULT,
            new DiffLineResolver(Map.of()),
            true,
            List.of(),
            "",
            List.of(prior)));

    verify(reviewClient)
        .createReview(anyString(), anyString(), anyString(), anyString(), eq(1), any());
  }

  @Test
  void summaryOnlyRerunSkipsReviewWhenBotAlreadyApprovedThisHead() {
    when(config.review()).thenReturn(reviewConfig);
    when(config.github()).thenReturn(githubConfig);
    when(githubConfig.writeRetryBudget()).thenReturn(Duration.ofMinutes(5));
    var bot = new GitHubReviewClient.ReviewResponse.User("thrillhousebot");
    var prior = new GitHubReviewClient.ReviewResponse(1L, "", "APPROVED", "sha1", bot);
    var clean =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            null,
            ReviewState.APPROVE,
            false,
            "summary",
            List.of(),
            List.of(),
            0);
    publisher.postReview(
        new ReviewPublisher.PostReviewRequest(
            "auth",
            "o",
            "r",
            1,
            "sha1",
            clean,
            new DiffLineResolver(Map.of()),
            true,
            List.of(),
            "",
            List.of(prior)));

    verify(reviewClient, never())
        .createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
    verify(reviewClient, never())
        .createPendingReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
  }

  private static final String BOT_LOGIN = "thrillhousebot";

  private static GitHubReviewClient.ReviewResponse review(
      long id, String state, String commitSha, String login) {
    return new GitHubReviewClient.ReviewResponse(
        id,
        "",
        state,
        commitSha,
        login == null ? null : new GitHubReviewClient.ReviewResponse.User(login));
  }

  /** A follow-up with no new findings whose approval pending CI holds back. */
  private static ReviewResult heldOnCi() {
    return new ReviewResult(
        List.of(),
        0,
        0,
        0,
        0,
        null,
        ReviewState.COMMENT,
        false,
        "summary",
        List.of(),
        List.of(new ReviewResult.CiCheck("build", "check-run", "pending", null)),
        0);
  }

  private static ReviewResult withFinding(ReviewState state) {
    var finding =
        new Finding(RiskLevel.HIGH, "src/A.java", 3, "Missing null check", "desc", null, null);
    return new ReviewResult(
        List.of(finding),
        0,
        1,
        0,
        0,
        RiskLevel.HIGH,
        state,
        false,
        "summary",
        List.of(),
        List.of(),
        0);
  }

  private void post(
      String headSha, ReviewResult result, List<GitHubReviewClient.ReviewResponse> priorReviews) {
    publisher.postReview(
        new ReviewPublisher.PostReviewRequest(
            "auth",
            "o",
            "r",
            1,
            headSha,
            result,
            new DiffLineResolver(Map.of()),
            false,
            List.of(),
            "",
            priorReviews));
  }

  private void verifyDismissed(long reviewId, String headSha) {
    verify(reviewClient)
        .dismissReview(
            "auth",
            "application/vnd.github+json",
            "o",
            "r",
            1,
            reviewId,
            ApprovalWithdrawals.approvalWithdrawnMessage(headSha));
  }

  private void verifyNothingDismissed() {
    verify(reviewClient, never())
        .dismissReview(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong(), anyString());
  }

  private String postedReviewEvent() {
    var posted = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
    verify(reviewClient)
        .createReview(anyString(), anyString(), eq("o"), eq("r"), eq(1), posted.capture());
    return posted.getValue().event();
  }

  @Test
  void roundOnANewHeadThatDoesNotApproveDismissesTheBotsEarlierApproval() {
    post("sha2", heldOnCi(), List.of(review(1L, "APPROVED", "sha1", BOT_LOGIN)));

    var order = org.mockito.Mockito.inOrder(reviewClient);
    order
        .verify(reviewClient)
        .dismissReview(
            "auth",
            "application/vnd.github+json",
            "o",
            "r",
            1,
            1L,
            ApprovalWithdrawals.approvalWithdrawnMessage("sha2"));
    order
        .verify(reviewClient)
        .createReview(anyString(), anyString(), eq("o"), eq("r"), eq(1), any());
    assertEquals("COMMENT", postedReviewEvent());
  }

  @Test
  void roundThatRequestsChangesDismissesTheBotsEarlierApproval() {
    post(
        "sha2",
        withFinding(ReviewState.REQUEST_CHANGES),
        List.of(review(1L, "APPROVED", "sha1", BOT_LOGIN)));

    verifyDismissed(1L, "sha2");
    assertEquals("REQUEST_CHANGES", postedReviewEvent());
  }

  @Test
  void roundThatDoesNotApproveDismissesAnApprovalOfTheSameHead() {
    post("sha1", heldOnCi(), List.of(review(1L, "APPROVED", "sha1", BOT_LOGIN)));

    verifyDismissed(1L, "sha1");
  }

  @Test
  void approvalBehindALaterCommentReviewIsStillDismissed() {
    // GitHub goes on counting an approval after the same author comments, so the approval is the
    // review to withdraw even though it is no longer the bot's latest one.
    post(
        "sha3",
        heldOnCi(),
        List.of(
            review(1L, "APPROVED", "sha1", BOT_LOGIN), review(2L, "COMMENTED", "sha2", BOT_LOGIN)));

    verifyDismissed(1L, "sha3");
  }

  @Test
  void everyStandingBotApprovalIsDismissedNewestFirst() {
    post(
        "sha3",
        heldOnCi(),
        List.of(
            review(1L, "APPROVED", "sha1", BOT_LOGIN), review(2L, "APPROVED", "sha2", BOT_LOGIN)));

    var order = org.mockito.Mockito.inOrder(reviewClient);
    order
        .verify(reviewClient)
        .dismissReview(
            anyString(), anyString(), anyString(), anyString(), anyInt(), eq(2L), anyString());
    order
        .verify(reviewClient)
        .dismissReview(
            anyString(), anyString(), anyString(), anyString(), anyInt(), eq(1L), anyString());
  }

  @Test
  void roundThatApprovesAgainKeepsTheEarlierApproval() {
    var approving =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            null,
            ReviewState.APPROVE,
            false,
            "summary",
            List.of(),
            List.of(),
            0);

    post("sha2", approving, List.of(review(1L, "APPROVED", "sha1", BOT_LOGIN)));

    verifyNothingDismissed();
    assertEquals("APPROVE", postedReviewEvent());
  }

  @Test
  void roundWithFindingsDismissesWhateverItsVerdictBecauseItPostsNoApproval() {
    // A round with findings posts COMMENT, so even one carrying an APPROVE verdict (the verdict
    // builder never pairs the two) leaves no approving review for the head.
    post(
        "sha2",
        withFinding(ReviewState.APPROVE),
        List.of(review(1L, "APPROVED", "sha1", BOT_LOGIN)));

    verifyDismissed(1L, "sha2");
    assertEquals("COMMENT", postedReviewEvent());
  }

  @Test
  void onlyTheBotsOwnStandingApprovalIsEverDismissed() {
    post(
        "sha2",
        heldOnCi(),
        List.of(
            review(8L, "APPROVED", "sha1", BOT_LOGIN),
            review(1L, "APPROVED", "sha1", "maintainer"),
            review(2L, "APPROVED", "sha1", "another-app[bot]"),
            // A review from a since-deleted account serializes as user:null.
            review(3L, "APPROVED", "sha1", null),
            review(4L, "DISMISSED", "sha1", BOT_LOGIN),
            review(5L, "COMMENTED", "sha1", BOT_LOGIN),
            review(6L, "CHANGES_REQUESTED", "sha1", BOT_LOGIN),
            review(7L, "PENDING", "sha1", BOT_LOGIN)));

    verify(reviewClient, times(1))
        .dismissReview(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong(), anyString());
    verifyDismissed(8L, "sha2");
    assertEquals("COMMENT", postedReviewEvent());
  }

  @Test
  void refusedDismissalStillPostsTheReviewAndStopsAsking() {
    when(reviewClient.dismissReview(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong(), anyString()))
        .thenThrow(new WebApplicationException(403));

    post(
        "sha3",
        heldOnCi(),
        List.of(
            review(1L, "APPROVED", "sha1", BOT_LOGIN), review(2L, "APPROVED", "sha2", BOT_LOGIN)));

    // The newest approval is tried first; the refusal would meet the older one too.
    verify(reviewClient, times(1))
        .dismissReview(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong(), anyString());
    verifyDismissed(2L, "sha3");
    assertEquals("COMMENT", postedReviewEvent());
  }

  @Test
  void dismissalThatFailsWithoutAnAnswerStillPostsTheReview() {
    when(reviewClient.dismissReview(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong(), anyString()))
        .thenThrow(new jakarta.ws.rs.ProcessingException("read timed out"));

    post(
        "sha2",
        withFinding(ReviewState.REQUEST_CHANGES),
        List.of(review(1L, "APPROVED", "sha1", BOT_LOGIN)));

    verifyDismissed(1L, "sha2");
    assertEquals("REQUEST_CHANGES", postedReviewEvent());
  }

  @Test
  void firstRoundHeldOnCiThatPostsNoReviewStillDismissesTheEarlierApproval() {
    // The summary comment stands in for this round's review, so nothing else would withdraw it.
    var firstRoundHeldOnCi =
        new ReviewResult(
            List.of(),
            0,
            0,
            0,
            0,
            null,
            ReviewState.COMMENT,
            true,
            "summary",
            List.of(),
            List.of(new ReviewResult.CiCheck("build", "check-run", "pending", null)),
            0);

    publisher.postReview(
        new ReviewPublisher.PostReviewRequest(
            "auth",
            "o",
            "r",
            1,
            "sha2",
            firstRoundHeldOnCi,
            new DiffLineResolver(Map.of()),
            true,
            List.of(),
            "",
            List.of(review(1L, "APPROVED", "sha1", BOT_LOGIN))));

    verifyDismissed(1L, "sha2");
    verify(reviewClient, never())
        .createReview(anyString(), anyString(), anyString(), anyString(), anyInt(), any());
  }

  @Test
  void dismissalThatFailsWithoutAnAnswerStillTriesTheOlderApprovals() {
    when(reviewClient.dismissReview(
            anyString(), anyString(), anyString(), anyString(), anyInt(), eq(2L), anyString()))
        .thenThrow(new jakarta.ws.rs.ProcessingException("read timed out"));

    post(
        "sha3",
        heldOnCi(),
        List.of(
            review(1L, "APPROVED", "sha1", BOT_LOGIN), review(2L, "APPROVED", "sha2", BOT_LOGIN)));

    // A timeout says nothing about the next call, so the older approval is not left counting.
    verifyDismissed(2L, "sha3");
    verifyDismissed(1L, "sha3");
    assertEquals("COMMENT", postedReviewEvent());
  }

  @Test
  void dismissalMetByAServerErrorStillTriesTheOlderApprovals() {
    when(reviewClient.dismissReview(
            anyString(), anyString(), anyString(), anyString(), anyInt(), eq(2L), anyString()))
        .thenThrow(new WebApplicationException(502));

    post(
        "sha3",
        heldOnCi(),
        List.of(
            review(1L, "APPROVED", "sha1", BOT_LOGIN), review(2L, "APPROVED", "sha2", BOT_LOGIN)));

    verifyDismissed(1L, "sha3");
  }

  /** GitHub's answers to a dismissal that say nothing about the App's right to dismiss here. */
  private static Stream<Arguments> answersThatAreNotAPermissionRefusal() {
    return Stream.of(
        Arguments.of("404, the review is gone", Response.status(404).build()),
        Arguments.of("422, the review was already dismissed", Response.status(422).build()),
        Arguments.of("429, the retries did not outlast the throttle", Response.status(429).build()),
        Arguments.of(
            "403 with Retry-After, a secondary rate limit",
            Response.status(403).header("Retry-After", "60").build()),
        Arguments.of(
            "403 with no rate limit left, the primary rate limit",
            Response.status(403).header("x-ratelimit-remaining", "0").build()),
        Arguments.of(
            "403 worded as a secondary rate limit",
            Response.status(403)
                .entity("{\"message\":\"You have exceeded a secondary rate limit.\"}")
                .build()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("answersThatAreNotAPermissionRefusal")
  void dismissalMetByAnAnswerAboutOneCallStillTriesTheOlderApprovals(
      String answer, Response response) {
    when(reviewClient.dismissReview(
            anyString(), anyString(), anyString(), anyString(), anyInt(), eq(2L), anyString()))
        .thenThrow(new WebApplicationException(response));

    try (var log = CapturedLog.of(ApprovalWithdrawals.class)) {
      post(
          "sha3",
          heldOnCi(),
          List.of(
              review(1L, "APPROVED", "sha1", BOT_LOGIN),
              review(2L, "APPROVED", "sha2", BOT_LOGIN)));

      verifyDismissed(2L, "sha3");
      verifyDismissed(1L, "sha3");
      var warnings = log.at(Level.WARNING);
      assertEquals(1, warnings.size(), warnings.toString());
      assertTrue(warnings.getFirst().contains("older approvals are still tried"), answer);
      assertFalse(warnings.getFirst().contains("Pull requests: write"), answer);
    }
    assertEquals("COMMENT", postedReviewEvent());
  }

  @Test
  void refusedDismissalIsLoggedWithThePermissionHint() {
    when(reviewClient.dismissReview(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong(), anyString()))
        .thenThrow(
            new WebApplicationException(
                Response.status(403)
                    .entity("{\"message\":\"Resource not accessible by integration\"}")
                    .build()));

    try (var log = CapturedLog.of(ApprovalWithdrawals.class)) {
      post(
          "sha3",
          heldOnCi(),
          List.of(
              review(1L, "APPROVED", "sha1", BOT_LOGIN),
              review(2L, "APPROVED", "sha2", BOT_LOGIN)));

      verify(reviewClient, times(1))
          .dismissReview(
              anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong(), anyString());
      var warnings = log.at(Level.WARNING);
      assertEquals(1, warnings.size(), warnings.toString());
      assertTrue(warnings.getFirst().contains("Pull requests: write"), warnings.getFirst());
    }
  }

  @Test
  void dismissalMessageNamesTheHeadThatWasNotApproved() {
    assertEquals(
        "ThrillhouseBot withdrew this approval: its review round for sha2 did not approve the"
            + " pull request.",
        ApprovalWithdrawals.approvalWithdrawnMessage("sha2"));
  }

  @Test
  void pushDismissalMessageSaysTheNewHeadHasNoReviewYet() {
    assertEquals(
        "ThrillhouseBot withdrew this approval: commits were pushed after it, and sha2 has not"
            + " been reviewed yet.",
        ApprovalWithdrawals.approvalWithdrawnOnPushMessage("sha2"));
  }

  private void verifyDismissedOnPush(long reviewId, String headSha) {
    verify(reviewClient)
        .dismissReview(
            "auth",
            "application/vnd.github+json",
            "o",
            "r",
            1,
            reviewId,
            ApprovalWithdrawals.approvalWithdrawnOnPushMessage(headSha));
  }

  @Test
  void pushWithdrawsEveryStandingBotApprovalNewestFirstAndPostsNothing() {
    try (var log = CapturedLog.of(ApprovalWithdrawals.class)) {
      publisher.withdrawApprovalsAfterPush(
          "auth",
          "o",
          "r",
          1,
          "sha3",
          List.of(
              review(1L, "APPROVED", "sha1", BOT_LOGIN),
              review(2L, "APPROVED", "sha2", BOT_LOGIN),
              review(3L, "APPROVED", "sha2", "maintainer"),
              review(4L, "DISMISSED", "sha1", BOT_LOGIN)));

      var order = org.mockito.Mockito.inOrder(reviewClient);
      order
          .verify(reviewClient)
          .dismissReview(
              anyString(), anyString(), anyString(), anyString(), anyInt(), eq(2L), anyString());
      order
          .verify(reviewClient)
          .dismissReview(
              anyString(), anyString(), anyString(), anyString(), anyInt(), eq(1L), anyString());
      verifyDismissedOnPush(2L, "sha3");
      verifyDismissedOnPush(1L, "sha3");
      org.mockito.Mockito.verifyNoMoreInteractions(reviewClient);
      assertEquals(2, log.at(Level.INFO).size(), log.at(Level.INFO).toString());
      assertTrue(
          log.at(Level.INFO).stream()
              .allMatch(
                  line ->
                      line.endsWith("commits were pushed after it and sha3 has not been reviewed")),
          log.at(Level.INFO).toString());
    }
  }

  @Test
  void pushDismissalRefusedForWantOfPermissionIsLoggedAndStopsTheWalk() {
    when(reviewClient.dismissReview(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong(), anyString()))
        .thenThrow(
            new WebApplicationException(
                Response.status(403)
                    .entity("{\"message\":\"Resource not accessible by integration\"}")
                    .build()));

    try (var log = CapturedLog.of(ApprovalWithdrawals.class)) {
      publisher.withdrawApprovalsAfterPush(
          "auth",
          "o",
          "r",
          1,
          "sha3",
          List.of(
              review(1L, "APPROVED", "sha1", BOT_LOGIN),
              review(2L, "APPROVED", "sha2", BOT_LOGIN)));

      verify(reviewClient, times(1))
          .dismissReview(
              anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong(), anyString());
      var warnings = log.at(Level.WARNING);
      assertEquals(1, warnings.size(), warnings.toString());
      assertTrue(warnings.getFirst().contains("Pull requests: write"), warnings.getFirst());
      assertTrue(
          warnings
              .getFirst()
              .contains("although commits were pushed after it and sha3 has not been reviewed"),
          warnings.getFirst());
      assertFalse(warnings.getFirst().contains("round"), warnings.getFirst());
    }
  }

  @Test
  void pushDismissalThatFailsOtherwiseStillTriesTheOlderApprovals() {
    when(reviewClient.dismissReview(
            anyString(), anyString(), anyString(), anyString(), anyInt(), eq(2L), anyString()))
        .thenThrow(new WebApplicationException(502));

    publisher.withdrawApprovalsAfterPush(
        "auth",
        "o",
        "r",
        1,
        "sha3",
        List.of(
            review(1L, "APPROVED", "sha1", BOT_LOGIN), review(2L, "APPROVED", "sha2", BOT_LOGIN)));

    verifyDismissedOnPush(1L, "sha3");
  }

  @Test
  void roundThatDoesNotApproveSkipsAnApprovalThePushAlreadyDismissed() {
    // The push dismissed the approval of sha1; the listing the later round reads says so.
    post("sha2", heldOnCi(), List.of(review(1L, "DISMISSED", "sha1", BOT_LOGIN)));

    verifyNothingDismissed();
    assertEquals("COMMENT", postedReviewEvent());
  }

  @Test
  void roundLogsItsOwnReasonForADismissal() {
    try (var log = CapturedLog.of(ApprovalWithdrawals.class)) {
      post("sha2", heldOnCi(), List.of(review(1L, "APPROVED", "sha1", BOT_LOGIN)));

      assertTrue(
          log.at(Level.INFO)
              .contains("Dismissed approval 1 on o/r #1 — the round for sha2 did not approve"),
          log.at(Level.INFO).toString());
    }
  }

  @Test
  void reviewBodyNamingClosuresIsNotReadBackAsAPreviousFinding() {
    // The section names findings the round just closed, so a later round that falls back to the
    // last bot review body for its previous-findings context must not hand them to the model as
    // issues "flagged in the previous review" and re-open every one of them (#455).
    var body =
        ClosedFindingNames.reviewBodySection(RESOLVED_FOLLOW_UP, List.of(THREADLESS_PREVIOUS));

    assertTrue(FollowUpAnalyzer.isSelfAuthoredStatusBody(body), body);
  }

  /** A follow-up that raised nothing and is held because no stored earlier round could be read. */
  private static ReviewResult heldOnUnreadableRounds() {
    return new ReviewResult(
        List.of(),
        0,
        0,
        0,
        0,
        null,
        ReviewState.COMMENT,
        false,
        "summary",
        List.of(),
        List.of(),
        0,
        false,
        true,
        null,
        0,
        List.of(),
        true);
  }

  @Test
  void aRoundHeldOnUnreadableEarlierRoundsSaysSoInTheReviewBody() {
    post("sha2", heldOnUnreadableRounds(), List.of());

    var posted = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
    verify(reviewClient)
        .createReview(anyString(), anyString(), eq("o"), eq("r"), eq(1), posted.capture());
    assertEquals("COMMENT", posted.getValue().event());
    var body = posted.getValue().body();
    assertEquals(ReviewResult.PRIOR_ROUNDS_UNREADABLE_NOTICE.strip(), body);
    // The bot's own sentence about its verdict is never offered back to the model as a finding.
    assertTrue(FollowUpAnalyzer.isSelfAuthoredStatusBody(body), body);
  }
}
