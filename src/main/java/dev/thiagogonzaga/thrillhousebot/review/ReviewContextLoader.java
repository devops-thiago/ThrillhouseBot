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

import dev.thiagogonzaga.thrillhousebot.config.ActiveModelSettings;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSessionPersistence;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubLabelClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.ProjectStackResolver;
import dev.thiagogonzaga.thrillhousebot.github.RepoSettings;
import dev.thiagogonzaga.thrillhousebot.github.RepoSettingsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Loads everything a review reads from GitHub and persistence before the AI is called — the diff,
 * base comparison, prior reviews/comments, persisted prior findings, repository instructions,
 * existing labels, project stack, the definition sites of the config keys the PR's documentation
 * names, and the patch coverage of the diff — and computes the first-visible / has-context signals.
 * Extracted from {@code ReviewOrchestrator} as the read side of the pipeline; every fetch fails
 * soft exactly as before, except the PR-files fetch whose failure must reach the caller.
 *
 * <p>When token budgeting is on ({@code max-input-tokens > 0}), the legacy line-capped mega-diff
 * and base comparison are not loaded: {@link DiffBudgetPlanner} owns what the model sees and shared
 * prompt overhead excludes the uncapped base comparison that multi-call batches drop anyway.
 */
@ApplicationScoped
public class ReviewContextLoader {

  private static final String ACCEPT = "application/vnd.github+json";

  private final GitHubPullRequestClient prClient;
  private final GitHubReviewClient reviewClient;
  private final GitHubCommentClient commentClient;
  private final InstructionsResolver instructionsResolver;
  private final RepoSettingsResolver repoSettingsResolver;
  private final ProjectStackResolver projectStackResolver;
  private final ReviewDiffFormatter diffFormatter;
  private final PrLabeler labeler;
  private final FollowUpAnalyzer followUpAnalyzer;
  private final BugFixContextResolver bugFixContextResolver;
  private final ConfigKeyContextResolver configKeyContextResolver;
  private final PatchCoverageResolver patchCoverageResolver;
  private final ReviewSessionPersistence sessionPersistence;
  private final BotIdentity botIdentity;
  private final ActiveModelSettings activeModel;

  @Inject
  public ReviewContextLoader(
      @RestClient GitHubPullRequestClient prClient,
      @RestClient GitHubReviewClient reviewClient,
      @RestClient GitHubCommentClient commentClient,
      InstructionsResolver instructionsResolver,
      RepoSettingsResolver repoSettingsResolver,
      ProjectStackResolver projectStackResolver,
      ReviewDiffFormatter diffFormatter,
      PrLabeler labeler,
      FollowUpAnalyzer followUpAnalyzer,
      BugFixContextResolver bugFixContextResolver,
      ConfigKeyContextResolver configKeyContextResolver,
      PatchCoverageResolver patchCoverageResolver,
      ReviewSessionPersistence sessionPersistence,
      BotIdentity botIdentity,
      ActiveModelSettings activeModel) {
    this.prClient = prClient;
    this.reviewClient = reviewClient;
    this.commentClient = commentClient;
    this.instructionsResolver = instructionsResolver;
    this.repoSettingsResolver = repoSettingsResolver;
    this.projectStackResolver = projectStackResolver;
    this.diffFormatter = diffFormatter;
    this.labeler = labeler;
    this.followUpAnalyzer = followUpAnalyzer;
    this.bugFixContextResolver = bugFixContextResolver;
    this.configKeyContextResolver = configKeyContextResolver;
    this.patchCoverageResolver = patchCoverageResolver;
    this.sessionPersistence = sessionPersistence;
    this.botIdentity = botIdentity;
    this.activeModel = activeModel;
  }

  /**
   * GitHub's authoritative PR-level file/line totals, read from the pulls endpoint. Preferred over
   * the ignore-glob-filtered diff counts for the summary's "Changes Overview"; {@code null} when
   * the totals could not be fetched, in which case the summary falls back to the diff-derived
   * counts.
   */
  public record PrTotals(int filesChanged, int additions, int deletions) {}

