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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubLabelClient;
import dev.thiagogonzaga.thrillhousebot.webhook.ReviewTriggerFilter;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link PullRequestLabelGate}: the label read and the stand-down (#1021). */
class PullRequestLabelGateTest {

  private static final String AUTH = "Bearer test";

  private final ReviewTriggerFilter triggerFilter = mock(ReviewTriggerFilter.class);
  private final GitHubAuthClient authClient = mock(GitHubAuthClient.class);
  private final GitHubLabelClient labelClient = mock(GitHubLabelClient.class);
  private final CheckRunManager checkRunManager = mock(CheckRunManager.class);
  private final ReviewSkipEmitter skipEmitter =
      new ReviewSkipEmitter(io.opentelemetry.api.OpenTelemetry.noop());
  private final PullRequestLabelGate gate =
      new PullRequestLabelGate(
          triggerFilter, authClient, labelClient, checkRunManager, skipEmitter);

  private static final ReviewTriggerFilter.Skip EXCLUDED =
      new ReviewTriggerFilter.Skip(ReviewSkipReason.EXCLUDED_LABEL, "PR carries an excluded label");

  private static PullRequestLabelGate.StandDown where(long checkRunId) {
    return new PullRequestLabelGate.StandDown(
        AUTH, "owner", "repo", 42, checkRunId, "https://bot.example/s", "the review of abc");
  }

  private static ReviewOrchestrator.ReviewRequest request(boolean manual) {
    return new ReviewOrchestrator.ReviewRequest(
        "owner", "repo", 42, "abc", "T", "", "base", "main", 1L, manual);
  }

  @Test
  void anAutomaticRunARejectingGateStandsDown() {
    when(triggerFilter.hasLabelGates()).thenReturn(true);
    page(1, List.of(label("dependencies")));
    when(triggerFilter.labelSkipReason(List.of("dependencies"))).thenReturn(Optional.of(EXCLUDED));

    assertTrue(gate.standsDownBeforePublish(request(false), where(9L)));

    assertEquals(1L, skipEmitter.countsByReason().get("EXCLUDED_LABEL"));
    var update = ArgumentCaptor.forClass(CheckRunManager.CheckRunUpdate.class);
    verify(checkRunManager).updateCheckRun(update.capture());
    assertEquals(9L, update.getValue().checkRunId());
    assertEquals("skipped", update.getValue().conclusion());
    assertEquals(PullRequestLabelGate.CHECK_TITLE, update.getValue().title());
    assertEquals(
        "PR carries an excluded label (webhook.triggers label gates), so this result was not"
            + " posted. A manual /review still reviews the pull request.",
        update.getValue().summary());
    assertEquals("https://bot.example/s", update.getValue().detailsUrl());
  }

  @Test
  void aRunTheGatesAllowOrCannotJudgeIsNotStoodDown() {
    when(triggerFilter.hasLabelGates()).thenReturn(true);
    page(1, List.of(label("java")));

    assertFalse(gate.standsDownBeforePublish(request(false), where(9L)));

    when(labelClient.listIssueLabels(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt()))
        .thenThrow(new RuntimeException("labels down"));
    assertFalse(gate.standsDownBeforePublish(request(false), where(9L)));
    verifyNoInteractions(checkRunManager);
    assertTrue(skipEmitter.countsByReason().isEmpty());
  }

  @Test
  void aManualRunOrAnUnconfiguredGateReadsNothing() {
    assertFalse(gate.standsDownBeforePublish(request(false), where(9L)));
    when(triggerFilter.hasLabelGates()).thenReturn(true);
    assertFalse(gate.standsDownBeforePublish(request(true), where(9L)));

    verifyNoInteractions(labelClient);
  }

  @Test
  void standDownWithoutACheckRunOrWithAFailingOneStillCountsTheSkip() {
    gate.standDown(EXCLUDED, where(-1L));
    verifyNoInteractions(checkRunManager);

    doThrow(new RuntimeException("check runs down")).when(checkRunManager).updateCheckRun(any());
    assertDoesNotThrow(() -> gate.standDown(EXCLUDED, where(9L)));

    assertEquals(2L, skipEmitter.countsByReason().get("EXCLUDED_LABEL"));
  }

  private static GitHubLabelClient.Label label(String name) {
    return new GitHubLabelClient.Label(name, null, null);
  }

  private void page(int page, List<GitHubLabelClient.Label> labels) {
    when(labelClient.listIssueLabels(
            AUTH, "application/vnd.github+json", "owner", "repo", 42, 100, page))
        .thenReturn(labels);
  }

  @Test
  void readsEveryPageOfLabelsAsTheInstallation() {
    when(authClient.getAuthHeader(123L)).thenReturn(AUTH);
    page(1, IntStream.range(0, 100).mapToObj(i -> label("l" + i)).toList());
    page(2, List.of(label("dependencies")));

    var labels = gate.currentLabels(123L, "owner", "repo", 42).orElseThrow();

    assertEquals(101, labels.size());
    assertEquals("dependencies", labels.get(100));
  }

  @Test
  void theReadStopsAtThePageCap() {
    when(labelClient.listIssueLabels(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt()))
        .thenReturn(IntStream.range(0, 100).mapToObj(i -> label("l" + i)).toList());

    var labels = gate.currentLabels(AUTH, "owner", "repo", 42).orElseThrow();

    assertEquals(100 * PrLabeler.MAX_LABEL_PAGES, labels.size());
    verify(labelClient, times(PrLabeler.MAX_LABEL_PAGES))
        .listIssueLabels(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void aNullPageEndsTheRead() {
    page(1, null);

    assertEquals(Optional.of(List.of()), gate.currentLabels(AUTH, "owner", "repo", 42));
  }

  @Test
  void aFailedPageMakesTheWholeSetUnreadable() {
    page(1, IntStream.range(0, 100).mapToObj(i -> label("l" + i)).toList());
    when(labelClient.listIssueLabels(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), eq(2)))
        .thenThrow(new RuntimeException("labels down"));

    assertTrue(gate.currentLabels(AUTH, "owner", "repo", 42).isEmpty());
  }

  @Test
  void aFailedTokenMakesTheSetUnreadableWithoutAnyRead() {
    when(authClient.getAuthHeader(anyLong())).thenThrow(new RuntimeException("token down"));

    assertTrue(gate.currentLabels(123L, "owner", "repo", 42).isEmpty());
    verifyNoInteractions(labelClient);
  }

  @Test
  void delegatesTheGatesToTheTriggerFilter() {
    var skip =
        new ReviewTriggerFilter.Skip(
            ReviewSkipReason.EXCLUDED_LABEL, "PR carries an excluded label");
    when(triggerFilter.hasLabelGates()).thenReturn(false);
    when(triggerFilter.labelSkipReason(List.of("dependencies"))).thenReturn(Optional.of(skip));

    assertFalse(gate.configured());
    assertEquals(Optional.of(skip), gate.skipFor(List.of("dependencies")));
    verify(triggerFilter).labelSkipReason(List.of("dependencies"));
  }
}
