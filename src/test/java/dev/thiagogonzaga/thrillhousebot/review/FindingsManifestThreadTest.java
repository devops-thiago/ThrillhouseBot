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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import jakarta.ws.rs.WebApplicationException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * #1433 review: the manifest's {@code thread} follows the rules the review posts by, so a consumer
 * is not sent to reply on a thread the round never opens.
 */
class FindingsManifestThreadTest {

  private static final Pattern LINE =
      Pattern.compile("^<!-- thrillhousebot:findings v=1 (.*) -->$", Pattern.MULTILINE);
  private static final String PATCH = "@@ -1,2 +1,3 @@\n a\n+b\n+c\n";
  private static final String LONG_PATCH = "@@ -1,2 +1,6 @@\n a\n+b\n+c\n+d\n+e\n+f\n";
  private static final long SUMMARY_ID = 77L;

  private final GitHubCommentClient commentClient = mock(GitHubCommentClient.class);
  private final GitHubReviewClient reviewClient = mock(GitHubReviewClient.class);
  private final ReviewThreadService threadService = mock(ReviewThreadService.class);
  private final SuggestionFormatter suggestionFormatter = mock(SuggestionFormatter.class);
  private final ThrillhouseConfig.ReviewConfig reviewConfig =
      mock(ThrillhouseConfig.ReviewConfig.class);
  private final ReviewPublisher publisher;

