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

import dev.thiagogonzaga.thrillhousebot.LogSafe;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.InstallationChange;
import dev.thiagogonzaga.thrillhousebot.github.PullRequestClosure;
import dev.thiagogonzaga.thrillhousebot.github.RepoScope;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Keeps the stored finding rows (#1319) in step with the pull requests and the installation.
 *
 * <ul>
 *   <li>A pull request closed or merged starts its rows' retention period; reopened, it clears it,
 *       and its findings carry on from where they were.
 *   <li>Uninstalling the App deletes the installation's rows. Suspending it does not: an
 *       unsuspended installation carries on.
 *   <li>A renamed or transferred repository keeps its rows, under the new name.
 *   <li>A daily sweep, also run once at startup, deletes the rows of pull requests closed longer
 *       than {@code FINDINGS_RETENTION} ago. It then asks GitHub about up to {@link #SWEEP_CHECKS}
 *       pull requests whose rows no round wrote for a day, and closes the rows of those GitHub
 *       reports closed, or no longer serves ({@code 404}, {@code 410}), so a missed {@code closed}
 *       delivery does not keep rows forever. Any other failure leaves the rows as they are: an
 *       outage must not start the retention period of every open pull request.
 * </ul>
 */
@ApplicationScoped
public class FindingStateLifecycle {

  /** How often the sweep runs. */
  static final Duration SWEEP_INTERVAL = Duration.ofDays(1);

  /** How long no round wrote a pull request's rows before the sweep asks GitHub about it. */
  static final Duration IDLE = Duration.ofDays(1);

  /** Pull requests the sweep asks GitHub about per run. */
  static final int SWEEP_CHECKS = 50;

  private static final String ACCEPT = "application/vnd.github+json";
  private static final String CLOSED = "closed";

  private final FindingStates states;
  private final GitHubAuthClient authClient;
  private final GitHubPullRequestClient prClient;
  private final Vertx vertx;
  private final Duration retention;
  private final Supplier<Instant> clock;
  private final Executor sweeper;
  private final AtomicBoolean sweeping = new AtomicBoolean();
  private volatile long timerId = -1;

  @Inject
  public FindingStateLifecycle(
      FindingStates states,
      GitHubAuthClient authClient,
      GitHubPullRequestClient prClient,
      Vertx vertx,
      ThrillhouseConfig config) {
    this(states, authClient, prClient, vertx, config.findings().retention(), Instant::now);
  }

  /** Visible for tests: a controllable clock and retention. */
  FindingStateLifecycle(
      FindingStates states,
      GitHubAuthClient authClient,
      GitHubPullRequestClient prClient,
      Vertx vertx,
      Duration retention,
      Supplier<Instant> clock) {
    this(
        states,
        authClient,
        prClient,
        vertx,
        retention,
        clock,
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("finding-sweep-", 0).factory()));
  }

  /** Visible for tests: also where the sweep runs. */
  FindingStateLifecycle(
      FindingStates states,
      GitHubAuthClient authClient,
      GitHubPullRequestClient prClient,
      Vertx vertx,
      Duration retention,
      Supplier<Instant> clock,
      Executor sweeper) {
    this.sweeper = sweeper;
    this.states = states;
    this.authClient = authClient;
    this.prClient = prClient;
    this.vertx = vertx;
    this.retention = retention;
    this.clock = clock;
  }

  void onStart(@Observes StartupEvent event) {
    timerId = vertx.setPeriodic(SWEEP_INTERVAL.toMillis(), id -> scheduleSweep());
    scheduleSweep();
  }

  void onStop(@Observes ShutdownEvent event) {
    if (timerId >= 0) {
      vertx.cancelTimer(timerId);
    }
    if (sweeper instanceof ExecutorService service) {
      service.shutdownNow();
    }
  }

  /** Records a close or a reopen; never calls GitHub. */
  void onClosure(@Observes PullRequestClosure closure) {
    try {
      var rows =
          closure.closed()
              ? states.markClosed(closure.scope(), closure.prNumber())
              : states.reopen(closure.scope(), closure.prNumber());
      if (rows > 0) {
        Log.infof(
            "Pull request #%d %s: %d stored finding(s) %s",
            closure.prNumber(),
            closure.closed() ? CLOSED : "reopened",
            rows,
            closure.closed() ? "start their retention period" : "carry on");
      }
    } catch (RuntimeException e) {
      Log.warnf(
          "Could not record pull request #%d %s on its stored findings: %s",
          closure.prNumber(),
          closure.closed() ? CLOSED : "reopened",
          LogSafe.oneLine(e.toString()));
    }
  }

  /** Deletes an uninstalled installation's rows and follows a repository rename. */
  void onInstallationChange(@Observes InstallationChange change) {
    try {
      if (change.kind() == InstallationChange.Kind.GONE && change.uninstalled()) {
        var deleted = states.deleteInstallation(change.installationId());
        Log.infof(
            "Installation %d uninstalled: deleted %d stored finding(s)",
            change.installationId(), deleted);
      } else if (change.kind() == InstallationChange.Kind.REPOSITORY_RENAMED) {
        for (var repository : change.repositories()) {
          states.rename(change.installationId(), repository.id(), repository.fullName());
        }
      }
    } catch (RuntimeException e) {
      Log.warnf(
          "Could not apply installation %d's %s to its stored findings: %s",
          change.installationId(), change.kind(), LogSafe.oneLine(e.toString()));
    }
  }

  /** Runs the sweep on a virtual thread; the timer fires on the event loop, which must not wait. */
  void scheduleSweep() {
    if (!sweeping.compareAndSet(false, true)) {
      return;
    }
    try {
      sweeper.execute(
          () -> {
            try {
              sweep();
            } finally {
              sweeping.set(false);
            }
          });
    } catch (RuntimeException e) {
      sweeping.set(false);
      Log.warn("Could not schedule the stored findings sweep", e);
    }
  }

  /** Whether a sweep is running. Visible for tests. */
  boolean sweepRunning() {
    return sweeping.get();
  }

  /**
   * Deletes expired rows, then closes the rows of idle pull requests GitHub reports closed or no
   * longer serves.
   *
   * @return how many pull requests it found closed
   */
  int sweep() {
    var now = clock.get();
    try {
      var deleted = states.deleteClosedBefore(now.minus(retention));
      if (deleted > 0) {
        Log.infof(
            "Deleted %d stored finding(s) of pull requests closed over %s ago", deleted, retention);
      }
      var closed = 0;
      for (var pr : states.idleOpenPullRequests(now.minus(IDLE), SWEEP_CHECKS)) {
        var scope = new RepoScope(pr.installationId(), pr.repositoryId());
        if (isClosed(pr)) {
          states.markClosed(scope, pr.prNumber());
          closed++;
        } else {
          // Checked now: the next sweeps ask about the pull requests not checked for longest.
          states.touch(scope, pr.prNumber(), now);
        }
      }
      if (closed > 0) {
        Log.infof("The sweep found %d pull request(s) closed without a closed event", closed);
      }
      return closed;
    } catch (RuntimeException e) {
      Log.warnf("The stored findings sweep failed: %s", LogSafe.oneLine(e.toString()));
      return 0;
    }
  }

  /**
   * Whether GitHub reports the pull request closed, or no longer serves it. Any other failure, an
   * installation token among them, says nothing, and the rows stay open.
   */
  private boolean isClosed(FindingStates.PullRequestRef pr) {
    var slash = pr.repository().indexOf('/');
    if (slash <= 0) {
      return false;
    }
    try {
      var details =
          prClient.getPullRequest(
              authClient.getAuthHeader(pr.installationId(), pr.repository().substring(slash + 1)),
              ACCEPT,
              pr.repository().substring(0, slash),
              pr.repository().substring(slash + 1),
              pr.prNumber());
      return CLOSED.equals(details.state());
    } catch (WebApplicationException e) {
      var status = e.getResponse().getStatus();
      return status == 404 || status == 410;
    } catch (RuntimeException e) {
      Log.debugf(
          "Could not read %s #%d for the sweep: %s",
          LogSafe.oneLine(pr.repository()), pr.prNumber(), LogSafe.oneLine(e.toString()));
      return false;
    }
  }
}
