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
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@ApplicationScoped
public class ReviewOrchestrator {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final String CHECK_NAME = "ThrillhouseBot Review";
  private static final String ZERO_ISSUES_MESSAGE = PrSummaryGenerator.ZERO_ISSUES_MESSAGE;
  private static final String SUMMARY_HEADING = PrSummaryGenerator.SUMMARY_HEADING;

  // Check run status constants
  private static final String CHECK_STATUS_COMPLETED = "completed";
  private static final String CHECK_STATUS_IN_PROGRESS = "in_progress";

  // Check run conclusion constants
  private static final String CONCLUSION_FAILURE = "failure";

  // CI check evaluation constants
  private static final String CI_SUCCESS = "success";
  private static final String CI_PENDING = "pending";
  private static final String CI_FAILING = "failing";

  // Lowercased token identifying ThrillhouseBot's own checks/apps among CI results.
  private static final String THRILLHOUSEBOT_TOKEN = "thrillhousebot";

  // Check-run conclusions that count as a passing check (everything else is a failure)
  private static final Set<String> PASSING_CONCLUSIONS = Set.of(CI_SUCCESS, "skipped", "neutral");

  // CI status pagination: GitHub serves at most 100 rows per page; the loop stops on a short page.
  // Package-private so the pagination guard can be exercised in tests without 5000 mock rows.
  static final int CI_PER_PAGE = 100;
  static final int CI_MAX_PAGES = 50;

  private final ThrillhouseConfig config;
  // The login(s) the bot posts under, resolved once from config so that summary dedup, first-review
  // detection, and follow-up matching all recognize the bot's own activity regardless of which
  // <app-slug>[bot] this deployment runs under.
  private final BotIdentity botIdentity;
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

