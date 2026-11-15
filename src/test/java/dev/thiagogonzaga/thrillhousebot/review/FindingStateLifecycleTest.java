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

import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.InstallationChange;
import dev.thiagogonzaga.thrillhousebot.github.PullRequestClosure;
import dev.thiagogonzaga.thrillhousebot.github.RepoScope;
import dev.thiagogonzaga.thrillhousebot.review.DiffLineResolver.RaisedAnchor;
import dev.thiagogonzaga.thrillhousebot.review.DiffLineResolver.Side;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import jakarta.ws.rs.WebApplicationException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The stored finding rows' lifecycle (#1319): written and read back, closed, reopened, deleted
 * after the retention period, swept when a {@code closed} event was missed, and deleted on
 * uninstall.
 */
@QuarkusTest
class FindingStateLifecycleTest {

  private static final RepoScope SCOPE = new RepoScope(31L, 310L);
  private static final RepoScope OTHER_INSTALLATION = new RepoScope(32L, 320L);
  private static final int PR = 5;
  private static final Duration RETENTION = Duration.ofDays(30);

  @Inject FindingStates states;
  @Inject UserTransaction tx;

  private final GitHubAuthClient auth = mock(GitHubAuthClient.class);
  private final GitHubPullRequestClient prClient = mock(GitHubPullRequestClient.class);
  private final Vertx vertx = mock(Vertx.class);

  @AfterEach
  void cleanup() throws Exception {
    tx.begin();
    FindingState.deleteAll();
    tx.commit();
  }

  private static FindingLedger.Row row(String key, String status) {
    return new FindingLedger.Row(
        key,
        new FindingLedger.Subject("src/A.java", 3, "high", "medium", "Title " + key, "x();"),
        new RaisedAnchor(Side.LEFT, false),
        "blob",
        new FindingLedger.Status(status, "head1", "head2"),
        new FindingLedger.Thread(77L, "head1"));
  }

  private void store(RepoScope scope, int pr, String... keys) {
    var rows = new java.util.LinkedHashMap<String, FindingLedger.Row>();
    for (var key : keys) {
      rows.put(key, row(key, FindingLedger.OPEN));
    }
    states.save(scope, "owner/repo", pr, rows);
  }

  private FindingStateLifecycle lifecycle(Instant now) {
    return new FindingStateLifecycle(states, auth, prClient, vertx, RETENTION, () -> now);
  }

  private static GitHubPullRequestClient.PullRequestDetails details(String state) {
    return new GitHubPullRequestClient.PullRequestDetails(
        "t",
        "b",
        new GitHubPullRequestClient.Ref("h"),
        new GitHubPullRequestClient.Ref("b"),
        0,
        0,
        0,
        state);
  }

  @Test
  void rowsAreWrittenAndReadBackWithEverythingTheyStore() {
    store(SCOPE, PR, "aaaa0001");
    states.save(
        SCOPE, "owner/renamed", PR, Map.of("aaaa0001", row("aaaa0001", FindingLedger.RESOLVED)));
    states.save(SCOPE, "owner/repo", PR, Map.of());

    var loaded = states.load(SCOPE, PR);

    assertEquals(Map.of("aaaa0001", row("aaaa0001", FindingLedger.RESOLVED)), loaded);
    assertEquals(1, FindingState.count());
    assertTrue(states.load(SCOPE, PR + 1).isEmpty());
    assertTrue(states.load(OTHER_INSTALLATION, PR).isEmpty());
  }

  @Test
  void theStoredRowsTellWhichFilesAreUnchangedSinceTheRoundThatRaisedThem() {
    store(SCOPE, PR, "aaaa0002");
    var unchanged =
        List.of(
            new GitHubPullRequestClient.FileDiff(
                "src/A.java", "modified", 1, 1, 2, "", null, "blob"));

    var changes =
        states.roundChanges(
            request("head2"), List.of("head1"), unchanged, DiffLineResolver.RoundChanges.NONE);

    assertEquals(DiffLineResolver.FileChange.UNCHANGED, changes.since(0, "src/A.java"));
    assertEquals(
        java.util.Optional.of(new RaisedAnchor(Side.LEFT, false)),
        changes.raisedAnchor("aaaa0002"));
  }