  /** Everything the review pipeline reads before the model call, loaded once up front. */
  public record ReviewContext(
      List<GitHubPullRequestClient.FileDiff> files,
      String diff,
      String baseComparison,
      int omittedFiles,
      List<GitHubReviewClient.ReviewResponse> priorReviews,
      List<String> priorAiResponseJsons,
      List<ReviewResponse> priorAiResponses,
      boolean isFirstVisibleReview,
      boolean hasContext,
      String previousAiResponseJson,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      String previousFindings,
      InstructionsResolver.ResolvedInstructions instructions,
      PathScopedInstructions pathInstructions,
      List<GitHubLabelClient.Label> repoLabels,
      String projectStack,
      String linkedIssuesContext,
      String configKeyContext,
      String patchCoverage,
      List<GitHubPullRequestClient.FileDiff> reviewableFiles,
      Supplier<DiffLineResolver> lineResolverSupplier,
      PrTotals prTotals,
      List<GitHubCommentClient.IssueComment> conversationComments,
      List<String> unmatchedIgnoreGlobs) {
    public ReviewContext {
      files = List.copyOf(files);
      priorReviews = List.copyOf(priorReviews);
      priorAiResponseJsons = List.copyOf(priorAiResponseJsons);
      priorAiResponses = List.copyOf(priorAiResponses);
      inlineComments = List.copyOf(inlineComments);
      repoLabels = List.copyOf(repoLabels);
      reviewableFiles = List.copyOf(reviewableFiles);
      conversationComments = List.copyOf(conversationComments);
      unmatchedIgnoreGlobs = List.copyOf(unmatchedIgnoreGlobs);
    }

    /**
     * Back-compat constructor for callers that carry no unmatched-glob disclosure. Defaults it to
     * empty, which reads as "every declared ignore glob matched something" — the quiet direction,
     * since a disclosure nobody computed must never be invented.
     */
    @SuppressWarnings("java:S107")
    public ReviewContext(
        List<GitHubPullRequestClient.FileDiff> files,
        String diff,
        String baseComparison,
        int omittedFiles,
        List<GitHubReviewClient.ReviewResponse> priorReviews,
        List<String> priorAiResponseJsons,
        List<ReviewResponse> priorAiResponses,
        boolean isFirstVisibleReview,
        boolean hasContext,
        String previousAiResponseJson,
        List<GitHubReviewClient.PullRequestComment> inlineComments,
        String previousFindings,
        InstructionsResolver.ResolvedInstructions instructions,
        PathScopedInstructions pathInstructions,
        List<GitHubLabelClient.Label> repoLabels,
        String projectStack,
        String linkedIssuesContext,
        String configKeyContext,
        String patchCoverage,
        List<GitHubPullRequestClient.FileDiff> reviewableFiles,
        Supplier<DiffLineResolver> lineResolverSupplier,
        PrTotals prTotals,
        List<GitHubCommentClient.IssueComment> conversationComments) {
      this(
          files,
          diff,
          baseComparison,
          omittedFiles,
          priorReviews,
          priorAiResponseJsons,
          priorAiResponses,
          isFirstVisibleReview,
          hasContext,
          previousAiResponseJson,
          inlineComments,
          previousFindings,
          instructions,
          pathInstructions,
          repoLabels,
          projectStack,
          linkedIssuesContext,
          configKeyContext,
          patchCoverage,
          reviewableFiles,
          lineResolverSupplier,
          prTotals,
          conversationComments,
          List.of());
    }

    /**
     * Back-compat constructor for callers that carry no PR conversation comments. Defaults them to
     * empty, which reads as "no maintainer cleared anything from the conversation" — the safe
     * direction, since a missing list must never clear a hold.
     */
    @SuppressWarnings("java:S107")
    public ReviewContext(
        List<GitHubPullRequestClient.FileDiff> files,
        String diff,
        String baseComparison,
        int omittedFiles,
        List<GitHubReviewClient.ReviewResponse> priorReviews,
        List<String> priorAiResponseJsons,
        List<ReviewResponse> priorAiResponses,
        boolean isFirstVisibleReview,
        boolean hasContext,
        String previousAiResponseJson,
        List<GitHubReviewClient.PullRequestComment> inlineComments,
        String previousFindings,
        InstructionsResolver.ResolvedInstructions instructions,
        PathScopedInstructions pathInstructions,
        List<GitHubLabelClient.Label> repoLabels,
        String projectStack,
        String linkedIssuesContext,
        String configKeyContext,
        String patchCoverage,
        List<GitHubPullRequestClient.FileDiff> reviewableFiles,
        Supplier<DiffLineResolver> lineResolverSupplier,
        PrTotals prTotals) {
      this(
          files,
          diff,
          baseComparison,
          omittedFiles,
          priorReviews,
          priorAiResponseJsons,
          priorAiResponses,
          isFirstVisibleReview,
          hasContext,
          previousAiResponseJson,
          inlineComments,
          previousFindings,
          instructions,
          pathInstructions,
          repoLabels,
          projectStack,
          linkedIssuesContext,
          configKeyContext,
          patchCoverage,
          reviewableFiles,
          lineResolverSupplier,
          prTotals,
          List.of());
    }

    /**
     * Memoized {@link DiffLineResolver} for this review — one construction shared by the finding
     * pipeline, approve backstop, and {@code postReview}.
     */
    public DiffLineResolver lineResolver() {
      return lineResolverSupplier.get();
    }

    /**
     * The prior findings this round reports on — the newest prior AI response that actually raised
     * findings, so a round that legitimately found nothing does not evict the still-open set or its
     * ids ({@link FollowUpAnalyzer#effectivePreviousFindings}). Empty on a first review, when every
     * prior JSON was missing/unparseable, or when no prior round found anything. Parsed once in
     * {@link #load}.
     */
    public List<ReviewResponse.Finding> previousFindingsList() {
      return FollowUpAnalyzer.effectivePreviousFindings(priorAiResponses);
    }
  }

