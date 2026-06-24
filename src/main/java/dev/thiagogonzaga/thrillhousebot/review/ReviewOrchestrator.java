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

  private final ThrillhouseConfig config;
  // The login(s) the bot posts under, resolved once from config so that summary dedup, first-review
  // detection, and follow-up matching all recognize the bot's own activity regardless of which
  // <app-slug>[bot] this deployment runs under.
  private final BotIdentity botIdentity;
  private final GitHubAuthClient authClient;

  private final GitHubCommentClient commentClient;
  private final AiReviewService aiReviewService;
  private final FindingVerificationService findingVerificationService;
  private final FindingQuoteValidator quoteValidator;
  private final FindingDeduplicator deduplicator;

  private final SessionEventBroadcaster broadcaster;

  private final ReviewSessionPersistence sessionPersistence;

  private final PrSummaryGenerator summaryGenerator;
  private final FollowUpAnalyzer followUpAnalyzer;

  private final ReviewDiffFormatter diffFormatter;

  private final PrLabeler labeler;

  private final CiStatusEvaluator ciStatusEvaluator;

  private final CheckRunManager checkRunManager;

  private final ReviewContextLoader contextLoader;

  private final ReviewPromptAssembler promptAssembler;

  private final ReviewPublisher reviewPublisher;

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
      @RestClient GitHubCommentClient commentClient,
      AiReviewService aiReviewService,
      FindingVerificationService findingVerificationService,
      FindingQuoteValidator quoteValidator,
      FindingDeduplicator deduplicator,
      SessionEventBroadcaster broadcaster,
      ReviewSessionPersistence sessionPersistence,
      PrSummaryGenerator summaryGenerator,
      FollowUpAnalyzer followUpAnalyzer,
      ReviewDiffFormatter diffFormatter,
      PrLabeler labeler,
      CiStatusEvaluator ciStatusEvaluator,
      CheckRunManager checkRunManager,
      ReviewContextLoader contextLoader,
      ReviewPromptAssembler promptAssembler,
      ReviewPublisher reviewPublisher,
      ObjectMapper mapper) {
    this.config = config;
    this.botIdentity = BotIdentity.from(config.github().botLogins());
    this.authClient = authClient;
    this.commentClient = commentClient;
    this.aiReviewService = aiReviewService;
    this.findingVerificationService = findingVerificationService;
    this.quoteValidator = quoteValidator;
    this.deduplicator = deduplicator;
    this.broadcaster = broadcaster;
    this.sessionPersistence = sessionPersistence;
    this.summaryGenerator = summaryGenerator;
    this.followUpAnalyzer = followUpAnalyzer;
    this.diffFormatter = diffFormatter;
    this.labeler = labeler;
    this.ciStatusEvaluator = ciStatusEvaluator;
    this.checkRunManager = checkRunManager;
    this.contextLoader = contextLoader;
    this.promptAssembler = promptAssembler;
    this.reviewPublisher = reviewPublisher;
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

      reviewPublisher.dismissPendingBotReviews(
          auth, req.owner(), req.repo(), req.prNumber(), priorReviews);
      reviewPublisher.postReview(
          auth, req.owner(), req.repo(), req.prNumber(), req.commitSha(), result, files);

      reviewPublisher.resolveAddressedThreads(
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
        && result.unresolvedPreviousCount() == 0
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
    var unresolved = result.unresolvedPreviousCount();
    if (unresolved == 0) {
      return ZERO_ISSUES_MESSAGE;
    }
    return ReviewResult.unresolvedPreviousMessage(unresolved);
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
      summaryMarkdown = ReviewResult.truncationNotice(diffStats.omittedFiles()) + summaryMarkdown;
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
}
