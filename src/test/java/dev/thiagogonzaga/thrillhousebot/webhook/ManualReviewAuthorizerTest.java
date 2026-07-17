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
package dev.thiagogonzaga.thrillhousebot.webhook;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubInstallationClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubInstallationClient.CollaboratorPermission;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManualReviewAuthorizerTest {

  @Mock private ThrillhouseConfig config;

  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;

  @Mock private GitHubAuthClient authClient;

  @Mock private GitHubInstallationClient installationClient;

  @InjectMocks private ManualReviewAuthorizer authorizer;

  @BeforeEach
  void setUp() {
    lenient().when(config.review()).thenReturn(reviewConfig);
    lenient().when(reviewConfig.manualTriggerAllowedLogins()).thenReturn(Optional.empty());
    lenient().when(reviewConfig.manualTriggerAuthTimeout()).thenReturn(Duration.ofSeconds(5));
  }

  private void stubPermission(String permission) {
    when(authClient.getAuthHeader(anyLong())).thenReturn("Bearer token");
    when(installationClient.collaboratorPermission(
            anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new CollaboratorPermission(permission, permission));
  }

  @Test
  void shouldRejectBlankOrNullLogin() {
    assertFalse(authorizer.isAuthorized("o", "r", 1L, null, "OWNER"));
    assertFalse(authorizer.isAuthorized("o", "r", 1L, "   ", "OWNER"));
    verifyNoInteractions(installationClient);
  }

  @Test
  void shouldAllowAllowlistedLoginWithoutApiCall() {
    when(reviewConfig.manualTriggerAllowedLogins()).thenReturn(Optional.of(List.of("Trusted-Bot")));

    // Allowlist match is case-insensitive and short-circuits before any GitHub call.
    assertTrue(authorizer.isAuthorized("o", "r", 1L, "trusted-bot", "NONE"));
    verifyNoInteractions(installationClient);
    verifyNoInteractions(authClient);
  }

  @Test
  void shouldNotMatchDifferentAllowlistedLogin() {
    when(reviewConfig.manualTriggerAllowedLogins())
        .thenReturn(Optional.of(List.of("someone-else")));

    // A non-matching allowlist entry is skipped; with a non-write association the user is then
    // rejected without an API call.
    assertFalse(authorizer.isAuthorized("o", "r", 1L, "stranger", "NONE"));
    verifyNoInteractions(installationClient);
  }

  @Test
  void shouldRejectNonWriteCapableAssociationWithoutApiCall() {
    assertFalse(authorizer.isAuthorized("o", "r", 1L, "stranger", "NONE"));
    assertFalse(authorizer.isAuthorized("o", "r", 1L, "drive-by", "CONTRIBUTOR"));
    assertFalse(authorizer.isAuthorized("o", "r", 1L, "newcomer", "FIRST_TIME_CONTRIBUTOR"));
    assertFalse(authorizer.isAuthorized("o", "r", 1L, "puppet", "MANNEQUIN"));
    assertFalse(authorizer.isAuthorized("o", "r", 1L, "unknown", null));
    verifyNoInteractions(installationClient);
  }

  @Test
  void shouldRejectReadOnlyOrgMember() {
    stubPermission("read");
    assertFalse(authorizer.isAuthorized("o", "r", 1L, "member", "MEMBER"));
  }

  @Test
  void shouldAuthorizeMemberWithWritePermission() {
    stubPermission("write");
    assertTrue(authorizer.isAuthorized("o", "r", 1L, "maintainer", "MEMBER"));
  }

  @Test
  void shouldAuthorizeCollaboratorWithAdminPermission() {
    stubPermission("admin");
    assertTrue(authorizer.isAuthorized("o", "r", 1L, "collab", "COLLABORATOR"));
  }

  @Test
  void shouldVerifyPermissionEvenForOwnerAssociation() {
    stubPermission("admin");

    // A mixed-case association still passes the pre-filter, and OWNER is confirmed via the API
    // rather than trusted on the association alone.
    assertTrue(authorizer.isAuthorized("acme", "widgets", 7L, "boss", "owner"));
    verify(installationClient)
        .collaboratorPermission(anyString(), anyString(), eq("acme"), eq("widgets"), eq("boss"));
  }

  @Test
  void shouldFailClosedWhenPermissionCheckThrows() {
    when(authClient.getAuthHeader(anyLong())).thenReturn("Bearer token");
    when(installationClient.collaboratorPermission(
            anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException("github unavailable"));

    assertFalse(authorizer.isAuthorized("o", "r", 1L, "member", "MEMBER"));
  }

  @Test
  void shouldFailClosedWhenPermissionResponseNull() {
    when(authClient.getAuthHeader(anyLong())).thenReturn("Bearer token");
    when(installationClient.collaboratorPermission(
            anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(null);

    assertFalse(authorizer.isAuthorized("o", "r", 1L, "member", "MEMBER"));
  }

  @Test
  void shouldFailClosedWhenPermissionMissing() {
    when(authClient.getAuthHeader(anyLong())).thenReturn("Bearer token");
    when(installationClient.collaboratorPermission(
            anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new CollaboratorPermission(null, null));

    assertFalse(authorizer.isAuthorized("o", "r", 1L, "member", "MEMBER"));
  }

  @Test
  void shouldTreatPermissionLevelCaseInsensitively() {
    stubPermission("WRITE");
    assertTrue(authorizer.isAuthorized("o", "r", 1L, "member", "MEMBER"));
  }

  @Test
  void shouldFailClosedWhenPermissionCheckExceedsTimeout() {
    // GitHub is degraded: the permission call would eventually answer "admin", but not within the
    // ACK-path budget. The check must abandon it and deny rather than block the webhook worker.
    when(reviewConfig.manualTriggerAuthTimeout()).thenReturn(Duration.ofMillis(100));
    when(authClient.getAuthHeader(anyLong())).thenReturn("Bearer token");
    // The latch keeps the call in flight (past the timeout) without a wall-clock sleep.
    var release = new CountDownLatch(1);
    when(installationClient.collaboratorPermission(
            anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(
            inv -> {
              release.await();
              return new CollaboratorPermission("admin", "admin");
            });

    try {
      assertFalse(authorizer.isAuthorized("o", "r", 1L, "member", "MEMBER"));
    } finally {
      release.countDown(); // let the abandoned check finish so its thread does not leak
    }
  }

  @Test
  void shouldFailClosedWhenInterruptedWhileWaitingOnPermissionCheck() {
    // Interrupt is observed before the permission check runs; stubs stay lenient so Mockito
    // does not fail the test when the early fail-closed path skips GitHub calls.
    lenient().when(authClient.getAuthHeader(anyLong())).thenReturn("Bearer token");
    var release = new CountDownLatch(1);
    lenient()
        .when(
            installationClient.collaboratorPermission(
                anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(
            inv -> {
              release.await();
              return new CollaboratorPermission("admin", "admin");
            });

    Thread.currentThread().interrupt(); // observed when hasWriteAccess waits on the check
    try {
      assertFalse(authorizer.isAuthorized("o", "r", 1L, "member", "MEMBER"));
      // The handler restores the interrupt flag rather than swallowing it.
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted(); // clear so the flag does not leak into later tests
      release.countDown();
    }
  }

  @Test
  void shouldPropagateErrorFromPermissionCheck() {
    // A fatal Error must not be masked as a denied review — it propagates as it would have before
    // the check moved onto a separate thread.
    when(authClient.getAuthHeader(anyLong())).thenReturn("Bearer token");
    var fatal = new Error("simulated fatal");
    when(installationClient.collaboratorPermission(
            anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenThrow(fatal);

    var thrown =
        assertThrows(Error.class, () -> authorizer.isAuthorized("o", "r", 1L, "member", "MEMBER"));
    assertSame(fatal, thrown);
  }

  @Test
  void shutdownStopsTheAuthCheckExecutor() {
    // @PreDestroy must release the background executor without throwing.
    assertDoesNotThrow(() -> authorizer.shutdown());
  }
}