  @Test
  void anOverlongModelWrittenValueIsCutToItsColumn() {
    var longTitle = "t".repeat(FindingStates.TITLE_LENGTH + 20);
    var row =
        new FindingLedger.Row(
            "kkkk0001",
            new FindingLedger.Subject(
                "f".repeat(FindingStates.FILE_LENGTH + 1),
                3,
                "critical!!!!!!!!!!",
                "high",
                longTitle,
                "x"),
            null,
            null,
            new FindingLedger.Status(FindingLedger.OPEN, null, "h"),
            FindingLedger.Thread.NONE);

    states.save(SCOPE, "owner/repo", PR, Map.of(row.key(), row));

    var subject = states.load(SCOPE, PR).get("kkkk0001").subject();
    assertEquals(FindingStates.TITLE_LENGTH, subject.title().length());
    assertEquals(FindingStates.FILE_LENGTH, subject.file().length());
    assertEquals(FindingStates.LABEL_LENGTH, subject.risk().length());
  }

  @Test
  void aRowStoredWithoutAnAnchorReadsBackWithout() {
    var bare =
        new FindingLedger.Row(
            "bbbb0001",
            new FindingLedger.Subject(null, 0, null, null, null, null),
            null,
            null,
            new FindingLedger.Status(FindingLedger.OPEN, null, "h"),
            FindingLedger.Thread.NONE);
    states.save(SCOPE, "owner/repo", PR, Map.of(bare.key(), bare));

    assertEquals(bare, states.load(SCOPE, PR).get(bare.key()));
  }

  @Test
  void aClosedPullRequestsRowsAreDeletedOnlyAfterTheRetentionPeriod() {
    store(SCOPE, PR, "cccc0001", "cccc0002");
    store(SCOPE, PR + 1, "cccc0003");
    var now = Instant.now();
    var lifecycle = lifecycle(now);

    lifecycle.onClosure(new PullRequestClosure(SCOPE, PR, true));
    FindingState closed = FindingState.find("prNumber", PR).firstResult();
    var closedAt = closed.closedAt();
    lifecycle.onClosure(new PullRequestClosure(SCOPE, PR, true));
    FindingState closedAgain = FindingState.find("prNumber", PR).firstResult();
    assertEquals(closedAt, closedAgain.closedAt(), "a second close keeps the first close time");

    assertEquals(0, lifecycle(now.plus(RETENTION).minus(Duration.ofHours(1))).sweep());
    assertEquals(2, states.load(SCOPE, PR).size(), "kept within the retention period");
    lifecycle(now.plus(RETENTION).plus(Duration.ofHours(1))).sweep();
    assertTrue(states.load(SCOPE, PR).isEmpty(), "deleted after it");
    assertEquals(1, states.load(SCOPE, PR + 1).size(), "an open pull request's rows stay");
  }

  @Test
  void aReopenedPullRequestKeepsItsRowsAndCarriesOn() {
    store(SCOPE, PR, "dddd0001");
    var now = Instant.now();
    var lifecycle = lifecycle(now);

    lifecycle.onClosure(new PullRequestClosure(SCOPE, PR, true));
    lifecycle.onClosure(new PullRequestClosure(SCOPE, PR, false));
    lifecycle.onClosure(new PullRequestClosure(SCOPE, PR, false));
    when(prClient.getPullRequest(any(), any(), any(), any(), anyInt())).thenReturn(details("open"));
    lifecycle(now.plus(RETENTION).plus(Duration.ofDays(1))).sweep();

    assertEquals(Map.of("dddd0001", row("dddd0001", FindingLedger.OPEN)), states.load(SCOPE, PR));
    assertEquals(0, FindingState.count("prClosedAt is not null"));
  }

