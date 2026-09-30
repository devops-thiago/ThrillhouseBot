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

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.service.TokenStream;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig.AiPricingConfig.ReasoningConfig;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.dashboard.SessionEventBroadcaster;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiResponses.ModelLane;
import io.quarkiverse.langchain4j.runtime.aiservice.QuarkusAiServiceTokenStream;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Runs PR reviews with streaming output to the dashboard and automatic retries for transient model
 * failures.
 */
@ApplicationScoped
public class AiReviewService {

  private static final int STREAM_TAIL_CHARS = 2_000;
  private static final long STREAM_FLUSH_INTERVAL_MS = 250;
  private static final int STREAM_FLUSH_MIN_CHARS = 512;

  /** The ceiling of the retry schedule, and the whole wait after a provider throttle (#838). */
  private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

  private static final int TOO_MANY_REQUESTS = 429;

  /**
   * How many attempts of one logical call may end at the streaming deadline (#862): the first and
   * one repeat. Every other transient failure keeps the whole {@code max-ai-retries} budget. A
   * timed-out attempt is the most expensive failure the loop can have — it spends {@code
   * ai-timeout-seconds} in full, 15 minutes in production, and the review holds its pull request's
   * place in the dispatcher throughout — and it is the failure least likely to go away on a repeat,
   * because a prompt the model did not finish in 15 minutes is the same prompt on the next attempt.
   * Production saw one 503-file pull request spend 20 such attempts in a day. The repeat is kept
   * because a first token that never arrived can also be the provider queueing the request, which
   * the next attempt need not repeat; at the shipped five retries the ceiling was 75 minutes of
   * waiting per call, and it is 30 now.
   */
  private static final int MAX_TIMED_OUT_ATTEMPTS = 2;

  /** Wording of Ollama cloud's refusal when no concurrent request slot freed in time (#838). */
  private static final String CONCURRENT_SLOT_REFUSAL = "concurrent request slot";

  /** Dashboard reason for the one repeat with reasoning disabled (#839). */
  static final String REASONING_STEP_DOWN_REASON =
      "Model spent its output allowance reasoning and returned no content; repeating the call"
          + " with reasoning disabled";

  private final PrReviewer prReviewer;
  private final PrSummarizer prSummarizer;
  private final ReviewResponseParser parser;
  private final ThrillhouseConfig config;

  private final SessionEventBroadcaster broadcaster;

  private final ReviewTokenLedger tokenLedger;

  private final ModelCallGate callGate;

  private final ResponseCaps responseCaps;

  @Inject
  public AiReviewService(
      PrReviewer prReviewer,
      PrSummarizer prSummarizer,
      ReviewResponseParser parser,
      ThrillhouseConfig config,
      SessionEventBroadcaster broadcaster,
      ReviewTokenLedger tokenLedger,
      ModelCallGate callGate,
      ResponseCaps responseCaps) {
    this.prReviewer = prReviewer;
    this.prSummarizer = prSummarizer;
    this.parser = parser;
    this.config = config;
    this.broadcaster = broadcaster;
    this.tokenLedger = tokenLedger;
    this.callGate = callGate;
    this.responseCaps = responseCaps;
  }

  /** Single-call review (normal-size PRs): streams tokens to the dashboard as they arrive. */
  public ReviewResponse review(ReviewSession session, PromptInputs inputs) {
    return runWithRetries(session, () -> reviewStream(inputs), true, ModelLane.ACTIVE);
  }

  /**
   * Reviews one batch of a large, multi-call PR. Runs blocking on the caller's thread (map-reduce
   * fans batches out on virtual threads) — no per-token dashboard stream (the live feed would
   * interleave several batches) — and emits a {@code batch index/count} progress event instead. The
   * same retry/timeout scaffolding applies; only the findings of the returned response are used by
   * the caller (PR-level summary comes from the final summary call).
   */
  public ReviewResponse reviewBatch(
      ReviewSession session, PromptInputs inputs, int batchIndex, int batchCount) {
    broadcaster.broadcast(
        SessionEventBroadcaster.SessionEvent.batch(session, batchIndex, batchCount));
    return runWithRetries(session, () -> reviewStream(inputs), false, ModelLane.ACTIVE);
  }

