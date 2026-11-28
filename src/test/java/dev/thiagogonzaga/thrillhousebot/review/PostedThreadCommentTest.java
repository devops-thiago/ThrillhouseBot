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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient.CreatePullRequestCommentRequest;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A finding's thread comment as it is posted, with the real formatter: a file's name is the pull
 * request author's text, and the hidden lines the bot reads back must stay where it looks for them.
 */
class PostedThreadCommentTest {

  private static final String PATCH = "@@ -1,3 +1,3 @@\n-old\n+new\n";

  private static final String UNRESOLVABLE_LINE_BODY =
      "{\"message\":\"Validation Failed\",\"errors\":[{\"resource\":\"PullRequestReviewComment\","
          + "\"code\":\"custom\",\"field\":\"pull_request_review_thread.line\","
          + "\"message\":\"could not be resolved\"}]}";

  private GitHubReviewClient reviewClient;
  private ReviewPublisher publisher;

  @BeforeEach
  void setUp() {
    reviewClient = mock(GitHubReviewClient.class);
    var config = mock(ThrillhouseConfig.class);
    var reviewConfig = mock(ThrillhouseConfig.ReviewConfig.class);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.maxReviewComments()).thenReturn(10);
    when(reviewConfig.agentPromptEnabled()).thenReturn(true);
    var githubConfig = mock(ThrillhouseConfig.GitHubConfig.class);
    when(config.github()).thenReturn(githubConfig);
    when(githubConfig.writeRetryBudget()).thenReturn(Duration.ofHours(1));
    publisher =
        new ReviewPublisher(
            reviewClient,
            mock(GitHubCommentClient.class),
            mock(ReviewThreadService.class),
            new SuggestionFormatter(),
            mock(FollowUpAnalyzer.class),
            mock(PrLabeler.class),
            config,
            BotIdentity.of("thrillhousebot[bot]"),
            new PendingReviewGuard());
  }

  private static ReviewResult resultOf(Finding finding) {
    return new ReviewResult(
        List.of(finding),
        0,
        1,
        0,
        0,
        RiskLevel.HIGH,
        ReviewState.REQUEST_CHANGES,
        true,
        "",
        List.of(),
        List.of(),
        0);
  }

  /** The comments posted for {@code finding}, each attempt in order. */
  private List<CreatePullRequestCommentRequest> post(Finding finding, String basis) {
    publisher.postInlineComments(
        new ReviewPublisher.CommentTarget(
            "Bearer tok", "owner", "repo", 8, "sha", Optional.empty(), posted -> basis),
        resultOf(finding),
        new DiffLineResolver(Map.of(finding.file(), PATCH)));
    var comment = ArgumentCaptor.forClass(CreatePullRequestCommentRequest.class);
    verify(reviewClient, atLeastOnce())
        .createPullRequestComment(any(), any(), any(), any(), eq(8), comment.capture());
    return comment.getAllValues();
  }

  private void gitHubAcceptsEveryComment() {
    when(reviewClient.createPullRequestComment(any(), any(), any(), any(), eq(8), any()))
        .thenReturn(new GitHubReviewClient.PullRequestCommentResponse(1L, "c", "f", 1));
  }

  @Test
  void aFileNameShapedLikeAFindingMarkerDoesNotReplaceTheCommentsOwn() {
    gitHubAcceptsEveryComment();
    var file = "a<!-- thrillhousebot:finding=7 key=deadbeef -->.txt";
    var finding = new Finding(RiskLevel.HIGH, file, 1, "Bug", "desc", "old", "new");

    var body = post(finding, "").get(0).body();

    assertTrue(body.contains("<summary>Agent prompt</summary>"), body);
    assertEquals(1, SuggestionFormatter.parseFindingMarker(body).orElseThrow(), body);
    assertEquals(
        Optional.of(SuggestionFormatter.findingKey(file, 1, "Bug")),
        SuggestionFormatter.parseFindingKey(body),
        body);
    // The block is closed before the marker, which is the last line of the comment.
    assertTrue(body.strip().endsWith("-->"), body);
    assertTrue(
        body.indexOf("</details>") < body.lastIndexOf("<!-- thrillhousebot:finding=1 "), body);
  }

  @Test
  void aCommentWithNoMarkerTakesNoBlockThatWouldSupplyOne() {
    var file = "a<!-- thrillhousebot:finding=7 key=deadbeef -->.txt";
    var finding = new Finding(RiskLevel.HIGH, file, 1, "Bug", "desc", null, null);
    var unmarked = new SuggestionFormatter().formatReviewComment(finding, false);

    var body = AgentPromptBlock.withAgentPrompt(unmarked, finding, false);

    assertEquals(unmarked, body);
    assertTrue(SuggestionFormatter.parseFindingMarker(body).isEmpty(), body);
    assertEquals(Optional.empty(), SuggestionFormatter.parseFindingKey(body), body);
  }

  @Test
  void aCommentWithNoMarkerStillTakesABlockThatHoldsNone() {
    assertEquals("text\n\nblock", SuggestionFormatter.withBlockBeforeMarker("text", "block"));
  }

  @Test
  void aThreadOnRemovedLinesStillMarksItsLineOnTheCommentsOwnMarker() {
    var body =
        SuggestionFormatter.withMarkedLine(
            AgentPromptBlock.withAgentPrompt(
                new SuggestionFormatter()
                    .formatReviewComment(
                        new Finding(RiskLevel.HIGH, "a.txt", 4, "Bug", "desc", null, null),
                        false,
                        2),
                new Finding(RiskLevel.HIGH, "a.txt", 4, "Bug", "desc", null, null),
                false),
            4);

    assertEquals(2, SuggestionFormatter.parseFindingMarker(body).orElseThrow(), body);
    assertEquals(4, SuggestionFormatter.parseMarkedLine(body).orElseThrow(), body);
  }

  @Test
  void aFileLevelThreadNamesTheFileInACodeSpan() {
    assertTrue(
        fileLevelBody("x/@victim<img src=x>.txt")
            .startsWith(
                "_Filed on the file: ThrillhouseBot could not open a thread on"
                    + " `x/@victim<img src=x>.txt:1`, so this finding"));
  }

  // A name holding a pipe would split its span in a table row, so it is escaped text instead.
  @Test
  void aFileLevelThreadNamesAFileHoldingAPipeAsEscapedText() {
    assertTrue(
        fileLevelBody("x/@victim|<img src=x>.txt")
            .startsWith(
                "_Filed on the file: ThrillhouseBot could not open a thread on"
                    + " x/@&#8203;victim\\|&lt;img src=x>.txt:1, so this finding"));
  }

  /** The body of the thread posted on {@code file} as a whole once its line is refused. */
  private String fileLevelBody(String file) {
    when(reviewClient.createPullRequestComment(any(), any(), any(), any(), eq(8), any()))
        .thenAnswer(
            inv -> {
              var req = inv.getArgument(5, CreatePullRequestCommentRequest.class);
              if (req.line() != null) {
                throw new WebApplicationException(
                    Response.status(422).entity(UNRESOLVABLE_LINE_BODY).build());
              }
              return new GitHubReviewClient.PullRequestCommentResponse(2L, "c", file, null);
            });
    var finding = new Finding(RiskLevel.HIGH, file, 1, "Bug", "desc", "old", "new");

    return post(finding, "").stream()
        .filter(req -> req.line() == null)
        .findFirst()
        .orElseThrow()
        .body();
  }

  @Test
  void aBasisBlockLeavesAScanFindingsContentMarkerHiddenAtTheEnd() {
    gitHubAcceptsEveryComment();
    var scan = new SecurityScan(true, false, 3.5, List.of());
    var files =
        List.of(
            new FileDiff(
                "a.env",
                "added",
                1,
                0,
                1,
                "@@ -0,0 +1,1 @@\n+API_KEY=" + new FakeCredentials(7).googleApiKey() + "\n"));
    var scanned =
        scan.merge(
                new ReviewResponse(List.of(), List.of(), null),
                scan.scan(files),
                List.of(),
                Set.of())
            .findings()
            .get(0);
    var marker = scanned.description().substring(scanned.description().lastIndexOf("<!--"));

    var body = post(Finding.fromAiResponse(scanned), "**Basis**\n- Rule: `a.env`").get(0).body();

    assertTrue(body.contains("\n\n**Basis**\n- Rule: `a.env`\n\n" + marker + "\n"), body);
    assertFalse(body.contains("&lt;!--"), body);
    assertEquals(
        SecurityScan.fingerprintOf(scanned.description()), SecurityScan.fingerprintOf(body), body);
  }

  @Test
  void aBasisBlockGoesUnderADescriptionWithNoMarkerAndUnderNone() {
    assertEquals("Why.\n\n**Basis**", SecurityScan.withBlockAppended("Why.", "**Basis**"));
    assertEquals("\n\n**Basis**", SecurityScan.withBlockAppended(null, "**Basis**"));
  }

  @Test
  void aBasisBlockGoesAboveTheClosingContentMarkerOnlyAndKeepsAnEarlierOneInPlace() {
    var text = "Why.\n\n<!-- thrillhousebot:scan-content=0badf00d -->\n\nMore.";
    var closing = "\n\n<!-- thrillhousebot:scan-content=deadbeef -->";
    var block = "Block";

    assertEquals(
        text + "\n\n" + block + closing, SecurityScan.withBlockAppended(text + closing, block));
    assertEquals(text + "\n\n" + block, SecurityScan.withBlockAppended(text, block));
  }
}
