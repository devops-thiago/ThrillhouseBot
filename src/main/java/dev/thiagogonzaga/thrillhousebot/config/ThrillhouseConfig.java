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
package dev.thiagogonzaga.thrillhousebot.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "thrillhousebot")
public interface ThrillhouseConfig {
  /** TCP connect timeout for the shared outbound {@link java.net.http.HttpClient}. */
  @WithDefault("10s")
  @WithName("http-connect-timeout")
  Duration httpConnectTimeout();

  /** Per-request timeout for outbound HTTP calls made with the shared client. */
  @WithDefault("10s")
  @WithName("http-request-timeout")
  Duration httpRequestTimeout();

  /**
   * Interval between dashboard WebSocket keepalive frames so reverse proxies do not idle-close
   * connections.
   */
  @WithDefault("25000")
  @WithName("websocket-keepalive-ms")
  long websocketKeepAliveMs();

  GitHubConfig github();

  WebhookConfig webhook();

  ReviewConfig review();

  DashboardConfig dashboard();

  AiPricingConfig ai();

  interface GitHubConfig {
    @WithName("app-id")
    String appId();

    @WithName("private-key")
    String privateKey();

    @WithName("webhook-secret")
    String webhookSecret();

    /**
     * GitHub login(s) of this App's bot account. Used everywhere the bot must recognize its own
     * activity — skipping its own comments to avoid infinite reply loops, summary-comment dedup,
     * prior-review detection, and follow-up thread matching (resolved via {@link BotIdentity}).
     * Defaults to the names this project ships under; override when the App is deployed under a
     * different slug (its bot login is {@code <app-slug>[bot]}).
     */
    @WithName("bot-logins")
    @WithDefault("thrillhousebot[bot],thrillhouse-bot[bot]")
    List<String> botLogins();
  }

  interface WebhookConfig {
    /**
     * How long a processed {@code X-GitHub-Delivery} id is remembered so GitHub redeliveries
     * (manual redelivery or automatic retries) within this window are dropped instead of triggering
     * a duplicate review. State is in-memory per replica.
     */
    @WithDefault("24h")
    @WithName("dedup-ttl")
    Duration dedupTtl();

    /** Filters applied to automatic pull_request events before a review is dispatched. */
    TriggerFilters triggers();

    /**
     * Controls which pull requests automatically trigger a review (opened/reopened/synchronize).
     * Defaults review every PR, matching the original behavior. Filters apply only to automatic
     * triggers — a manual {@code /review} comment always runs.
     */
    interface TriggerFilters {
      /**
       * Skip auto-review while a PR is a draft. Pairs with the {@code ready_for_review} trigger so
       * a draft is reviewed once it is marked ready.
       */
      @WithDefault("false")
      @WithName("skip-drafts")
      boolean skipDrafts();

      /**
       * When non-empty, only auto-review PRs carrying at least one of these labels
       * (case-insensitive exact match). Empty means no label is required.
       */
      @WithName("required-labels")
      Optional<List<String>> requiredLabels();

      /**
       * Skip auto-review of any PR carrying one of these labels (case-insensitive exact match).
       * Takes precedence over {@link #requiredLabels()}.
       */
      @WithName("excluded-labels")
      Optional<List<String>> excludedLabels();

      /**
       * When non-empty, only auto-review PRs whose base branch matches one of these globs (e.g.
       * {@code main}, {@code release/*}). Empty means every base branch is allowed.
       */
      @WithName("base-branches")
      Optional<List<String>> baseBranches();

      /**
       * Skip auto-review of PRs whose base branch matches one of these globs. Takes precedence over
       * {@link #baseBranches()}.
       */
      @WithName("ignored-base-branches")
      Optional<List<String>> ignoredBaseBranches();
    }
  }

  interface ReviewConfig {
    @WithDefault("50")
    @WithName("max-review-comments")
    int maxReviewComments();

    @WithDefault("5")
    @WithName("max-ai-retries")
    int maxAiRetries();

    @WithDefault("2000")
    @WithName("ai-retry-base-delay-ms")
    long aiRetryBaseDelayMs();

    @WithDefault("300")
    @WithName("ai-timeout-seconds")
    int aiTimeoutSeconds();

