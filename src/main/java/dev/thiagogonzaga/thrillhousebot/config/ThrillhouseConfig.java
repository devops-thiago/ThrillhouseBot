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

/**
 * Typed view of every {@code thrillhousebot.*} key.
 *
 * <p>The configuration reference ({@code docs/CONFIGURATION.md}) is generated from this interface
 * and {@code application.properties} by {@code ConfigReferenceTest}. The first paragraph of a
 * property's javadoc is its published description, so it is written for operators and names other
 * settings by their environment variable; later paragraphs are maintainer notes and stay out of the
 * reference. A {@code {@link}} is not allowed in the first paragraph.
 */
@ConfigMapping(prefix = "thrillhousebot")
public interface ThrillhouseConfig {
  /**
   * Outbound HTTP connect timeout (GitHub API, OAuth).
   *
   * <p>TCP connect timeout of the shared outbound {@link java.net.http.HttpClient}.
   */
  @WithDefault("10s")
  @WithName("http-connect-timeout")
  Duration httpConnectTimeout();

  /** Outbound HTTP request timeout (GitHub API, OAuth). */
  @WithDefault("10s")
  @WithName("http-request-timeout")
  Duration httpRequestTimeout();

  /**
   * Dashboard WebSocket keepalive in milliseconds, so reverse proxies do not close idle
   * connections. {@code 0} or less disables it and stale replay-buffer eviction.
   */
  @WithDefault("25000")
  @WithName("websocket-keepalive-ms")
  long websocketKeepAliveMs();

  GitHubConfig github();

  WebhookConfig webhook();

  ReviewConfig review();

  DashboardConfig dashboard();

  AiPricingConfig ai();

  MetricsConfig metrics();

  interface MetricsConfig {
    /**
     * Serves the OpenTelemetry metrics in Prometheus text format at {@code GET /metrics} on the
     * main HTTP port, alongside the OTLP export. Off by default: the endpoint is unauthenticated,
     * so shield it at the proxy before enabling it on internet-facing deployments.
     */
    @WithDefault("false")
    @WithName("prometheus-enabled")
    boolean prometheusEnabled();
  }

  NotificationsConfig notifications();

  interface GitHubConfig {
    /** GitHub App ID, from the App's settings page. Required. */
    @WithName("app-id")
    String appId();

    /**
     * GitHub App private key (PEM), on one line with each newline written as {@code \n}, unquoted.
     * Required.
     */
    @WithName("private-key")
    String privateKey();

    /** The GitHub App's webhook secret, used to check each delivery's HMAC signature. Required. */
    @WithName("webhook-secret")
    String webhookSecret();

    /**
     * Comma-separated bot logins the bot ignores so it never answers itself. Override when the App
     * slug differs ({@code <app-slug>[bot]}).
     *
     * <p>Used everywhere the bot must recognize its own activity: skipping its own comments to
     * avoid infinite reply loops, summary-comment dedup, prior-review detection, and follow-up
     * thread matching (resolved via {@link BotIdentity}).
     */
    @WithName("bot-logins")
    @WithDefault("thrillhousebot[bot],thrillhouse-bot[bot]")
    List<String> botLogins();

    /**
     * Minimum spacing between two content-creating GitHub calls (comments, review comments,
     * replies, reviews), shared by the whole process. GitHub's guidance is at most one per second;
     * it answers faster writes with {@code 403}, {@code 429} or a {@code 422} "was submitted too
     * quickly". When GitHub throttles a write anyway, every queued write waits with it. {@code 0}
     * disables pacing.
     *
     * <p>Declared here as the namespace's schema: the limiter itself sits on the REST clients'
     * write path, which has no CDI, and reads the key directly (see {@code GitHubWritePacer}).
     */
    @WithName("write-min-interval")
    @WithDefault("1s")
    Duration writeMinInterval();

    /**
     * Longest one caller waits for its write slot before it goes out unpaced and the backoff
     * handles any refusal.
     *
     * <p>So a long queue never parks a finished command while it holds its per-PR dispatcher slot.
     */
    @WithName("write-max-wait")
    @WithDefault("90s")
    Duration writeMaxWait();

    /**
     * Longest one review may spend waiting on GitHub's rate limit across all its writes. Once
     * spent, later throttled writes are not retried, and the review body names the findings they
     * carried and asks for a re-run. {@code 0} disables the limit.
     *
     * <p>#734: the backoff bounds one call at 90 seconds and a review makes one call per route per
     * finding, so without this a review refused throughout could hold its pull request's dispatcher
     * slot for hours. Read by {@code ReviewPublisher} when it opens a review's ledger (see {@code
     * GitHubWriteBudget}).
     */
    @WithName("write-retry-budget")
    @WithDefault("5m")
    Duration writeRetryBudget();
  }

  interface WebhookConfig {
    /**
     * How long a webhook delivery id ({@code X-GitHub-Delivery}) is remembered, to ignore GitHub
     * redeliveries instead of reviewing twice. Kept in memory, per replica.
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
       * Do not auto-review drafts; the PR is reviewed once marked ready ({@code ready_for_review}).
       */
      @WithDefault("false")
      @WithName("skip-drafts")
      boolean skipDrafts();

      /**
       * Comma-separated labels; auto-review only PRs carrying at least one (case-insensitive exact
       * match). Unset means no label is required.
       */
      @WithName("required-labels")
      Optional<List<String>> requiredLabels();

      /**
       * Comma-separated labels; skip auto-review of PRs carrying any (case-insensitive exact
       * match). Wins over {@code WEBHOOK_REQUIRED_LABELS}.
       */
      @WithName("excluded-labels")
      Optional<List<String>> excludedLabels();

      /**
       * Comma-separated globs; auto-review only PRs whose base branch matches one ({@code
       * main,release/*}). {@code *} does not cross {@code /}; use {@code **} for that. Unset means
       * every base branch.
       */
      @WithName("base-branches")
      Optional<List<String>> baseBranches();

