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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Marks a pull request the bot has been silenced on via {@code /pause}. A single row per PR (the
 * unique constraint enforces it) means the bot skips automatic reviews and manual {@code /review} /
 * {@code /summary} on that PR until {@code /resume} removes the row.
 */
@Entity
@Table(
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_pausedpr_repository_pr",
            columnNames = {"repository", "prNumber"}))
@RegisterForReflection
public class PausedPr extends PanacheEntity {

  /** Repository in "owner/repo" form. */
  @Column(nullable = false)
  public String repository;

  /** Pull request number, unique together with {@link #repository}. */
  @Column(nullable = false)
  public int prNumber;

  /** Login of the user who paused the PR (audit only). */
  public String pausedBy;

  /** When the PR was paused. */
  public Instant pausedAt;
}
