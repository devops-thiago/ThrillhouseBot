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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.review.ReviewOrchestrator.ReviewRequest;
import dev.thiagogonzaga.thrillhousebot.review.ReviewResult;
import dev.thiagogonzaga.thrillhousebot.review.ReviewState;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewNotifierTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneOffset.UTC);
  private static final ReviewRequest REQUEST =
      new ReviewRequest("octo", "repo", 42, "0123456789", "Title", "", "base", "main", 1L, false);
  private static final ReviewResult CLEAN =
      new ReviewResult(
          List.of(), 0, 0, 0, 0, null, ReviewState.APPROVE, true, "", List.of(), List.of(), 0);
  private static final String SESSION = "https://bot.example/session/x";

  private final WebhookDelivery delivery = mock(WebhookDelivery.class);
  private final List<Runnable> queued = new ArrayList<>();
  private final Executor executor = queued::add;

  private static NotificationSettings settings(Set<ReviewOutcome.Event> events) {
    return new NotificationSettings(
        URI.create("https://hooks.example/secret"),
        NotificationFormat.JSON,
        Optional.empty(),
        events,
        false,
        Duration.ofSeconds(5),
        3);
  }

  private ReviewNotifier notifier(Optional<NotificationSettings> settings) {
    return new ReviewNotifier(settings, delivery, executor, CLOCK, "0.7.0");
  }

  private WebhookDelivery.Request runQueuedAndCapture() {
    assertThat(queued).hasSize(1);
    queued.getFirst().run();
    var captor = ArgumentCaptor.forClass(WebhookDelivery.Request.class);
    verify(delivery).deliver(any(), captor.capture());
    return captor.getValue();
  }

  @Test
  void offByDefaultIsANoOp() {
    var notifier = notifier(Optional.empty());

    notifier.reviewCompleted(REQUEST, SESSION, CLEAN);
    notifier.reviewFailed(REQUEST, SESSION, new RuntimeException("x"));

    assertThat(queued).isEmpty();
    verifyNoInteractions(delivery);
  }

  @Test
  void completedReviewIsRenderedAndDeliveredOffThread() throws Exception {
    var active = settings(Set.of(ReviewOutcome.Event.COMPLETED, ReviewOutcome.Event.FAILED));
    var notifier = notifier(Optional.of(active));

    notifier.reviewCompleted(REQUEST, SESSION, CLEAN);

    // Nothing is sent on the caller's thread: the delivery waits on the executor.
    verifyNoInteractions(delivery);
    var request = runQueuedAndCapture();
    verify(delivery).deliver(eq(active), any());
    assertThat(request.event()).isEqualTo("review.completed");
    assertThat(request.userAgent()).isEqualTo("ThrillhouseBot/0.7.0");
    assertThat(request.deliveryId()).isNotBlank();
    var json = new ObjectMapper().readTree(request.body());
    assertThat(json.get("timestamp").asText()).isEqualTo("2026-09-29T12:00:00Z");
    assertThat(json.at("/pull_request/head_sha").asText()).isEqualTo("0123456789");
    assertThat(json.get("session_url").asText()).isEqualTo(SESSION);
    assertThat(json.at("/review/verdict").asText()).isEqualTo("APPROVE");
  }

  @Test
  void failedReviewCarriesItsCategory() throws Exception {
    var notifier = notifier(Optional.of(settings(Set.of(ReviewOutcome.Event.FAILED))));

    notifier.reviewFailed(
        REQUEST,
        SESSION,
        new RuntimeException(
            new AiReviewTimeoutException("slow", 2, Duration.ofSeconds(300), null)));

    var request = runQueuedAndCapture();
    assertThat(request.event()).isEqualTo("review.failed");
    var json = new ObjectMapper().readTree(request.body());
    assertThat(json.at("/failure/category").asText()).isEqualTo("ai_timeout");
  }

  @Test
  void eventsTheOperatorLeftOutAreNotSent() {
    var notifier = notifier(Optional.of(settings(Set.of(ReviewOutcome.Event.FAILED))));

    notifier.reviewCompleted(REQUEST, SESSION, CLEAN);

    assertThat(queued).isEmpty();
    verifyNoInteractions(delivery);
  }

  @Test
  void aRefusingExecutorNeverReachesTheReview() {
    Executor refusing =
        task -> {
          throw new RejectedExecutionException("shutting down");
        };
    var notifier =
        new ReviewNotifier(
            Optional.of(settings(Set.of(ReviewOutcome.Event.COMPLETED))),
            delivery,
            refusing,
            CLOCK,
            "v");

    assertThatCode(() -> notifier.reviewCompleted(REQUEST, SESSION, CLEAN))
        .doesNotThrowAnyException();
    verify(delivery, never()).deliver(any(), any());
  }

  @Test
  void productionConstructorResolvesTheConfiguration() {
    var config = mock(ThrillhouseConfig.class);
    var notifications = mock(ThrillhouseConfig.NotificationsConfig.class);
    var webhook = mock(ThrillhouseConfig.NotificationsConfig.OutgoingWebhookConfig.class);
    when(config.notifications()).thenReturn(notifications);
    when(notifications.webhook()).thenReturn(webhook);
    when(webhook.url()).thenReturn(Optional.empty());
    var executorService = mock(ExecutorService.class);

    var notifier =
        new ReviewNotifier(config, HttpClient.newHttpClient(), executorService, "unknown");
    notifier.reviewCompleted(REQUEST, SESSION, CLEAN);

    verifyNoInteractions(executorService);
  }
}