  /**
   * Loads the full read-side context for a review. Mirrors the prior inline sequence: diff + base
   * comparison, prior reviews, persisted prior AI responses, the first-visible / has-context
   * signals, inline comments and previous-findings context (only when prior responses exist),
   * repository instructions, existing labels, and project stack.
   *
   * <p>With token budgeting enabled, the line-capped mega-diff and base comparison are skipped
   * ({@code diff} and {@code baseComparison} are empty; line-path {@code omittedFiles} is 0);
   * {@link DiffBudgetPlanner} is authoritative for what the model sees.
   */
  ReviewContext load(
      String auth, ReviewOrchestrator.ReviewRequest req, ReviewSession session, String repository) {
    var files = fetchPrFiles(auth, req.owner(), req.repo(), req.prNumber());
    var prTotals = fetchPrTotalsForReview(auth, req);
    // One read of the repository's own settings for the whole review: the ignore globs below and
    // the path-scoped review rules further down are both derived from it.
    var repoSettings = resolveRepoSettings(req);
    // Global ∪ per-repo ignore globs, compiled once so the ignore filter is still walked a single
    // time per review; a repo that declares nothing resolves straight back to the global set.
    var ignoreGlobs = diffFormatter.ignoreGlobs(repoSettings.ignoredFiles());
    var reviewableFiles = diffFormatter.reviewableFiles(files, ignoreGlobs);
    // Which of the repository's own globs excluded nothing here — a declaration that quietly does
    // nothing is disclosed in the summary rather than left to be discovered by a review that
    // should not have seen the file (#481). Computed against the whole file list, before the
    // filter, and only when the repository declared something of its own.
    var unmatchedIgnoreGlobs =
        ReviewDiffFormatter.unmatchedPatterns(
            repoSettings.ignoredFiles(),
            files.stream().map(GitHubPullRequestClient.FileDiff::filename).toList());
    // Which declared scopes govern which files, resolved once against the post-ignore-filter list:
    // rules never apply to a file the ignore set already took out of review scope, and no later
    // stage re-walks a glob per finding.
    var pathInstructions =
        PathScopedInstructions.resolve(
            repoSettings,
            reviewableFiles.stream().map(GitHubPullRequestClient.FileDiff::filename).toList());
    var tokenBudgeted = activeModel.maxInputTokens() > 0;
    var diffResult =
        tokenBudgeted
            ? new ReviewDiffFormatter.FormattedDiff("", 0)
            : diffFormatter.buildDiffStringWithStats(files, reviewableFiles);
    var baseComparisonResult =
        tokenBudgeted
            ? new ReviewDiffFormatter.FormattedDiff("", 0)
            : buildBaseComparisonWithStats(
                auth, req.owner(), req.repo(), req.baseSha(), req.commitSha(), true, ignoreGlobs);
    var omittedFiles = diffResult.omittedFiles();
    // One DiffLineResolver per review, shared by the finding pipeline / backstop / postReview.
    // Memoized so a no-context path that never touches it (e.g. VerdictBuilder when hasContext is
    // false) does not pay for a full patch parse.
    var patchesByFile = diffFormatter.patchesByReviewableFiles(reviewableFiles);
    Supplier<DiffLineResolver> lineResolverSupplier =
        memoize(() -> new DiffLineResolver(patchesByFile));

    var priorReviews = fetchPriorReviews(auth, req.owner(), req.repo(), req.prNumber());
    // isFirstVisibleReview keys off the summary comment directly, not reviews alone: a first
    // round held back only by pending CI posts the summary comment but no review.
    List<String> priorAiResponseJsons =
        sessionPersistence.findAllPriorAiResponseJsons(repository, req.prNumber(), session.id);
    var isFirstVisibleReview =
        priorReviews.stream()
                .noneMatch(r -> r.user() != null && botIdentity.matches(r.user().login()))
            && !botSummaryCommentExists(auth, req.owner(), req.repo(), req.prNumber());
    var hasContext = !priorAiResponseJsons.isEmpty();
    // Deserialize each prior response once for the whole review — context formatting, unresolved
    // gate, approve backstop, and later thread matching all reuse these objects.
    List<ReviewResponse> priorAiResponses =
        followUpAnalyzer.parsePreviousResponses(priorAiResponseJsons);
    // The round this review reports on is the newest prior round that actually raised findings: a
    // round that legitimately found nothing exposes no ids, and treating it as "the previous round"
    // dropped the still-open finding out of the prompt and out of every id-keyed consumer (#455).
    // Everything derived from it — the rendered context, the id space, the raw JSON the supersede
    // pass re-reads — is taken from that one round, so the three cannot drift apart. When no round
    // raised anything there is nothing to skip past, and the newest round keeps both roles exactly
    // as before.
    var previousRoundIndex =
        Math.max(FollowUpAnalyzer.effectivePreviousRoundIndex(priorAiResponses), 0);
    String previousAiResponseJson =
        priorAiResponseJsons.isEmpty() ? null : priorAiResponseJsons.get(previousRoundIndex);
    List<ReviewResponse> olderAiResponses =
        previousRoundIndex + 1 < priorAiResponses.size()
            ? priorAiResponses.subList(previousRoundIndex + 1, priorAiResponses.size())
            : List.of();
    List<GitHubReviewClient.PullRequestComment> inlineComments =
        hasContext
            ? fetchPullRequestComments(auth, req.owner(), req.repo(), req.prNumber())
            : List.of();
    // The PR conversation carries the only escape hatch a thread-less finding has: an
    // "@thrillhousebot resolved" comment naming it (#548). Fetched on the same gate as the inline
    // comments — a first review has no prior finding to clear, so it never pays for the call — and
    // paginated, with the read ceiling disclosed rather than silently truncating the conversation.
    List<GitHubCommentClient.IssueComment> conversationComments =
        hasContext
            ? fetchConversationComments(auth, req.owner(), req.repo(), req.prNumber())
            : List.of();
    List<ReviewResponse.Finding> previousFindingsSource =
        FollowUpAnalyzer.effectivePreviousFindings(priorAiResponses);
    // The review-body fallback is for sessions carrying no readable AI response at all. Once any
    // prior round parsed, the bot's own review prose must never stand in for structured findings.
    boolean previousResponsePersisted =
        priorAiResponses.stream().anyMatch(FollowUpAnalyzer::isPersistedResponse);
    // Findings a round newer than the effective previous round already closed are settled: not
    // re-shown to the model as still open, and never re-superseded downstream (#470).
    var settledPreviousIds = FollowUpAnalyzer.settledPreviousIds(priorAiResponses);
    String previousFindings =
        hasContext
            ? followUpAnalyzer.buildPreviousFindingsContext(
                previousFindingsSource,
                previousResponsePersisted,
                priorReviews,
                inlineComments,
                olderAiResponses,
                botIdentity,
                settledPreviousIds)
            : "";

    var instructions =
        instructionsResolver.resolve(
            req.owner(), req.repo(), req.defaultBranch(), req.installationId());

    var repoLabels = labeler.fetchExistingLabels(auth, req.owner(), req.repo());
    var projectStack = resolveProjectStack(req);
    var linkedIssuesContext =
        bugFixContextResolver.loadLinkedIssueContext(
            auth, req.owner(), req.repo(), req.prDescription());
    var configKeyContext = resolveConfigKeyContext(auth, req, reviewableFiles);
    // Reuses the repo settings and the post-ignore file list already computed above: the coverage
    // artifact name comes from the same single read, and an ignored file is never reported as
    // under-tested.
    var patchCoverage = patchCoverageResolver.resolve(auth, req, repoSettings, reviewableFiles);

    return new ReviewContext(
        files,
        diffResult.text(),
        baseComparisonResult.text(),
        omittedFiles,
        priorReviews,
        priorAiResponseJsons,
        priorAiResponses,
        isFirstVisibleReview,
        hasContext,
        previousAiResponseJson,
        inlineComments,
        previousFindings,
        instructions,
        pathInstructions,
        repoLabels,
        projectStack,
        linkedIssuesContext,
        configKeyContext,
        patchCoverage,
        reviewableFiles,
        lineResolverSupplier,
        prTotals,
        conversationComments,
        unmatchedIgnoreGlobs);
  }

