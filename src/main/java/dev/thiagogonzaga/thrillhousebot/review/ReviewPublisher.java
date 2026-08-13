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

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubApiError;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.ReviewThreadService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Posts a review's outcome to GitHub — inline finding comments, the review verdict (or no-issues
 * body), dismissal of the bot's stale pending review, and resolution of addressed finding threads.
 * The write side of the pipeline.
 */
@ApplicationScoped
public class ReviewPublisher {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final String EVENT_REQUEST_CHANGES = "REQUEST_CHANGES";
  private static final String EVENT_APPROVE = "APPROVE";
  private static final String EVENT_COMMENT = "COMMENT";
  private static final String CI_PENDING = "pending";

  private final GitHubReviewClient reviewClient;
  private final GitHubCommentClient commentClient;
  private final ReviewThreadService reviewThreadService;
  private final SuggestionFormatter suggestionFormatter;
  private final FollowUpAnalyzer followUpAnalyzer;
  private final PrLabeler labeler;
  private final ThrillhouseConfig config;
  private final BotIdentity botIdentity;

  @Inject
  public ReviewPublisher(
      @RestClient GitHubReviewClient reviewClient,
      @RestClient GitHubCommentClient commentClient,
      ReviewThreadService reviewThreadService,
      SuggestionFormatter suggestionFormatter,
      FollowUpAnalyzer followUpAnalyzer,
      PrLabeler labeler,
      ThrillhouseConfig config,
      BotIdentity botIdentity) {
    this.reviewClient = reviewClient;
    this.commentClient = commentClient;
    this.reviewThreadService = reviewThreadService;
    this.suggestionFormatter = suggestionFormatter;
    this.followUpAnalyzer = followUpAnalyzer;
    this.labeler = labeler;
    this.config = config;
    this.botIdentity = botIdentity;
  }

  /**
   * Posts the PR summary comment, but only on the first user-visible review (and only when there is
   * a summary to post). Follow-up reviews carry their signal in the review itself, not a new
   * comment — unless {@code forceSummary} is set, which the {@code /summary} command uses to
   * regenerate a summary that was deleted from the PR even though a review already ran, or a prior
   * finding was superseded this round ({@link ReviewResult#hasSupersededPrevious}): its targeted
   * code left the diff, so the earlier summary may describe code that no longer exists. In the
   * superseded case the bot's existing summary comment is edited in place with the regenerated
   * markdown — never posted alongside the stale one — falling back to a new comment only when no
   * prior summary comment exists (e.g. it was deleted).
   *
   * @return {@code true} when the summary comment was actually created; {@code false} when this
   *     review posts no summary (follow-up, or nothing to post). {@code postReview} suppresses a
   *     redundant no-issues review (summary-only re-run, or a first review held back solely by
   *     pending/failed CI) only when this returned {@code true} — a failed or skipped summary must
   *     leave the review as the run's visible outcome.
   */
  boolean publishSummary(
      String auth,
      String owner,
      String repo,
      int prNumber,
      ReviewResult result,
      boolean forceSummary) {
    if (result.summaryMarkdown().isBlank()) {
      return false;
    }
    if (result.isFirstReview() || forceSummary) {
      commentClient.createComment(
          auth,
          ACCEPT,
          owner,
          repo,
          prNumber,
          new GitHubCommentClient.CreateCommentRequest(result.summaryMarkdown()));
      return true;
    }
    if (result.hasSupersededPrevious()) {
      refreshSummaryComment(auth, owner, repo, prNumber, result.summaryMarkdown());
      return true;
    }
    return false;
  }

