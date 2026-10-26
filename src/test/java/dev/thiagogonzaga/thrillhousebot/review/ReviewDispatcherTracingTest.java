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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import dev.thiagogonzaga.thrillhousebot.github.TestScopes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The trace a review's spans land in (#1263). The webhook thread's context does not cross to the
 * review worker on its own, so before the fix every model call of a review started a trace of its
 * own. A review now runs in a root span that links to the span each request was dispatched under,
 * and a span the review makes, as the model-call instrumentation does, is that span's child.
 */
class ReviewDispatcherTracingTest {

  private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
  private OpenTelemetrySdk openTelemetry;
  private ReviewOrchestrator orchestrator;
  private CiHoldRevisit ciHoldRevisit;
  private ExecutorService executor;
  private ReviewDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    openTelemetry =
        OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build())
            .build();
    orchestrator = mock(ReviewOrchestrator.class);
    ciHoldRevisit = mock(CiHoldRevisit.class);
    executor = Executors.newSingleThreadExecutor();
    dispatcher =
        new ReviewDispatcher(
            executor,
            orchestrator,
            mock(AutoReviewRateLimiter.class),
            mock(ReviewSkipEmitter.class),
            ciHoldRevisit,
            mock(RunningReviewCoverage.class),
            openTelemetry);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    executor.shutdownNow();
    executor.awaitTermination(5, TimeUnit.SECONDS);
    openTelemetry.close();
  }

  @Test
  void aSpanTheReviewMakesIsInTheReviewSpanLinkedToTheWebhookSpan() throws InterruptedException {
    var done = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              openTelemetry.getTracer("model").spanBuilder("chat").startSpan().end();
              done.countDown();
              return true;
            })
        .when(orchestrator)
        .review(any());

    var webhook = dispatchUnder("POST /api/webhook", request(42, "sha1"));
    assertTrue(done.await(5, TimeUnit.SECONDS));

    var review = awaitSpan(ReviewDispatcher.REVIEW_SPAN);
    var chat = awaitSpan("chat");
    assertFalse(review.getParentSpanContext().isValid(), "the review span is a root");
    assertEquals(List.of(webhook), linkedContexts(review));
    assertEquals(review.getTraceId(), chat.getTraceId());
    assertEquals(review.getSpanId(), chat.getParentSpanId());
    assertEquals("owner", attribute(review, "vcs.owner.name"));
    assertEquals("repo", attribute(review, "vcs.repository.name"));
    assertEquals("42", attribute(review, "vcs.change.id"));
    assertEquals("sha1", attribute(review, "vcs.ref.head.revision"));
  }

  @Test
  void aReviewServingCoalescedDeliveriesLinksEachOfThem() throws InterruptedException {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var finished = new CountDownLatch(2);
    doAnswer(
            invocation -> {
              started.countDown();
              release.await(5, TimeUnit.SECONDS);
              finished.countDown();
              return true;
            })
        .when(orchestrator)
        .review(any());

    var first = dispatchUnder("delivery 1", request(7, "sha1"));
    assertTrue(started.await(5, TimeUnit.SECONDS));
    var second = dispatchUnder("delivery 2", request(7, "sha2"));
    var third = dispatchUnder("delivery 3", request(7, "sha3"));
    release.countDown();
    assertTrue(finished.await(5, TimeUnit.SECONDS));

    var reviews = awaitSpans(ReviewDispatcher.REVIEW_SPAN, 2);
    assertEquals(List.of(first), linkedContexts(reviews.get(0)));
    assertEquals(List.of(second, third), linkedContexts(reviews.get(1)));
    assertEquals("sha3", attribute(reviews.get(1), "vcs.ref.head.revision"));
  }

  @Test
  void aBurstOfCoalescedDeliveriesLinksOnlyTheFirstOnes() throws InterruptedException {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              started.countDown();
              release.await(5, TimeUnit.SECONDS);
              return true;
            })
        .when(orchestrator)
        .review(any());

    dispatchUnder("delivery 0", request(9, "sha0"));
    assertTrue(started.await(5, TimeUnit.SECONDS));
    var coalesced = new ArrayList<SpanContext>();
    for (int i = 1; i <= ReviewDispatcher.MAX_LINKED_TRIGGERS + 5; i++) {
      coalesced.add(dispatchUnder("delivery " + i, request(9, "sha" + i)));
    }
    release.countDown();

    var reviews = awaitSpans(ReviewDispatcher.REVIEW_SPAN, 2);
    assertEquals(
        coalesced.subList(0, ReviewDispatcher.MAX_LINKED_TRIGGERS), linkedContexts(reviews.get(1)));
  }

  @Test
  void aDispatchWithNoCurrentSpanLinksNothing() {
    doAnswer(invocation -> true).when(orchestrator).review(any());

    assertTrue(dispatcher.dispatch(request(5, "")));

    var review = awaitSpan(ReviewDispatcher.REVIEW_SPAN);
    assertTrue(review.getLinks().isEmpty());
    assertNull(attribute(review, "vcs.ref.head.revision"));
  }

  @Test
  void aFailedReviewMarksItsSpanFailed() {
    doThrow(new IllegalStateException("boom")).when(orchestrator).review(any());

    dispatchUnder("POST /api/webhook", request(3, "sha1"));

    var review = awaitSpan(ReviewDispatcher.REVIEW_SPAN);
    assertEquals(StatusCode.ERROR, review.getStatus().getStatusCode());
    assertEquals("exception", review.getEvents().getFirst().getName());
  }

  @Test
  void aCiRecheckRunsInItsOwnSpanLinkedToItsDelivery() {
    var task = new CiHoldRevisit.Recheck("owner", "repo", 11, "sha9", 1L, TestScopes.REPOSITORY_ID);
    doThrow(new IllegalStateException("boom")).when(ciHoldRevisit).revisit(task);

    var span = openTelemetry.getTracer("test").spanBuilder("check_run").startSpan();
    try (var _ = span.makeCurrent()) {
      assertTrue(dispatcher.dispatchCiRecheck(task));
    } finally {
      span.end();
    }

    var recheck = awaitSpan(ReviewDispatcher.CI_RECHECK_SPAN);
    assertFalse(recheck.getParentSpanContext().isValid());
    assertEquals(List.of(span.getSpanContext()), linkedContexts(recheck));
    assertEquals("sha9", attribute(recheck, "vcs.ref.head.revision"));
    assertEquals(StatusCode.ERROR, recheck.getStatus().getStatusCode());
  }

  /** Dispatches {@code req} with a span of {@code name} current, as the webhook thread does. */
  private SpanContext dispatchUnder(String name, ReviewOrchestrator.ReviewRequest req) {
    var span = openTelemetry.getTracer("test").spanBuilder(name).startSpan();
    try (var _ = Context.current().with(span).makeCurrent()) {
      assertTrue(dispatcher.dispatch(req));
    } finally {
      span.end();
    }
    return span.getSpanContext();
  }

  private SpanData awaitSpan(String name) {
    return awaitSpans(name, 1).getFirst();
  }

  private List<SpanData> awaitSpans(String name, int count) {
    var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (true) {
      var spans =
          exporter.getFinishedSpanItems().stream().filter(s -> s.getName().equals(name)).toList();
      if (spans.size() >= count || System.nanoTime() > deadline) {
        assertEquals(count, spans.size(), () -> "finished spans named " + name);
        return spans;
      }
      Thread.onSpinWait();
    }
  }

  private static List<SpanContext> linkedContexts(SpanData span) {
    return span.getLinks().stream().map(LinkData::getSpanContext).toList();
  }

  private static String attribute(SpanData span, String key) {
    return span.getAttributes().get(AttributeKey.stringKey(key));
  }

  private static ReviewOrchestrator.ReviewRequest request(int prNumber, String sha) {
    return new ReviewOrchestrator.ReviewRequest(
        "owner",
        "repo",
        prNumber,
        sha,
        "title",
        "",
        "base",
        "main",
        1L,
        TestScopes.REPOSITORY_ID,
        false);
  }
}