  /** Thread-safe memoizing supplier — the resolver is built at most once per review context. */
  static <T> Supplier<T> memoize(Supplier<T> delegate) {
    return new Supplier<>() {
      private final Object lock = new Object();
      private boolean initialized;
      private T value;

      @Override
      public T get() {
        synchronized (lock) {
          if (!initialized) {
            value = delegate.get();
            initialized = true;
          }
          return value;
        }
      }
    };
  }

  /**
   * Manual /review triggers arrive from issue_comment webhooks, which carry no PR head/base or
   * title — fetch them so the check run gets a valid head_sha (GitHub rejects blank with 422).
   */
  ReviewOrchestrator.ReviewRequest resolveMissingPrDetails(
      String auth, ReviewOrchestrator.ReviewRequest req) {
    if (req.commitSha() != null && !req.commitSha().isBlank()) {
      return req;
    }
    var pr = prClient.getPullRequest(auth, ACCEPT, req.owner(), req.repo(), req.prNumber());
    return new ReviewOrchestrator.ReviewRequest(
        req.owner(),
        req.repo(),
        req.prNumber(),
        pr.head() != null ? pr.head().sha() : "",
        pr.title() != null ? pr.title() : req.prTitle(),
        pr.body() != null ? pr.body() : "",
        pr.base() != null ? pr.base().sha() : "",
        req.defaultBranch(),
        req.installationId(),
        req.isManualTrigger(),
        pr.base() != null ? pr.base().ref() : "",
        req.forceSummary());
  }