    /**
     * Line cap on single-call diff renders: the on-demand commands (/describe, /changelog,
     * /add-docs), maintainer replies, and the budgeting-disabled legacy review ({@code
     * max-input-tokens=0}). Token-budgeted reviews ({@code max-input-tokens > 0}) ignore this —
     * {@link dev.thiagogonzaga.thrillhousebot.review.DiffBudgetPlanner} owns coverage by tokens per
     * request, and the review context loader does not line-truncate the loaded diff or base
     * comparison. Keeps its previous default and released semantics: 5000 lines, and an explicit
     * {@code 0} turns the cap off (unbounded render) for the remaining single-call paths.
     */
    @WithDefault("5000")
    @WithName("max-diff-lines")
    int maxDiffLines();

    /**
     * Per-call input-token budget for the review prompt. The diff is split into batches that each
     * fit this budget after the shared prompt overhead and {@link #outputBufferTokens()} are
     * subtracted; a PR whose diff exceeds one batch is reviewed in multiple calls. Sized for the
     * model's context window — keep headroom for output. 0 disables token budgeting (single call).
     */
    @WithDefault("48000")
    @WithName("max-input-tokens")
    int maxInputTokens();

    /** Tokens reserved out of the context window for the model's response (findings JSON). */
    @WithDefault("8192")
    @WithName("output-buffer-tokens")
    int outputBufferTokens();

    /**
     * Hard cap on model calls per review across all batches plus the final summary call, so a
     * pathologically large PR can never fan out without bound. Files that do not fit within this
     * many calls are reported by name, never silently dropped.
     */
    @WithDefault("6")
    @WithName("max-ai-calls")
    int maxAiCalls();

    /**
     * Fraction of {@link #maxInputTokens()} actually used when budgeting, so an under-estimate from
     * the provider-agnostic token counter never pushes a call over the real limit. Self-calibration
     * against the API's reported usage is a follow-up.
     */
    @WithDefault("0.9")
    @WithName("token-safety-margin")
    double tokenSafetyMargin();

    @WithDefault(".github/thrillhousebot.md")
    @WithName("instructions-file")
    String instructionsFile();

    /** Second-pass AI audit of findings before posting; trades one extra call for fewer FPs. */
    @WithDefault("true")
    @WithName("verifier-enabled")
    boolean verifierEnabled();

    /**
     * How severely a finding must score before the review escalates to {@code REQUEST_CHANGES}. One
     * of {@code balanced} (default — CRITICAL/HIGH + HIGH confidence), {@code strict} (any
     * CRITICAL/HIGH regardless of confidence), or {@code lenient} (CRITICAL + HIGH confidence
     * only). Case-insensitive; validated at boot by {@link StartupConfigValidator}.
     */
    @WithDefault("balanced")
    @WithName("blocking-strictness")
    String blockingStrictness();

    /**
     * Whether the bot answers maintainer replies to its review findings and {@code @thrillhousebot}
     * mentions in PR threads with a contextual AI reply. Each reply spends the operator's AI
     * budget, so this is the operator's kill switch; replies are additionally restricted to the
     * same write-access/allowlisted users as a manual {@code /review}.
     */
    @WithDefault("true")
    @WithName("conversational-replies-enabled")
    boolean conversationalRepliesEnabled();

    /**
     * Whether the {@code /add-docs} command is available. When a write-access holder runs it, the
     * model generates docstrings/inline docs for the symbols changed in the diff and posts them as
     * committable suggestions. Each invocation spends the operator's AI budget, so this is the
     * operator's kill switch; the command is otherwise on-demand only (never automatic).
     */
    @WithDefault("true")
    @WithName("add-docs-enabled")
    boolean addDocsEnabled();

    @WithDefault("**/pom.xml,**/package-lock.json,**/*.lock,**/*.generated.*,**/target/**")
    @WithName("ignored-files")
    List<String> ignoredFiles();

    /**
     * GitHub logins permitted to manually trigger reviews regardless of repository permission. When
     * empty, only users with write access to the repository may trigger a manual review.
     */
    @WithName("manual-trigger-allowed-logins")
    Optional<List<String>> manualTriggerAllowedLogins();

    /**
     * Upper bound on the manual-review write-access check — the installation-token mint (on a cold
     * cache) plus the GitHub collaborator-permission call — which runs on the synchronous webhook
     * acknowledgement thread. GitHub expects the {@code 200} ACK within ~10s, so when this elapses
     * the check fails closed (denying the manual review) instead of letting a degraded GitHub tie
     * up a webhook worker past the delivery SLA.
     */
    @WithDefault("5s")
    @WithName("manual-trigger-auth-timeout")
    Duration manualTriggerAuthTimeout();

