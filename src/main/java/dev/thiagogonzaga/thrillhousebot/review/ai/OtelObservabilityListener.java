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

import static io.opentelemetry.api.common.AttributeKey.stringKey;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig.AiPricingConfig.ModelPricing;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSessionUpdater;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.trace.Span;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class OtelObservabilityListener implements ChatModelListener {

  private static final Logger log = LoggerFactory.getLogger(OtelObservabilityListener.class);

  static final String ATTR_START_NANOS = "startNanos";
  static final String ATTR_SESSION_ID = "reviewSessionId";
  static final String ATTR_STREAM_ATTEMPT = "streamAttempt";

  private final ThrillhouseConfig config;
  private final ReviewSessionUpdater sessionUpdater;
  private final Vertx vertx;
  private final LongHistogram tokenHistogram;
  private final DoubleHistogram durationHistogram;
  private final DoubleCounter costCounter;

  @Inject
  public OtelObservabilityListener(
      OpenTelemetry otel,
      ThrillhouseConfig config,
      ReviewSessionUpdater sessionUpdater,
      Vertx vertx) {
    this.config = config;
    this.sessionUpdater = sessionUpdater;
    this.vertx = vertx;
    var meter = otel.getMeter("thrillhousebot");

    this.tokenHistogram =
        meter
            .histogramBuilder("gen_ai.client.token.usage")
            .setDescription("Token usage per LLM call")
            .ofLongs()
            .build();

    this.durationHistogram =
        meter
            .histogramBuilder("gen_ai.client.operation.duration")
            .setDescription("LLM call duration")
            .setUnit("s")
            .build();

    this.costCounter =
        meter
            .counterBuilder("thrillhouse.ai.cost.total")
            .setDescription("Total AI cost in USD")
            .setUnit("USD")
            .ofDoubles()
            .build();
  }

  @Override
  public void onRequest(ChatModelRequestContext ctx) {
    ctx.attributes().put(ATTR_START_NANOS, System.nanoTime());
    Long sessionId = ReviewSessionContext.currentSessionId();
    Integer attempt = ReviewSessionContext.currentAttempt();
    if (sessionId != null) {
      ctx.attributes().put(ATTR_SESSION_ID, sessionId);
    }
    if (attempt != null) {
      ctx.attributes().put(ATTR_STREAM_ATTEMPT, attempt);
    }
  }

  @Override
  public void onResponse(ChatModelResponseContext ctx) {
    var response = ctx.chatResponse();
    var usage = response.tokenUsage();
    var model = response.modelName();
    var startNanos = (long) ctx.attributes().get(ATTR_START_NANOS);
    var durationSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;

    var cost = 0.0;
    var pricing = config.ai().pricing().get(model);
    if (pricing != null) {
      cost =
          ModelPricing.cost(
              pricing.inputPer1k(),
              pricing.outputPer1k(),
              usage.inputTokenCount(),
              usage.outputTokenCount());
    }

    var attrs =
        Attributes.of(
            stringKey("gen_ai.provider.name"),
            "deepseek",
            stringKey("gen_ai.request.model"),
            model,
            stringKey("gen_ai.response.model"),
            model,
            stringKey("gen_ai.operation.name"),
            "chat");

    tokenHistogram.record(
        usage.inputTokenCount(), attrs.toBuilder().put("gen_ai.token.type", "input").build());
    tokenHistogram.record(
        usage.outputTokenCount(), attrs.toBuilder().put("gen_ai.token.type", "output").build());
    durationHistogram.record(durationSeconds, attrs);
    costCounter.add(cost, attrs);

    Span span = Span.current();
    span.setAttribute("gen_ai.usage.input_tokens", usage.inputTokenCount());
    span.setAttribute("gen_ai.usage.output_tokens", usage.outputTokenCount());
    span.setAttribute("gen_ai.usage.cost", cost);
    span.setAttribute("gen_ai.response.model", model);
    span.setAttribute("gen_ai.operation.name", "chat");

    if (shouldPersistSessionUsage(ctx.attributes())) {
      var sessionId = (Long) ctx.attributes().get(ATTR_SESSION_ID);
      var durationMs = (long) (durationSeconds * 1000);
      var inputTokens = usage.inputTokenCount();
      var outputTokens = usage.outputTokenCount();
      var totalTokens = usage.totalTokenCount();
      var recordedCost = cost;
      runOnWorker(
          sessionId,
          () ->
              sessionUpdater.recordModelUsage(
                  sessionId, model, inputTokens, outputTokens, recordedCost, durationMs),
          "persist session usage");
      if (log.isInfoEnabled()) {
        log.info(
            "Session persisted: {} tokens, ${}", totalTokens, String.format("%.6f", recordedCost));
      }
    }
  }

  @Override
  public void onError(ChatModelErrorContext ctx) {
    if (shouldPersistSessionUsage(ctx.attributes())) {
      var sessionId = (Long) ctx.attributes().get(ATTR_SESSION_ID);
      var startNanos = (long) ctx.attributes().get(ATTR_START_NANOS);
      var durationMs = (System.nanoTime() - startNanos) / 1_000_000;
      var error = sanitizeError(ctx.error());
      runOnWorker(
          sessionId,
          () -> sessionUpdater.recordFailure(sessionId, error, durationMs),
          "persist failure");
      if (log.isErrorEnabled()) {
        log.error("Session failed: {}", error);
      }
    }

    Span span = Span.current();
    span.setAttribute("error", true);
    var error = ctx.error();
    span.setAttribute("error.type", error != null ? error.getClass().getSimpleName() : "Unknown");
  }

  private boolean shouldPersistSessionUsage(java.util.Map<Object, Object> attributes) {
    var sessionId = (Long) attributes.get(ATTR_SESSION_ID);
    var attempt = attributes.get(ATTR_STREAM_ATTEMPT);
    if (sessionId == null || attempt == null) {
      return false;
    }
    var active = ReviewSessionContext.isActiveAttempt(sessionId, ((Number) attempt).intValue());
    if (!active) {
      log.debug(
          "Dropping stale stream callback for session {} attempt {} — usage not persisted",
          sessionId,
          attempt);
    }
    return active;
  }

  /** Runs blocking DB work off the Vert.x event loop (streaming callbacks are reactive). */
  private void runOnWorker(long sessionId, Runnable action, String description) {
    vertx
        .<Void>executeBlocking(
            () -> {
              action.run();
              return null;
            })
        .onFailure(e -> log.warn("Failed to {} for session {}", description, sessionId, e));
  }

  /** Sanitizes error messages — no stack traces exposed. */
  private String sanitizeError(Throwable error) {
    if (error == null) return "Unknown error";
    var msg = error.getMessage();
    if (msg != null && msg.length() > 200) {
      msg = msg.substring(0, 200) + "...";
    }
    return msg != null ? msg : error.getClass().getSimpleName();
  }
}