  /**
   * The summary call every review ends with, on both lanes (#664): rolls the verified findings up
   * into the PR-level summary object. Blocking, no token stream; only the returned response's
   * summary is used (its findings list is empty, and previous-finding statuses come from the review
   * calls, which saw the diff). Runs on the {@code concise} named model — the response is one
   * fixed-shape object, so it carries the concise cap rather than the batch review's response
   * allowance. That binding is declared here as the call's {@link ModelLane} and travels to the
   * truncation site, so a cut summary states {@code REVIEW_CONCISE_MAX_OUTPUT_TOKENS} from the
   * outset instead of being raised with the active model's wording and re-marked afterwards (#581).
   * The same lane selects {@link ReviewResponseParser#parseSummary}, which reads a response that
   * omits the findings node or the summary object around its fields (#850).
   */
  public ReviewResponse summarize(ReviewSession session, SummaryInputs inputs) {
    return runWithRetries(
        session,
        () ->
            prSummarizer.summarizeStream(
                inputs.prContext(),
                inputs.findings(),
                inputs.changedFiles(),
                inputs.previousFindings(),
                inputs.repoInstructions()),
        false,
        ModelLane.CONCISE);
  }

  private TokenStream reviewStream(PromptInputs inputs) {
    return prReviewer.reviewStream(
        inputs.diff(),
        inputs.prContext(),
        inputs.baseComparison(),
        inputs.projectStack(),
        inputs.relatedTests(),
        inputs.previousFindings(),
        inputs.repoInstructions(),
        inputs.reviewSystemPrompt());
  }

  /**
   * Runs one logical AI call: the transient-failure retry loop at the configured effort, and — once
   * — the same loop again with reasoning disabled when the first pass ends in a length stop that
   * produced no content at all (#839).
   *
   * <p>A length stop comes in two shapes that the retry loop must tell apart. With content, the
   * answer itself outgrew the cap: the identical call would be cut identically, so it is not
   * retried and the salvage path keeps what was produced. With no content, the model never began
   * the answer — the reasoning tail spent the whole output allowance, which is a variable-length
   * thing that the identical call may or may not repeat, and that a call without reasoning cannot.
   * Production saw three reviews in a row fail that way at {@code max}, each billed for the full
   * cap and delivering nothing. The repeat goes straight to {@code none} rather than one tier down:
   * on the provider where this was measured the tiers barely move the reasoning length, so a tier
   * down burns the cap a second time. The step-down is per call — the configured effort is not
   * touched, the next call sends it again — and it is noted on the review's ledger entry so the
   * posted summary discloses that the review ran with reasoning off. A repeat that also stops at
   * the cap propagates as before, so the cap is hit at most twice per logical call; a transient
   * failure on either pass keeps the ordinary {@code max-ai-retries} budget, because a provider
   * error on the repeat is the same kind of failure it is on any other call and is retried on the
   * same terms.
   *
   * <p>The premise holds only where the provider keeps reasoning in its own channel. A model that
   * writes its deliberation into the response once reasoning is disabled makes the repeat the same
   * call at the same price (#893), so the repeat is watched by an {@link InlinedDeliberationGuard}:
   * content that runs its bound with no answer begun stops the repeat there, and the call fails
   * with the first call's truncation, whose report states what the repeat showed. That is noted on
   * the ledger apart from a repeat that ran, so the posted summary says which one happened.
   *
   * <p>The repeat's calls are bound as reasoning-disabled on the thread that starts them (see
   * {@link #streamOnce}); {@link ReasoningStepDownStreamingModel} reads that binding and sends
   * {@code reasoning_effort=none} on those calls alone.
   *
   * <p>The timed-out attempts are counted here rather than inside the loop, so the bound of {@link
   * #MAX_TIMED_OUT_ATTEMPTS} is on the logical call and both passes share it (#862). A call that
   * already waited out one deadline before its length stop has one attempt left to wait out
   * another, whether or not reasoning is still on for it.
   */
  private ReviewResponse runWithRetries(
      ReviewSession session,
      Supplier<TokenStream> streamFactory,
      boolean broadcastTokens,
      ModelLane lane) {
    var timeouts = new TimeoutBudget();
    try {
      return attemptWithRetries(session, streamFactory, broadcastTokens, lane, false, timeouts);
    } catch (AiResponseTruncatedException e) {
      var effort = reasoningEffortToStepDownFrom(e, lane);
      if (effort.isEmpty()) {
        throw e;
      }
      Log.warnf(
          "AI review for session %d stopped at its response-length cap with no content at"
              + " reasoning_effort=%s (%s): the reasoning tail spent the whole output allowance,"
              + " so the call is repeated once with reasoning disabled",
          session.id, effort.get(), describeUsage(e));
      tokenLedger.recordReasoningStepDown(ReviewTokenLedger.keyFor(session));
      broadcaster.broadcast(
          SessionEventBroadcaster.SessionEvent.retry(
              session, 1, config.review().maxAiRetries(), REASONING_STEP_DOWN_REASON));
      return repeatWithReasoningDisabled(
          session, streamFactory, broadcastTokens, lane, timeouts, e);
    }
  }