  /**
   * Posts the opt-in follow-up delta comment — the short "what moved since the last pass" note
   * described by {@link FollowUpDeltaSummary}. Independent of {@link #publishSummary}: it never
   * runs on a first review, and never on a round that did post a summary comment, so the two can
   * not both land on the same review and the first-run summary is never duplicated.
   *
   * <p>Its outcome deliberately does <em>not</em> feed the {@code summaryPosted} flag {@link
   * #postReview} keys its redundant-review skips on. That flag means "the PR already carries this
   * round's verdict in a comment"; a delta comment carries counts, not a verdict, so letting it
   * suppress the review would leave a clean follow-up with no stated outcome at all.
   *
   * @param summaryPosted whether {@link #publishSummary} created or refreshed a summary comment on
   *     this round — when it did, this comment is skipped rather than posted beside it
   * @return {@code true} when the delta comment was created; {@code false} when the feature is off,
   *     the review is a first review, a summary was posted, or the round has no delta to report
   */
  boolean publishFollowUpDelta(
      String auth,
      String owner,
      String repo,
      int prNumber,
      ReviewResult result,
      boolean summaryPosted) {
    if (!config.review().followUpSummary().enabled() || result.isFirstReview() || summaryPosted) {
      return false;
    }
    var body = FollowUpDeltaSummary.render(result);
    if (body.isEmpty()) {
      Log.debugf(
          "No delta to report for %s/%s #%d — skipping the follow-up summary comment",
          owner, repo, prNumber);
      return false;
    }
    commentClient.createComment(
        auth,
        ACCEPT,
        owner,
        repo,
        prNumber,
        new GitHubCommentClient.CreateCommentRequest(body.get()));
    return true;
  }

  /**
   * Replaces the stale summary with the regenerated one after a finding was superseded: edits the
   * bot's newest existing summary comment in place, so the PR never shows the outdated summary
   * (describing removed code) next to the fresh one. Creates a new comment only when no summary
   * comment is found (e.g. a maintainer deleted it).
   */
  private void refreshSummaryComment(
      String auth, String owner, String repo, int prNumber, String summaryMarkdown) {
    var existing =
        commentClient.listComments(auth, ACCEPT, owner, repo, prNumber).stream()
            .filter(c -> c.user() != null && botIdentity.matches(c.user().login()))
            .filter(c -> ReviewContextLoader.isBotSummaryComment(c.body()))
            .reduce((first, second) -> second);
    var request = new GitHubCommentClient.CreateCommentRequest(summaryMarkdown);
    if (existing.isPresent()) {
      commentClient.updateComment(auth, ACCEPT, owner, repo, existing.get().id(), request);
    } else {
      commentClient.createComment(auth, ACCEPT, owner, repo, prNumber, request);
    }
  }

  /**
   * Applies or suggests the model's labels (best-effort; never blocks the review). The suggested
   * labels come from the model summary and are reconciled against the repo's existing labels.
   */
  void applyLabels(
      String auth,
      ReviewOrchestrator.ReviewRequest req,
      ReviewResult result,
      ReviewResponse aiResponse,
      ReviewContextLoader.ReviewContext ctx) {
    labeler.applyOrSuggest(
        new PrLabeler.LabelRequest(
            auth,
            req.owner(),
            req.repo(),
            req.prNumber(),
            result.isFirstReview(),
            aiResponse.summary() != null ? aiResponse.summary().suggestedLabels() : List.of(),
            ctx.repoLabels()));
  }

  /**
   * Posts the "review could not be completed" notice when a review fails before its result was
   * surfaced. Best-effort: a failure to post it is logged, not propagated.
   */
  void postFailureNotice(String auth, String owner, String repo, int prNumber) {
    postFailureNoticeComment(
        auth,
        owner,
        repo,
        prNumber,
        """
            ⚠️ **ThrillhouseBot review could not be completed.**

            The review service encountered an error. \
            Please reply with `/review` or `@Thrillhousebot review` to retry.""");
  }

