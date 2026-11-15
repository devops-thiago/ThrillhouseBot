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
 * One finding of a pull request and its status across review rounds (#1319). {@link FindingLedger}
 * decides what a round writes; {@link FindingStates} maps it to and from this row.
 *
 * <p>Retention: a row is deleted once its pull request has been closed or merged for longer than
 * {@code FINDINGS_RETENTION}, and every row of an installation when the App is uninstalled. Nothing
 * else references these rows: finding feedback, learnings and review sessions keep their own copy
 * of what they need.
 */
@Entity
@Table(
    name = "finding_state",
    indexes = {
      @Index(name = "idx_findingstate_closed", columnList = "prClosedAt"),
      @Index(name = "idx_findingstate_installation", columnList = "installationId")
    },
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_findingstate_scope_pr_key",
            columnNames = {"installationId", "repositoryId", "prNumber", "findingKey"}))
@RegisterForReflection
public class FindingState extends PanacheEntity {

  @Column(nullable = false)
  long installationId;

  @Column(nullable = false)
  long repositoryId;

  /** {@code owner/repo}, kept current on a rename, for the sweep's call to GitHub. */
  @Column(nullable = false, length = 255)
  String repository;

  @Column(nullable = false)
  int prNumber;

  /** The key the finding's thread marker carries. */
  @Column(nullable = false, length = 16)
  String findingKey;

  @Column(length = 1000)
  String file;

  @Column(nullable = false)
  int line;

  @Column(length = 16)
  String risk;

  @Column(length = 16)
  String confidence;

  @Column(length = 500)
  String title;

  /** The code the finding quotes, as text, so a force-push that drops a commit loses nothing. */
  @Column(columnDefinition = "TEXT")
  String anchor;

  /** {@code RIGHT} or {@code LEFT}; {@code null} when not known. */
  @Column(length = 8)
  String anchorSide;

  /** Whether the quote was present in the round that raised the finding; null when not known. */
  Boolean anchorMatched;

  /** The file's blob SHA at the head of the round that raised the finding. */
  @Column(length = 64)
  String blobSha;

  @Column(nullable = false, length = 16)
  String status;

  @Column(length = 64)
  String raisedHead;

  /** The head of the round that last changed {@link #status}. */
  @Column(length = 64)
  String statusHead;

  /** The finding's thread root comment; {@code null} while it has none. */
  Long threadId;

  /** The commit {@link #threadId} was posted on; {@code null} while there is none. */
  @Column(length = 64)
  String threadHead;

  /** When the pull request was closed or merged; {@code null} while it is open. */
  Instant prClosedAt;

  @Column(nullable = false)
  Instant updatedAt;

  /** When the pull request was closed or merged; {@code null} while it is open. */
  Instant closedAt() {
    return prClosedAt;
  }
}
