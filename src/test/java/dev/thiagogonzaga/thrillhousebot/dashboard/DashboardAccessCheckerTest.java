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
import dev.thiagogonzaga.thrillhousebot.dashboard.DashboardAccessChecker.AccessDecision;
import dev.thiagogonzaga.thrillhousebot.github.AllowedAccounts;
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubInstallationClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubInstallationClient.Installation;
import dev.thiagogonzaga.thrillhousebot.github.GitHubInstallationClient.InstallationRepositoriesResponse;
import dev.thiagogonzaga.thrillhousebot.github.GitHubUserClient;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Dashboard access from the login's own GitHub token (#1221). GitHub is mocked: {@code acme} (an
 * organization, installation 10) lets the token read {@code acme/app} (id 100) and {@code acme/lib}
 * (id 101); {@code alice} (a personal account, installation 20) lets it read {@code alice/site} (id
 * 200).
 */
class DashboardAccessCheckerTest {

  private static final String TOKEN = "gho_alice";
  private static final String AUTH = "Bearer " + TOKEN;

  @Mock ThrillhouseConfig config;
  @Mock ThrillhouseConfig.DashboardConfig dashboardConfig;
  @Mock GitHubAuthClient authClient;
  @Mock GitHubInstallationClient installationClient;
  @Mock GitHubUserClient userClient;

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-06-10T12:00:00Z"));

