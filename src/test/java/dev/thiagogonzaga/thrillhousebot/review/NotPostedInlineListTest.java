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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The review body's list of findings that got no inline thread (#1438): how each bullet renders,
 * where its header sends the reader, and what a later round reads back from it.
 */
class NotPostedInlineListTest {

  private static final String LOW_NOTE = SuggestionFormatter.confidenceDisclaimer(Confidence.LOW);

  private static final String LISTED_HEADER =
      "ThrillhouseBot noted 1 lower-confidence item(s), listed here and not posted as inline"
          + " threads:\n\n";

  private static final String SUMMARY_HEADER =
      "ThrillhouseBot noted 1 lower-confidence item(s) under **Things to double-check** in the PR"
          + " summary (not posted as inline threads):\n\n";

  private static final String DELTA =
      FollowUpDeltaSummary.DELTA_HEADING
          + "\n\n- **New findings this round:** 1\n- **Previous findings resolved:** 0\n"
          + "- **Previous findings still open:** 0";

  private final GitHubCommentClient commentClient = mock(GitHubCommentClient.class);

  private final GitHubReviewClient reviewClient = mock(GitHubReviewClient.class);

  private final ThrillhouseConfig config = mock(ThrillhouseConfig.class);

  private final ThrillhouseConfig.ReviewConfig reviewConfig =
      mock(ThrillhouseConfig.ReviewConfig.class);

  private final ThrillhouseConfig.GitHubConfig githubConfig =
      mock(ThrillhouseConfig.GitHubConfig.class);

  private final ReviewPublisher publisher =
      new ReviewPublisher(
          reviewClient,
          commentClient,
          mock(ReviewThreadService.class),
          mock(SuggestionFormatter.class),
          mock(FollowUpAnalyzer.class),
          mock(PrLabeler.class),
          config,
          BotIdentity.of("thrillhousebot"),
          new PendingReviewGuard());

  @BeforeEach
  void stubConfig() {
    when(config.review()).thenReturn(reviewConfig);
    when(config.github()).thenReturn(githubConfig);
    when(githubConfig.writeRetryBudget()).thenReturn(Duration.ofMinutes(5));
  }

  private static Finding finding(RiskLevel risk, Confidence confidence, String description) {
    return new Finding(
        risk, confidence, "src/A.java", 7, "Stub vacates the path", description, null, null);
  }

  private static Finding doubleCheck(String description) {
    return finding(RiskLevel.MEDIUM, Confidence.LOW, description);
  }