  /**
   * The failure notice for a review that failed because the model's response was cut at its length
   * cap (#500 scope B). A truncation is deterministic — the generic notice's bare {@code /review}
   * advice is exactly the knowably-futile retry #495 exists to prevent — so this variant names the
   * cap and the knob to raise instead: the active model's {@code max-output-tokens}, plus {@code
   * REVIEW_CONCISE_MAX_OUTPUT_TOKENS} when the truncated call ran on the concise named model (its
   * cap is configured separately).
   */
  void postTruncationFailureNotice(
      String auth, String owner, String repo, int prNumber, boolean conciseModelImplicated) {
    var conciseClause =
        conciseModelImplicated
            ? " The truncated call ran on the concise model, so raise"
                + " `REVIEW_CONCISE_MAX_OUTPUT_TOKENS` as well."
            : "";
    postFailureNoticeComment(
        auth,
        owner,
        repo,
        prNumber,
        """
            ⚠️ **ThrillhouseBot review could not be completed.**

            The model's response was cut at its response-length cap (`finish_reason=length`), \
            so the review output was incomplete. This failure is deterministic: retrying \
            without changing configuration would be cut at the same point. Raise the active \
            model's `max-output-tokens` (or leave it unset to use the provider default), then \
            run `/review` again."""
            + conciseClause);
  }

  /**
   * The failure notice for a review request the provider rejected for exceeding the model's context
   * window (#622). Deterministic like a truncation — the generic notice's bare {@code /review}
   * advice would repeat a request that is rejected identically every time — so this variant names
   * the cause and the knob that shrinks the request: {@code REVIEW_MAX_INPUT_TOKENS}, whose token
   * budgeting batches or clips the diff to fit.
   */
  void postContextWindowFailureNotice(String auth, String owner, String repo, int prNumber) {
    postFailureNoticeComment(
        auth,
        owner,
        repo,
        prNumber,
        """
            ⚠️ **ThrillhouseBot review could not be completed.**

            The provider rejected the review request because it exceeds the model's context \
            window — the diff and its context are too large for the model. This failure is \
            deterministic: retrying the identical request would be rejected identically. Lower \
            `REVIEW_MAX_INPUT_TOKENS` so the planned material fits the window (token budgeting \
            then batches or clips the diff), then run `/review` again.""");
  }

  private void postFailureNoticeComment(
      String auth, String owner, String repo, int prNumber, String body) {
    try {
      commentClient.createComment(
          auth, ACCEPT, owner, repo, prNumber, new GitHubCommentClient.CreateCommentRequest(body));
    } catch (RuntimeException commentError) {
      Log.warnf(
          commentError,
          "Failed to post review failure comment for %s/%s #%d",
          owner,
          repo,
          prNumber);
    }
  }

  /**
   * Parameter object for {@link #postReview(PostReviewRequest)}.
   *
   * @param summaryPosted {@code true} only when this run's PR summary comment was actually created
   *     ({@code publishSummary} returned {@code true}) — never assumed from {@code isFirstReview}
   *     or {@code forceSummary} alone, so a failed or skipped summary post can never suppress the
   *     review too and leave the run with no visible outcome at all.
   */
  record PostReviewRequest(
      String auth,
      String owner,
      String repo,
      int prNumber,
      String commitSha,
      ReviewResult result,
      DiffLineResolver lineResolver,
      boolean summaryPosted) {}

  /**
   * Back-compat convenience for tests and callers with no summary outcome to report. Defaults
   * {@code summaryPosted} to {@code false}, so no review-suppressing skip can fire.
   */
  void postReview(
      String auth,
      String owner,
      String repo,
      int prNumber,
      String commitSha,
      ReviewResult result,
      DiffLineResolver lineResolver) {
    postReview(
        new PostReviewRequest(auth, owner, repo, prNumber, commitSha, result, lineResolver, false));
  }