  @Test
  void theSweepClosesThePullRequestsGitHubReportsClosedOrNoLongerServes() {
    store(SCOPE, 1, "eeee0001");
    store(SCOPE, 2, "eeee0002");
    store(SCOPE, 3, "eeee0003");
    store(SCOPE, 4, "eeee0004");
    when(auth.getAuthHeader(31L)).thenReturn("token t");
    when(prClient.getPullRequest(eq("token t"), any(), eq("owner"), eq("repo"), anyInt()))
        .thenAnswer(
            call ->
                switch ((int) call.getArgument(4)) {
                  case 1 -> details("closed");
                  case 2 -> throw new WebApplicationException(404);
                  case 3 -> throw new WebApplicationException(410);
                  default -> details("open");
                });
    var now = Instant.now();

    assertEquals(0, lifecycle(now).sweep(), "rows a round wrote within a day are not checked");
    verifyNoInteractions(prClient);
    assertEquals(3, lifecycle(now.plus(Duration.ofDays(2))).sweep());

    assertEquals(3, FindingState.count("prClosedAt is not null"));
    assertEquals(0, FindingState.count("prNumber = 4 and prClosedAt is not null"));
    // The open one is marked checked, so the next sweep the same day asks about others first.
    assertEquals(0, lifecycle(now.plus(Duration.ofDays(2))).sweep());
    verify(prClient, times(1)).getPullRequest(any(), any(), any(), any(), eq(4));
  }

  @Test
  void aFailureToReachGitHubLeavesTheRowsOpen() {
    store(SCOPE, 1, "ffff0001");
    store(SCOPE, 2, "ffff0002");
    store(OTHER_INSTALLATION, 3, "ffff0003");
    states.save(
        new RepoScope(33L, 330L), "no-slash", 4, Map.of("ffff0004", row("ffff0004", "open")));
    when(auth.getAuthHeader(31L)).thenReturn("token t");
    when(auth.getAuthHeader(32L)).thenThrow(new IllegalStateException("suspended"));
    when(prClient.getPullRequest(eq("token t"), any(), any(), any(), eq(1)))
        .thenThrow(new WebApplicationException(502));
    when(prClient.getPullRequest(eq("token t"), any(), any(), any(), eq(2)))
        .thenThrow(new IllegalStateException("connection reset"));

    assertEquals(0, lifecycle(Instant.now().plus(Duration.ofDays(2))).sweep());

    assertEquals(0, FindingState.count("prClosedAt is not null"));
  }

  @Test
  void aFailingSweepIsLoggedAndReportsNothing() {
    var broken = mock(FindingStates.class);
    when(broken.deleteClosedBefore(any())).thenThrow(new IllegalStateException("db down"));

    assertEquals(
        0,
        new FindingStateLifecycle(broken, auth, prClient, vertx, RETENTION, Instant::now).sweep());
  }

  @Test
  void uninstallingDeletesTheInstallationsRowsAndSuspendingDoesNot() {
    store(SCOPE, PR, "gggg0001");
    store(OTHER_INSTALLATION, PR, "gggg0002");
    var lifecycle = lifecycle(Instant.now());

    lifecycle.onInstallationChange(
        new InstallationChange(InstallationChange.Kind.GONE, SCOPE.installationId(), List.of()));
    assertEquals(1, states.load(SCOPE, PR).size(), "a suspended installation keeps its rows");
    lifecycle.onInstallationChange(
        new InstallationChange(
            InstallationChange.Kind.BACK, SCOPE.installationId(), List.of(), true));
    assertEquals(1, states.load(SCOPE, PR).size(), "only a GONE change deletes");

    lifecycle.onInstallationChange(
        new InstallationChange(
            InstallationChange.Kind.GONE, SCOPE.installationId(), List.of(), true));

    assertTrue(states.load(SCOPE, PR).isEmpty());
    assertEquals(1, states.load(OTHER_INSTALLATION, PR).size());
  }