  private final PrLabeler labeler;

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
      PrLabeler labeler,
      ObjectMapper mapper) {
    this.config = config;
    this.botIdentity = BotIdentity.from(config.github().botLogins());
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
    this.labeler = labeler;
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
      // Two independent flags decouple UX presentation from context loading:
      //  • isFirstVisibleReview — true when the bot has posted nothing user-visible on the PR yet:
      //    neither a review nor a summary comment. Gates the first-review summary issue-comment and
      //    the APPROVE celebration body. A review alone is not a reliable proxy: a first round held
      //    back only by pending CI posts the summary comment but no review, so keying off
      // the
      //    review would re-post the summary every round until one finally creates a review. The
      //    summary comment is the artifact we must not duplicate, so we look for it directly.
      //  • hasContext — true when persistence holds prior AI responses (surviving force-push/
      //    rebase). Gates previous-findings context loading, inline comment fetching, and the
      //    deterministic backstop. A persisted-but-unreviewed prior round (e.g. createReview
      //    failed after persistAiResponse) must still reconstruct context without suppressing
      //    the first user-visible summary.
      List<String> priorAiResponseJsons =
          sessionPersistence.findAllPriorAiResponseJsons(repository, req.prNumber(), session.id);
      var isFirstVisibleReview =
          priorReviews.stream().noneMatch(r -> botIdentity.matches(r.user().login()))
              && !botSummaryCommentExists(auth, req.owner(), req.repo(), req.prNumber());
      var hasContext = !priorAiResponseJsons.isEmpty();
      String previousAiResponseJson =
          priorAiResponseJsons.isEmpty() ? null : priorAiResponseJsons.get(0);
      List<String> olderAiResponseJsons =
          priorAiResponseJsons.size() > 1
              ? priorAiResponseJsons.subList(1, priorAiResponseJsons.size())
              : List.of();
      List<GitHubReviewClient.PullRequestComment> inlineComments =
          hasContext
              ? fetchPullRequestComments(auth, req.owner(), req.repo(), req.prNumber())
              : List.of();
      String previousFindings =
          hasContext
              ? followUpAnalyzer.buildPreviousFindingsContext(
                  previousAiResponseJson,
                  priorReviews,
                  inlineComments,
                  olderAiResponseJsons,
                  botIdentity)
              : "";

      var instructions =
          instructionsResolver.resolve(
              req.owner(), req.repo(), req.defaultBranch(), req.installationId());

      // Existing repo labels are fetched once (when the feature is on): they both constrain the
      // model's suggestions in the prompt and are reused to reconcile its output afterwards.
      var repoLabels = labeler.fetchExistingLabels(auth, req.owner(), req.repo());

      // The diff carries the code under review, so it is enclosed in a per-review random fence and
      // passed byte-exact (no marker rewriting that would corrupt marker-handling code). The
      // smaller prose slots keep the lightweight marker neutralization as defense-in-depth.
      String fencedDiff = PromptTemplateEscaper.fence(diff);
      String escapedStack = PromptTemplateEscaper.escape(resolveProjectStack(req));
      // The label guidance and the repo-instructions file share the prompt's trailing
      // {{repoInstructions}} slot; the label section is escaped (it carries repo label names),
      // the instructions section escapes its own maintainer content.
      String labelGuidance = PrLabeler.buildLabelGuidance(repoLabels, labeler.allowNewLabels());
      String trailingGuidance =
          combineSections(
              labelGuidance.isBlank() ? "" : PromptTemplateEscaper.escape(labelGuidance),
              buildInstructionsSection(instructions));
      var promptInputs =
          new AiReviewService.PromptInputs(
              fencedDiff,
              PromptTemplateEscaper.escape(buildPrContext(req.prTitle(), req.prDescription())),
              PromptTemplateEscaper.escape(baseComparison),
              escapedStack,
              PromptTemplateEscaper.escape(
                  diffFormatter.buildRelatedTests(diffFormatter.reviewableFiles(files))),
              PromptTemplateEscaper.escape(previousFindings),
              trailingGuidance);
      // Quote validation runs before dedupe so a merged finding can never inherit a phantom
      // quote from one duplicate while a verbatim sibling gets discarded
      var aiResponse = aiReviewService.review(session, promptInputs);
      aiResponse = quoteValidator.validate(aiResponse, diff);
      aiResponse = deduplicator.dedupe(aiResponse);
      aiResponse =
          findingVerificationService.verify(
              aiResponse, fencedDiff, escapedStack, PromptTemplateEscaper.escape(previousFindings));
      aiResponse =
          followUpAnalyzer.dropRepliedDuplicates(
              aiResponse, priorAiResponseJsons, inlineComments, botIdentity);
      var lineResolver = new DiffLineResolver(patchesByFile(files));
      aiResponse = populateMissingAnchors(aiResponse, lineResolver);
      persistAiResponse(session, aiResponse);

      // Fetch required status checks and evaluate CI checks on the head SHA. An absent result means
      // "could not determine required checks" — evaluateCiChecks then gates on all checks (null).
      List<String> requiredContexts = resolveRequiredContexts(auth, req).orElse(null);
      List<ReviewResult.CiCheck> offendingCiChecks =
          evaluateCiChecks(auth, req.owner(), req.repo(), req.commitSha(), requiredContexts);

      DiffStats diffStats = DiffStats.fromFiles(diffFormatter.reviewableFiles(files));
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
              unresolvedPrevious,
              offendingCiChecks,
              backstopUnresolved);

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
      if (isFirstVisibleReview && !result.summaryMarkdown().isBlank()) {
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
      handleReviewFailure(auth, req, session, checkRunId, e);
    }
  }

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

  /** Joins two optional prompt sections with a blank line, dropping any that are blank. */
  static String combineSections(String first, String second) {
    if (first.isBlank()) {
      return second;
    }
    if (second.isBlank()) {
      return first;
    }
    return first + "\n\n" + second;
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
    // A fetch failure must propagate to review()'s handler (failed check + "could not complete"
    // notice). Swallowing it into an empty list is indistinguishable from a PR with no changes and
    // produces a false APPROVE + green check on code that was never read (#211). A genuinely empty
    // PR returns an empty list with no exception and still takes the normal path.
    return prClient.getPullRequestFiles(auth, ACCEPT, owner, repo, prNumber);
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

  /**
   * Whether the bot has already posted its PR summary comment on this PR. Used to suppress a
   * duplicate summary on a re-review when a prior round left a summary comment but no review (e.g.
   * a first round held back only by pending CI). Best-effort: on a fetch failure it returns {@code
   * false}, falling back to the review-based signal rather than blocking the summary.
   */
  boolean botSummaryCommentExists(String auth, String owner, String repo, int prNumber) {
    for (var comment : fetchIssueComments(auth, owner, repo, prNumber)) {
      var user = comment.user();
      var body = comment.body();
      if (user != null
          && botIdentity.matches(user.login())
          && body != null
          && body.stripLeading().startsWith(SUMMARY_HEADING)) {
        return true;
      }
    }
    return false;
  }

  List<GitHubCommentClient.IssueComment> fetchIssueComments(
      String auth, String owner, String repo, int prNumber) {
    try {
      // listComments paginates internally and always returns a non-null list (empty when there are
      // no comments), so the only best-effort fallback needed here is the fetch throwing.
      return commentClient.listComments(auth, ACCEPT, owner, repo, prNumber);
    } catch (RuntimeException e) {
      Log.debug("Could not fetch PR issue comments (continuing as if none exist)", e);
      return List.of();
    }
  }

  List<GitHubReviewClient.PullRequestComment> fetchPullRequestComments(
      String auth, String owner, String repo, int prNumber) {
    try {
      // The client walks every page and never returns null, so the only failure to absorb here is
      // the fetch throwing.
      return reviewClient.listPullRequestComments(auth, ACCEPT, owner, repo, prNumber);
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
      List<ReviewResult.CiCheck> offendingCiChecks,
      List<ReviewResult.PreviousFindingStatus> backstopUnresolved) {
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
      List<Finding> unresolvedPrevious,
      List<ReviewResult.CiCheck> offendingCiChecks) {
    return buildResult(
        aiResponse, isFirstReview, diffStats, unresolvedPrevious, offendingCiChecks, List.of());
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
      postNoIssuesReview(auth, owner, repo, prNumber, commitSha, result);
      return;
    }

    var lineResolver = new DiffLineResolver(patchesByFile(files));
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
   * Posts the review when there are no new findings: a bare APPROVE, or a COMMENT explaining why.
   */
  private void postNoIssuesReview(
      String auth, String owner, String repo, int prNumber, String commitSha, ReviewResult result) {
    if (result.reviewState() == ReviewState.APPROVE) {
      // On a first review the celebration is part of the summary comment, so the approval itself
      // carries no body; follow-ups post no summary and keep the message here.
      var req =
          new GitHubReviewClient.CreateReviewRequest(
              commitSha, result.isFirstReview() ? "" : ZERO_ISSUES_MESSAGE, "APPROVE", List.of());
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
            result.reviewState() == ReviewState.REQUEST_CHANGES ? "REQUEST_CHANGES" : "COMMENT",
            List.of());
    createReviewWithFallback(auth, owner, repo, prNumber, req);
  }

  /**
   * Body for a no-new-findings COMMENT review: failing/pending CI checks and/or unresolved items.
   */
  private String noIssuesBody(ReviewResult result) {
    long unresolved = unresolvedPreviousCount(result);
    if (result.offendingCiChecks().isEmpty()) {
      return unresolvedPreviousMessage(unresolved);
    }
    var sb = new StringBuilder();
    sb.append(
        "ThrillhouseBot found no issues in this PR, but some checks are still pending or failed:\n");
    for (var check : result.offendingCiChecks()) {
      String status = check.isFailing() ? "failed" : CI_PENDING;
      sb.append("- Check **").append(check.name()).append("** is ").append(status).append("\n");
    }
    if (unresolved > 0) {
      sb.append("\nAdditionally, ").append(unresolvedPreviousMessage(unresolved));
    }
    return sb.toString();
  }

  private Map<String, String> patchesByFile(List<GitHubPullRequestClient.FileDiff> files) {
    var patchesByFile = new HashMap<String, String>();
    for (var file : diffFormatter.reviewableFiles(files)) {
      if (file.patch() != null && !file.patch().isBlank()) {
        patchesByFile.put(file.filename(), file.patch());
      }
    }
    return patchesByFile;
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

  /**
   * Resolves the required status-check contexts for the PR's target branch. The contexts come from
   * two GitHub mechanisms, unioned: repository/organization <em>rulesets</em> (modern; needs only
   * read access) and <em>classic branch protection</em> (legacy; needs admin). Returns an empty
   * {@link Optional} — which the caller maps to {@code null}, gating on every check — only when
   * neither mechanism governs the branch or both lookups fail.
   */
  // Package-private so each resolution branch can be exercised directly in tests.
  Optional<List<String>> resolveRequiredContexts(String auth, ReviewRequest req) {
    String branch;
    try {
      var prDetails =
          prClient.getPullRequest(auth, ACCEPT, req.owner(), req.repo(), req.prNumber());
      if (prDetails == null || prDetails.base() == null || prDetails.base().ref() == null) {
        return Optional.empty();
      }
      branch = prDetails.base().ref();
    } catch (Exception e) {
      Log.warnf(e, "Could not resolve target branch; gating CI on all checks");
      return Optional.empty();
    }

    var contexts = new LinkedHashSet<String>();
    boolean resolved = false;
    var fromRulesets = requiredContextsFromRulesets(auth, req, branch);
    if (fromRulesets.isPresent()) {
      resolved = true;
      contexts.addAll(fromRulesets.get());
    }
    var fromClassic = requiredContextsFromClassicProtection(auth, req, branch);
    if (fromClassic.isPresent()) {
      resolved = true;
      contexts.addAll(fromClassic.get());
    }

    return resolved ? Optional.of(List.copyOf(contexts)) : Optional.empty();
  }

  /**
   * Required contexts declared by rulesets governing {@code branch}. An empty {@link Optional}
   * means no ruleset applies (or the lookup failed); a present-but-empty list means a ruleset
   * applies but mandates no status checks.
   */
  private Optional<List<String>> requiredContextsFromRulesets(
      String auth, ReviewRequest req, String branch) {
    try {
      var rules = checkRunClient.getBranchRules(auth, ACCEPT, req.owner(), req.repo(), branch);
      if (rules == null || rules.isEmpty()) {
        return Optional.empty();
      }
      var contexts = new ArrayList<String>();
      for (var rule : rules) {
        if (rule.isRequiredStatusChecks() && rule.parameters() != null) {
          for (var check : rule.parameters().requiredStatusChecks()) {
            if (check.context() != null) {
              contexts.add(check.context());
            }
          }
        }
      }
      return Optional.of(contexts);
    } catch (Exception e) {
      Log.warnf(
          e, "Could not fetch branch rules (rulesets) for %s; trying classic protection", branch);
      return Optional.empty();
    }
  }

  /**
   * Required contexts declared by classic branch protection. An empty {@link Optional} means the
   * branch is not protected this way — expected, not an error, for ruleset-only repositories — or
   * the lookup failed.
   */
  private Optional<List<String>> requiredContextsFromClassicProtection(
      String auth, ReviewRequest req, String branch) {
    try {
      var protection =
          checkRunClient.getRequiredStatusChecks(auth, ACCEPT, req.owner(), req.repo(), branch);
      return protection == null ? Optional.empty() : Optional.of(protection.contexts());
    } catch (Exception e) {
      // A 404 here is normal when the repo uses rulesets instead of classic branch protection.
      Log.debugf(e, "No classic branch protection for %s", branch);
      return Optional.empty();
    }
  }

  /**
   * Returns only the <em>offending</em> CI checks on {@code commitSha} — those that are pending,
   * failing, or (when required) missing entirely. Passing checks are deliberately excluded so the
   * caller can gate APPROVE on a non-empty result; a successful required check is recorded in
   * {@code seen} so it is not later mistaken for a missing one.
   */
  List<ReviewResult.CiCheck> evaluateCiChecks(
      String auth, String owner, String repo, String commitSha, List<String> requiredContexts) {
    var offending = new ArrayList<ReviewResult.CiCheck>();
    // Required contexts that reported in any state, so the missing-check pass does not re-flag a
    // green check as pending.
    var seen = new HashSet<String>();
    // Contexts already recorded as offending, so a check reported through BOTH the Check Runs API
    // and the Commit Status API is not listed twice.
    var offendingNames = new HashSet<String>();

    try {
      collectPaged(
          page -> {
            var resp =
                checkRunClient.getCheckRuns(
                    auth, ACCEPT, owner, repo, commitSha, CI_PER_PAGE, page);
            return resp == null ? null : resp.checkRuns();
          },
          run -> addOffendingCheckRun(run, requiredContexts, seen, offendingNames, offending));
    } catch (Exception e) {
      Log.warnf(e, "Failed to fetch check runs for commit %s", commitSha);
    }

    try {
      collectPaged(
          page -> {
            var resp =
                checkRunClient.getCombinedStatus(
                    auth, ACCEPT, owner, repo, commitSha, CI_PER_PAGE, page);
            return resp == null ? null : resp.statuses();
          },
          status -> addOffendingStatus(status, requiredContexts, seen, offendingNames, offending));
    } catch (Exception e) {
      Log.warnf(e, "Failed to fetch combined status for commit %s", commitSha);
    }

    addMissingRequiredChecks(requiredContexts, seen, offending);
    return offending;
  }

  /**
   * Pages through a GitHub list endpoint, applying {@code consume} to every row. Stops on an empty
   * or short page (GitHub's last-page marker) or once {@link #CI_MAX_PAGES} is reached.
   */
  private static <T> void collectPaged(IntFunction<List<T>> fetchPage, Consumer<T> consume) {
    List<T> page = null;
    for (int p = 1; p <= CI_MAX_PAGES && (page == null || page.size() == CI_PER_PAGE); p++) {
      page = fetchPage.apply(p);
      if (page == null) {
        page = List.of();
      }
      page.forEach(consume);
    }
  }

  private void addOffendingCheckRun(
      GitHubCheckRunClient.CheckRunsResponse.CheckRun run,
      List<String> requiredContexts,
      Set<String> seen,
      Set<String> offendingNames,
      List<ReviewResult.CiCheck> offending) {
    if (isThrillhouseBotCheck(run.name(), run.app())
        || isNotRequired(run.name(), requiredContexts)) {
      return;
    }
    seen.add(run.name());
    String ciStatus = classifyCheckRun(run.status(), run.conclusion());
    if (!CI_SUCCESS.equals(ciStatus) && offendingNames.add(run.name())) {
      offending.add(new ReviewResult.CiCheck(run.name(), "check-run", ciStatus, run.conclusion()));
    }
  }

  private void addOffendingStatus(
      GitHubCheckRunClient.CombinedStatus.StatusDetail status,
      List<String> requiredContexts,
      Set<String> seen,
      Set<String> offendingNames,
      List<ReviewResult.CiCheck> offending) {
    if (isThrillhouseBotCheck(status.context(), null)
        || isNotRequired(status.context(), requiredContexts)) {
      return;
    }
    seen.add(status.context());
    String ciStatus = classifyStatus(status.state());
    if (!CI_SUCCESS.equals(ciStatus) && offendingNames.add(status.context())) {
      offending.add(new ReviewResult.CiCheck(status.context(), "status", ciStatus, status.state()));
    }
  }

  /** Required contexts that never reported are implicitly pending. */
  private void addMissingRequiredChecks(
      List<String> requiredContexts, Set<String> seen, List<ReviewResult.CiCheck> offending) {
    if (requiredContexts == null) {
      return;
    }
    for (String required : requiredContexts) {
      // seen.add is false when the context already reported (or is a duplicate entry) — skip those.
      if (!isThrillhouseBotCheck(required, null) && seen.add(required)) {
        offending.add(new ReviewResult.CiCheck(required, "missing", CI_PENDING, null));
      }
    }
  }

  private static boolean isNotRequired(String name, List<String> requiredContexts) {
    return requiredContexts != null && !requiredContexts.contains(name);
  }

  /** Maps a check-run status/conclusion pair to one of success/pending/failing. */
  private static String classifyCheckRun(String status, String conclusion) {
    if (!CHECK_STATUS_COMPLETED.equalsIgnoreCase(status)) {
      return CI_PENDING;
    }
    if (conclusion == null || !PASSING_CONCLUSIONS.contains(conclusion.toLowerCase(Locale.ROOT))) {
      return CI_FAILING;
    }
    return CI_SUCCESS;
  }

  /** Maps a commit-status state to one of success/pending/failing. */
  private static String classifyStatus(String state) {
    if (CI_PENDING.equalsIgnoreCase(state)) {
      return CI_PENDING;
    }
    if (CONCLUSION_FAILURE.equalsIgnoreCase(state) || "error".equalsIgnoreCase(state)) {
      return CI_FAILING;
    }
    return CI_SUCCESS;
  }

  private boolean isThrillhouseBotCheck(
      String name, GitHubCheckRunClient.CheckRunsResponse.CheckRun.App app) {
    if (name != null && (name.equalsIgnoreCase(CHECK_NAME) || containsBotToken(name))) {
      return true;
    }
    return app != null && (containsBotToken(app.slug()) || containsBotToken(app.name()));
  }

  private static boolean containsBotToken(String value) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(THRILLHOUSEBOT_TOKEN);
  }
}