  /**
   * The repository's own structured settings from {@code .github/thrillhousebot.yml}, read once per
   * review and used for both the effective ignore set (deployment-wide globs ∪ the repository's
   * own, strictly additive) and its path-scoped review rules. Every failure mode (feature off, file
   * absent, YAML malformed, glob invalid) collapses back to {@link RepoSettings#EMPTY} — the global
   * ignore list and the global instructions alone — rather than failing the review.
   */
  RepoSettings resolveRepoSettings(ReviewOrchestrator.ReviewRequest req) {
    return SoftLoaders.repoSettings(
        repoSettingsResolver,
        req.owner(),
        req.repo(),
        req.defaultBranch(),
        req.installationId(),
        "review");
  }

  /**
   * Definition sites for the config keys the PR's documentation/config files name, read at the PR
   * head so a key added by this same PR resolves. Best-effort enrichment like the project stack: a
   * failure degrades to no extra context, never a failed review.
   */
  String resolveConfigKeyContext(
      String auth,
      ReviewOrchestrator.ReviewRequest req,
      List<GitHubPullRequestClient.FileDiff> reviewableFiles) {
    var ref =
        req.commitSha() != null && !req.commitSha().isBlank()
            ? req.commitSha()
            : req.defaultBranch();
    if (ref == null || ref.isBlank()) {
      return "";
    }
    try {
      return configKeyContextResolver.resolve(auth, req.owner(), req.repo(), ref, reviewableFiles);
    } catch (RuntimeException e) {
      Log.warn("Config-key context resolution failed, continuing without it", e);
      return "";
    }
  }