      /**
       * Comma-separated globs; skip auto-review of PRs whose base branch matches one ({@code
       * dependabot/**}). Wins over {@code WEBHOOK_BASE_BRANCHES}.
       */
      @WithName("ignored-base-branches")
      Optional<List<String>> ignoredBaseBranches();
    }
  }

  interface ReviewConfig {
    /** Most inline comments per review; the rest are listed in the summary. */
    @WithDefault("50")
    @WithName("max-review-comments")
    int maxReviewComments();

    /** Attempts per failed AI call. At most two may end in a timeout. */
    @WithDefault("5")
    @WithName("max-ai-retries")
    int maxAiRetries();

    /** Base delay of the exponential retry backoff, in milliseconds. */
    @WithDefault("2000")
    @WithName("ai-retry-base-delay-ms")
    long aiRetryBaseDelayMs();

    /**
     * Client-side wait per streaming attempt, in seconds. Keep it at or above {@code AI_TIMEOUT} so
     * a timed-out attempt does not leave a stream open at the provider. A second timeout fails the
     * call.
     */
    @WithDefault("300")
    @WithName("ai-timeout-seconds")
    int aiTimeoutSeconds();

    /**
     * Line cap for single-call diff renders (replies, base comparison, reviews with budgeting off).
     * Token-budgeted reviews and the batched commands ignore it. {@code 0} disables it.
     *
     * <p>Applies to the on-demand commands (/describe, /changelog, /add-docs), maintainer replies,
     * and the budgeting-disabled legacy review ({@code max-input-tokens=0}). Token-budgeted reviews
     * ({@code max-input-tokens > 0}) ignore this: {@link
     * dev.thiagogonzaga.thrillhousebot.review.DiffBudgetPlanner} owns coverage by tokens per
     * request, and the review context loader does not line-truncate the loaded diff or base
     * comparison.
     */
    @WithDefault("5000")
    @WithName("max-diff-lines")
    int maxDiffLines();

    /**
     * Input-token budget per call for reviews and the batched commands; larger PRs are split into
     * batches. Capped by the model's {@code max-input-tokens} (see the per-model settings). {@code
     * 0} disables budgeting.
     *
     * <p>The diff is split into batches that each fit this budget after the shared prompt overhead
     * and {@link #outputBufferTokens()} are subtracted (the buffer only on a shared window: a model
     * marked {@code separate-output-budget} reserves nothing).
     */
    @WithDefault("48000")
    @WithName("max-input-tokens")
    int maxInputTokens();

    /**
     * Tokens reserved from the input budget for the response. A model with {@code
     * separate-output-budget} set reserves nothing.
     *
     * <p>On a shared window the response is spent from the same pool the prompt was packed into. A
     * model marked {@link AiPricingConfig.ModelSettings#separateOutputBudget()} draws its response
     * on an allowance of its own, so it takes nothing from the diff.
     */
    @WithDefault("8192")
    @WithName("output-buffer-tokens")
    int outputBufferTokens();

    /**
     * Most AI calls per review (batch calls plus the summary call) and per command run (batch calls
     * plus, for {@code /describe} and {@code /changelog}, the merge call). At {@code 1}, a review
     * that makes its review call has none left for the summary and posts a counts-only summary that
     * says so. Verifier calls are not counted.
     *
     * <p>#664: a hard cap so a pathologically large PR can never fan out without bound. Files that
     * do not fit within this many calls are reported by name, never silently dropped; a review
     * whose every file exceeded the budget makes no review call, so its summary call still fits.
     */
    @WithDefault("6")
    @WithName("max-ai-calls")
    int maxAiCalls();

    /**
     * Fraction of the input budget actually used, to absorb token-estimate error.
     *
     * <p>So an under-estimate from the provider-agnostic token counter never pushes a call over the
     * real limit. Self-calibration against the API's reported usage is a follow-up.
     */
    @WithDefault("0.9")
    @WithName("token-safety-margin")
    double tokenSafetyMargin();

    /**
     * Most tokens one review may use across all its calls, as the provider reports them, retries
     * and the summary call included. Once reached, no further call is made: the remaining batches
     * are named as not reviewed, the summary degrades to counts only, and the findings already paid
     * for are kept. Reviews only. {@code 0} disables it.
     *
     * <p>Complements {@link #maxAiCalls()}, which bounds how many calls are planned but not what
     * they cost: one call against a large-context model can span two orders of magnitude of spend.
     * The overrun is bounded by one in-flight call per parallel batch.
     */
    @WithDefault("0")
    @WithName("max-tokens-per-review")
    long maxTokensPerReview();

    /** Repository path of the review instructions file. */
    @WithDefault(".github/thrillhousebot.md")
    @WithName("instructions-file")
    String instructionsFile();

    /**
     * Run a second, skeptical pass that re-checks each finding against the diff and drops or
     * downgrades what it cannot confirm. See <a
     * href="https://github.com/devops-thiago/ThrillhouseBot#ai-call-budget">AI call budget</a>.
     */
    @WithDefault("true")
    @WithName("verifier-enabled")
    boolean verifierEnabled();

    /**
     * Check the summary's sentences, walkthrough rows and diagram against the diff and the
     * findings, and remove what they contradict. One more call per review. See <a
     * href="https://github.com/devops-thiago/ThrillhouseBot#ai-call-budget">AI call budget</a>.
     *
     * <p>#1078: the call runs on the concise model after the summary call. It fails open and never
     * changes findings.
     */
    @WithDefault("false")
    @WithName("summary-verification-enabled")
    boolean summaryVerificationEnabled();

    /**
     * When a review or summary answer is not valid review JSON, send only that answer and the
     * parser's error in one small call and ask for the same content in the required shape, before
     * resending the whole prompt. Also asks again for the walkthrough's file summaries when the
     * summary answer wrote them as a string. See <a
     * href="https://github.com/devops-thiago/ThrillhouseBot#ai-call-budget">AI call budget</a>.
     *
     * <p>#1079: the repair fails open, and a failed or rejected repair leaves the full retry
     * exactly as it was.
     */
    @WithDefault("true")
    @WithName("response-repair-enabled")
    boolean responseRepairEnabled();

    /**
     * Give each review call only the review-dimension blocks its files can use, instead of all ten.
     * See <a href="https://github.com/devops-thiago/ThrillhouseBot#dimension-routing">Dimension
     * routing</a>.
     *
     * <p>#665: the verifier's dimension carve-outs follow the files its candidates are anchored in.
     * Off, every call carries the monolithic prompt, byte for byte the one before the split.
     */
    @WithDefault("false")
    @WithName("dimension-routing-enabled")
    boolean dimensionRoutingEnabled();

    /**
     * Check a maintainer's decline against the reviewed code before recording the finding
     * justified. See <a
     * href="https://github.com/devops-thiago/ThrillhouseBot#re-checking-declines">Re-checking
     * declines</a>. {@code false} makes any reply final.
     *
     * <p>When the reviewed code plainly contradicts the rebuttal's premise the finding stays open
     * for one more round; every other decline is respected, and a second reply on the thread always
     * ends the re-check.
     */
    @WithDefault("true")
    @WithName("decline-recheck-enabled")
    boolean declineRecheckEnabled();

    /**
     * Which findings request changes: {@code balanced}, {@code strict} or {@code lenient}. See <a
     * href="https://github.com/devops-thiago/ThrillhouseBot#blocking-strictness">Blocking
     * strictness</a>.
     *
     * <p>{@code balanced}: CRITICAL/HIGH at HIGH confidence; {@code strict}: any CRITICAL/HIGH
     * regardless of confidence; {@code lenient}: CRITICAL at HIGH confidence only.
     * Case-insensitive; validated at boot by {@link StartupConfigValidator}.
     */
    @WithDefault("balanced")
    @WithName("blocking-strictness")
    String blockingStrictness();

    /**
     * Answer {@code @thrillhousebot} mentions in PR threads and replies to findings.
     *
     * <p>Each reply spends the operator's AI budget, so this is the operator's kill switch; replies
     * are restricted to the same write-access/allowlisted users as a manual {@code /review}.
     */
    @WithDefault("true")
    @WithName("conversational-replies-enabled")
    boolean conversationalRepliesEnabled();

    /**
     * Allow {@code /add-docs}, which posts generated docs for the changed symbols as committable
     * suggestions.
     *
     * <p>Each invocation spends the operator's AI budget, so this is the operator's kill switch;
     * the command is otherwise on-demand only (never automatic).
     */
    @WithDefault("true")
    @WithName("add-docs-enabled")
    boolean addDocsEnabled();

    /**
     * Allow {@code /improve}, which posts a whole-PR improvement pass as committable suggestions.
     *
     * <p>Each invocation spends the operator's AI budget, so this is the operator's kill switch;
     * like {@code add-docs-enabled} the command is otherwise on-demand only (never automatic),
     * which is why it ships on.
     */
    @WithDefault("true")
    @WithName("improve-enabled")
    boolean improveEnabled();

    /**
     * Allow {@code /generate-tests}, which posts proposed unit tests for the changed code as a
     * comment to copy in. Nothing is committed.
     *
     * <p>Each invocation spends the operator's AI budget, so this is the operator's kill switch;
     * the command is otherwise on-demand only (never automatic).
     */
    @WithDefault("true")
    @WithName("generate-tests-enabled")
    boolean generateTestsEnabled();

    /**
     * Comma-separated gitignore-style globs never reviewed. A pattern without {@code /} matches at
     * any depth ({@code *.lock}, {@code vendor}); one with {@code /} is anchored at the root
     * ({@code docs/generated/**}); a trailing {@code /} means the directory's tree; {@code *} does
     * not cross {@code /}. Write a {@code {a,b}} alternation as separate patterns. Setting it
     * replaces the default list, which covers lockfiles, generated and minified code, sourcemaps,
     * and build and vendor trees. Build manifests such as {@code pom.xml}, {@code package.json} and
     * {@code Cargo.toml} are reviewed; every file the list excludes is named in the summary's scope
     * note.
     *
     * <p>Generated or vendored artifacts only, never handwritten source. Directory globs like
     * {@code **}{@code /build/**} may need narrowing in repos that keep real source under those
     * names.
     *
     * <p>Patterns are gitignore-style and are compiled by {@code ReviewDiffFormatter.IgnoreGlobs},
     * the same matcher a repository's own {@code review.ignored-files} list goes through, so the
     * two keys can never drift apart (#481). The defaults are spelled with an explicit {@code
     * **}{@code /} prefix, which is what an unanchored pattern means anyway; they are left that way
     * so this list reads identically to the one shipped in {@code application.properties}. Being a
     * comma-separated value, this key cannot carry a {@code &#123;a,b&#125;} alternation — write
     * those as separate patterns.
     */
    @WithDefault(
        "**/package-lock.json,**/pnpm-lock.yaml,**/go.sum,**/*.lock,**/*.generated.*,"
            + "**/*.pb.go,**/*_pb2.py,**/*.min.js,**/*.min.css,**/*.map,"
            + "**/target/**,**/node_modules/**,**/dist/**,**/build/**,**/out/**,**/.next/**,"
            + "**/vendor/**,**/__pycache__/**,**/.venv/**,**/bin/**,**/obj/**")
    @WithName("ignored-files")
    List<String> ignoredFiles();

    /**
     * Read {@code .github/thrillhousebot.yml} from each repository (extra ignore globs, path-scoped
     * rules, coverage artifact). See <a
     * href="https://github.com/devops-thiago/ThrillhouseBot#repository-configuration">Repository
     * configuration</a>.
     *
     * <p>Extra globs extend {@link #ignoredFiles()} under {@code review.ignored-files}, and review
     * rules are scoped to a path glob under {@code review.path-instructions}. Both are additive:
     * the effective ignore set is always global ∪ per-repo, so a repository can take more files out
     * of review scope but can never put back a file the deployment excludes, and scoped rules apply
     * on top of the repository's global instructions, for the matching files only. This is the
     * operator's kill switch for installs that must not let a repository narrow its own review
     * coverage or steer its own review rules; a missing or malformed file is ignored either way and
     * never fails a review.
     */
    @WithDefault("true")
    @WithName("repo-config-enabled")
    boolean repoConfigEnabled();

    /**
     * Comma-separated logins allowed to run slash commands without write access. Does not apply to
     * the {@code resolved} and {@code declined} directives. Unset means only users with write
     * access.
     */
    @WithName("manual-trigger-allowed-logins")
    Optional<List<String>> manualTriggerAllowedLogins();

    /**
     * Time limit on the write-access check for a command. Slower answers from GitHub deny the
     * command.
     *
     * <p>The check (the installation-token mint on a cold cache plus the collaborator-permission
     * call) runs on the synchronous webhook acknowledgement thread. GitHub expects the {@code 200}
     * ACK within ~10s, so when this elapses the check fails closed instead of letting a degraded
     * GitHub tie up a webhook worker past the delivery SLA.
     */
    @WithDefault("5s")
    @WithName("manual-trigger-auth-timeout")
    Duration manualTriggerAuthTimeout();

    /**
     * Time limit on posting the 👀 reaction to a command; the reaction may land late.
     *
     * <p>The token mint on a cold cache plus the reaction POST run on the synchronous webhook
     * acknowledgement thread. GitHub expects the {@code 200} ACK within ~10s, so when this elapses
     * the wait is abandoned instead of letting a degraded GitHub delay the ACK.
     */
    @WithDefault("3s")
    @WithName("ack-reaction-timeout")
    Duration ackReactionTimeout();

    /**
     * Minimum time between automatic reviews of one PR. Pushes inside the window are skipped, even
     * on a new head (in memory, per replica). {@code /review} always runs. {@code 0} reviews every
     * push.
     *
     * <p>A manual {@code /review} never shifts the window. A negative value also disables
     * throttling.
     */
    @WithDefault("0")
    @WithName("auto-review-min-interval")
    Duration autoReviewMinInterval();

    /**
     * How CI affects approval: {@code strict} holds approval while required CI is pending, failing
     * or unreadable; {@code warn} approves and notes the CI state; {@code off} ignores CI. See <a
     * href="https://github.com/devops-thiago/ThrillhouseBot#ci-gating">CI gating</a>.
     *
     * <p>Validated at boot by {@link StartupConfigValidator}.
     */
    @WithDefault("strict")
    @WithName("ci-gating")
    String ciGating();

    /** Values accepted by {@link #ciGating()}, matching {@code CiGatingMode}. */
    List<String> ALLOWED_CI_GATING = List.of("strict", "warn", "off");

    /** A {@link #ciGating()} value normalized to the lowercase wire form. */
    static String normalizeCiGating(String raw) {
      return raw.strip().toLowerCase(java.util.Locale.ROOT);
    }

    LabelsConfig labels();

    DiagramConfig diagram();

    @WithName("patch-coverage")
    PatchCoverageConfig patchCoverage();

    @WithName("ci-context")
    CiContextConfig ciContext();

    @WithName("ticket-context")
    TicketContextConfig ticketContext();

    LearningsConfig learnings();

    @WithName("follow-up-summary")
    FollowUpSummaryConfig followUpSummary();

    @WithName("large-pr-nudge")
    LargePrNudgeConfig largePrNudge();

    @WithName("security-scan")
    SecurityScanConfig securityScan();
  }

  /**
   * Opt-in deterministic security scan of the lines a pull request adds (#60): leaked credentials
   * in well-known formats and risky infrastructure-as-code settings, raised as findings with a
   * fixed grade and no model call. A matched credential is never repeated: every surface shows only
   * its first characters and its length.
   *
   * <p>Both halves are off by default. A new source of blocking findings changes the verdict an
   * existing deployment gets, so turning it on is the operator's call, as it is for every other
   * feature that adds to a review.
   */
  interface SecurityScanConfig {
    /**
     * Scan added lines for leaked credentials (provider token formats, private keys, JWTs,
     * assignments). See <a
     * href="https://github.com/devops-thiago/ThrillhouseBot#security-scan">Security scan</a>.
     */
    @WithName("secrets-enabled")
    @WithDefault("false")
    boolean secretsEnabled();

    /**
     * Scan added lines for risky infrastructure-as-code settings (Terraform, Kubernetes,
     * CloudFormation, Dockerfiles).
     */
    @WithName("iac-enabled")
    @WithDefault("false")
    boolean iacEnabled();

    /**
     * Minimum Shannon entropy (bits per character) for the generic credential-assignment rule; the
     * known token formats ignore it. Must be in (0, 8].
     */
    @WithName("entropy-threshold")
    @WithDefault("3.5")
    double entropyThreshold();

    /**
     * Comma-separated globs the scan skips on top of the review's ignore list, for fixtures and
     * snapshots where token-shaped sample data is expected.
     */
    @WithName("skipped-files")
    @WithDefault(
        "**/fixtures/**,**/__fixtures__/**,**/testdata/**,**/test-data/**,**/__snapshots__/**,"
            + "**/*.snap")
    List<String> skippedFiles();
  }

  /**
   * Opt-in maintainer nudge for a large pull request the review came back clean on. A big refactor
   * that produces no inline finding is either genuinely clean or a shallow pass, and the summary
   * alone cannot tell those apart — so when a PR is over the size thresholds below and no finding
   * opened an inline thread, the summary comment says so and points at {@code /review} and {@code
   * /improve}. Purely advisory: it spends no extra AI call and never changes the verdict.
   *
   * <p>Off by default, like every other feature that adds content to the summary comment ({@link
   * DiagramConfig}, {@link LabelsConfig}, {@link FollowUpSummaryConfig}) — the quiet summary is the
   * released behaviour, and a heuristic that fires on the largest PRs is the operator's call.
   */
  interface LargePrNudgeConfig {
    /**
     * On a large PR whose review opened no inline finding and left no earlier finding open, add a
     * note to the summary pointing at {@code /review} and {@code /improve}. No extra AI call; the
     * verdict is unchanged.
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * Changed files (ignored files included) at which a PR counts as large. {@code 0} turns this
     * test off.
     *
     * <p>Read from the PR-level total the summary's "Changes Overview" reports, so files the ignore
     * globs dropped still count toward how big the change is.
     */
    @WithName("min-files")
    @WithDefault("20")
    int minFiles();

    /**
     * Added plus deleted lines at which a PR counts as large; either test is enough. {@code 0}
     * turns this test off.
     *
     * <p>A handful of files can still be a large change. With both at {@code 0} the nudge never
     * fires.
     */
    @WithName("min-changed-lines")
    @WithDefault("1000")
    int minChangedLines();
  }

  /**
   * Opt-in patch-coverage context. When {@link #enabled()} — and only for a repository that names
   * its coverage artifact in {@code .github/thrillhousebot.yml} under {@code
   * review.coverage-artifact} — the bot downloads that artifact from a completed workflow run for
   * the exact head commit, intersects the report's never-executed lines with the lines the diff
   * adds, and tells the reviewer which changed logic no test exercises. Off by default: it spends
   * GitHub Actions API calls and one artifact download per review, and a repository that declares
   * no artifact name gets nothing from it either way.
   */
  interface PatchCoverageConfig {
    /**
     * Tell the review which added lines the repository's own coverage report never ran. Needs
     * {@code coverage-artifact} in {@code .github/thrillhousebot.yml} (see <a
     * href="https://github.com/devops-thiago/ThrillhouseBot#repository-configuration">Repository
     * configuration</a>).
     *
     * <p>No artifact is looked up or downloaded unless this is {@code true}.
     */
    @WithDefault("false")
    boolean enabled();
  }

  /**
   * Opt-in CI-failure context (#59). When {@link #enabled()} and the head commit already has
   * completed, failing checks when the review starts, the review prompt carries a bounded, fenced
   * summary of them — each check's name and conclusion, its output title and summary, and a page of
   * its annotations — so a finding can be tied to the failing test or build step it explains. The
   * check list is the one the CI gate already reads; the extra cost is at most one annotations page
   * per detailed failing check. Off by default: it adds prompt tokens, and a review that starts
   * while CI is still running (the usual case for an automatic review) has nothing to add.
   */
  interface CiContextConfig {
    /** Lower bound accepted for {@link #maxChars()}: room for a header and one check. */
    int MIN_MAX_CHARS = 500;

    /** Upper bound accepted for {@link #maxChars()}, so CI output can never rival the diff. */
    int MAX_MAX_CHARS = 20_000;

    /**
     * Include the checks that had already failed on the head commit. See <a
     * href="https://github.com/devops-thiago/ThrillhouseBot#ci-failure-context">CI-failure
     * context</a>.
     *
     * <p>No CI-failure section is built unless this is {@code true}.
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * Also include the last 30 lines of up to two failing GitHub Actions job logs. Ignored unless
     * {@code REVIEW_CI_CONTEXT_ENABLED} is on.
     *
     * <p>The logs are read with {@link #enabled()} alone, for the failing tests they name (#922);
     * this switch adds their last lines verbatim.
     */
    @WithName("include-logs")
    @WithDefault("false")
    boolean includeLogs();

    /**
     * Character cap on the CI section ({@value #MIN_MAX_CHARS}–{@value #MAX_MAX_CHARS}).
     *
     * <p>Applied before fencing. Counted in the planner's shared prompt overhead like every other
     * non-diff section.
     */
    @WithName("max-chars")
    @WithDefault("4000")
    int maxChars();
  }

  /**
   * Opt-in linked-issue context (#58). When {@link #enabled()}, the review resolves the issue(s)
   * the pull request says it implements and gives their title, body and acceptance criteria to the
   * review call (as intent) and to the summary call (which lists unmet acceptance criteria under
   * Description vs. Implementation). The issue is only read, never commented on, labelled or
   * closed. Off by default: it adds prompt tokens and, per review, up to one GraphQL read and one
   * REST read per candidate issue until {@link #maxIssues()} are read — at most ten, since an
   * unreadable candidate lets the next one in.
   */
  interface TicketContextConfig {
    /** The GitHub Issues provider, and the only one this build ships. */
    String GITHUB_PROVIDER = "github";

    /** Values accepted by {@link #provider()}; an external tracker adds its name here. */
    List<String> ALLOWED_PROVIDERS = List.of(GITHUB_PROVIDER);

    /** Lower bound accepted for {@link #maxIssues()}. */
    int MIN_MAX_ISSUES = 1;

    /** Upper bound accepted for {@link #maxIssues()}. */
    int MAX_MAX_ISSUES = 5;

    /** Lower bound accepted for {@link #maxChars()}: room for a title and a few criteria. */
    int MIN_MAX_CHARS = 1_000;

    /** Upper bound accepted for {@link #maxChars()}, so issue text can never rival the diff. */
    int MAX_MAX_CHARS = 20_000;

    /**
     * Include the issues the PR links and list unaddressed acceptance criteria in the summary. See
     * <a href="https://github.com/devops-thiago/ThrillhouseBot#linked-issue-context">Linked-issue
     * context</a>.
     *
     * <p>No issue is read unless this is {@code true}.
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * Where linked issues are read from. Only {@code github} exists.
     *
     * <p>Validated at boot against {@link #ALLOWED_PROVIDERS}.
     */
    @WithDefault(GITHUB_PROVIDER)
    String provider();

    /**
     * Linked issues read per review ({@value #MIN_MAX_ISSUES}–{@value #MAX_MAX_ISSUES}); once this
     * many are read, later references are ignored.
     */
    @WithName("max-issues")
    @WithDefault("3")
    int maxIssues();

    /**
     * Character cap on the linked-issue section ({@value #MIN_MAX_CHARS}–{@value #MAX_MAX_CHARS}).
     *
     * <p>Applied before fencing. It rides both the review call (counted in the planner's shared
     * overhead) and the summary call (counted in its clamp).
     */
    @WithName("max-chars")
    @WithDefault("6000")
    int maxChars();

    /**
     * Also take an issue number from the head branch name ({@code issue-57}, {@code
     * fix/57-short-name}) when nothing else links one.
     *
     * <p>Off by default: a branch name is a guess, and a wrong guess puts an unrelated issue in
     * front of the model.
     */
    @WithName("from-branch")
    @WithDefault("false")
    boolean fromBranch();
  }

  /**
   * Opt-in cross-review learnings (#38). When {@link #enabled()}, a maintainer's decline that
   * survives the decline re-check ({@code decline-recheck-enabled}, #169) is stored per repository
   * with its reason, as is an explicit {@code /remember} convention, and the learnings relevant to
   * the files a review touches ride that review's prompt as fenced, untrusted data. Maintainers
   * list them with {@code /learnings} and retract one with {@code /forget <id>}. Off by default: a
   * stored learning changes what every later review of the repository says.
   */
  interface LearningsConfig {
    /** Lower bound accepted for {@link #maxPerRepo()}. */
    int MIN_MAX_PER_REPO = 1;

    /** Upper bound accepted for {@link #maxPerRepo()}. */
    int MAX_MAX_PER_REPO = 1000;

    /** Lower bound accepted for {@link #promptMaxItems()}. */
    int MIN_PROMPT_MAX_ITEMS = 1;

    /** Upper bound accepted for {@link #promptMaxItems()}. */
    int MAX_PROMPT_MAX_ITEMS = 50;

    /** Lower bound accepted for {@link #promptMaxChars()}: room for one full learning. */
    int MIN_PROMPT_MAX_CHARS = 500;

    /** Upper bound accepted for {@link #promptMaxChars()}, so learnings never rival the diff. */
    int MAX_PROMPT_MAX_CHARS = 20_000;

    /**
     * Remember maintainer declines and {@code /remember} conventions per repository and show them
     * to later reviews. See <a
     * href="https://github.com/devops-thiago/ThrillhouseBot#review-learnings">Review learnings</a>.
     * Startup fails if this is on while {@code REVIEW_DECLINE_RECHECK_ENABLED} is off.
     *
     * <p>Nothing is captured, injected or answered unless this is {@code true}.
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * Active learnings one repository may hold ({@value #MIN_MAX_PER_REPO}–{@value
     * #MAX_MAX_PER_REPO}). At the cap a new learning is refused, not evicted; two captures racing
     * at the cap can each add one.
     *
     * <p>The refusal is logged; a maintainer frees room with {@code /forget}.
     */
    @WithName("max-per-repo")
    @WithDefault("100")
    int maxPerRepo();

    /**
     * Most learnings in one review prompt ({@value #MIN_PROMPT_MAX_ITEMS}–{@value
     * #MAX_PROMPT_MAX_ITEMS}).
     */
    @WithName("prompt-max-items")
    @WithDefault("10")
    int promptMaxItems();

    /**
     * Character cap on the learnings section ({@value #MIN_PROMPT_MAX_CHARS}–{@value
     * #MAX_PROMPT_MAX_CHARS}).
     *
     * <p>Applied before fencing. Counted in the planner's shared prompt overhead like every other
     * non-diff section.
     */
    @WithName("prompt-max-chars")
    @WithDefault("3000")
    int promptMaxChars();
  }

  /**
   * Opt-in delta counts in the follow-up review outcome. A pass that raises or closes findings
   * includes new, resolved and still-open counts alongside the actual verdict, naming each closed
   * finding once. No separate delta comment is posted. Off by default; first reviews, a successful
   * explicit summary command and rounds without movement add no section.
   */
  interface FollowUpSummaryConfig {
    /**
     * Include new, resolved and still-open counts in the follow-up review outcome, naming closed
     * findings once. No separate delta comment; nothing is added when no finding is raised or
     * closed.
     */
    @WithDefault("false")
    boolean enabled();
  }

  /**
   * Opt-in Mermaid control-flow diagram in the PR summary. When {@link #enabled()} the model is
   * asked to emit a small Mermaid diagram of the affected control flow for non-trivial changes,
   * which the summary comment renders as a collapsible block (GitHub renders Mermaid natively). It
   * rides the existing review call, so it costs only a few extra output tokens.
   */
  interface DiagramConfig {
    /** Add a Mermaid control-flow diagram to the summary. */
    @WithDefault("false")
    boolean enabled();
  }

  /**
   * Opt-in context-aware PR labelling. When {@link #enabled()} the model is shown the repository's
   * existing labels and asked which best describe the change; the result is either applied to the
   * PR or posted as a suggestion comment.
   */
  interface LabelsConfig {
    /**
     * Suggest PR labels. See <a href="https://github.com/devops-thiago/ThrillhouseBot#pr-labels">PR
     * labels</a>.
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * Add the labels to the PR instead of suggesting them in a comment.
     *
     * <p>When {@code false}, they are only posted as a suggestion comment on the first review.
     */
    @WithDefault("false")
    boolean apply();

    /**
     * Allow creating a suggested label that does not exist yet.
     *
     * <p>Off by default so the bot never invents labels.
     */
    @WithName("allow-create")
    @WithDefault("false")
    boolean allowCreate();

    /** Most labels applied or suggested per PR. */
    @WithName("max-labels")
    @WithDefault("3")
    int maxLabels();
  }

  interface DashboardConfig {
    /** Public dashboard URL, used for the OAuth callback and the session links posted to GitHub. */
    @WithName("url")
    @WithDefault("http://localhost:8080")
    String url();

    /**
     * OAuth client ID for dashboard login, from the GitHub App's settings. Leave it and {@code
     * GITHUB_CLIENT_SECRET} unset to disable login.
     */
    @WithName("github.client-id")
    Optional<String> clientId();

    /** OAuth client secret for dashboard login, from the GitHub App's settings. */
    @WithName("github.client-secret")
    Optional<String> clientSecret();

    /** OAuth callback URL registered on the GitHub App; follows {@code DASHBOARD_URL}. */
    @WithName("github.redirect-uri")
    @WithDefault("http://localhost:8080/api/auth/callback")
    String redirectUri();

    /** Base URL of GitHub's OAuth endpoints. */
    @WithName("github.oauth-url")
    @WithDefault("https://github.com/login/oauth")
    String oauthUrl();

    /**
     * Login of the account that owns the GitHub App. The dashboard shows that account every
     * installed repository and limits everyone else to the repositories they collaborate on. Unset
     * resolves it from the App ({@code GET /app}).
     */
    @WithName("github.account-owner")
    Optional<String> accountOwner();
  }

  /**
   * Outgoing review-outcome notifications (#73). Everything here is off until an operator sets
   * {@link OutgoingWebhookConfig#url()}.
   */
  interface NotificationsConfig {
    OutgoingWebhookConfig webhook();

    /**
     * One outgoing webhook fired when a review reaches a final outcome. The payload carries
     * metadata only — repository, pull request number, head commit, verdict, finding counts,
     * failure category, dashboard link — unless {@link #includeContent()} opts in to the PR title
     * and finding titles. Validated at boot by {@link StartupConfigValidator}.
     */
    interface OutgoingWebhookConfig {
      /** Formats accepted by {@link #format()}. */
      List<String> ALLOWED_FORMATS = List.of("json", "slack", "discord");

      /** Events accepted by {@link #events()}. */
      List<String> ALLOWED_EVENTS = List.of("completed", "failed");

      /**
       * Upper bound on {@link #maxAttempts()}, so a dead receiver cannot hold a thread for long.
       */
      int MAX_ATTEMPTS_CEILING = 5;

      /** Upper bound on {@link #timeout()} for the same reason. */
      Duration MAX_TIMEOUT = Duration.ofSeconds(60);

      /**
       * Receiver of review-outcome notifications; unset turns them off. Must be {@code https://}.
       * Treated as a secret and logged only as scheme, host and port. See <a
       * href="https://github.com/devops-thiago/ThrillhouseBot#outgoing-notifications">Outgoing
       * notifications</a>.
       *
       * <p>Slack and Discord incoming-webhook URLs carry their credential in the path (see {@code
       * NotificationSettings#redactedUrl()}).
       */
      Optional<String> url();

      /**
       * Body format: {@code json} (the structured payload), {@code slack} or {@code discord} (an
       * incoming-webhook message for that chat service).
       */
      @WithDefault("json")
      String format();

      /**
       * HMAC-SHA256 key; when set, each request carries {@code X-Thrillhousebot-Signature-256:
       * sha256=<hex>} computed over the exact request body.
       */
      Optional<String> secret();

      /** Comma-separated outcomes to send: {@code completed}, {@code failed}. */
      @WithDefault("completed,failed")
      List<String> events();

      /**
       * Also send the PR title and each finding's severity, file, line and title.
       *
       * <p>Off by default so nothing the model wrote, and nothing from the PR itself, leaves the
       * process.
       */
      @WithName("include-content")
      @WithDefault("false")
      boolean includeContent();

      /**
       * Accept a plain {@code http://} URL, for local testing.
       *
       * <p>Off by default so the payload and its signature never travel unencrypted by accident.
       */
      @WithName("allow-http")
      @WithDefault("false")
      boolean allowHttp();

      /** Per-attempt timeout (at most {@code 60s}). */
      @WithDefault("10s")
      Duration timeout();

      /**
       * Attempts per notification (1–{@value #MAX_ATTEMPTS_CEILING}). A timeout, connection
       * failure, {@code 429} or {@code 5xx} is retried after 1, 2, 4 and 8 seconds, then dropped
       * with a WARN.
       */
      @WithName("max-attempts")
      @WithDefault("3")
      int maxAttempts();
    }
  }

  interface AiPricingConfig {
    /**
     * The AI base URL, read from {@code AI_BASE_URL}; used to derive the telemetry provider when
     * {@code AI_PROVIDER} is unset. Set {@code AI_BASE_URL} rather than this key.
     */
    @WithName("base-url")
    String baseUrl();

    /**
     * Provider label for telemetry ({@code gen_ai.provider.name}); derived from {@code AI_BASE_URL}
     * when unset.
     */
    @WithName("provider-name")
    Optional<String> providerName();

    /**
     * Most model calls in flight at once across the process (review batches, summaries, the
     * verifier, replies and commands). A call past it waits for a slot, up to {@code
     * THRILLHOUSEBOT_REVIEW_AI_TIMEOUT_SECONDS}, and a wait over 5 seconds is logged at INFO. A
     * provider's rate-limit or concurrent-slot refusal is retried after 30 seconds either way.
     * {@code 0} means no limit.
     *
     * <p>#838: a negative value is rejected at boot. A call past the ceiling waits instead of
     * reaching a provider that refuses requests past its own concurrency limit.
     */
    @WithDefault("0")
    @WithName("max-concurrent-calls")
    int maxConcurrentCalls();

    ReasoningConfig reasoning();

    /**
     * Prices used for cost tracking, keyed by model name (the {@code AI_MODEL} value) like the
     * per-model settings. {@code application.properties} ships prices for the models it knows. When
     * you change {@code AI_MODEL}, add a matching pair; without one, tokens are still recorded and
     * the model's sessions are flagged "no pricing" in the dashboard instead of showing {@code $0}.
     */
    Map<String, ModelPricing> pricing();

    /**
     * Model-specific settings, keyed by the model name (the {@code AI_MODEL} value). Only the
     * active model's entry is read, so you can keep entries for several models and switch {@code
     * AI_MODEL} freely. Quote a model name that contains {@code .} or {@code /} ({@code
     * thrillhousebot.ai.models."gpt-5.5".max-input-tokens}). From the environment, hyphen-only
     * names use underscores ({@code
     * THRILLHOUSEBOT_AI_MODELS_DEEPSEEK_V4_PRO_MAX_INPUT_TOKENS=1000000}) and dotted names the
     * quoted form ({@code THRILLHOUSEBOT_AI_MODELS__GPT_5_5__MAX_INPUT_TOKENS=256000}); either
     * needs an empty {@code max-input-tokens=} line for that model in {@code
     * application.properties}, which ships one for the models it knows. See <a
     * href="https://github.com/devops-thiago/ThrillhouseBot#per-model-ai-settings">Per-model AI
     * settings</a>.
     *
     * <p>Mirrors the {@link #pricing()} key scheme. All values are optional (an absent value falls
     * back as documented on each key), so a model needs an entry only for the values that differ
     * from the defaults. Resolution for the active model lives in {@link ActiveModelSettings}.
     */
    Map<String, ModelSettings> models();

    /**
     * Reasoning-effort control for reasoning-capable models. Off by default so no reasoning
     * parameter is sent and today's provider-default behavior is preserved; when {@link #enabled()}
     * the configured {@link #effort()} is sent as the OpenAI-compatible {@code reasoning_effort} on
     * every chat call, which providers map to their thinking budgets. Reasoning tokens are billed
     * as output tokens, so this is the operator's cost/quality dial.
     *
     * <p>The concise lane (summary, verifier, replies) has its own {@link #conciseEffort()} rather
     * than following {@link #effort()} — see that method for why.
     */
    interface ReasoningConfig {
      /**
       * The tier that asks the model not to reason at all. Named because it is also the value one
       * call steps down to on its own when the reasoning tail spends the whole output allowance
       * (#839), so the step-down and the configured value can never disagree on the spelling.
       */
      String EFFORT_NONE = "none";

      /** Effort values accepted by {@link #effort()}, in ascending cost/quality order. */
      List<String> ALLOWED_EFFORTS = List.of(EFFORT_NONE, "low", "medium", "high", "xhigh", "max");

      /**
       * Ceiling applied to the concise lane when {@link #conciseEffort()} is unset. {@code low} and
       * not {@code none}: {@code none} is not a tier every provider recognizes (an unknown tier is
       * rejected outright, not downgraded), whereas {@code low} is the value this project already
       * ships as the {@link #effort()} default, so it is the lowest tier known to be valid wherever
       * reasoning is enabled at all.
       */
      String DEFAULT_CONCISE_EFFORT = "low";

      /**
       * Send a reasoning-effort hint ({@code reasoning_effort}). When {@code false}, no reasoning
       * parameter is sent and the provider default applies, except on the one repeat of a call that
       * spent its whole output cap reasoning, which sends the lane's effort.
       */
      @WithDefault("false")
      boolean enabled();

      /**
       * Effort for the review and the on-demand commands: {@code none}, {@code low}, {@code
       * medium}, {@code high}, {@code xhigh} or {@code max}. Reasoning tokens are billed as output.
       * A provider that does not know a tier rejects the call. See <a
       * href="https://github.com/devops-thiago/ThrillhouseBot#per-model-ai-settings">Per-model AI
       * settings</a>.
       *
       * <p>{@code none} explicitly asks the model not to reason, which pins down a
       * reasoning-capable model that reasons by default; {@code xhigh} and {@code max} are the
       * extended tiers newer reasoning models expose above {@code high}. The value rides the wire
       * verbatim. Case-insensitive; validated at boot by {@link StartupConfigValidator}.
       */
      @WithDefault("low")
      String effort();

      /**
       * Effort for the calls on the {@code concise} model (summary, verifier, replies), which do
       * not follow {@code AI_REASONING_EFFORT}. Same values. Unset means {@code low}, or {@code
       * AI_REASONING_EFFORT} when that is lower.
       *
       * <p>Unset resolves through {@link #resolveConciseEffort}.
       *
       * <p>This lane exists as a separate knob because it must NOT follow {@link #effort()} (#567).
       * Reasoning tokens are billed as output and count against the response cap, so at a high
       * effort the verifier can spend its whole allowance reasoning and return no content at all —
       * observed intermittently at {@code max} for the same finding count that succeeded on another
       * run, which makes it a variable-length reasoning tail rather than a size threshold that a
       * bigger {@code REVIEW_CONCISE_MAX_OUTPUT_TOKENS} could clear. The lane's calls are
       * fixed-shape mechanical work — one verdict per finding, a summary object, a status list — so
       * capping their effort costs nothing the review itself needs.
       */
      @WithName("concise-effort")
      Optional<String> conciseEffort();

      /** An {@link #effort()} value normalized to the lowercase wire value providers expect. */
      static String normalize(String effort) {
        return effort.strip().toLowerCase(java.util.Locale.ROOT);
      }

      /**
       * The wire value the concise lane sends while {@link #enabled()}: {@link #conciseEffort()}
       * verbatim when the operator set one, otherwise {@link #DEFAULT_CONCISE_EFFORT} — lowered to
       * {@link #effort()} when the active lane is configured below that ceiling, so an operator who
       * asked for {@code none} everywhere never gets more reasoning on the concise lane than on the
       * main one. An unrecognized {@link #effort()} is left alone here; {@link
       * StartupConfigValidator} refuses the boot over it.
       */
      static String resolveConciseEffort(ReasoningConfig reasoning) {
        var explicit = reasoning.conciseEffort().map(ReasoningConfig::normalize);
        if (explicit.isPresent()) {
          return explicit.get();
        }
        var active = ALLOWED_EFFORTS.indexOf(normalize(reasoning.effort()));
        return active >= 0 && active < ALLOWED_EFFORTS.indexOf(DEFAULT_CONCISE_EFFORT)
            ? ALLOWED_EFFORTS.get(active)
            : DEFAULT_CONCISE_EFFORT;
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
       * Input-token cap for this model. The effective review budget is the smaller of {@code
       * REVIEW_MAX_INPUT_TOKENS} and this cap; a model without one gets {@value
       * #DEFAULT_MAX_INPUT_TOKENS}. It is a cap, not the budget: to go beyond it on a large-context
       * model, raise both. Startup logs a warning when the cap lowers the budget.
       *
       * <p>So a raised global budget can never overshoot a model's real window unless the operator
       * raises the cap too.
       */
      @WithName("max-input-tokens")
      Optional<Integer> maxInputTokens();

      /**
       * The model's total context window. On a shared window the prompt and the completion are both
       * charged to it, so boot fails when {@code max-input-tokens + max-output-tokens} does not fit
       * inside it, or when the effective budget plus the largest response cap in play ({@code
       * max-output-tokens} or {@code REVIEW_CONCISE_MAX_OUTPUT_TOKENS}) does not. Unset, the
       * ceiling is not checked.
       *
       * <p>On a shared window ({@link #separateOutputBudget()} off) {@link #maxInputTokens()} plus
       * {@link #maxOutputTokens()} must fit inside it or every call is rejected as too long no
       * matter how well the prompt was budgeted; {@link StartupConfigValidator} refuses such a boot
       * (#562).
       *
       * <p>Absent means "not declared", and the ceiling is simply not checked — a model with no
       * entry keeps exactly the previous behaviour. Distinct from {@link #maxInputTokens()} on
       * purpose: that is how much of the window the review may spend on input, this is how much
       * window there is.
       */
      @WithName("context-tokens")
      Optional<Integer> contextTokens();

      /**
       * This model's {@code REVIEW_OUTPUT_BUFFER_TOKENS}: tokens reserved from the input budget for
       * the response. On a shared window it must be at least {@code max-output-tokens}, or boot
       * fails. Ignored with {@code separate-output-budget} set.
       */
      @WithName("output-buffer-tokens")
      Optional<Integer> outputBufferTokens();

      /**
       * This model's {@code REVIEW_TOKEN_SAFETY_MARGIN}: the fraction of the input budget actually
       * used, to absorb token-estimate error.
       *
       * <p>The error varies by model family; the estimator BPE is cl100k_base.
       */
      @WithName("token-safety-margin")
      Optional<Double> tokenSafetyMargin();

      /**
       * Sampling temperature sent on every chat call, in {@code [0, 2]}. Unset keeps the default
       * (1.0).
       */
      Optional<Double> temperature();

      /**
       * Nucleus-sampling {@code top_p} sent on every chat call, in {@code (0, 1]}. Unset keeps the
       * default (1.0). {@code top_k} is not part of the OpenAI-compatible API, so it cannot be set.
       */
      @WithName("top-p")
      Optional<Double> topP();

      /**
       * Response cap ({@code max_tokens}) sent on the review call and the command generators.
       * {@code output-buffer-tokens} only reserves room in the input budget, so set both when you
       * cap output. Unset leaves the provider default.
       *
       * <p>The concise lane has its own cap, {@code REVIEW_CONCISE_MAX_OUTPUT_TOKENS}.
       */
      @WithName("max-output-tokens")
      Optional<Integer> maxOutputTokens();

      /**
       * Set {@code true} only when the provider bills the response outside the context window (1M
       * in with 384K out on top, rather than 384K carved out of the 1M). It switches off both the
       * {@code output-buffer-tokens} reservation and the {@code context-tokens} ceiling. Getting it
       * wrong is costly either way; check the provider's documented window first.
       *
       * <p>The default ({@code false}) is the classic OpenAI-style contract: prompt and completion
       * share one window, so tokens spent on the response are tokens unavailable to the diff. The
       * budgeter reserves {@link #outputBufferTokens()} out of the input budget accordingly, and
       * {@code max-output-tokens} may not exceed that reservation — licensing more output than was
       * reserved is how a call overruns the window.
       *
       * <p>Set {@code true} for a model that publishes an input window and a response allowance
       * that do not compete (e.g. 1M in with 384K out <em>on top</em>). Both of those rules are
       * then wrong rather than merely conservative: the reservation would take budget from the diff
       * to protect a pool the response never draws from, and the ceiling would make the model's
       * real output allowance unconfigurable. So the budgeter stops subtracting and {@link
       * StartupConfigValidator} stops requiring the buffer to cover the cap.
       *
       * <p>Deliberately explicit rather than inferred from a model declaring both caps: getting
       * this wrong silently picks the wrong arithmetic, and an operator should be able to read
       * which contract a model is on straight from its config. Claiming it for a model whose
       * provider actually shares the window is the expensive direction — the caps stop being held
       * to {@link #contextTokens()}, and the provider rejects every call instead (#562).
       */
      @WithName("separate-output-budget")
      Optional<Boolean> separateOutputBudget();

      /**
       * {@code frequency_penalty} sent on every chat call, in {@code [-2, 2]}. Positive values
       * discourage repeating tokens in proportion to how often they already appeared. Unset keeps
       * the provider default.
       */
      @WithName("frequency-penalty")
      Optional<Double> frequencyPenalty();

      /**
       * {@code presence_penalty} sent on every chat call, in {@code [-2, 2]}. Positive values
       * discourage repeating any token that already appeared. Unset keeps the provider default.
       */
      @WithName("presence-penalty")
      Optional<Double> presencePenalty();

      /**
       * {@code seed} sent on every chat call, asking for repeatable sampling on providers that
       * support it; others ignore it. For steadier reviews, lower {@code temperature} first. Unset
       * sends no seed.
       */
      Optional<Integer> seed();
    }

    interface ModelPricing {
      /** Price of 1,000 input tokens, in US dollars. */
      @WithName("input-per-1k")
      double inputPer1k();

      /** Price of 1,000 output tokens, in US dollars. */
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
