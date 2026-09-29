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
package dev.thiagogonzaga.thrillhousebot.notification;

import dev.thiagogonzaga.thrillhousebot.review.ReviewResult;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One final review outcome, as the outgoing notification sees it (#73): which pull request and
 * head, where its dashboard session lives, and either the verdict the review posted or the category
 * of the failure that stopped it.
 *
 * @param event whether the review completed or failed
 * @param owner repository owner login
 * @param repo repository name
 * @param prNumber pull request number
 * @param headSha the head commit the review ran against
 * @param prTitle the pull request title; only sent when content is opted in
 * @param sessionUrl the dashboard session deep-link
 * @param result the posted verdict; present exactly when {@code event} is {@link Event#COMPLETED}
 * @param failure why the review failed; present exactly when {@code event} is {@link Event#FAILED}
 */
public record ReviewOutcome(
    Event event,
    String owner,
    String repo,
    int prNumber,
    String headSha,
    String prTitle,
    String sessionUrl,
    ReviewResult result,
    FailureCategory failure) {

  /** The outcomes that fire a notification. */
  public enum Event {
    COMPLETED("completed"),
    FAILED("failed");

    private final String configName;

    Event(String configName) {
      this.configName = configName;
    }

    /** The value in {@code NOTIFICATIONS_WEBHOOK_EVENTS}. */
    public String configName() {
      return configName;
    }

    /** The {@code event} field of the payload and the {@code X-Thrillhousebot-Event} header. */
    public String wireName() {
      return "review." + configName;
    }

    /** The event a configured value names, compared case-insensitively. */
    public static Optional<Event> from(String value) {
      if (value == null) {
        return Optional.empty();
      }
      var normalized = value.strip().toLowerCase(Locale.ROOT);
      for (var event : values()) {
        if (event.configName.equals(normalized)) {
          return Optional.of(event);
        }
      }
      return Optional.empty();
    }
  }

  public ReviewOutcome {
    Objects.requireNonNull(event, "event");
    if ((event == Event.COMPLETED) != (result != null)) {
      throw new IllegalArgumentException("a completed outcome carries a result, a failed one not");
    }
    if ((event == Event.FAILED) != (failure != null)) {
      throw new IllegalArgumentException(
          "a failed outcome carries a category, a completed one not");
    }
  }

  /** A review that posted its verdict. */
  public static ReviewOutcome completed(
      String owner,
      String repo,
      int prNumber,
      String headSha,
      String prTitle,
      String sessionUrl,
      ReviewResult result) {
    return new ReviewOutcome(
        Event.COMPLETED, owner, repo, prNumber, headSha, prTitle, sessionUrl, result, null);
  }

  /** A review that failed before its verdict was posted. */
  public static ReviewOutcome failed(
      String owner,
      String repo,
      int prNumber,
      String headSha,
      String prTitle,
      String sessionUrl,
      FailureCategory failure) {
    return new ReviewOutcome(
        Event.FAILED, owner, repo, prNumber, headSha, prTitle, sessionUrl, null, failure);
  }

  /** {@code owner/repo}. */
  public String repository() {
    return owner + "/" + repo;
  }

  /** The pull request's page on GitHub. */
  public String pullRequestUrl() {
    return "https://github.com/" + owner + "/" + repo + "/pull/" + prNumber;
  }
}