  /** Stack context is best-effort enrichment — its failure must never fail the review. */
  String resolveProjectStack(ReviewOrchestrator.ReviewRequest req) {
    return SoftLoaders.projectStack(
        projectStackResolver,
        req.owner(),
        req.repo(),
        req.defaultBranch(),
        req.installationId(),
        "review");
  }

  List<GitHubPullRequestClient.FileDiff> fetchPrFiles(
      String auth, String owner, String repo, int prNumber) {
    return prClient.getPullRequestFiles(auth, ACCEPT, owner, repo, prNumber);
  }

  /**
   * GitHub's authoritative PR-level file/line totals ({@code changed_files}/{@code
   * additions}/{@code deletions} on the pulls endpoint), or {@code null} when they can't be read.
   * The summary reports these rather than the ignore-glob-filtered diff counts, which undercount
   * whenever a changed file is dropped by the ignore-glob. Best-effort: a fetch failure returns
   * {@code null} so the summary falls back to the diff-derived counts rather than failing the
   * review.
   */
  PrTotals fetchPrTotals(String auth, String owner, String repo, int prNumber) {
    try {
      var pr = prClient.getPullRequest(auth, ACCEPT, owner, repo, prNumber);
      return new PrTotals(pr.changedFiles(), pr.additions(), pr.deletions());
    } catch (RuntimeException e) {
      Log.warn("Failed to fetch PR totals; summary will fall back to diff-derived counts", e);
      return null;
    }
  }

  /**
   * The PR's head SHA as GitHub reports it right now — a fresh read, taken just before the run
   * posts, so a head that moved during the minutes-long model call is caught (#704). Goes through
   * {@link GitHubPullRequestClient#getPullRequest}, so a credential that expired mid-run is healed
   * the same way every other late read is (#626/#693). Fail-open: a failed or headless read returns
   * empty, and the caller posts rather than losing a finished review to this guard.
   */
  Optional<String> currentHeadSha(String auth, ReviewOrchestrator.ReviewRequest req) {
    try {
      var pr = prClient.getPullRequest(auth, ACCEPT, req.owner(), req.repo(), req.prNumber());
      return Optional.ofNullable(pr)
          .map(GitHubPullRequestClient.PullRequestDetails::head)
          .map(GitHubPullRequestClient.Ref::sha)
          .filter(sha -> !sha.isBlank());
    } catch (RuntimeException e) {
      Log.warnf(
          e,
          "Could not re-read the head of %s/%s #%d before posting — posting against the reviewed"
              + " sha",
          req.owner(),
          req.repo(),
          req.prNumber());
      return Optional.empty();
    }
  }

