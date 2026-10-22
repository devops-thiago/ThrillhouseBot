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

import static dev.thiagogonzaga.thrillhousebot.review.PrePublishGuardFixtures.AUTH;
import static dev.thiagogonzaga.thrillhousebot.review.PrePublishGuardFixtures.review;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubLabelClient;
import dev.thiagogonzaga.thrillhousebot.webhook.ReviewTriggerFilter;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LabelGateGuard}: the label-gate stand-down before the first write (#1021).
 */
class LabelGateGuardTest {

  private final ReviewTriggerFilter triggerFilter = mock(ReviewTriggerFilter.class);
  private final GitHubLabelClient labelClient = mock(GitHubLabelClient.class);
  private final CheckRunManager checkRunManager = mock(CheckRunManager.class);
  private final ReviewSkipEmitter skipEmitter =
      new ReviewSkipEmitter(io.opentelemetry.api.OpenTelemetry.noop());
  private final LabelGateGuard guard =
      new LabelGateGuard(
          new PullRequestLabelGate(
              triggerFilter,
              mock(GitHubAuthClient.class),
              labelClient,
              checkRunManager,
              skipEmitter));

  private void labelled(String label) {
    when(triggerFilter.hasLabelGates()).thenReturn(true);
    when(labelClient.listIssueLabels(
            AUTH, "application/vnd.github+json", "owner", "repo", 42, 100, 1))
        .thenReturn(List.of(new GitHubLabelClient.Label(label, null, null)));
  }

  @Test
  void aRejectingGateStandsAnAutomaticRunDown() {
    labelled("dependencies");
    when(triggerFilter.labelSkipReason(List.of("dependencies")))
        .thenReturn(
            Optional.of(
                new ReviewTriggerFilter.Skip(
                    ReviewSkipReason.EXCLUDED_LABEL, "PR carries an excluded label")));

    var standDown = guard.check(review(false, 9L)).orElseThrow();

    assertEquals(ReviewSkipReason.EXCLUDED_LABEL, standDown.reason());
    assertEquals(
        "PR carries an excluded label — not posting the review of abcdefgh",
        standDown.skipDetail());
    assertEquals(PullRequestLabelGate.CHECK_TITLE, standDown.checkTitle());
    assertEquals(
        "PR carries an excluded label (webhook.triggers label gates), so this result was not"
            + " posted. A manual /review still reviews the pull request.",
        standDown.checkSummary());
    assertEquals("Skipped: a label gate rejects the pull request", standDown.sessionError());
    assertEquals("label-gated", standDown.kind());
    // The guard only decides; the stand-down's consequences are ReviewStandDown's.
    verifyNoInteractions(checkRunManager);
    assertTrue(skipEmitter.countsByReason(java.util.List.of(1L)).isEmpty());
  }

  @Test
  void aManualReviewIsNotLabelGated() {
    labelled("dependencies");

    assertTrue(guard.check(review(true, 9L)).isEmpty());

    verifyNoInteractions(labelClient);
  }

  @Test
  void noLabelGateConfiguredReadsNoLabels() {
    assertTrue(guard.check(review(false, 9L)).isEmpty());

    verifyNoInteractions(labelClient);
  }

  @Test
  void aRunTheGatesAllowOrCannotJudgeProceeds() {
    labelled("java");

    assertTrue(guard.check(review(false, 9L)).isEmpty());

    when(labelClient.listIssueLabels(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt()))
        .thenThrow(new RuntimeException("labels down"));
    assertTrue(guard.check(review(false, 9L)).isEmpty());
  }
}
