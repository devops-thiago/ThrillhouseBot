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
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@ApplicationScoped
public class ReviewOrchestrator {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final String CHECK_NAME = "ThrillhouseBot Review";
  private static final String BOT_LOGIN = "thrillhousebot[bot]";
  private static final String ZERO_ISSUES_MESSAGE = PrSummaryGenerator.ZERO_ISSUES_MESSAGE;

  // Check run status constants
  private static final String CHECK_STATUS_COMPLETED = "completed";
  private static final String CHECK_STATUS_IN_PROGRESS = "in_progress";

  // Check run conclusion constants
  private static final String CONCLUSION_FAILURE = "failure";

  private final ThrillhouseConfig config;
  private final GitHubAuthClient authClient;
  private final GitHubCheckRunClient checkRunClient;

  private final GitHubReviewClient reviewClient;

  private final GitHubCommentClient commentClient;
  private final GitHubPullRequestClient prClient;
  private final ReviewThreadService reviewThreadService;
  private final InstructionsResolver instructionsResolver;
  private final ProjectStackResolver projectStackResolver;
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
      @RestClient GitHubCheckRunClient checkRunClient,
      @RestClient GitHubReviewClient reviewClient,
      @RestClient GitHubCommentClient commentClient,
      @RestClient GitHubPullRequestClient prClient,
      ReviewThreadService reviewThreadService,
      InstructionsResolver instructionsResolver,
      ProjectStackResolver projectStackResolver,
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
      ObjectMapper mapper) {
    this.config = config;
    this.authClient = authClient;
    this.checkRunClient = checkRunClient;
    this.reviewClient = reviewClient;
    this.commentClient = commentClient;
    this.prClient = prClient;
    this.reviewThreadService = reviewThreadService;
    this.instructionsResolver = instructionsResolver;
    this.projectStackResolver = projectStackResolver;
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
    try {
      var resolved = resolveMissingPrDetails(auth, request);
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
          createCheckRun(auth, req.owner(), req.repo(), req.commitSha(), sessionUrl(session));
      var files = fetchPrFiles(auth, req.owner(), req.repo(), req.prNumber());
      var diff = buildDiffString(files);
      var baseComparison =
          buildBaseComparison(auth, req.owner(), req.repo(), req.baseSha(), req.commitSha());

      var priorReviews = fetchPriorReviews(auth, req.owner(), req.repo(), req.prNumber());
      var isFirstReview = priorReviews.stream().noneMatch(r -> BOT_LOGIN.equals(r.user().login()));
      List<String> priorAiResponseJsons =
          isFirstReview
              ? List.of()
              : sessionPersistence.findAllPriorAiResponseJsons(
                  repository, req.prNumber(), session.id);
      String previousAiResponseJson =
          priorAiResponseJsons.isEmpty() ? null : priorAiResponseJsons.get(0);
      List<String> olderAiResponseJsons =
          priorAiResponseJsons.size() > 1
              ? priorAiResponseJsons.subList(1, priorAiResponseJsons.size())
              : List.of();
      List<GitHubReviewClient.PullRequestComment> inlineComments =
          isFirstReview
              ? List.of()
              : fetchPullRequestComments(auth, req.owner(), req.repo(), req.prNumber());
      String previousFindings =
          isFirstReview
              ? ""
              : followUpAnalyzer.buildPreviousFindingsContext(
                  previousAiResponseJson,
                  priorReviews,
                  inlineComments,
                  olderAiResponseJsons,
                  BOT_LOGIN);

      var instructions =
          instructionsResolver.resolve(
              req.owner(), req.repo(), req.defaultBranch(), req.installationId());

      String escapedDiff = PromptTemplateEscaper.escape(diff);
      String escapedStack = PromptTemplateEscaper.escape(resolveProjectStack(req));
      var promptInputs =
          new AiReviewService.PromptInputs(
              escapedDiff,
              PromptTemplateEscaper.escape(buildPrContext(req.prTitle(), req.prDescription())),
              PromptTemplateEscaper.escape(baseComparison),
              escapedStack,
              PromptTemplateEscaper.escape(
                  diffFormatter.buildRelatedTests(diffFormatter.reviewableFiles(files))),
              PromptTemplateEscaper.escape(previousFindings),
              buildInstructionsSection(instructions));
      // Quote validation runs before dedupe so a merged finding can never inherit a phantom
      // quote from one duplicate while a verbatim sibling gets discarded
      var aiResponse = aiReviewService.review(session, promptInputs);
      aiResponse = quoteValidator.validate(aiResponse, diff);
      aiResponse = deduplicator.dedupe(aiResponse);
      aiResponse =
          findingVerificationService.verify(
              aiResponse,
              escapedDiff,
              escapedStack,
              PromptTemplateEscaper.escape(previousFindings));
      aiResponse =
          followUpAnalyzer.dropRepliedDuplicates(
              aiResponse, priorAiResponseJsons, inlineComments, BOT_LOGIN);
      persistAiResponse(session, aiResponse);

      // Fetch required status checks and evaluate CI checks on the head SHA
      List<String> requiredContexts = null;
      try {
        var prDetails =
            prClient.getPullRequest(auth, ACCEPT, req.owner(), req.repo(), req.prNumber());
        if (prDetails != null && prDetails.base() != null && prDetails.base().ref() != null) {
          var targetBranch = prDetails.base().ref();
          var protection =
              checkRunClient.getRequiredStatusChecks(
                  auth, ACCEPT, req.owner(), req.repo(), targetBranch);
          if (protection != null) {
            requiredContexts = protection.contexts();
          }
        }
      } catch (Exception e) {
        Log.warnf(
            e, "Could not fetch required status checks for branch, falling back to all checks");
      }

      List<ReviewResult.CiCheck> offendingCiChecks =
          evaluateCiChecks(auth, req.owner(), req.repo(), req.commitSha(), requiredContexts);

      DiffStats diffStats = DiffStats.fromFiles(diffFormatter.reviewableFiles(files));
      var unresolvedPrevious =
          followUpAnalyzer.unresolvedFindings(
              previousAiResponseJson, aiResponse.previousFindingsStatus());
      var result =
          buildResult(aiResponse, isFirstReview, diffStats, unresolvedPrevious, offendingCiChecks);

      // Finalize check run before posting PR review — avoid approving then failing later
      String conclusion = conclusionForResult(result);
      String checkTitle = checkTitleForResult(result);
      String checkSummary = checkSummaryForResult(result);
      updateCheckRun(
          new CheckRunUpdate(
              auth,
              req.owner(),
              req.repo(),
              checkRunId,
              CHECK_STATUS_COMPLETED,
              conclusion,
              checkTitle,
              checkSummary,
              sessionUrl(session)));

      // Summary goes first so it tops the PR conversation, before the inline finding comments.
      // Posted on clean first reviews too: the celebration line lives inside the summary
      if (isFirstReview && !result.summaryMarkdown().isBlank()) {
        commentClient.createComment(
            auth,
            ACCEPT,
            req.owner(),
            req.repo(),
            req.prNumber(),
            new GitHubCommentClient.CreateCommentRequest(result.summaryMarkdown()));
      }

      postReview(auth, req.owner(), req.repo(), req.prNumber(), req.commitSha(), result, files);

      resolveAddressedThreads(
          auth, req, previousAiResponseJson, inlineComments, aiResponse.previousFindingsStatus());

      applyReviewResult(session, result);
      broadcaster.broadcast(SessionEventBroadcaster.SessionEvent.completed(session));

      Log.infof(
          "Review complete for %s/%s #%d: %d findings, state=%s",
          req.owner(), req.repo(), req.prNumber(), result.totalFindings(), result.reviewState());
    } catch (RuntimeException e) {
      handleReviewFailure(auth, req, session, checkRunId, e);
    }
  }

  // ─── Private helpers ──────────────────────────────────────────

  /**
   * Manual /review triggers arrive from issue_comment webhooks, which carry no PR head/base or
   * title — fetch them so the check run gets a valid head_sha (GitHub rejects blank with 422).
   */
  ReviewRequest resolveMissingPrDetails(String auth, ReviewRequest req) {
    if (req.commitSha() != null && !req.commitSha().isBlank()) {
      return req;
    }
    var pr = prClient.getPullRequest(auth, ACCEPT, req.owner(), req.repo(), req.prNumber());
    return new ReviewRequest(
        req.owner(),
        req.repo(),
        req.prNumber(),
        pr.head() != null ? pr.head().sha() : "",
        pr.title() != null ? pr.title() : req.prTitle(),
        pr.body() != null ? pr.body() : "",
        pr.base() != null ? pr.base().sha() : "",
        req.defaultBranch(),
        req.installationId(),
        req.isManualTrigger());
  }

  /** Stack context is best-effort enrichment — its failure must never fail the review. */
  String resolveProjectStack(ReviewRequest req) {
    try {
      return projectStackResolver.resolve(
          req.owner(), req.repo(), req.defaultBranch(), req.installationId());
    } catch (RuntimeException e) {
      Log.warn("Project stack resolution failed, continuing without stack context", e);
      return "";
    }
  }

  /**
   * Pre-rendered repo-instructions prompt section, header and source attribution included, so the
   * template only needs a single variable. Only the maintainer-provided content is escaped.
   */
  static String buildInstructionsSection(InstructionsResolver.ResolvedInstructions instructions) {
    if (!instructions.isPresent()) {
      return "";
    }
    return "## Project-Specific Instructions (from "
        + instructions.source()
        + ")\n"
        + "The repository maintainers have provided these additional review guidelines.\n"
        + "These take precedence over default rules where they conflict.\n"
        + PromptTemplateEscaper.escape(instructions.content());
  }

  /** Title and author description block the model checks the implementation against. */
  static String buildPrContext(String title, String description) {
    var sb = new StringBuilder();
    if (title != null && !title.isBlank()) {
      sb.append("Title: ").append(title.strip()).append('\n');
    }
    if (description != null && !description.isBlank()) {
      sb.append("Description:\n").append(description.strip()).append('\n');
    }
    return sb.toString();
  }

  void persistAiResponse(ReviewSession session, ReviewResponse aiResponse) {
    try {
      session.setAiResponseJson(mapper.writeValueAsString(aiResponse));
    } catch (JsonProcessingException e) {
      Log.warn("Failed to serialize AI response for session persistence", e);
    }
  }

  long createCheckRun(String auth, String owner, String repo, String headSha, String detailsUrl) {
    var req =
        new GitHubCheckRunClient.CreateCheckRunRequest(
            CHECK_NAME, headSha, CHECK_STATUS_IN_PROGRESS, detailsUrl);
    var response = checkRunClient.createCheckRun(auth, ACCEPT, owner, repo, req);
    Log.debugf("Created check run: %d", response.id());
    return response.id();
  }

  void updateCheckRun(CheckRunUpdate u) {
    var output =
        u.title() != null
            ? new GitHubCheckRunClient.UpdateCheckRunRequest.Output(u.title(), u.summary(), null)
            : null;
    var completed = CHECK_STATUS_COMPLETED.equals(u.status());
    var req =
        new GitHubCheckRunClient.UpdateCheckRunRequest(
            completed ? null : u.status(),
            completed ? u.conclusion() : null,
            completed ? githubTimestamp() : null,
            u.detailsUrl(),
            output);
    if (!completed) {
      checkRunClient.updateCheckRun(u.auth(), ACCEPT, u.owner(), u.repo(), u.checkRunId(), req);
      return;
    }
    try {
      checkRunClient.updateCheckRun(u.auth(), ACCEPT, u.owner(), u.repo(), u.checkRunId(), req);
    } catch (RuntimeException e) {
      Log.warnf(
          e,
          "Check run %d completion update failed, retrying with conclusion only",
          u.checkRunId());
      checkRunClient.updateCheckRun(
          u.auth(),
          ACCEPT,
          u.owner(),
          u.repo(),
          u.checkRunId(),
          new GitHubCheckRunClient.UpdateCheckRunRequest(null, u.conclusion(), null, null, null));
    }
  }

  private static String githubTimestamp() {
    return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
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

  record CheckRunUpdate(
      String auth,
      String owner,
      String repo,
      long checkRunId,
      String status,
      String conclusion,
      String title,
      String summary,
      String detailsUrl) {}

  List<GitHubPullRequestClient.FileDiff> fetchPrFiles(
      String auth, String owner, String repo, int prNumber) {
    try {
      return prClient.getPullRequestFiles(auth, ACCEPT, owner, repo, prNumber);
    } catch (RuntimeException e) {
      Log.warn("Failed to fetch PR files, continuing with empty diff", e);
      return List.of();
    }
  }

  String buildDiffString(List<GitHubPullRequestClient.FileDiff> files) {
    return diffFormatter.buildDiffString(files);
  }

  String buildBaseComparison(String auth, String owner, String repo, String base, String head) {
    if (base == null || head == null || base.length() < 7 || head.length() < 7) {
      return "(regression comparison unavailable — refs too short)";
    }
    try {
      var comparison = prClient.compareCommits(auth, ACCEPT, owner, repo, base, head);
      return diffFormatter.buildBaseComparison(comparison, base, head);
    } catch (RuntimeException e) {
      Log.warn("Failed to fetch base comparison, continuing without regression context", e);
      return "(regression comparison unavailable)";
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

  List<GitHubReviewClient.PullRequestComment> fetchPullRequestComments(
      String auth, String owner, String repo, int prNumber) {
    try {
      var comments = reviewClient.listPullRequestComments(auth, ACCEPT, owner, repo, prNumber);
      return comments != null ? comments : List.<GitHubReviewClient.PullRequestComment>of();
    } catch (RuntimeException e) {
      Log.warn("Failed to fetch PR inline comments, continuing without thread context", e);
      return List.of();
    }
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
          followUpAnalyzer.matchFindingThreads(previousAiResponseJson, inlineComments, BOT_LOGIN);
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
        updateCheckRun(
            new CheckRunUpdate(
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
    if (!result.hasIssues() && unresolvedPreviousCount(result) == 0) {
      return CHECK_NAME + " ✅";
    }
    return CHECK_NAME;
  }

  static String checkSummaryForResult(ReviewResult result) {
    if (!result.hasIssues()) {
      var unresolved = unresolvedPreviousCount(result);
      if (unresolved == 0) {
        return ZERO_ISSUES_MESSAGE;
      }
      return unresolvedPreviousMessage(unresolved);
    }
    return String.format(
        "%d findings: %d critical, %d high, %d medium, %d low",
        result.totalFindings(),
        result.criticalCount(),
        result.highCount(),
        result.mediumCount(),
        result.lowCount());
  }

  static long unresolvedPreviousCount(ReviewResult result) {
    return result.previousStatuses().stream()
        .filter(s -> "unresolved".equalsIgnoreCase(s.status()))
        .count();
  }

  static String unresolvedPreviousMessage(long unresolved) {
    return String.format(
        "No new issues in this revision, but %d previous finding(s) remain unresolved — "
            + "fix them or reply on their threads with why they are deferred.",
        unresolved);
  }

  record DiffStats(int filesChanged, int additions, int deletions) {
    static DiffStats fromFiles(List<GitHubPullRequestClient.FileDiff> files) {
      var additions = 0;
      var deletions = 0;
      for (var file : files) {
        additions += file.additions();
        deletions += file.deletions();
      }
      return new DiffStats(files.size(), additions, deletions);
    }
  }

  ReviewResult buildResult(
      ReviewResponse aiResponse,
      boolean isFirstReview,
      DiffStats diffStats,
      List<Finding> unresolvedPrevious,
      List<ReviewResult.CiCheck> offendingCiChecks) {
    var findings = new ArrayList<Finding>();
    var critical = 0;
    var high = 0;
    var medium = 0;
    var low = 0;
    RiskLevel highest = null;

    if (aiResponse.findings() != null) {
      for (var ai : aiResponse.findings()) {
        Finding f = Finding.fromAiResponse(ai);
        findings.add(f);
        switch (f.risk()) {
          case CRITICAL -> critical++;
          case HIGH -> high++;
          case MEDIUM -> medium++;
          case LOW -> low++;
        }
        if (highest == null || f.risk().compareTo(highest) < 0) {
          highest = f.risk();
        }
      }
    }

    // The review may only approve when nothing is outstanding: new findings AND previous
    // findings still unresolved (not fixed, no accepted justification) both count
    var outstanding = new ArrayList<Finding>(findings);
    outstanding.addAll(unresolvedPrevious);
    ReviewState state = ReviewState.fromFindings(outstanding);
    var previousStatuses = followUpAnalyzer.toStatuses(aiResponse.previousFindingsStatus());
    // Unresolved statuses that could not be mapped back to stored findings (e.g. pre-persistence
    // sessions) must still hold the approval back
    if (state == ReviewState.APPROVE && followUpAnalyzer.hasUnresolved(previousStatuses)) {
      state = ReviewState.COMMENT;
    }

    // Gating approval verdict on CI status:
    if (state == ReviewState.APPROVE && !offendingCiChecks.isEmpty()) {
      state = ReviewState.COMMENT;
    }

    // The summary is generated for clean reviews too: a zero-findings result still reports the
    // change overview and carries the celebration line inside the same comment
    var summaryMarkdown =
        summaryGenerator.generate(
            diffStats.filesChanged(),
            diffStats.additions(),
            diffStats.deletions(),
            aiResponse.summary(),
            new ReviewResult(
                findings,
                critical,
                high,
                medium,
                low,
                highest,
                state,
                isFirstReview,
                "",
                previousStatuses,
                offendingCiChecks));

    return new ReviewResult(
        findings,
        critical,
        high,
        medium,
        low,
        highest,
        state,
        isFirstReview,
        summaryMarkdown,
        previousStatuses,
        offendingCiChecks);
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
    dismissPendingBotReviews(auth, owner, repo, prNumber);

    if (!result.hasIssues()) {
      if (result.reviewState() == ReviewState.APPROVE) {
        // On a first review the celebration is part of the summary comment, so the approval
        // itself carries no body; follow-ups post no summary and keep the message here
        var req =
            new GitHubReviewClient.CreateReviewRequest(
                commitSha, result.isFirstReview() ? "" : ZERO_ISSUES_MESSAGE, "APPROVE", List.of());
        createReviewWithFallback(auth, owner, repo, prNumber, req);
        return;
      }
      // No new findings, but previous ones are still unresolved or CI checks are pending/failed —
      // never claim a clean review
      String body;
      if (!result.offendingCiChecks().isEmpty()) {
        var sb = new StringBuilder();
        sb.append(
            "ThrillhouseBot found no issues in this PR, but some checks are still pending or failed:\n");
        for (var check : result.offendingCiChecks()) {
          String status = check.isFailing() ? "failed" : "pending";
          sb.append("- Check **").append(check.name()).append("** is ").append(status).append("\n");
        }
        long unresolved = unresolvedPreviousCount(result);
        if (unresolved > 0) {
          sb.append("\nAdditionally, ").append(unresolvedPreviousMessage(unresolved));
        }
        body = sb.toString();
      } else {
        body = unresolvedPreviousMessage(unresolvedPreviousCount(result));
      }
      var req =
          new GitHubReviewClient.CreateReviewRequest(
              commitSha,
              body,
              result.reviewState() == ReviewState.REQUEST_CHANGES ? "REQUEST_CHANGES" : "COMMENT",
              List.of());
      createReviewWithFallback(auth, owner, repo, prNumber, req);
      return;
    }

    var patchesByFile = new HashMap<String, String>();
    for (var file : diffFormatter.reviewableFiles(files)) {
      if (file.patch() != null && !file.patch().isBlank()) {
        patchesByFile.put(file.filename(), file.patch());
      }
    }
    var lineResolver = new DiffLineResolver(patchesByFile);
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
              "REQUEST_CHANGES",
              List.of()));
    } else if (posted == 0) {
      Log.warnf(
          "No inline comments posted for %s/%s #%d — findings are in the PR summary comment only",
          owner, repo, prNumber);
    }
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

    if (tryPostInlineComment(target, finding, findingId, resolvedLine, true)
        || (finding.hasSuggestion()
            && tryPostInlineComment(target, finding, findingId, resolvedLine, false))) {
      return true;
    }
    Log.warnf("GitHub rejected inline comment for %s:%d", finding.file(), finding.line());
    return false;
  }

  private boolean tryPostInlineComment(
      CommentTarget target, Finding finding, int findingId, int line, boolean includeSuggestion) {
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
              line,
              "RIGHT"));
      return true;
    } catch (RuntimeException e) {
      Log.debugf(
          e,
          "Inline comment rejected for %s:%d (suggestion=%s)",
          finding.file(),
          line,
          includeSuggestion);
      return false;
    }
  }

  /**
   * Deletes any pending review left by this bot — GitHub allows only one pending review per user.
   */
  void dismissPendingBotReviews(String auth, String owner, String repo, int prNumber) {
    try {
      for (var review : reviewClient.listReviews(auth, ACCEPT, owner, repo, prNumber)) {
        if ("PENDING".equals(review.state()) && BOT_LOGIN.equals(review.user().login())) {
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

  List<ReviewResult.CiCheck> evaluateCiChecks(
      String auth, String owner, String repo, String commitSha, List<String> requiredContexts) {
    List<ReviewResult.CiCheck> checks = new ArrayList<>();

    // 1. Get Check Runs
    try {
      var checkRunsResponse = checkRunClient.getCheckRuns(auth, ACCEPT, owner, repo, commitSha);
      if (checkRunsResponse != null && checkRunsResponse.checkRuns() != null) {
        for (var run : checkRunsResponse.checkRuns()) {
          // Ignore ThrillhouseBot's own check runs
          if (isThrillhouseBotCheck(run.name(), run.app())) {
            continue;
          }

          // If requiredContexts is provided, check if this run is in it
          if (requiredContexts != null && !requiredContexts.contains(run.name())) {
            continue;
          }

          String ciStatus = "success";
          if (!"completed".equalsIgnoreCase(run.status())) {
            ciStatus = "pending";
          } else if (run.conclusion() == null
              || !List.of("success", "skipped", "neutral")
                  .contains(run.conclusion().toLowerCase(java.util.Locale.ROOT))) {
            ciStatus = "failing";
          }

          checks.add(new ReviewResult.CiCheck(run.name(), "check-run", ciStatus, run.conclusion()));
        }
      }
    } catch (Exception e) {
      Log.warnf(e, "Failed to fetch check runs for commit %s", commitSha);
    }

    // 2. Get Combined Statuses
    try {
      var combinedStatus = checkRunClient.getCombinedStatus(auth, ACCEPT, owner, repo, commitSha);
      if (combinedStatus != null && combinedStatus.statuses() != null) {
        for (var status : combinedStatus.statuses()) {
          if (isThrillhouseBotCheck(status.context(), null)) {
            continue;
          }

          if (requiredContexts != null && !requiredContexts.contains(status.context())) {
            continue;
          }

          String ciStatus = "success";
          if ("pending".equalsIgnoreCase(status.state())) {
            ciStatus = "pending";
          } else if ("failure".equalsIgnoreCase(status.state())
              || "error".equalsIgnoreCase(status.state())) {
            ciStatus = "failing";
          }

          checks.add(
              new ReviewResult.CiCheck(status.context(), "status", ciStatus, status.state()));
        }
      }
    } catch (Exception e) {
      Log.warnf(e, "Failed to fetch combined status for commit %s", commitSha);
    }

    // 3. If requiredContexts is provided, check if any required contexts are missing entirely
    if (requiredContexts != null) {
      for (String required : requiredContexts) {
        // ThrillhouseBot itself must never be added as missing/pending
        if (isThrillhouseBotCheck(required, null)) {
          continue;
        }
        boolean found = false;
        for (var check : checks) {
          if (required.equals(check.name())) {
            found = true;
            break;
          }
        }
        if (!found) {
          // A required check that hasn't reported anything is implicitly pending
          checks.add(new ReviewResult.CiCheck(required, "missing", "pending", null));
        }
      }
    }

    return checks;
  }

  private boolean isThrillhouseBotCheck(
      String name, GitHubCheckRunClient.CheckRunsResponse.CheckRun.App app) {
    if (name != null) {
      if (name.equalsIgnoreCase(CHECK_NAME)
          || name.toLowerCase(java.util.Locale.ROOT).contains("thrillhousebot")) {
        return true;
      }
    }
    if (app != null) {
      if (app.slug() != null
          && app.slug().toLowerCase(java.util.Locale.ROOT).contains("thrillhousebot")) {
        return true;
      }
      if (app.name() != null
          && app.name().toLowerCase(java.util.Locale.ROOT).contains("thrillhousebot")) {
        return true;
      }
    }
    return false;
  }
}