  /**
   * The step-down's one repeat. A repeat its {@link InlinedDeliberationGuard} stopped fails the
   * call with {@code firstStop} — the call that was billed at the cap — carrying what the repeat
   * showed, so every surface that reports the truncation states both (#893). Nothing else about the
   * repeat's outcome changes: an answer is the call's, and any other failure propagates as before.
   */
  private ReviewResponse repeatWithReasoningDisabled(
      ReviewSession session,
      Supplier<TokenStream> streamFactory,
      boolean broadcastTokens,
      ModelLane lane,
      TimeoutBudget timeouts,
      AiResponseTruncatedException firstStop) {
    try {
      return attemptWithRetries(session, streamFactory, broadcastTokens, lane, true, timeouts);
    } catch (InlinedDeliberationGuard.Stopped stopped) {
      tokenLedger.recordReasoningRepeatStopped(ReviewTokenLedger.keyFor(session));
      var report = firstStop.report().withInlinedDeliberation(stopped.chars());
      Log.warnf(
          "AI review for session %d: the repeat with reasoning disabled wrote %d characters of"
              + " deliberation into its response with no answer begun, so it was stopped instead"
              + " of billed the cap a second time; this model does not move its reasoning out of"
              + " the output allowance when reasoning is disabled",
          session.id, stopped.chars());
      throw new AiResponseTruncatedException(
          "Model stopped on a length limit (finish_reason=length) with no content, and the"
              + " repeat with reasoning disabled wrote its deliberation into the response"
              + " instead. "
              + report.describe(TruncationReport.PLAIN),
          firstStop.partialBody(),
          report);
    }
  }

  /**
   * The reasoning effort a no-content length stop ran at, when there is one to step down from.
   * Empty for a stop with content (the answer was too long — the salvage path's case, never
   * retried), when reasoning is off in config (nothing is sent, so there is nothing to disable),
   * and when the lane already runs at {@code none} (the repeat would be the identical call). The
   * lane matters because the concise lane resolves its own effort (#567).
   */
  private Optional<String> reasoningEffortToStepDownFrom(
      AiResponseTruncatedException e, ModelLane lane) {
    if (!"".equals(e.partialBody())) {
      return Optional.empty();
    }
    return ChatModelCustomizers.reasoningEffort(config, lane == ModelLane.CONCISE)
        .filter(effort -> !ReasoningConfig.EFFORT_NONE.equals(effort));
  }

  /**
   * The figures the step-down log states — the licensed cap and what the provider billed, never the
   * model's text. The same figures the truncation's own message carries (#895).
   */
  private static String describeUsage(AiResponseTruncatedException e) {
    return e.report().figures(TruncationReport.PLAIN);
  }

  private ReviewResponse attemptWithRetries(
      ReviewSession session,
      Supplier<TokenStream> streamFactory,
      boolean broadcastTokens,
      ModelLane lane,
      boolean reasoningDisabled,
      TimeoutBudget timeouts) {
    var maxAttempts = config.review().maxAiRetries();
    RuntimeException lastFailure = null;

    for (var attempt = 1; attempt <= maxAttempts; attempt++) {
      // Spend-ceiling gate, before EVERY attempt: a retry is a fresh billed call, and the previous
      // attempt's usage is already in the ledger by the time we get here (the observability
      // listener's onResponse runs before the completion handler resolves the stream future — see
      // StreamingChatModelListenerOrderingTest). Deliberately outside the try below, so the typed
      // refusal propagates instead of being mistaken for one more transient failure to retry.
      tokenLedger.ensureCallAllowed(ReviewTokenLedger.keyFor(session));
      if (attempt > 1) {
        broadcaster.broadcast(
            SessionEventBroadcaster.SessionEvent.retry(
                session, attempt, maxAttempts, retryFailureReason(lastFailure)));
        sleep(retryDelay(session, attempt, maxAttempts, lastFailure));
      }

      try {
        return streamOnce(
            session, streamFactory, attempt, broadcastTokens, lane, reasoningDisabled);
      } catch (AiResponseTruncatedException e) {
        // Deterministic: the next attempt sends the identical prompt against the identical cap and
        // is cut at the identical point. Retrying only bills the same failure again. The one
        // repeat that changes the call — reasoning off after a no-content stop (#839) — is decided
        // by runWithRetries around this loop, not here.
        Log.warnf(
            "AI review attempt %d/%d for session %d hit the response-length cap; not retrying"
                + " (identical call would truncate identically)",
            attempt, maxAttempts, session.id);
        throw e;
      } catch (InlinedDeliberationGuard.Stopped e) {
        // Deterministic too (#893): the model writes its deliberation into the response with
        // reasoning off, so another attempt would bill the same prose again.
        Log.warnf(
            "AI review attempt %d/%d for session %d was stopped with no answer begun; not"
                + " retrying (the identical call would deliberate identically)",
            attempt, maxAttempts, session.id);
        throw e;
      } catch (RuntimeException e) {
        var rejection = AiContextWindowExceededException.classify(e);
        if (rejection.isPresent()) {
          // Deterministic like a truncation: the identical request against the identical window
          // is rejected identically, and every attempt here is a fresh full-price call (#622).
          Log.warnf(
              "AI review attempt %d/%d for session %d was rejected for exceeding the model's"
                  + " context window; not retrying (an identical request would be rejected"
                  + " identically)",
              attempt, maxAttempts, session.id);
          throw rejection.get();
        }
        lastFailure = e;
        Log.warnf(
            e, "AI review attempt %d/%d failed for session %d", attempt, maxAttempts, session.id);
        broadcaster.broadcast(
            SessionEventBroadcaster.SessionEvent.streamFailed(
                session, attempt, maxAttempts, sanitize(e)));
        failIfTimeoutBudgetSpent(session, attempt, maxAttempts, e, timeouts);
      }
    }

    throw new AiReviewException(
        "AI review failed after " + maxAttempts + " attempts", maxAttempts, lastFailure);
  }

