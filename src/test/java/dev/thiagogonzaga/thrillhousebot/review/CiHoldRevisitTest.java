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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link CiHoldRevisit}: the CI-completion path that posts, keeps or drops a verdict
 * the strict CI gate held back (#825).
 */
class CiHoldRevisitTest {

  private static final String AUTH = "Bearer test";
  private static final String HEAD = "abc123";
  private static final String DETAILS_URL = "https://bot.example/session/x";
  private static final CiHoldRevisit.Recheck RECHECK =
      new CiHoldRevisit.Recheck("owner", "repo", 42, HEAD, 123L);

  private final GitHubAuthClient authClient = mock(GitHubAuthClient.class);
  private final ReviewContextLoader contextLoader = mock(ReviewContextLoader.class);
  private final CiStatusEvaluator ciStatusEvaluator = mock(CiStatusEvaluator.class);
  private final CheckRunManager checkRunManager = mock(CheckRunManager.class);
  private final ReviewPublisher reviewPublisher = mock(ReviewPublisher.class);
  private final CiHoldRegistry registry = new CiHoldRegistry();

  private CiHoldRevisit revisit;

  @BeforeEach
  void setUp() {
    var verdictBuilder =
        new VerdictBuilder(
            mock(PrSummaryGenerator.class),
            mock(FollowUpAnalyzer.class),
            BotIdentity.of("thrillhousebot[bot]"),
            CiGatingMode.STRICT);
    revisit =
        new CiHoldRevisit(
            authClient,
            contextLoader,
            ciStatusEvaluator,
            verdictBuilder,
            checkRunManager,
            reviewPublisher,
            registry);
    when(authClient.getAuthHeader(123L)).thenReturn(AUTH);
    when(contextLoader.currentHeadSha(eq(AUTH), any())).thenReturn(Optional.of(HEAD));
  }

  private void hold() {
    registry.hold(
        "owner", "repo", 42, new CiHoldRegistry.HeldVerdict(HEAD, "main", 7L, DETAILS_URL));
  }

  private static CiStatusEvaluator.CiEvaluation green() {
    return new CiStatusEvaluator.CiEvaluation(List.of(), false);
  }

  private static CiStatusEvaluator.CiEvaluation red() {
    return new CiStatusEvaluator.CiEvaluation(
        List.of(new ReviewResult.CiCheck("test", "check-run", "failing", "failure")), false);
  }

  @Test
  void greenCiPostsTheHeldApprovalAndConcludesTheCheckRunSuccess() {
    hold();
    when(ciStatusEvaluator.evaluate(AUTH, "owner", "repo", HEAD, "main")).thenReturn(green());

    revisit.revisit(RECHECK);

    var review = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
    verify(reviewPublisher)
        .createReviewWithFallback(eq(AUTH), eq("owner"), eq("repo"), eq(42), review.capture());
    assertEquals("APPROVE", review.getValue().event());
    assertEquals(HEAD, review.getValue().commitId());
    assertTrue(
        review.getValue().body().contains("Required CI is now green"), review.getValue().body());

    var update = ArgumentCaptor.forClass(CheckRunManager.CheckRunUpdate.class);
    verify(checkRunManager).updateCheckRun(update.capture());
    assertEquals(7L, update.getValue().checkRunId());
    assertEquals("completed", update.getValue().status());
    assertEquals("success", update.getValue().conclusion());
    assertEquals(CheckRunManager.CHECK_NAME + " ✅", update.getValue().title());
    assertEquals(DETAILS_URL, update.getValue().detailsUrl());
    assertTrue(registry.heldAt("owner", "repo", 42, HEAD).isEmpty());
  }

  @Test
  void greenCiWithUnknownRequiredContextsDropsTheWordRequired() {
    hold();
    when(ciStatusEvaluator.evaluate(AUTH, "owner", "repo", HEAD, "main"))
        .thenReturn(new CiStatusEvaluator.CiEvaluation(List.of(), false, false));

    revisit.revisit(RECHECK);

    var review = ArgumentCaptor.forClass(GitHubReviewClient.CreateReviewRequest.class);
    verify(reviewPublisher)
        .createReviewWithFallback(
            anyString(), anyString(), anyString(), anyInt(), review.capture());
    assertTrue(review.getValue().body().startsWith("CI is now green"), review.getValue().body());
  }

  @Test
  void redCiKeepsTheHoldAndRefreshesTheCheckRunSummary() {
    hold();
    when(ciStatusEvaluator.evaluate(AUTH, "owner", "repo", HEAD, "main")).thenReturn(red());

    revisit.revisit(RECHECK);

    verify(reviewPublisher, never())
        .createReviewWithFallback(anyString(), anyString(), anyString(), anyInt(), any());
    var update = ArgumentCaptor.forClass(CheckRunManager.CheckRunUpdate.class);
    verify(checkRunManager).updateCheckRun(update.capture());
    assertEquals("neutral", update.getValue().conclusion());
    assertEquals(CheckRunManager.CHECK_NAME, update.getValue().title());
    assertTrue(
        update.getValue().summary().contains("1 required CI check(s) are still pending or failing"),
        update.getValue().summary());
    assertTrue(update.getValue().summary().endsWith(CiHoldRevisit.REVISIT_NOTE));
    assertTrue(registry.heldAt("owner", "repo", 42, HEAD).isPresent());
  }

  @Test
  void unreadableCiKeepsTheHold() {
    hold();
    when(ciStatusEvaluator.evaluate(AUTH, "owner", "repo", HEAD, "main"))
        .thenReturn(new CiStatusEvaluator.CiEvaluation(List.of(), true));

    revisit.revisit(RECHECK);

    verify(reviewPublisher, never())
        .createReviewWithFallback(anyString(), anyString(), anyString(), anyInt(), any());
    assertTrue(registry.heldAt("owner", "repo", 42, HEAD).isPresent());
  }

  @Test
  void movedHeadDropsTheHoldWithoutPosting() {
    hold();
    when(contextLoader.currentHeadSha(eq(AUTH), any())).thenReturn(Optional.of("def456"));

    revisit.revisit(RECHECK);

    verifyNoInteractions(ciStatusEvaluator, reviewPublisher, checkRunManager);
    assertTrue(registry.heldAt("owner", "repo", 42, HEAD).isEmpty());
  }

  @Test
  void unreadableHeadIsTreatedAsUnmoved() {
    hold();
    when(contextLoader.currentHeadSha(eq(AUTH), any())).thenReturn(Optional.empty());
    when(ciStatusEvaluator.evaluate(AUTH, "owner", "repo", HEAD, "main")).thenReturn(green());

    revisit.revisit(RECHECK);

    verify(reviewPublisher)
        .createReviewWithFallback(anyString(), anyString(), anyString(), anyInt(), any());
  }

  @Test
  void pullRequestWithoutAHeldVerdictIsIgnored() {
    registry.track("owner", "repo", 42, HEAD);

    revisit.revisit(RECHECK);

    verifyNoInteractions(authClient, contextLoader, ciStatusEvaluator, reviewPublisher);
    verifyNoInteractions(checkRunManager);
  }

  @Test
  void eventForAnotherHeadThanTheHeldOneIsIgnored() {
    hold();

    revisit.revisit(new CiHoldRevisit.Recheck("owner", "repo", 42, "def456", 123L));

    verifyNoInteractions(authClient, contextLoader, ciStatusEvaluator, reviewPublisher);
    assertTrue(registry.heldAt("owner", "repo", 42, HEAD).isPresent());
  }

  @Test
  void failedApprovalPostKeepsTheHoldForTheNextEvent() {
    hold();
    when(ciStatusEvaluator.evaluate(AUTH, "owner", "repo", HEAD, "main")).thenReturn(green());
    doThrow(new RuntimeException("boom"))
        .when(reviewPublisher)
        .createReviewWithFallback(anyString(), anyString(), anyString(), anyInt(), any());

    revisit.revisit(RECHECK);

    verify(checkRunManager, never()).updateCheckRun(any());
    assertTrue(registry.heldAt("owner", "repo", 42, HEAD).isPresent());
  }

  @Test
  void failedCheckRunUpdateAfterApprovalIsSwallowed() {
    hold();
    when(ciStatusEvaluator.evaluate(AUTH, "owner", "repo", HEAD, "main")).thenReturn(green());
    doThrow(new RuntimeException("boom")).when(checkRunManager).updateCheckRun(any());

    revisit.revisit(RECHECK);

    verify(reviewPublisher)
        .createReviewWithFallback(anyString(), anyString(), anyString(), anyInt(), any());
    assertTrue(registry.heldAt("owner", "repo", 42, HEAD).isEmpty());
  }

  @Test
  void failedCheckRunUpdateOnRedCiKeepsTheHold() {
    hold();
    when(ciStatusEvaluator.evaluate(AUTH, "owner", "repo", HEAD, "main")).thenReturn(red());
    doThrow(new RuntimeException("boom")).when(checkRunManager).updateCheckRun(any());

    revisit.revisit(RECHECK);

    assertFalse(registry.heldAt("owner", "repo", 42, HEAD).isEmpty());
  }
}