  void postReview(PostReviewRequest post) {
    var auth = post.auth();
    var owner = post.owner();
    var repo = post.repo();
    var prNumber = post.prNumber();
    var commitSha = post.commitSha();
    var result = post.result();
    var lineResolver = post.lineResolver();
    if (!result.hasIssues()) {
      // Summary-only re-run: skip restating a clean verdict when the summary re-posted; first
      // review, unresolved previous, and truncation still post.
      if (post.summaryPosted()
          && !result.isFirstReview()
          && result.unresolvedPreviousCount() == 0
          && !result.truncated()) {
        Log.infof(
            "Skipping the no-issues review for %s/%s #%d — summary-only re-run on an"
                + " already-reviewed PR",
            owner, repo, prNumber);
        return;
      }
      postNoIssuesReview(auth, owner, repo, prNumber, commitSha, result, post.summaryPosted());
      return;
    }

    var inline = postInlineComments(auth, owner, repo, prNumber, commitSha, result, lineResolver);
    var event =
        result.reviewState() == ReviewState.REQUEST_CHANGES ? EVENT_REQUEST_CHANGES : EVENT_COMMENT;

    if (inline.posted() == 0) {
      // Nothing landed inline: either anchoring failed, the comment cap skipped everything, or
      // every finding was routed to the summary as lower-confidence. Surface them in the review
      // body so follow-ups (which do not re-post the summary) still carry the signal.
      if (!inline.unanchored().isEmpty() || !inline.capSkipped().isEmpty()) {
        Log.warnf(
            "No inline comments posted for %s/%s #%d — surfacing findings in the review body",
            owner, repo, prNumber);
      } else {
        Log.infof(
            "No inline comments for %s/%s #%d — lower-confidence findings routed to the summary",
            owner, repo, prNumber);
      }
      var fallbackParts = new ArrayList<>(skippedFindingsBodyParts(inline));
      appendTruncationNotice(fallbackParts, result);
      createReviewWithFallback(
          auth,
          owner,
          repo,
          prNumber,
          new GitHubReviewClient.CreateReviewRequest(
              commitSha, String.join("\n\n", fallbackParts), event, List.of()));
      return;
    }

    var bodyParts = new ArrayList<String>();
    if (result.reviewState() == ReviewState.REQUEST_CHANGES) {
      bodyParts.add("ThrillhouseBot requested changes — see inline comments on the diff.");
    }
    bodyParts.addAll(skippedFindingsBodyParts(inline));
    appendTruncationNotice(bodyParts, result);
    if (!bodyParts.isEmpty()) {
      createReviewWithFallback(
          auth,
          owner,
          repo,
          prNumber,
          new GitHubReviewClient.CreateReviewRequest(
              commitSha, String.join("\n\n", bodyParts), event, List.of()));
    }
  }

  /**
   * Adds the partial-review notice to a findings review body when the diff was truncated. The
   * truncation banner otherwise lives only in the summary comment, which is posted best-effort and
   * only on first reviews — if that post fails or is skipped, the review body is the sole PR
   * surface left to disclose the partial coverage. A duplicated notice when the summary does post
   * is the acceptable cost of never dropping it (same standard as {@link #noIssuesBody}).
   */
  private static void appendTruncationNotice(List<String> bodyParts, ReviewResult result) {
    if (result.truncated()) {
      bodyParts.add(result.truncationNotice().strip());
    }
  }

  /**
   * Review-body sections for findings not posted inline — un-anchored, cap-skipped, and/or
   * confidence-routed to the summary's "Things to double-check" section.
   */
  private static List<String> skippedFindingsBodyParts(InlineCommentResult inline) {
    var parts = new ArrayList<String>();
    if (!inline.unanchored().isEmpty()) {
      parts.add(unanchoredFindingsBody(inline.unanchored()));
    }
    if (!inline.capSkipped().isEmpty()) {
      parts.add(capSkippedFindingsBody(inline.capSkipped()));
    }
    if (!inline.confidenceSkipped().isEmpty()) {
      parts.add(confidenceSkippedFindingsBody(inline.confidenceSkipped()));
    }
    return parts;
  }

