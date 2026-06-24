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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSession;
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSessionPersistence;
import dev.thiagogonzaga.thrillhousebot.dashboard.SessionEventBroadcaster;
import dev.thiagogonzaga.thrillhousebot.github.*;
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewService;
import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@ApplicationScoped
public class ReviewOrchestrator {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final String CHECK_NAME = "ThrillhouseBot Review";
  private static final String ZERO_ISSUES_MESSAGE = PrSummaryGenerator.ZERO_ISSUES_MESSAGE;

  // Check run status constant used when building the completion update (CheckRunManager owns
  // create).
  private static final String CHECK_STATUS_COMPLETED = "completed";

  // Check run conclusion constants
  private static final String CONCLUSION_FAILURE = "failure";

  // PR review event types submitted via createReview.
  private static final String EVENT_APPROVE = "APPROVE";
  private static final String EVENT_REQUEST_CHANGES = "REQUEST_CHANGES";
  private static final String EVENT_COMMENT = "COMMENT";

  // CI check status shown in the no-issues review body; CI evaluation lives in CiStatusEvaluator.
  private static final String CI_PENDING = "pending";

  private final ThrillhouseConfig config;
  // The login(s) the bot posts under, resolved once from config so that summary dedup, first-review
  // detection, and follow-up matching all recognize the bot's own activity regardless of which
  // <app-slug>[bot] this deployment runs under.
  private final BotIdentity botIdentity;
  private final GitHubAuthClient authClient;

  private final GitHubReviewClient reviewClient;

  private final GitHubCommentClient commentClient;
  private final ReviewThreadService reviewThreadService;
  private final AiReviewService aiReviewService;
  private final FindingVerificationService findingVerificationService;
  private final FindingQuoteValidator quoteValidator;
  private final FindingDeduplicator deduplicator;

  private final SessionEventBroadcaster broadcaster;

  private final ReviewSessionPersistence sessionPersistence;

  private final SuggestionFormatter suggestionFormatter;
  private final PrSummaryGenerator summaryGenerator;
  private final FollowUpAnalyzer followUpAnalyzer;

  private final ReviewDiffFormatter diffFormatter;

  private final PrLabeler labeler;

  private final CiStatusEvaluator ciStatusEvaluator;

  private final CheckRunManager checkRunManager;

  private final ReviewContextLoader contextLoader;

  private final ReviewPromptAssembler promptAssembler;

  private final ObjectMapper mapper;

  /**
   * Parameter object for the {@link #review(ReviewRequest)} method.
   *
   * @param owner repository owner login
   * @param repo repository name
   * @param prNumber pull request number
   * @param commitSha HEAD commit SHA
   * @param prTitle PR title
   * @param prDescription PR body as written by the author (may be empty)
   * @param baseSha base branch SHA (for comparison)
   * @param defaultBranch repo default branch name
   * @param installationId GitHub App installation ID
   * @param isManualTrigger {@code true} when triggered by a /review command
   */
  public record ReviewRequest(
      String owner,
      String repo,
      int prNumber,
      String commitSha,
      String prTitle,
      String prDescription,
      String baseSha,
      String defaultBranch,
      long installationId,
      boolean isManualTrigger) {}

  @Inject
  public ReviewOrchestrator(
      ThrillhouseConfig config,
      GitHubAuthClient authClient,
      @RestClient GitHubReviewClient reviewClient,
      @RestClient GitHubCommentClient commentClient,
      ReviewThreadService reviewThreadService,
      AiReviewService aiReviewService,
      FindingVerificationService findingVerificationService,
      FindingQuoteValidator quoteValidator,
      FindingDeduplicator deduplicator,
      SessionEventBroadcaster broadcaster,
      ReviewSessionPersistence sessionPersistence,
      SuggestionFormatter suggestionFormatter,
      PrSummaryGenerator summaryGenerator,
      FollowUpAnalyzer followUpAnalyzer,
      ReviewDiffFormatter diffFormatter,
      PrLabeler labeler,
      CiStatusEvaluator ciStatusEvaluator,
      CheckRunManager checkRunManager,
      ReviewContextLoader contextLoader,
      ReviewPromptAssembler promptAssembler,
      ObjectMapper mapper) {
    this.config = config;
    this.botIdentity = BotIdentity.from(config.github().botLogins());
    this.authClient = authClient;
    this.reviewClient = reviewClient;
    this.commentClient = commentClient;
    this.reviewThreadService = reviewThreadService;
    this.aiReviewService = aiReviewService;
    this.findingVerificationService = findingVerificationService;
    this.quoteValidator = quoteValidator;
    this.deduplicator = deduplicator;
    this.broadcaster = broadcaster;
    this.sessionPersistence = sessionPersistence;
    this.suggestionFormatter = suggestionFormatter;
    this.summaryGenerator = summaryGenerator;
    this.followUpAnalyzer = followUpAnalyzer;
    this.diffFormatter = diffFormatter;
    this.labeler = labeler;
    this.ciStatusEvaluator = ciStatusEvaluator;
    this.checkRunManager = checkRunManager;
    this.contextLoader = contextLoader;
    this.promptAssembler = promptAssembler;
    this.mapper = mapper;
  }