  private static ReviewResult result(
      List<Finding> findings, boolean firstReview, List<Finding> openPrevious) {
    return new ReviewResult(
        findings,
        0,
        0,
        0,
        0,
        null,
        ReviewState.COMMENT,
        firstReview,
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

  /** The body of the review posted for {@code findings}, none of which gets a thread. */
  private String postedBody(boolean summaryLists, String delta, Finding... findings) {
    publisher.postReview(
        new ReviewPublisher.PostReviewRequest(
            "auth",
            "o",
            "r",
            1,
            "sha",
            result(List.of(findings), false, List.of()),
            new DiffLineResolver(Map.of()),
            false,
            List.of(),
            delta,
            List.of(),
            f -> "",
            summaryLists));
    var review = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
    verify(reviewClient)
        .createReview(
            anyString(), anyString(), anyString(), anyString(), anyInt(), review.capture());
    return review.getValue().body();
  }

  private static String bullet(String risk, String note) {
    return "- **" + risk + ":** Stub vacates the path (`src/A.java:7`)" + note;
  }

  @Test
  void codeQuotedInADescriptionRendersAsAFencedBlockInsideTheBullet() {
    var description =
        "The test made the first lookup fail:\n"
            + "\n"
            + "  when(client.checkOrgMember(anyString()))\n"
            + "      .thenThrow(new WebApplicationException(403))\n"
            + "\n"
            + "The replacement stub succeeds first.";

    var body = postedBody(false, "", doubleCheck(description));

    assertEquals(
        LISTED_HEADER
            + bullet("MEDIUM", " " + LOW_NOTE)
            + "\n  The test made the first lookup fail:\n"
            + "  \n"
            + "  ```\n"
            + "  when(client.checkOrgMember(anyString()))\n"
            + "      .thenThrow(new WebApplicationException(403))\n"
            + "  ```\n"
            + "  \n"
            + "  The replacement stub succeeds first.\n",
        body);
  }

  @Test
  void aDescriptionThatOpensWithCodeRendersItAsOneBlockInsideTheBullet() {
    var body =
        postedBody(false, "", doubleCheck("\n    call();\n    result();\n\nBoth run twice."));

    assertEquals(
        LISTED_HEADER
            + bullet("MEDIUM", " " + LOW_NOTE)
            + "\n  ```\n  call();\n  result();\n  ```\n  \n  Both run twice.\n",
        body);
  }

  @Test
  void aFourSpaceBlockKeepsItsAnnotationsAndGenericsAsWritten() {
    var body =
        postedBody(
            false, "", doubleCheck("Add:\n\n    @Test\n    List<String> names() {}\n\nthen run."));

    assertTrue(
        body.contains(
            "  Add:\n  \n  ```\n  @Test\n  List<String> names() {}\n  ```\n  \n  then run."),
        body);
  }

  @Test
  void anUnbalancedFenceOrClosingTagCannotEscapeItsBullet() {
    var open = doubleCheck("Broken:\n\n```java\nint x = 1;\n</details>");
    var tag =
        finding(RiskLevel.LOW, Confidence.LOW, "stray </details> in prose, and a lone ``` run");

    var body = postedBody(false, DELTA, open, tag);

    assertEquals(
        LISTED_HEADER.replace("1 lower", "2 lower")
            + bullet("MEDIUM", " " + LOW_NOTE)
            + "\n  Broken:\n  \n  ```java\n  int x = 1;\n  </details>\n  ```\n"
            + bullet("LOW", " " + LOW_NOTE)
            + "\n  stray &lt;/details> in prose, and a lone &#96;&#96;&#96; run\n"
            + "\n\n"
            + DELTA,
        body);
  }

  @Test
  void aBareCarriageReturnCannotEndTheBullet() {
    var body = postedBody(false, "", doubleCheck("first\r\rsecond\r\nthird"));

    assertTrue(body.contains("\n  first\n  \n  second\n  third\n"), body);
  }

  @Test
  void theTitleAndPathAreNeutralisedAsTheSummaryDoesThem() {
    var hostile =
        new Finding(
            RiskLevel.MEDIUM,
            Confidence.LOW,
            "src/`A`\n.java",
            7,
            "Broken\n```\n</details>",
            null,
            null,
            null);

    var body = postedBody(false, "", hostile);

    assertEquals(
        LISTED_HEADER
            + "- **MEDIUM:** Broken &#96;&#96;&#96; &lt;/details> (`src/A .java:7`) "
            + LOW_NOTE
            + "\n",
        body);
  }

  @Test
  void capSkippedAndConfidenceSkippedBulletsRenderAlike() {
    var capped = finding(RiskLevel.MEDIUM, Confidence.MEDIUM, "Quoted:\n\n  call();");
    var certain = finding(RiskLevel.HIGH, Confidence.HIGH, null);
    var hedged = finding(RiskLevel.LOW, Confidence.LOW, "Quoted:\n\n  call();");

    var body = postedBody(false, "", capped, certain, hedged);

    var code = "\n  Quoted:\n  \n  ```\n  call();\n  ```\n";
    var mediumNote = " " + SuggestionFormatter.confidenceDisclaimer(Confidence.MEDIUM);
    assertTrue(
        body.contains(bullet("MEDIUM", mediumNote) + code + bullet("HIGH", "") + "\n"), body);
    assertTrue(body.contains(bullet("LOW", " " + LOW_NOTE) + code), body);
    assertTrue(body.contains("comment cap was reached"), body);
  }

  @Test
  void theHeaderNamesTheSummaryWhenItListsTheItems() {
    var body = postedBody(true, "", doubleCheck("d"));

    assertTrue(body.startsWith(SUMMARY_HEADER), body);
  }

  @Test
  void theListSurvivesBeingReadBackAsPreviousFindings() {
    var body = postedBody(false, DELTA, doubleCheck("Quoted:\n\n  call();"));
    var prior =
        new GitHubReviewClient.ReviewResponse(
            1L,
            body,
            "COMMENTED",
            "sha",
            new GitHubReviewClient.ReviewResponse.User("thrillhousebot"));

    var context =
        new FollowUpAnalyzer(new ObjectMapper())
            .buildPreviousFindingsContext(List.of(prior), BotIdentity.of("thrillhousebot"));

    assertEquals(
        (LISTED_HEADER
                + bullet("MEDIUM", " " + LOW_NOTE)
                + "\n  Quoted:\n  \n  ```\n  call();\n  ```")
            .strip(),
        context);
  }

  private ReviewPublisher.SummaryOutcome publishSummary(ReviewResult result) {
    return publisher.publishSummary(
        new ReviewPublisher.SummaryRequest("auth", "o", "r", 1, result, false, null, null, null));
  }

  @Test
  void aPostedSummaryListsTheRoundsDoubleCheckItems() {
    var outcome = publishSummary(result(List.of(doubleCheck("d")), true, List.of()));

    assertEquals(new ReviewPublisher.SummaryOutcome(true, true), outcome);
  }

  @Test
  void aSummaryEditedInPlaceListsThemThoughItDoesNotStandIn() {
    var existing =
        new GitHubCommentClient.IssueComment(
            9L,
            PrSummaryGenerator.SUMMARY_MARKER + "\nold",
            new GitHubReviewClient.ReviewResponse.User("thrillhousebot"));
    when(commentClient.listComments(anyString(), anyString(), anyString(), anyString(), anyInt()))
        .thenReturn(List.of(existing));

    var outcome = publishSummary(result(List.of(doubleCheck("d")), false, List.of()));

    assertEquals(new ReviewPublisher.SummaryOutcome(false, true), outcome);
    verify(commentClient)
        .updateComment(anyString(), anyString(), anyString(), anyString(), anyLong(), any());
  }

  @Test
  void anItemTheSummaryListsAtAnEarlierSeverityIsNotUnderDoubleCheck() {
    var earlier = finding(RiskLevel.HIGH, Confidence.LOW, "d");
    var again = finding(RiskLevel.LOW, Confidence.LOW, "d");

    var outcome = publishSummary(result(List.of(again), true, List.of(earlier)));

    assertEquals(new ReviewPublisher.SummaryOutcome(true, false), outcome);
  }
}