  @Test
  void aRenamedRepositoryKeepsItsRowsUnderTheNewName() {
    store(SCOPE, PR, "hhhh0001");
    when(auth.getAuthHeader(31L)).thenReturn("token t");
    when(prClient.getPullRequest(any(), any(), eq("owner"), eq("renamed"), eq(PR)))
        .thenReturn(details("closed"));

    lifecycle(Instant.now())
        .onInstallationChange(
            new InstallationChange(
                InstallationChange.Kind.REPOSITORY_RENAMED,
                SCOPE.installationId(),
                List.of(new InstallationChange.Repository(SCOPE.repositoryId(), "owner/renamed"))));
    lifecycle(Instant.now().plus(Duration.ofDays(2))).sweep();

    assertEquals(
        1, FindingState.count("repository = ?1 and prClosedAt is not null", "owner/renamed"));
  }

  @Test
  void aFailedWriteOnAnEventIsLoggedAndNotThrown() {
    var broken = mock(FindingStates.class);
    when(broken.markClosed(any(), anyInt())).thenThrow(new IllegalStateException("db down"));
    when(broken.reopen(any(), anyInt())).thenThrow(new IllegalStateException("db down"));
    when(broken.deleteInstallation(anyLong())).thenThrow(new IllegalStateException("db down"));
    var lifecycle =
        new FindingStateLifecycle(broken, auth, prClient, vertx, RETENTION, Instant::now);

    assertDoesNotThrow(() -> lifecycle.onClosure(new PullRequestClosure(SCOPE, PR, true)));
    assertDoesNotThrow(() -> lifecycle.onClosure(new PullRequestClosure(SCOPE, PR, false)));
    assertDoesNotThrow(
        () ->
            lifecycle.onInstallationChange(
                new InstallationChange(InstallationChange.Kind.GONE, 31L, List.of(), true)));
  }

  @Test
  void theSweepRunsAtStartupAndDailyOffTheEventLoopAndStopsWithTheApplication() {
    var timer = new AtomicReference<Handler<Long>>();
    when(vertx.setPeriodic(eq(FindingStateLifecycle.SWEEP_INTERVAL.toMillis()), any()))
        .thenAnswer(
            call -> {
              timer.set(call.getArgument(1));
              return 9L;
            });
    var broken = mock(FindingStates.class);
    var queued = new java.util.ArrayList<Runnable>();
    var lifecycle =
        new FindingStateLifecycle(
            broken, auth, prClient, vertx, RETENTION, Instant::now, queued::add);

    lifecycle.onStart(new StartupEvent());
    assertEquals(1, queued.size(), "the startup sweep is handed off, not run on the caller");
    queued.get(0).run();
    timer.get().handle(9L);
    assertEquals(2, queued.size(), "the timer hands off another");
    queued.get(1).run();
    lifecycle.onStop(new ShutdownEvent());

    verify(broken, times(2)).deleteClosedBefore(any());
    verify(vertx).cancelTimer(9L);
  }

  @Test
  void aSweepIsNotStartedWhileOneIsRunning() {
    var broken = mock(FindingStates.class);
    var queued = new java.util.ArrayList<Runnable>();
    var lifecycle =
        new FindingStateLifecycle(
            broken, auth, prClient, vertx, RETENTION, Instant::now, queued::add);

    lifecycle.scheduleSweep();
    lifecycle.scheduleSweep();
    assertEquals(1, queued.size());
    assertTrue(lifecycle.sweepRunning());
    queued.get(0).run();
    assertFalse(lifecycle.sweepRunning());
    lifecycle.scheduleSweep();

    assertEquals(2, queued.size(), "a finished sweep lets the next one start");
  }

  @Test
  void aSweepThatCannotBeHandedOffIsLoggedAndCanBeTriedAgain() {
    var lifecycle =
        new FindingStateLifecycle(
            states,
            auth,
            prClient,
            vertx,
            RETENTION,
            Instant::now,
            task -> {
              throw new java.util.concurrent.RejectedExecutionException("stopped");
            });

    assertDoesNotThrow(lifecycle::scheduleSweep);
    assertFalse(lifecycle.sweepRunning());
  }

  @Test
  void theApplicationsSweeperStopsWithIt() {
    var lifecycle = lifecycle(Instant.now());

    lifecycle.onStop(new ShutdownEvent());

    assertDoesNotThrow(lifecycle::scheduleSweep, "a stopped sweeper only logs");
    assertFalse(lifecycle.sweepRunning());
  }