  FindingsManifestThreadTest() {
    var config = mock(ThrillhouseConfig.class);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.maxReviewComments()).thenReturn(2);
    var githubConfig = mock(ThrillhouseConfig.GitHubConfig.class);
    when(config.github()).thenReturn(githubConfig);
    when(githubConfig.writeRetryBudget()).thenReturn(Duration.ofMinutes(5));
    when(suggestionFormatter.formatReviewComment(any(), anyBoolean(), anyInt()))
        .thenAnswer(call -> "finding " + call.<Finding>getArgument(0).line());
    publisher =
        new ReviewPublisher(
            reviewClient,
            commentClient,
            threadService,
            suggestionFormatter,
            mock(FollowUpAnalyzer.class),
            mock(PrLabeler.class),
            config,
            BotIdentity.of("thrillhousebot"),
            new PendingReviewGuard());
  }

  private static Finding finding(RiskLevel risk, Confidence confidence, String file, int line) {
    return new Finding(risk, confidence, file, line, "T" + line, "d", null, null);
  }

  private static ReviewResult result(List<Finding> findings, List<Finding> openPrevious) {
    return new ReviewResult(
        findings,
        0,
        0,
        0,
        0,
        null,
        ReviewState.COMMENT,
        true,
        "summary",
        List.of(),
        List.of(),
        0,
        false,
        true,
        null,
        0,
        openPrevious,
        false);
  }

  /** {@code thread} by entry line. */
  private Map<Integer, Boolean> threads(ReviewResult result, DiffLineResolver resolver)
      throws Exception {
    publisher.publishSummary(
        new ReviewPublisher.SummaryRequest(
            "auth", "o", "r", 1, result, false, null, resolver, null));
    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(anyString(), anyString(), eq("o"), eq("r"), eq(1), body.capture());
    var matcher = LINE.matcher(body.getValue().body());
    assertTrue(matcher.find(), body.getValue().body());
    var byLine = new HashMap<Integer, Boolean>();
    for (JsonNode entry : new ObjectMapper().readTree(matcher.group(1))) {
      byLine.put(entry.get("line").asInt(), entry.get("thread").asBoolean());
    }
    return byLine;
  }

  @Test
  void onlyInlineFindingsOnAChangedFileWithinTheCapExpectAThread() throws Exception {
    var changed = new DiffLineResolver(Map.of("src/A.java", PATCH));
    var threads =
        threads(
            result(
                List.of(
                    finding(RiskLevel.HIGH, Confidence.HIGH, "src/Other.java", 1),
                    finding(RiskLevel.HIGH, Confidence.HIGH, "src/A.java", 2),
                    finding(RiskLevel.MEDIUM, Confidence.LOW, "src/A.java", 3),
                    finding(RiskLevel.HIGH, Confidence.HIGH, "src/A.java", 4),
                    finding(RiskLevel.HIGH, Confidence.HIGH, "src/A.java", 5)),
                List.of(finding(RiskLevel.HIGH, Confidence.HIGH, "src/Gone.java", 9))),
            changed);

    assertEquals(
        Map.of(1, false, 2, true, 3, false, 4, true, 5, false, 9, true),
        threads,
        "outside the diff, listed only and past the cap of two expect no thread; a carried"
            + " finding keeps its inline routing");
  }

  @Test
  void withoutTheDiffEveryInlineFindingExpectsAThread() throws Exception {
    var threads =
        threads(
            result(
                List.of(
                    finding(RiskLevel.HIGH, Confidence.HIGH, "src/Other.java", 1),
                    finding(RiskLevel.MEDIUM, Confidence.LOW, "src/A.java", 3)),
                List.of()),
            null);

    assertEquals(Map.of(1, true, 3, false), threads);
  }

  private static Map<Integer, Boolean> threadsIn(String manifestOrBody, boolean wholeLine)
      throws Exception {
    var entries = manifestOrBody;
    if (wholeLine) {
      var matcher = LINE.matcher(manifestOrBody);
      assertTrue(matcher.find(), manifestOrBody);
      entries = matcher.group(1);
    }
    var byLine = new HashMap<Integer, Boolean>();
    for (JsonNode entry : new ObjectMapper().readTree(entries)) {
      byLine.put(entry.get("line").asInt(), entry.get("thread").asBoolean());
    }
    return byLine;
  }

  private static ReviewResult threeOnTheChangedFile() {
    return result(
        List.of(
            finding(RiskLevel.HIGH, Confidence.HIGH, "src/A.java", 2),
            finding(RiskLevel.HIGH, Confidence.HIGH, "src/A.java", 4),
            finding(RiskLevel.HIGH, Confidence.HIGH, "src/A.java", 5)),
        List.of());
  }

  /** Publishes the summary, then has the conversation return it as the bot's comment. */
  private String publishedSummary(ReviewResult result, DiffLineResolver resolver) {
    publisher.publishSummary(
        new ReviewPublisher.SummaryRequest(
            "auth", "o", "r", 1, result, false, null, resolver, null));
    var body = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .createComment(anyString(), anyString(), eq("o"), eq("r"), eq(1), body.capture());
    summaryOnThePullRequest(body.getValue().body());
    return body.getValue().body();
  }

  private void summaryOnThePullRequest(String body) {
    when(commentClient.listComments(anyString(), anyString(), eq("o"), eq("r"), eq(1)))
        .thenReturn(
            List.of(
                new GitHubCommentClient.IssueComment(
                    SUMMARY_ID,
                    body,
                    new GitHubReviewClient.ReviewResponse.User("thrillhousebot"))));
  }

  /** GitHub refuses every route of the finding on {@code line}. */
  private void refuseTheFindingOn(int line) {
    when(reviewClient.createPullRequestComment(
            any(),
            any(),
            any(),
            any(),
            anyInt(),
            argThat(comment -> comment != null && comment.body().contains("finding " + line))))
        .thenThrow(new WebApplicationException(422));
  }

  private ReviewPublisher.PostedThreads post(ReviewResult result, DiffLineResolver resolver) {
    return publisher.postReview(
        new ReviewPublisher.PostReviewRequest(
            "auth", "o", "r", 1, "sha", result, resolver, true, List.of()));
  }

  private String editedSummary() {
    var edit = ArgumentCaptor.forClass(GitHubCommentClient.CreateCommentRequest.class);
    verify(commentClient)
        .updateComment(anyString(), anyString(), eq("o"), eq("r"), eq(SUMMARY_ID), edit.capture());
    return edit.getValue().body();
  }

  // #1465 review: a refused post frees a cap slot, so the threads opened differ from the ones the
  // summary announced; the manifest is corrected once the review has posted.
  @Test
  void aRefusedThreadIsCorrectedInTheManifestAfterTheReviewPosts() throws Exception {
    var resolver = new DiffLineResolver(Map.of("src/A.java", LONG_PATCH));
    var result = threeOnTheChangedFile();
    var summary = publishedSummary(result, resolver);
    assertEquals(Map.of(2, true, 4, true, 5, false), threadsIn(summary, true));
    refuseTheFindingOn(2);

    var posted = post(result, resolver);

    var edited = editedSummary();
    assertEquals(Map.of(2, false, 4, true, 5, true), threadsIn(edited, true));
    assertEquals(
        LINE.matcher(summary).replaceAll(""),
        LINE.matcher(edited).replaceAll(""),
        "only the manifest line changes");
    assertEquals(
        Map.of(2, false, 4, true, 5, true),
        threadsIn(publisher.openFindings(result, posted).entries(), false));
  }

  @Test
  void aRoundWhoseThreadsLandedAsAnnouncedReadsAndEditsNothingMore() throws Exception {
    var resolver = new DiffLineResolver(Map.of("src/A.java", LONG_PATCH));
    var result = threeOnTheChangedFile();
    publishedSummary(result, resolver);

    var posted = post(result, resolver);

    verify(commentClient, never()).listComments(any(), any(), any(), any(), anyInt());
    verify(commentClient, never()).updateComment(any(), any(), any(), any(), anyLong(), any());
    assertEquals(
        Map.of(2, true, 4, true, 5, false),
        threadsIn(publisher.openFindings(result, posted).entries(), false));
  }

  @Test
  void aSummaryThatNoLongerCarriesTheAnnouncedManifestIsLeftAlone() {
    var resolver = new DiffLineResolver(Map.of("src/A.java", LONG_PATCH));
    var result = threeOnTheChangedFile();
    publishedSummary(result, resolver);
    summaryOnThePullRequest(
        PrSummaryGenerator.SUMMARY_MARKER + "\n<!-- thrillhousebot:findings v=1 [] -->\nnewer");
    refuseTheFindingOn(2);

    post(result, resolver);

    verify(commentClient, never()).updateComment(any(), any(), any(), any(), anyLong(), any());
  }

  @Test
  void aPullRequestWithNoSummaryCommentIsLeftAlone() {
    var resolver = new DiffLineResolver(Map.of("src/A.java", LONG_PATCH));
    refuseTheFindingOn(2);

    post(threeOnTheChangedFile(), resolver);

    verify(commentClient, never()).updateComment(any(), any(), any(), any(), anyLong(), any());
  }

  @Test
  void aFailedManifestEditCostsNothingElse() throws Exception {
    var resolver = new DiffLineResolver(Map.of("src/A.java", LONG_PATCH));
    var result = threeOnTheChangedFile();
    publishedSummary(result, resolver);
    refuseTheFindingOn(2);
    when(commentClient.updateComment(any(), any(), any(), any(), anyLong(), any()))
        .thenThrow(new WebApplicationException(502));

    var posted = post(result, resolver);

    // The summary was created once, by publishSummary; the failed edit posts no second one.
    verify(commentClient).createComment(any(), any(), any(), any(), anyInt(), any());
    assertEquals(
        Map.of(2, false, 4, true, 5, true),
        threadsIn(publisher.openFindings(result, posted).entries(), false));
  }

  @Test
  void aBatchedReviewThatCouldNotBeSubmittedLeavesNoThread() throws Exception {
    when(reviewConfig.batchedInlineReviewEnabled()).thenReturn(true);
    when(reviewClient.createPendingReview(any(), any(), any(), any(), anyInt(), any()))
        .thenReturn(
            new GitHubReviewClient.ReviewResponse(99L, "", "PENDING", "sha", null, "PRR_node"));
    when(threadService.addPendingReviewThread(any(), any(), any(), anyInt(), any(), any()))
        .thenReturn("PRRT_1");
    when(reviewClient.submitReview(any(), any(), any(), any(), anyInt(), anyLong(), any()))
        .thenThrow(new WebApplicationException(422));
    var resolver = new DiffLineResolver(Map.of("src/A.java", LONG_PATCH));
    var result = threeOnTheChangedFile();
    publishedSummary(result, resolver);

    var posted = post(result, resolver);

    assertEquals(Map.of(2, false, 4, false, 5, false), threadsIn(editedSummary(), true));
    assertEquals(
        Map.of(2, false, 4, false, 5, false),
        threadsIn(publisher.openFindings(result, posted).entries(), false));
  }
}
