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
import io.quarkiverse.githubapp.GitHubClientProvider;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.kohsuke.github.GHPermissionType;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManualReviewAuthorizerTest {

  @Mock private ThrillhouseConfig config;

  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;

  @Mock private GitHubClientProvider clientProvider;
  @Mock private GitHub gitHub;
  @Mock private GHRepository repository;

  @InjectMocks private ManualReviewAuthorizer authorizer;

  @BeforeEach
  void setUp() throws Exception {
    lenient().when(config.review()).thenReturn(reviewConfig);
    lenient().when(reviewConfig.manualTriggerAllowedLogins()).thenReturn(Optional.empty());
    lenient().when(reviewConfig.manualTriggerAuthTimeout()).thenReturn(Duration.ofSeconds(5));
    lenient().doReturn(gitHub).when(clientProvider).getInstallationClient(anyLong());
    lenient().doReturn(repository).when(gitHub).getRepository(anyString());
  }

  private void stubPermission(String permission) throws Exception {
    lenient()
        .doReturn(GHPermissionType.valueOf(permission.toUpperCase()))
        .when(repository)
        .getPermission(anyString());
  }

  @Test
  void aMaintainerMayRequestAReviewAndATriagerMayNot() throws Exception {
    doReturn(GHPermissionType.WRITE).when(repository).getPermission(eq("keeper"));
    doReturn(GHPermissionType.READ).when(repository).getPermission(eq("sorter"));

    assertTrue(authorizer.isAuthorized("o", "r", 1L, "keeper", "MEMBER"));
    assertFalse(authorizer.isAuthorized("o", "r", 1L, "sorter", "MEMBER"));
  }

  @Test
  void shouldRejectBlankOrNullLogin() throws Exception {
    assertFalse(authorizer.isAuthorized("o", "r", 1L, null, "OWNER"));
    assertFalse(authorizer.isAuthorized("o", "r", 1L, "   ", "OWNER"));
    verifyNoInteractions(clientProvider);
  }

  @Test
  void shouldAllowAllowlistedLoginWithoutApiCall() throws Exception {
    when(reviewConfig.manualTriggerAllowedLogins()).thenReturn(Optional.of(List.of("Trusted-Bot")));

    // Allowlist match is case-insensitive and short-circuits before any GitHub call.
    assertTrue(authorizer.isAuthorized("o", "r", 1L, "trusted-bot", "NONE"));
    verifyNoInteractions(clientProvider);
  }

  @Test
  void shouldNotMatchDifferentAllowlistedLogin() throws Exception {
    when(reviewConfig.manualTriggerAllowedLogins())
        .thenReturn(Optional.of(List.of("someone-else")));

    // A non-matching allowlist entry is skipped; with a non-write association the user is then
    // rejected without an API call.
    assertFalse(authorizer.isAuthorized("o", "r", 1L, "stranger", "NONE"));
    verifyNoInteractions(clientProvider);
  }

  @Test
  void shouldRejectNonWriteCapableAssociationWithoutApiCall() throws Exception {
    assertFalse(authorizer.isAuthorized("o", "r", 1L, "stranger", "NONE"));
    assertFalse(authorizer.isAuthorized("o", "r", 1L, "drive-by", "CONTRIBUTOR"));
    assertFalse(authorizer.isAuthorized("o", "r", 1L, "newcomer", "FIRST_TIME_CONTRIBUTOR"));
    assertFalse(authorizer.isAuthorized("o", "r", 1L, "puppet", "MANNEQUIN"));
    assertFalse(authorizer.isAuthorized("o", "r", 1L, "unknown", null));
    verifyNoInteractions(clientProvider);
  }

  @Test
  void shouldRejectReadOnlyOrgMember() throws Exception {
    stubPermission("read");
    assertFalse(authorizer.isAuthorized("o", "r", 1L, "member", "MEMBER"));
  }

  @Test
  void shouldAuthorizeMemberWithWritePermission() throws Exception {
    stubPermission("write");
    assertTrue(authorizer.isAuthorized("o", "r", 1L, "maintainer", "MEMBER"));
  }

  @Test
  void shouldAuthorizeCollaboratorWithAdminPermission() throws Exception {
    stubPermission("admin");
    assertTrue(authorizer.isAuthorized("o", "r", 1L, "collab", "COLLABORATOR"));
  }

  @Test
  void shouldVerifyPermissionEvenForOwnerAssociation() throws Exception {
    stubPermission("admin");

    // A mixed-case association still passes the pre-filter, and OWNER is confirmed via the API
    // rather than trusted on the association alone.
    assertTrue(authorizer.isAuthorized("acme", "widgets", 7L, "boss", "owner"));
    verify(clientProvider).getInstallationClient(eq(7L));
    verify(gitHub).getRepository(eq("acme/widgets"));
    verify(repository).getPermission(eq("boss"));
  }

  @Test
  void shouldFailClosedWhenPermissionCheckThrows() throws Exception {
    doThrow(new RuntimeException("github unavailable")).when(repository).getPermission(anyString());

    assertFalse(authorizer.isAuthorized("o", "r", 1L, "member", "MEMBER"));
  }

  @Test
  void shouldFailClosedWhenPermissionUnrecognized() throws Exception {
    doReturn(GHPermissionType.UNKNOWN).when(repository).getPermission(anyString());

    assertFalse(authorizer.isAuthorized("o", "r", 1L, "member", "MEMBER"));
  }

  @Test
  void shouldTreatPermissionLevelCaseInsensitively() throws Exception {
    stubPermission("WRITE");
    assertTrue(authorizer.isAuthorized("o", "r", 1L, "member", "MEMBER"));
  }

  @Test
  void shouldFailClosedWhenPermissionCheckExceedsTimeout() throws Exception {
    // GitHub is degraded: the permission call would eventually answer "admin", but not within the
    // ACK-path budget. The check must abandon it and deny rather than block the webhook worker.
    when(reviewConfig.manualTriggerAuthTimeout()).thenReturn(Duration.ofMillis(100));
    // On a loaded machine the timeout can pass before the background check reaches GitHub at all,
    // so these stubs stay lenient: the test is about the denial, not about the calls being made.
    // The latch keeps the call in flight (past the timeout) without a wall-clock sleep.
    var release = new CountDownLatch(1);
    lenient()
        .doAnswer(
            inv -> {
              release.await();
              return GHPermissionType.ADMIN;
            })
        .when(repository)
        .getPermission(anyString());

    try {
      assertFalse(authorizer.isAuthorized("o", "r", 1L, "member", "MEMBER"));
    } finally {
      release.countDown(); // let the abandoned check finish so its thread does not leak
    }
  }

  @Test
  void shouldFailClosedWhenInterruptedWhileWaitingOnPermissionCheck() throws Exception {
    // Interrupt is observed before the permission check runs; stubs stay lenient so Mockito
    // does not fail the test when the early fail-closed path skips GitHub calls.
    var release = new CountDownLatch(1);
    lenient()
        .doAnswer(
            inv -> {
              release.await();
              return GHPermissionType.ADMIN;
            })
        .when(repository)
        .getPermission(anyString());

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
  void shouldPropagateErrorFromPermissionCheck() throws Exception {
    // A fatal Error must not be masked as a denied review — it propagates as it would have before
    // the check moved onto a separate thread.
    var fatal = new Error("simulated fatal");
    doThrow(fatal).when(repository).getPermission(anyString());

    var thrown =
        assertThrows(Error.class, () -> authorizer.isAuthorized("o", "r", 1L, "member", "MEMBER"));
    assertSame(fatal, thrown);
  }

  @Test
  void shutdownStopsTheAuthCheckExecutor() throws Exception {
    // @PreDestroy must release the background executor without throwing.
    assertDoesNotThrow(() -> authorizer.shutdown());
  }
}
