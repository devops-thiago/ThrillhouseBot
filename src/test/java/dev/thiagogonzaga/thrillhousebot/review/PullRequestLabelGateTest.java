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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubLabelClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubTokenReads;
import dev.thiagogonzaga.thrillhousebot.webhook.ReviewTriggerFilter;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PullRequestLabelGate}: the label read and the stand-down (#1021). */
class PullRequestLabelGateTest {

  private static final String AUTH = "Bearer test";

  private final ReviewTriggerFilter triggerFilter = mock(ReviewTriggerFilter.class);
  private final GitHubAuthClient authClient = mock(GitHubAuthClient.class);
  private final GitHubTokenReads reads = mock(GitHubTokenReads.class);
  private final CheckRunManager checkRunManager = mock(CheckRunManager.class);
  private final ReviewSkipEmitter skipEmitter =
      new ReviewSkipEmitter(io.opentelemetry.api.OpenTelemetry.noop());
  private final PullRequestLabelGate gate =
      new PullRequestLabelGate(triggerFilter, authClient, reads, checkRunManager, skipEmitter);

  private static final ReviewTriggerFilter.Skip EXCLUDED =
      new ReviewTriggerFilter.Skip(ReviewSkipReason.EXCLUDED_LABEL, "PR carries an excluded label");

  private static PullRequestLabelGate.StandDown where(long checkRunId) {
    return new PullRequestLabelGate.StandDown(
        AUTH, 123L, "owner", "repo", 42, checkRunId, "https://bot.example/s", "the review of abc");
  }

  @Test
  void standDownWithoutACheckRunOrWithAFailingOneStillCountsTheSkip() {
    gate.standDown(EXCLUDED, where(-1L));
    verifyNoInteractions(checkRunManager);

    doThrow(new RuntimeException("check runs down")).when(checkRunManager).updateCheckRun(any());
    assertDoesNotThrow(() -> gate.standDown(EXCLUDED, where(9L)));

    assertEquals(2L, skipEmitter.countsByReason(java.util.List.of(123L)).get("EXCLUDED_LABEL"));
  }

  private static GitHubLabelClient.Label label(String name) {
    return new GitHubLabelClient.Label(name, null, null);
  }

  @Test
  void readsEveryLabelAsTheInstallation() {
    when(authClient.getAuthHeader(123L, "repo")).thenReturn(AUTH);
    when(reads.issueLabels(AUTH, "owner", "repo", 42))
        .thenReturn(List.of(label("java"), label("dependencies")));

    var labels = gate.currentLabels(123L, "owner", "repo", 42).orElseThrow();

    assertEquals(List.of("java", "dependencies"), labels);
  }

  @Test
  void aFailedReadMakesTheSetUnreadable() {
    when(reads.issueLabels(AUTH, "owner", "repo", 42))
        .thenThrow(new RuntimeException("labels down"));

    assertTrue(gate.currentLabels(AUTH, "owner", "repo", 42).isEmpty());
  }

  @Test
  void aFailedTokenMakesTheSetUnreadableWithoutAnyRead() {
    when(authClient.getAuthHeader(anyLong(), anyString()))
        .thenThrow(new RuntimeException("token down"));

    assertTrue(gate.currentLabels(123L, "owner", "repo", 42).isEmpty());
    verifyNoInteractions(reads);
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
