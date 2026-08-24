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

import dev.thiagogonzaga.thrillhousebot.LogSafe;
import dev.thiagogonzaga.thrillhousebot.config.ActiveModelSettings;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.RepoSettingsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.ImprovementParser;
import dev.thiagogonzaga.thrillhousebot.review.ai.ImprovementResponse;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrImproveAssistant;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrImproveAssistantPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrSuggestionPrompts;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Runs the {@code /improve} command: a whole-PR improvement pass over the diff that proposes
 * committable changes across the change set, rather than the correctness findings the review
 * pipeline produces.
 *
 * <p>Loads the diff, current title/body and repository instructions and plans the token-budgeted
 * batches through {@link AbstractPrSuggestionGenerator} (so the coverage planning, instructions
 * fallback chain and fail-soft degradation are the ones the other on-request commands use), asks
 * the {@link PrImproveAssistant} for improvements once per batch, then posts each one that anchors
 * cleanly onto the diff as an inline {@code ```suggestion} block. Improvements that cannot be
 * anchored are still surfaced as copy-paste blocks in the run's summary comment, which also carries
 * the partial-coverage disclosure when the token budget left files uncovered.
 *
 * <p>Authorization, the pause check and the {@code improve-enabled} kill switch are enforced by
 * {@link dev.thiagogonzaga.thrillhousebot.webhook.CommentCommandService} before this runs.
 */
@ApplicationScoped
public class PrImprovementService extends AbstractPrSuggestionGenerator {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final String COMMAND = "/improve";

  static final String NO_CHANGES =
      "✨ ThrillhouseBot found no reviewable changes to improve in this PR.";
  static final String GENERATION_FAILED =
      "✨ ThrillhouseBot could not generate improvements for this PR. Please try `/improve` again.";

  /**
   * Posted when the token budget could fit no part of the diff: the plan is empty but the omitted
   * list is not, so "no reviewable changes" would be false — there were changes, they could not be
   * read. Mirrors the sibling generators, which all name the budget as the cause.
   */
  static final String NOT_COVERED =
      "✨ ThrillhouseBot could not fit any part of this PR into the per-call input-token budget,"
          + " so it has nothing to improve. Raise the input-token budget and re-run `/improve`.";

  static final String NOTHING_TO_IMPROVE =
      "✨ ThrillhouseBot has no improvements to suggest for the changes in this PR.";

  private static final String FOOTER =
      """


      ---
      *Nothing was committed — review each suggestion and commit the ones you want. \
      Re-run with `/improve`.*""";

  private final GitHubReviewClient reviewClient;
  private final GitHubCommentClient commentClient;
  private final SuggestionFormatter suggestionFormatter;
  private final PrImproveAssistant improveAssistant;
  private final ImprovementParser parser;

  @Inject
  public PrImprovementService(
      @RestClient GitHubPullRequestClient prClient,
      @RestClient GitHubReviewClient reviewClient,
      @RestClient GitHubCommentClient commentClient,
      ReviewDiffFormatter diffFormatter,
      SuggestionFormatter suggestionFormatter,
      InstructionsResolver instructionsResolver,
      PrImproveAssistant improveAssistant,
      ImprovementParser parser,
      RepoSettingsResolver repoSettingsResolver,
      DiffBudgetPlanner budgetPlanner,
      ActiveModelSettings activeModel,
      ThrillhouseConfig config) {
    super(
        prClient,
        diffFormatter,
        instructionsResolver,
        repoSettingsResolver,
        budgetPlanner,
        activeModel,
        config);
    this.reviewClient = reviewClient;
    this.commentClient = commentClient;
    this.suggestionFormatter = suggestionFormatter;
    this.improveAssistant = improveAssistant;
    this.parser = parser;
  }

  /** PR coordinates for one {@code /improve} run. */
  public record ImproveTask(
      String owner, String repo, int prNumber, String defaultBranch, long installationId) {
    CommandTarget target() {
      return new CommandTarget(owner, repo, defaultBranch, installationId);
    }
  }

  /**
   * Generates and posts the improvement suggestions. Swallows every failure after logging it — this
   * runs off the webhook ACK path, so it must never throw back to the caller.
   *
   * @param auth the {@code Authorization} header for the installation (already minted by the
   *     caller)
   */
  @ActivateRequestContext
  public void handle(ImproveTask task, String auth) {
    try {
      var inputs =
          loadInputs(
              task.owner(),
              task.repo(),
              task.prNumber(),
              task.defaultBranch(),
              task.installationId(),
              auth,
              COMMAND);
      if (inputs == null) {
        postComment(auth, task, NO_CHANGES);
        return;
      }
      // Resolved once and threaded downstream: the batches and the line map must agree on which
      // files are in scope, or an out-of-scope file could still be anchored onto.
      var reviewable = respectPerRepoIgnores(task.target(), COMMAND, inputs.reviewableFiles());
      // No reduce call: the summary comment is assembled locally, so the whole max-ai-calls
      // allowance goes to batches and an invocation costs at most what one review costs.
      var plan =
          planBatches(
              reviewable,
              inputs,
              PrImproveAssistantPrompts.systemPrompt(),
              PrSuggestionPrompts.userPrompt(),
              0);
      if (plan.batches().isEmpty()) {
        postComment(auth, task, plan.truncated() ? NOT_COVERED + disclosure(plan) : NO_CHANGES);
        return;
      }
      var generated = generate(inputs, plan);
      if (generated == null) {
        postComment(auth, task, GENERATION_FAILED);
        return;
      }
      var outcome = post(auth, task, inputs, reviewable, generated.improvements());
      postComment(auth, task, summaryMessage(outcome, plan, generated.failedBatches()));
      Log.infof(
          "/improve posted %d committable suggestion(s) and %d copy-paste block(s)"
              + " from %d batch(es) on %s/%s #%d",
          outcome.committable(),
          outcome.copyPaste().size(),
          plan.batches().size(),
          task.owner(),
          task.repo(),
          task.prNumber());
    } catch (RuntimeException e) {
      Log.warnf(
          e, "Failed to handle /improve on %s/%s #%d", task.owner(), task.repo(), task.prNumber());
    }
  }

  /** Improvements merged across batches, plus how many batch calls failed outright. */
  private record Generated(List<ImprovementResponse.Improvement> improvements, int failedBatches) {}

  /**
   * Runs one assistant call per planned batch and merges the results. A batch that fails or returns
   * unparseable JSON is skipped rather than failing the run — the other batches' improvements are
   * still worth posting — and the count is disclosed. Returns {@code null} only when every batch
   * failed, which is the case that warrants the failure notice.
   */
  private Generated generate(Inputs inputs, DiffBudgetPlanner.BudgetPlan plan) {
    var merged = new ArrayList<ImprovementResponse.Improvement>();
    var seen = new HashSet<String>();
    int failed = 0;
    for (var batch : plan.batches()) {
      var response = generateOne(inputs, batch);
      if (response == null) {
        failed++;
        continue;
      }
      for (var improvement : response.improvements()) {
        // Two batches must never propose the same line twice: batches hold disjoint files, but a
        // model can still quote a file it saw named in another batch's context.
        if (improvement.isPostable() && seen.add(dedupeKey(improvement))) {
          merged.add(improvement);
        }
      }
    }
    if (failed == plan.batches().size()) {
      return null;
    }
    return new Generated(List.copyOf(merged), failed);
  }

  /** One batch's assistant call and parse, or {@code null} when either step fails. */
  private ImprovementResponse generateOne(Inputs inputs, DiffBudgetPlanner.DiffBatch batch) {
    String raw =
        callAssistant(
            COMMAND,
            () ->
                improveAssistant.improve(
                    PromptTemplateEscaper.fence(batch.text()),
                    PromptTemplateEscaper.escape(inputs.title()),
                    PromptTemplateEscaper.escape(inputs.body()),
                    PromptTemplateEscaper.escape(inputs.instructions())));
    if (raw == null) {
      return null;
    }
    try {
      return parser.parse(raw);
    } catch (RuntimeException e) {
      Log.warnf(e, "Could not parse a /improve batch response — skipping that batch");
      return null;
    }
  }

  /** Identity of a proposed change for dedupe: the same file and line is the same suggestion. */
  private static String dedupeKey(ImprovementResponse.Improvement improvement) {
    return improvement.file().strip() + ":" + improvement.line();
  }

  /**
   * How one {@code /improve} run landed: how many improvements became committable suggestions, the
   * ones that could not be anchored (rendered as copy-paste blocks in the summary), and how many
   * were left out by the per-run comment cap.
   */
  record ImproveOutcome(
      int committable, List<ImprovementResponse.Improvement> copyPaste, int skippedByCap) {}

  /**
   * Posts every postable improvement that anchors onto the diff as an inline committable
   * suggestion, collecting the rest for the summary's copy-paste section. Both kinds count against
   * the per-run comment cap so a large PR can never produce an unbounded run.
   *
   * <p>The line map is built once from the run's whole effective file list — every batch's files
   * together, never one batch's — so an improvement produced by any batch anchors to its correct
   * absolute line. It is the same list the batches were planned over, so a file the repository
   * asked the bot to ignore cannot be anchored onto either, even if the model names one.
   */
  private ImproveOutcome post(
      String auth,
      ImproveTask task,
      Inputs inputs,
      List<GitHubPullRequestClient.FileDiff> reviewable,
      List<ImprovementResponse.Improvement> improvements) {
    var lineResolver = new DiffLineResolver(diffFormatter().patchesByReviewableFiles(reviewable));
    boolean canPostInline = inputs.headSha() != null && !inputs.headSha().isBlank();
    if (!canPostInline) {
      Log.debugf(
          "/improve has no head SHA for %s/%s #%d — falling back to copy-paste blocks",
          task.owner(), task.repo(), task.prNumber());
    }
    int cap = config().review().maxReviewComments();
    int committable = 0;
    var copyPaste = new ArrayList<ImprovementResponse.Improvement>();
    int skippedByCap = 0;
    // Every improvement here is already postable and deduped: generate() filters as it merges the
    // batches, so this loop only decides inline-vs-copy-paste and enforces the cap.
    for (int i = 0; i < improvements.size(); i++) {
      if (committable + copyPaste.size() >= cap) {
        skippedByCap = improvements.size() - i;
        Log.debugf(
            "/improve reached the %d-comment cap on %s/%s #%d — %d further improvement(s) dropped",
            cap, task.owner(), task.repo(), task.prNumber(), skippedByCap);
        break;
      }
      var improvement = improvements.get(i);
      if (canPostInline && postInline(auth, task, inputs.headSha(), improvement, lineResolver)) {
        committable++;
      } else {
        copyPaste.add(improvement);
      }
    }
    return new ImproveOutcome(committable, List.copyOf(copyPaste), skippedByCap);
  }

  /**
   * Posts one improvement as an inline committable suggestion, or returns {@code false} when it
   * cannot be anchored onto the diff (so the caller falls back to a copy-paste block).
   *
   * <p>A single-line replacement must anchor at the reported line exactly and that line's text must
   * match the quoted {@code suggestion_old} — a snapped-to neighbour or a stale quote would rewrite
   * the wrong line on commit. A multi-line replacement is anchored by its verbatim range instead,
   * so it overwrites the whole span rather than only its first line.
   */
  private boolean postInline(
      String auth,
      ImproveTask task,
      String commitSha,
      ImprovementResponse.Improvement improvement,
      DiffLineResolver lineResolver) {
    boolean multiLine = improvement.suggestionOld().strip().contains("\n");
    var resolved = lineResolver.resolveRightSideLine(improvement.file(), improvement.line());
    if (resolved.isEmpty() || (!multiLine && resolved.getAsInt() != improvement.line())) {
      Log.debugf(
          "/improve cannot anchor %s:%d — the line is not in the diff",
          LogSafe.oneLine(improvement.file()), improvement.line());
      return false;
    }
    Optional<DiffLineResolver.LineRange> range =
        multiLine
            ? lineResolver.resolveSuggestionRange(improvement.file(), improvement.suggestionOld())
            : Optional.empty();
    if (multiLine && range.isEmpty()) {
      Log.debugf(
          "/improve cannot anchor the multi-line replacement at %s:%d",
          LogSafe.oneLine(improvement.file()), improvement.line());
      return false;
    }
    if (!multiLine && !quotesCurrentLine(lineResolver, improvement)) {
      Log.debugf(
          "/improve skipped %s:%d — the quoted code does not match the line in the diff",
          LogSafe.oneLine(improvement.file()), improvement.line());
      return false;
    }
    Integer startLine = range.map(DiffLineResolver.LineRange::startLine).orElse(null);
    int endLine = range.map(DiffLineResolver.LineRange::endLine).orElse(improvement.line());
    String body =
        suggestionFormatter.formatImprovementComment(
            improvement.title(),
            improvement.category(),
            improvement.rationale(),
            improvement.suggestionOld(),
            improvement.suggestionNew());
    try {
      reviewClient.createPullRequestComment(
          auth,
          ACCEPT,
          task.owner(),
          task.repo(),
          task.prNumber(),
          new GitHubReviewClient.CreatePullRequestCommentRequest(
              commitSha,
              body,
              improvement.file(),
              endLine,
              "RIGHT",
              startLine,
              startLine != null ? "RIGHT" : null));
      return true;
    } catch (RuntimeException e) {
      Log.debugf(
          e,
          "GitHub rejected the /improve comment for %s:%d",
          LogSafe.oneLine(improvement.file()),
          endLine);
      return false;
    }
  }

  /**
   * Whether the improvement's {@code suggestion_old} is the text actually on that line of the diff,
   * <em>including its leading indentation</em>.
   *
   * <p>Indentation is part of the match on purpose. Committing a suggestion replaces the anchored
   * line with {@code suggestion_new} verbatim, so a model that re-indented the code it quoted has
   * almost certainly re-indented the replacement too — and committing that would silently reflow
   * the line, or in an indentation-sensitive language change what the code means. A quote that does
   * not reproduce the line exactly is therefore treated as unanchorable, and the improvement
   * degrades to a copy-paste block a human applies deliberately. Only trailing whitespace is
   * tolerated: it is invisible, and formatters strip it anyway.
   *
   * <p>Unknown line text fails closed for the same reason — an unverifiable line must not be
   * rewritten on the author's behalf. The resolver records text for every line it resolves, so that
   * is a guard against future divergence rather than a path taken today.
   */
  private static boolean quotesCurrentLine(
      DiffLineResolver lineResolver, ImprovementResponse.Improvement improvement) {
    String current = lineResolver.getLineText(improvement.file(), improvement.line());
    return current != null
        && current.stripTrailing().equals(improvement.suggestionOld().stripTrailing());
  }

  /**
   * The summary comment for a completed run: the committable count, a copy-paste section for the
   * improvements that could not be anchored, and the partial-coverage disclosure when the token
   * budget left whole files uncovered — so improvements derived from part of the change set are
   * never presented as if they covered the whole PR (reuses the review path's wording).
   */
  private String summaryMessage(
      ImproveOutcome outcome, DiffBudgetPlanner.BudgetPlan plan, int failedBatches) {
    if (outcome.committable() == 0 && outcome.copyPaste().isEmpty()) {
      String base =
          outcome.skippedByCap() > 0
              ? "✨ ThrillhouseBot posted no improvements: the per-run comment cap was reached, so **"
                  + outcome.skippedByCap()
                  + "** improvement(s) were dropped. Raise the comment cap or re-run `/improve`."
              : NOTHING_TO_IMPROVE;
      return base + batchFailureNote(failedBatches) + disclosure(plan);
    }
    var sb = new StringBuilder("## ✨ ThrillhouseBot — suggested improvements\n\n");
    if (outcome.committable() > 0) {
      sb.append("Proposed **")
          .append(outcome.committable())
          .append("** committable improvement(s) inline on the changed lines.");
    } else {
      sb.append("No improvement could be anchored onto the diff as a committable suggestion.");
    }
    if (!outcome.copyPaste().isEmpty()) {
      sb.append("\n\n<details>\n<summary>")
          .append(outcome.copyPaste().size())
          .append(
              """
               improvement(s) that could not be pinned to the diff — copy these in manually</summary>

              """);
      for (var improvement : outcome.copyPaste()) {
        sb.append(
                suggestionFormatter.formatImprovementBlock(
                    improvement.title(),
                    improvement.category(),
                    improvement.rationale(),
                    improvement.file(),
                    improvement.line(),
                    improvement.suggestionNew()))
            .append("\n");
      }
      sb.append("\n</details>");
    }
    if (outcome.skippedByCap() > 0) {
      sb.append("\n\n")
          .append(outcome.skippedByCap())
          .append(
              " further improvement(s) were not posted because the per-run comment cap was reached"
                  + " — re-run `/improve` after addressing these.");
    }
    return sb.append(batchFailureNote(failedBatches))
        .append(FOOTER)
        .append(disclosure(plan))
        .toString();
  }

  /** Discloses batches whose model call failed, in {@code /improve}'s own terms. */
  private static String batchFailureNote(int failedBatches) {
    return batchFailureNote(
        failedBatches, COMMAND, "improvements for the files in them are missing.");
  }

  private void postComment(String auth, ImproveTask task, String body) {
    commentClient.createComment(
        auth,
        ACCEPT,
        task.owner(),
        task.repo(),
        task.prNumber(),
        new GitHubCommentClient.CreateCommentRequest(body));
  }
}
