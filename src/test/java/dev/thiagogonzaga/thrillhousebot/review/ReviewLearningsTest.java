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

import static dev.thiagogonzaga.thrillhousebot.review.FollowUpAnalyzerLearningsTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubInstallationClient;
import dev.thiagogonzaga.thrillhousebot.review.ReviewLearningService.LearningInput;
import dev.thiagogonzaga.thrillhousebot.review.ReviewLearningService.LearningView;
import dev.thiagogonzaga.thrillhousebot.review.ReviewLearningService.RecordOutcome;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewLearningsTest {

  private final ReviewLearningService store = mock(ReviewLearningService.class);
  private final GitHubInstallationClient installationClient = mock(GitHubInstallationClient.class);
  private final FollowUpAnalyzer analyzer = new FollowUpAnalyzer(new ObjectMapper());

  private ReviewLearnings learnings(boolean enabled, int maxItems, int maxChars) {
    return new ReviewLearnings(
        store,
        analyzer,
        BOT_ID,
        installationClient,
        new ReviewLearnings.Settings(enabled, 100, maxItems, maxChars));
  }

  private ReviewLearnings enabled() {
    return learnings(true, 10, 3000);
  }

  private static LearningView view(long id, String kind, String path, String text, long age) {
    return new LearningView(
        id,
        kind,
        ReviewLearning.KIND_DECLINE.equals(kind) ? "Title " + id : null,
        ReviewLearning.KIND_DECLINE.equals(kind) ? "medium" : null,
        path,
        text,
        159,
        "https://github.com/o/r/pull/159#discussion_r" + id,
        "maintainer",
        Instant.parse("2026-09-01T00:00:00Z").minusSeconds(age),
        true,
        null,
        null);
  }

  private void writeAccess(String level) {
    when(installationClient.collaboratorPermission(any(), any(), any(), any(), any()))
        .thenReturn(new GitHubInstallationClient.CollaboratorPermission(level, null));
  }

  // --- config wiring ---

  @Test
  void theProductionConstructorReadsTheLearningsConfig() {
    var config = mock(ThrillhouseConfig.class, RETURNS_DEEP_STUBS);
    when(config.review().learnings().enabled()).thenReturn(true);
    when(config.review().learnings().maxPerRepo()).thenReturn(7);

    var built = new ReviewLearnings(store, analyzer, BOT_ID, installationClient, config);

    assertTrue(built.enabled());
    assertEquals(7, built.maxPerRepo());
  }

  // --- recall ---

  @Test
  void configOffInjectsNothingAndNeverReadsTheStore() {
    assertEquals("", learnings(false, 10, 3000).promptSection(1L, "o", "r", List.of("a.java")));
    verifyNoInteractions(store);
  }

  @Test
  void theSectionIsReadForThisInstallationAndRepositoryOnly() {
    when(store.listActive(1L, "o/r", 100))
        .thenReturn(List.of(view(4, ReviewLearning.KIND_DECLINE, "src/A.java", "flat", 0)));

    var section = enabled().promptSection(1L, "o", "r", List.of("src/A.java"));

    assertEquals(
        "- [L4] Declined finding \"Title 4\" (medium) on src/A.java — @maintainer, PR #159:\n"
            + "  flat",
        section);
    verify(store).listActive(1L, "o/r", 100);
  }

  @Test
  void aStoreFailureLeavesTheReviewWithoutLearnings() {
    when(store.listActive(anyLong(), anyString(), anyInt()))
        .thenThrow(new IllegalStateException("db down"));

    assertEquals("", enabled().promptSection(1L, "o", "r", List.of("a.java")));
  }

  @Test
  void relevanceRanksTheChangedFileThenItsDirectoryAndConventionsThenItsType() {
    var sameFile = view(1, ReviewLearning.KIND_DECLINE, "src/review/A.java", "same file", 50);
    var sameDir = view(2, ReviewLearning.KIND_DECLINE, "src/review/B.java", "same dir", 0);
    var convention = view(3, ReviewLearning.KIND_CONVENTION, null, "repo wide", 10);
    var sameType = view(4, ReviewLearning.KIND_DECLINE, "src/other/C.java", "same type", 0);
    var unrelated = view(5, ReviewLearning.KIND_DECLINE, "docs/guide.md", "unrelated", 0);
    var noExtension = view(6, ReviewLearning.KIND_DECLINE, "Makefile", "no ext", 0);
    var blankPath = view(7, ReviewLearning.KIND_CONVENTION, " ", "blank path", 20);

    var chosen =
        ReviewLearnings.select(
            List.of(unrelated, sameType, convention, sameDir, sameFile, noExtension, blankPath),
            List.of("src/review/A.java", "docker/Dockerfile"),
            10,
            10_000);

    assertEquals(
        List.of(1L, 2L, 3L, 7L, 4L), chosen.stream().map(LearningView::id).toList(), "" + chosen);
  }

  @Test
  void noChangedPathsLeavesOnlyConventions() {
    var chosen =
        ReviewLearnings.select(
            List.of(
                view(1, ReviewLearning.KIND_DECLINE, "a/B.java", "x", 0),
                view(2, ReviewLearning.KIND_CONVENTION, null, "y", 0)),
            null,
            10,
            10_000);

    assertEquals(List.of(2L), chosen.stream().map(LearningView::id).toList());
  }

  @Test
  void theCountAndCharacterCapsBoundTheSection() {
    var active = new ArrayList<LearningView>();
    for (long i = 1; i <= 20; i++) {
      active.add(view(i, ReviewLearning.KIND_CONVENTION, null, "rule " + i, i));
    }

    assertEquals(3, ReviewLearnings.select(active, List.of(), 3, 10_000).size());
    var byChars = ReviewLearnings.select(active, List.of(), 50, 200);
    var rendered = ReviewLearnings.render(byChars);
    assertTrue(rendered.length() <= 200, rendered);
    assertFalse(byChars.isEmpty());
  }

  @Test
  void aLongLearningIsSkippedWithoutCrowdingOutShorterOnes() {
    var huge = view(1, ReviewLearning.KIND_CONVENTION, null, "x".repeat(900), 0);
    var small = view(2, ReviewLearning.KIND_CONVENTION, null, "short", 10);

    var chosen = ReviewLearnings.select(List.of(huge, small), List.of(), 10, 300);

    assertEquals(List.of(2L), chosen.stream().map(LearningView::id).toList());
  }

  @Test
  void aStoredCredentialIsNeverReplayed() {
    var leaked = view(1, ReviewLearning.KIND_CONVENTION, null, "use ghp_abcdefghijklmnop1234", 0);

    var leakedTitle =
        new LearningView(
            2,
            ReviewLearning.KIND_DECLINE,
            "hardcoded AKIAABCDEFGHIJKLMNOP",
            null,
            "a/B.java",
            "fine",
            1,
            "u",
            "m",
            Instant.EPOCH,
            true,
            null,
            null);
    var leakedPath =
        new LearningView(
            3,
            ReviewLearning.KIND_DECLINE,
            "t",
            null,
            "keys/ghp_abcdefghijklmnop1234.txt",
            "fine",
            1,
            "u",
            "m",
            Instant.EPOCH,
            true,
            null,
            null);

    assertTrue(
        ReviewLearnings.select(
                List.of(leaked, leakedTitle, leakedPath), List.of("a/B.java"), 10, 10_000)
            .isEmpty());
  }

  @Test
  void aConventionAndADeclineWithoutRiskRenderTheirOwnHeads() {
    var convention = view(3, ReviewLearning.KIND_CONVENTION, null, "Prefer records.", 0);
    var riskless =
        new LearningView(
            9,
            ReviewLearning.KIND_DECLINE,
            "t",
            null,
            "a/B.java",
            "why",
            2,
            "u",
            "m",
            Instant.EPOCH,
            true,
            null,
            null);

    assertEquals(
        "- [L3] Convention on the whole repository — @maintainer, PR #159:\n  Prefer records.",
        ReviewLearnings.entry(convention));
    assertEquals(
        "- [L9] Declined finding \"t\" on a/B.java — @m, PR #2:\n  why",
        ReviewLearnings.entry(riskless));
    assertEquals("", ReviewLearnings.render(List.of()));
  }

  // --- capture ---

  private ReviewLearnings.DeclineCapture capture(
      ReviewResponse.Finding finding,
      String status,
      List<dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient.PullRequestComment> comments,
      String code) {
    return new ReviewLearnings.DeclineCapture(
        "token",
        1L,
        "o",
        "r",
        161,
        List.of(finding),
        List.of(new ReviewResult.PreviousFindingStatus(1, status, "note")),
        comments,
        List.of(),
        () -> code);
  }

  @Test
  void thePr159DeclineBecomesALearningCitingItsThreadReply() {
    writeAccess("write");
    when(store.save(any(), anyInt())).thenReturn(RecordOutcome.STORED);

    var stored =
        enabled()
            .captureSurvivingDeclines(
                capture(NESTED, "justified", thread(NESTED, 500L, FLAT_THREADS), PLAIN_DIFF));

    assertEquals(1, stored);
    var input = ArgumentCaptor.forClass(LearningInput.class);
    verify(store).save(input.capture(), eq(100));
    var learning = input.getValue();
    assertEquals(1L, learning.installationId());
    assertEquals("o/r", learning.repository());
    assertEquals(ReviewLearning.KIND_DECLINE, learning.kind());
    assertEquals(NESTED_TITLE, learning.findingTitle());
    assertEquals("medium", learning.findingRisk());
    assertEquals(NESTED_FILE, learning.path());
    assertEquals(FLAT_THREADS, learning.text());
    assertEquals(161, learning.sourcePrNumber());
    assertEquals("https://github.com/o/r/pull/161#discussion_r501", learning.sourceUrl());
    assertEquals("maintainer", learning.author());
  }

  @Test
  void aConversationDeclineCitesTheIssueComment() {
    writeAccess("admin");
    when(store.save(any(), anyInt())).thenReturn(RecordOutcome.STORED);
    var directive =
        "@thrillhousebot declined `"
            + NESTED_FILE
            + ":156` — "
            + NESTED_TITLE
            + "\n\n"
            + FLAT_THREADS;

    enabled()
        .captureSurvivingDeclines(
            new ReviewLearnings.DeclineCapture(
                "token",
                1L,
                "o",
                "r",
                161,
                List.of(NESTED),
                List.of(new ReviewResult.PreviousFindingStatus(1, "justified", "n")),
                List.of(),
                List.of(conversation(902L, directive)),
                () -> PLAIN_DIFF));

    var input = ArgumentCaptor.forClass(LearningInput.class);
    verify(store).save(input.capture(), anyInt());
    assertEquals("https://github.com/o/r/pull/161#issuecomment-902", input.getValue().sourceUrl());
  }

  @Test
  void thePr160DeclineTheRecheckContradictedIsNeverCaptured() {
    // The re-check reopened the finding, so the round's final status is "unresolved".
    writeAccess("write");
    var comments = thread(RACE, 700L, ASYNC_AFTER_ACK);
    var statuses =
        analyzer.recheckDeclines(
            List.of(RACE),
            List.of(new ReviewResponse.PreviousFindingStatus(1, "justified", "declined")),
            comments,
            List.of(),
            BOT_ID,
            () -> DISPATCHING_DIFF);
    assertEquals("unresolved", statuses.get(0).status());

    var stored =
        enabled()
            .captureSurvivingDeclines(
                capture(RACE, statuses.get(0).status(), comments, DISPATCHING_DIFF));

    assertEquals(0, stored);
    verifyNoInteractions(store);
  }

  @Test
  void thePr160DeclineThatStoodThisRoundIsStillNotCaptured() {
    writeAccess("write");

    var stored =
        enabled()
            .captureSurvivingDeclines(
                capture(RACE, "justified", thread(RACE, 700L, ASYNC_AFTER_ACK), PLAIN_DIFF));

    assertEquals(0, stored);
    verifyNoInteractions(store);
  }

  @Test
  void aDeclineFromSomeoneWithoutWriteAccessIsNotCaptured() {
    writeAccess("read");

    assertEquals(
        0,
        enabled()
            .captureSurvivingDeclines(
                capture(NESTED, "justified", thread(NESTED, 500L, FLAT_THREADS), PLAIN_DIFF)));
    verify(store, never()).save(any(), anyInt());
  }

  @Test
  void aJoinedReasonIsStoredOnlyWhenEveryContributorHoldsWriteAccess() {
    var comments = new java.util.ArrayList<>(thread(NESTED, 500L, FLAT_THREADS));
    comments.add(comment(502L, 500L, NESTED_FILE, "See the REST docs.", "member", "MEMBER"));
    when(installationClient.collaboratorPermission(any(), any(), any(), any(), eq("maintainer")))
        .thenReturn(new GitHubInstallationClient.CollaboratorPermission("write", null));
    when(installationClient.collaboratorPermission(any(), any(), any(), any(), eq("member")))
        .thenReturn(new GitHubInstallationClient.CollaboratorPermission("read", null));

    assertEquals(
        0, enabled().captureSurvivingDeclines(capture(NESTED, "justified", comments, PLAIN_DIFF)));
    verify(store, never()).save(any(), anyInt());
  }

  @Test
  void anUnreadablePermissionIsTreatedAsNoAccess() {
    when(installationClient.collaboratorPermission(any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("404"));
    assertEquals(
        0,
        enabled()
            .captureSurvivingDeclines(
                capture(NESTED, "justified", thread(NESTED, 500L, FLAT_THREADS), PLAIN_DIFF)));

    reset(installationClient);
    when(installationClient.collaboratorPermission(any(), any(), any(), any(), any()))
        .thenReturn(null);
    assertEquals(
        0,
        enabled()
            .captureSurvivingDeclines(
                capture(NESTED, "justified", thread(NESTED, 500L, FLAT_THREADS), PLAIN_DIFF)));

    reset(installationClient);
    when(installationClient.collaboratorPermission(any(), any(), any(), any(), any()))
        .thenReturn(new GitHubInstallationClient.CollaboratorPermission(null, null));
    assertEquals(
        0,
        enabled()
            .captureSurvivingDeclines(
                capture(NESTED, "justified", thread(NESTED, 500L, FLAT_THREADS), PLAIN_DIFF)));
    verify(store, never()).save(any(), anyInt());
  }

  @Test
  void aRefusedRecordIsNotCounted() {
    writeAccess("maintain");
    when(store.save(any(), anyInt())).thenReturn(RecordOutcome.REFUSED_CAP);

    assertEquals(
        0,
        enabled()
            .captureSurvivingDeclines(
                capture(NESTED, "justified", thread(NESTED, 500L, FLAT_THREADS), PLAIN_DIFF)));
  }

  @Test
  void nothingIsCapturedWhenOffOrNothingWasJustified() {
    var comments = thread(NESTED, 500L, FLAT_THREADS);

    assertEquals(
        0,
        learnings(false, 10, 3000)
            .captureSurvivingDeclines(capture(NESTED, "justified", comments, PLAIN_DIFF)));
    assertEquals(
        0, enabled().captureSurvivingDeclines(capture(NESTED, "resolved", comments, PLAIN_DIFF)));
    assertEquals(
        0,
        enabled()
            .captureSurvivingDeclines(
                new ReviewLearnings.DeclineCapture(
                    "t", 1L, "o", "r", 1, List.of(), null, List.of(), List.of(), () -> "")));
    assertEquals(
        0,
        enabled()
            .captureSurvivingDeclines(
                new ReviewLearnings.DeclineCapture(
                    "t", 1L, "o", "r", 1, null, null, null, null, () -> "")));
    verifyNoInteractions(store, installationClient);
  }

  @Test
  void aCaptureCopiesItsListsAndTreatsMissingOnesAsEmpty() {
    var capture =
        new ReviewLearnings.DeclineCapture("t", 1L, "o", "r", 1, null, null, null, null, null);

    assertTrue(capture.previous().isEmpty());
    assertTrue(capture.statuses().isEmpty());
    assertTrue(capture.inlineComments().isEmpty());
    assertTrue(capture.conversationComments().isEmpty());
  }

  // --- governance ---

  @Test
  void aConventionIsStoredWithItsCommentLink() {
    when(store.save(any(), anyInt())).thenReturn(RecordOutcome.STORED);

    assertEquals(
        RecordOutcome.STORED,
        enabled().rememberConvention(1L, "o", "r", 7, 99L, "maintainer", "Prefer records."));

    var input = ArgumentCaptor.forClass(LearningInput.class);
    verify(store).save(input.capture(), eq(100));
    assertEquals(ReviewLearning.KIND_CONVENTION, input.getValue().kind());
    assertNull(input.getValue().path());
    assertEquals("https://github.com/o/r/pull/7#issuecomment-99", input.getValue().sourceUrl());
  }

  @Test
  void listAndForgetAreScopedToTheRepository() {
    when(store.listActive(1L, "o/r", 100)).thenReturn(List.of());
    when(store.retract(1L, "o/r", 5L, "admin"))
        .thenReturn(ReviewLearningService.RetractOutcome.RETRACTED);

    assertTrue(enabled().list(1L, "o", "r").isEmpty());
    assertEquals(
        ReviewLearningService.RetractOutcome.RETRACTED,
        enabled().forget(1L, "o", "r", 5L, "admin"));
  }
}
