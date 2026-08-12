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
import dev.thiagogonzaga.thrillhousebot.dashboard.ReviewSessionPersistence;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.ProjectStackResolver;
import dev.thiagogonzaga.thrillhousebot.github.RepoSettingsResolver;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import dev.thiagogonzaga.thrillhousebot.review.ai.UnitTestAssistant;
import dev.thiagogonzaga.thrillhousebot.review.ai.UnitTestAssistantPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.UnitTestGenerationParser;
import dev.thiagogonzaga.thrillhousebot.review.ai.UnitTestGenerationResponse;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Builds the {@code /generate-tests} suggestion: unit tests for the code the PR changed, posted as
 * a comment the author may copy in. It never commits or edits a file, so no test file is written on
 * the author's behalf.
 *
 * <p>Loads the PR's diff, current title/body and the repository instructions (via {@link
 * AbstractPrSuggestionGenerator}) plus the project stack, asks the {@link UnitTestAssistant} for
 * test files, and renders each one through {@link SuggestionFormatter}. A proposed test is normally
 * a NEW file with no diff line to anchor a committable {@code suggestion} block to — GitHub only
 * renders those on an inline review comment — so each file is presented as a code block headed by
 * the path it belongs at, ready to copy or paste into a new file. Every step fails soft: a failure
 * simply yields {@code null} (post nothing) rather than a noisy error on the PR.
 *
 * <p>Coverage is token-budgeted rather than line-capped: the change set is planned into batches
 * over the whole reviewable file list, so a PR longer than {@code max-diff-lines} no longer has
 * tests written from its first N lines with the rest of the changed code unseen — which for this
 * command is the worst version of the failure, because "nothing here warrants a test" would then be
 * a verdict on a diff the model never saw.
 *
 * <p>Every batch call also carries the findings the bot's own review already published on the same
 * PR, read back from the persisted prior rounds. They are the one piece of context this command
 * cannot derive from the diff: told about them, it can aim a test at the exact sink the review
 * flagged instead of rediscovering the defect — or pinning it as expected behavior (#571). The
 * section repeats on every call, so it is part of the overhead {@code planBatches} subtracts.
 *
 * <p>The reduce is local, so no call is reserved: per-batch test files are unioned and deduplicated
 * by path. Batches partition the file list, so two batches usually propose disjoint paths; when
 * they do collide, the first proposal wins and the rest are disclosed rather than dropped silently.
 * They cannot be concatenated — each {@code code} is a <em>complete</em> compilable file, package
 * and imports included, so two of them at one path are alternatives rather than additions, and
 * pasting the second over the first would silently discard the first's cases.
 */
@ApplicationScoped
public class UnitTestGenerator extends AbstractPrSuggestionGenerator {

  private static final String COMMAND = "/generate-tests";

  /** Upper bound on test files rendered into one comment, so a huge PR cannot flood the thread. */
  static final int MAX_TEST_FILES = 5;

  static final String HEADER = "## 🤖 ThrillhouseBot — suggested unit tests\n\n";

  static final String FOOTER =
      """


      ---
      *Suggestion only — nothing was committed. Create each file at the path shown (or merge the \
      cases into the existing file), then run them: treat the code as a starting point and adjust \
      imports and fixtures to match your suite. Re-run with `/generate-tests`.*
      """;

  static final String NOTHING_TO_TEST =
      "🧪 ThrillhouseBot found nothing in this PR's changes that warrants a new unit test.";

  /**
   * Posted when the plan covered nothing: every file overflowed even a single-file batch. Saying
   * {@link #NOTHING_TO_TEST} here would be a verdict on code the model never read — the one answer
   * this command must never give wrongly — so it names the budget as the cause instead.
   */
  static final String NOT_COVERED =
      "🤖 ThrillhouseBot could not fit any part of this PR into the per-call input-token budget,"
          + " so it has nothing to propose tests from. Raise the input-token budget and re-run"
          + " `/generate-tests`.";

  /** The union reduce is assembled locally, so the whole allowance goes to batch calls. */
  private static final int REDUCE_CALLS = 0;

  /**
   * Prior findings rendered into the prompt before the rest are rolled up by count. The section
   * rides on <em>every</em> batch call, so an unbounded one would eat the diff budget it shares — a
   * review that raised thirty findings would leave no room for the code they are about, and the
   * command would answer {@link #NOT_COVERED} for a PR it could otherwise have tested.
   */
  static final int MAX_PRIOR_FINDINGS = 10;

  /**
   * No review session is in progress on the suggestion path, so nothing is excluded from the prior
   * rounds. Negative rather than 0 because the sequence-generated ids start at 1 and the query
   * excludes by equality — a value no row can hold reads as "exclude nothing" unambiguously.
   */
  private static final long NO_SESSION_IN_PROGRESS = -1L;

  private final ProjectStackResolver projectStackResolver;
  private final UnitTestAssistant testAssistant;
  private final UnitTestGenerationParser parser;
  private final SuggestionFormatter suggestionFormatter;
  private final ReviewSessionPersistence sessionPersistence;
  private final FollowUpAnalyzer followUpAnalyzer;

  @Inject
  public UnitTestGenerator(
      @RestClient GitHubPullRequestClient prClient,
      ReviewDiffFormatter diffFormatter,
      InstructionsResolver instructionsResolver,
      RepoSettingsResolver repoSettingsResolver,
      DiffBudgetPlanner budgetPlanner,
      ActiveModelSettings activeModel,
      ThrillhouseConfig config,
      ProjectStackResolver projectStackResolver,
      UnitTestAssistant testAssistant,
      UnitTestGenerationParser parser,
      SuggestionFormatter suggestionFormatter,
      ReviewSessionPersistence sessionPersistence,
      FollowUpAnalyzer followUpAnalyzer) {
    super(
        prClient,
        diffFormatter,
        instructionsResolver,
        repoSettingsResolver,
        budgetPlanner,
        activeModel,
        config);
    this.projectStackResolver = projectStackResolver;
    this.testAssistant = testAssistant;
    this.parser = parser;
    this.suggestionFormatter = suggestionFormatter;
    this.sessionPersistence = sessionPersistence;
    this.followUpAnalyzer = followUpAnalyzer;
  }

  /**
   * Generates the suggested-tests comment for a PR, or {@code null} when there is nothing to work
   * from (no diff) or the model produced no usable answer. When the model judged nothing testable,
   * the comment says so rather than staying silent — the maintainer asked for tests explicitly. The
   * caller is responsible for posting it.
   *
   * @param auth the {@code Authorization} header for the installation (already minted by the
   *     caller)
   */
  @ActivateRequestContext
  public String generate(
      String owner,
      String repo,
      int prNumber,
      String defaultBranch,
      long installationId,
      String auth) {
    var inputs = loadInputs(owner, repo, prNumber, defaultBranch, installationId, auth, COMMAND);
    if (inputs == null) {
      return null;
    }
    String stack =
        SoftLoaders.projectStack(
            projectStackResolver, owner, repo, defaultBranch, installationId, COMMAND);
    // Resolved once and threaded downstream, so nothing later re-derives the in-scope file list.
    // This command writes code from what it reads, so a file the repository asked the bot to leave
    // alone must not reach a batch at all.
    var reviewable =
        respectPerRepoIgnores(
            new CommandTarget(owner, repo, defaultBranch, installationId),
            COMMAND,
            inputs.reviewableFiles());
    var extras =
        new ExtraSections(
            PromptTemplateEscaper.escape(stack),
            PromptTemplateEscaper.escape(priorFindings(owner, repo, prNumber)));
    // The project stack and the prior findings ride on every batch call, so both have to be part
    // of the overhead the planner subtracts before sizing batches — see the six-argument
    // planBatches.
    var plan =
        planBatches(
            reviewable,
            inputs,
            UnitTestAssistantPrompts.systemPrompt(),
            UnitTestAssistantPrompts.userPrompt(),
            extras.all(),
            REDUCE_CALLS);
    if (plan.batches().isEmpty()) {
      Log.debugf("No file of %s/%s #%d fit a %s batch", owner, repo, prNumber, COMMAND);
      // A plan that covered nothing but omitted files means the budget is too small to read any
      // of them: say so and name them, because NOTHING_TO_TEST here would be a verdict on code
      // the model never saw. Nothing covered and nothing omitted means no file was in scope.
      return plan.truncated() ? NOT_COVERED + disclosure(plan) : null;
    }
    var generated = generateEachBatch(inputs, extras, plan);
    if (generated == null) {
      return null;
    }
    return render(generated.tests(), generated.notes(), generated.droppedDuplicatePaths())
        + batchFailureNote(
            generated.failedBatches(), COMMAND, "the code in them has no tests proposed here.")
        + disclosure(plan);
  }

  /**
   * The two sections this command adds on top of {@link #sharedPromptOverhead}, each escaped
   * exactly as it is sent. Both ride on every batch call, so they are built once and handed to the
   * planner and to the calls: what the planner subtracts as overhead cannot then drift from what a
   * call actually carries, which is the drift that lets an "in-budget" batch overshoot the model's
   * real input limit.
   */
  private record ExtraSections(String projectStack, String priorFindings) {

    /** Both sections as the planner sizes them — concatenated exactly as the call sends them. */
    String all() {
      return projectStack + priorFindings;
    }
  }

  /**
   * The findings the bot's own review already reported on this PR, rendered for the prompt, or
   * empty when there is no prior round (or it raised nothing). Without this the command runs blind
   * to a defect the bot found, described and published on the very PR it is writing tests for, and
   * can only rediscover it — or, worse, pin it as the expected behavior (#571).
   *
   * <p>Takes the newest prior round that actually raised findings, exactly as the review path does
   * ({@link FollowUpAnalyzer#effectivePreviousFindings}), so a later round that legitimately found
   * nothing does not read as "the review found nothing". Fails soft like every other context load
   * here, and over the <em>whole</em> load: this is enrichment, so nothing in fetching or
   * deserializing prior rounds may cost a command that worked without them before.
   */
  private String priorFindings(String owner, String repo, int prNumber) {
    try {
      var priorJsons =
          sessionPersistence.findAllPriorAiResponseJsons(
              owner + "/" + repo, prNumber, NO_SESSION_IN_PROGRESS);
      var priorResponses = followUpAnalyzer.parsePreviousResponses(priorJsons);
      return renderFindings(
          FollowUpAnalyzer.effectivePreviousFindings(priorResponses),
          FollowUpAnalyzer.settledPreviousIds(priorResponses));
    } catch (RuntimeException e) {
      Log.warnf(
          e,
          "Could not load prior review findings for %s on %s/%s #%d, continuing without them",
          COMMAND,
          owner,
          repo,
          prNumber);
      return "";
    }
  }

  /**
   * One line per still-open finding — risk, location, title — with its description underneath,
   * numbered by the 1-based position the review published as the finding's id so a maintainer
   * reading both can line them up. Capped at {@link #MAX_PRIOR_FINDINGS}, with the remainder
   * counted rather than dropped silently. Empty when nothing is left to show.
   *
   * <p>A finding a later round already closed is skipped: the prompt presents this list as behavior
   * that is wrong <em>today</em>, and a resolved one is not. Its id slot is skipped rather than
   * renumbered, so the ids still match the ones the review posted.
   */
  private static String renderFindings(
      List<ReviewResponse.Finding> findings, Set<Integer> settledIds) {
    var sb = new StringBuilder();
    int shown = 0;
    int overCap = 0;
    for (var i = 0; i < findings.size(); i++) {
      if (settledIds.contains(i + 1)) {
        continue;
      }
      if (shown == MAX_PRIOR_FINDINGS) {
        overCap++;
      } else {
        appendFinding(sb, i + 1, findings.get(i));
        shown++;
      }
    }
    if (overCap > 0) {
      sb.append("(")
          .append(overCap)
          .append(" further finding(s) were reported but are not listed here.)\n");
    }
    return sb.toString();
  }

  /**
   * One rendered finding: {@code id. [RISK] file:line — title}, with its description underneath.
   */
  private static void appendFinding(StringBuilder sb, int id, ReviewResponse.Finding finding) {
    sb.append(id).append(". [").append(text(finding.risk()).toUpperCase(Locale.ROOT)).append("] ");
    // The location is written only when there is one. line is a primitive, so an absent line
    // arrives as 0 — appending it would hand the model a file:0 that points nowhere and costs
    // tokens on every finding that lacks one, which is the same noise a literal "null" would be.
    var file = text(finding.file());
    if (!file.isEmpty()) {
      sb.append(file);
      if (finding.line() > 0) {
        sb.append(':').append(finding.line());
      }
      sb.append(" — ");
    }
    sb.append(text(finding.title())).append('\n');
    var description = text(finding.description());
    if (!description.isEmpty()) {
      sb.append("   ").append(description).append('\n');
    }
  }

  /** A finding field as prompt text: absent fields are blanks, not the literal {@code null}. */
  private static String text(String value) {
    return value == null ? "" : value.strip();
  }

  /**
   * The per-batch proposals merged into one run's worth, plus how many batch calls failed and how
   * many same-path proposals a later batch lost to an earlier one.
   */
  private record Generated(
      List<UnitTestGenerationResponse.GeneratedTestFile> tests,
      String notes,
      int failedBatches,
      int droppedDuplicatePaths) {}

  /**
   * The map step plus the local union reduce: one call per batch, proposals deduplicated by path,
   * notes joined. A batch whose call fails or whose response will not parse is skipped rather than
   * failing the run — the other batches still cover most of the PR — and the count is disclosed.
   * Returns {@code null} only when every batch failed, which is a failed run rather than a verdict.
   */
  private Generated generateEachBatch(
      Inputs inputs, ExtraSections extras, DiffBudgetPlanner.BudgetPlan plan) {
    var merged = new ArrayList<UnitTestGenerationResponse.GeneratedTestFile>();
    var seenPaths = new HashSet<String>();
    var notes = new ArrayList<String>();
    int failed = 0;
    int dropped = 0;
    for (var batch : plan.batches()) {
      var response = generateOne(inputs, extras, batch);
      if (response == null) {
        failed++;
        continue;
      }
      for (var test : response.postableTests()) {
        if (seenPaths.add(test.path().strip())) {
          merged.add(test);
        } else {
          dropped++;
        }
      }
      if (!response.notes().isBlank()) {
        notes.add(response.notes().strip());
      }
    }
    if (failed == plan.batches().size()) {
      Log.debugf("Every %s batch failed — posting nothing", COMMAND);
      return null;
    }
    return new Generated(List.copyOf(merged), String.join(" ", notes), failed, dropped);
  }

  /**
   * One batch's proposals, or {@code null} when the call failed or the response would not parse.
   */
  private UnitTestGenerationResponse generateOne(
      Inputs inputs, ExtraSections extras, DiffBudgetPlanner.DiffBatch batch) {
    String raw =
        callAssistant(
            COMMAND,
            () ->
                testAssistant.generate(
                    PromptTemplateEscaper.fence(batch.text()),
                    PromptTemplateEscaper.escape(
                        PromptSections.prContext(inputs.title(), inputs.body())),
                    extras.projectStack(),
                    PromptTemplateEscaper.escape(inputs.instructions()),
                    extras.priorFindings()));
    if (raw == null) {
      return null;
    }
    try {
      return parser.parse(raw);
    } catch (RuntimeException e) {
      Log.warnf(e, "A %s batch response could not be parsed — skipping that batch", COMMAND);
      return null;
    }
  }

  /** The comment body for the run's merged proposals, without the partial-coverage disclosure. */
  private String render(
      List<UnitTestGenerationResponse.GeneratedTestFile> tests,
      String notes,
      int droppedDuplicatePaths) {
    if (tests.isEmpty()) {
      return NOTHING_TO_TEST + notesLine(notes);
    }
    var sb = new StringBuilder(HEADER);
    int rendered = Math.min(tests.size(), MAX_TEST_FILES);
    for (var test : tests.subList(0, rendered)) {
      sb.append(
              suggestionFormatter.formatGeneratedTestFile(
                  test.path(), test.language(), test.covers(), test.code()))
          .append('\n');
    }
    if (tests.size() > rendered) {
      sb.append("_")
          .append(tests.size() - rendered)
          .append(" further proposed test file(s) were left out to keep this comment readable —")
          .append(" re-run `/generate-tests` once these are in._\n");
    }
    if (droppedDuplicatePaths > 0) {
      sb.append("_")
          .append(droppedDuplicatePaths)
          .append(" further proposal(s) targeted a path already shown above and were left out —")
          .append(" each proposed file is complete, so they are alternatives rather than extra")
          .append(" cases. Re-run `/generate-tests` once these are in._\n");
    }
    return sb.append(notesLine(notes)).append(FOOTER).toString();
  }

  /**
   * The model's coverage caveats as a trailing line, or empty when it flagged none. Like the path
   * and the "covers" note, this is model output spliced into the comment body, so it is flattened
   * to a single line — left multi-line it could open a fence or a heading of its own and
   * restructure everything below it.
   */
  private static String notesLine(String notes) {
    return notes.isBlank() ? "" : "\n**Not covered:** " + SuggestionFormatter.oneLine(notes) + "\n";
  }
}
