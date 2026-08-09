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
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.github.InstructionsResolver;
import dev.thiagogonzaga.thrillhousebot.github.ProjectStackResolver;
import dev.thiagogonzaga.thrillhousebot.github.RepoSettingsResolver;
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

  private final ProjectStackResolver projectStackResolver;
  private final UnitTestAssistant testAssistant;
  private final UnitTestGenerationParser parser;
  private final SuggestionFormatter suggestionFormatter;

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
      SuggestionFormatter suggestionFormatter) {
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
    // The project stack rides on every batch call, so it has to be part of the overhead the
    // planner subtracts before sizing batches — see the six-argument planBatches.
    var plan =
        planBatches(
            reviewable,
            inputs,
            UnitTestAssistantPrompts.systemPrompt(),
            UnitTestAssistantPrompts.userPrompt(),
            PromptTemplateEscaper.escape(stack),
            REDUCE_CALLS);
    if (plan.batches().isEmpty()) {
      Log.debugf("No file of %s/%s #%d fit a %s batch", owner, repo, prNumber, COMMAND);
      // A plan that covered nothing but omitted files means the budget is too small to read any
      // of them: say so and name them, because NOTHING_TO_TEST here would be a verdict on code
      // the model never saw. Nothing covered and nothing omitted means no file was in scope.
      return plan.truncated() ? NOT_COVERED + disclosure(plan) : null;
    }
    var generated = generateEachBatch(inputs, stack, plan);
    if (generated == null) {
      return null;
    }
    return render(generated.tests(), generated.notes(), generated.droppedDuplicatePaths())
        + batchFailureNote(
            generated.failedBatches(), COMMAND, "the code in them has no tests proposed here.")
        + disclosure(plan);
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
      Inputs inputs, String stack, DiffBudgetPlanner.BudgetPlan plan) {
    var merged = new ArrayList<UnitTestGenerationResponse.GeneratedTestFile>();
    var seenPaths = new HashSet<String>();
    var notes = new ArrayList<String>();
    int failed = 0;
    int dropped = 0;
    for (var batch : plan.batches()) {
      var response = generateOne(inputs, stack, batch);
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
      Inputs inputs, String stack, DiffBudgetPlanner.DiffBatch batch) {
    String raw =
        callAssistant(
            COMMAND,
            () ->
                testAssistant.generate(
                    PromptTemplateEscaper.fence(batch.text()),
                    PromptTemplateEscaper.escape(
                        PromptSections.prContext(inputs.title(), inputs.body())),
                    PromptTemplateEscaper.escape(stack),
                    PromptTemplateEscaper.escape(inputs.instructions())));
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