  private DashboardAccessChecker checker;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(config.dashboard()).thenReturn(dashboardConfig);
    when(dashboardConfig.accountOwner()).thenReturn(Optional.empty());
    when(authClient.getAuthHeader(10L)).thenReturn("Bearer acme-installation");
    stubInstallations(
        AUTH, installation(10L, "acme", "Organization"), installation(20L, "alice", "User"));
    stubRepositories(AUTH, 10L, repo(100L, "acme", "app"), repo(101L, "acme", "lib"));
    stubRepositories(AUTH, 20L, repo(200L, "alice", "site"));
    stubMembership("alice", 204);
    checker =
        new DashboardAccessChecker(
            config,
            authClient,
            installationClient,
            userClient,
            new AllowedAccounts(List.of()),
            now::get);
  }

  @Test
  void aLoginSeesEveryRepositoryGitHubLetsItsTokenRead() {
    var resolution = checker.resolve("alice", TOKEN);

    assertEquals(AccessDecision.ALLOWED, resolution.decision());
    var scope = resolution.scope();
    assertEquals(
        Map.of(100L, "acme/app", 101L, "acme/lib"), scope.installations().get(10L).repositories());
    assertEquals(Map.of(200L, "alice/site"), scope.installations().get(20L).repositories());
    assertEquals("acme", scope.installations().get(10L).account());
    assertTrue(scope.allows(10L, 100L));
    assertFalse(scope.allows(20L, 100L), "a repository id under another installation");
  }

  @Test
  void aLoginGitHubShowsNothingIsDenied() {
    stubInstallations("Bearer gho_outsider");

    assertEquals(AccessDecision.DENIED, checker.checkAccess("outsider", "gho_outsider"));
    assertSame(RepositoryScope.NONE, checker.repositoryScope("outsider", "gho_outsider"));
  }

  @Test
  void anInstallationWithNoReadableRepositoryGrantsNothing() {
    stubInstallations("Bearer gho_member", installation(10L, "acme", "Organization"));
    stubRepositories("Bearer gho_member", 10L);
    stubMembership("member", 204);

    assertEquals(AccessDecision.DENIED, checker.checkAccess("member", "gho_member"));
  }

  @Test
  void theInjectedCheckerUsesTheWallClock() {
    var injected =
        new DashboardAccessChecker(
            config, authClient, installationClient, userClient, new AllowedAccounts(List.of()));

    assertEquals(AccessDecision.ALLOWED, injected.checkAccess("alice", TOKEN));
  }

  @Test
  void blankLoginsAndTokensAreDeniedWithoutAskingGitHub() {
    assertEquals(AccessDecision.DENIED, checker.checkAccess(null, TOKEN));
    assertEquals(AccessDecision.DENIED, checker.checkAccess(" ", TOKEN));
    assertEquals(AccessDecision.DENIED, checker.checkAccess("alice", null));
    assertEquals(AccessDecision.DENIED, checker.checkAccess("alice", " "));
    verifyNoInteractions(userClient, installationClient);
  }

  @Test
  void theConfiguredAccountOwnerKeepsOnlyItsInstallation() {
    when(dashboardConfig.accountOwner()).thenReturn(Optional.of(" ALICE "));

    var scope = checker.repositoryScope("alice", TOKEN);

    assertEquals(List.of(20L), scope.sortedInstallations().stream().map(i -> i.id()).toList());
    verify(userClient, never())
        .listRepositories(anyString(), anyString(), eq(10L), anyInt(), anyInt());
  }

  @Test
  void theDashboardListsOnlyAllowedAccountsInstallations() {
    var limited =
        new DashboardAccessChecker(
            config,
            authClient,
            installationClient,
            userClient,
            new AllowedAccounts(List.of("ACME")),
            now::get);

    var scope = limited.repositoryScope("alice", TOKEN);

    assertEquals(List.of(10L), scope.sortedInstallations().stream().map(i -> i.id()).toList());
  }

  @Test
  void aBlankAccountOwnerFiltersNothing() {
    when(dashboardConfig.accountOwner()).thenReturn(Optional.of("  "));

    assertEquals(2, checker.repositoryScope("alice", TOKEN).installations().size());
  }

  @Test
  void installationWideViewsGoToThePersonalAccountItselfAndToOrganizationMembers() {
    var scope = checker.repositoryScope("alice", TOKEN);

    assertTrue(scope.installations().get(20L).installationWide(), "her own account");
    assertTrue(scope.installations().get(10L).installationWide(), "a member of acme");
    assertEquals(java.util.Set.of(10L, 20L), scope.installationWideIds());
  }

  @ParameterizedTest
  @ValueSource(ints = {302, 403, 404})
  void onlyGitHubs204MakesAnOrganizationMember(int status) {
    stubMembership("alice", status);

    var scope = checker.repositoryScope("alice", TOKEN);

    assertFalse(scope.installations().get(10L).installationWide());
    assertTrue(scope.allows(10L, 100L), "viewing still follows read access");
  }

  @Test
  void aRefusedOrFailedMembershipCheckHidesInstallationWideViews() {
    when(installationClient.checkOrgMember(anyString(), anyString(), eq("acme"), eq("alice")))
        .thenThrow(new WebApplicationException(Response.status(403).build()))
        .thenThrow(new IllegalStateException("timeout"));

    assertFalse(
        checker.repositoryScope("alice", TOKEN).installations().get(10L).installationWide());
    now.set(now.get().plus(DashboardAccessChecker.SCOPE_CACHE_TTL).plusSeconds(1));
    assertFalse(
        checker.repositoryScope("alice", TOKEN).installations().get(10L).installationWide());
  }

  @Test
  void aMembershipCheckThatThrowsAnyOtherStatusIsNoMembershipAndWarnsOnce() {
    when(installationClient.checkOrgMember(anyString(), anyString(), eq("acme"), eq("alice")))
        .thenThrow(new WebApplicationException(Response.status(404).build()))
        .thenThrow(new WebApplicationException(Response.status(403).build()))
        .thenThrow(new WebApplicationException(Response.status(403).build()));

    for (var i = 0; i < 3; i++) {
      now.set(now.get().plus(DashboardAccessChecker.SCOPE_CACHE_TTL).plusSeconds(1));
      assertFalse(
          checker.repositoryScope("alice", TOKEN).installations().get(10L).installationWide());
    }
  }

  @Test
  void anotherUsersPersonalAccountIsNotInstallationWide() {
    stubInstallations("Bearer gho_bob", installation(20L, "alice", "User"));
    stubRepositories("Bearer gho_bob", 20L, repo(200L, "alice", "site"));

    var scope = checker.repositoryScope("bob", "gho_bob");

    assertFalse(scope.installations().get(20L).installationWide());
    assertTrue(scope.installationWideIds().isEmpty());
  }

  @Test
  void aTokenGitHubRejectsEndsTheSessionAndIsAskedAgain() {
    when(userClient.listInstallations(eq(AUTH), anyString(), eq(100), eq(1)))
        .thenThrow(new WebApplicationException(Response.status(401).build()));

    assertEquals(AccessDecision.TOKEN_REJECTED, checker.checkAccess("alice", TOKEN));
    assertEquals(AccessDecision.TOKEN_REJECTED, checker.checkAccess("alice", TOKEN));
    verify(userClient, times(2)).listInstallations(eq(AUTH), anyString(), eq(100), eq(1));
  }

  @Test
  void aRejectedTokenWhileListingRepositoriesAlsoEndsTheSession() {
    when(userClient.listRepositories(eq(AUTH), anyString(), eq(20L), eq(100), eq(1)))
        .thenThrow(new WebApplicationException(Response.status(401).build()));

    assertEquals(AccessDecision.TOKEN_REJECTED, checker.checkAccess("alice", TOKEN));
  }

  @Test
  void anUnreachableGitHubDeniesAndIsAskedAgainOnlyAfterABackoff() {
    when(userClient.listInstallations(eq(AUTH), anyString(), eq(100), eq(1)))
        .thenThrow(new WebApplicationException(Response.status(502).build()))
        .thenThrow(new IllegalStateException("connection reset"))
        .thenReturn(
            new GitHubUserClient.UserInstallations(1, List.of(installation(20L, "alice", "User"))));

    assertEquals(AccessDecision.UNAVAILABLE, checker.checkAccess("alice", TOKEN));
    assertEquals(AccessDecision.UNAVAILABLE, checker.checkAccess("alice", TOKEN), "remembered");
    verify(userClient, times(1)).listInstallations(eq(AUTH), anyString(), eq(100), eq(1));

    now.set(now.get().plus(DashboardAccessChecker.RETRY_AFTER_ERROR));
    assertEquals(AccessDecision.UNAVAILABLE, checker.checkAccess("alice", TOKEN));
    now.set(now.get().plus(DashboardAccessChecker.RETRY_AFTER_ERROR));
    assertEquals(AccessDecision.ALLOWED, checker.checkAccess("alice", TOKEN));
  }

  @Test
  void anOutageServesTheLastAnswerGitHubGaveWhileItIsRecent() {
    var answered = checker.repositoryScope("alice", TOKEN);
    when(userClient.listInstallations(eq(AUTH), anyString(), eq(100), eq(1)))
        .thenThrow(new IllegalStateException("GitHub is down"));

    now.set(now.get().plus(DashboardAccessChecker.SCOPE_CACHE_TTL));
    assertSame(answered, checker.repositoryScope("alice", TOKEN), "the last answer");
    assertSame(answered, checker.repositoryScope("alice", TOKEN), "within the backoff");
    verify(userClient, times(2)).listInstallations(eq(AUTH), anyString(), eq(100), eq(1));

    now.set(now.get().plus(DashboardAccessChecker.STALE_SCOPE_LIMIT));
    assertEquals(
        AccessDecision.UNAVAILABLE,
        checker.checkAccess("alice", TOKEN),
        "too old to serve once the outage outlasts the stale limit");
  }

  @Test
  void aWebErrorWithoutAResponseIsUnavailable() {
    when(userClient.listInstallations(eq(AUTH), anyString(), eq(100), eq(1)))
        .thenThrow(new WebApplicationException((Response) null));

    assertEquals(AccessDecision.UNAVAILABLE, checker.checkAccess("alice", TOKEN));
  }

  @Test
  void aResolvedScopeIsCachedPerTokenUntilItExpires() {
    var first = checker.repositoryScope("alice", TOKEN);
    assertSame(first, checker.repositoryScope("ALICE", TOKEN));
    verify(userClient, times(1)).listInstallations(eq(AUTH), anyString(), eq(100), eq(1));

    stubInstallations("Bearer gho_new", installation(20L, "alice", "User"));
    stubRepositories("Bearer gho_new", 20L, repo(200L, "alice", "site"));
    assertNotSame(first, checker.repositoryScope("alice", "gho_new"), "a new sign-in asks again");

    now.set(now.get().plus(DashboardAccessChecker.SCOPE_CACHE_TTL).plusSeconds(1));
    checker.repositoryScope("alice", TOKEN);
    verify(userClient, times(2)).listInstallations(eq(AUTH), anyString(), eq(100), eq(1));
  }

  @Test
  void aDeniedAnswerIsCachedToo() {
    stubInstallations("Bearer gho_outsider");

    checker.checkAccess("outsider", "gho_outsider");
    checker.checkAccess("outsider", "gho_outsider");

    verify(userClient, times(1))
        .listInstallations(eq("Bearer gho_outsider"), anyString(), eq(100), eq(1));
  }

  @Test
  void incompleteEntriesAreSkipped() {
    when(userClient.listInstallations(eq(AUTH), anyString(), eq(100), eq(1)))
        .thenReturn(
            new GitHubUserClient.UserInstallations(
                3,
                java.util.Arrays.asList(
                    new Installation(30L, null),
                    new Installation(31L, new Installation.Account(null, "User")),
                    installation(20L, "alice", "User"))));
    var owner = new InstallationRepositoriesResponse.Repository.Owner("alice");
    when(userClient.listRepositories(eq(AUTH), anyString(), eq(20L), eq(100), eq(1)))
        .thenReturn(
            new InstallationRepositoriesResponse(
                5,
                List.of(
                    new InstallationRepositoriesResponse.Repository(0L, "no-id", owner),
                    new InstallationRepositoriesResponse.Repository(201L, null, owner),
                    new InstallationRepositoriesResponse.Repository(202L, "no-owner", null),
                    new InstallationRepositoriesResponse.Repository(
                        203L,
                        "no-login",
                        new InstallationRepositoriesResponse.Repository.Owner(null)),
                    new InstallationRepositoriesResponse.Repository(200L, "site", owner),
                    new InstallationRepositoriesResponse.Repository(200L, "site", owner))));

    var scope = checker.repositoryScope("alice", TOKEN);

    assertEquals(List.of(20L), scope.sortedInstallations().stream().map(i -> i.id()).toList());
    assertEquals(Map.of(200L, "alice/site"), scope.installations().get(20L).repositories());
  }

  @Test
  void pagesAreFollowedUntilAShortOne() {
    var firstPage =
        IntStream.rangeClosed(1, 100).mapToObj(i -> repo(1_000L + i, "alice", "repo" + i)).toList();
    when(userClient.listRepositories(eq(AUTH), anyString(), eq(20L), eq(100), eq(1)))
        .thenReturn(new InstallationRepositoriesResponse(101, firstPage));
    when(userClient.listRepositories(eq(AUTH), anyString(), eq(20L), eq(100), eq(2)))
        .thenReturn(
            new InstallationRepositoriesResponse(101, List.of(repo(200L, "alice", "site"))));

    assertEquals(
        101,
        checker.repositoryScope("alice", TOKEN).installations().get(20L).repositories().size());
  }

  @Test
  void aListingThatNeverEndsFailsClosed() {
    var fullPage =
        IntStream.rangeClosed(1, 100)
            .mapToObj(i -> installation(i, "org" + i, "Organization"))
            .toList();
    when(userClient.listInstallations(eq(AUTH), anyString(), eq(100), anyInt()))
        .thenReturn(new GitHubUserClient.UserInstallations(100_000, fullPage));

    assertEquals(AccessDecision.UNAVAILABLE, checker.checkAccess("alice", TOKEN));
  }

  @Test
  void anEmptyPageEndsTheListing() {
    when(userClient.listRepositories(eq(AUTH), anyString(), eq(20L), eq(100), eq(1)))
        .thenReturn(new InstallationRepositoriesResponse(0, null));

    assertTrue(
        checker.repositoryScope("alice", TOKEN).installations().get(20L).repositories().isEmpty());
  }

  @Test
  void theSweepKeepsAnEntryThatCanStillServeAsAFallback() {
    checker.checkAccess("alice", TOKEN);
    now.set(now.get().plus(DashboardAccessChecker.SCOPE_CACHE_TTL).plusSeconds(1));
    for (var i = 0; i < DashboardAccessChecker.SCOPE_CACHE_SWEEP_THRESHOLD; i++) {
      stubInstallations("Bearer s" + i);
      checker.checkAccess("sweeper" + i, "s" + i);
    }
    when(userClient.listInstallations(eq(AUTH), anyString(), eq(100), eq(1)))
        .thenThrow(new IllegalStateException("GitHub is down"));

    assertEquals(AccessDecision.ALLOWED, checker.checkAccess("alice", TOKEN));
  }

  @Test
  void theCacheDropsExpiredLoginsOnceItGrows() {
    // One login GitHub never answered for: its remembered error goes with the rest.
    when(userClient.listInstallations(eq("Bearer down"), anyString(), eq(100), eq(1)))
        .thenThrow(new IllegalStateException("GitHub is down"));
    checker.checkAccess("unanswered", "down");
    for (var i = 1; i < DashboardAccessChecker.SCOPE_CACHE_SWEEP_THRESHOLD; i++) {
      stubInstallations("Bearer t" + i);
      checker.checkAccess("user" + i, "t" + i);
    }
    assertEquals(DashboardAccessChecker.SCOPE_CACHE_SWEEP_THRESHOLD, checker.cacheSize());

    now.set(now.get().plus(DashboardAccessChecker.STALE_SCOPE_LIMIT).plus(Duration.ofSeconds(1)));
    checker.checkAccess("alice", TOKEN);

    assertEquals(1, checker.cacheSize());
  }

  private void stubInstallations(String auth, Installation... installations) {
    when(userClient.listInstallations(eq(auth), anyString(), eq(100), eq(1)))
        .thenReturn(
            new GitHubUserClient.UserInstallations(installations.length, List.of(installations)));
  }

  private void stubRepositories(
      String auth, long installationId, InstallationRepositoriesResponse.Repository... repos) {
    when(userClient.listRepositories(eq(auth), anyString(), eq(installationId), eq(100), eq(1)))
        .thenReturn(
            new InstallationRepositoriesResponse(repos.length, new ArrayList<>(List.of(repos))));
  }

  private void stubMembership(String login, int status) {
    var response = mock(Response.class);
    when(response.getStatus()).thenReturn(status);
    when(installationClient.checkOrgMember(
            eq("Bearer acme-installation"), anyString(), eq("acme"), eq(login)))
        .thenReturn(response);
  }

  private static Installation installation(long id, String account, String type) {
    return new Installation(id, new Installation.Account(account, type));
  }

  private static InstallationRepositoriesResponse.Repository repo(
      long id, String owner, String name) {
    return new InstallationRepositoriesResponse.Repository(
        id, name, new InstallationRepositoriesResponse.Repository.Owner(owner));
  }
}
