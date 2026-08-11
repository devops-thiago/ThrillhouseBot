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
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.ProjectStackResolver;
import dev.thiagogonzaga.thrillhousebot.github.RepoSettingsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.DocGenerationParser;
import dev.thiagogonzaga.thrillhousebot.review.ai.DocGenerationResponse;
import dev.thiagogonzaga.thrillhousebot.review.ai.DocGenerator;
import dev.thiagogonzaga.thrillhousebot.review.ai.DocGeneratorPrompts;
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
 * Generates documentation for the symbols changed in a PR and posts it as committable suggestions,
 * driven by the {@code /add-docs} command.
 *
 * <p>Runs off the webhook ACK path (on the shared review executor, via {@link
 * dev.thiagogonzaga.thrillhousebot.webhook.CommentCommandService}). It loads the diff, project
 * stack and repository instructions, asks the {@link DocGenerator} for docstrings, then posts each
 * as an inline {@code ```suggestion} comment on the symbol's declaration line. Authorization, the
 * pause check and the {@code add-docs-enabled} kill switch are enforced by the caller before this
 * runs.
 *
 * <p>Coverage and scoping come from {@link AbstractPrSuggestionGenerator}, the same seam the other
 * on-request commands use. Two things follow from that, and both matter more here than anywhere
 * else because this command posts committable edits:
 *
 * <ul>
 *   <li>The file list is narrowed by the repository's own ignore globs on top of the
 *       deployment-wide ones, so a path a maintainer told the bot to leave alone is never handed
 *       back as a one-click commit button.
 *   <li>The pass is planned as token-budgeted batches over the whole change set rather than one
 *       call over a {@code max-diff-lines} render, so a large PR no longer has only its first N
 *       lines documented with a footnote about the rest.
 * </ul>
 *
 * <p>Unlike its siblings this command loads the PR itself rather than through {@link
 * AbstractPrSuggestionGenerator#loadInputs}: it distinguishes "the PR could not be loaded" from
 * "the PR has nothing reviewable to document" in what it posts back, and it needs the head SHA
 * before doing any work at all, since without one no suggestion can be anchored.
 */
@ApplicationScoped
public class DocGenerationService extends AbstractPrSuggestionGenerator {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final String COMMAND = "/add-docs";

  static final String NO_PR_DETAILS =
      "📝 ThrillhouseBot could not load this pull request to generate documentation. "
          + "Please try `/add-docs` again.";
  static final String NO_FILES =
      "📝 ThrillhouseBot found no reviewable changed files to document in this PR.";
  static final String GENERATION_FAILED =
      "📝 ThrillhouseBot could not generate documentation for this PR. "
          + "Please try `/add-docs` again.";
  static final String NOTHING_TO_DOCUMENT =
      "📝 ThrillhouseBot found no changed symbols that need documentation in this PR.";

  /**
   * Posted when the model named symbols but every entry it returned was missing a field a
   * suggestion needs — no file, no positive line, no declaration line to anchor against, or no
   * replacement. Neither neighbour fits: {@link #NOTHING_TO_DOCUMENT} would assert a verdict on the
   * code ("no changed symbol needs a doc comment") that a run which never got a usable suggestion
   * cannot support, and {@link #COULD_NOT_PLACE} would blame the diff for a reply that carried no
   * anchor to place in the first place.
   */
  static final String UNUSABLE_SUGGESTIONS =
      "📝 ThrillhouseBot drafted documentation for this PR, but every suggestion came back"
          + " incomplete, so none could be posted. Please try `/add-docs` again.";

  static final String COULD_NOT_PLACE =
      "📝 ThrillhouseBot generated documentation but could not anchor it to the current diff. "
          + "This usually happens after a force-push — try `/add-docs` again.";

  /**
   * Posted when the plan covered nothing: every changed file overflowed even a single-file batch.
   * {@link #NOTHING_TO_DOCUMENT} here would be a verdict on code the model never read, so this
   * names the budget as the cause instead.
   */
  static final String NOT_COVERED =
      "📝 ThrillhouseBot could not fit any part of this PR into the per-call input-token budget, so"
          + " it has nothing to document. Raise the input-token budget and re-run `/add-docs`.";

  /** The summary is assembled locally, so the whole max-ai-calls allowance goes to batch calls. */
  private static final int REDUCE_CALLS = 0;

  private final GitHubAuthClient authClient;
  private final GitHubPullRequestClient prClient;
  private final GitHubReviewClient reviewClient;
  private final GitHubCommentClient commentClient;
  private final SuggestionFormatter suggestionFormatter;
  private final InstructionsResolver instructionsResolver;
  private final ProjectStackResolver projectStackResolver;
  private final DocGenerator docGenerator;
  private final DocGenerationParser parser;

  @Inject
  public DocGenerationService(
      GitHubAuthClient authClient,
      @RestClient GitHubPullRequestClient prClient,
      @RestClient GitHubReviewClient reviewClient,
      @RestClient GitHubCommentClient commentClient,
      ReviewDiffFormatter diffFormatter,
      SuggestionFormatter suggestionFormatter,
      InstructionsResolver instructionsResolver,
      ProjectStackResolver projectStackResolver,
      DocGenerator docGenerator,
      DocGenerationParser parser,
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
    this.authClient = authClient;
    this.prClient = prClient;
    this.reviewClient = reviewClient;
    this.commentClient = commentClient;
    this.suggestionFormatter = suggestionFormatter;
    this.instructionsResolver = instructionsResolver;
    this.projectStackResolver = projectStackResolver;
    this.docGenerator = docGenerator;
    this.parser = parser;
  }

  /**
   * Coordinates of the PR to document, captured from the comment command so the heavy work can run
   * asynchronously.
   */
  public record DocTask(
      String owner, String repo, int prNumber, String defaultBranch, long installationId) {

    CommandTarget target() {
      return new CommandTarget(owner, repo, defaultBranch, installationId);
    }
  }

  /** Generates and posts the documentation suggestions. Swallows every failure after logging it. */
  @ActivateRequestContext
  public void handle(DocTask task) {
    try {
      var auth = authClient.getAuthHeader(task.installationId());
      var pr =
          SoftLoaders.pullRequest(
              prClient, auth, task.owner(), task.repo(), task.prNumber(), COMMAND);
      if (pr == null || pr.head() == null || isBlank(pr.head().sha())) {
        postComment(auth, task, NO_PR_DETAILS);
        return;
      }

      var files =
          SoftLoaders.files(prClient, auth, task.owner(), task.repo(), task.prNumber(), COMMAND);
      // Resolved once and threaded downstream: the batches and the line map must agree on which
      // files are in scope. This command posts committable suggestions, so a file the repository
      // asked the bot to leave alone must reach neither a batch nor the anchor resolver.
      var reviewable =
          respectPerRepoIgnores(task.target(), COMMAND, diffFormatter().reviewableFiles(files));
      if (reviewable.isEmpty()) {
        postComment(auth, task, NO_FILES);
        return;
      }

      var planned = planOrReportFailure(auth, task, pr, reviewable);
      if (planned == null) {
        return;
      }
      if (planned.plan().batches().isEmpty()) {
        Log.debugf(
            "No file of %s/%s #%d fit an /add-docs batch",
            task.owner(), task.repo(), task.prNumber());
        postComment(auth, task, NOT_COVERED + disclosure(planned.plan()));
        return;
      }

      var generated = generateEachBatch(planned);
      if (generated == null) {
        postComment(auth, task, GENERATION_FAILED);
        return;
      }
      var outcome = postSuggestions(auth, task, pr.head().sha(), reviewable, generated.docs());
      postComment(auth, task, summaryMessage(generated, outcome, planned.plan()));
      // The doc counts ride along with the posted counts on purpose: a run that posts nothing is
      // otherwise indistinguishable in the log from one where the model returned nothing, from one
      // whose entries were all incomplete, and from one whose docs would not anchor — which is
      // exactly the question asked of every "/add-docs found nothing" report.
      Log.infof(
          "/add-docs posted %d suggestion(s) and %d note(s) from %d batch(es) on %s/%s #%d"
              + " (model returned %d usable doc(s) and %d incomplete entry/entries)",
          outcome.suggestions(),
          outcome.notes(),
          planned.plan().batches().size(),
          task.owner(),
          task.repo(),
          task.prNumber(),
          generated.docs().size(),
          generated.unusable());
    } catch (RuntimeException e) {
      Log.warnf(
          e, "Failed to handle /add-docs on %s/%s #%d", task.owner(), task.repo(), task.prNumber());
    }
  }

  /** One run's prompt inputs, its resolved project stack, and the batch plan built from them. */
  private record PlannedRun(Inputs inputs, String stack, DiffBudgetPlanner.BudgetPlan plan) {}

  /**
   * Resolves the per-call context and plans the token-budgeted batches, posting the failure notice
   * and returning {@code null} when planning throws — so the caller can bail without a nested try.
   * Planning stays inside this handler on purpose: a planner failure must still surface {@code
   * GENERATION_FAILED} to the user rather than be swallowed by {@link #handle}'s outer catch with
   * only a log line.
   */
  private PlannedRun planOrReportFailure(
      String auth,
      DocTask task,
      GitHubPullRequestClient.PullRequestDetails pr,
      List<GitHubPullRequestClient.FileDiff> reviewable) {
    try {
      String stack =
          SoftLoaders.projectStack(
              projectStackResolver,
              task.owner(),
              task.repo(),
              task.defaultBranch(),
              task.installationId(),
              COMMAND);
      // The whole-PR render is deliberately absent: nothing is sent to a model from it, and this
      // command establishes "is there anything to work from" from the reviewable file list above.
      var inputs =
          new Inputs(
              "",
              orEmpty(pr.title()),
              orEmpty(pr.body()),
              buildInstructionsSection(task),
              pr.head().sha(),
              reviewable);
      // The project stack rides on every batch call, so it has to be part of the overhead the
      // planner subtracts before sizing batches — see the six-argument planBatches.
      var plan =
          planBatches(
              reviewable,
              inputs,
              DocGeneratorPrompts.systemPrompt(),
              DocGeneratorPrompts.userPrompt(),
              PromptTemplateEscaper.escape(stack),
              REDUCE_CALLS);
      return new PlannedRun(inputs, stack, plan);
    } catch (RuntimeException e) {
      Log.warnf(
          e, "Doc generation failed for %s/%s #%d", task.owner(), task.repo(), task.prNumber());
      postComment(auth, task, GENERATION_FAILED);
      return null;
    }
  }

  /**
   * The docs merged across every batch, how many batch calls failed outright, and how many entries
   * the model returned that carried too little to post. The last count is what lets an empty run
   * distinguish "nothing needed documenting" from "the reply was unusable" — both reach the summary
   * with an empty {@code docs} list, and only one of them is a statement about the code.
   */
  private record Generated(
      List<DocGenerationResponse.DocSuggestion> docs, int failedBatches, int unusable) {}

  /**
   * The map step plus the local union reduce: one call per batch, docs merged in batch order. A
   * batch whose call fails or whose response will not parse is skipped rather than failing the run
   * — the other batches still cover most of the PR — and the count is disclosed. Returns {@code
   * null} only when every batch failed, which is the case that warrants the failure notice.
   */
  private Generated generateEachBatch(PlannedRun planned) {
    var merged = new ArrayList<DocGenerationResponse.DocSuggestion>();
    var seen = new HashSet<String>();
    int failed = 0;
    int unusable = 0;
    for (var batch : planned.plan().batches()) {
      var response = generateOne(planned, batch);
      if (response == null) {
        failed++;
        continue;
      }
      for (var doc : response.docs()) {
        // Batches hold disjoint files, but a model can still quote a file it saw named in another
        // batch's context — two comments on one declaration line would be noise on the PR. The
        // postability check gates the dedupe key: file is nullable, so keying on doc.file().strip()
        // first would throw on a malformed entry (losing every valid entry in the same reply), and
        // a non-postable entry consuming file:line would both block a later batch's valid
        // suggestion for that declaration and leave a malformed reply reported as COULD_NOT_PLACE.
        if (!doc.isPostable()) {
          unusable++;
          continue;
        }
        if (seen.add(doc.file().strip() + ":" + doc.line())) {
          merged.add(doc);
        }
      }
    }
    if (failed == planned.plan().batches().size()) {
      Log.debugf("Every /add-docs batch failed — reporting the failure");
      return null;
    }
    return new Generated(List.copyOf(merged), failed, unusable);
  }

  /** One batch's assistant call and parse, or {@code null} when either step fails. */
  private DocGenerationResponse generateOne(PlannedRun planned, DiffBudgetPlanner.DiffBatch batch) {
    String raw =
        callAssistant(
            COMMAND,
            () ->
                docGenerator.generate(
                    PromptTemplateEscaper.fence(batch.text()),
                    PromptTemplateEscaper.escape(
                        PromptSections.prContext(
                            planned.inputs().title(), planned.inputs().body())),
                    PromptTemplateEscaper.escape(planned.stack()),
                    planned.inputs().instructions()));
    if (raw == null) {
      return null;
    }
    try {
      return parser.parse(raw);
    } catch (RuntimeException e) {
      Log.warnf(e, "Could not parse an /add-docs batch response — skipping that batch");
      return null;
    }
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  /** How many /add-docs comments landed, split into committable suggestions and plain notes. */
  record DocPostOutcome(int suggestions, int notes, int skippedByCap) {}

  private enum DocPostResult {
    SUGGESTION,
    NOTE,
    SKIPPED
  }

  /**
   * Posts each postable suggestion that anchors cleanly to the diff; returns the per-kind counts.
   *
   * <p>The line map is built from the run's whole effective file list — every batch's files
   * together, never one batch's — so a doc produced by any batch anchors to its correct absolute
   * line. It is the same list the batches were planned over, so a file the repository asked the bot
   * to ignore cannot be anchored onto either, even if the model names one.
   */
  DocPostOutcome postSuggestions(
      String auth,
      DocTask task,
      String commitSha,
      List<GitHubPullRequestClient.FileDiff> reviewable,
      List<DocGenerationResponse.DocSuggestion> docs) {
    var lineResolver = new DiffLineResolver(diffFormatter().patchesByReviewableFiles(reviewable));
    int cap = config().review().maxReviewComments();
    int suggestions = 0;
    int notes = 0;
    int skippedByCap = 0;
    for (int i = 0; i < docs.size(); i++) {
      if (suggestions + notes >= cap) {
        skippedByCap =
            (int) docs.subList(i, docs.size()).stream().filter(d -> d.isPostable()).count();
        Log.debugf(
            "/add-docs reached the %d-comment cap on %s/%s #%d — %d further doc(s) not posted",
            cap, task.owner(), task.repo(), task.prNumber(), skippedByCap);
        break;
      }
      var doc = docs.get(i);
      if (doc.isPostable()) {
        switch (postDoc(auth, task, commitSha, doc, lineResolver)) {
          case SUGGESTION -> suggestions++;
          case NOTE -> notes++;
          case SKIPPED -> {
            // Not posted — nothing to count.
          }
        }
      }
    }
    return new DocPostOutcome(suggestions, notes, skippedByCap);
  }

  private DocPostResult postDoc(
      String auth,
      DocTask task,
      String commitSha,
      DocGenerationResponse.DocSuggestion doc,
      DiffLineResolver lineResolver) {
    boolean multiLine = doc.suggestionOld().strip().contains("\n");
    var resolved = lineResolver.resolveRightSideLine(doc.file(), doc.line());
    // A single-line docstring must anchor at doc.line() exactly — a snapped-to neighbour would
    // rewrite the wrong line on commit. A multi-line declaration is anchored by its verbatim
    // range below, so it only needs the file to be in the diff.
    if (resolved.isEmpty() || (!multiLine && resolved.getAsInt() != doc.line())) {
      Log.debugf(
          "Skipping /add-docs suggestion for %s:%d — declaration line is not in the diff",
          doc.file(), doc.line());
      return DocPostResult.SKIPPED;
    }
    if (!preservesExistingCode(doc)) {
      Log.debugf(
          "Skipping /add-docs suggestion for %s:%d — replacement would not keep the existing line",
          doc.file(), doc.line());
      return DocPostResult.SKIPPED;
    }
    // A multi-line suggestion must overwrite the whole declaration span, not just doc.line() —
    // otherwise the commit replaces only the first line and corrupts the file.
    var range =
        multiLine
            ? lineResolver.resolveSuggestionRange(doc.file(), doc.suggestionOld())
            : Optional.<DiffLineResolver.LineRange>empty();
    if (multiLine && range.isEmpty()) {
      boolean posted =
          postInline(
              auth,
              task,
              commitSha,
              doc.file(),
              resolved.getAsInt(),
              null,
              suggestionFormatter.formatDocNote(doc.symbol(), doc.suggestionNew()));
      return posted ? DocPostResult.NOTE : DocPostResult.SKIPPED;
    }
    Integer startLine = range.map(DiffLineResolver.LineRange::startLine).orElse(null);
    int endLine = range.map(DiffLineResolver.LineRange::endLine).orElse(doc.line());
    boolean posted =
        postInline(
            auth,
            task,
            commitSha,
            doc.file(),
            endLine,
            startLine,
            suggestionFormatter.formatDocComment(
                doc.symbol(), doc.suggestionOld(), doc.suggestionNew()));
    return posted ? DocPostResult.SUGGESTION : DocPostResult.SKIPPED;
  }

  /**
   * Posts one inline /add-docs comment, anchored at {@code line} (and {@code startLine}..{@code
   * line} when a multi-line range was resolved). Returns {@code false} if GitHub rejects it.
   */
  private boolean postInline(
      String auth,
      DocTask task,
      String commitSha,
      String file,
      int line,
      Integer startLine,
      String body) {
    String startSide = startLine != null ? "RIGHT" : null;
    try {
      reviewClient.createPullRequestComment(
          auth,
          ACCEPT,
          task.owner(),
          task.repo(),
          task.prNumber(),
          new GitHubReviewClient.CreatePullRequestCommentRequest(
              commitSha, body, file, line, "RIGHT", startLine, startSide));
      return true;
    } catch (RuntimeException e) {
      Log.debugf(e, "GitHub rejected /add-docs comment for %s:%d", file, line);
      return false;
    }
  }

  /**
   * Guards against a suggestion that documents a symbol but drops its declaration: the replacement
   * ({@code suggestion_new}) must still contain the original declaration line ({@code
   * suggestion_old}, which {@link DocGenerationResponse.DocSuggestion#isPostable()} guarantees is
   * present), so committing it only inserts documentation. Mirrors the trust the review path places
   * in the model's verbatim quote, paired here with the exact-line anchoring done by the caller.
   */
  private static boolean preservesExistingCode(DocGenerationResponse.DocSuggestion doc) {
    return doc.suggestionNew().contains(doc.suggestionOld().strip());
  }

  /**
   * The summary comment for a completed {@code /add-docs} run, with the batch-failure note and the
   * partial-coverage disclosure appended when the token budget left whole files uncovered — so docs
   * derived from part of the change set are never presented as if they covered the whole PR (reuses
   * the review path's wording).
   */
  private String summaryMessage(
      Generated generated, DocPostOutcome outcome, DiffBudgetPlanner.BudgetPlan plan) {
    return baseSummaryMessage(generated, outcome)
        + batchFailureNote(
            generated.failedBatches(), COMMAND, "the symbols in them are not documented here.")
        + disclosure(plan);
  }

  private String baseSummaryMessage(Generated generated, DocPostOutcome outcome) {
    var docs = generated.docs();
    int suggestions = outcome.suggestions();
    int notes = outcome.notes();
    String capSuffix =
        outcome.skippedByCap() > 0
            ? " "
                + outcome.skippedByCap()
                + " more changed symbol(s) were not documented because the per-run comment cap was"
                + " reached — re-run `/add-docs` after addressing these."
            : "";
    if (suggestions > 0 && notes > 0) {
      return "📝 ThrillhouseBot added **"
          + suggestions
          + "** committable documentation suggestion(s) and drafted **"
          + notes
          + "** more it couldn't post as committable suggestions (declarations that don't map"
          + " cleanly onto the diff — each note has the docs to add manually). Review each one and"
          + " commit the suggestions you want to keep."
          + capSuffix;
    }
    if (suggestions > 0) {
      return "📝 ThrillhouseBot added **"
          + suggestions
          + "** documentation suggestion(s) for changed symbols. "
          + "Review each one and commit the suggestions you want to keep."
          + capSuffix;
    }
    if (notes > 0) {
      return "📝 ThrillhouseBot drafted documentation for **"
          + notes
          + "** symbol(s) it couldn't post as committable suggestions (the declaration doesn't map"
          + " cleanly onto the diff). Each note has the docs to add manually."
          + capSuffix;
    }
    if (outcome.skippedByCap() > 0) {
      return "📝 ThrillhouseBot posted no documentation: the per-run comment cap was reached, so **"
          + outcome.skippedByCap()
          + "** changed symbol(s) were not documented. Raise the comment cap or re-run `/add-docs`.";
    }
    if (!docs.isEmpty()) {
      return COULD_NOT_PLACE;
    }
    // Nothing usable came back. Only a run where the model returned no entries at all can claim
    // there was nothing to document; one whose entries were all incomplete says so instead.
    return generated.unusable() > 0 ? UNUSABLE_SUGGESTIONS : NOTHING_TO_DOCUMENT;
  }

  // Command-specific guidance for the repository-instructions section.
  private static final String INSTRUCTIONS_GUIDANCE =
      "The repository maintainers have provided these guidelines; respect them when writing"
          + " documentation.\n";

  /**
   * Pre-rendered, pre-escaped repository-instructions section, or empty when none is configured.
   */
  private String buildInstructionsSection(DocTask task) {
    var instructions =
        SoftLoaders.instructions(
            instructionsResolver,
            task.owner(),
            task.repo(),
            task.defaultBranch(),
            task.installationId(),
            COMMAND);
    return PromptSections.instructionsSection(instructions, INSTRUCTIONS_GUIDANCE);
  }

  private void postComment(String auth, DocTask task, String body) {
    commentClient.createComment(
        auth,
        ACCEPT,
        task.owner(),
        task.repo(),
        task.prNumber(),
        new GitHubCommentClient.CreateCommentRequest(body));
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
