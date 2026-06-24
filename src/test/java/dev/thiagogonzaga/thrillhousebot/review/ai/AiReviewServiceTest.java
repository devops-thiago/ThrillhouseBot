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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.dashboard.SessionEventBroadcaster;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class AiReviewServiceTest {

  @Mock private PrReviewer prReviewer;

  @Mock private ReviewResponseParser parser;

  @Mock private ThrillhouseConfig config;

  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;

  @Mock private SessionEventBroadcaster broadcaster;

  private static final AiReviewService.PromptInputs PROMPT_INPUTS =
      new AiReviewService.PromptInputs("diff", "", "base", "", "", "", "");

  private AiReviewService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.maxAiRetries()).thenReturn(3);
    when(reviewConfig.aiRetryBaseDelayMs()).thenReturn(1L);
    when(reviewConfig.aiTimeoutSeconds()).thenReturn(5);
    service = new AiReviewService(prReviewer, parser, config, broadcaster);
  }

  @Test
  void shouldUseUnknownErrorReasonOnRetryAfterNullMessageFailure() {
    ReviewSession session = reviewSession();
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(new ErrorTokenStream(new AiReviewException(null, 1, new RuntimeException())))
        .thenReturn(new FakeTokenStream("{\"findings\":[]}"));
    when(parser.parse(anyString())).thenReturn(new ReviewResponse(List.of(), List.of(), null));

    service.review(session, PROMPT_INPUTS);

    var captor = ArgumentCaptor.forClass(SessionEventBroadcaster.SessionEvent.class);
    verify(broadcaster, atLeastOnce()).broadcast(captor.capture());
    assertTrue(
        captor.getAllValues().stream()
            .anyMatch(
                e ->
                    "review.retry".equals(e.type())
                        && "Unknown error".equals(e.data().get("reason"))));
  }

  @Test
  void reviewBatchEmitsBatchProgressAndSuppressesTokenStream() {
    ReviewSession session = reviewSession();
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(new FakeTokenStream("{\"findings\":[]}"));
    when(parser.parse(anyString())).thenReturn(new ReviewResponse(List.of(), List.of(), null));

    var response = service.reviewBatch(session, PROMPT_INPUTS, 2, 4);

    assertNotNull(response);
    var captor = ArgumentCaptor.forClass(SessionEventBroadcaster.SessionEvent.class);
    verify(broadcaster, atLeastOnce()).broadcast(captor.capture());
    var events = captor.getAllValues();
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    "review.batch".equals(e.type())
                        && Integer.valueOf(2).equals(e.data().get("batchIndex"))
                        && Integer.valueOf(4).equals(e.data().get("batchCount"))),
        "expected a review.batch progress event for batch 2/4");
    assertTrue(
        events.stream().noneMatch(e -> "review.stream".equals(e.type())),
        "a blocking batch must not broadcast per-token stream events");
  }

  @Test
  void summarizeCallsSummaryStreamBlockingAndReturnsParsedResponse() {
    ReviewSession session = reviewSession();
    when(prReviewer.summarizeStream(
            anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(new FakeTokenStream("{\"findings\":[]}"));
    when(parser.parse(anyString())).thenReturn(new ReviewResponse(List.of(), List.of(), null));

    var response =
        service.summarize(session, new AiReviewService.SummaryInputs("ctx", "[]", "files", "", ""));

    assertNotNull(response);
    verify(prReviewer)
        .summarizeStream(anyString(), anyString(), anyString(), anyString(), anyString());
    var captor = ArgumentCaptor.forClass(SessionEventBroadcaster.SessionEvent.class);
    verify(broadcaster, atLeast(0)).broadcast(captor.capture());
    assertTrue(
        captor.getAllValues().stream().noneMatch(e -> "review.stream".equals(e.type())),
        "the summary call must not broadcast a per-token stream");
  }

  @Test
  void shouldWrapStreamErrorWithNullMessage() {
    ReviewSession session = reviewSession();
    when(reviewConfig.maxAiRetries()).thenReturn(1);
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(new ErrorTokenStream(new RuntimeException()));

    AiReviewException ex =
        assertThrows(AiReviewException.class, () -> service.review(session, PROMPT_INPUTS));

    assertNotNull(ex.getCause());
    assertEquals("AI review failed", ex.getCause().getMessage());
  }

  @Test
  void shouldRetryUntilSuccess() {
    ReviewSession session = reviewSession();

    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(new FakeTokenStream("{\"findings\":[]}"))
        .thenReturn(new FakeTokenStream("{\"findings\":[]}"))
        .thenReturn(new FakeTokenStream("{\"findings\":[]}"));

    when(parser.parse(anyString()))
        .thenThrow(new IllegalArgumentException("bad json"))
        .thenThrow(new IllegalArgumentException("bad json"))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    var response = service.review(session, PROMPT_INPUTS);

    assertNotNull(response);
    verify(prReviewer, times(3))
        .reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString());

    var captor = ArgumentCaptor.forClass(SessionEventBroadcaster.SessionEvent.class);
    verify(broadcaster, atLeastOnce()).broadcast(captor.capture());
    assertTrue(captor.getAllValues().stream().anyMatch(e -> "review.retry".equals(e.type())));
  }

  @Test
  void shouldStopBroadcastingAfterStreamError() {
    ReviewSession session = reviewSession();
    when(reviewConfig.maxAiRetries()).thenReturn(1);
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(new OrphanedAfterErrorTokenStream(100));

    assertThrows(AiReviewException.class, () -> service.review(session, PROMPT_INPUTS));

    clearInvocations(broadcaster);
    verify(broadcaster, after(300).never()).broadcast(any());
  }

  @Test
  void shouldIgnoreLateCompletionAfterTimeout() {
    ReviewSession session = reviewSession();
    when(reviewConfig.maxAiRetries()).thenReturn(1);
    when(reviewConfig.aiTimeoutSeconds()).thenReturn(1);
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(new OrphanedAfterCompleteTokenStream(2_000));
    when(parser.parse(anyString())).thenReturn(new ReviewResponse(List.of(), List.of(), null));

    assertThrows(AiReviewException.class, () -> service.review(session, PROMPT_INPUTS));

    verify(parser, never()).parse(anyString());
  }

  @Test
  void shouldStopBroadcastingAfterTimeout() {
    ReviewSession session = reviewSession();
    when(reviewConfig.maxAiRetries()).thenReturn(1);
    when(reviewConfig.aiTimeoutSeconds()).thenReturn(1);
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(new OrphanedTokenStream(100));

    assertThrows(AiReviewException.class, () -> service.review(session, PROMPT_INPUTS));

    clearInvocations(broadcaster);
    verify(broadcaster, after(300).never()).broadcast(any());
  }

  @Test
  void streamBufferShouldHandleFallbacksAndPendingChunks() {
    var buffer = new AiReviewService.StreamBuffer();

    assertEquals("", buffer.textOrFallback(null));
    assertEquals("fallback", buffer.textOrFallback("fallback"));
    assertNull(buffer.takePending());

    buffer.append("streamed");
    assertEquals("streamed", buffer.textOrFallback("fallback"));

    var pending = buffer.takePending();
    assertEquals("streamed", pending.chunk());
    assertEquals("streamed", pending.tail());
    assertEquals(8, pending.totalChars());
    assertNull(buffer.takePending());
  }

  @Test
  void asAiReviewExceptionShouldDefaultMessageWhenCauseIsMissing() {
    AiReviewException ex =
        AiReviewService.asAiReviewException(new ExecutionException((Throwable) null));

    assertEquals("AI review failed", ex.getMessage());
    assertInstanceOf(ExecutionException.class, ex.getCause());
  }

  @Test
  void shouldIgnoreLateErrorAfterTimeout() throws Exception {
    ReviewSession session = reviewSession();
    when(reviewConfig.maxAiRetries()).thenReturn(1);
    when(reviewConfig.aiTimeoutSeconds()).thenReturn(1);
    var stream = new LateErrorTokenStream(1_500);
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(stream);

    AiReviewException ex =
        assertThrows(AiReviewException.class, () -> service.review(session, PROMPT_INPUTS));

    // The failure is the timeout — the stale error landing afterwards is gated out
    assertTrue(ex.getCause().getMessage().contains("timed out"));

    clearInvocations(broadcaster);
    // A fixed verify window can close before the ~1.5s late error fires; wait for the
    // delivery itself so the never() check actually observes the gated callback
    assertTrue(stream.awaitErrorDelivered(5, TimeUnit.SECONDS));
    verify(broadcaster, never()).broadcast(any());
  }

  @Test
  void shouldInterruptDuringStreamWait() throws Exception {
    ReviewSession session = reviewSession();
    when(reviewConfig.aiTimeoutSeconds()).thenReturn(30);
    var streamWaitStarted = new CountDownLatch(1);
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenAnswer(
            invocation -> {
              streamWaitStarted.countDown();
              return new HangingTokenStream();
            });

    var thrown = new AtomicReference<Throwable>();
    var worker =
        new Thread(
            () -> {
              try {
                service.review(session, PROMPT_INPUTS);
              } catch (RuntimeException e) {
                thrown.set(e);
              }
            });

    worker.start();
    assertTrue(streamWaitStarted.await(2, TimeUnit.SECONDS));
    worker.interrupt();
    worker.join(5_000);

    assertFalse(worker.isAlive());
    assertInstanceOf(AiReviewException.class, thrown.get());
    assertTrue(thrown.get().getMessage().contains("interrupted"));
  }

  @Test
  void shouldTimeoutWhenStreamNeverCompletes() {
    ReviewSession session = reviewSession();
    when(reviewConfig.aiTimeoutSeconds()).thenReturn(1);
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(new HangingTokenStream());

    AiReviewException ex =
        assertThrows(AiReviewException.class, () -> service.review(session, PROMPT_INPUTS));

    assertNotNull(ex.getCause());
    assertTrue(ex.getCause().getMessage().contains("timed out"));
  }

  @Test
  void shouldParseFromCompleteResponseWhenNoPartialTokens() {
    ReviewSession session = reviewSession();
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(new CompleteOnlyTokenStream("{\"findings\":[]}"));
    when(parser.parse("{\"findings\":[]}"))
        .thenReturn(new ReviewResponse(List.of(), List.of(), null));

    var response = service.review(session, PROMPT_INPUTS);

    assertNotNull(response);
    verify(parser).parse("{\"findings\":[]}");
  }

  @Test
  void shouldCoalesceStreamBroadcastsForManySmallTokens() {
    ReviewSession session = reviewSession();
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(new FakeTokenStream("abcdefghij", 1));
    when(parser.parse("abcdefghij")).thenReturn(new ReviewResponse(List.of(), List.of(), null));

    service.review(session, PROMPT_INPUTS);

    var captor = ArgumentCaptor.forClass(SessionEventBroadcaster.SessionEvent.class);
    verify(broadcaster, atLeastOnce()).broadcast(captor.capture());

    var streamEvents =
        captor.getAllValues().stream().filter(e -> "review.stream".equals(e.type())).count();
    assertTrue(streamEvents < 10, "expected coalesced stream events, got " + streamEvents);

    var lastStream =
        captor.getAllValues().stream()
            .filter(e -> "review.stream".equals(e.type()))
            .reduce((first, second) -> second)
            .orElseThrow();
    assertEquals(10, lastStream.data().get("totalChars"));
    assertEquals("abcdefghij", lastStream.data().get("chunk"));
  }

  @Test
  void shouldFlushPendingChunkOnStreamError() {
    ReviewSession session = reviewSession();
    when(reviewConfig.maxAiRetries()).thenReturn(1);
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(
            new PartialThenErrorTokenStream(
                "partial-output", new RuntimeException("stream failed")));

    assertThrows(AiReviewException.class, () -> service.review(session, PROMPT_INPUTS));

    var captor = ArgumentCaptor.forClass(SessionEventBroadcaster.SessionEvent.class);
    verify(broadcaster, atLeastOnce()).broadcast(captor.capture());

    var streamEvent =
        captor.getAllValues().stream()
            .filter(e -> "review.stream".equals(e.type()))
            .findFirst()
            .orElseThrow();
    assertEquals("partial-output", streamEvent.data().get("chunk"));
  }

  @Test
  void shouldFlushOnCharThresholdBeforeCompletion() {
    ReviewSession session = reviewSession();
    var payload = "x".repeat(768);
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(new FakeTokenStream(payload, 256));
    when(parser.parse(payload)).thenReturn(new ReviewResponse(List.of(), List.of(), null));

    service.review(session, PROMPT_INPUTS);

    var captor = ArgumentCaptor.forClass(SessionEventBroadcaster.SessionEvent.class);
    verify(broadcaster, atLeastOnce()).broadcast(captor.capture());

    var streamEvents =
        captor.getAllValues().stream().filter(e -> "review.stream".equals(e.type())).count();
    assertTrue(streamEvents >= 2, "expected mid-stream flush before completion");
  }

  @Test
  void shouldFlushOnTimeIntervalBeforeCompletion() {
    ReviewSession session = reviewSession();
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(new DelayedChunkTokenStream("abcdef", 1, 300));
    when(parser.parse("abcdef")).thenReturn(new ReviewResponse(List.of(), List.of(), null));

    service.review(session, PROMPT_INPUTS);

    var captor = ArgumentCaptor.forClass(SessionEventBroadcaster.SessionEvent.class);
    verify(broadcaster, atLeastOnce()).broadcast(captor.capture());

    var streamEvents =
        captor.getAllValues().stream().filter(e -> "review.stream".equals(e.type())).count();
    assertTrue(streamEvents >= 2, "expected time-based mid-stream flush");
  }

  @Test
  void shouldFlushPendingChunkOnTimeout() {
    ReviewSession session = reviewSession();
    when(reviewConfig.maxAiRetries()).thenReturn(1);
    when(reviewConfig.aiTimeoutSeconds()).thenReturn(1);
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(new PartialHangingTokenStream("partial-output"));

    assertThrows(AiReviewException.class, () -> service.review(session, PROMPT_INPUTS));

    var captor = ArgumentCaptor.forClass(SessionEventBroadcaster.SessionEvent.class);
    verify(broadcaster, atLeastOnce()).broadcast(captor.capture());

    assertTrue(
        captor.getAllValues().stream().anyMatch(e -> "review.stream".equals(e.type())),
        "expected pending stream chunk flushed before timeout failure");
  }

  @Test
  void shouldTruncateStreamTailForLargeResponses() {
    ReviewSession session = reviewSession();
    var chunk = "x".repeat(500);
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(new FakeTokenStream(chunk + chunk + chunk + chunk + chunk));
    when(parser.parse(anyString())).thenReturn(new ReviewResponse(List.of(), List.of(), null));

    service.review(session, PROMPT_INPUTS);

    var captor = ArgumentCaptor.forClass(SessionEventBroadcaster.SessionEvent.class);
    verify(broadcaster, atLeastOnce()).broadcast(captor.capture());

    var streamEvent =
        captor.getAllValues().stream()
            .filter(e -> "review.stream".equals(e.type()))
            .reduce((first, second) -> second)
            .orElseThrow();
    var tail = (String) streamEvent.data().get("tail");
    assertEquals(2000, tail.length());
    assertEquals(2500, streamEvent.data().get("totalChars"));
  }

  @Test
  void shouldSanitizeBlankErrorMessagesOnStreamFailure() {
    ReviewSession session = reviewSession();
    when(reviewConfig.maxAiRetries()).thenReturn(1);
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenThrow(new RuntimeException());

    assertThrows(AiReviewException.class, () -> service.review(session, PROMPT_INPUTS));

    var captor = ArgumentCaptor.forClass(SessionEventBroadcaster.SessionEvent.class);
    verify(broadcaster, atLeastOnce()).broadcast(captor.capture());

    var failedEvent =
        captor.getAllValues().stream()
            .filter(e -> "review.stream.failed".equals(e.type()))
            .findFirst()
            .orElseThrow();
    assertEquals("RuntimeException", failedEvent.data().get("reason"));
  }

  @Test
  void shouldRethrowAiReviewExceptionFromStream() {
    ReviewSession session = reviewSession();
    when(reviewConfig.maxAiRetries()).thenReturn(1);
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(
            new ErrorTokenStream(
                new AiReviewException("model unavailable", 1, new RuntimeException())));

    AiReviewException ex =
        assertThrows(AiReviewException.class, () -> service.review(session, PROMPT_INPUTS));

    assertNotNull(ex.getCause());
    assertEquals("model unavailable", ex.getCause().getMessage());
  }

  @Test
  void shouldWrapNonRuntimeStreamErrors() {
    ReviewSession session = reviewSession();
    when(reviewConfig.maxAiRetries()).thenReturn(1);
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(new ErrorTokenStream(new Exception("checked failure")));

    AiReviewException ex =
        assertThrows(AiReviewException.class, () -> service.review(session, PROMPT_INPUTS));

    assertNotNull(ex.getCause());
    assertEquals("AI review failed", ex.getCause().getMessage());
  }

  @Test
  void shouldTruncateLongSanitizedErrorMessages() {
    ReviewSession session = reviewSession();
    when(reviewConfig.maxAiRetries()).thenReturn(1);
    var longMessage = "x".repeat(250);
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenThrow(new RuntimeException(longMessage));

    assertThrows(AiReviewException.class, () -> service.review(session, PROMPT_INPUTS));

    var captor = ArgumentCaptor.forClass(SessionEventBroadcaster.SessionEvent.class);
    verify(broadcaster, atLeastOnce()).broadcast(captor.capture());

    var reason =
        (String)
            captor.getAllValues().stream()
                .filter(e -> "review.stream.failed".equals(e.type()))
                .findFirst()
                .orElseThrow()
                .data()
                .get("reason");
    assertEquals(203, reason.length());
    assertTrue(reason.endsWith("..."));
  }

  @Test
  void shouldThrowWhenRetryDelayInterrupted() throws Exception {
    var sleepStarted = new CountDownLatch(1);
    var spiedService = spy(service);
    doAnswer(
            invocation -> {
              sleepStarted.countDown();
              return invocation.callRealMethod();
            })
        .when(spiedService)
        .sleep(any(Duration.class));

    var thrown = new AtomicReference<Throwable>();
    var worker =
        new Thread(
            () -> {
              try {
                spiedService.sleep(Duration.ofSeconds(5));
              } catch (RuntimeException e) {
                thrown.set(e);
              }
            });

    worker.start();
    assertTrue(sleepStarted.await(2, TimeUnit.SECONDS));
    worker.interrupt();
    worker.join(5000);

    assertFalse(worker.isAlive());
    assertInstanceOf(AiReviewException.class, thrown.get());
    assertTrue(thrown.get().getMessage().contains("Retry delay interrupted"));
  }

  @Test
  void shouldUseUnknownErrorReasonOnRetryWhenFailureHasNoMessage() {
    ReviewSession session = reviewSession();
    when(reviewConfig.maxAiRetries()).thenReturn(2);
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenThrow(new RuntimeException())
        .thenReturn(new FakeTokenStream("{\"findings\":[]}"));
    when(parser.parse(anyString())).thenReturn(new ReviewResponse(List.of(), List.of(), null));

    service.review(session, PROMPT_INPUTS);

    var captor = ArgumentCaptor.forClass(SessionEventBroadcaster.SessionEvent.class);
    verify(broadcaster, atLeastOnce()).broadcast(captor.capture());

    var retryEvent =
        captor.getAllValues().stream()
            .filter(e -> "review.retry".equals(e.type()))
            .findFirst()
            .orElseThrow();
    assertEquals("Unknown error", retryEvent.data().get("reason"));
  }

  @Test
  void shouldSanitizeNullErrors() {
    assertEquals("Unknown error", AiReviewService.sanitize(null));
  }

  @Test
  void shouldThrowAfterExhaustingRetries() {
    ReviewSession session = reviewSession();

    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(new FakeTokenStream("not-json"));
    when(parser.parse(anyString())).thenThrow(new IllegalArgumentException("still bad"));

    AiReviewException ex =
        assertThrows(AiReviewException.class, () -> service.review(session, PROMPT_INPUTS));

    assertEquals(3, ex.attempts());
    verify(prReviewer, times(3))
        .reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString());
  }

  @Test
  void shouldCancelStreamingHandleOnTimeout() {
    ReviewSession session = reviewSession();
    when(reviewConfig.maxAiRetries()).thenReturn(1);
    when(reviewConfig.aiTimeoutSeconds()).thenReturn(1);
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(new HangingTokenStream());
    StreamingHandle handle = mock(StreamingHandle.class);
    var cancellableService =
        new AiReviewService(prReviewer, parser, config, broadcaster) {
          @Override
          StreamingHandle streamingHandleOf(TokenStream stream) {
            return handle;
          }
        };

    assertThrows(AiReviewException.class, () -> cancellableService.review(session, PROMPT_INPUTS));

    verify(handle).cancel();
  }

  @Test
  void shouldSurviveTimeoutWhenNoStreamingHandleAvailable() {
    ReviewSession session = reviewSession();
    when(reviewConfig.maxAiRetries()).thenReturn(1);
    when(reviewConfig.aiTimeoutSeconds()).thenReturn(1);
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(new HangingTokenStream());

    // HangingTokenStream is not a Quarkus stream, so no handle exists — timeout must still work
    AiReviewException ex =
        assertThrows(AiReviewException.class, () -> service.review(session, PROMPT_INPUTS));

    assertNotNull(ex.getCause());
    assertTrue(ex.getCause().getMessage().contains("timed out"));
  }

  @Test
  void shouldSurviveStreamingHandleCancelFailure() {
    ReviewSession session = reviewSession();
    when(reviewConfig.maxAiRetries()).thenReturn(1);
    when(reviewConfig.aiTimeoutSeconds()).thenReturn(1);
    when(prReviewer.reviewStream(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(new HangingTokenStream());
    StreamingHandle handle = mock(StreamingHandle.class);
    doThrow(new IllegalStateException("already closed")).when(handle).cancel();
    var cancellableService =
        new AiReviewService(prReviewer, parser, config, broadcaster) {
          @Override
          StreamingHandle streamingHandleOf(TokenStream stream) {
            return handle;
          }
        };

    AiReviewException ex =
        assertThrows(
            AiReviewException.class, () -> cancellableService.review(session, PROMPT_INPUTS));

    assertNotNull(ex.getCause());
    assertTrue(ex.getCause().getMessage().contains("timed out"));
  }

  @Test
  void streamingHandleOfShouldUnwrapQuarkusStreamsOnly() {
    var quarkusStream =
        mock(io.quarkiverse.langchain4j.runtime.aiservice.QuarkusAiServiceTokenStream.class);
    StreamingHandle handle = mock(StreamingHandle.class);
    when(quarkusStream.getStreamingHandle()).thenReturn(handle);

    assertSame(handle, service.streamingHandleOf(quarkusStream));
    assertNull(service.streamingHandleOf(new FakeTokenStream("{\"findings\":[]}")));
  }

  private static ReviewSession reviewSession() {
    var session = new ReviewSession();
    session.id = 42L;
    session.setRepository("owner/repo");
    session.setPrNumber(1);
    session.setPrTitle("Test");
    session.setCommitSha("abc");
    session.setTimestamp(Instant.parse("2025-06-01T12:00:00Z"));
    return session;
  }
}