  /**
   * Reads totals after the file list and rejects a review whose webhook SHA is no longer current.
   * The files endpoint is keyed only by PR number, so without this check a force-push can pair the
   * old request SHA with the new revision's files.
   */
  private PrTotals fetchPrTotalsForReview(String auth, ReviewOrchestrator.ReviewRequest req) {
    try {
      var pr = prClient.getPullRequest(auth, ACCEPT, req.owner(), req.repo(), req.prNumber());
      var currentHead = pr != null && pr.head() != null ? pr.head().sha() : null;
      if (currentHead == null
          || req.commitSha() == null
          || !currentHead.equalsIgnoreCase(req.commitSha())) {
        throw new StaleReviewException(req.commitSha(), currentHead);
      }
      return new PrTotals(pr.changedFiles(), pr.additions(), pr.deletions());
    } catch (StaleReviewException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new IllegalStateException(
          "Unable to confirm the current PR head; refusing to review potentially mixed revisions",
          e);
    }
  }

  static final class StaleReviewException extends RuntimeException {
    StaleReviewException(String expectedHead, String currentHead) {
      super(
          "PR head changed while loading review context (expected "
              + expectedHead
              + ", current "
              + currentHead
              + "); refusing to review mixed revisions");
    }
  }

  ReviewDiffFormatter.FormattedDiff buildBaseComparisonWithStats(
      String auth, String owner, String repo, String base, String head) {
    return buildBaseComparisonWithStats(auth, owner, repo, base, head, true);
  }

  /**
   * @param applyLineBudget when false (token-budgeted reviews), include every patched file; the
   *     planner counts the text in shared overhead instead of dropping files here by line count
   */
  ReviewDiffFormatter.FormattedDiff buildBaseComparisonWithStats(
      String auth, String owner, String repo, String base, String head, boolean applyLineBudget) {
    return buildBaseComparisonWithStats(
        auth, owner, repo, base, head, applyLineBudget, diffFormatter.ignoreGlobs(List.of()));
  }

  /**
   * @param ignoreGlobs the review's effective ignore set (global ∪ per-repo), so the base
   *     comparison hides exactly what the PR diff hides
   */
  ReviewDiffFormatter.FormattedDiff buildBaseComparisonWithStats(
      String auth,
      String owner,
      String repo,
      String base,
      String head,
      boolean applyLineBudget,
      ReviewDiffFormatter.IgnoreGlobs ignoreGlobs) {
    if (base == null || head == null || base.length() < 7 || head.length() < 7) {
      return new ReviewDiffFormatter.FormattedDiff(
          "(regression comparison unavailable — refs too short)", 0);
    }
    try {
      var comparison = prClient.compareCommits(auth, ACCEPT, owner, repo, base, head);
      return diffFormatter.buildBaseComparisonWithStats(
          comparison, base, head, applyLineBudget, ignoreGlobs);
    } catch (RuntimeException e) {
      Log.warn("Failed to fetch base comparison, continuing without regression context", e);
      return new ReviewDiffFormatter.FormattedDiff("(regression comparison unavailable)", 0);
    }
  }

  List<GitHubReviewClient.ReviewResponse> fetchPriorReviews(
      String auth, String owner, String repo, int prNumber) {
    try {
      return reviewClient.listReviews(auth, ACCEPT, owner, repo, prNumber);
    } catch (RuntimeException e) {
      Log.debug("No prior reviews found (this is normal for first review)", e);
      return List.of();
    }
  }

