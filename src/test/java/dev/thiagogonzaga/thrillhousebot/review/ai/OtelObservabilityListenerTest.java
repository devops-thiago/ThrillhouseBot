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

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig.AiPricingConfig;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig.AiPricingConfig.ModelPricing;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSessionUpdater;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleCounterBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.LongCounterBuilder;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongHistogramBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

class OtelObservabilityListenerTest {

  private OtelObservabilityListener listener;
  private ReviewSessionUpdater sessionUpdater;
  private OpenTelemetry otel;
  private LongHistogram tokenHistogram;
  private DoubleHistogram durationHistogram;
  private DoubleCounter costCounter;
  private ThrillhouseConfig config;
  private AiPricingConfig aiConfig;
  private Vertx vertx;

  @BeforeEach
  void setUp() {
    otel = mock(OpenTelemetry.class);
    Meter meter = mock(Meter.class);
    when(otel.getMeter("thrillhousebot")).thenReturn(meter);

    DoubleHistogramBuilder tokenDoubleBuilder =
        mock(DoubleHistogramBuilder.class, Answers.RETURNS_SELF);
    LongHistogramBuilder tokenLongBuilder = mock(LongHistogramBuilder.class, Answers.RETURNS_SELF);
    tokenHistogram = mock(LongHistogram.class);
    when(meter.histogramBuilder("gen_ai.client.token.usage")).thenReturn(tokenDoubleBuilder);
    when(tokenDoubleBuilder.ofLongs()).thenReturn(tokenLongBuilder);
    when(tokenLongBuilder.build()).thenReturn(tokenHistogram);

    DoubleHistogramBuilder durationBuilder =
        mock(DoubleHistogramBuilder.class, Answers.RETURNS_SELF);
    durationHistogram = mock(DoubleHistogram.class);
    when(meter.histogramBuilder("gen_ai.client.operation.duration")).thenReturn(durationBuilder);
    when(durationBuilder.build()).thenReturn(durationHistogram);

    LongCounterBuilder longCounterBuilder = mock(LongCounterBuilder.class, Answers.RETURNS_SELF);
    DoubleCounterBuilder doubleCounterBuilder =
        mock(DoubleCounterBuilder.class, Answers.RETURNS_SELF);
    costCounter = mock(DoubleCounter.class);
    when(meter.counterBuilder("thrillhouse.ai.cost.total")).thenReturn(longCounterBuilder);
    when(longCounterBuilder.ofDoubles()).thenReturn(doubleCounterBuilder);
    when(doubleCounterBuilder.build()).thenReturn(costCounter);

    config = mock(ThrillhouseConfig.class);
    aiConfig = mock(AiPricingConfig.class);
    when(config.ai()).thenReturn(aiConfig);
    when(aiConfig.pricing()).thenReturn(Map.of());
    when(aiConfig.baseUrl()).thenReturn("https://api.deepseek.com/v1");
    when(aiConfig.providerName()).thenReturn(Optional.empty());

    sessionUpdater = mock(ReviewSessionUpdater.class);
    vertx = mock(Vertx.class);
    when(vertx.<Void>executeBlocking(any(Callable.class)))
        .thenAnswer(
            invocation -> {
              Callable<Void> callable = invocation.getArgument(0);
              callable.call();

              var future = mock(Future.class);
              when(future.onFailure(any())).thenReturn(future);
              return future;
            });
    listener = new OtelObservabilityListener(otel, config, sessionUpdater, vertx);
  }

  @AfterEach
  void tearDown() {
    ReviewSessionContext.reset();
  }

  @Test
  void onRequestShouldStoreStartTimeSessionIdAndStreamAttempt() {
    var ctx = mock(ChatModelRequestContext.class);
    var attrs = new HashMap<>();
    when(ctx.attributes()).thenReturn(attrs);

    ReviewSessionContext.bind(42L, 3);
    listener.onRequest(ctx);
    ReviewSessionContext.clear();

    assertTrue(attrs.containsKey(OtelObservabilityListener.ATTR_START_NANOS));
    assertTrue(attrs.get(OtelObservabilityListener.ATTR_START_NANOS) instanceof Long);
    assertEquals(42L, attrs.get(OtelObservabilityListener.ATTR_SESSION_ID));
    assertEquals(3, attrs.get(OtelObservabilityListener.ATTR_STREAM_ATTEMPT));
  }

  @Test
  void onRequestShouldOmitSessionAttributesWhenNotBound() {
    var ctx = mock(ChatModelRequestContext.class);
    var attrs = new HashMap<>();
    when(ctx.attributes()).thenReturn(attrs);

    listener.onRequest(ctx);

    assertFalse(attrs.containsKey(OtelObservabilityListener.ATTR_SESSION_ID));
    assertFalse(attrs.containsKey(OtelObservabilityListener.ATTR_STREAM_ATTEMPT));
  }

