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
package dev.thiagogonzaga.thrillhousebot.webhook;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Marks a pull request the bot has been silenced on via {@code /pause}. A single row per PR (the
 * unique constraint enforces it) means the bot skips automatic reviews and manual {@code /review} /
 * {@code /summary} on that PR until {@code /resume} removes the row. Presence of the row is the
 * whole state — the bot is paused iff a row exists.
 */
@Entity
@Table(
    indexes = @Index(name = "idx_pausedpr_repository", columnList = "repository, prNumber"),
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_pausedpr_scope_pr",
            columnNames = {"installationId", "repositoryId", "prNumber"}))
@RegisterForReflection
public class PausedPr extends PanacheEntity {

  /** Repository in "owner/repo" form. */
  @Column(nullable = false)
  public String repository;

  /**
   * Installation and repository id the pause belongs to (#1220). {@code null} on a pause from
   * before #1220 that the startup backfill could not match; {@link PrPauseService} still honors
   * such a row by its exact repository name, so a pause a maintainer asked for is never silently
   * lifted.
   */
  @Column public Long installationId;

  @Column public Long repositoryId;

  /** Pull request number, unique together with the installation and repository id. */
  @Column(nullable = false)
  public int prNumber;
}