  private static String unanchoredFindingsBody(List<Finding> findings) {
    var sb = new StringBuilder();
    sb.append("ThrillhouseBot found ")
        .append(findings.size())
        .append(" issue(s) that could not be anchored to the current diff:\n\n");
    appendFindingList(sb, findings);
    return sb.toString();
  }

  private static String capSkippedFindingsBody(List<Finding> findings) {
    var sb = new StringBuilder();
    sb.append("ThrillhouseBot found ")
        .append(findings.size())
        .append(
            """
             issue(s) not posted inline because the per-run comment cap was reached — re-run \
            `/review` or raise the comment cap:

            """);
    appendFindingList(sb, findings);
    return sb.toString();
  }

  private static String confidenceSkippedFindingsBody(List<Finding> findings) {
    var sb = new StringBuilder();
    sb.append("ThrillhouseBot noted ")
        .append(findings.size())
        .append(
            """
             lower-confidence item(s) under **Things to double-check** in the PR summary \
            (not posted as inline threads):

            """);
    appendFindingList(sb, findings);
    return sb.toString();
  }

  private static void appendFindingList(StringBuilder sb, List<Finding> findings) {
    for (Finding f : findings) {
      sb.append("- **")
          .append(f.risk().name())
          .append(":** ")
          .append(f.title())
          .append(" (`")
          .append(f.file())
          .append(":")
          .append(f.line())
          .append("`)");
      if (f.description() != null && !f.description().isBlank()) {
        sb.append("\n  ").append(f.description().strip().replace("\n", "\n  "));
      }
      sb.append("\n");
    }
  }

  /**
   * Posts the review when there are no new findings: a bare APPROVE, or a COMMENT explaining why. A
   * first-review COMMENT held back solely by CI is skipped only when the PR summary comment
   * actually posted — its Required CI Checks table already carries the same pending/failed list, so
   * a second surface with identical copy is pure noise (#334). When the summary post failed or was
   * skipped, the COMMENT review is the round's only visible signal and always posts.
   */
  private void postNoIssuesReview(
      String auth,
      String owner,
      String repo,
      int prNumber,
      String commitSha,
      ReviewResult result,
      boolean summaryPosted) {
    if (result.reviewState() == ReviewState.APPROVE) {
      var req =
          new GitHubReviewClient.CreateReviewRequest(
              commitSha,
              result.isFirstReview() ? "" : PrSummaryGenerator.ZERO_ISSUES_MESSAGE,
              EVENT_APPROVE,
              List.of());
      createReviewWithFallback(auth, owner, repo, prNumber, req);
      return;
    }
    if (summaryPosted
        && result.reviewState() == ReviewState.COMMENT
        && result.isFirstReview()
        && result.unresolvedPreviousCount() == 0
        && !result.truncated()) {
      return;
    }
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
    long unresolved = result.unresolvedPreviousCount();
    var sb = new StringBuilder();
    boolean ciHeld = false;
    if (!result.offendingCiChecks().isEmpty()) {
      sb.append(ReviewResult.NO_ISSUES_CI_PENDING_LEAD_IN).append("\n");
      for (var check : result.offendingCiChecks()) {
        String status = check.isFailing() ? "failed" : CI_PENDING;
        sb.append("- Check **").append(check.name()).append("** is ").append(status).append("\n");
      }
      ciHeld = true;
    }
    if (result.ciUnreadable()) {
      sb.append(ReviewResult.NO_ISSUES_CI_UNREADABLE_LEAD_IN).append("\n");
      ciHeld = true;
    }
    if (unresolved > 0) {
      sb.append(ciHeld ? "\nAdditionally, " : "")
          .append(ReviewResult.unresolvedPreviousMessage(unresolved));
      appendReopenedDeclineNotes(sb, result);
    }
    if (result.truncated()) {
      if (!sb.isEmpty()) {
        sb.append("\n\n");
      }
      sb.append(result.truncationNotice().strip());
    }
    return sb.toString();
  }

