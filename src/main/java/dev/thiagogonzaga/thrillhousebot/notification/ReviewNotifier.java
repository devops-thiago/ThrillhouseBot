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

import dev.thiagogonzaga.thrillhousebot.config.ReviewExecutor;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.review.ReviewOrchestrator.ReviewRequest;
import dev.thiagogonzaga.thrillhousebot.review.ReviewResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends the opt-in outgoing notification when a review reaches its final outcome (#73).
 *
 * <p>Called by {@code ReviewOrchestrator} once per review run: {@link #reviewCompleted} after the
 * verdict is on the pull request, {@link #reviewFailed} after a failure has been surfaced. A run
 * abandoned because the head moved while it ran sends nothing — its result was discarded and the
 * run for the new head sends its own — and a verdict held on CI is sent once, when the review posts
 * it ({@code review.held_on_ci} is {@code true}); the approval a later CI completion posts without
 * another review is not a second notification.
 *
 * <p>Nothing here can delay or fail a review: when notifications are off every call returns at
 * once, the body is rendered on the calling thread (cheap, no I/O) and the delivery itself runs on
 * the review executor's virtual threads, bounded by {@link WebhookDelivery}'s timeout and retry.
 * Every failure is logged and swallowed.
 */
@ApplicationScoped
public class ReviewNotifier {

  private static final Logger log = LoggerFactory.getLogger(ReviewNotifier.class);

  private final Optional<NotificationSettings> settings;
  private final WebhookDelivery delivery;
  private final Executor executor;
  private final Clock clock;
  private final String botVersion;

  @Inject
  public ReviewNotifier(
      ThrillhouseConfig config,
      HttpClient httpClient,
      @ReviewExecutor ExecutorService executor,
      @ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown")
          String botVersion) {
    this(
        NotificationSettings.resolve(config.notifications().webhook()),
        new WebhookDelivery(httpClient, Thread::sleep),
        executor,
        Clock.systemUTC(),
        botVersion);
  }

  /** Visible for tests: every collaborator injectable, so no test sleeps or opens a socket. */
  ReviewNotifier(
      Optional<NotificationSettings> settings,
      WebhookDelivery delivery,
      Executor executor,
      Clock clock,
      String botVersion) {
    this.settings = settings;
    this.delivery = delivery;
    this.executor = executor;
    this.clock = clock;
    this.botVersion = botVersion;
  }

  /** The review posted its verdict for {@code req}'s head. */
  public void reviewCompleted(ReviewRequest req, String sessionUrl, ReviewResult result) {
    if (settings.isEmpty()) {
      return;
    }
    send(
        ReviewOutcome.completed(
            req.owner(),
            req.repo(),
            req.prNumber(),
            req.commitSha(),
            req.prTitle(),
            sessionUrl,
            result));
  }

  /** The review of {@code req}'s head failed with {@code failure} before its verdict was posted. */
  public void reviewFailed(ReviewRequest req, String sessionUrl, Throwable failure) {
    if (settings.isEmpty()) {
      return;
    }
    send(
        ReviewOutcome.failed(
            req.owner(),
            req.repo(),
            req.prNumber(),
            req.commitSha(),
            req.prTitle(),
            sessionUrl,
            FailureCategory.of(failure)));
  }

  /** Renders and queues one notification; never throws. Visible for tests. */
  void send(ReviewOutcome outcome) {
    var active = settings.orElseThrow();
    if (!active.events().contains(outcome.event())) {
      return;
    }
    var event = outcome.event().wireName();
    try {
      var body =
          NotificationPayloads.render(
              outcome, active.format(), active.includeContent(), clock.instant(), botVersion);
      var request =
          new WebhookDelivery.Request(
              event, UUID.randomUUID().toString(), body, "ThrillhouseBot/" + botVersion);
      executor.execute(() -> delivery.deliver(active, request));
    } catch (RuntimeException e) {
      // Rendering is pure and the executor only refuses work while the process shuts down; either
      // way the review must not notice.
      log.warn(
          "Could not queue the {} notification for {}#{}: {}",
          event,
          outcome.repository(),
          outcome.prNumber(),
          e.getClass().getSimpleName());
    }
  }
}