  /**
   * Main entry point for reviewing a PR. Called from WebhookController for pull_request (opened,
   * synchronize) and /review triggers.
   */
  @ActivateRequestContext
  public void review(ReviewRequest request) {
    Log.infof(
        "Starting review for %s/%s #%d (sha: %s)",
        request.owner(), request.repo(), request.prNumber(), request.commitSha());
    var repository = request.owner() + "/" + request.repo();
    var auth = authClient.getAuthHeader(request.installationId());

    ReviewSession session =
        ReviewSession.create(
            repository, request.prNumber(), request.prTitle(), request.commitSha());
    sessionPersistence.create(session);
    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.started(session));

    // Resolution happens inside the try: a failing PR fetch (deleted PR, rate limit) must take
    // the same failure path as any other error — comment, failed session, broadcast.
    var req = request;
    var checkRunId = -1L;
    var resultSurfaced = false;
    try {
      var resolved = contextLoader.resolveMissingPrDetails(auth, request);
      req = resolved;
      if (resolved != request) {
        applySessionState(
            session,
            s -> {
              s.setPrTitle(resolved.prTitle());
              s.setCommitSha(resolved.commitSha());
            });
      }

      checkRunId =
          checkRunManager.createCheckRun(
              auth, req.owner(), req.repo(), req.commitSha(), sessionUrl(session));
      var ctx = contextLoader.load(auth, req, session, repository);
      var files = ctx.files();
      var diff = ctx.diff();
      var omittedFiles = ctx.omittedFiles();
      var priorReviews = ctx.priorReviews();
      var priorAiResponseJsons = ctx.priorAiResponseJsons();
      var isFirstVisibleReview = ctx.isFirstVisibleReview();
      var hasContext = ctx.hasContext();
      var previousAiResponseJson = ctx.previousAiResponseJson();
      var inlineComments = ctx.inlineComments();
      var repoLabels = ctx.repoLabels();

      var promptInputs = promptAssembler.assemble(ctx, req);
      // Quote validation runs before dedupe so a merged finding can never inherit a phantom
      // quote from one duplicate while a verbatim sibling gets discarded
      var aiResponse = aiReviewService.review(session, promptInputs);
      aiResponse = quoteValidator.validate(aiResponse, diff);
      aiResponse = deduplicator.dedupe(aiResponse);
      aiResponse =
          findingVerificationService.verify(
              aiResponse,
              promptInputs.diff(),
              promptInputs.projectStack(),
              promptInputs.previousFindings());
      aiResponse =
          followUpAnalyzer.dropRepliedDuplicates(
              aiResponse, priorAiResponseJsons, inlineComments, botIdentity);
      var lineResolver = new DiffLineResolver(diffFormatter.patchesByFile(files));
      aiResponse = populateMissingAnchors(aiResponse, lineResolver);
      persistAiResponse(session, aiResponse);

      // Fetch required status checks and evaluate CI checks on the head SHA. An absent result means
      // "could not determine required checks" — evaluateCiChecks then gates on all checks (null).
      List<String> requiredContexts =
          ciStatusEvaluator.resolveRequiredContexts(auth, req).orElse(null);
      List<ReviewResult.CiCheck> offendingCiChecks =
          ciStatusEvaluator.evaluateCiChecks(
              auth, req.owner(), req.repo(), req.commitSha(), requiredContexts);

      var reviewableFiles = diffFormatter.reviewableFiles(files);
      DiffStats diffStats = DiffStats.fromFiles(reviewableFiles, omittedFiles);
      List<PrSummaryGenerator.ChangedFile> changedFiles = toChangedFiles(reviewableFiles);
      var unresolvedPrevious =
          followUpAnalyzer.unresolvedFindings(
              previousAiResponseJson, aiResponse.previousFindingsStatus());
      // Deterministic backstop: reconstruct the bot's own prior findings the model silently dropped
      // from previous_findings_status but that are still present in this diff, as unresolved
      // statuses, so an APPROVE can never sail over them. Spans every prior round, not just the
      // newest, so a finding dropped rounds after it was raised is still caught.
      var backstopUnresolved =
          hasContext
              ? followUpAnalyzer.unreportedUnresolvedStatuses(
                  priorAiResponseJsons,
                  aiResponse.previousFindingsStatus(),
                  inlineComments,
                  lineResolver,
                  botIdentity)
              : List.<ReviewResult.PreviousFindingStatus>of();
      var result =
          buildResult(
              aiResponse,
              isFirstVisibleReview,
              diffStats,
              changedFiles,
              unresolvedPrevious,
              offendingCiChecks,
              backstopUnresolved);

      String conclusion = conclusionForResult(result);
      String checkTitle = checkTitleForResult(result);
      String checkSummary = checkSummaryForResult(result);
      checkRunManager.updateCheckRun(
          new CheckRunManager.CheckRunUpdate(
              auth,
              req.owner(),
              req.repo(),
              checkRunId,
              CHECK_STATUS_COMPLETED,
              conclusion,
              checkTitle,
              checkSummary,
              sessionUrl(session)));
      resultSurfaced = true;

      if (isFirstVisibleReview && !result.summaryMarkdown().isBlank()) {
        commentClient.createComment(
            auth,
            ACCEPT,
            req.owner(),
            req.repo(),
            req.prNumber(),
            new GitHubCommentClient.CreateCommentRequest(result.summaryMarkdown()));
      }

      dismissPendingBotReviews(auth, req.owner(), req.repo(), req.prNumber(), priorReviews);
      postReview(auth, req.owner(), req.repo(), req.prNumber(), req.commitSha(), result, files);

      resolveAddressedThreads(
          auth, req, previousAiResponseJson, inlineComments, aiResponse.previousFindingsStatus());

      labeler.applyOrSuggest(
          new PrLabeler.LabelRequest(
              auth,
              req.owner(),
              req.repo(),
              req.prNumber(),
              isFirstVisibleReview,
              aiResponse.summary() != null ? aiResponse.summary().suggestedLabels() : List.of(),
              repoLabels));

      applyReviewResult(session, result);
      broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.completed(session));

