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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubInstallationClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubInstallationClient.InstallationRepositoriesResponse;
import dev.thiagogonzaga.thrillhousebot.github.RepoScope;
import dev.thiagogonzaga.thrillhousebot.review.FindingFeedback;
import dev.thiagogonzaga.thrillhousebot.review.ReviewLearning;
import dev.thiagogonzaga.thrillhousebot.webhook.PausedPr;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Fills the installation and repository id of rows stored before #1220, against the real schema.
 * GitHub is mocked: installation 10 belongs to {@code acme} and can access {@code acme/app} (id
 * 100); installation 20 belongs to {@code other}.
 */
@QuarkusTest
class ScopeBackfillTest extends ReviewSessionTestSupport {

  private static final RepoScope ACME_APP = new RepoScope(10L, 100L);

  @Inject ScopeBackfillStore store;
  @Inject EntityManager em;

  private final GitHubAuthClient authClient = mock(GitHubAuthClient.class);
  private final GitHubInstallationClient installationClient = mock(GitHubInstallationClient.class);
  private ScopeBackfill backfill;

  @BeforeEach
  void setUp() {
    backfill = new ScopeBackfill(store, authClient, installationClient);
    when(authClient.generateAppJwt()).thenReturn("jwt");
    when(authClient.getAuthHeader(10L)).thenReturn("Bearer acme");
    when(authClient.getAuthHeader(20L)).thenReturn("Bearer other");
    when(installationClient.listInstallations(eq("Bearer jwt"), anyString(), eq(100), eq(1)))
        .thenReturn(List.of(installation(10L, "acme"), installation(20L, "other")));
    when(installationClient.listInstallationRepositories(
            eq("Bearer acme"), anyString(), eq(100), eq(1)))
        .thenReturn(repositories(new Repo(100L, "acme", "app")));
    when(installationClient.listInstallationRepositories(
            eq("Bearer other"), anyString(), eq(100), eq(1)))
        .thenReturn(repositories(new Repo(200L, "other", "lib")));
  }

  @AfterEach
  void cleanUp() throws Exception {
    tx.begin();
    PausedPr.deleteAll();
    FindingFeedback.deleteAll();
    ReviewLearning.deleteAll();
    tx.commit();
  }

  @Test
  void aMatchedRepositoryHasEveryUnscopedRowFilled() throws Exception {
    var session = legacySession("Acme/App");
    legacyPause("acme/app", 7);
    legacyFeedback("ACME/app");
    legacyLearning(10L, "acme/app");
    legacyLearning(99L, "acme/app");

    var result = backfill.backfill();

    assertEquals(new ScopeBackfill.Result(1, 0, 4), result);
    ReviewSession filled = ReviewSession.findById(session.id);
    assertEquals(10L, filled.getInstallationId());
    assertEquals(100L, filled.getRepositoryId());
    assertEquals(0, PausedPr.count("installationId is null"));
    assertEquals(1, PausedPr.count("installationId = 10 and repositoryId = 100"));
    assertEquals(0, FindingFeedback.count("installationId is null"));
    assertEquals(
        1,
        ReviewLearning.count("repositoryId is null"),
        "a learning of another installation is not moved into this one");
  }

  @Test
  void aRenamedRepositoryIsMatchedThroughGitHubsRedirect() throws Exception {
    var session = legacySession("acme/old-name");
    var moved = mock(Response.class);
    when(moved.getStatus()).thenReturn(301);
    when(moved.getHeaderString("Location")).thenReturn("https://api.github.com/repositories/100");
    when(installationClient.getRepository("Bearer acme", ACCEPT, "acme", "old-name"))
        .thenReturn(moved);

    assertEquals(new ScopeBackfill.Result(1, 0, 1), backfill.backfill());
    ReviewSession filled = ReviewSession.findById(session.id);
    assertEquals(ACME_APP.repositoryId(), filled.getRepositoryId());
  }

  @Test
  void anUnmatchedRepositoryKeepsItsRowsHidden() throws Exception {
    var deleted = legacySession("acme/deleted");
    var notFound = mock(Response.class);
    when(notFound.getStatus()).thenReturn(404);
    when(installationClient.getRepository("Bearer acme", ACCEPT, "acme", "deleted"))
        .thenReturn(notFound);
    var elsewhere = mock(Response.class);
    when(elsewhere.getStatus()).thenReturn(301);
    when(elsewhere.getHeaderString("Location"))
        .thenReturn("https://api.github.com/repositories/555");
    when(installationClient.getRepository("Bearer acme", ACCEPT, "acme", "moved-away"))
        .thenReturn(elsewhere);
    var movedAway = legacySession("acme/moved-away");
    var unknownOwner = legacySession("stranger/app");

    assertEquals(new ScopeBackfill.Result(0, 3, 0), backfill.backfill());
    for (var id : List.of(deleted.id, movedAway.id, unknownOwner.id)) {
      ReviewSession row = ReviewSession.findById(id);
      assertNull(row.getInstallationId());
      assertNull(row.getRepositoryId());
    }
  }

