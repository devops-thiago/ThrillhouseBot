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

import dev.thiagogonzaga.thrillhousebot.github.RepoScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persists and aggregates {@link FindingFeedback} rows. Short DB transactions only — never spans a
 * GitHub API call. Idempotent inserts: duplicate reaction ids or the same (comment, reactor,
 * signal, source) are ignored via pre-insert existence checks.
 *
 * <p>{@link #summarize} is the seam a future learnings {@code ContextProvider} (#38) will read; it
 * does not feed prompts yet.
 *
 * <p>Every row carries the installation and repository id it was given under, and every read
 * matches them (#1220). A row from before #1220 that the startup backfill could not match has no
 * ids and is never read.
 */
@ApplicationScoped
public class FindingFeedbackService {

  private static final String SCOPE = "installationId = ?1 and repositoryId = ?2";

  private final FindingFeedbackRepository repository;

  @Inject
  public FindingFeedbackService(FindingFeedbackRepository repository) {
    this.repository = repository;
  }

  /**
   * Per-repository preference counts for a future {@code ContextProvider}. Only {@code useful} /
   * {@code not_useful} signals are counted.
   */
  public record FeedbackPreferenceSummary(
      String repository, long usefulCount, long notUsefulCount, long totalEvents) {}

  /** Input for {@link #recordFeedback(FeedbackInput)}. */
  public record FeedbackInput(
      RepoScope scope,
      String repositoryKey,
      int prNumber,
      long githubCommentId,
      Integer findingIndex,
      String signal,
      String source,
      String reactorLogin,
      Long githubReactionId) {}

  /** Records one feedback event; returns {@code true} when a new row was inserted. */
  @Transactional
  public boolean recordFeedback(FeedbackInput input) {
    if (input == null
        || input.scope() == null
        || input.repositoryKey() == null
        || input.repositoryKey().isBlank()
        || input.signal() == null
        || input.source() == null
        || input.reactorLogin() == null
        || input.reactorLogin().isBlank()) {
      return false;
    }
    var login = input.reactorLogin().strip().toLowerCase(Locale.ROOT);
    if (input.githubReactionId() != null
        && repository.count("githubReactionId = ?1", input.githubReactionId()) > 0) {
      return false;
    }
    if (repository.count(
            "githubCommentId = ?1 and reactorLogin = ?2 and signal = ?3 and source = ?4",
            input.githubCommentId(),
            login,
            input.signal(),
            input.source())
        > 0) {
      return false;
    }
    var row = new FindingFeedback();
    row.installationId = input.scope().installationId();
    row.repositoryId = input.scope().repositoryId();
    row.repository = input.repositoryKey().strip();
    row.prNumber = input.prNumber();
    row.githubCommentId = input.githubCommentId();
    row.findingIndex = input.findingIndex();
    row.signal = input.signal();
    row.source = input.source();
    row.reactorLogin = login;
    row.githubReactionId = input.githubReactionId();
    row.createdAt = Instant.now();
    repository.persist(row);
    return true;
  }

  /**
   * One persisted feedback row for dashboard / ops inspection. Reads every stored column so the
   * entity fields stay live for static analysis and for a future {@code ContextProvider}.
   */
  public record FeedbackEvent(
      long id,
      String repository,
      int prNumber,
      long githubCommentId,
      Integer findingIndex,
      String signal,
      String source,
      String reactorLogin,
      Long githubReactionId,
      Instant createdAt) {}

  /**
   * Aggregated 👍/👎-style preferences for one repository, labelled {@code repositoryName} (its
   * current {@code owner/repo} name, which a rename may have changed since the rows were stored).
   */
  @Transactional
  public FeedbackPreferenceSummary summarize(RepoScope scope, String repositoryName) {
    long useful =
        repository.count(
            SCOPE + " and signal = ?3",
            scope.installationId(),
            scope.repositoryId(),
            FindingFeedback.SIGNAL_USEFUL);
    long notUseful =
        repository.count(
            SCOPE + " and signal = ?3",
            scope.installationId(),
            scope.repositoryId(),
            FindingFeedback.SIGNAL_NOT_USEFUL);
    return new FeedbackPreferenceSummary(repositoryName, useful, notUseful, useful + notUseful);
  }

  /**
   * Newest events for a repository (dashboard detail); empty when {@code limit} is not positive.
   */
  @Transactional
  public List<FeedbackEvent> listRecent(RepoScope scope, int limit) {
    if (limit <= 0) {
      return List.of();
    }
    return repository
        .find(
            SCOPE + " order by createdAt desc, id desc",
            scope.installationId(),
            scope.repositoryId())
        .page(0, Math.min(limit, 100))
        .list()
        .stream()
        .map(
            f ->
                new FeedbackEvent(
                    f.id,
                    f.repository,
                    f.prNumber,
                    f.githubCommentId,
                    f.findingIndex,
                    f.signal,
                    f.source,
                    f.reactorLogin,
                    f.githubReactionId,
                    f.createdAt))
        .toList();
  }

  /**
   * Per-repository counts for the given repositories of each installation, in one query, ordered by
   * total events descending; a repository with no feedback is absent. Keyed by installation and
   * repository id, so the caller labels each with the repository's current name.
   */
  @Transactional
  public Map<RepoScope, FeedbackCounts> countsByRepository(
      Map<Long, ? extends Collection<Long>> repositoryIdsByInstallation) {
    var installationIds = new ArrayList<Long>();
    var repositoryIds = new HashSet<Long>();
    repositoryIdsByInstallation.forEach(
        (installationId, ids) -> {
          if (!ids.isEmpty()) {
            installationIds.add(installationId);
            repositoryIds.addAll(ids);
          }
        });
    if (installationIds.isEmpty()) {
      return Map.of();
    }
    List<Object[]> rows =
        repository
            .getEntityManager()
            .createQuery(
                """
                select f.installationId,
                       f.repositoryId,
                       sum(case when f.signal = :useful then 1 else 0 end),
                       sum(case when f.signal = :notUseful then 1 else 0 end),
                       count(f)
                from FindingFeedback f
                where f.installationId in :installationIds and f.repositoryId in :repositoryIds
                group by f.installationId, f.repositoryId
                order by count(f) desc, f.installationId, f.repositoryId
                """,
                Object[].class)
            .setParameter("useful", FindingFeedback.SIGNAL_USEFUL)
            .setParameter("notUseful", FindingFeedback.SIGNAL_NOT_USEFUL)
            .setParameter("installationIds", installationIds)
            .setParameter("repositoryIds", repositoryIds)
            .getResultList();
    var counts = new LinkedHashMap<RepoScope, FeedbackCounts>();
    for (var r : rows) {
      var installationId = ((Number) r[0]).longValue();
      var repositoryId = ((Number) r[1]).longValue();
      // The query matches the installation and repository id sets independently, so a row under
      // one installation whose repository id was only listed for another one can come back; keep
      // only pairs the caller asked for, so a repository id is counted under its own installation
      // only.
      if (repositoryIdsByInstallation.get(installationId).contains(repositoryId)) {
        counts.put(
            new RepoScope(installationId, repositoryId),
            new FeedbackCounts(
                ((Number) r[2]).longValue(),
                ((Number) r[3]).longValue(),
                ((Number) r[4]).longValue()));
      }
    }
    return counts;
  }

  /** Useful, not-useful and total feedback events of one repository. */
  public record FeedbackCounts(long usefulCount, long notUsefulCount, long totalEvents) {}
}
