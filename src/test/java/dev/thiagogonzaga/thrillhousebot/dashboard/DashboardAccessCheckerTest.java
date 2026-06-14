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
package dev.thiagogonzaga.thrillhousebot.dashboard;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubInstallationClient;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class DashboardAccessCheckerTest {

  @Mock ThrillhouseConfig config;
  @Mock ThrillhouseConfig.DashboardConfig dashboardConfig;
  @Mock GitHubAuthClient authClient;
  @Mock GitHubInstallationClient installationClient;

  private final java.util.concurrent.atomic.AtomicReference<java.time.Instant> now =
      new java.util.concurrent.atomic.AtomicReference<>(
          java.time.Instant.parse("2026-06-10T12:00:00Z"));

  private DashboardAccessChecker checker;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(config.dashboard()).thenReturn(dashboardConfig);
    when(dashboardConfig.accountOwner()).thenReturn(Optional.empty());
    when(authClient.generateAppJwt()).thenReturn("jwt");
    when(installationClient.getApp(eq("Bearer jwt"), anyString()))
        .thenReturn(
            new GitHubInstallationClient.AppInfo(
                new GitHubInstallationClient.AppInfo.Owner("myowner", "User")));
    checker = new DashboardAccessChecker(config, authClient, installationClient, now::get);
  }

  @Test
  void shouldDenyEveryUserWhenOwnerCannotBeResolved() {
    when(installationClient.getApp(anyString(), anyString()))
        .thenThrow(new RuntimeException("GitHub App unavailable"));
    // Fail closed: an unresolvable owner must lock the dashboard, not open it to everyone.
    assertFalse(checker.hasAccess("random-user"));
    assertEquals(
        DashboardAccessChecker.AccessDecision.NOT_CONFIGURED, checker.checkAccess("random-user"));
  }

  @Test
  void shouldResolveOwnerFromGitHubApp() {
    assertTrue(checker.hasAccess("myowner"));
    verify(installationClient).getApp(eq("Bearer jwt"), anyString());
  }

  @Test
  void shouldPreferConfiguredAccountOwnerOverApp() {
    when(dashboardConfig.accountOwner()).thenReturn(Optional.of("override-owner"));
    assertTrue(checker.hasAccess("override-owner"));
    verify(installationClient, never()).getApp(anyString(), anyString());
  }

  @Test
  void shouldAllowAccountOwner() {
    assertTrue(checker.hasAccess("myowner"));
    assertTrue(checker.hasAccess("MyOwner"));
  }

  @Test
  void shouldAllowCollaboratorOnInstalledRepo() {
    stubInstalledRepo("myowner", "demo");
    when(authClient.getAuthHeader(99L)).thenReturn("Bearer inst-token");

    Response collaboratorResponse = mock(Response.class);
    when(collaboratorResponse.getStatus()).thenReturn(204);
    when(installationClient.checkCollaborator(
            eq("Bearer inst-token"), anyString(), eq("myowner"), eq("demo"), eq("collab")))
        .thenReturn(collaboratorResponse);

    assertTrue(checker.hasAccess("collab"));
  }

  @Test
  void shouldDenyUserWhoIsNotCollaborator() {
    stubInstalledRepo("myowner", "demo");
    when(authClient.getAuthHeader(99L)).thenReturn("Bearer inst-token");

    Response notCollaborator = mock(Response.class);
    when(notCollaborator.getStatus()).thenReturn(404);
    when(installationClient.checkCollaborator(
            eq("Bearer inst-token"), anyString(), eq("myowner"), eq("demo"), eq("outsider")))
        .thenReturn(notCollaborator);

    assertFalse(checker.hasAccess("outsider"));
  }

  @Test
  void shouldDenyNullLogin() {
    assertFalse(checker.hasAccess(null));
  }

  @Test
  void shouldDenyBlankLogin() {
    assertFalse(checker.hasAccess("   "));
  }

  @Test
  void shouldReevaluateAccessAfterCacheExpiry() {
    stubInstalledRepo("myowner", "demo");
    when(authClient.getAuthHeader(99L)).thenReturn("Bearer inst-token");
    Response collab = mock(Response.class);
    when(collab.getStatus()).thenReturn(204);
    when(installationClient.checkCollaborator(
            eq("Bearer inst-token"), anyString(), eq("myowner"), eq("demo"), eq("collab")))
        .thenReturn(collab);

    checker.seedAccessCache("collab", true, now.get().minus(java.time.Duration.ofMinutes(10)));

    assertTrue(checker.hasAccess("collab"));
    verify(installationClient, times(1))
        .checkCollaborator(
            eq("Bearer inst-token"), anyString(), eq("myowner"), eq("demo"), eq("collab"));
  }

  @Test
  void shouldReturnCachedAccess() {
    stubInstalledRepo("myowner", "demo");
    when(authClient.getAuthHeader(99L)).thenReturn("Bearer inst-token");
    Response collab = mock(Response.class);
    when(collab.getStatus()).thenReturn(204);
    when(installationClient.checkCollaborator(
            anyString(), anyString(), anyString(), anyString(), eq("collab")))
        .thenReturn(collab);

    assertTrue(checker.hasAccess("collab"));
    // second call uses cache, no additional collaborator API calls
    assertTrue(checker.hasAccess("collab"));
    verify(installationClient, times(1))
        .checkCollaborator(anyString(), anyString(), anyString(), anyString(), eq("collab"));
  }

  @Test
  void shouldWarnOnUnexpectedCollaboratorCheckStatus() {
    stubInstalledRepo("myowner", "demo");
    when(authClient.getAuthHeader(99L)).thenReturn("Bearer inst-token");
    Response unexpected = mock(Response.class);
    when(unexpected.getStatus()).thenReturn(500);
    when(installationClient.checkCollaborator(
            anyString(), anyString(), anyString(), anyString(), eq("collab")))
        .thenReturn(unexpected);

    assertFalse(checker.hasAccess("collab"));
  }

  @Test
  void shouldDenyWhenCollaboratorCheckThrowsException() {
    stubInstalledRepo("myowner", "demo");
    when(authClient.getAuthHeader(99L)).thenReturn("Bearer inst-token");
    when(installationClient.checkCollaborator(
            anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new RuntimeException("network error"));

    assertFalse(checker.hasAccess("collab"));
  }

  @Test
  void shouldHandleNullRepositoriesResponse() {
    when(installationClient.listInstallations(eq("Bearer jwt"), anyString(), eq(100)))
        .thenReturn(
            List.of(
                new GitHubInstallationClient.Installation(
                    99L, new GitHubInstallationClient.Installation.Account("myowner", "User"))));
    when(authClient.getAuthHeader(99L)).thenReturn("Bearer inst-token");
    when(installationClient.listInstallationRepositories(anyString(), anyString(), eq(100), eq(1)))
        .thenReturn(new GitHubInstallationClient.InstallationRepositoriesResponse(0, null));

    assertFalse(checker.hasAccess("collab"));
  }

  @Test
  void shouldSkipInstallationWithNoMatchingAccount() {
    when(installationClient.listInstallations(eq("Bearer jwt"), anyString(), eq(100)))
        .thenReturn(
            List.of(
                new GitHubInstallationClient.Installation(
                    99L,
                    new GitHubInstallationClient.Installation.Account("other-owner", "User"))));

    assertFalse(checker.hasAccess("collab"));
  }

  @Test
  void shouldReturnCachedOwner() {
    // First call resolves owner from GitHub App
    assertTrue(checker.hasAccess("myowner"));
    // Second call should use cached owner; clear the mock so App call would fail if made
    when(installationClient.getApp(anyString(), anyString()))
        .thenThrow(new RuntimeException("should not be called"));
    assertTrue(checker.hasAccess("myowner"));
  }

  @Test
  void shouldReturnEmptyOwnerWhenAppHasNullOwner() {
    when(installationClient.getApp(anyString(), anyString()))
        .thenReturn(new GitHubInstallationClient.AppInfo(null));
    assertEquals(
        DashboardAccessChecker.AccessDecision.NOT_CONFIGURED, checker.checkAccess("anyone"));
    assertFalse(checker.hasAccess("anyone"));
  }

  @Test
  void shouldReturnEmptyOwnerWhenAppOwnerLoginIsBlank() {
    when(installationClient.getApp(anyString(), anyString()))
        .thenReturn(
            new GitHubInstallationClient.AppInfo(
                new GitHubInstallationClient.AppInfo.Owner("", "User")));
    assertEquals(
        DashboardAccessChecker.AccessDecision.NOT_CONFIGURED, checker.checkAccess("anyone"));
    assertFalse(checker.hasAccess("anyone"));
  }

  @Test
  void shouldReturnSnapshotFromCacheOnRepoFetchFailure() {
    when(installationClient.listInstallations(anyString(), anyString(), eq(100)))
        .thenThrow(new RuntimeException("API error"));

    assertFalse(checker.hasAccess("collab"));
  }

  @Test
  void shouldReturnConfiguredAccountOwnerWithoutCallingGitHubApp() {
    when(dashboardConfig.accountOwner()).thenReturn(Optional.of("configured-owner"));

    assertTrue(checker.hasAccess("configured-owner"));

    verify(installationClient, never()).getApp(anyString(), anyString());
  }

  @Test
  void shouldIgnoreBlankConfiguredAccountOwner() {
    when(dashboardConfig.accountOwner()).thenReturn(Optional.of("   "));

    assertTrue(checker.hasAccess("myowner"));
    verify(installationClient).getApp(eq("Bearer jwt"), anyString());
  }

  @Test
  void shouldUseCachedRepoSnapshotWithinTtl() {
    stubInstalledRepo("myowner", "demo");
    when(authClient.getAuthHeader(99L)).thenReturn("Bearer inst-token");
    Response collab = mock(Response.class);
    when(collab.getStatus()).thenReturn(404);
    when(installationClient.checkCollaborator(
            anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(collab);

    assertFalse(checker.hasAccess("outsider"));
    assertFalse(checker.hasAccess("another-outsider"));

    verify(installationClient, times(1)).listInstallations(eq("Bearer jwt"), anyString(), eq(100));
  }

  @Test
  void shouldUseCachedRepoSnapshotForSubsequentAccessChecks() {
    stubInstalledRepo("myowner", "demo");
    when(authClient.getAuthHeader(99L)).thenReturn("Bearer inst-token");
    Response collab = mock(Response.class);
    when(collab.getStatus()).thenReturn(204);
    when(installationClient.checkCollaborator(
            anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(collab);

    assertTrue(checker.hasAccess("collab-one"));
    assertTrue(checker.hasAccess("collab-two"));

    verify(installationClient, times(1)).listInstallations(eq("Bearer jwt"), anyString(), eq(100));
  }

  private void stubInstalledRepo(String owner, String repo) {
    when(installationClient.listInstallations(eq("Bearer jwt"), anyString(), eq(100)))
        .thenReturn(
            List.of(
                new GitHubInstallationClient.Installation(
                    99L, new GitHubInstallationClient.Installation.Account(owner, "User"))));
    when(installationClient.listInstallationRepositories(
            eq("Bearer inst-token"), anyString(), eq(100), eq(1)))
        .thenReturn(
            new GitHubInstallationClient.InstallationRepositoriesResponse(
                1,
                List.of(
                    new GitHubInstallationClient.InstallationRepositoriesResponse.Repository(
                        repo,
                        new GitHubInstallationClient.InstallationRepositoriesResponse.Repository
                            .Owner(owner)))));
  }

  @Test
  void sweepShouldEvictExpiredEntriesAboveThreshold() {
    var expired = now.get().minus(java.time.Duration.ofMinutes(10));
    for (var i = 0; i < DashboardAccessChecker.ACCESS_CACHE_SWEEP_THRESHOLD; i++) {
      checker.seedAccessCache("stale-user-" + i, false, expired);
    }
    checker.seedAccessCache("fresh-user", true, now.get());

    checker.sweepExpiredAccessEntries();

    assertEquals(1, checker.accessCacheSize());
    assertTrue(checker.hasAccessCacheEntry("fresh-user"));
  }

  @Test
  void sweepShouldNotRunBelowThreshold() {
    var expired = now.get().minus(java.time.Duration.ofMinutes(10));
    checker.seedAccessCache("stale-user", false, expired);

    checker.sweepExpiredAccessEntries();

    assertEquals(1, checker.accessCacheSize());
  }

  @Test
  void injectConstructorShouldDelegateWithSystemClock() {
    var productionChecker = new DashboardAccessChecker(config, authClient, installationClient);

    assertFalse(productionChecker.hasAccess(null));
    assertFalse(productionChecker.hasAccess("   "));
  }

  @Test
  void shouldReResolveOwnerAfterOwnerCacheExpiry() {
    assertTrue(checker.hasAccess("myowner"));

    // Advance past the 1h owner cache TTL — the owner must be re-resolved from the App
    now.set(now.get().plus(java.time.Duration.ofMinutes(61)));
    assertTrue(checker.hasAccess("myowner"));

    verify(installationClient, times(2)).getApp(eq("Bearer jwt"), anyString());
  }

  @Test
  void shouldNotReResolveOwnerWithinNegativeCacheTtl() {
    when(installationClient.getApp(anyString(), anyString()))
        .thenThrow(new RuntimeException("GitHub App unavailable"));

    // A failed resolution is cached briefly so repeated requests do not hammer the App endpoint.
    assertFalse(checker.hasAccess("random-user"));
    assertFalse(checker.hasAccess("another-user"));
    assertEquals(
        DashboardAccessChecker.AccessDecision.NOT_CONFIGURED, checker.checkAccess("third-user"));

    verify(installationClient, times(1)).getApp(eq("Bearer jwt"), anyString());
  }

  @Test
  void shouldRecoverAfterNegativeCacheTtlExpires() {
    when(installationClient.getApp(anyString(), anyString()))
        .thenThrow(new RuntimeException("GitHub App unavailable"));
    assertFalse(checker.hasAccess("myowner"));

    // Once the negative cache lapses and the App becomes resolvable again, access is restored.
    now.set(now.get().plus(java.time.Duration.ofMinutes(2)));
    reset(installationClient);
    when(installationClient.getApp(eq("Bearer jwt"), anyString()))
        .thenReturn(
            new GitHubInstallationClient.AppInfo(
                new GitHubInstallationClient.AppInfo.Owner("myowner", "User")));

    assertEquals(DashboardAccessChecker.AccessDecision.ALLOWED, checker.checkAccess("myowner"));
  }

  @Test
  void checkAccessShouldDistinguishAllowedDeniedAndNotConfigured() {
    assertEquals(DashboardAccessChecker.AccessDecision.ALLOWED, checker.checkAccess("myowner"));

    stubInstalledRepo("myowner", "demo");
    when(authClient.getAuthHeader(99L)).thenReturn("Bearer inst-token");
    Response notCollaborator = mock(Response.class);
    when(notCollaborator.getStatus()).thenReturn(404);
    when(installationClient.checkCollaborator(
            eq("Bearer inst-token"), anyString(), eq("myowner"), eq("demo"), eq("outsider")))
        .thenReturn(notCollaborator);
    assertEquals(DashboardAccessChecker.AccessDecision.DENIED, checker.checkAccess("outsider"));

    assertEquals(DashboardAccessChecker.AccessDecision.DENIED, checker.checkAccess("  "));
  }

  @Test
  void checkAccessShouldReturnNotConfiguredWhenOwnerUnresolvable() {
    when(installationClient.getApp(anyString(), anyString()))
        .thenThrow(new RuntimeException("GitHub App unavailable"));

    assertEquals(
        DashboardAccessChecker.AccessDecision.NOT_CONFIGURED, checker.checkAccess("anyone"));
  }
}