    /**
     * Upper bound on the 👀 command-ack reaction (token mint on a cold cache plus the reaction
     * POST), which runs on the synchronous webhook acknowledgement thread. GitHub expects the
     * {@code 200} ACK within ~10s, so when this elapses the wait is abandoned — the reaction may
     * still land late in the background — instead of letting a degraded GitHub delay the ACK.
     */
    @WithDefault("3s")
    @WithName("ack-reaction-timeout")
    Duration ackReactionTimeout();

    /**
     * Minimum interval between automatic (webhook-triggered) reviews of the same PR. While the last
     * automatic review completed less than this ago, {@code pull_request} events for the PR are
     * skipped silently — even when the head SHA changed — capping AI spend on noisy repositories. A
     * manual {@code /review} always bypasses and never shifts the window. Zero (or negative)
     * disables throttling. Tracked in-memory per replica.
     */
    @WithDefault("0")
    @WithName("auto-review-min-interval")
    Duration autoReviewMinInterval();

    LabelsConfig labels();

    DiagramConfig diagram();
  }

  /**
   * Opt-in Mermaid control-flow diagram in the PR summary. When {@link #enabled()} the model is
   * asked to emit a small Mermaid diagram of the affected control flow for non-trivial changes,
   * which the summary comment renders as a collapsible block (GitHub renders Mermaid natively). It
   * rides the existing review call, so it costs only a few extra output tokens.
   */
  interface DiagramConfig {
    /** Master switch — no diagram is requested or rendered unless this is {@code true}. */
    @WithDefault("false")
    boolean enabled();
  }

  /**
   * Opt-in context-aware PR labelling. When {@link #enabled()} the model is shown the repository's
   * existing labels and asked which best describe the change; the result is either applied to the
   * PR or posted as a suggestion comment.
   */
  interface LabelsConfig {
    /** Master switch — the whole feature is off unless this is {@code true}. */
    @WithDefault("false")
    boolean enabled();

    /**
     * When {@code true}, the matched labels are added to the PR. When {@code false} (the default),
     * they are only posted as a suggestion comment on the first review — applying is the explicit
     * opt-in.
     */
    @WithDefault("false")
    boolean apply();

    /**
     * When {@code true}, labels the model suggests that do not yet exist in the repository are
     * created before being applied. Off by default so the bot never invents labels.
     */
    @WithName("allow-create")
    @WithDefault("false")
    boolean allowCreate();

    /** Upper bound on how many labels are applied or suggested for one PR. */
    @WithName("max-labels")
    @WithDefault("3")
    int maxLabels();
  }

  interface DashboardConfig {
    /** Public base URL of the dashboard, used for session deep-links posted to GitHub. */
    @WithName("url")
    @WithDefault("http://localhost:8080")
    String url();

    @WithName("github.client-id")
    Optional<String> clientId();

    @WithName("github.client-secret")
    Optional<String> clientSecret();

    @WithName("github.redirect-uri")
    @WithDefault("http://localhost:8080/api/auth/callback")
    String redirectUri();

    @WithName("github.oauth-url")
    @WithDefault("https://github.com/login/oauth")
    String oauthUrl();

    /** Optional override; when unset the owner is resolved from the GitHub App via GET /app. */
    @WithName("github.account-owner")
    Optional<String> accountOwner();
  }

  interface AiPricingConfig {
    /**
     * Configured AI base URL; used to derive the telemetry provider when none is set explicitly.
     */
    @WithName("base-url")
    String baseUrl();

    /**
     * Explicit provider label for telemetry ({@code gen_ai.provider.name}). When unset, the
     * provider is derived from {@link #baseUrl()}.
     */
    @WithName("provider-name")
    Optional<String> providerName();

    ReasoningConfig reasoning();

    Map<String, ModelPricing> pricing();

    /**
     * Per-model AI settings keyed by the model name (the {@code AI_MODEL} value), mirroring the
     * {@link #pricing()} key scheme. Only the active model's entry is read; keeping entries for
     * several models lets an operator switch {@code AI_MODEL} without retuning. All values are
     * optional — an absent value falls back as documented on each key — so a model needs an entry
     * only for the values that differ from the defaults. Resolution for the active model lives in
     * {@link ActiveModelSettings}.
     */
    Map<String, ModelSettings> models();

