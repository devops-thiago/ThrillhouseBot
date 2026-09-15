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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class OtelObservabilityListener implements ChatModelListener {

  private static final Logger log = LoggerFactory.getLogger(OtelObservabilityListener.class);

  static final String ATTR_START_NANOS = "startNanos";
  static final String ATTR_SESSION_ID = "reviewSessionId";
  static final String ATTR_STREAM_ATTEMPT = "streamAttempt";

  private static final String GEN_AI_PROVIDER_NAME = "gen_ai.provider.name";

  private final ThrillhouseConfig config;
  private final ReviewSessionUpdater sessionUpdater;
  private final Vertx vertx;
  private final ReviewTokenLedger tokenLedger;
  private final String providerName;
  private final LongHistogram tokenHistogram;
  private final DoubleHistogram durationHistogram;
  private final DoubleCounter costCounter;
  private final Set<String> modelsWarnedForMissingPricing = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean warnedForMissingUsage = new AtomicBoolean();

  @Inject
  public OtelObservabilityListener(
      OpenTelemetry otel,
      ThrillhouseConfig config,
      ReviewSessionUpdater sessionUpdater,
      Vertx vertx,
      ReviewTokenLedger tokenLedger) {
    this.config = config;
    this.sessionUpdater = sessionUpdater;
    this.vertx = vertx;
    this.tokenLedger = tokenLedger;
    this.providerName = resolveProviderName(config);
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

  /**
   * Resolves the {@code gen_ai.provider.name} label once at startup: an explicit configured name
   * wins; otherwise it is derived from the configured AI base URL.
   */
  private static String resolveProviderName(ThrillhouseConfig config) {
    var ai = config.ai();
    return ai.providerName()
        .map(String::trim)
        .filter(name -> !name.isEmpty())
        .orElseGet(() -> AiProviderResolver.fromBaseUrl(ai.baseUrl()));
  }

  @Override
  public void onRequest(ChatModelRequestContext ctx) {
    ctx.attributes().put(ATTR_START_NANOS, System.nanoTime());
    Long sessionId = ReviewSessionContext.currentSessionId();
    Long callId = ReviewSessionContext.currentCallId();
    if (sessionId != null) {
      ctx.attributes().put(ATTR_SESSION_ID, sessionId);
    }
    if (callId != null) {
      ctx.attributes().put(ATTR_STREAM_ATTEMPT, callId);
    }
  }

  @Override
  public void onResponse(ChatModelResponseContext ctx) {
    // A stream can complete without its usage chunk even with include_usage requested, and a usage
    // object can carry one side only (#857). A missing count stays null here: the ledger already
    // counts it as 0, the session row adds 0, and the metrics leave that sample out.
    var usage = ctx.chatResponse().tokenUsage();
    var inputTokens = usage == null ? null : usage.inputTokenCount();
    var outputTokens = usage == null ? null : usage.outputTokenCount();
    var model = modelNameOf(ctx);
    if (inputTokens == null || outputTokens == null) {
      warnOnceAboutMissingUsage(model);
    }
    var startNanos = (long) ctx.attributes().get(ATTR_START_NANOS);
    var durationSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;

    // Spend-ceiling accounting (#499). Deliberately NOT behind shouldPersistSessionUsage: a call
    // whose future already timed out client-side was still billed by the provider, so the ceiling
    // must count it even though the dashboard drops it as stale. Correlation rides the same
    // ATTR_SESSION_ID stamped in onRequest, which covers every model the extension builds — the
    // concise named model included — so summary/retry calls are all metered.
    var ledgerSessionId = (Long) ctx.attributes().get(ATTR_SESSION_ID);
    if (ledgerSessionId != null) {
      tokenLedger.recordUsage(ledgerSessionId, inputTokens, outputTokens);
    }

    var pricing = pricingFor(model);
    var pricingMissing = pricing == null;
    var cost =
        pricingMissing
            ? 0.0
            : ModelPricing.cost(
                pricing.inputPer1k(),
                pricing.outputPer1k(),
                countOf(inputTokens),
                countOf(outputTokens));

    var attrs =
        Attributes.of(
            stringKey(GEN_AI_PROVIDER_NAME),
            providerName,
            stringKey("gen_ai.request.model"),
            model,
            stringKey("gen_ai.response.model"),
            model,
            stringKey("gen_ai.operation.name"),
            "chat");

    Span span = Span.current();
    recordTokenCount(span, "input", inputTokens, attrs);
    recordTokenCount(span, "output", outputTokens, attrs);
    durationHistogram.record(durationSeconds, attrs);
    costCounter.add(cost, attrs);

    span.setAttribute(GEN_AI_PROVIDER_NAME, providerName);
    span.setAttribute("gen_ai.usage.cost", cost);
    span.setAttribute("gen_ai.response.model", model);
    span.setAttribute("gen_ai.operation.name", "chat");

    if (shouldPersistSessionUsage(ctx.attributes())) {
      var sessionId = (Long) ctx.attributes().get(ATTR_SESSION_ID);
      var durationMs = (long) (durationSeconds * 1000);
      var persistedInput = countOf(inputTokens);
      var persistedOutput = countOf(outputTokens);
      var recordedCost = cost;
      var recordedPricingMissing = pricingMissing;
      runOnWorker(
          sessionId,
          () ->
              sessionUpdater.recordModelUsage(
                  sessionId,
                  model,
                  persistedInput,
                  persistedOutput,
                  recordedCost,
                  recordedPricingMissing,
                  durationMs),
          "persist session usage");
      if (log.isInfoEnabled()) {
        var costLabel =
            recordedPricingMissing
                ? "cost unknown (no pricing for model)"
                : "$" + String.format("%.6f", recordedCost);
        log.info("Session persisted: {} tokens, {}", persistedInput + persistedOutput, costLabel);
      }
    }
  }

  /**
   * The model the response names, else the one the request asked for. A stream whose chunks never
   * carry {@code model} completes with a null name, and a null would overwrite the session's model
   * and could not key the pricing lookup or the missing-pricing warning (#857).
   */
  private static String modelNameOf(ChatModelResponseContext ctx) {
    var reported = ctx.chatResponse().modelName();
    return reported != null ? reported : ctx.chatRequest().modelName();
  }

  /**
   * The configured pricing for the model, or null when there is none. A call that names no model
   * anywhere is priced as missing without the warning, which has no pricing key to point the
   * operator at.
   */
  private ModelPricing pricingFor(String model) {
    if (model == null) {
      return null;
    }
    var pricing = config.ai().pricing().get(model);
    if (pricing == null) {
      warnOnceAboutMissingPricing(model);
    }
    return pricing;
  }

  /**
   * Records one side of the call's usage on the token histogram and the span. A side the provider
   * did not report is left out rather than recorded as 0, which would pull the token distribution
   * toward calls that never happened that way (#857).
   */
  private void recordTokenCount(Span span, String type, Integer count, Attributes attrs) {
    if (count == null) {
      return;
    }
    tokenHistogram.record(count, attrs.toBuilder().put("gen_ai.token.type", type).build());
    span.setAttribute("gen_ai.usage." + type + "_tokens", count);
  }

  private static int countOf(Integer count) {
    return count == null ? 0 : count;
  }

  /**
   * Warns once for the process lifetime that the provider completed a call without reporting its
   * token usage (#857). The call is still recorded, but its missing counts reach the spend ceiling
   * and the dashboard as 0, so the operator needs to know the ceiling can be exceeded unseen; a
   * provider that does this does it routinely, and a per-call warning would say nothing new.
   */
  private void warnOnceAboutMissingUsage(String model) {
    if (warnedForMissingUsage.compareAndSet(false, true)) {
      log.warn(
          "The AI provider completed a call to model '{}' without reporting its token usage. The"
              + " missing counts are recorded as 0, so thrillhousebot.review.max-tokens-per-review"
              + " and the dashboard under-count such calls; later ones are not logged again.",
          model);
    }
  }

  /**
   * Warns once per model name for the process lifetime — a missing pricing entry repeats on every
   * call of every review, and a per-request warning would drown the log while saying nothing new.
   * Token counts are unaffected; only the cost is recorded as unknown.
   */
  private void warnOnceAboutMissingPricing(String model) {
    if (modelsWarnedForMissingPricing.add(model)) {
      log.warn(
          "No pricing configured for model '{}' — its cost is recorded as $0 and flagged, so the"
              + " dashboard under-reports spend. Add thrillhousebot.ai.pricing.\"{}\".input-per-1k"
              + " and .output-per-1k; existing sessions are backfilled on the next restart.",
          model,
          model);
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
    span.setAttribute(GEN_AI_PROVIDER_NAME, providerName);
    span.setAttribute("error", true);
    var error = ctx.error();
    span.setAttribute("error.type", error != null ? error.getClass().getSimpleName() : "Unknown");
  }

  private boolean shouldPersistSessionUsage(java.util.Map<Object, Object> attributes) {
    var sessionId = (Long) attributes.get(ATTR_SESSION_ID);
    var callIdAttr = attributes.get(ATTR_STREAM_ATTEMPT);
    if (sessionId == null || callIdAttr == null) {
      return false;
    }
    var callId = ((Number) callIdAttr).longValue();
    var active = ReviewSessionContext.isActiveCall(sessionId, callId);
    if (!active) {
      log.debug(
          "Dropping stale stream callback for session {} call {} — usage not persisted",
          sessionId,
          callId);
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