  /**
   * Appends the explanatory note of each unresolved previous finding whose decline the re-check
   * overturned ({@link RebuttalContradiction}) — the reply's premise, the contradicting line, and
   * the "reply again to keep the decline" escape hatch. Without this the maintainer sees only the
   * generic "N previous finding(s) remain unresolved" line and no reason their decline was not
   * honored (F6). Only the reopened-decline note is surfaced; the backstop's generic carry-over
   * note and the model's own unresolved notes stay out of the review body.
   */
  private static void appendReopenedDeclineNotes(StringBuilder sb, ReviewResult result) {
    for (var status : result.previousStatuses()) {
      if ("unresolved".equalsIgnoreCase(status.status())
          && status.note() != null
          && status.note().startsWith(RebuttalContradiction.NOTE_LEAD_IN)) {
        sb.append("\n\n").append(status.note().strip());
      }
    }
  }

  /**
   * How many findings anchored as inline comments, the ones that could not be anchored, the ones
   * skipped because {@code maxReviewComments} was reached (never tried for anchoring), and the ones
   * withheld because confidence is low (routed to the summary instead).
   */
  record InlineCommentResult(
      int posted,
      List<Finding> unanchored,
      List<Finding> capSkipped,
      List<Finding> confidenceSkipped) {}

  /**
   * Posts each finding as its own pull request review comment on the diff. Individual comments
   * survive 422s that would reject an entire batched review. Findings whose line falls outside the
   * diff (or are otherwise rejected) are returned as {@code unanchored} so the caller can still
   * report them in the review body rather than dropping them. Low-confidence medium/low findings
   * are skipped here and surfaced in the PR summary's "Things to double-check" section instead.
   */
  InlineCommentResult postInlineComments(
      String auth,
      String owner,
      String repo,
      int prNumber,
      String commitSha,
      ReviewResult result,
      DiffLineResolver lineResolver) {
    var target = new CommentTarget(auth, owner, repo, prNumber, commitSha);
    var posted = 0;
    var unanchored = new ArrayList<Finding>();
    var capSkipped = new ArrayList<Finding>();
    var confidenceSkipped = new ArrayList<Finding>();
    var maxComments = config.review().maxReviewComments();
    for (var i = 0; i < result.findings().size(); i++) {
      // The 1-based index doubles as the finding's id in the persisted response and the hidden
      // comment marker, keeping thread matching deterministic on follow-up reviews.
      var finding = result.findings().get(i);
      if (!finding.postsInline()) {
        confidenceSkipped.add(finding);
      } else if (posted >= maxComments) {
        capSkipped.add(finding);
      } else if (postFindingComment(target, finding, i + 1, lineResolver)) {
        posted++;
      } else {
        unanchored.add(finding);
      }
    }
    return new InlineCommentResult(
        posted, List.copyOf(unanchored), List.copyOf(capSkipped), List.copyOf(confidenceSkipped));
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

    // A GitHub suggestion overwrites the whole commented range, so multi-line old code needs a
    // multi-line anchor; an unresolvable anchor leaves the range empty and the comment single-line.
    var range =
        finding.hasSuggestion()
            ? lineResolver.resolveSuggestionRange(finding.file(), finding.suggestionOld())
            : Optional.<DiffLineResolver.LineRange>empty();

    // A multi-line suggestion posted against a single line corrupts code when applied (only the
    // anchor line is overwritten), so with no resolvable range the suggestion block is dropped.
    // hasSuggestion() guarantees a non-blank suggestionOld, so the newline check is safe.
    var multiLineSuggestion =
        finding.hasSuggestion() && finding.suggestionOld().strip().contains("\n");
    var includeSuggestion = finding.hasSuggestion() && (!multiLineSuggestion || range.isPresent());

    if (tryPostInlineComment(target, finding, findingId, resolvedLine, range, includeSuggestion)
        || (includeSuggestion
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
        // A review from a since-deleted account serializes as user:null.
        if ("PENDING".equals(review.state())
            && review.user() != null
            && botIdentity.matches(review.user().login())) {
          reviewClient.deletePendingReview(auth, ACCEPT, owner, repo, prNumber, review.id());
          Log.debugf(
              "Dismissed pending review %d on %s/%s #%d", review.id(), owner, repo, prNumber);
        }
      }
    } catch (RuntimeException e) {
      Log.debug("Could not dismiss pending bot reviews (continuing)", e);
    }
  }

