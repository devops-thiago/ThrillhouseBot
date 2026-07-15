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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Persists and aggregates {@link FindingFeedback} rows. Short DB transactions only — never spans a
 * GitHub API call. Idempotent inserts: duplicate reaction ids or the same (comment, reactor,
 * signal, source) are ignored via pre-insert existence checks.
 *
 * <p>{@link #summarize(String)} is the seam a future learnings {@code ContextProvider} (#38) will
 * read; it does not feed prompts yet.
 */
@ApplicationScoped
public class FindingFeedbackService {

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

  /** Aggregated 👍/👎-style preferences for {@code owner/repo}. */
  @Transactional
  public FeedbackPreferenceSummary summarize(String repositoryKey) {
    if (repositoryKey == null || repositoryKey.isBlank()) {
      return new FeedbackPreferenceSummary("", 0, 0, 0);
    }
    var key = repositoryKey.strip();
    long useful =
        repository.count("repository = ?1 and signal = ?2", key, FindingFeedback.SIGNAL_USEFUL);
    long notUseful =
        repository.count("repository = ?1 and signal = ?2", key, FindingFeedback.SIGNAL_NOT_USEFUL);
    return new FeedbackPreferenceSummary(key, useful, notUseful, useful + notUseful);
  }

  /** Newest events for a repository (dashboard detail); empty when the key is blank. */
  @Transactional
  public List<FeedbackEvent> listRecent(String repositoryKey, int limit) {
    if (repositoryKey == null || repositoryKey.isBlank() || limit <= 0) {
      return List.of();
    }
    return repository
        .find("repository = ?1 order by createdAt desc", repositoryKey.strip())
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

  /** Per-repository summaries ordered by total events descending (dashboard / ops). */
  @Transactional
  public List<FeedbackPreferenceSummary> summarizeAll() {
    List<Object[]> rows =
        repository
            .getEntityManager()
            .createQuery(
                """
                select f.repository,
                       sum(case when f.signal = :useful then 1 else 0 end),
                       sum(case when f.signal = :notUseful then 1 else 0 end),
                       count(f)
                from FindingFeedback f
                group by f.repository
                order by count(f) desc
                """,
                Object[].class)
            .setParameter("useful", FindingFeedback.SIGNAL_USEFUL)
            .setParameter("notUseful", FindingFeedback.SIGNAL_NOT_USEFUL)
            .getResultList();
    return rows.stream()
        .map(
            r ->
                new FeedbackPreferenceSummary(
                    (String) r[0],
                    ((Number) r[1]).longValue(),
                    ((Number) r[2]).longValue(),
                    ((Number) r[3]).longValue()))
        .toList();
  }
}
