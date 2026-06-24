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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.thiagogonzaga.thrillhousebot.github.GitHubCheckRunClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link CheckRunManager} — the check-run create/update subsystem extracted from
 * {@code ReviewOrchestrator} (#250), including the completion-only retry after a failed full
 * update.
 */
class CheckRunManagerTest {

  private static final String SESSION_URL = "https://bot.example/session/test-public-id";

  @Mock private GitHubCheckRunClient checkRunClient;

  private CheckRunManager manager;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    manager = new CheckRunManager(checkRunClient);
  }

  @Test
  void shouldCreateCheckRunAsInProgress() {
    when(checkRunClient.createCheckRun(anyString(), anyString(), eq("owner"), eq("repo"), any()))
        .thenReturn(new GitHubCheckRunClient.CheckRunResponse(99L, "http://check"));

    var id = manager.createCheckRun("Bearer tok", "owner", "repo", "abcdefgh", SESSION_URL);

    assertEquals(99L, id);
    var captor = ArgumentCaptor.forClass(GitHubCheckRunClient.CreateCheckRunRequest.class);
    verify(checkRunClient)
        .createCheckRun(anyString(), anyString(), eq("owner"), eq("repo"), captor.capture());
    assertEquals("in_progress", captor.getValue().status());
    assertEquals(SESSION_URL, captor.getValue().detailsUrl());
  }

  @Test
  void shouldIncludeCompletedAtOnlyForCompletedStatusUpdate() {
    doNothing()
        .when(checkRunClient)
        .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());

    manager.updateCheckRun(newCheckRunUpdate("completed", "success"));

    var completedCaptor = ArgumentCaptor.forClass(GitHubCheckRunClient.UpdateCheckRunRequest.class);
    verify(checkRunClient)
        .updateCheckRun(
            anyString(), anyString(), eq("owner"), eq("repo"), eq(42L), completedCaptor.capture());
    assertNull(completedCaptor.getValue().status());
    assertEquals("success", completedCaptor.getValue().conclusion());
    assertNotNull(completedCaptor.getValue().completedAt());
    assertTrue(completedCaptor.getValue().completedAt().endsWith("Z"));
    assertFalse(completedCaptor.getValue().completedAt().contains("."));

    clearInvocations(checkRunClient);

    manager.updateCheckRun(newCheckRunUpdate("in_progress", null));

    var inProgressCaptor =
        ArgumentCaptor.forClass(GitHubCheckRunClient.UpdateCheckRunRequest.class);
    verify(checkRunClient)
        .updateCheckRun(
            anyString(), anyString(), eq("owner"), eq("repo"), eq(42L), inProgressCaptor.capture());
    assertEquals("in_progress", inProgressCaptor.getValue().status());
    assertNull(inProgressCaptor.getValue().conclusion());
    assertNull(inProgressCaptor.getValue().completedAt());
  }

  @Test
  void shouldRetryWithConclusionOnlyWhenCompletionUpdateFails() {
    doThrow(new RuntimeException("422 Unprocessable Entity"))
        .doNothing()
        .when(checkRunClient)
        .updateCheckRun(anyString(), anyString(), anyString(), anyString(), anyLong(), any());

    manager.updateCheckRun(newCheckRunUpdate("completed", "failure"));

    var captor = ArgumentCaptor.forClass(GitHubCheckRunClient.UpdateCheckRunRequest.class);
    verify(checkRunClient, times(2))
        .updateCheckRun(
            anyString(), anyString(), eq("owner"), eq("repo"), eq(42L), captor.capture());

    var fallback = captor.getAllValues().get(1);
    assertNull(fallback.status());
    assertEquals("failure", fallback.conclusion());
    assertNull(fallback.completedAt());
    assertNull(fallback.output());
  }

  private CheckRunManager.CheckRunUpdate newCheckRunUpdate(String status, String conclusion) {
    return new CheckRunManager.CheckRunUpdate(
        "Bearer tok", "owner", "repo", 42L, status, conclusion, "Title", "Summary", SESSION_URL);
  }
}