  /**
   * Ends the call when this failure is the streaming deadline and the call has no timed-out attempt
   * left (#862), after the attempt has been logged and broadcast like any other transient failure.
   * Returns for every other failure, and for a timeout the call may still repeat.
   *
   * <p>Only the client-side deadline counts. A call that found no free model call slot (#838) was
   * never sent, and a timeout the provider itself reports arrives as a stream error; both are
   * ordinary transient failures and keep the ordinary budget.
   *
   * @throws AiReviewTimeoutException naming the attempts made and the wall clock they spent, when
   *     the bound is reached
   */
  private static void failIfTimeoutBudgetSpent(
      ReviewSession session,
      int attempt,
      int maxAttempts,
      RuntimeException failure,
      TimeoutBudget timeouts) {
    if (!(failure instanceof AiReviewTimeoutException timeout)
        || !timeouts.spend(timeout.waited())) {
      return;
    }
    Log.warnf(
        "AI review attempt %d/%d for session %d timed out after %s; that is the last of the %d"
            + " timed-out attempts one call may have (%s of waiting in all), so the call fails"
            + " here instead of spending its remaining attempts on a request that already did not"
            + " finish inside the deadline",
        attempt,
        maxAttempts,
        session.id,
        timeout.waited(),
        MAX_TIMED_OUT_ATTEMPTS,
        timeouts.waited());
    throw new AiReviewTimeoutException(
        "AI review failed after "
            + attempt
            + " attempts, "
            + timeouts.timedOut()
            + " of which timed out",
        attempt,
        timeouts.waited(),
        timeout);
  }

  /**
   * The timed-out attempts of one logical call and the wall clock they spent (#862). Not thread
   * safe by design: one logical call runs its attempts one after another on a single thread, and
   * parallel batches each run their own call with a budget of their own.
   */
  private static final class TimeoutBudget {

    private int timedOut;
    private Duration waited = Duration.ZERO;

    /** Records a timed-out attempt and reports whether the call has none left. */
    boolean spend(Duration wait) {
      timedOut++;
      waited = waited.plus(wait);
      return timedOut >= MAX_TIMED_OUT_ATTEMPTS;
    }

    int timedOut() {
      return timedOut;
    }

    Duration waited() {
      return waited;
    }
  }

