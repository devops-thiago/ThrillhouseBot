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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.ProjectStackResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.DocGenerationParser;
import dev.thiagogonzaga.thrillhousebot.review.ai.DocGenerationResponse;
import dev.thiagogonzaga.thrillhousebot.review.ai.DocGenerator;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
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
 */
@ApplicationScoped
public class DocGenerationService {

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
  static final String COULD_NOT_PLACE =
      "📝 ThrillhouseBot generated documentation but could not anchor it to the current diff. "
          + "This usually happens after a force-push — try `/add-docs` again.";

  private final GitHubAuthClient authClient;
  private final GitHubPullRequestClient prClient;
  private final GitHubReviewClient reviewClient;
  private final GitHubCommentClient commentClient;
  private final ReviewDiffFormatter diffFormatter;
  private final SuggestionFormatter suggestionFormatter;
  private final InstructionsResolver instructionsResolver;
  private final ProjectStackResolver projectStackResolver;
  private final DocGenerator docGenerator;
  private final DocGenerationParser parser;
  private final ThrillhouseConfig config;

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
      ThrillhouseConfig config) {
    this.authClient = authClient;
    this.prClient = prClient;
    this.reviewClient = reviewClient;
    this.commentClient = commentClient;
    this.diffFormatter = diffFormatter;
    this.suggestionFormatter = suggestionFormatter;
    this.instructionsResolver = instructionsResolver;
    this.projectStackResolver = projectStackResolver;
    this.docGenerator = docGenerator;
    this.parser = parser;
    this.config = config;
  }

  /**
   * Coordinates of the PR to document, captured from the comment command so the heavy work can run
   * asynchronously.
   */
  public record DocTask(
      String owner, String repo, int prNumber, String defaultBranch, long installationId) {}

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
      var reviewable = diffFormatter.reviewableFiles(files);
      if (reviewable.isEmpty()) {
        postComment(auth, task, NO_FILES);
        return;
      }

      var generated = generateOrReportFailure(auth, task, files, reviewable, pr);
      if (generated == null) {
        return;
      }

      var outcome = postSuggestions(auth, task, pr.head().sha(), reviewable, generated.response());
      postComment(
          auth, task, summaryMessage(generated.response(), outcome, generated.omittedFiles()));
      Log.infof(
          "/add-docs posted %d suggestion(s) and %d note(s) on %s/%s #%d",
          outcome.suggestions(), outcome.notes(), task.owner(), task.repo(), task.prNumber());
    } catch (RuntimeException e) {
      Log.warnf(
          e, "Failed to handle /add-docs on %s/%s #%d", task.owner(), task.repo(), task.prNumber());
    }
  }

  /** Parsed documentation plus how many files the diff line budget omitted. */
  private record GeneratedDocs(DocGenerationResponse response, int omittedFiles) {}

  /**
   * Builds the diff, runs generation, and returns the parsed docs plus the omitted-file count —
   * posting the failure notice and returning {@code null} when the diff build, model call, or parse
   * throws, so the caller can bail without a nested try. The diff build stays inside this handler
   * on purpose: a formatter failure must still surface {@code GENERATION_FAILED} to the user rather
   * than be swallowed by {@link #handle}'s outer catch with only a log line.
   */
  private GeneratedDocs generateOrReportFailure(
      String auth,
      DocTask task,
      List<GitHubPullRequestClient.FileDiff> files,
      List<GitHubPullRequestClient.FileDiff> reviewable,
      GitHubPullRequestClient.PullRequestDetails pr) {
    try {
      var formatted = diffFormatter.buildDiffStringWithStats(files, reviewable);
      var response = generate(task, formatted.text(), pr);
      return new GeneratedDocs(response, formatted.omittedFiles());
    } catch (RuntimeException e) {
      Log.warnf(
          e, "Doc generation failed for %s/%s #%d", task.owner(), task.repo(), task.prNumber());
      postComment(auth, task, GENERATION_FAILED);
      return null;
    }
  }

  private DocGenerationResponse generate(
      DocTask task, String diff, GitHubPullRequestClient.PullRequestDetails pr) {
    String prContext = PromptSections.prContext(pr.title(), pr.body());
    String stack =
        SoftLoaders.projectStack(
            projectStackResolver,
            task.owner(),
            task.repo(),
            task.defaultBranch(),
            task.installationId(),
            COMMAND);
    String instructions = buildInstructionsSection(task);

    String raw =
        docGenerator.generate(
            PromptTemplateEscaper.fence(diff),
            PromptTemplateEscaper.escape(prContext),
            PromptTemplateEscaper.escape(stack),
            instructions);
    return parser.parse(raw);
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
   */
  DocPostOutcome postSuggestions(
      String auth,
      DocTask task,
      String commitSha,
      List<GitHubPullRequestClient.FileDiff> reviewable,
      DocGenerationResponse response) {
    // `reviewable` is the already ignore-glob-filtered list the caller computed, so use the
    // overload
    // that trusts that filter rather than re-walking the glob here.
    var lineResolver = new DiffLineResolver(diffFormatter.patchesByReviewableFiles(reviewable));
    int cap = config.review().maxReviewComments();
    int suggestions = 0;
    int notes = 0;
    int skippedByCap = 0;
    var docs = response.docs();
    for (int i = 0; i < docs.size(); i++) {
      if (suggestions + notes >= cap) {
        // Count the postable docs we're dropping because the cap is reached, so the summary can
        // disclose them rather than silently under-documenting the PR.
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
    // A single-line docstring is anchored at doc.line() itself, so a snapped-to neighbour would
    // rewrite the wrong line on commit — drop a near miss. A multi-line declaration is anchored by
    // its verbatim range (below), so the exact line isn't required; only that the file is in the
    // diff, which also gives the note fallback a valid line to attach to.
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
    // A wrapped declaration spans several lines, so a committable suggestion must overwrite the
    // whole span, not just doc.line() — otherwise the commit replaces only the first line and
    // leaves
    // the rest in place, corrupting the file. Resolve the range from the verbatim old code's
    // position in the diff, which also anchors it independently of doc.line().
    var range =
        multiLine
            ? lineResolver.resolveSuggestionRange(doc.file(), doc.suggestionOld())
            : Optional.<DiffLineResolver.LineRange>empty();
    if (multiLine && range.isEmpty()) {
      // The multi-line declaration can't be anchored to a single hunk, so a committable suggestion
      // would mis-apply. Still surface the missing-docs problem: post a note (anchored at the
      // nearest in-diff line) with the drafted docs to add manually, rather than dropping it.
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
   * The summary comment for a completed {@code /add-docs} run, with a partial-coverage disclosure
   * appended when the diff line budget dropped whole files — so docs derived from a truncated diff
   * are never presented as if they covered the whole PR (reuses the review path's wording).
   */
  private String summaryMessage(
      DocGenerationResponse response, DocPostOutcome outcome, int omittedFiles) {
    return baseSummaryMessage(response, outcome) + ReviewResult.truncationDisclosure(omittedFiles);
  }

  private String baseSummaryMessage(DocGenerationResponse response, DocPostOutcome outcome) {
    int suggestions = outcome.suggestions();
    int notes = outcome.notes();
    // When the per-run comment cap stopped further docs, disclose it rather than silently
    // under-documenting the PR — the maintainer needs to know to re-run after addressing these.
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
      // Notes have no committable ```suggestion block, so don't tell the maintainer to "commit"
      // them.
      return "📝 ThrillhouseBot drafted documentation for **"
          + notes
          + "** symbol(s) it couldn't post as committable suggestions (the declaration doesn't map"
          + " cleanly onto the diff). Each note has the docs to add manually."
          + capSuffix;
    }
    if (outcome.skippedByCap() > 0) {
      // Nothing posted, but the cap (e.g. maxReviewComments=0) stopped postable docs. Disclose the
      // cap rather than falling through to COULD_NOT_PLACE, which would wrongly blame anchoring.
      return "📝 ThrillhouseBot posted no documentation: the per-run comment cap was reached, so **"
          + outcome.skippedByCap()
          + "** changed symbol(s) were not documented. Raise the comment cap or re-run `/add-docs`.";
    }
    return response.docs().isEmpty() ? NOTHING_TO_DOCUMENT : COULD_NOT_PLACE;
  }

  // The command-specific guidance line for the repository-instructions section
  // (PromptSections.instructionsSection renders the shared header, source attribution, and escape).
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
