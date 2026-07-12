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
package dev.thiagogonzaga.thrillhousebot.dashboard;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    indexes = {
      @Index(name = "idx_reviewsession_timestamp", columnList = "timestamp"),
      @Index(name = "idx_reviewsession_status", columnList = "status"),
      @Index(name = "idx_reviewsession_repository", columnList = "repository")
    })
@RegisterForReflection
public class ReviewSession extends PanacheEntity {

  public static final String STATUS_IN_PROGRESS = "in_progress";
  public static final String STATUS_COMPLETED = "completed";
  public static final String STATUS_FAILED = "failed";

  /** Unguessable identifier for links posted publicly on GitHub — never the sequential id. */
  @Column(name = "public_id", unique = true, updatable = false, length = 36)
  String publicId;

  String repository; // "owner/repo"
  int prNumber;
  String prTitle;
  String commitSha;
  Instant timestamp;

  String model; // model name
  int inputTokens;
  int outputTokens;
  double cost;

  /**
   * True when the session's cost was recorded as 0 because the model had no {@code
   * thrillhousebot.ai.pricing} entry — distinguishes "pricing not configured" from a genuine $0 so
   * the dashboard can flag under-reported spend. Cleared by {@link SessionCostBackfill} once
   * pricing is added and the cost is recomputed.
   *
   * <p>{@code default false} is required so schema-update can add this NOT NULL column to a
   * populated Postgres table (without it, existing rows are null and the ALTER fails).
   */
  @Column(nullable = false, columnDefinition = "boolean not null default false")
  boolean pricingMissing;

  long durationMs;

  int criticalFindings;
  int highFindings;
  int mediumFindings;
  int lowFindings;

  String status; // STATUS_IN_PROGRESS, STATUS_COMPLETED, STATUS_FAILED
  String errorMessage; // sanitized, null if completed

  @Column(columnDefinition = "TEXT")
  String aiResponseJson; // serialized ReviewResponse from the model

  public String getPublicId() {
    return publicId;
  }

  public void setPublicId(String publicId) {
    this.publicId = publicId;
  }

  public String getRepository() {
    return repository;
  }

  public int getPrNumber() {
    return prNumber;
  }

  public String getPrTitle() {
    return prTitle;
  }

  public String getCommitSha() {
    return commitSha;
  }

  public Instant getTimestamp() {
    return timestamp;
  }

  public String getModel() {
    return model;
  }

  public int getInputTokens() {
    return inputTokens;
  }

  public int getOutputTokens() {
    return outputTokens;
  }

  public double getCost() {
    return cost;
  }

  public boolean isPricingMissing() {
    return pricingMissing;
  }

  public long getDurationMs() {
    return durationMs;
  }

  public int getCriticalFindings() {
    return criticalFindings;
  }

  public int getHighFindings() {
    return highFindings;
  }

  public int getMediumFindings() {
    return mediumFindings;
  }

  public int getLowFindings() {
    return lowFindings;
  }

  public String getStatus() {
    return status;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  @JsonIgnore
  public String getAiResponseJson() {
    return aiResponseJson;
  }

  public void setRepository(String repository) {
    this.repository = repository;
  }

  public void setPrNumber(int prNumber) {
    this.prNumber = prNumber;
  }

  public void setPrTitle(String prTitle) {
    this.prTitle = prTitle;
  }

  public void setCommitSha(String commitSha) {
    this.commitSha = commitSha;
  }

  public void setTimestamp(Instant timestamp) {
    this.timestamp = timestamp;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public void setInputTokens(int inputTokens) {
    this.inputTokens = inputTokens;
  }

  public void setOutputTokens(int outputTokens) {
    this.outputTokens = outputTokens;
  }

  public void setCost(double cost) {
    this.cost = cost;
  }

  public void setPricingMissing(boolean pricingMissing) {
    this.pricingMissing = pricingMissing;
  }

  public void setDurationMs(long durationMs) {
    this.durationMs = durationMs;
  }

  public void setCriticalFindings(int criticalFindings) {
    this.criticalFindings = criticalFindings;
  }

  public void setHighFindings(int highFindings) {
    this.highFindings = highFindings;
  }

  public void setMediumFindings(int mediumFindings) {
    this.mediumFindings = mediumFindings;
  }

  public void setLowFindings(int lowFindings) {
    this.lowFindings = lowFindings;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public void setAiResponseJson(String aiResponseJson) {
    this.aiResponseJson = aiResponseJson;
  }

  public static ReviewSession create(
      String repository, int prNumber, String prTitle, String commitSha) {
    var session = new ReviewSession();
    session.setPublicId(UUID.randomUUID().toString());
    session.setRepository(repository);
    session.setPrNumber(prNumber);
    session.setPrTitle(prTitle);
    session.setCommitSha(commitSha);
    session.setTimestamp(Instant.now());
    session.setStatus(STATUS_IN_PROGRESS);
    return session;
  }
}