  /**
   * The prompt sections sent to the model for one review, pre-escaped for templating. The
   * instructions section arrives pre-rendered with its header and source attribution.
   *
   * <p>{@code repoInstructions} is the review call's trailing guidance; {@code summaryInstructions}
   * is the summary call's (#664) — the label and diagram requests, which gate summary fields the
   * review call no longer writes, plus the repository's own instructions. The two are assembled
   * together so each call carries only the guidance its response acts on.
   *
   * <p>{@code reviewSystemPrompt} is the system prompt the review call sends (#665): {@link
   * PrReviewPrompts#SYSTEM} with dimension routing off, otherwise the routed prompt for the call's
   * files. The assembler sets the whole pull request's, which is the largest any batch of it can
   * get and what the budget planner sizes the shared overhead from; the pipeline swaps in each
   * batch's own. {@code null} reads as {@link PrReviewPrompts#SYSTEM}.
   *
   * <p>{@code linkedIssues} is the unfenced linked-issue section (#58) both calls already carry
   * inside their guidance, kept apart as well so the summary's {@code Linked issue #N} entries can
   * be checked against the issues actually linked (#923). {@code null} reads as {@code ""}.
   */
  public record PromptInputs(
      String diff,
      String prContext,
      String baseComparison,
      String projectStack,
      String relatedTests,
      String previousFindings,
      String repoInstructions,
      String summaryInstructions,
      String reviewSystemPrompt,
      String linkedIssues) {

    public PromptInputs {
      reviewSystemPrompt = reviewSystemPrompt == null ? PrReviewPrompts.SYSTEM : reviewSystemPrompt;
      linkedIssues = linkedIssues == null ? "" : linkedIssues;
    }

    /** Inputs that carry no linked-issue section of their own. */
    public PromptInputs(
        String diff,
        String prContext,
        String baseComparison,
        String projectStack,
        String relatedTests,
        String previousFindings,
        String repoInstructions,
        String summaryInstructions,
        String reviewSystemPrompt) {
      this(
          diff,
          prContext,
          baseComparison,
          projectStack,
          relatedTests,
          previousFindings,
          repoInstructions,
          summaryInstructions,
          reviewSystemPrompt,
          "");
    }

    /** These inputs with the summary call's guidance replaced; every other slot is kept. */
    public PromptInputs withSummaryInstructions(String replacement) {
      return new PromptInputs(
          diff,
          prContext,
          baseComparison,
          projectStack,
          relatedTests,
          previousFindings,
          repoInstructions,
          replacement,
          reviewSystemPrompt,
          linkedIssues);
    }

    /** Inputs whose review call carries the monolithic system prompt, every dimension included. */
    public PromptInputs(
        String diff,
        String prContext,
        String baseComparison,
        String projectStack,
        String relatedTests,
        String previousFindings,
        String repoInstructions,
        String summaryInstructions) {
      this(
          diff,
          prContext,
          baseComparison,
          projectStack,
          relatedTests,
          previousFindings,
          repoInstructions,
          summaryInstructions,
          PrReviewPrompts.SYSTEM);
    }

    /**
     * Inputs with no summary-call guidance of their own: no label section, no diagram request, no
     * repository instructions reach the summary call. For callers that only drive the review call.
     */
    public PromptInputs(
        String diff,
        String prContext,
        String baseComparison,
        String projectStack,
        String relatedTests,
        String previousFindings,
        String repoInstructions) {
      this(
          diff,
          prContext,
          baseComparison,
          projectStack,
          relatedTests,
          previousFindings,
          repoInstructions,
          "");
    }
  }

  /**
   * The prompt sections for the final summary call, pre-escaped for templating: the
   * already-computed findings to roll up, the changed-files overview, and the PR-level context.
   */
  public record SummaryInputs(
      String prContext,
      String findings,
      String changedFiles,
      String previousFindings,
      String repoInstructions) {}

  /**
   * One attempt, holding a {@link ModelCallGate} slot from before the stream opens until the
   * attempt ends (#838). The wait comes before the session binding and the stream factory, so a
   * call that finds no slot is never sent and binds nothing; that failure is an ordinary transient
   * one and is retried on the usual terms.
   *
   * <p>The slot is returned when the attempt returns, not when the provider's stream reports its
   * end. On success and on a stream error those are the same moment, because the attempt waits for
   * the handler. On a timeout or an interrupt the attempt cancels the stream first, which closes
   * the connection and frees the provider's slot with it, and only then returns the local one. A
   * stream with no handle to cancel (a runtime other than Quarkus', or one that never started
   * streaming) can run on after the attempt until the HTTP client's {@code AI_TIMEOUT} ends it, so
   * the ceiling can be overshot by that stream for that long. Holding the slot until the handler
   * fires instead would lose it for good whenever a cancelled stream never reports back, and at a
   * ceiling of 1 that stops every model call in the process; a bounded overshoot is the lesser
   * failure.
   */
  private ReviewResponse streamOnce(
      ReviewSession session,
      Supplier<TokenStream> streamFactory,
      int attempt,
      boolean broadcastTokens,
      ModelLane lane,
      boolean reasoningDisabled) {
    try (var _ = callGate.acquire("AI review call for session " + session.id)) {
      return streamInSlot(
          session, streamFactory, attempt, broadcastTokens, lane, reasoningDisabled);
    }
  }