  /** Lead-in for a review body preserved as an issue comment after GitHub refused it (#704). */
  static final String REVIEW_REFUSED_NOTE =
      "⚠️ GitHub refused the review post, so ThrillhouseBot is posting the review as a regular"
          + " comment instead.";

  /**
   * Submits a PR review, falling back to a summary-only review when inline comments are rejected
   * (e.g. stale line numbers after a force-push), and to an issue comment carrying the same body
   * when GitHub definitely refused the review post (#704) — a rejected summary-only review used to
   * discard the whole generation behind a "review could not be completed" notice. Only a
   * response-carrying 4xx counts as a refusal, and every attempt made here must have been one; an
   * ambiguous failure (timeout, connection reset, 5xx) on any attempt may have landed that
   * attempt's review, so it throws {@link ReviewPostException} instead of risking a duplicate — as
   * does a refusal whose comment fallback fails too.
   */
  void createReviewWithFallback(
      String auth,
      String owner,
      String repo,
      int prNumber,
      GitHubReviewClient.CreateReviewRequest req) {
    RuntimeException rejection;
    boolean anyAmbiguous;
    try {
      reviewClient.createReview(auth, ACCEPT, owner, repo, prNumber, req);
      return;
    } catch (RuntimeException e) {
      logReviewRejection(e, owner, repo, prNumber);
      rejection = e;
      anyAmbiguous = !isRefusal(e);
    }
    // CreateReviewRequest's compact constructor normalizes a null comments list to List.of().
    if (!req.comments().isEmpty()) {
      Log.warnf(
          rejection,
          "PR review with inline comments rejected for %s/%s #%d — retrying without comments",
          owner,
          repo,
          prNumber);
      var fallback =
          new GitHubReviewClient.CreateReviewRequest(
              req.commitId(), req.body(), req.event(), List.of());
      try {
        reviewClient.createReview(auth, ACCEPT, owner, repo, prNumber, fallback);
        return;
      } catch (RuntimeException retryFailure) {
        logReviewRejection(retryFailure, owner, repo, prNumber);
        rejection = retryFailure;
        anyAmbiguous |= !isRefusal(retryFailure);
      }
    }
    // The comment fallback fires only when EVERY attempt was a definite refusal — a
    // response-carrying 4xx, where GitHub rejected the request and the review provably does not
    // exist. An ambiguous failure (a timeout, a connection reset, a 5xx) on any attempt is one
    // where that attempt's review may well have landed, so posting the body again would duplicate
    // it while asserting a refusal the code cannot support; those propagate as before.
    if (anyAmbiguous) {
      throw new ReviewPostException(
          "GitHub review rejected for " + owner + "/" + repo + " #" + prNumber, rejection);
    }
    postReviewBodyAsComment(auth, owner, repo, prNumber, req.body(), rejection);
  }

  /**
   * Whether this failure is a definite refusal: it carries GitHub's response and that response is a
   * 4xx, so the request was rejected and the review was not created. False for anything ambiguous —
   * no response at all, or a 5xx — where the write may have landed.
   */
  private static boolean isRefusal(RuntimeException rejection) {
    return webApplicationFailure(rejection)
        .map(WebApplicationException::getResponse)
        .map(jakarta.ws.rs.core.Response::getStatus)
        .filter(status -> status >= 400 && status < 500)
        .isPresent();
  }