  @Test
  void aGitHubErrorWritesNothingAndTheNextStartTriesAgain() throws Exception {
    var session = legacySession("acme/app");
    when(installationClient.listInstallations(eq("Bearer jwt"), anyString(), eq(100), eq(1)))
        .thenThrow(new IllegalStateException("GitHub is down"))
        .thenReturn(List.of(installation(10L, "acme")));

    assertDoesNotThrow(() -> backfill.onStart(mock(StartupEvent.class)));
    assertEquals(1, ReviewSession.count("installationId is null"));

    assertEquals(new ScopeBackfill.Result(1, 0, 1), backfill.backfill());
    assertEquals(1, ReviewSession.count("id = ?1 and installationId = 10", session.id));
  }

  @Test
  void anInstallationWhoseRepositoriesCannotBeListedMatchesNothing() throws Exception {
    legacySession("acme/app");
    legacySession("other/lib");
    when(installationClient.listInstallationRepositories(
            eq("Bearer acme"), anyString(), eq(100), eq(1)))
        .thenThrow(new IllegalStateException("token revoked"));

    assertEquals(new ScopeBackfill.Result(1, 1, 1), backfill.backfill());
    assertEquals(1, ReviewSession.count("installationId = 20 and repositoryId = 200"));
    assertEquals(1, ReviewSession.count("installationId is null"));
  }

  @Test
  void aLookupFailureLeavesTheRepositoryForTheNextStart() throws Exception {
    legacySession("acme/old-name");
    when(installationClient.getRepository("Bearer acme", ACCEPT, "acme", "old-name"))
        .thenThrow(new IllegalStateException("timeout"));

    assertEquals(new ScopeBackfill.Result(0, 1, 0), backfill.backfill());
  }

  @Test
  void aLegacyPauseAlreadyPausedUnderTheScopeIsRemovedNotDuplicated() throws Exception {
    legacyPause("acme/app", 7);
    legacyPause("Acme/App", 7);
    legacyPause("acme/app", 8);

    assertEquals(new ScopeBackfill.Result(1, 0, 3), backfill.backfill());
    assertEquals(2, PausedPr.count());
    assertEquals(1, PausedPr.count("installationId = 10 and repositoryId = 100 and prNumber = 7"));
  }

  @Test
  void aStartWithWorkToDoFillsTheRows() throws Exception {
    legacySession("acme/app");

    backfill.onStart(mock(StartupEvent.class));

    assertEquals(1, ReviewSession.count("installationId = 10 and repositoryId = 100"));
  }

  @Test
  void incompleteRepositoryEntriesAreSkipped() throws Exception {
    legacySession("acme/app");
    var acme = new InstallationRepositoriesResponse.Repository.Owner("acme");
    when(installationClient.listInstallationRepositories(
            eq("Bearer acme"), anyString(), eq(100), eq(1)))
        .thenReturn(
            new InstallationRepositoriesResponse(
                5,
                List.of(
                    new InstallationRepositoriesResponse.Repository(1L, null, acme),
                    new InstallationRepositoriesResponse.Repository(2L, "app", null),
                    new InstallationRepositoriesResponse.Repository(
                        3L, "app", new InstallationRepositoriesResponse.Repository.Owner(null)),
                    new InstallationRepositoriesResponse.Repository(100L, "app", acme),
                    // Listed without an id: skipped, so it neither replaces the entry above nor
                    // aborts the run with a scope that cannot exist.
                    new InstallationRepositoriesResponse.Repository(0L, "app", acme))));
    when(installationClient.listInstallationRepositories(
            eq("Bearer other"), anyString(), eq(100), eq(1)))
        .thenReturn(new InstallationRepositoriesResponse(0, List.of()));

    assertEquals(new ScopeBackfill.Result(1, 0, 1), backfill.backfill());
  }

  @Test
  void aRedirectThatNamesNoRepositoryOrAnAnswerThatIsNoRedirectMatchesNothing() throws Exception {
    legacySession("acme/no-location");
    legacySession("acme/elsewhere");
    legacySession("acme/still-here");
    legacySession("no-slash");
    var noLocation = mock(Response.class);
    when(noLocation.getStatus()).thenReturn(301);
    when(installationClient.getRepository("Bearer acme", ACCEPT, "acme", "no-location"))
        .thenReturn(noLocation);
    var elsewhere = mock(Response.class);
    when(elsewhere.getStatus()).thenReturn(301);
    when(elsewhere.getHeaderString("Location")).thenReturn("https://example.com/somewhere");
    when(installationClient.getRepository("Bearer acme", ACCEPT, "acme", "elsewhere"))
        .thenReturn(elsewhere);
    var ok = mock(Response.class);
    when(ok.getStatus()).thenReturn(200);
    when(installationClient.getRepository("Bearer acme", ACCEPT, "acme", "still-here"))
        .thenReturn(ok);

    assertEquals(new ScopeBackfill.Result(0, 4, 0), backfill.backfill());
    verify(noLocation).close();
    verify(ok).close();
  }