  @Test
  void aStopBeforeAStartCancelsNoTimer() {
    lifecycle(Instant.now()).onStop(new ShutdownEvent());

    verify(vertx, never()).cancelTimer(anyLong());
  }

  private static ReviewResult result(
      List<Finding> findings, List<ReviewResult.PreviousFindingStatus> statuses) {
    return new ReviewResult(
        findings,
        0,
        0,
        findings.size(),
        0,
        RiskLevel.MEDIUM,
        ReviewState.COMMENT,
        false,
        "summary",
        statuses,
        List.of(),
        0);
  }

  private static ReviewOrchestrator.ReviewRequest request(String head) {
    return new ReviewOrchestrator.ReviewRequest(
        "owner", "repo", PR, head, "t", "b", "base", "main", 31L, 310L, false, "main", false);
  }

  private static ReviewContextLoader.ReviewContext context(
      List<dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse.Finding> previous) {
    var ctx = mock(ReviewContextLoader.ReviewContext.class);
    when(ctx.previousFindingsList()).thenReturn(previous);
    when(ctx.files())
        .thenReturn(
            List.of(
                new GitHubPullRequestClient.FileDiff(
                    "src/A.java",
                    "modified",
                    1,
                    1,
                    2,
                    "@@ -1 +1 @@\n-a();\n+b();",
                    null,
                    "blob-h")));
    when(ctx.lineResolver())
        .thenReturn(new DiffLineResolver(Map.of("src/A.java", "@@ -1 +1 @@\n-a();\n+b();")));
    when(ctx.inlineComments()).thenReturn(List.of());
    return ctx;
  }

  @Test
  void aRoundThatRaisesNothingAndMovesNoStoredStatusHasNotMoved() {
    var raised = new Finding(RiskLevel.MEDIUM, "src/A.java", 1, "B is wrong", "d", "b();", null);
    var first = result(List.of(raised), List.of());

    var round1 = states.prepare(request("head1"), context(List.of()), first);
    assertTrue(round1.moved(first), "a pull request with no rows has nothing to compare with");
    states.save(round1);
    var row = states.load(SCOPE, PR).values().iterator().next();
    assertEquals("blob-h", row.blobSha());
    assertEquals(new FindingLedger.Status(FindingLedger.OPEN, "head1", "head1"), row.status());

    var previous =
        List.of(
            new dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse.Finding(
                "medium", "src/A.java", 1, "B is wrong", "d", "b();", null));
    var holds =
        result(List.of(), List.of(new ReviewResult.PreviousFindingStatus(1, "unresolved", "n")));
    var round2 = states.prepare(request("head2"), context(previous), holds);
    assertFalse(round2.moved(holds), "the same status again moves nothing");
    states.save(round2);

    var fixes =
        result(List.of(), List.of(new ReviewResult.PreviousFindingStatus(1, "resolved", "n")));
    var round3 = states.prepare(request("head3"), context(previous), fixes);
    assertTrue(round3.moved(fixes));
    states.save(round3);
    assertEquals(
        new FindingLedger.Status(FindingLedger.RESOLVED, "head1", "head3"),
        states.load(SCOPE, PR).values().iterator().next().status());

    var raisesAgain =
        result(
            List.of(new Finding(RiskLevel.LOW, "src/A.java", 1, "New", "d", null, null)),
            List.of());
    assertTrue(states.prepare(request("head4"), context(previous), raisesAgain).moved(raisesAgain));
  }

  @Test
  void aRoundWhoseRowsCannotBeWorkedOutWritesNothingAndMovesAsBefore() {
    var ctx = mock(ReviewContextLoader.ReviewContext.class);
    when(ctx.previousFindingsList()).thenThrow(new IllegalStateException("broken context"));
    var holds = result(List.of(), List.of());

    var pending = states.prepare(request("head1"), ctx, holds);

    assertTrue(pending.moved(holds));
    states.save(pending);
    assertEquals(0, FindingState.count());
  }
}
