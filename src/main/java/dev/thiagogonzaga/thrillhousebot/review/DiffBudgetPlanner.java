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
import dev.thiagogonzaga.thrillhousebot.review.ai.AiReviewService;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrReviewPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.TokenCounter;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Splits a PR's reviewable files into token-budgeted batches so every file is covered by some model
 * call, replacing the old silent line cap. Files are ordered highest-impact-first and packed
 * First-Fit-Decreasing into at most {@code maxBatches} bins, each within the per-call token budget;
 * a single file larger than one budget is hunk-clipped (via {@link ReviewDiffFormatter}) to fit,
 * and anything that still does not fit — once every bin is full — is reported {@link
 * BudgetPlan#omittedFiles() by name} rather than dropped silently.
 *
 * <p>The budget passed here is the share left for diff text after the caller subtracts the fixed
 * prompt overhead (system prompt, project stack, previous findings, instructions). Token estimates
 * come from {@link TokenCounter}; the caller applies its safety margin to the budget.
 */
@ApplicationScoped
public class DiffBudgetPlanner {

  /**
   * Share of the per-call input budget the previous-findings block may occupy before {@link
   * #boundPreviousFindings} condenses it. It is shared overhead — repeated in full by every batch
   * call — so it is charged against the whole per-call budget, not against one batch's diff. A
   * quarter is deliberately generous: condensation costs the model the prose it uses to judge a
   * finding resolved, so the bound should bite on the accumulation this exists to stop, not on an
   * ordinary follow-up round.
   */
  private static final double PREVIOUS_FINDINGS_BUDGET_SHARE = 0.25;

  /**
   * Opening shape of a numbered previous finding: {@code "12. [HIGH] path/File.java:7 — Title"}.
   */
  private static final Pattern NUMBERED_ENTRY = Pattern.compile("^\\d+\\. \\[");

  /**
   * Stand-in for a coverage gap on a file GitHub gave no name. It keeps the gap counted — the
   * omitted/clipped list sizes are what hold APPROVE — while naming it as honestly as the input
   * allows. See {@link #recordName}.
   */
  static final String UNNAMED_FILE = "(unnamed file)";

  private final ReviewDiffFormatter formatter;
  private final TokenCounter tokenCounter;
  private final ThrillhouseConfig config;
  private final ActiveModelSettings activeModel;

  @Inject
  public DiffBudgetPlanner(
      ReviewDiffFormatter formatter,
      TokenCounter tokenCounter,
      ThrillhouseConfig config,
      ActiveModelSettings activeModel) {
    this.formatter = formatter;
    this.tokenCounter = tokenCounter;
    this.config = config;
    this.activeModel = activeModel;
  }

  /** One in-budget batch: its rendered diff text, the files it covers, and the token estimate. */
  public record DiffBatch(
      String text, List<GitHubPullRequestClient.FileDiff> files, int estimatedTokens) {
    public DiffBatch {
      files = List.copyOf(files);
    }
  }

  /**
   * The batching plan: ordered in-budget batches, files that did not fit at all (by name), and
   * files that were hunk-clipped to fit their batch — covered, but only partially analyzed, so the
   * summary must not present them as fully reviewed. {@code budgeted} is false only for an explicit
   * {@code max-input-tokens=0} (legacy single uncapped batch) — consumers use it to pick between
   * the plan's omissions and the legacy line-cap count.
   *
   * <p>{@code runtimeUncoveredFiles} is a live, mutable accumulator, distinct from the planned
   * omissions: the plan is built before the review runs, and the same instance flows to both the
   * review pass and the verdict, so a batch that fails all its retries records its files here
   * rather than aborting the whole review — the verdict still holds APPROVE and discloses the gap,
   * but every batch that succeeded keeps its findings. Written only through {@link
   * #recordUncoveredFiles(List)}; its accessor returns a defensive copy so the mutable backing
   * never escapes. The {@code effective*} accessors fold it into the planned omissions.
   */
  public record BudgetPlan(
      List<DiffBatch> batches,
      List<String> omittedFiles,
      List<String> clippedFiles,
      boolean budgeted,
      List<String> runtimeUncoveredFiles,
      List<String> spendCeilingSkippedFiles,
      List<String> responseCutFiles,
      java.util.concurrent.atomic.AtomicReference<SummaryDegradation> summaryDegradationRef,
      java.util.concurrent.atomic.AtomicReference<VerificationCoverage> verificationCoverageRef) {
    public BudgetPlan {
      batches = List.copyOf(batches);
      omittedFiles = List.copyOf(omittedFiles);
      clippedFiles = List.copyOf(clippedFiles);
      // Kept mutable on purpose (not List.copyOf): the review pass records runtime batch failures
      // onto this shared instance after the plan is built.
      runtimeUncoveredFiles =
          runtimeUncoveredFiles == null ? new CopyOnWriteArrayList<>() : runtimeUncoveredFiles;
      spendCeilingSkippedFiles =
          spendCeilingSkippedFiles == null
              ? new CopyOnWriteArrayList<>()
              : spendCeilingSkippedFiles;
      responseCutFiles = responseCutFiles == null ? new CopyOnWriteArrayList<>() : responseCutFiles;
      summaryDegradationRef =
          summaryDegradationRef == null
              ? new java.util.concurrent.atomic.AtomicReference<>(SummaryDegradation.NONE)
              : summaryDegradationRef;
      verificationCoverageRef =
          verificationCoverageRef == null
              ? new java.util.concurrent.atomic.AtomicReference<>(VerificationCoverage.EMPTY)
              : verificationCoverageRef;
    }

    /**
     * Convenience constructor for plans built before the verification-coverage slot existed (and
     * tests): coverage starts at {@link VerificationCoverage#EMPTY} and accumulates through {@link
     * #recordVerificationCoverage} exactly as with the canonical constructor's {@code null}.
     */
    public BudgetPlan(
        List<DiffBatch> batches,
        List<String> omittedFiles,
        List<String> clippedFiles,
        boolean budgeted,
        List<String> runtimeUncoveredFiles,
        List<String> spendCeilingSkippedFiles,
        List<String> responseCutFiles,
        java.util.concurrent.atomic.AtomicReference<SummaryDegradation> summaryDegradationRef) {
      this(
          batches,
          omittedFiles,
          clippedFiles,
          budgeted,
          runtimeUncoveredFiles,
          spendCeilingSkippedFiles,
          responseCutFiles,
          summaryDegradationRef,
          null);
    }

    /**
     * Accumulates one verification call's coverage onto the review-wide total, so a multi-batch
     * review — one verifier call per batch — discloses the summed candidate and verified counts
     * (#623). Recorded by the review pass onto this shared instance, exactly like the runtime file
     * classes above; {@code VerificationCoverage.plus} is commutative, so the parallel batch lanes
     * may record in any order.
     */
    void recordVerificationCoverage(VerificationCoverage coverage) {
      if (coverage == null) {
        return;
      }
      verificationCoverageRef.accumulateAndGet(coverage, VerificationCoverage::plus);
    }

    /** The review-wide verification coverage recorded so far — the disclosure's counts. */
    public VerificationCoverage verificationCoverage() {
      return verificationCoverageRef.get();
    }

    /**
     * Defensive snapshot, like {@link #summaryDegradationRef()}: the live slot is only written
     * through {@link #recordVerificationCoverage} and read through {@link #verificationCoverage()}.
     */
    @Override
    public java.util.concurrent.atomic.AtomicReference<VerificationCoverage>
        verificationCoverageRef() {
      return new java.util.concurrent.atomic.AtomicReference<>(verificationCoverageRef.get());
    }

    /**
     * Records how the review's summary prose degraded — response cut at the model's length cap
     * (#500 scope A) or call skipped (or refused mid-call) at the token spend ceiling (#518) — so
     * the posted review discloses the degradation instead of leaving it log-only. The two flavors
     * arise on disjoint control paths, so a single slot suffices and the meaningless both-at-once
     * state stays unrepresentable.
     */
    void recordSummaryDegradation(SummaryDegradation degradation) {
      summaryDegradationRef.set(degradation);
    }

    /** How the summary prose degraded, if at all — the disclosure names the flavor. */
    public SummaryDegradation summaryDegradation() {
      return summaryDegradationRef.get();
    }

    /** Defensive copy: the mutable runtime-gap backing must never escape the plan. */
    @Override
    public List<String> runtimeUncoveredFiles() {
      return List.copyOf(runtimeUncoveredFiles);
    }

    /** Defensive copy, like {@link #runtimeUncoveredFiles()}. */
    @Override
    public List<String> spendCeilingSkippedFiles() {
      return List.copyOf(spendCeilingSkippedFiles);
    }

    /** Defensive copy, like {@link #runtimeUncoveredFiles()}. */
    @Override
    public List<String> responseCutFiles() {
      return List.copyOf(responseCutFiles);
    }

    /**
     * Defensive snapshot, like {@link #runtimeUncoveredFiles()}: the live slot is only written
     * through {@link #recordSummaryDegradation} and read through {@link #summaryDegradation()}.
     */
    @Override
    public java.util.concurrent.atomic.AtomicReference<SummaryDegradation> summaryDegradationRef() {
      return new java.util.concurrent.atomic.AtomicReference<>(summaryDegradationRef.get());
    }

    /** Records files a batch left unreviewed at runtime, ignoring nulls and duplicates. */
    void recordUncoveredFiles(List<String> filenames) {
      recordDistinct(runtimeUncoveredFiles, filenames);
    }

    /**
     * Appends every non-null name of {@code filenames} that {@code into} does not already hold,
     * preserving first-seen order.
     *
     * <p>Both accumulators are {@link CopyOnWriteArrayList copy-on-write} lists, whose {@code
     * contains} <em>and</em> {@code add} are linear, so a scan-then-append-per-name pass was
     * quadratic twice over in the batch's file count — a count nothing bounds, since a
     * token-budgeted batch can hold hundreds of small files, and every failed or refused batch runs
     * another pass. Both halves are removed here: membership is tested against a {@link HashSet}
     * snapshot taken once per call, and the new names are appended in one {@code addAll}, which
     * copies the backing array once rather than once per appended name.
     *
     * <p>Neither change weakens the existing concurrency contract: {@code contains}-then-{@code
     * add} was never atomic either, so two threads recording the same name concurrently could
     * already both append it. The snapshot only widens that same window within a single call, and
     * the batched append narrows it back; the next call re-reads the live list regardless.
     */
    private static void recordDistinct(List<String> into, List<String> filenames) {
      var known = new HashSet<>(into);
      var added = new ArrayList<String>();
      for (var name : filenames) {
        if (name != null && known.add(name)) {
          added.add(name);
        }
      }
      into.addAll(added);
    }

    /**
     * Records files whose review call was skipped because the review's token spend ceiling was
     * reached. They flow through {@link #recordUncoveredFiles} — so the verdict holds and the
     * summary discloses them exactly like any other runtime coverage gap — and are additionally
     * remembered here so the disclosure can name the spend ceiling as the reason, distinct from the
     * token-budget omissions.
     */
    void recordSpendCeilingSkippedFiles(List<String> filenames) {
      recordUncoveredFiles(filenames);
      recordDistinct(spendCeilingSkippedFiles, filenames);
    }

    /**
     * Records files whose batch response the model cut at its length cap but whose complete leading
     * findings were salvaged (#500) — partially reviewed, a state distinct from {@link
     * #recordUncoveredFiles not reviewed}: the salvaged findings are kept and the disclosure says
     * the response was cut, not that the files were never seen. Deliberately <em>not</em> folded
     * into {@code runtimeUncoveredFiles}: these files keep their walkthrough rows and partial
     * findings; only approval is withheld ({@link #truncated()}).
     */
    void recordResponseCutFiles(List<String> filenames) {
      recordDistinct(responseCutFiles, filenames);
    }

    public boolean truncated() {
      // Clipped unseen hunks, files a failed batch never covered, and files whose response was
      // cut mid-body all withhold coverage like omitted files — each holds APPROVE.
      return !omittedFiles.isEmpty()
          || !clippedFiles.isEmpty()
          || !runtimeUncoveredFiles.isEmpty()
          || !responseCutFiles.isEmpty();
    }

    /**
     * Files with no usable coverage: the planned omissions plus any file a failed batch left
     * unreviewed at runtime, each listed once. Consumers gating and disclosing coverage use this,
     * not {@link #omittedFiles()}, so a runtime batch failure is accounted for like an omission.
     */
    public List<String> effectiveOmittedFiles() {
      if (runtimeUncoveredFiles.isEmpty()) {
        return omittedFiles;
      }
      var merged = new LinkedHashSet<>(omittedFiles);
      merged.addAll(runtimeUncoveredFiles);
      return List.copyOf(merged);
    }

    /**
     * Clipped (partially analyzed) files minus any a failed batch left wholly uncovered — those are
     * reported as omitted, not merely clipped, so a runtime failure never has a file counted twice
     * nor its coverage overstated.
     */
    public List<String> effectiveClippedFiles() {
      if (runtimeUncoveredFiles.isEmpty()) {
        return clippedFiles;
      }
      var gapSet = new HashSet<>(runtimeUncoveredFiles);
      return clippedFiles.stream().filter(n -> !gapSet.contains(n)).toList();
    }

    public boolean multiCall() {
      return batches.size() > 1;
    }
  }

  /**
   * Plans batches for a review from its fully assembled prompt inputs. Owns the budget math: the
   * per-call input budget is {@code max-input-tokens * token-safety-margin - output-buffer-tokens},
   * one call of the {@code max-ai-calls} cap is reserved for the final summary, and the shared
   * overhead is sized from the prompt templates plus every non-diff section the batch calls
   * actually repeat — including PR context, related tests, and trailing guidance, whose omission
   * would let "in-budget" batches overshoot the real input limit. The base comparison is counted
   * too: the budgeted single-batch call keeps it (multi-batch calls drop it, so they under-fill
   * slightly, which errs safe). An explicit {@code max-input-tokens <= 0} disables budgeting.
   */
  public BudgetPlan plan(
      List<GitHubPullRequestClient.FileDiff> reviewable, AiReviewService.PromptInputs inputs) {
    var review = config.review();
    if (activeModel.maxInputTokens() <= 0) {
      return plan(reviewable, 0, 1);
    }
    // Size the overhead from the same bounded block the calls actually carry (#583): planning
    // against the raw block and sending a bounded one — or the reverse — would make the estimate
    // a fiction. boundPreviousFindings is idempotent, so the caller applying it first is a no-op
    // here.
    var bounded = boundPreviousFindings(inputs);
    // fence(" ") produces the two real fence lines (fence of empty content is a no-op by design),
    // counting the per-review scaffolding the pipeline wraps each batch in — small, but the safety
    // margin should absorb estimate error, not known constants.
    var sharedOverhead =
        PrReviewPrompts.SYSTEM
            + PrReviewPrompts.USER
            + PromptTemplateEscaper.fence(" ")
            + bounded.prContext()
            + bounded.baseComparison()
            + bounded.projectStack()
            + bounded.relatedTests()
            + bounded.previousFindings()
            + bounded.repoInstructions();
    return plan(
        reviewable, sharedOverhead, perCallInputBudget(), Math.max(1, review.maxAiCalls() - 1));
  }

  /**
   * Bounds the previous-findings block to {@link #PREVIOUS_FINDINGS_BUDGET_SHARE} of the per-call
   * input budget, returning the inputs the review should actually be run with (#583).
   *
   * <p>The block is the one section of the shared overhead that grows monotonically: every round
   * appends the previous round's findings, their prose and their whole comment threads, and nothing
   * ever retires them on a long-lived PR whose head keeps advancing. Because the overhead is
   * <em>shared</em> — repeated verbatim in every batch call — adding batches multiplies it instead
   * of dividing it, so the planner's only other lever (shrinking the diff budget) starves the
   * review of the code it is supposed to read, and past that, the request is rejected outright. On
   * this repository's own release PR the block reached ~437K tokens against 57–74K for a normal
   * review.
   *
   * <p>The bound is deliberately <em>not</em> a blind truncation. What the follow-up pass needs
   * from a previous finding is its identity and location — the id it must report a status for, the
   * file and line it must look at, and the title it matches against — not the prose. So the block
   * degrades by <em>condensation</em>: every entry keeps its own line (id, risk, {@code file:line},
   * title) and loses only its continuation lines (description, quoted code, thread replies). No
   * finding disappears, no id shifts, and the deterministic machinery that decides resolved /
   * unresolved is untouched by this — it runs off the structured previous-findings list, never off
   * this prompt text.
   *
   * <p>Only if the condensed block still does not fit are entries dropped, from the tail, so the
   * numbered findings whose ids {@code previous_findings_status} is keyed to outlive the advisory
   * "answered in earlier rounds" list that follows them. That is real forgetting, so it degrades
   * the safe way: an unreported finding is held open by the approve backstop rather than counted
   * resolved, and the elision is disclosed — in-band to the model, which is the consumer of the
   * block, and at {@code WARN} to the operator, the same channel the existing overhead shortfall
   * uses.
   *
   * <p>Idempotent: a block already at or under its share is returned untouched, and so is the very
   * same {@code inputs} instance.
   */
  public AiReviewService.PromptInputs boundPreviousFindings(AiReviewService.PromptInputs inputs) {
    var previousFindings = inputs.previousFindings();
    if (previousFindings == null || previousFindings.isBlank()) {
      return inputs;
    }
    if (activeModel.maxInputTokens() <= 0) {
      // Budgeting is explicitly off: the operator asked for no cap, and this is not the place to
      // reintroduce one.
      return inputs;
    }
    var cap = Math.max(1, (int) (perCallInputBudget() * PREVIOUS_FINDINGS_BUDGET_SHARE));
    var before = tokenCounter.estimateTokens(previousFindings);
    if (before <= cap) {
      return inputs;
    }
    var bounded = condensePreviousFindings(previousFindings, cap);
    Log.warnf(
        "Previous-findings context (%d tokens) exceeds its %d-token share of the input budget;"
            + " condensed %d finding(s) to id, location and title%s",
        before,
        cap,
        bounded.keptEntries(),
        bounded.droppedEntries() == 0
            ? ""
            : " and omitted " + bounded.droppedEntries() + " that still did not fit");
    return new AiReviewService.PromptInputs(
        inputs.diff(),
        inputs.prContext(),
        inputs.baseComparison(),
        inputs.projectStack(),
        inputs.relatedTests(),
        bounded.text(),
        inputs.repoInstructions());
  }

  /** A bounded previous-findings block: its text and what the bounding cost. */
  private record BoundedFindings(String text, int keptEntries, int droppedEntries) {}

  /**
   * Condenses the block to entry lines only and, if that still overruns {@code capTokens}, keeps
   * the longest prefix of entries that fits. The fence lines are structural, not entries: they are
   * carried across the cut so the untrusted region can never end up unterminated with the trailing
   * notice — our own instruction — swallowed inside it.
   */
  private BoundedFindings condensePreviousFindings(String block, int capTokens) {
    var all = block.split("\n", -1);
    var fenced = isFenced(all);
    var body = fenced ? List.of(all).subList(1, all.length - 1) : List.of(all);

    var condensed = entryLinesOnly(body);

    // The disclosure has to survive the cap that forced it, so its cost — and the fences' — comes
    // off the top rather than being what gets dropped.
    var reserve = tokenCounter.estimateTokens(elisionNotice(condensed.entries()));
    if (fenced) {
      reserve += 2 * tokenCounter.estimateTokens(all[0]);
    }
    var capped = longestPrefixWithin(condensed.lines(), capTokens, reserve);

    var text = new StringBuilder();
    if (fenced) {
      text.append(all[0]).append('\n');
    }
    text.append(String.join("\n", capped.lines()));
    if (fenced) {
      text.append('\n').append(all[0]);
    }
    text.append('\n').append(elisionNotice(capped.dropped()));
    return new BoundedFindings(
        text.toString(), condensed.entries() - capped.dropped(), capped.dropped());
  }

  /**
   * Whether the block is wrapped in the untrusted-data fence, which is only true when the first and
   * last lines are the same fence line and there is a body between them.
   */
  private static boolean isFenced(String[] lines) {
    return lines.length > 2
        && lines[0].startsWith(PromptTemplateEscaper.fencePrefix())
        && lines[0].equals(lines[lines.length - 1]);
  }

  /** The condensation pass's output: the surviving lines and how many entries they open. */
  private record CondensedBody(List<String> lines, int entries) {}

  /**
   * Drops every line that continues an entry — description, quoted code, thread reply — keeping the
   * entry lines themselves and any preamble that precedes the first entry.
   */
  private static CondensedBody entryLinesOnly(List<String> body) {
    var condensed = new ArrayList<String>(body.size());
    var entries = 0;
    var inEntry = false;
    for (var line : body) {
      var startsEntry = startsEntry(line);
      if (startsEntry) {
        entries++;
        inEntry = true;
      }
      if (startsEntry || !inEntry) {
        condensed.add(line);
      }
    }
    return new CondensedBody(condensed, entries);
  }

  /** The capping pass's output: the lines that fit and how many entries the cut dropped. */
  private record CappedBody(List<String> lines, int dropped) {}

  /**
   * The longest leading run of {@code lines} whose estimated cost, on top of {@code reserve}, stays
   * within {@code capTokens}. Cutting is one-way: once a line does not fit, nothing after it is
   * reconsidered, so the kept entries are a prefix and their ids never shift.
   */
  private CappedBody longestPrefixWithin(List<String> lines, int capTokens, int reserve) {
    var kept = new ArrayList<String>(lines.size());
    var used = reserve;
    var dropped = 0;
    var cutting = false;
    for (var line : lines) {
      if (!cutting) {
        var cost = tokenCounter.estimateTokens(line);
        if (used + cost <= capTokens) {
          kept.add(line);
          used += cost;
          continue;
        }
        cutting = true;
      }
      if (startsEntry(line)) {
        dropped++;
      }
    }
    return new CappedBody(kept, dropped);
  }

  /**
   * Whether the line opens a previous-findings entry: a numbered finding ({@code "3. [HIGH] …"},
   * the id space {@code previous_findings_status} references) or an answered-earlier bullet. Detail
   * lines — descriptions, quoted code, thread replies — are indented under their entry, so they
   * never match; an untrusted description line that mimics the shape at most survives condensation,
   * which is what it already did before it.
   */
  private static boolean startsEntry(String line) {
    return line.startsWith("- ") || NUMBERED_ENTRY.matcher(line).find();
  }

  /**
   * The elision disclosure, appended outside the fence so it reads as instruction rather than as
   * more untrusted data. It tells the model what it is looking at (identity only) and forbids the
   * one dangerous inference — that a finding it cannot see in full has been dealt with.
   *
   * <p>It also restates what the block's own section header used to say about the unnumbered
   * entries. That header is a detail line under the entry above it and condenses away with the
   * rest; saying it here instead makes the meaning survive the elision, keeps it out of the
   * untrusted region, and does not make the bound depend on matching another class's prose.
   */
  private static String elisionNotice(int droppedEntries) {
    var condensed =
        "(The previous findings above were condensed to id, location and title to fit this"
            + " review's token budget; their descriptions and comment threads are not shown. Judge"
            + " each by its location and title; one you cannot see in full is still open unless"
            + " this diff shows it fixed. An entry with no id, written \"- file:line — title\", was"
            + " answered in an earlier round: do not raise it again and do not include it in"
            + " previous_findings_status.)";
    if (droppedEntries <= 0) {
      return condensed;
    }
    return condensed
        + "\n("
        + droppedEntries
        + " further previous finding(s) did not fit and were omitted entirely. Never report a"
        + " finding you cannot see as resolved, and do not re-raise one as if it were new.)";
  }

  /**
   * The per-call input-token budget every review-path AI call must fit — {@code max-input-tokens *
   * token-safety-margin - reserved-output-tokens} — or {@link Integer#MAX_VALUE} when budgeting is
   * disabled. Each term is the active model's effective value ({@link ActiveModelSettings}): the
   * max input tokens are the global budget bounded by the model's input cap, the margin honors the
   * per-model override, and the reservation is the (per-model-overridable) output buffer on a
   * shared window but zero for a model marked {@code separate-output-budget} — its response never
   * draws on this pool. Shared with the pipeline so the summary call is bounded by the same ceiling
   * as the batch calls.
   */
  public int perCallInputBudget() {
    var maxInputTokens = activeModel.maxInputTokens();
    if (maxInputTokens <= 0) {
      return Integer.MAX_VALUE;
    }
    return (int) (maxInputTokens * activeModel.tokenSafetyMargin())
        - activeModel.reservedOutputTokens();
  }

  /**
   * Plans batches given the per-call input budget and the shared prompt overhead that every batch
   * call repeats. The diff budget is what remains of {@code inputBudgetTokens} after that overhead,
   * so each call's full prompt (shared + one batch's diff) stays within the model's input limit.
   * Overhead consuming the whole budget must not silently disable budgeting (the operator
   * configured a limit; the prompt is at its largest exactly then), so the diff budget is floored
   * at 1 token — most files then overflow into {@link BudgetPlan#omittedFiles()} and the partial
   * review is disclosed.
   */
  BudgetPlan plan(
      List<GitHubPullRequestClient.FileDiff> reviewable,
      String sharedPromptOverhead,
      int inputBudgetTokens,
      int maxBatches) {
    var overheadTokens = tokenCounter.estimateTokens(sharedPromptOverhead);
    var diffBudget = inputBudgetTokens - overheadTokens;
    if (diffBudget <= 0) {
      Log.warnf(
          "Shared prompt overhead (%d tokens) consumes the whole input budget (%d tokens);"
              + " batching with a minimal diff budget — most files will be omitted by name",
          overheadTokens, inputBudgetTokens);
      diffBudget = 1;
    }
    return plan(reviewable, diffBudget, maxBatches);
  }

  /**
   * Plans batches for the already-ignore-glob-filtered {@code reviewable} files. A {@code
   * diffBudgetTokens <= 0} disables budgeting — every file lands in a single batch (the legacy "no
   * cap" behaviour for an explicit {@code max-input-tokens=0}); overload above never passes it.
   */
  BudgetPlan plan(
      List<GitHubPullRequestClient.FileDiff> reviewable, int diffBudgetTokens, int maxBatches) {
    var budgeted = diffBudgetTokens > 0;
    if (reviewable.isEmpty()) {
      return new BudgetPlan(
          List.of(), List.of(), List.of(), budgeted, null, null, null, null, null);
    }

    var rendered = renderAndSize(reviewable, diffBudgetTokens);

    if (!budgeted) {
      return new BudgetPlan(
          List.of(toBatch(rendered.sized())),
          List.of(),
          List.of(),
          false,
          null,
          null,
          null,
          null,
          null);
    }
    return pack(rendered, diffBudgetTokens, Math.max(1, maxBatches));
  }

  /** A file rendered to its diff section with a token estimate (oversized files pre-clipped). */
  private record Sized(GitHubPullRequestClient.FileDiff file, String text, int tokens) {}

  /**
   * Rendered sections plus the files no clipping could fit (omitted by name, never packed) and the
   * files that were clipped to fit (covered, but only partially analyzed).
   */
  private record Rendered(List<Sized> sized, List<String> unclippable, List<String> clipped) {}

  private Rendered renderAndSize(
      List<GitHubPullRequestClient.FileDiff> reviewable, int diffBudgetTokens) {
    var ordered = new ArrayList<>(reviewable);
    // The name tie-break only fires between files of equal size, and natural ordering on a null
    // key throws. Unnamed last: packing is impact-descending, so whatever sorts last is what the
    // bin cap omits first, and a degenerate entry must never displace a well-formed file — a
    // finding on a file the model cannot name has no path to anchor an inline comment to anyway.
    ordered.sort(
        Comparator.comparingInt(
                (GitHubPullRequestClient.FileDiff f) -> f.additions() + f.deletions())
            .reversed()
            .thenComparing(
                GitHubPullRequestClient.FileDiff::filename,
                Comparator.nullsLast(Comparator.naturalOrder())));

    var reviewableNames = ReviewDiffFormatter.namesOf(reviewable);
    var rendered = new Rendered(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    for (var file : ordered) {
      var section = formatter.formatFileSection(file, reviewableNames);
      if (diffBudgetTokens <= 0) {
        // The disabled-budgeting path never reads the estimates; skip the BPE pass entirely.
        rendered.sized().add(new Sized(file, section, 0));
      } else {
        sizeWithinBudget(file, section, diffBudgetTokens, rendered);
      }
    }
    return rendered;
  }

  /**
   * Estimates (clipping if oversized) one file section into the rendered result. A clipped file is
   * recorded so the summary can disclose the partial coverage; a file no clip fits is recorded as
   * unclippable — content the model would never see must not count as reviewed, so it is reported
   * by name (holds APPROVE, disclosed) instead of packing a placeholder as coverage.
   */
  private void sizeWithinBudget(
      GitHubPullRequestClient.FileDiff file,
      String section,
      int diffBudgetTokens,
      Rendered rendered) {
    if (isPatchlessWithChanges(file)) {
      // GitHub returns a null/blank patch for binary files and for text diffs too large to
      // display, while still reporting real additions/deletions. Its rendered section is a bare
      // header with no ```diff``` body — there is nothing for the model to read — so packing it
      // would count the file as fully reviewed and let an unbacked "resolved" claim through.
      // Omit it by name like an unclippable file: never packed, holds APPROVE, disclosed.
      recordName(rendered.unclippable(), file.filename());
      return;
    }
    var tokens = tokenCounter.estimateTokens(section);
    if (tokens > diffBudgetTokens) {
      var clipped = clipToBudget(section, diffBudgetTokens);
      if (clipped == null) {
        recordName(rendered.unclippable(), file.filename());
        return;
      }
      recordName(rendered.clipped(), file.filename());
      section = clipped;
      tokens = tokenCounter.estimateTokens(clipped);
    }
    rendered.sized().add(new Sized(file, section, tokens));
  }

  /**
   * Records a coverage-gap filename, standing in for a null one. {@code FileDiff.filename()} is not
   * validated at construction, and a null name reaching the plan's name lists makes the {@code
   * List.copyOf} in the {@link BudgetPlan} constructor throw — failing the whole review at plan
   * time for a file the disclosure could not have named anyway.
   *
   * <p>Dropping the entry instead would be worse than the crash it avoids, and silently: these
   * lists are not only display text, their <em>size</em> is what {@link BudgetPlan#truncated()}
   * gates APPROVE on. If the unnamed file were the only gap, the count would fall to zero and a
   * file the model never read would stop withholding approval — the bot approving a PR it did not
   * fully see. So the gap keeps a slot and the disclosure names it as best it can (#473).
   *
   * <p>Two unnamed gaps in different classes can collide on the placeholder and be deduplicated by
   * the omitted/clipped disjointness filter. Both are gaps and both hold approval, so the outcome
   * stays honest in the direction that matters.
   */
  private static void recordName(List<String> names, String filename) {
    names.add(filename == null ? UNNAMED_FILE : filename);
  }

  /**
   * A reviewable file GitHub reported with real additions/deletions but no patch text (binary, or a
   * text diff too large to display). It survives {@link ReviewDiffFormatter#isPureRename} (which
   * needs a zero change count) yet has no diff to review, so it must be omitted, not packed.
   */
  private static boolean isPatchlessWithChanges(GitHubPullRequestClient.FileDiff file) {
    var patch = file.patch();
    return (patch == null || patch.isBlank()) && file.additions() + file.deletions() > 0;
  }

  private static DiffBatch toBatch(List<Sized> sized) {
    var text = new StringBuilder();
    var files = new ArrayList<GitHubPullRequestClient.FileDiff>(sized.size());
    var tokens = 0;
    for (var s : sized) {
      text.append(s.text());
      files.add(s.file());
      tokens += s.tokens();
    }
    return new DiffBatch(text.toString(), files, tokens);
  }

  private static BudgetPlan pack(Rendered rendered, int diffBudgetTokens, int maxBatches) {
    var binSections = new ArrayList<List<Sized>>();
    var binTokens = new ArrayList<Integer>();
    var omitted = new ArrayList<>(rendered.unclippable());

    for (var s : rendered.sized()) {
      int target = firstFit(binTokens, s.tokens(), diffBudgetTokens);
      if (target < 0 && binSections.size() < maxBatches) {
        binSections.add(new ArrayList<>());
        binTokens.add(0);
        target = binSections.size() - 1;
      }
      if (target < 0) {
        recordName(omitted, s.file().filename());
        continue;
      }
      binSections.get(target).add(s);
      binTokens.set(target, binTokens.get(target) + s.tokens());
    }

    var batches = new ArrayList<DiffBatch>(binSections.size());
    for (var bin : binSections) {
      batches.add(toBatch(bin));
    }
    // A clipped file can still overflow every bin and end up omitted; each file must land in
    // exactly one class or the disclosure would list it twice and the verdict double-count it.
    var omittedSet = new HashSet<>(omitted);
    var clipped = rendered.clipped().stream().filter(n -> !omittedSet.contains(n)).toList();
    return new BudgetPlan(batches, omitted, clipped, true, null, null, null, null, null);
  }

  /** Index of the first open bin with room for {@code tokens}, or -1 if none. */
  private static int firstFit(List<Integer> binTokens, int tokens, int budget) {
    for (var i = 0; i < binTokens.size(); i++) {
      if (binTokens.get(i) + tokens <= budget) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Hunk-clips a single oversized file section down to {@code budgetTokens}. Converts the token
   * budget to a line budget by ratio and re-clips from the original a few times until it fits — a
   * bounded loop, since {@link ReviewDiffFormatter#truncateSection} is monotonic in line count.
   * Returns {@code null} when even a one-line clip stays over budget (pathologically small
   * budgets): the caller must omit the file by name rather than present a stub as coverage.
   */
  private String clipToBudget(String section, int budgetTokens) {
    var target = Math.max(1, (int) (budgetTokens * 0.9));
    var lines = ReviewDiffFormatter.lineCount(section);
    var clipped = section;
    var clippedTokens = tokenCounter.estimateTokens(clipped);
    for (var i = 0; i < 8 && clippedTokens > budgetTokens; i++) {
      var ratio = Math.min(0.95, (double) target / Math.max(1, clippedTokens));
      lines = Math.max(1, (int) (lines * ratio));
      clipped = ReviewDiffFormatter.truncateSection(section, lines);
      clippedTokens = tokenCounter.estimateTokens(clipped);
    }
    if (clippedTokens > budgetTokens) {
      Log.warnf(
          "File section exceeds the %d-token budget even after clipping; omitting the file by name",
          budgetTokens);
      return null;
    }
    return clipped;
  }
}