  /**
   * Whether the bot has already posted its PR summary comment on this PR. Used to suppress a
   * duplicate summary on a re-review when a prior round left a summary comment but no review (e.g.
   * a first round held back only by pending CI). Best-effort: on a fetch failure it returns {@code
   * false}, falling back to the review-based signal rather than blocking the summary.
   */
  public boolean botSummaryCommentExists(String auth, String owner, String repo, int prNumber) {
    for (var comment : fetchIssueComments(auth, owner, repo, prNumber)) {
      var user = comment.user();
      var body = comment.body();
      if (user != null && botIdentity.matches(user.login()) && isBotSummaryComment(body)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether an issue-comment body is the bot's PR summary. The heading may be preceded by the
   * truncation blockquote banner ({@link ReviewResult#truncationNotice(int)}), so a
   * starts-with-heading check alone would miss an already-posted summary on a large PR and re-post
   * it on every re-review.
   */
  static boolean isBotSummaryComment(String body) {
    return body != null
        && body.lines().anyMatch(line -> line.strip().equals(PrSummaryGenerator.SUMMARY_HEADING));
  }

  List<GitHubCommentClient.IssueComment> fetchIssueComments(
      String auth, String owner, String repo, int prNumber) {
    try {
      // listComments paginates internally and always returns a non-null list (empty when none).
      return commentClient.listComments(auth, ACCEPT, owner, repo, prNumber);
    } catch (RuntimeException e) {
      Log.debug("Could not fetch PR issue comments (continuing as if none exist)", e);
      return List.of();
    }
  }

  /**
   * Hard ceiling on how much of a PR conversation one review reads: {@link
   * GitHubCommentClient#MAX_COMMENT_PAGES} pages of {@link GitHubCommentClient#COMMENTS_PER_PAGE}.
   * The walk is deliberately bounded rather than unbounded — a runaway thread must not turn one
   * review into hundreds of API calls — so the bound is named here, disclosed in the log, and
   * documented in the README next to the directive it limits.
   */
  static final int MAX_CONVERSATION_COMMENTS =
      GitHubCommentClient.COMMENTS_PER_PAGE * GitHubCommentClient.MAX_COMMENT_PAGES;

  /**
   * Whether a conversation walk stopped at {@link #MAX_CONVERSATION_COMMENTS} rather than at the
   * end of the thread. GitHub serves issue comments oldest first and offers no reverse order on
   * this endpoint, so a capped walk keeps the <em>oldest</em> window and drops the newest — exactly
   * where a freshly written clear directive lives. Reaching the ceiling therefore has to be said
   * out loud instead of silently reading a partial conversation.
   */
  static boolean conversationWalkCapped(List<GitHubCommentClient.IssueComment> comments) {
    return comments.size() >= MAX_CONVERSATION_COMMENTS;
  }

  /**
   * The PR conversation the clear-directive scan reads (#548), oldest first. Same paginated fetch
   * as everywhere else, plus the ceiling disclosure: past it the newest comments are unread, so a
   * directive among them cannot clear a finding and the operator is told why rather than watching
   * the feature quietly do nothing.
   *
   * <p>{@link #botSummaryCommentExists} deliberately keeps the plain fetch: the summary is posted
   * on the first round, so it lives in the oldest window a capped walk retains.
   */
  List<GitHubCommentClient.IssueComment> fetchConversationComments(
      String auth, String owner, String repo, int prNumber) {
    var comments = fetchIssueComments(auth, owner, repo, prNumber);
    if (conversationWalkCapped(comments)) {
      Log.warnf(
          "PR conversation on %s/%s #%d hit the %d-comment read ceiling (%d pages of %d): comments"
              + " newer than that were not read, so an \"@thrillhousebot resolved\" directive among"
              + " them cannot clear a finding this round",
          owner,
          repo,
          prNumber,
          MAX_CONVERSATION_COMMENTS,
          GitHubCommentClient.MAX_COMMENT_PAGES,
          GitHubCommentClient.COMMENTS_PER_PAGE);
    }
    return comments;
  }

  List<GitHubReviewClient.PullRequestComment> fetchPullRequestComments(
      String auth, String owner, String repo, int prNumber) {
    try {
      // The client walks every page and never returns null.
      return reviewClient.listPullRequestComments(auth, ACCEPT, owner, repo, prNumber);
    } catch (RuntimeException e) {
      Log.warn("Failed to fetch PR inline comments, continuing without thread context", e);
      return List.of();
    }
  }
}