    /**
     * Reasoning-effort control for reasoning-capable models. Off by default so no reasoning
     * parameter is sent and today's provider-default behavior is preserved; when {@link #enabled()}
     * the configured {@link #effort()} is sent as the OpenAI-compatible {@code reasoning_effort} on
     * every chat call, which providers map to their thinking budgets. Reasoning tokens are billed
     * as output tokens, so this is the operator's cost/quality dial.
     */
    interface ReasoningConfig {
      /** Effort values accepted by {@link #effort()}, in ascending cost/quality order. */
      List<String> ALLOWED_EFFORTS = List.of("none", "low", "medium", "high");

      /** Master switch — no reasoning parameter is sent unless this is {@code true}. */
      @WithDefault("false")
      boolean enabled();

      /**
       * Effort sent while {@link #enabled()}: one of {@code none}, {@code low}, {@code medium},
       * {@code high} (case-insensitive). {@code none} explicitly asks the model not to reason —
       * useful to pin down a reasoning-capable model that reasons by default. Validated at boot by
       * {@link StartupConfigValidator}.
       */
      @WithDefault("low")
      String effort();

      /** An {@link #effort()} value normalized to the lowercase wire value providers expect. */
      static String normalize(String effort) {
        return effort.strip().toLowerCase(java.util.Locale.ROOT);
      }
    }

    /**
     * One model's settings: the input-token hard cap the budgeter respects, per-model overrides of
     * the token-budget knobs, and generation parameters sent on every chat call. Generation
     * parameters ride the OpenAI-compatible wire, which carries {@code temperature}, {@code top_p},
     * and {@code max_tokens} but has no {@code top_k} — a top-k dial needs a native provider
     * integration.
     */
    interface ModelSettings {
      /**
       * Input-token cap assumed for a model with no explicit {@link #maxInputTokens()} — sized to
       * the smallest context window among commonly deployed models, so an unknown model is never
       * assumed larger than it may be.
       */
      int DEFAULT_MAX_INPUT_TOKENS = 128_000;

      /**
       * Hard cap on the per-call input tokens for this model — its context window. The effective
       * review budget is the smaller of {@code thrillhousebot.review.max-input-tokens} and this cap
       * ({@link #DEFAULT_MAX_INPUT_TOKENS} when absent), so a raised global budget can never
       * overshoot a model's real window unless the operator raises the cap too.
       */
      @WithName("max-input-tokens")
      Optional<Integer> maxInputTokens();

      /**
       * Per-model override of {@code thrillhousebot.review.output-buffer-tokens} — tokens reserved
       * out of the context window for the model's response.
       */
      @WithName("output-buffer-tokens")
      Optional<Integer> outputBufferTokens();

      /**
       * Per-model override of {@code thrillhousebot.review.token-safety-margin} — the fraction of
       * the input budget actually used, absorbing token-estimate error (which varies by model
       * family; the estimator BPE is cl100k_base).
       */
      @WithName("token-safety-margin")
      Optional<Double> tokenSafetyMargin();

      /**
       * Sampling temperature sent on every chat call; absent keeps the default already in effect
       * (the extension's 1.0).
       */
      Optional<Double> temperature();

      /**
       * Nucleus-sampling {@code top_p} sent on every chat call; absent keeps the default already in
       * effect (the extension's 1.0).
       */
      @WithName("top-p")
      Optional<Double> topP();

      /**
       * OpenAI-compatible {@code max_tokens} sent on every chat call, bounding the response length
       * (and spend). Absent leaves the provider default. Independent of {@link
       * #outputBufferTokens()}, which only reserves input-budget headroom — set both when capping
       * output.
       */
      @WithName("max-output-tokens")
      Optional<Integer> maxOutputTokens();
    }

    interface ModelPricing {
      @WithName("input-per-1k")
      double inputPer1k();

      @WithName("output-per-1k")
      double outputPer1k();

      /** Cost for the given token counts using per-1K input/output rates. */
      static double cost(
          double inputPer1k, double outputPer1k, long inputTokens, long outputTokens) {
        return (inputTokens * inputPer1k + outputTokens * outputPer1k) / 1000.0;
      }
    }
  }
}