  private ReviewResponse streamInSlot(
      ReviewSession session,
      Supplier<TokenStream> streamFactory,
      int attempt,
      boolean broadcastTokens,
      ModelLane lane,
      boolean reasoningDisabled) {
    var result = new CompletableFuture<ReviewResponse>();
    var buffer = new StreamBuffer();
    var chunkCount = new AtomicInteger();
    var lastFlushNanos = new AtomicLong(System.nanoTime());
    var cancelled = new AtomicBoolean(false);
    // Only the step-down's repeat is watched for deliberation written into the response (#893);
    // a call at the configured effort streams exactly as before.
    var deliberationGuard = reasoningDisabled ? new InlinedDeliberationGuard() : null;

    Runnable flushStream =
        broadcastTokens
            ? () -> flushPendingStream(session, buffer, chunkCount, attempt, lastFlushNanos)
            : () -> {};

    // The binding travels on this thread into the model's chat call: the observability listener
    // reads the session off it, and the step-down model reads whether this call goes out with
    // reasoning disabled (#839). Both run before the stream returns, on this same thread.
    ReviewSessionContext.bind(session.id, attempt, reasoningDisabled);
    var callId = ReviewSessionContext.currentCallId();
    var deadline = streamTimeout();
    TokenStream stream = null;
    try {
      stream = streamFactory.get();

      stream
          .onPartialResponse(
              token -> {
                handlePartialToken(token, buffer, flushStream, cancelled, lastFlushNanos);
                stopOnInlinedDeliberation(deliberationGuard, buffer, result, cancelled);
              })
          .onCompleteResponse(
              response ->
                  handleCompleteResponse(response, result, buffer, flushStream, cancelled, lane))
          // langchain4j requires exactly one of onError/ignoreErrors; start() rejects both.
          .onError(error -> handleStreamError(error, result, flushStream, cancelled))
          .start();

      return result.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      cancelled.set(true);
      cancelStream(stream, session.id, attempt);
      flushStream.run();
      // Typed apart from the other transient failures: the retry loop bounds how many attempts of
      // one call may spend the deadline, and it needs the wait this one spent to say so (#862).
      throw new AiReviewTimeoutException("AI review timed out after " + deadline, 1, deadline, e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof InlinedDeliberationGuard.Stopped) {
        // The guard ended the attempt, not the provider: close the connection so the model stops
        // generating billed deliberation, exactly as an abandoned stream is closed on a timeout.
        cancelStream(stream, session.id, attempt);
        flushStream.run();
      }
      throw asAiReviewException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      cancelled.set(true);
      cancelStream(stream, session.id, attempt);
      throw new AiReviewException("AI review interrupted", 1, e);
    } finally {
      cancelled.set(true);
      // Invalidate this call only — parallel map-reduce batches share the session id and must
      // keep their own in-flight callIds active. Safe on success: langchain4j notifies
      // ChatModelListener.onResponse before completing the handler that resolves the future.
      ReviewSessionContext.invalidate(session.id, callId);
      ReviewSessionContext.clear();
    }
  }