  @Test
  void onErrorShouldHandleNullError() {
    var attrs = requestAttributes(15L, 1);
    var ctx = mock(ChatModelErrorContext.class);
    when(ctx.attributes()).thenReturn(attrs);
    when(ctx.error()).thenReturn(null);

    listener.onError(ctx);

    verify(sessionUpdater).recordFailure(eq(15L), eq("Unknown error"), anyLong());
  }

  @Test
  void onResponseShouldLogWhenWorkerPersistenceFails() {
    OpenTelemetry localOtel = mock(OpenTelemetry.class);
    Meter meter = mock(Meter.class);
    when(localOtel.getMeter("thrillhousebot")).thenReturn(meter);
    DoubleHistogramBuilder tokenDoubleBuilder =
        mock(DoubleHistogramBuilder.class, org.mockito.Answers.RETURNS_SELF);
    LongHistogramBuilder tokenLongBuilder =
        mock(LongHistogramBuilder.class, org.mockito.Answers.RETURNS_SELF);
    LongHistogram tokenHist = mock(LongHistogram.class);
    when(meter.histogramBuilder("gen_ai.client.token.usage")).thenReturn(tokenDoubleBuilder);
    when(tokenDoubleBuilder.ofLongs()).thenReturn(tokenLongBuilder);
    when(tokenLongBuilder.build()).thenReturn(tokenHist);
    DoubleHistogramBuilder durationBuilder =
        mock(DoubleHistogramBuilder.class, org.mockito.Answers.RETURNS_SELF);
    DoubleHistogram durationHist = mock(DoubleHistogram.class);
    when(meter.histogramBuilder("gen_ai.client.operation.duration")).thenReturn(durationBuilder);
    when(durationBuilder.build()).thenReturn(durationHist);
    LongCounterBuilder longCounterBuilder =
        mock(LongCounterBuilder.class, org.mockito.Answers.RETURNS_SELF);
    DoubleCounterBuilder doubleCounterBuilder =
        mock(DoubleCounterBuilder.class, org.mockito.Answers.RETURNS_SELF);
    DoubleCounter costCnt = mock(DoubleCounter.class);
    when(meter.counterBuilder("thrillhouse.ai.cost.total")).thenReturn(longCounterBuilder);
    when(longCounterBuilder.ofDoubles()).thenReturn(doubleCounterBuilder);
    when(doubleCounterBuilder.build()).thenReturn(costCnt);

    ReviewSessionUpdater failingUpdater = mock(ReviewSessionUpdater.class);
    doThrow(new RuntimeException("db down"))
        .when(failingUpdater)
        .recordModelUsage(anyLong(), any(), anyInt(), anyInt(), anyDouble(), anyLong());
    Vertx failingVertx = mock(Vertx.class);

    var failedFuture = mock(Future.class);
    when(failingVertx.<Void>executeBlocking(any(Callable.class))).thenReturn(failedFuture);
    when(failedFuture.onFailure(any(io.vertx.core.Handler.class)))
        .thenAnswer(
            invocation -> {
              io.vertx.core.Handler<Throwable> handler = invocation.getArgument(0);
              handler.handle(new RuntimeException("db down"));
              return failedFuture;
            });

    var failingListener =
        new OtelObservabilityListener(localOtel, config, failingUpdater, failingVertx);
    var attrs = requestAttributes(failingListener, 16L, 1);

    assertDoesNotThrow(() -> failingListener.onResponse(responseContext(attrs)));
    // A failing session persistence must not stop OTel metrics from being recorded
    verify(tokenHist, atLeastOnce()).record(anyLong(), any());
    verify(durationHist).record(anyDouble(), any());
    verify(costCnt).add(anyDouble(), any());
  }

  @Test
  void onResponseShouldRecordMetrics() {
    var attrs = requestAttributes(99L, 1);
    var ctx = responseContext(attrs);

    listener.onResponse(ctx);

    verify(tokenHistogram, atLeastOnce()).record(anyLong(), any());
    verify(durationHistogram).record(anyDouble(), any());
    verify(costCounter).add(anyDouble(), any());
  }

  @Test
  void onResponseShouldTagMetricsWithProviderDerivedFromBaseUrl() {
    var ctx = responseContext(requestAttributes(99L, 1));

    listener.onResponse(ctx);

    assertEquals("deepseek", recordedProviderName());
  }

  @Test
  void onResponseShouldPreferExplicitlyConfiguredProviderName() {
    when(aiConfig.providerName()).thenReturn(Optional.of("  my-gateway  "));
    var overridden = new OtelObservabilityListener(otel, config, sessionUpdater, vertx);

    overridden.onResponse(responseContext(requestAttributes(overridden, 1L, 1)));

    assertEquals("my-gateway", recordedProviderName());
  }