  @Test
  void installationsArePagedUntilAShortPage() throws Exception {
    legacySession("acme/app");
    var fullPage = new java.util.ArrayList<GitHubInstallationClient.Installation>();
    fullPage.add(installation(10L, "acme"));
    for (var i = 1; i < 100; i++) {
      fullPage.add(installation(1000L + i, "filler" + i));
    }
    when(installationClient.listInstallations(eq("Bearer jwt"), anyString(), eq(100), eq(1)))
        .thenReturn(fullPage);
    when(installationClient.listInstallations(eq("Bearer jwt"), anyString(), eq(100), eq(2)))
        .thenReturn(null);

    assertEquals(new ScopeBackfill.Result(1, 0, 1), backfill.backfill());
    verify(installationClient).listInstallations(eq("Bearer jwt"), anyString(), eq(100), eq(2));
  }

  @Test
  void aListingThatNeverEndsIsAnError() throws Exception {
    legacySession("acme/app");
    var fullPage =
        java.util.stream.LongStream.rangeClosed(1, 100)
            .mapToObj(id -> installation(id, "filler" + id))
            .toList();
    when(installationClient.listInstallations(eq("Bearer jwt"), anyString(), eq(100), anyInt()))
        .thenReturn(fullPage);

    assertThrows(IllegalStateException.class, backfill::backfill);
    assertEquals(1, ReviewSession.count("installationId is null"));
  }

  @Test
  void nothingToFillMakesNoGitHubCall() {
    assertEquals(new ScopeBackfill.Result(0, 0, 0), backfill.backfill());
    assertDoesNotThrow(() -> backfill.onStart(mock(StartupEvent.class)));
    verifyNoInteractions(authClient, installationClient);
  }

  @Test
  void aScopedRowIsNeverLookedUpAgain() throws Exception {
    tx.begin();
    ReviewSession.create(ACME_APP, "acme/app", 1, "PR", "sha").persist();
    tx.commit();

    assertEquals(new ScopeBackfill.Result(0, 0, 0), backfill.backfill());
    verify(installationClient, never())
        .listInstallations(anyString(), anyString(), anyInt(), anyInt());
  }

  private static final String ACCEPT = "application/vnd.github+json";

  private record Repo(long id, String owner, String name) {}

  private static GitHubInstallationClient.Installation installation(long id, String account) {
    return new GitHubInstallationClient.Installation(
        id, new GitHubInstallationClient.Installation.Account(account, "Organization"));
  }

  private static InstallationRepositoriesResponse repositories(Repo... repos) {
    return new InstallationRepositoriesResponse(
        repos.length,
        java.util.Arrays.stream(repos)
            .map(
                r ->
                    new InstallationRepositoriesResponse.Repository(
                        r.id(),
                        r.name(),
                        new InstallationRepositoriesResponse.Repository.Owner(r.owner())))
            .toList());
  }

  private ReviewSession legacySession(String repository) throws Exception {
    tx.begin();
    var session = ReviewSession.create(ACME_APP, repository, 1, "PR", "sha");
    session.installationId = null;
    session.repositoryId = null;
    session.persist();
    tx.commit();
    return session;
  }

  private void legacyPause(String repository, int prNumber) throws Exception {
    tx.begin();
    em.createNativeQuery(
            "insert into pausedpr (id, prnumber, repository) values (nextval('pausedpr_seq'), ?1,"
                + " ?2)")
        .setParameter(1, prNumber)
        .setParameter(2, repository)
        .executeUpdate();
    tx.commit();
  }

  private void legacyFeedback(String repository) throws Exception {
    tx.begin();
    em.createNativeQuery(
            "insert into finding_feedback (id, createdat, githubcommentid, prnumber, reactorlogin,"
                + " repository, signal, source) values (nextval('finding_feedback_seq'),"
                + " current_timestamp, 1, 1, 'u', ?1, 'useful', 'reaction')")
        .setParameter(1, repository)
        .executeUpdate();
    tx.commit();
  }

  private void legacyLearning(long installationId, String repository) throws Exception {
    tx.begin();
    em.createNativeQuery(
            "insert into review_learning (id, active, author, createdat, dedupkey, installationid,"
                + " kind, repository, sourceprnumber, sourceurl, text) values"
                + " (nextval('review_learning_seq'), true, 'm', current_timestamp, ?1, ?2,"
                + " 'convention', ?3, 1, 'u', 't')")
        .setParameter(1, "key-" + installationId + repository)
        .setParameter(2, installationId)
        .setParameter(3, repository)
        .executeUpdate();
    tx.commit();
  }
}
