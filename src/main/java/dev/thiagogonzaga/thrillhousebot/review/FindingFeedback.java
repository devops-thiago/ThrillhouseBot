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
 * One maintainer signal about a bot finding comment — a 👍/👎 reaction or a reply heuristic such as
 * "not useful". Persisted per repository so a future learnings {@code ContextProvider} (#38) can
 * aggregate preferences without re-scraping GitHub. Stores only the GitHub login already present on
 * webhook payloads — no email or other PII.
 *
 * <p>Retention: rows are kept for the life of the deployment database (no automatic purge).
 * Operators may delete rows or drop the table when decommissioning an installation; there is no
 * cross-installation export.
 */
@Entity
@Table(
    name = "finding_feedback",
    indexes = {
      @Index(name = "idx_findingfeedback_repository", columnList = "repository"),
      @Index(name = "idx_findingfeedback_comment", columnList = "githubCommentId"),
      @Index(name = "idx_findingfeedback_created", columnList = "createdAt")
    },
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_findingfeedback_reaction_id",
          columnNames = {"githubReactionId"}),
      @UniqueConstraint(
          name = "uq_findingfeedback_comment_reactor_signal_source",
          columnNames = {"githubCommentId", "reactorLogin", "signal", "source"})
    })
@RegisterForReflection
public class FindingFeedback extends PanacheEntity {

  /** Useful reaction ({@code +1}). */
  public static final String SIGNAL_USEFUL = "useful";

  /** Not-useful reaction ({@code -1}) or reply heuristic. */
  public static final String SIGNAL_NOT_USEFUL = "not_useful";

  /** Captured from the GitHub Reactions REST API. */
  public static final String SOURCE_REACTION = "reaction";

  /** Captured from reply-body heuristics (e.g. "not useful", 👎). */
  public static final String SOURCE_REPLY_HEURISTIC = "reply_heuristic";

  /** Repository in {@code owner/repo} form. */
  @Column(nullable = false, length = 255)
  String repository;

  /** Pull request number on {@link #repository}. */
  @Column(nullable = false)
  int prNumber;

  /** GitHub review-comment id of the finding root (or the reacted comment). */
  @Column(nullable = false)
  long githubCommentId;

  /**
   * 1-based finding index from {@code <!-- thrillhousebot:finding=N -->} when the comment body was
   * available; {@code null} when unknown.
   */
  Integer findingIndex;

  /** {@link #SIGNAL_USEFUL} or {@link #SIGNAL_NOT_USEFUL}. */
  @Column(nullable = false, length = 32)
  String signal;

  /** {@link #SOURCE_REACTION} or {@link #SOURCE_REPLY_HEURISTIC}. */
  @Column(nullable = false, length = 32)
  String source;

  /** GitHub login of the reactor / reply author (no other PII). */
  @Column(nullable = false, length = 255)
  String reactorLogin;

  /**
   * GitHub reaction id when {@link #source} is {@link #SOURCE_REACTION}; {@code null} for reply
   * heuristics. Unique so redeliveries / re-polls are idempotent.
   */
  Long githubReactionId;

  @Column(nullable = false)
  Instant createdAt;
}