  /** Captures the {@code gen_ai.provider.name} label attached to the recorded duration metric. */
  private String recordedProviderName() {
    var attrCaptor = ArgumentCaptor.forClass(Attributes.class);
    verify(durationHistogram).record(anyDouble(), attrCaptor.capture());
    return attrCaptor.getValue().get(AttributeKey.stringKey("gen_ai.provider.name"));
  }

  @Test
  void onResponseShouldIgnoreStaleStreamAttempt() {
    var attrs = requestAttributes(42L, 1);
    ReviewSessionContext.invalidate(42L);
    var ctx = responseContext(attrs);

    listener.onResponse(ctx);

    verify(sessionUpdater, never())
        .recordModelUsage(anyLong(), any(), anyInt(), anyInt(), anyDouble(), anyLong());
  }

  @Test
  void onResponseShouldIgnoreCallbackFromPreviousAttemptDuringRetry() {
    var attrs = requestAttributes(42L, 1);
    // The retry attempt re-registered the session — attempt 1's late callback is stale
    ReviewSessionContext.bind(42L, 2);
    ReviewSessionContext.clear();
    var ctx = responseContext(attrs);

    listener.onResponse(ctx);

    verify(sessionUpdater, never())
        .recordModelUsage(anyLong(), any(), anyInt(), anyInt(), anyDouble(), anyLong());
  }

  @Test
  void onResponseShouldPersistSessionData() {
    var attrs = requestAttributes(42L, 1);
    var ctx = responseContext(attrs);

    listener.onResponse(ctx);

    verify(sessionUpdater)
        .recordModelUsage(eq(42L), eq("deepseek-chat"), eq(100), eq(50), anyDouble(), anyLong());
  }

  @Test
  void onResponseShouldCalculateCostWithPricing() {
    ModelPricing modelPricing = mock(ModelPricing.class);
    when(modelPricing.inputPer1k()).thenReturn(0.14);
    when(modelPricing.outputPer1k()).thenReturn(0.28);
    when(aiConfig.pricing()).thenReturn(Map.of("deepseek-chat", modelPricing));

    var attrs = requestAttributes(7L, 1);
    var ctx = responseContext(attrs);
    var usage = ctx.chatResponse().tokenUsage();
    when(usage.inputTokenCount()).thenReturn(1000);
    when(usage.outputTokenCount()).thenReturn(1000);
    when(usage.totalTokenCount()).thenReturn(2000);

    listener.onResponse(ctx);

    var costCaptor = ArgumentCaptor.forClass(Double.class);
    verify(sessionUpdater)
        .recordModelUsage(
            eq(7L), eq("deepseek-chat"), eq(1000), eq(1000), costCaptor.capture(), anyLong());
    assertEquals(0.42, costCaptor.getValue(), 0.001);
  }

  @Test
  void onResponseShouldHandleNullPricing() {
    when(aiConfig.pricing()).thenReturn(Map.of());

    var attrs = requestAttributes(8L, 1);
    var ctx = responseContext(attrs);
    when(ctx.chatResponse().modelName()).thenReturn("unknown-model");
    var usage = ctx.chatResponse().tokenUsage();
    when(usage.inputTokenCount()).thenReturn(500);
    when(usage.outputTokenCount()).thenReturn(300);
    when(usage.totalTokenCount()).thenReturn(800);

    listener.onResponse(ctx);

    verify(sessionUpdater)
        .recordModelUsage(eq(8L), eq("unknown-model"), eq(500), eq(300), eq(0.0), anyLong());
    verify(costCounter).add(eq(0.0), any());
  }

  @Test
  void onResponseShouldNotPersistWhenNoSessionInAttributes() {
    var attrs = new HashMap<>();
    attrs.put(OtelObservabilityListener.ATTR_START_NANOS, System.nanoTime() - 1_000_000_000L);
    var ctx = responseContext(attrs);

    assertDoesNotThrow(() -> listener.onResponse(ctx));
    verify(sessionUpdater, never())
        .recordModelUsage(anyLong(), any(), anyInt(), anyInt(), anyDouble(), anyLong());
    verify(tokenHistogram, atLeastOnce()).record(anyLong(), any());
  }

