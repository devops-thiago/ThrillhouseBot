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

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * One thing a maintainer taught the bot about a repository (#38): a finding they declined with a
 * reason that survived the decline re-check (#169), or a convention they stated with {@code
 * /remember}. Relevant rows ride later review prompts of the same repository and installation as
 * fenced, untrusted data.
 *
 * <p>Every text column holds maintainer- or model-written prose, stored as data: it is clipped and
 * screened for credential-shaped values before it is written ({@link LearningText}), fenced when it
 * is read back into a prompt, and never interpreted. Retraction clears {@link #active} instead of
 * deleting the row, so who taught what, where, and who took it back stays auditable.
 *
 * <p>Schema is managed like every other table here (Hibernate schema update; there is no Flyway in
 * this project), so upgrading creates the table on first start.
 */
@Entity
@Table(
    name = "review_learning",
    indexes = {
      @Index(name = "idx_reviewlearning_scope", columnList = "installationId, repository, active")
    },
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_reviewlearning_dedup_key",
          columnNames = {"dedupKey"})
    })
@RegisterForReflection
public class ReviewLearning extends PanacheEntity {

  /** A finding a maintainer declined, with a reason the re-check did not contradict. */
  public static final String KIND_DECLINE = "decline";

  /** A convention a maintainer stated explicitly with {@code /remember}. */
  public static final String KIND_CONVENTION = "convention";

  /** Longest learning text kept, in characters. */
  public static final int MAX_TEXT_CHARS = 1000;

  /** Longest finding title kept, in characters. */
  public static final int MAX_TITLE_CHARS = 300;

  /** Longest path kept, in characters. */
  public static final int MAX_PATH_CHARS = 500;

  /** GitHub App installation the learning was captured under; reads are scoped to it. */
  @Column(nullable = false)
  long installationId;

  /** Repository in {@code owner/repo} form, lower-cased so lookups are case-insensitive. */
  @Column(nullable = false, length = 255)
  String repository;

  /** {@link #KIND_DECLINE} or {@link #KIND_CONVENTION}. */
  @Column(nullable = false, length = 32)
  String kind;

  /** Title of the declined finding; {@code null} for a convention. */
  @Column(length = MAX_TITLE_CHARS)
  String findingTitle;

  /** Severity the declined finding was raised at; {@code null} for a convention. */
  @Column(length = 16)
  String findingRisk;

  /** File the declined finding was about; {@code null} for a repository-wide convention. */
  @Column(length = MAX_PATH_CHARS)
  String path;

  /** The maintainer's reason or convention, clipped and screened ({@link LearningText}). */
  @Column(nullable = false, length = MAX_TEXT_CHARS)
  String text;

  /** Pull request the learning was taught on. */
  @Column(nullable = false)
  int sourcePrNumber;

  /** Link to the comment that taught it — the audit trail. */
  @Column(nullable = false, length = 500)
  String sourceUrl;

  /** GitHub login of the maintainer who taught it (lower-cased; no other PII). */
  @Column(nullable = false, length = 255)
  String author;

  /**
   * Idempotency key over the source comment and the finding it declined, so a review that reports
   * the same surviving decline twice, or a redelivered {@code /remember}, stores one row.
   */
  @Column(nullable = false, length = 64)
  String dedupKey;

  @Column(nullable = false)
  Instant createdAt;

  /** Cleared by {@code /forget}; inactive rows are kept for audit and never reach a prompt. */
  @Column(nullable = false)
  boolean active;

  /** Login that retracted the learning; {@code null} while active. */
  @Column(length = 255)
  String retractedBy;

  /** When the learning was retracted; {@code null} while active. */
  Instant retractedAt;
}