      Log.infof(
          "Review complete for %s/%s #%d: %d findings, state=%s",
          req.owner(), req.repo(), req.prNumber(), result.totalFindings(), result.reviewState());
    } catch (RuntimeException e) {
      if (resultSurfaced) {
        Log.warnf(
            e,
            "Review for %s/%s #%d was posted, but a post-result step failed",
            req.owner(),
            req.repo(),
            req.prNumber());
      } else {
        handleReviewFailure(auth, req, session, checkRunId, e);
      }
    }
  }

  void persistAiResponse(ReviewSession session, ReviewResponse aiResponse) {
    try {
      session.setAiResponseJson(mapper.writeValueAsString(aiResponse));
    } catch (JsonProcessingException e) {
      Log.warn("Failed to serialize AI response for session persistence", e);
    }
  }

  ReviewResponse populateMissingAnchors(ReviewResponse response, DiffLineResolver lineResolver) {
    if (response.findings().isEmpty()) {
      return response;
    }
    var adjusted = new ArrayList<ReviewResponse.Finding>(response.findings().size());
    var changed = false;
    for (ReviewResponse.Finding finding : response.findings()) {
      if (finding.suggestionOld() == null || finding.suggestionOld().isBlank()) {
        String fallback = lineResolver.getLineText(finding.file(), finding.line());
        if (fallback != null && !fallback.isBlank()) {
          Log.infof(
              "Populating missing content anchor for finding '%s' (%s:%d) with: '%s'",
              finding.title(), finding.file(), finding.line(), fallback.strip());
          adjusted.add(
              new ReviewResponse.Finding(
                  finding.risk(),
                  finding.confidence(),
                  finding.file(),
                  finding.line(),
                  finding.title(),
                  finding.description(),
                  fallback,
                  finding.suggestionNew()));
          changed = true;
          continue;
        }
      }
      adjusted.add(finding);
    }
    return changed
        ? new ReviewResponse(adjusted, response.previousFindingsStatus(), response.summary())
        : response;
  }

  /**
   * Public dashboard deep-link for a review session, used in check runs and comments. Built from
   * the session's random public id — never the guessable sequential id. Sessions without one
   * (pre-migration rows) link to the sessions list instead.
   */
  String sessionUrl(ReviewSession session) {
    var base = config.dashboard().url();
    if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    var publicId = session.getPublicId();
    if (publicId == null || publicId.isBlank()) {
      return base + "/dashboard/sessions/";
    }
    return base + "/session/" + publicId;
  }

  /**
   * Resolves the GitHub threads of previous findings the model judged resolved (fix landed) or
   * justified (a reply explains the deferral), so addressed feedback stops cluttering the PR.
   * Best-effort: the review outcome is already posted when this runs.
   */
  void resolveAddressedThreads(
      String auth,
      ReviewRequest req,
      String previousAiResponseJson,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      List<ReviewResponse.PreviousFindingStatus> statuses) {
    try {
      List<Integer> addressed =
          statuses.stream()
              .filter(
                  s ->
                      "resolved".equalsIgnoreCase(s.status())
                          || "justified".equalsIgnoreCase(s.status()))
              .map(ReviewResponse.PreviousFindingStatus::id)
              .toList();
      if (addressed.isEmpty() || inlineComments.isEmpty()) {
        return;
      }
      var rootByFinding =
          followUpAnalyzer.matchFindingThreads(previousAiResponseJson, inlineComments, botIdentity);
      var threads =
          reviewThreadService.threadsByRootComment(auth, req.owner(), req.repo(), req.prNumber());
      var resolved = 0;
      for (int findingId : addressed) {
        var rootCommentId = rootByFinding.get(findingId);
        ReviewThreadService.ThreadRef thread =
            rootCommentId != null ? threads.get(rootCommentId) : null;
        if (thread != null
            && !thread.resolved()
            && reviewThreadService.resolve(auth, thread.id())) {
          resolved++;
        }
      }
      if (resolved > 0) {
        Log.infof(
            "Resolved %d addressed review thread(s) on %s/%s #%d",
            resolved, req.owner(), req.repo(), req.prNumber());
      }
    } catch (RuntimeException e) {
      Log.warn("Failed to resolve addressed review threads (continuing)", e);
    }
  }

  /**
   * Handles a review failure: updates the check run, posts an error comment, and updates the
   * session.
   */
  void handleReviewFailure(
      String auth, ReviewRequest req, ReviewSession session, long checkRunId, RuntimeException e) {
    Log.errorf(e, "Review failed for %s/%s #%d", req.owner(), req.repo(), req.prNumber());

    if (checkRunId > 0) {
      try {
        checkRunManager.updateCheckRun(
            new CheckRunManager.CheckRunUpdate(
                auth,
                req.owner(),
                req.repo(),
                checkRunId,
                CHECK_STATUS_COMPLETED,
                CONCLUSION_FAILURE,
                null,
                null,
                sessionUrl(session)));
      } catch (RuntimeException checkRunError) {
        Log.warnf(checkRunError, "Failed to mark check run %d as failed", checkRunId);
      }
    }

    try {
      commentClient.createComment(
          auth,
          ACCEPT,
          req.owner(),
          req.repo(),
          req.prNumber(),
          new GitHubCommentClient.CreateCommentRequest(
              """
                  ⚠️ **ThrillhouseBot review could not be completed.**

                  The review service encountered an error. \
                  Please reply with `/review` or `@Thrillhousebot review` to retry."""));
    } catch (RuntimeException commentError) {
      Log.warnf(
          commentError,
          "Failed to post review failure comment for %s/%s #%d",
          req.owner(),
          req.repo(),
          req.prNumber());
    }

    String errorMessage =
        e.getMessage() != null
            ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 200))
            : "Unknown error";
    try {
      applyReviewFailure(session, errorMessage);
    } catch (RuntimeException persistenceError) {
      Log.warnf(persistenceError, "Failed to persist failed review session %d", session.id);
    }
    broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.failed(session));
  }

  /** Applies review completion fields to the in-memory session and persisted entity together. */
  void applyReviewResult(ReviewSession session, ReviewResult result) {
    applySessionState(
        session,
        s -> {
          s.setStatus(ReviewSession.STATUS_COMPLETED);
          s.setCriticalFindings(result.criticalCount());
          s.setHighFindings(result.highCount());
          s.setMediumFindings(result.mediumCount());
          s.setLowFindings(result.lowCount());
          if (session.getAiResponseJson() != null) {
            s.setAiResponseJson(session.getAiResponseJson());
          }
        });
  }

  /** Applies failure fields to the in-memory session and persisted entity together. */
  void applyReviewFailure(ReviewSession session, String errorMessage) {
    applySessionState(
        session,
        s -> {
          s.setStatus(ReviewSession.STATUS_FAILED);
          s.setErrorMessage(errorMessage);
        });
  }

  private void applySessionState(ReviewSession session, Consumer<ReviewSession> mutator) {
    mutator.accept(session);
    sessionPersistence.update(session.id, mutator);
  }

  static String conclusionForResult(ReviewResult result) {
    return result.reviewState().checkRunConclusion();
  }

  static String checkTitleForResult(ReviewResult result) {
    if (!result.hasIssues()
        && unresolvedPreviousCount(result) == 0
        && result.offendingCiChecks().isEmpty()) {
      return CHECK_NAME + " ✅";
    }
    return CHECK_NAME;
  }

  static String checkSummaryForResult(ReviewResult result) {
    if (result.hasIssues()) {
      return String.format(
          "%d findings: %d critical, %d high, %d medium, %d low",
          result.totalFindings(),
          result.criticalCount(),
          result.highCount(),
          result.mediumCount(),
          result.lowCount());
    }
    // No new findings — surface CI gating first, since it also holds approval back.
    if (!result.offendingCiChecks().isEmpty()) {
      return String.format(
          "No new issues found, but %d required CI check(s) are still pending or failing.",
          result.offendingCiChecks().size());
    }
    var unresolved = unresolvedPreviousCount(result);
    if (unresolved == 0) {
      return ZERO_ISSUES_MESSAGE;
    }
    return unresolvedPreviousMessage(unresolved);
  }

  static long unresolvedPreviousCount(ReviewResult result) {
    return result.previousStatuses().stream()
        .filter(s -> "unresolved".equalsIgnoreCase(s.status()))
        .count();
  }

  // A backstop-held finding can be summary-only — its flagged line was outside the diff when first
  // raised, so it was never posted as an inline comment and has no thread to reply on.
  // The guidance therefore qualifies the reply path ("where one exists") instead of promising a
  // thread that may not be there; fixing the code, or a model resolved/justified verdict, still
  // clears such a finding.
  static String unresolvedPreviousMessage(long unresolved) {
    return String.format(
        "No new issues in this revision, but %d previous finding(s) remain unresolved — "
            + "fix them, or reply on their review thread (where one exists) with why they are"
            + " deferred.",
        unresolved);
  }

  record DiffStats(int filesChanged, int additions, int deletions, int omittedFiles) {
    DiffStats(int filesChanged, int additions, int deletions) {
      this(filesChanged, additions, deletions, 0);
    }

    /** True when the line budget dropped whole files, so the model never saw part of the change. */
    boolean truncated() {
      return omittedFiles > 0;
    }

    static DiffStats fromFiles(List<GitHubPullRequestClient.FileDiff> files, int omittedFiles) {
      var additions = 0;
      var deletions = 0;
      for (var file : files) {
        additions += file.additions();
        deletions += file.deletions();
      }
      return new DiffStats(files.size(), additions, deletions, omittedFiles);
    }
  }

  /**
   * Projects the reviewed diff onto the (path, change type) rows the summary walkthrough renders.
   */
  static List<PrSummaryGenerator.ChangedFile> toChangedFiles(
      List<GitHubPullRequestClient.FileDiff> files) {
    return files.stream()
        .map(f -> new PrSummaryGenerator.ChangedFile(f.filename(), f.status()))
        .toList();
  }

  ReviewResult buildResult(
      ReviewResponse aiResponse,
      boolean isFirstReview,
      DiffStats diffStats,
      List<PrSummaryGenerator.ChangedFile> changedFiles,
      List<Finding> unresolvedPrevious,
      List<ReviewResult.CiCheck> offendingCiChecks,
      List<ReviewResult.PreviousFindingStatus> backstopUnresolved) {
    var tally = tallyFindings(aiResponse);

    // The review may only approve when nothing is outstanding: new findings AND previous
    // findings still unresolved (not fixed, no accepted justification) both count
    var outstanding = new ArrayList<Finding>(tally.findings());
    outstanding.addAll(unresolvedPrevious);
    ReviewState state = ReviewState.fromFindings(outstanding);
    // Merge the model's previous-findings statuses with the backstop's reconstructed unresolved
    // ones: the silently dropped findings then flow through the same gate, counts, and
    // messages as any unresolved status, so no separate path is needed. Backstop statuses reach the
    // gate but never `outstanding`, keeping the hold downgrade-only (APPROVE → COMMENT, never
    // REQUEST_CHANGES).
    var previousStatuses =
        new ArrayList<ReviewResult.PreviousFindingStatus>(
            followUpAnalyzer.toStatuses(aiResponse.previousFindingsStatus()));
    previousStatuses.addAll(backstopUnresolved);
    // Unresolved statuses that could not be mapped back to stored findings (e.g. pre-persistence
    // sessions), or that the model dropped but the backstop reconstructed, must still hold approval
    if (state == ReviewState.APPROVE && followUpAnalyzer.hasUnresolved(previousStatuses)) {
      state = ReviewState.COMMENT;
    }

    if (state == ReviewState.APPROVE && !offendingCiChecks.isEmpty()) {
      state = ReviewState.COMMENT;
    }

    if (state == ReviewState.APPROVE && diffStats.truncated()) {
      state = ReviewState.COMMENT;
    }

    var summaryMarkdown =
        summaryGenerator.generate(
            diffStats.filesChanged(),
            diffStats.additions(),
            diffStats.deletions(),
            changedFiles,
            aiResponse.summary(),
            new ReviewResult(
                tally.findings(),
                tally.critical(),
                tally.high(),
                tally.medium(),
                tally.low(),
                tally.highest(),
                state,
                isFirstReview,
                "",
                previousStatuses,
                offendingCiChecks));
    if (diffStats.truncated()) {
      summaryMarkdown = truncationNotice(diffStats.omittedFiles()) + summaryMarkdown;
    }

    return new ReviewResult(
        tally.findings(),
        tally.critical(),
        tally.high(),
        tally.medium(),
        tally.low(),
        tally.highest(),
        state,
        isFirstReview,
        summaryMarkdown,
        previousStatuses,
        offendingCiChecks,
        diffStats.omittedFiles());
  }

  /** Findings parsed from the model response, with per-severity counts and the highest risk. */
  private record FindingTally(
      List<Finding> findings, int critical, int high, int medium, int low, RiskLevel highest) {}

  private static FindingTally tallyFindings(ReviewResponse aiResponse) {
    var findings = new ArrayList<Finding>();
    var critical = 0;
    var high = 0;
    var medium = 0;
    var low = 0;
    RiskLevel highest = null;
    // ReviewResponse never returns null findings, so we iterate directly. The lowest risk level is
    // counted by the catch-all branch — RiskLevel has exactly four values — which avoids the
    // unreachable extra branch the compiler would otherwise generate and report as uncovered.
    for (var ai : aiResponse.findings()) {
      Finding f = Finding.fromAiResponse(ai);
      findings.add(f);
      switch (f.risk()) {
        case CRITICAL -> critical++;
        case HIGH -> high++;
        case MEDIUM -> medium++;
        default -> low++;
      }
      if (highest == null || f.risk().compareTo(highest) < 0) {
        highest = f.risk();
      }
    }
    return new FindingTally(findings, critical, high, medium, low, highest);
  }

  /**
   * Banner prepended to the summary when the diff was truncated, so a reader knows the review is
   * partial — the verdict is also held back from APPROVE in that case.
   */
  private static String truncationNotice(int omittedFiles) {
    return String.format(
        "> ⚠️ **Large PR — partial review.** %d file(s) were omitted because the diff exceeded the"
            + " size budget; the findings and verdict below cover only the reviewed portion.%n%n",
        omittedFiles);
  }

  ReviewResult buildResult(
      ReviewResponse aiResponse,
      boolean isFirstReview,
      DiffStats diffStats,
      List<Finding> unresolvedPrevious,
      List<ReviewResult.CiCheck> offendingCiChecks) {
    return buildResult(
        aiResponse,
        isFirstReview,
        diffStats,
        List.of(),
        unresolvedPrevious,
        offendingCiChecks,
        List.of());
  }

  ReviewResult buildResult(
      ReviewResponse aiResponse,
      boolean isFirstReview,
      DiffStats diffStats,
      List<Finding> unresolvedPrevious) {
    return buildResult(aiResponse, isFirstReview, diffStats, unresolvedPrevious, List.of());
  }

  void postReview(
      String auth,
      String owner,
      String repo,
      int prNumber,
      String commitSha,
      ReviewResult result,
      List<GitHubPullRequestClient.FileDiff> files) {
    if (!result.hasIssues()) {
      postNoIssuesReview(auth, owner, repo, prNumber, commitSha, result);
      return;
    }

    var lineResolver = new DiffLineResolver(diffFormatter.patchesByFile(files));
    var posted = postInlineComments(auth, owner, repo, prNumber, commitSha, result, lineResolver);

    if (result.reviewState() == ReviewState.REQUEST_CHANGES && posted > 0) {
      createReviewWithFallback(
          auth,
          owner,
          repo,
          prNumber,
          new GitHubReviewClient.CreateReviewRequest(
              commitSha,
              "ThrillhouseBot requested changes — see inline comments on the diff.",
              EVENT_REQUEST_CHANGES,
              List.of()));
    } else if (posted == 0) {
      // Inline anchoring failed for every finding (e.g. all lines fell outside the diff after a
      // force-push). Surface them in the review body so they are not invisible: a follow-up review
      // posts no summary comment, so without this the findings would show only as a red check
      // .
      Log.warnf(
          "No inline comments posted for %s/%s #%d — surfacing findings in the review body",
          owner, repo, prNumber);
      String body =
          result.isFirstReview()
              ? "ThrillhouseBot found "
                  + result.findings().size()
                  + " issue(s) that could not be anchored to the current diff — see the PR summary"
                  + " comment above for details."
              : unanchoredFindingsBody(result);
      createReviewWithFallback(
          auth,
          owner,
          repo,
          prNumber,
          new GitHubReviewClient.CreateReviewRequest(
              commitSha,
              body,
              result.reviewState() == ReviewState.REQUEST_CHANGES
                  ? EVENT_REQUEST_CHANGES
                  : EVENT_COMMENT,
              List.of()));
    }
  }

  /**
   * A review-body list of findings, used when none could be anchored as inline comments (their
   * lines fell outside the current diff). It keeps the findings visible on a follow-up review,
   * which posts no summary comment — without it the findings would surface only as a red check run
   * .
   */
  private static String unanchoredFindingsBody(ReviewResult result) {
    var sb = new StringBuilder();
    sb.append("ThrillhouseBot found ")
        .append(result.findings().size())
        .append(" issue(s) that could not be anchored to the current diff:\n\n");
    for (Finding f : result.findings()) {
      sb.append("- **")
          .append(f.risk().name())
          .append(":** ")
          .append(f.title())
          .append(" (`")
          .append(f.file())
          .append(":")
          .append(f.line())
          .append("`)\n");
    }
    return sb.toString();
  }

  /**
   * Posts the review when there are no new findings: a bare APPROVE, or a COMMENT explaining why.
   */
  private void postNoIssuesReview(
      String auth, String owner, String repo, int prNumber, String commitSha, ReviewResult result) {
    if (result.reviewState() == ReviewState.APPROVE) {
      // On a first review the celebration is part of the summary comment, so the approval itself
      // carries no body; follow-ups post no summary and keep the message here.
      var req =
          new GitHubReviewClient.CreateReviewRequest(
              commitSha,
              result.isFirstReview() ? "" : ZERO_ISSUES_MESSAGE,
              EVENT_APPROVE,
              List.of());
      createReviewWithFallback(auth, owner, repo, prNumber, req);
      return;
    }
    // A COMMENT held back only by pending/failed CI (no findings, nothing unresolved) merely
    // restates the summary comment the first review already posts, so emitting it too would
    // duplicate that message. Skip it on the first review and let the summary stand alone.
    // Unresolved previous findings are excluded — their COMMENT carries distinct "reply on the
    // thread" guidance the summary lacks, so it still posts. Follow-ups post no summary, so the
    // COMMENT is their only signal; REQUEST_CHANGES always posts; the merge gate is the check run.
    if (result.reviewState() == ReviewState.COMMENT
        && result.isFirstReview()
        && unresolvedPreviousCount(result) == 0) {
      return;
    }
    // No new findings, but previous ones are still unresolved or CI checks are pending/failed —
    // never claim a clean review.
    var req =
        new GitHubReviewClient.CreateReviewRequest(
            commitSha,
            noIssuesBody(result),
            result.reviewState() == ReviewState.REQUEST_CHANGES
                ? EVENT_REQUEST_CHANGES
                : EVENT_COMMENT,
            List.of());
    createReviewWithFallback(auth, owner, repo, prNumber, req);
  }

  /**
   * Body for a no-new-findings COMMENT review: failing/pending CI checks, unresolved previous
   * findings, and/or a truncated diff — whichever held the verdict back from APPROVE. The
   * unresolved message is emitted only when there actually are unresolved findings (never a bogus
   * "0 … unresolved"), and truncation is disclosed here on follow-up reviews, which post no summary
   * comment to carry the first-review banner.
   */
  private String noIssuesBody(ReviewResult result) {
    long unresolved = unresolvedPreviousCount(result);
    var sb = new StringBuilder();
    if (!result.offendingCiChecks().isEmpty()) {
      sb.append(
          "ThrillhouseBot found no issues in this PR, but some checks are still pending or"
              + " failed:\n");
      for (var check : result.offendingCiChecks()) {
        String status = check.isFailing() ? "failed" : CI_PENDING;
        sb.append("- Check **").append(check.name()).append("** is ").append(status).append("\n");
      }
      if (unresolved > 0) {
        sb.append("\nAdditionally, ").append(unresolvedPreviousMessage(unresolved));
      }
    } else if (unresolved > 0) {
      sb.append(unresolvedPreviousMessage(unresolved));
    }
    // The first-review summary comment carries the truncation banner; a follow-up posts no summary,
    // so disclose the partial review here instead — otherwise a truncation-only hold would surface
    // no reason at all (and used to misreport "0 previous finding(s) remain unresolved").
    if (result.truncated() && !result.isFirstReview()) {
      if (!sb.isEmpty()) {
        sb.append("\n\n");
      }
      sb.append(truncationNotice(result.omittedFiles()).strip());
    }
    return sb.toString();
  }

  /**
   * Posts each finding as its own pull request review comment on the diff. Individual comments
   * survive 422s that would reject an entire batched review.
   */
  int postInlineComments(
      String auth,
      String owner,
      String repo,
      int prNumber,
      String commitSha,
      ReviewResult result,
      DiffLineResolver lineResolver) {
    var target = new CommentTarget(auth, owner, repo, prNumber, commitSha);
    var posted = 0;
    var maxComments = config.review().maxReviewComments();
    for (var i = 0; i < result.findings().size() && posted < maxComments; i++) {
      // The 1-based index doubles as the finding's id in the persisted response and the
      // hidden comment marker, keeping thread matching deterministic on follow-up reviews
      if (postFindingComment(target, result.findings().get(i), i + 1, lineResolver)) {
        posted++;
      }
    }
    return posted;
  }

  /** PR coordinates shared by every inline comment of one review. */
  private record CommentTarget(
      String auth, String owner, String repo, int prNumber, String commitSha) {}

  private boolean postFindingComment(
      CommentTarget target, Finding finding, int findingId, DiffLineResolver lineResolver) {
    var line = lineResolver.resolveRightSideLine(finding.file(), finding.line());
    if (line.isEmpty()) {
      Log.debugf(
          "Skipping inline comment for %s:%d — line is outside PR diff",
          finding.file(), finding.line());
      return false;
    }

    var resolvedLine = line.getAsInt();
    if (resolvedLine != finding.line()) {
      Log.debugf(
          "Adjusted inline comment line for %s from %d to %d",
          finding.file(), finding.line(), resolvedLine);
    }

    // A suggestion whose old code spans several lines is posted as a multi-line comment so the
    // GitHub suggestion overwrites the whole range, not just the anchor line (#71). The range is
    // resolved from the verbatim old code's position in the diff; a blank/single-line/unresolvable
    // anchor leaves it empty and the comment stays single-line.
    var range =
        finding.hasSuggestion()
            ? lineResolver.resolveSuggestionRange(finding.file(), finding.suggestionOld())
            : Optional.<DiffLineResolver.LineRange>empty();

    if (tryPostInlineComment(target, finding, findingId, resolvedLine, range, true)
        || (finding.hasSuggestion()
            && tryPostInlineComment(
                target, finding, findingId, resolvedLine, Optional.empty(), false))) {
      return true;
    }
    Log.warnf("GitHub rejected inline comment for %s:%d", finding.file(), finding.line());
    return false;
  }

  /**
   * Posts one inline comment. When {@code range} is present the comment spans {@code
   * start_line}..{@code line} (both RIGHT side); otherwise it anchors to the single {@code line}.
   * The retry without a suggestion block always passes an empty range — a multi-line span is only
   * meaningful with a suggestion to apply across it.
   */
  private boolean tryPostInlineComment(
      CommentTarget target,
      Finding finding,
      int findingId,
      int line,
      Optional<DiffLineResolver.LineRange> range,
      boolean includeSuggestion) {
    int endLine = range.map(DiffLineResolver.LineRange::endLine).orElse(line);
    Integer startLine = range.map(DiffLineResolver.LineRange::startLine).orElse(null);
    String startSide = startLine != null ? "RIGHT" : null;
    try {
      reviewClient.createPullRequestComment(
          target.auth(),
          ACCEPT,
          target.owner(),
          target.repo(),
          target.prNumber(),
          new GitHubReviewClient.CreatePullRequestCommentRequest(
              target.commitSha(),
              suggestionFormatter.formatReviewComment(finding, includeSuggestion, findingId),
              finding.file(),
              endLine,
              "RIGHT",
              startLine,
              startSide));
      return true;
    } catch (RuntimeException e) {
      Log.debugf(
          e,
          "Inline comment rejected for %s:%d (suggestion=%s)",
          finding.file(),
          endLine,
          includeSuggestion);
      return false;
    }
  }

  /**
   * Deletes any pending review left by this bot — GitHub allows only one pending review per user.
   * Reuses the reviews already fetched for this run instead of re-listing: no review is created
   * between that fetch and here, so a second {@code listReviews} would only spend extra rate-limit
   * budget.
   */
  void dismissPendingBotReviews(
      String auth,
      String owner,
      String repo,
      int prNumber,
      List<GitHubReviewClient.ReviewResponse> priorReviews) {
    try {
      for (var review : priorReviews) {
        if ("PENDING".equals(review.state()) && botIdentity.matches(review.user().login())) {
          reviewClient.deletePendingReview(auth, ACCEPT, owner, repo, prNumber, review.id());
          Log.debugf(
              "Dismissed pending review %d on %s/%s #%d", review.id(), owner, repo, prNumber);
        }
      }
    } catch (RuntimeException e) {
      Log.debug("Could not dismiss pending bot reviews (continuing)", e);
    }
  }

  /**
   * Submits a PR review, falling back to a summary-only review when inline comments are rejected
   * (e.g. stale line numbers after a force-push).
   */
  void createReviewWithFallback(
      String auth,
      String owner,
      String repo,
      int prNumber,
      GitHubReviewClient.CreateReviewRequest req) {
    try {
      reviewClient.createReview(auth, ACCEPT, owner, repo, prNumber, req);
    } catch (RuntimeException e) {
      if (req.comments() == null || req.comments().isEmpty()) {
        throw new ReviewPostException(
            "GitHub review rejected for " + owner + "/" + repo + " #" + prNumber, e);
      }
      Log.warnf(
          e,
          "PR review with inline comments rejected for %s/%s #%d — retrying without comments",
          owner,
          repo,
          prNumber);
      var fallback =
          new GitHubReviewClient.CreateReviewRequest(
              req.commitId(), req.body(), req.event(), List.of());
      reviewClient.createReview(auth, ACCEPT, owner, repo, prNumber, fallback);
    }
  }
}
