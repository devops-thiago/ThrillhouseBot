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

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Persists and reads {@link ReviewLearning} rows (#38). Short transactions only — never spans a
 * GitHub or model call. Every write, and every read that feeds a review or a comment command, is
 * scoped to one installation and one repository, so a learning taught on one repository can never
 * reach a review of another, nor a review of the same repository name under a different
 * installation. The one exception is {@link #listForAudit}, the dashboard's read-only history,
 * which is scoped to the repository alone behind the dashboard's repository-access check.
 *
 * <p>The per-repository cap is checked before each insert rather than enforced by a lock, so two
 * captures racing at the cap can each add one row. It bounds growth, not an exact count; the prompt
 * is bounded separately by its own item and character caps.
 */
@ApplicationScoped
public class ReviewLearningService {

  private static final String SCOPE = "installationId = ?1 and repository = ?2";

  /** Most rows one audit read returns; retracted learnings are kept, so history is unbounded. */
  public static final int MAX_AUDIT_ROWS = 200;

  private final ReviewLearningRepository repository;

  @Inject
  public ReviewLearningService(ReviewLearningRepository repository) {
    this.repository = repository;
  }

  /** A learning to store. {@code text} is the raw maintainer prose; it is normalized here. */
  public record LearningInput(
      long installationId,
      String repository,
      String kind,
      String findingTitle,
      String findingRisk,
      String path,
      String text,
      int sourcePrNumber,
      String sourceUrl,
      String author) {}

  /** What {@link #save} did with an input. */
  public enum RecordOutcome {
    /** A new active row was written. */
    STORED,
    /** The same source already taught this learning; nothing written. */
    DUPLICATE,
    /** The text carries a credential-shaped value, so it is never stored. */
    REFUSED_SECRET,
    /** Nothing of the maintainer's own was left to remember, or the input was incomplete. */
    REFUSED_EMPTY,
    /** The repository already holds its cap of active learnings. */
    REFUSED_CAP
  }

  /** One stored learning, as the prompt, the commands and the dashboard read it. */
  @RegisterForReflection
  public record LearningView(
      long id,
      String kind,
      String findingTitle,
      String findingRisk,
      String path,
      String text,
      int sourcePrNumber,
      String sourceUrl,
      String author,
      Instant createdAt,
      boolean active,
      String retractedBy,
      Instant retractedAt) {}

  /** What {@link #retract} did. */
  public enum RetractOutcome {
    RETRACTED,
    /** No learning with that id belongs to this repository and installation. */
    NOT_FOUND,
    ALREADY_RETRACTED
  }

  /**
   * Stores one learning unless it is a duplicate, carries a credential, is empty once normalized,
   * or the repository is at {@code maxPerRepo} active learnings. The cap refuses rather than
   * evicts: silently dropping an old learning would retract something a maintainer taught without
   * anyone asking.
   */
  @Transactional
  public RecordOutcome save(LearningInput input, int maxPerRepo) {
    if (input == null
        || isBlank(input.repository())
        || isBlank(input.kind())
        || isBlank(input.author())
        || isBlank(input.sourceUrl())) {
      return RecordOutcome.REFUSED_EMPTY;
    }
    if (LearningText.containsCredential(input.text())
        || LearningText.containsCredential(input.findingTitle())
        || LearningText.containsCredential(input.path())) {
      return RecordOutcome.REFUSED_SECRET;
    }
    var text = LearningText.normalize(input.text(), ReviewLearning.MAX_TEXT_CHARS);
    if (text.isEmpty()) {
      return RecordOutcome.REFUSED_EMPTY;
    }
    var repo = normalizeRepository(input.repository());
    var title =
        input.findingTitle() == null
            ? null
            : LearningText.oneLine(input.findingTitle(), ReviewLearning.MAX_TITLE_CHARS);
    var key = dedupKey(input.kind(), input.installationId(), repo, input.sourceUrl(), title);
    if (repository.count("dedupKey", key) > 0) {
      return RecordOutcome.DUPLICATE;
    }
    if (repository.count(SCOPE + " and active = true", input.installationId(), repo)
        >= maxPerRepo) {
      return RecordOutcome.REFUSED_CAP;
    }
    var row = new ReviewLearning();
    row.installationId = input.installationId();
    row.repository = repo;
    row.kind = input.kind();
    row.findingTitle = title;
    row.findingRisk =
        input.findingRisk() == null ? null : LearningText.oneLine(input.findingRisk(), 16);
    row.path = input.path() == null ? null : LearningText.oneLine(input.path(), 500);
    row.text = text;
    row.sourcePrNumber = input.sourcePrNumber();
    row.sourceUrl = input.sourceUrl();
    row.author = input.author().strip().toLowerCase(Locale.ROOT);
    row.dedupKey = key;
    row.createdAt = Instant.now();
    row.active = true;
    repository.persist(row);
    return RecordOutcome.STORED;
  }

  /** Active learnings of one repository under one installation, newest first. */
  @Transactional
  public List<LearningView> listActive(long installationId, String repositoryKey, int limit) {
    if (isBlank(repositoryKey) || limit <= 0) {
      return List.of();
    }
    return repository
        .find(
            SCOPE + " and active = true order by createdAt desc, id desc",
            installationId,
            normalizeRepository(repositoryKey))
        .page(0, limit)
        .list()
        .stream()
        .map(ReviewLearningService::view)
        .toList();
  }

  /** Active learnings held by one repository under one installation. */
  @Transactional
  public long countActive(long installationId, String repositoryKey) {
    if (isBlank(repositoryKey)) {
      return 0;
    }
    return repository.count(
        SCOPE + " and active = true", installationId, normalizeRepository(repositoryKey));
  }

  /**
   * The newest {@code limit} learnings of a repository (at most {@value #MAX_AUDIT_ROWS}),
   * retracted ones included, newest first — the dashboard's audit view. Not installation-scoped:
   * the dashboard has already checked the viewer can read the repository, and an operator auditing
   * it wants the whole history.
   */
  @Transactional
  public List<LearningView> listForAudit(String repositoryKey, int limit) {
    if (isBlank(repositoryKey) || limit <= 0) {
      return List.of();
    }
    return repository
        .find(
            "repository = ?1 order by createdAt desc, id desc", normalizeRepository(repositoryKey))
        .page(0, Math.min(limit, MAX_AUDIT_ROWS))
        .list()
        .stream()
        .map(ReviewLearningService::view)
        .toList();
  }

  /**
   * Retracts one learning. The id must belong to this repository and installation — an id from
   * another repository reads as not found, so {@code /forget} on one repository cannot touch
   * another's learnings. The row is kept, marked inactive, with who retracted it and when.
   */
  @Transactional
  public RetractOutcome retract(
      long installationId, String repositoryKey, long id, String retractedBy) {
    if (isBlank(repositoryKey)) {
      return RetractOutcome.NOT_FOUND;
    }
    var row =
        repository
            .find(SCOPE + " and id = ?3", installationId, normalizeRepository(repositoryKey), id)
            .firstResult();
    if (row == null) {
      return RetractOutcome.NOT_FOUND;
    }
    if (!row.active) {
      return RetractOutcome.ALREADY_RETRACTED;
    }
    row.active = false;
    row.retractedBy = retractedBy == null ? null : retractedBy.strip().toLowerCase(Locale.ROOT);
    row.retractedAt = Instant.now();
    return RetractOutcome.RETRACTED;
  }

  private static LearningView view(ReviewLearning l) {
    return new LearningView(
        l.id,
        l.kind,
        l.findingTitle,
        l.findingRisk,
        l.path,
        l.text,
        l.sourcePrNumber,
        l.sourceUrl,
        l.author,
        l.createdAt,
        l.active,
        l.retractedBy,
        l.retractedAt);
  }

  static String normalizeRepository(String repositoryKey) {
    return repositoryKey.strip().toLowerCase(Locale.ROOT);
  }

  /**
   * A name-based UUID over the fields that identify one taught learning. It is an idempotency key,
   * not a security boundary: the fields it covers are all public on the pull request.
   */
  static String dedupKey(
      String kind, long installationId, String repository, String sourceUrl, String title) {
    var material =
        String.join(
            "\u0000",
            kind,
            Long.toString(installationId),
            repository,
            sourceUrl,
            title == null ? "" : title);
    return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