  private static void handlePartialToken(
      String token,
      StreamBuffer buffer,
      Runnable flushStream,
      AtomicBoolean cancelled,
      AtomicLong lastFlushNanos) {
    if (cancelled.get()) {
      return;
    }
    var pendingChars = buffer.append(token);
    if (pendingChars >= STREAM_FLUSH_MIN_CHARS) {
      flushStream.run();
      return;
    }
    var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastFlushNanos.get());
    if (elapsedMs >= STREAM_FLUSH_INTERVAL_MS) {
      flushStream.run();
    }
  }

  /**
   * Stops the step-down's repeat once its {@link InlinedDeliberationGuard} finds deliberation with
   * no answer begun (#893). A no-op for every other call ({@code guard} is null) and once the guard
   * has decided. An attempt that already ended adds nothing to the buffer, and completing its
   * settled result again changes nothing. The stream itself is cancelled by the waiting thread,
   * which holds its handle.
   */
  private static void stopOnInlinedDeliberation(
      InlinedDeliberationGuard guard,
      StreamBuffer buffer,
      CompletableFuture<ReviewResponse> result,
      AtomicBoolean cancelled) {
    // Checked before the buffer is read: the guard decides once, and a snapshot of the whole
    // buffer on every later token would cost a copy per token for the rest of a long response.
    if (guard == null || guard.decided()) {
      return;
    }
    var stoppedAt = guard.check(buffer.textOnceAtLeast(InlinedDeliberationGuard.BOUND_CHARS));
    if (stoppedAt.isPresent()) {
      cancelled.set(true);
      result.completeExceptionally(new InlinedDeliberationGuard.Stopped(stoppedAt.getAsInt()));
    }
  }

  private void handleCompleteResponse(
      ChatResponse response,
      CompletableFuture<ReviewResponse> result,
      StreamBuffer buffer,
      Runnable flushStream,
      AtomicBoolean cancelled,
      ModelLane lane) {
    if (cancelled.get()) {
      return;
    }
    try {
      flushStream.run();
      // ChatResponse guarantees a non-null aiMessage; its text may still be null.
      var text = buffer.textOrFallback(response.aiMessage().text());
      // A length stop means the body is cut mid-structure. Naming it here is what keeps it out of
      // the transient-retry path below — parsing it first would surface only "not valid review
      // JSON", which is indistinguishable from a malformed response and gets retried.
      if (response.finishReason() == FinishReason.LENGTH) {
        // The buffered partial text travels with the exception: it is well-formed up to the cut,
        // and the disclose step can salvage its complete leading elements (#500) instead of
        // discarding output that was already paid for. The remedy wording and the concise flag
        // both come from the call's lane (#581) — this site used to state the active model's knob
        // unconditionally and let the summary lane re-mark the flag afterwards, which left a
        // concise-model truncation naming a cap that does not bound it.
        // The provider's usage rides along too: a stop after 0 characters is the reasoning tail
        // spending the whole allowance, and the step-down that repeats it logs the counts (#839).
        // It is stated against the cap the request licensed, so the message advises raising the
        // cap only when the billed completion reached it (#895). The character count stays only
        // as the size of what the salvage step has to work with — never the sole figure.
        result.completeExceptionally(
            AiResponses.truncation(
                "Model stopped on a length limit (finish_reason=length), so the response is"
                    + " incomplete; "
                    + text.length()
                    + " characters arrived before the cut.",
                text,
                response.tokenUsage(),
                responseCaps.forLane(lane)));
        return;
      }
      // The summary is the one call on the concise lane that streams through here (the verifier
      // and reply calls block and read their own shapes), and it is read with the summary lane's
      // tolerances; every batch keeps the #805 refusal of a root with no findings node (#850).
      result.complete(lane == ModelLane.CONCISE ? parser.parseSummary(text) : parser.parse(text));
    } catch (RuntimeException e) {
      result.completeExceptionally(e);
    }
  }

  private static void handleStreamError(
      Throwable error,
      CompletableFuture<ReviewResponse> result,
      Runnable flushStream,
      AtomicBoolean cancelled) {
    if (cancelled.get()) {
      return;
    }
    flushStream.run();
    result.completeExceptionally(error);
  }

  /** Visible for tests. */
  static AiReviewException asAiReviewException(ExecutionException e) {
    Throwable cause = e.getCause() != null ? e.getCause() : e;
    if (cause instanceof AiReviewException aiReviewException) {
      return aiReviewException;
    }
    if (cause instanceof RuntimeException runtimeException) {
      return new AiReviewException(
          runtimeException.getMessage() != null
              ? runtimeException.getMessage()
              : "AI review failed",
          1,
          runtimeException);
    }
    return new AiReviewException("AI review failed", 1, cause);
  }

  private void flushPendingStream(
      ReviewSession session,
      StreamBuffer buffer,
      AtomicInteger chunkCount,
      int attempt,
      AtomicLong lastFlushNanos) {
    var pending = buffer.takePending();
    if (pending == null) {
      return;
    }
    broadcaster.broadcast(
        SessionEventBroadcaster.SessionEvent.stream(
            session,
            pending.chunk(),
            pending.tail(),
            pending.totalChars(),
            chunkCount.incrementAndGet(),
            attempt));
    lastFlushNanos.set(System.nanoTime());
  }

  /**
   * Cancels the provider connection of an abandoned stream so it stops generating billed tokens,
   * instead of running until the provider-side timeout with only client-side muting.
   */
  private void cancelStream(TokenStream stream, long sessionId, int attempt) {
    var handle = streamingHandleOf(stream);
    if (handle == null) {
      Log.debugf(
          "No streaming handle to cancel for session %d attempt %d — relying on provider timeout",
          sessionId, attempt);
      return;
    }
    try {
      handle.cancel();
      Log.infof("Cancelled abandoned AI stream for session %d attempt %d", sessionId, attempt);
    } catch (RuntimeException e) {
      Log.warnf(e, "Failed to cancel AI stream for session %d attempt %d", sessionId, attempt);
    }
  }

  /**
   * The handle only exists once the runtime has started streaming; before that — and for runtimes
   * other than Quarkus' — this returns null and cancellation degrades to client-side muting.
   */
  StreamingHandle streamingHandleOf(TokenStream stream) {
    if (stream instanceof QuarkusAiServiceTokenStream quarkusStream) {
      return quarkusStream.getStreamingHandle();
    }
    return null;
  }

  private Duration streamTimeout() {
    return Duration.ofSeconds(config.review().aiTimeoutSeconds());
  }

  /**
   * The wait before a retry. A refusal for rate or concurrency is a throttle (#838): the provider
   * either queued the request and gave up waiting for a slot, or counted it over a limit, so the
   * capacity the retry needs is not a couple of seconds away, and the exponential schedule's first
   * steps would send it into the same full queue. Production on Ollama cloud saw such refusals
   * recover on retry, but each immediate retry was a gamble on a slot freeing within the base
   * delay. A throttle waits the schedule's ceiling, {@link #MAX_BACKOFF}, instead; every other
   * failure keeps the exponential step. The wait goes through {@link #sleep} either way, so an
   * interrupt ends it by restoring the flag and throwing.
   */
  private Duration retryDelay(
      ReviewSession session, int attempt, int maxAttempts, RuntimeException lastFailure) {
    if (!isThrottle(lastFailure)) {
      return backoffDelay(attempt);
    }
    Log.infof(
        "AI review attempt %d/%d for session %d follows a provider throttle (a rate limit or no"
            + " free concurrent request slot); waiting %d s before it",
        attempt, maxAttempts, session.id, MAX_BACKOFF.toSeconds());
    return MAX_BACKOFF;
  }

  /**
   * Whether a failure is the provider refusing for rate or concurrency: a langchain4j {@link
   * RateLimitException} (how the OpenAI client maps an HTTP 429), a bare 429, or the concurrent
   * slot refusal's wording under whatever status the provider sent it with. Visible for tests.
   */
  static boolean isThrottle(Throwable failure) {
    return Throwables.findCause(failure, AiReviewService::namesAThrottle).isPresent();
  }

  private static boolean namesAThrottle(Throwable cause) {
    if (cause instanceof RateLimitException) {
      return true;
    }
    if (cause instanceof HttpException http && http.statusCode() == TOO_MANY_REQUESTS) {
      return true;
    }
    var message = cause.getMessage();
    return message != null && message.contains(CONCURRENT_SLOT_REFUSAL);
  }

  private Duration backoffDelay(int attempt) {
    var baseMs = config.review().aiRetryBaseDelayMs();
    var delay = baseMs * (1L << Math.min(attempt - 2, 4));
    return Duration.ofMillis(Math.min(delay, MAX_BACKOFF.toMillis()));
  }

  void sleep(Duration delay) {
    try {
      Thread.sleep(delay.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AiReviewException("Retry delay interrupted", 1, e);
    }
  }

  /**
   * Accumulates streamed tokens and tracks the flush watermark. Owns its synchronization: the
   * streaming thread appends while the timeout path may flush from the waiting thread.
   */
  static final class StreamBuffer {

    private final StringBuilder text = new StringBuilder();
    private int flushedLength;

    /** Appends a token and returns how many characters are pending since the last flush. */
    synchronized int append(String token) {
      text.append(token);
      return text.length() - flushedLength;
    }

    /** Takes the unflushed delta with a tail/total snapshot, or null when nothing is pending. */
    synchronized PendingChunk takePending() {
      if (flushedLength >= text.length()) {
        return null;
      }
      var chunk = text.substring(flushedLength);
      flushedLength = text.length();
      var totalChars = text.length();
      var tail = text.substring(Math.max(0, totalChars - STREAM_TAIL_CHARS));
      return new PendingChunk(chunk, tail, totalChars);
    }

    /** The accumulated text once it holds at least {@code chars} characters; null before that. */
    synchronized String textOnceAtLeast(int chars) {
      return text.length() >= chars ? text.toString() : null;
    }

    /** Returns the accumulated text, or the fallback when nothing was streamed. */
    synchronized String textOrFallback(String fallback) {
      return text.isEmpty() && fallback != null ? fallback : text.toString();
    }

    record PendingChunk(String chunk, String tail, int totalChars) {}
  }

  static String sanitize(Throwable error) {
    if (error == null) {
      return "Unknown error";
    }
    var message = error.getMessage();
    if (message == null || message.isBlank()) {
      return error.getClass().getSimpleName();
    }
    return message.length() > 200 ? message.substring(0, 200) + "..." : message;
  }

  static String retryFailureReason(RuntimeException lastFailure) {
    if (lastFailure == null || lastFailure.getMessage() == null) {
      return "Unknown error";
    }
    return lastFailure.getMessage();
  }
}