  /**
   * Preserves a refused review as an issue comment: the same body, prefixed with a note that GitHub
   * refused the review post. Goes through {@link GitHubCommentClient#createComment}, so the comment
   * gets the same body cap (#487) and paced/backed-off write path (#597/#568) as every other
   * conversation comment. A blank body (a bare first-review APPROVE) is replaced with the
   * clean-review message so the comment still states an outcome.
   */
  private void postReviewBodyAsComment(
      String auth, String owner, String repo, int prNumber, String body, RuntimeException cause) {
    var outcome = body == null || body.isBlank() ? PrSummaryGenerator.ZERO_ISSUES_MESSAGE : body;
    try {
      commentClient.createComment(
          auth,
          ACCEPT,
          owner,
          repo,
          prNumber,
          new GitHubCommentClient.CreateCommentRequest(REVIEW_REFUSED_NOTE + "\n\n" + outcome));
    } catch (RuntimeException commentFailure) {
      var failure =
          new ReviewPostException(
              "GitHub review rejected for "
                  + owner
                  + "/"
                  + repo
                  + " #"
                  + prNumber
                  + " and the comment fallback failed too",
              cause);
      failure.addSuppressed(commentFailure);
      throw failure;
    }
    Log.warnf(
        "GitHub review rejected for %s/%s #%d — the review body was preserved as an issue comment",
        owner, repo, prNumber);
  }

  /**
   * Logs what GitHub actually said when it rejected a review post (#704). The runtime's default
   * exception mapper surfaces the status and nothing else, so without this line a 422 here is
   * undiagnosable after the fact. The body is read through {@link GitHubApiError}, which redacts
   * anything credential-shaped and caps the length before it reaches the log.
   */
  private static void logReviewRejection(
      RuntimeException rejection, String owner, String repo, int prNumber) {
    var diagnostics =
        webApplicationFailure(rejection)
            .flatMap(GitHubApiError::of)
            .map(GitHubApiError::diagnostics)
            .orElse("no HTTP response to read (" + rejection + ")");
    Log.warnf(
        "GitHub rejected the review post for %s/%s #%d: %s", owner, repo, prNumber, diagnostics);
  }

  /** The HTTP-carrying failure in the cause chain, if any — the one whose response can be read. */
  private static Optional<WebApplicationException> webApplicationFailure(Throwable rejection) {
    for (Throwable t = rejection; t != null; t = t.getCause()) {
      if (t instanceof WebApplicationException web) {
        return Optional.of(web);
      }
    }
    return Optional.empty();
  }

  /**
   * Resolves the GitHub threads of previous findings the model judged resolved (fix landed) or
   * justified (a reply explains the deferral), so addressed feedback stops cluttering the PR.
   * Best-effort: the review outcome is already posted when this runs.
   *
   * <p>Takes the <em>effective</em> statuses the verdict computed ({@link
   * ReviewResult#previousStatuses}), not the raw model statuses: a decline the re-check overturned
   * is {@code unresolved} here, so its thread is correctly left open rather than resolved on a
   * status the code already rejected (F7).
   */
  void resolveAddressedThreads(
      String auth,
      ReviewOrchestrator.ReviewRequest req,
      List<ReviewResponse.Finding> previousFindings,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      List<ReviewResult.PreviousFindingStatus> statuses) {
    try {
      List<Integer> addressed =
          statuses.stream()
              .filter(
                  s ->
                      "resolved".equalsIgnoreCase(s.status())
                          || "justified".equalsIgnoreCase(s.status()))
              .map(ReviewResult.PreviousFindingStatus::id)
              .toList();
      if (addressed.isEmpty() || inlineComments.isEmpty()) {
        return;
      }
      var rootByFinding =
          followUpAnalyzer.matchFindingThreads(previousFindings, inlineComments, botIdentity);
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
}
