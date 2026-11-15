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
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.RepoScope;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The stored finding rows (#1319): read at the start of a round, written once the round is on the
 * pull request, and closed, reopened, swept and deleted as the pull request and the installation
 * change. Short transactions only; no call to GitHub runs inside one.
 */
@ApplicationScoped
public class FindingStates implements PanacheRepository<FindingState> {

  private static final String SCOPE_PR =
      "installationId = ?1 and repositoryId = ?2 and prNumber = ?3";

  private final BotIdentity botIdentity;
  private final Supplier<Instant> clock;

  @Inject
  public FindingStates(BotIdentity botIdentity) {
    this(botIdentity, Instant::now);
  }

  /** Visible for tests: a controllable clock. */
  FindingStates(BotIdentity botIdentity, Supplier<Instant> clock) {
    this.botIdentity = botIdentity;
    this.clock = clock;
  }

  /** A pull request whose rows are kept, and where to ask GitHub about it. */
  public record PullRequestRef(
      long installationId, long repositoryId, String repository, int prNumber) {}

  /**
   * A round's rows, worked out before the round posts anything and written after.
   *
   * @param known whether the pull request had stored rows to compare the round with
   */
  public record Pending(
      RepoScope scope,
      String repository,
      int prNumber,
      FindingLedger.Update update,
      boolean known) {

    /**
     * Whether the round moved anything: it raised a new finding, or a previous finding's status
     * differs from the stored one. A round on a pull request with no stored rows always has, as
     * nothing shows otherwise.
     */
    public boolean moved(ReviewResult result) {
      return !known || update.statusMoved() || FollowUpDeltaSummary.newFindingCount(result) > 0;
    }
  }

  /**
   * The rows {@code result} adds or changes for {@code req}'s pull request, compared with the
   * stored ones. A failure to read them leaves the round as if the pull request had none, and
   * writes nothing.
   */
  public Pending prepare(
      ReviewOrchestrator.ReviewRequest req,
      ReviewContextLoader.ReviewContext ctx,
      ReviewResult result) {
    var repository = req.owner() + "/" + req.repo();
    try {
      var stored = load(req.scope(), req.prNumber());
      var update =
          FindingLedger.afterRound(
              stored,
              new FindingLedger.Round(
                  req.commitSha(),
                  result.findings(),
                  FindingLedger.statuses(result.previousStatuses(), ctx.previousFindingsList()),
                  headBlobs(ctx.files()),
                  ctx.lineResolver(),
                  FindingLedger.threads(ctx.inlineComments(), botIdentity, req.commitSha())));
      return new Pending(req.scope(), repository, req.prNumber(), update, !stored.isEmpty());
    } catch (RuntimeException e) {
      Log.warnf(
          "Could not work out the finding states of %s #%d; they are not recorded this round: %s",
          LogSafe.oneLine(repository), req.prNumber(), LogSafe.oneLine(e.toString()));
      return new Pending(
          req.scope(),
          repository,
          req.prNumber(),
          new FindingLedger.Update(Map.of(), false),
          false);
    }
  }

  /**
   * Which files changed since each earlier round, read from {@code req}'s pull request's stored
   * rows first and from {@code fallback} where they do not know ({@link StoredRoundChanges}). Rows
   * that cannot be read leave {@code fallback} alone to answer.
   *
   * @param roundHeads each prior round's head, newest first; empty when the heads cannot be told
   * @param files the pull request's files at this head, with their blob SHAs
   */
  public DiffLineResolver.RoundChanges roundChanges(
      ReviewOrchestrator.ReviewRequest req,
      List<String> roundHeads,
      List<GitHubPullRequestClient.FileDiff> files,
      DiffLineResolver.RoundChanges fallback) {
    try {
      return new StoredRoundChanges(
          load(req.scope(), req.prNumber()).values(), roundHeads, headBlobs(files), fallback);
    } catch (RuntimeException e) {
      Log.warnf(
          "Could not read the stored findings of %s/%s #%d; deciding without them: %s",
          LogSafe.oneLine(req.owner()),
          LogSafe.oneLine(req.repo()),
          req.prNumber(),
          LogSafe.oneLine(e.toString()));
      return fallback;
    }
  }

  /** Each changed file's blob SHA at the head, where GitHub gives one. */
  static Map<String, String> headBlobs(List<GitHubPullRequestClient.FileDiff> files) {
    var blobs = new HashMap<String, String>();
    for (var file : files) {
      if (file.filename() != null && file.sha() != null) {
        blobs.put(file.filename(), file.sha());
      }
    }
    return blobs;
  }

  /** Writes what {@link #prepare} worked out. */
  @Transactional
  public void save(Pending pending) {
    save(pending.scope(), pending.repository(), pending.prNumber(), pending.update().rows());
  }

  /** The pull request's rows, by finding key. */
  @Transactional
  public Map<String, FindingLedger.Row> load(RepoScope scope, int prNumber) {
    var rows = new LinkedHashMap<String, FindingLedger.Row>();
    find(SCOPE_PR, scope.installationId(), scope.repositoryId(), prNumber).stream()
        .forEach(state -> rows.put(state.findingKey, row(state)));
    return rows;
  }

  /**
   * Writes the rows a round added or changed. A row of a pull request already closed keeps its
   * close time.
   */
  @Transactional
  public void save(
      RepoScope scope, String repository, int prNumber, Map<String, FindingLedger.Row> rows) {
    if (rows.isEmpty()) {
      return;
    }
    var existing = new LinkedHashMap<String, FindingState>();
    find(SCOPE_PR, scope.installationId(), scope.repositoryId(), prNumber).stream()
        .forEach(state -> existing.put(state.findingKey, state));
    var now = clock.get();
    for (var row : rows.values()) {
      var state = existing.get(row.key());
      if (state == null) {
        state = new FindingState();
        state.installationId = scope.installationId();
        state.repositoryId = scope.repositoryId();
        state.prNumber = prNumber;
        state.findingKey = row.key();
      }
      state.repository = repository;
      write(state, row);
      state.updatedAt = now;
      persist(state);
    }
  }

  /** Starts the retention period of a closed or merged pull request's rows. */
  @Transactional
  public long markClosed(RepoScope scope, int prNumber) {
    var now = clock.get();
    var open =
        find(
                SCOPE_PR + " and prClosedAt is null",
                scope.installationId(),
                scope.repositoryId(),
                prNumber)
            .list();
    for (var state : open) {
      state.prClosedAt = now;
      state.updatedAt = now;
    }
    return open.size();
  }

  /** A reopened pull request's rows carry on from where they were. */
  @Transactional
  public long reopen(RepoScope scope, int prNumber) {
    return update(
        "prClosedAt = null, updatedAt = ?4 where " + SCOPE_PR + " and prClosedAt is not null",
        scope.installationId(),
        scope.repositoryId(),
        prNumber,
        clock.get());
  }

  /** Deletes every row of an uninstalled installation. */
  @Transactional
  public long deleteInstallation(long installationId) {
    return delete("installationId", installationId);
  }

  /** Keeps a renamed or transferred repository's name current for the sweep. */
  @Transactional
  public long rename(long installationId, long repositoryId, String fullName) {
    return update(
        "repository = ?1 where installationId = ?2 and repositoryId = ?3 and repository <> ?1",
        fullName,
        installationId,
        repositoryId);
  }

  /** Deletes the rows of pull requests closed before {@code cutoff}. */
  @Transactional
  public long deleteClosedBefore(Instant cutoff) {
    return delete("prClosedAt < ?1", cutoff);
  }

  /**
   * Up to {@code limit} pull requests with rows still open that no round has written since {@code
   * idleSince}, least recently written first: those the sweep asks GitHub about.
   */
  @Transactional
  public List<PullRequestRef> idleOpenPullRequests(Instant idleSince, int limit) {
    return getEntityManager()
        .createQuery(
            """
            select s.installationId, s.repositoryId, s.repository, s.prNumber from FindingState s
            where s.prClosedAt is null
            group by s.installationId, s.repositoryId, s.repository, s.prNumber
            having max(s.updatedAt) < :idleSince order by max(s.updatedAt)""",
            Object[].class)
        .setParameter("idleSince", idleSince)
        .setMaxResults(limit)
        .getResultStream()
        .map(
            columns ->
                new PullRequestRef(
                    (Long) columns[0],
                    (Long) columns[1],
                    (String) columns[2],
                    (Integer) columns[3]))
        .toList();
  }

  /** The row {@code state} holds. */
  static FindingLedger.Row row(FindingState state) {
    return new FindingLedger.Row(
        state.findingKey,
        new FindingLedger.Subject(
            state.file, state.line, state.risk, state.confidence, state.title, state.anchor),
        state.anchorSide == null
            ? null
            : new DiffLineResolver.RaisedAnchor(
                DiffLineResolver.Side.valueOf(state.anchorSide),
                Boolean.TRUE.equals(state.anchorMatched)),
        state.blobSha,
        new FindingLedger.Status(state.status, state.raisedHead, state.statusHead),
        new FindingLedger.Thread(state.threadId, state.threadHead));
  }

  private static void write(FindingState state, FindingLedger.Row row) {
    var subject = row.subject();
    state.file = subject.file();
    state.line = subject.line();
    state.risk = subject.risk();
    state.confidence = subject.confidence();
    state.title = subject.title();
    state.anchor = subject.quote();
    state.anchorSide = row.anchor() == null ? null : row.anchor().side().name();
    state.anchorMatched = row.anchor() == null ? null : row.anchor().matched();
    state.blobSha = row.blobSha();
    state.status = row.status().value();
    state.raisedHead = row.status().raisedHead();
    state.statusHead = row.status().changedHead();
    state.threadId = row.thread().id();
    state.threadHead = row.thread().head();
  }
}