  @Test
  void onResponseShouldUseSessionIdFromAttributesUnderConcurrentReviews() {
    var attrsA = requestAttributes(10L, 1);
    var attrsB = requestAttributes(20L, 1);

    ReviewSessionContext.bind(99L, 1);
    var strayRequest = mock(ChatModelRequestContext.class);
    var strayAttrs = new HashMap<>();
    when(strayRequest.attributes()).thenReturn(strayAttrs);
    listener.onRequest(strayRequest);
    ReviewSessionContext.clear();

    listener.onResponse(responseContext(attrsA));
    listener.onResponse(responseContext(attrsB));

    verify(sessionUpdater)
        .recordModelUsage(eq(10L), eq("deepseek-chat"), eq(100), eq(50), anyDouble(), anyLong());
    verify(sessionUpdater)
        .recordModelUsage(eq(20L), eq("deepseek-chat"), eq(100), eq(50), anyDouble(), anyLong());
    verify(sessionUpdater, never())
        .recordModelUsage(eq(99L), any(), anyInt(), anyInt(), anyDouble(), anyLong());
  }

  @Test
  void onErrorShouldPersistFailedSession() {
    var attrs = requestAttributes(99L, 1);
    var ctx = mock(ChatModelErrorContext.class);
    when(ctx.attributes()).thenReturn(attrs);
    when(ctx.error()).thenReturn(new RuntimeException("API rate limit exceeded"));

    listener.onError(ctx);

    verify(sessionUpdater).recordFailure(eq(99L), eq("API rate limit exceeded"), anyLong());
  }

  @Test
  void onErrorShouldIgnoreStaleStreamAttempt() {
    var attrs = requestAttributes(99L, 1);
    ReviewSessionContext.invalidate(99L);
    var ctx = mock(ChatModelErrorContext.class);
    when(ctx.attributes()).thenReturn(attrs);
    when(ctx.error()).thenReturn(new RuntimeException("stale failure"));

    listener.onError(ctx);

    verify(sessionUpdater, never()).recordFailure(anyLong(), any(), anyLong());
  }

  @Test
  void onErrorShouldNotPersistWhenNoSessionInAttributes() {
    var attrs = new HashMap<>();
    attrs.put(OtelObservabilityListener.ATTR_START_NANOS, System.nanoTime() - 1_000_000_000L);
    var ctx = mock(ChatModelErrorContext.class);
    when(ctx.attributes()).thenReturn(attrs);
    when(ctx.error()).thenReturn(new RuntimeException("error"));

    assertDoesNotThrow(() -> listener.onError(ctx));
    verify(sessionUpdater, never()).recordFailure(anyLong(), any(), anyLong());
  }

  @Test
  void onErrorShouldSanitizeLongMessages() {
    var longMsg = "A".repeat(300);
    var attrs = requestAttributes(11L, 1);
    var ctx = mock(ChatModelErrorContext.class);
    when(ctx.attributes()).thenReturn(attrs);
    when(ctx.error()).thenReturn(new RuntimeException(longMsg));

    listener.onError(ctx);

    var messageCaptor = ArgumentCaptor.forClass(String.class);
    verify(sessionUpdater).recordFailure(eq(11L), messageCaptor.capture(), anyLong());
    assertTrue(messageCaptor.getValue().length() <= 203);
    assertTrue(messageCaptor.getValue().endsWith("..."));
  }

  @Test
  void onErrorShouldHandleNullErrorMessage() {
    var error = new RuntimeException();
    var attrs = requestAttributes(12L, 1);
    var ctx = mock(ChatModelErrorContext.class);
    when(ctx.attributes()).thenReturn(attrs);
    when(ctx.error()).thenReturn(error);

    listener.onError(ctx);

    verify(sessionUpdater).recordFailure(eq(12L), eq("RuntimeException"), anyLong());
  }

  private Map<Object, Object> requestAttributes(long sessionId, int attempt) {
    return requestAttributes(listener, sessionId, attempt);
  }

  private Map<Object, Object> requestAttributes(
      OtelObservabilityListener target, long sessionId, int attempt) {
    var requestCtx = mock(ChatModelRequestContext.class);
    var attrs = new HashMap<>();
    when(requestCtx.attributes()).thenReturn(attrs);
    ReviewSessionContext.bind(sessionId, attempt);
    target.onRequest(requestCtx);
    ReviewSessionContext.clear();
    return attrs;
  }

  private ChatModelResponseContext responseContext(Map<Object, Object> attrs) {
    var ctx = mock(ChatModelResponseContext.class);
    when(ctx.attributes()).thenReturn(attrs);

    var response = mock(ChatResponse.class);
    when(ctx.chatResponse()).thenReturn(response);
    when(response.modelName()).thenReturn("deepseek-chat");

    var usage = mock(TokenUsage.class);
    when(response.tokenUsage()).thenReturn(usage);
    when(usage.inputTokenCount()).thenReturn(100);
    when(usage.outputTokenCount()).thenReturn(50);
    when(usage.totalTokenCount()).thenReturn(150);
    return ctx;
  }
}
