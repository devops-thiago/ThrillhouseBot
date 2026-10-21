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
import dev.thiagogonzaga.thrillhousebot.review.ai.PrReviewPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Generates the PR summary comment posted on the first review. */
@ApplicationScoped
public class PrSummaryGenerator {

  // Walkthrough-diagram master switch; also gates rendering when the model volunteers an
  // unrequested walkthrough_diagram.
  private final boolean diagramEnabled;

  // Opt-in "large PR, nothing inline" note; carries its own thresholds and off switch.
  private final LargePrNudge largePrNudge;

  @Inject
  public PrSummaryGenerator(ThrillhouseConfig config) {
    this(config.review().diagram().enabled(), LargePrNudge.from(config.review().largePrNudge()));
  }

  /** Visible for tests; the large-PR nudge stays off, matching its shipped default. */
  PrSummaryGenerator(boolean diagramEnabled) {
    this(diagramEnabled, LargePrNudge.DISABLED);
  }

  /** Visible for tests: pins the diagram switch and the large-PR nudge policy together. */
  PrSummaryGenerator(boolean diagramEnabled, LargePrNudge largePrNudge) {
    this.diagramEnabled = diagramEnabled;
    this.largePrNudge = largePrNudge;
  }

  /** The clean-review celebration; rendered inside the summary, never as a separate comment. */
  public static final String ZERO_ISSUES_MESSAGE =
      "Everything's coming up Thrillhouse! 🎉\n\nNo issues found in this PR.";

  /**
   * The first line of every generated summary. Doubles as the marker used to recognize a summary
   * comment the bot already posted on a PR, so a re-review never duplicates it.
   */
  public static final String SUMMARY_HEADING = "## 🤖 ThrillhouseBot PR Summary";

  /**
   * Invisible first line of the summary comment as posted, in the same {@code <!--
   * thrillhousebot:... -->} shape as the finding marker, so a later round can find the comment and
   * edit it in place (#868). It is written at the top because GitHub's size cap cuts the tail of an
   * over-long body, and the marker must survive that cut. The heading stays a recognizer too: a
   * summary posted before this marker existed carries only the heading.
   */
  public static final String SUMMARY_MARKER = "<!-- thrillhousebot:summary -->";

  /**
   * Upper bound on rows in the changed-files walkthrough. Keeps the comment within GitHub's size
   * budget on large PRs; any files beyond this are rolled up into a trailing "… and N more" note.
   *
   * <p>Read from {@link PrReviewPrompts#MAX_FILE_SUMMARIES} so the number of rows rendered and the
   * number of per-file summaries the prompt asks the model for cannot drift apart: a smaller prompt
   * cap would guarantee rows no response could ever fill (#536).
   */
  static final int MAX_FILE_ROWS = PrReviewPrompts.MAX_FILE_SUMMARIES;

  /**
   * Summary cell for a pure-rename row. A pure rename is excluded from AI review by design (its
   * content is unchanged, so there is nothing to review), which means the model never returns a
   * summary for it — rendering the bare "-" made that correct behavior read as a failed summary
   * (#536), so the row states the reason instead.
   */
  static final String PURE_RENAME_SUMMARY = "Pure rename — content unchanged";

  /**
   * Summary cell for a reviewed row the model returned no per-file note for. The bare "-" it
   * replaces was indistinguishable from a rendering bug: it stated nothing about why the cell was
   * empty, on exactly the rows a large PR leaves unsummarized (#547). Says the reason instead, for
   * the same reason {@link #PURE_RENAME_SUMMARY} does — the two cover the whole unsummarized set,
   * one row per cause.
   */
  static final String NO_MODEL_SUMMARY = "Not summarized — no model summary for this file";

  /** Heading of the Description vs. Implementation section when a mismatch exists. */
  static final String GAPS_HEADING = "### ⚠️ Description vs. Implementation";

  /** The heading of the criteria the description shows met by existing code (#1148). */
  static final String MET_HEADING = "### ✅ Met by existing code";

  /** The line under {@link #MET_HEADING}. */
  static final String MET_INTRO =
      "The description cites code already on the base branch for these linked-issue criteria:\n";

  /**
   * Opening of a Description vs. Implementation bullet that names a finding instead of restating it
   * (#964). The section used to say only that every mismatch "is reported as a finding below" once
   * #588 had collapsed each gap onto a finding: it named none, it was said over findings that did
   * not concern the description at all, and a description finding the summary call gave no gap for
   * was missing from the section, sometimes from Key Findings too. The section now names each such
   * finding by title and location. The carry-over skips these bullets: the finding is carried on
   * its own, and keeps the bullet listed for as long as it is open.
   */
  static final String GAP_FINDING_PREFIX = "Reported as a finding: ";

  /** A linked issue named in a finding, for the section's intro line. */
  private static final Pattern LINKED_ISSUE_MENTION = Pattern.compile("(?i)\\blinked\\s++issue\\b");

  /** Verbs after "description" that say what it asserts: "the description claims/states/says…". */
  private static final String CLAIMING_VERBS = "claim(?:s|ed)?|state[sd]?|says|said";

  /** …what it promises or implies. */
  private static final String PROMISING_VERBS = "promise[sd]?|asserts|implies|advertises";

  /** …what it describes or covers. */
  private static final String DESCRIBING_VERBS = "mentions?|describes?|lists|documents|covers";

  /** …what it leaves out: "the description omits/does not/never/leaves…". */
  private static final String DENYING_VERBS = "omit(?:s|ted)?|does\\s++not|doesn['’]t|never|leaves";

  /**
   * What marks a finding as a mismatch between the change and what the PR description or its linked
   * issue says (#964): "Description claims…", "the PR description states…", "contrary to the PR's
   * stated behavior", "the PR title says…", "the author claims…", "the linked issue asks…". One
   * pattern per shape, and the verb vocabulary in named parts, so each stays well under the regex
   * complexity bound. A finding's title and description are each matched on their own, so a shape
   * cannot be pieced together across them.
   */
  private static final List<Pattern> DESCRIPTION_CLAIMS =
      List.of(
          Pattern.compile("(?i)\\b(?:pr|pull request)(?:['’]s)?\\s++(?:description|body|title)\\b"),
          Pattern.compile(
              "(?i)\\b(?:pr|pull request|author)(?:['’]s)?\\s++"
                  + "(?:(?:state|claim|promise)s?|says|said)\\b"),
          Pattern.compile(
              "(?i)\\bdescription\\s++(?:\\w+\\s+)?(?:"
                  + CLAIMING_VERBS
                  + "|"
                  + PROMISING_VERBS
                  + "|"
                  + DESCRIBING_VERBS
                  + "|"
                  + DENYING_VERBS
                  + ")\\b"),
          Pattern.compile("(?i)\\bstated\\s++(?:behaviou?r|intent|purpose)\\b"),
          Pattern.compile(
              "(?i)\\b(?:contrary to|contradicts|inconsistent with|unlike)\\s++the\\s++"
                  + "(?:pr|pull request|description|stated)\\b"),
          LINKED_ISSUE_MENTION);

  /**
   * One changed file in the walkthrough: its path, the diff's authoritative change type, and
   * whether it is a pure rename — renamed with no content change, hence never sent to the model and
   * never carrying a model summary.
   */
  public record ChangedFile(String path, String changeType, boolean pureRename) {

    /** A row whose pure-rename status is not distinguished; treated as ordinary content change. */
    public ChangedFile(String path, String changeType) {
      this(path, changeType, false);
    }
  }

  /**
   * What the Description vs. Implementation section is held to (#1074).
   *
   * @param claimText the PR title and description and the linked-issue section, unfenced; a finding
   *     is named under the section only when it quotes or paraphrases this text. Blank when not
   *     known, which names every description finding as before
   * @param previouslyNamed the "Reported as a finding" bullets the previous round's summary listed,
   *     as rendered, when this round reviews the head that round reviewed; empty otherwise. A
   *     finding still open that one of them names is named again, so a re-review of the same commit
   *     does not drop it
   * @param linkedIssues the unfenced linked-issue section alone, {@code ""} when none; with one
   *     issue linked, each criterion the round states unmet gets its own line ({@link
   *     LinkedIssueCriteria}, #1093)
   * @param baseCode the code the PR description cites as already meeting a criterion; a criterion
   *     it shows met on the base branch is listed as met by existing code instead ({@link
   *     BaseCodeCitations}, #1148)
   */
  record DescriptionSources(
      String claimText,
      Set<String> previouslyNamed,
      String linkedIssues,
      BaseCodeCitations baseCode) {

    static final DescriptionSources NONE = new DescriptionSources("", Set.of());

    DescriptionSources {
      claimText = claimText == null ? "" : claimText;
      previouslyNamed = Set.copyOf(previouslyNamed);
      linkedIssues = Objects.requireNonNullElse(linkedIssues, "");
      baseCode = Objects.requireNonNullElse(baseCode, BaseCodeCitations.NONE);
    }

    /** Sources with no description citations read. */
    DescriptionSources(String claimText, Set<String> previouslyNamed, String linkedIssues) {
      this(claimText, previouslyNamed, linkedIssues, BaseCodeCitations.NONE);
    }

    /** Sources with no linked-issue section known. */
    DescriptionSources(String claimText, Set<String> previouslyNamed) {
      this(claimText, previouslyNamed, "");
    }

    /** Whether {@code finding} quotes or paraphrases the claim text; always with none known. */
    boolean echoedBy(Finding finding) {
      return claimText.isBlank()
          || findingTexts(finding).anyMatch(text -> DescriptionGapScreen.echoes(text, claimText));
    }
  }

  /**
   * Upper bound on the rendered Mermaid source. A diagram larger than this is dropped rather than
   * posted: it is likely runaway or truncated output, and GitHub silently fails to render oversized
   * Mermaid blocks anyway. Comfortably fits the ~12-node diagrams the prompt asks for.
   */
  static final int MAX_DIAGRAM_CHARS = 4000;

  /**
   * Mermaid diagram-type keywords the first source line may start with. The model is asked for a
   * flowchart or sequence diagram; the wider set is accepted so a valid diagram is never dropped on
   * a technicality, while arbitrary prose (which would render as a broken block) still is.
   */
  private static final List<String> MERMAID_PREFIXES =
      List.of(
          "graph",
          "flowchart",
          "sequencediagram",
          "classdiagram",
          "statediagram",
          "erdiagram",
          "journey",
          "gantt");

  /**
   * Renders the summary comment. {@code filesChanged}/{@code additions}/{@code deletions} are
   * GitHub's authoritative PR-level totals (or the diff-derived fallback), so the "Changes
   * Overview" reflects the whole PR even when files are dropped by the ignore-glob. {@code
   * changedFiles} is the reviewable (non-ignored) file list rendered as the walkthrough table; its
   * rollup "…and N more" note is derived from {@code filesChanged} so it, too, matches the
   * authoritative total.
   */
  public String generate(
      int filesChanged,
      int additions,
      int deletions,
      List<ChangedFile> changedFiles,
      ReviewResponse.Summary aiSummary,
      ReviewResult round) {
    return generate(
        filesChanged,
        additions,
        deletions,
        changedFiles,
        aiSummary,
        round,
        DescriptionSources.NONE);
  }

  /**
   * {@link #generate(int, int, int, List, ReviewResponse.Summary, ReviewResult)}, with the
   * Description vs. Implementation section held to {@code sources} (#1074).
   */
  public String generate(
      int filesChanged,
      int additions,
      int deletions,
      List<ChangedFile> changedFiles,
      ReviewResponse.Summary aiSummary,
      ReviewResult round,
      DescriptionSources sources) {
    // The nudge keys off this round (see LargePrNudge) and stays off while an earlier finding is
    // open (#933); every other section describes the pull request as it stands, earlier rounds'
    // open findings included (#917).
    var state = currentState(round);
    var result = state.result();
    var carried = state.carried();
    var sb = new StringBuilder();
    sb.append(SUMMARY_HEADING).append("\n\n");

    // One observation must be published once. The findings are the most specific surface, so a
    // description-gap bullet — or a walkthrough clause appended after the row's file summary —
    // that only restates one of them is collapsed away before anything is rendered (#588). A gap
    // gives way only to a finding the section names in its place (#1205).
    var surfaces =
        SummarySurfaceDeduplicator.collapse(
            descriptionGaps(aiSummary),
            summariesByPath(aiSummary),
            result.findings(),
            sources::echoedBy);

    appendPrPurpose(sb, aiSummary);
    // #1093: every criterion of the one linked issue the round states unmet gets its own line; a
    // line pointing at a finding takes the place of that finding's bullet.
    var rendered = backedByFindings(surfaces.descriptionGaps(), result.findings());
    var criteria =
        LinkedIssueCriteria.unmet(
            sources.linkedIssues(),
            rendered,
            result.findings(),
            aiSummary == null ? null : aiSummary.prPurpose(),
            summariesByPath(aiSummary));
    var gaps = new ArrayList<>(rendered);
    gaps.addAll(criteria.lines());
    // #1148: a criterion the description shows met by code already on the base branch.
    var split = sources.baseCode().split(gaps);
    appendDescriptionGaps(
        sb,
        split.unmet(),
        descriptionFindings(surfaces.gapFindings(), result.findings(), sources).stream()
            .filter(f -> !criteria.pointed().contains(f))
            .toList());
    appendMetByExistingCode(sb, split.met());
    appendWalkthroughDiagram(sb, aiSummary);

    sb.append("### Changes Overview\n");
    sb.append("- **Files changed:** ").append(filesChanged).append("\n");
    sb.append("- **Lines added:** ").append(signed('+', additions)).append("\n");
    sb.append("- **Lines removed:** ").append(signed('-', deletions)).append("\n\n");

    appendChangedFiles(sb, filesChanged, changedFiles, surfaces.fileSummaries());

    sb.append("### Risk Assessment\n");
    sb.append("| Risk | Count |\n");
    sb.append("|------|-------|\n");
    sb.append("| 🔴 Critical | ").append(result.criticalCount()).append(" |\n");
    sb.append("| 🟠 High | ").append(result.highCount()).append(" |\n");
    sb.append("| 🟡 Medium | ").append(result.mediumCount()).append(" |\n");
    sb.append("| 🔵 Low | ").append(result.lowCount()).append(" |\n\n");
    if (!carried.isEmpty()) {
      sb.append(carriedCountNote(carried.size()));
    }

    appendPreviousFindings(sb, result);
    appendFindingsOrCelebration(sb, result, carried);
    appendDoubleCheckFindings(sb, result, carried);
    // After the findings sections so the note can refer back to them, and so a reader meets the
    // celebration (or the double-check list) before the caveat about how much to trust it.
    largePrNudge.render(filesChanged, additions, deletions, round).ifPresent(sb::append);
    appendCiChecks(sb, result);

    sb.append("---\n");
    sb.append("*Automated review by ThrillhouseBot. Reply with `/review` to re-run.*\n");

    return sb.toString();
  }

  /**
   * What the summary renders: {@code result}, whose findings and risk counts cover the pull request
   * as it stands, and {@code carried}, the entries among them that are open since an earlier review
   * (by identity, so a carried finding is told apart from an equal new one). {@code carried} has
   * exactly one entry per earlier finding still open, so the "including N" line always equals the
   * still-open set the "Still present" count is taken over.
   */
  record CurrentState(ReviewResult result, Set<Finding> carried) {}

  /**
   * The result the summary renders: this round's findings followed by the earlier rounds' findings
   * still open, with the risk counts taken over both. The summary comment is edited in place every
   * round (#868) and nothing from the earlier render survives the edit, so a render of this round
   * alone read "Critical 0" over a pull request with a critical finding still open, while the same
   * comment's status table counted it as still present (#917). The counts and lists are merged
   * here, deterministically, rather than asked of the summary call, so its input stays this round's
   * findings. {@code round} is returned as-is when nothing earlier is open.
   *
   * <p>Every still-open finding is listed; none is dropped for resembling another (#934). The only
   * fold is the same finding raised again by this round — the same file, line and title ({@link
   * #reRaises}) — which is listed once, as the round's entry, marked as open since an earlier
   * review, and at the higher of the two severities. Each round finding absorbs at most one earlier
   * one, so the carried count cannot fall below the still-open set.
   */
  static CurrentState currentState(ReviewResult round) {
    var open = round.openPreviousFindings();
    if (open.isEmpty()) {
      return new CurrentState(round, Set.of());
    }
    var own = round.findings();
    var findings = new ArrayList<Finding>(own.size() + open.size());
    findings.addAll(own);
    var absorbed = new boolean[own.size()];
    Set<Finding> carried = Collections.newSetFromMap(new IdentityHashMap<>());
    for (var earlier : open) {
      var at = reRaisedAt(own, absorbed, earlier);
      if (at < 0) {
        findings.add(earlier);
        carried.add(earlier);
        continue;
      }
      absorbed[at] = true;
      var raised = own.get(at);
      var kept =
          earlier.risk().compareTo(raised.risk()) < 0 ? withRisk(raised, earlier.risk()) : raised;
      findings.set(at, kept);
      carried.add(kept);
    }
    var counts = new EnumMap<RiskLevel, Integer>(RiskLevel.class);
    for (Finding f : findings) {
      counts.merge(f.risk(), 1, Integer::sum);
    }
    var highest = findings.stream().map(Finding::risk).min(Comparator.naturalOrder()).orElse(null);
    var result =
        new ReviewResult(
            findings,
            counts.getOrDefault(RiskLevel.CRITICAL, 0),
            counts.getOrDefault(RiskLevel.HIGH, 0),
            counts.getOrDefault(RiskLevel.MEDIUM, 0),
            counts.getOrDefault(RiskLevel.LOW, 0),
            highest,
            round.reviewState(),
            round.isFirstReview(),
            round.summaryMarkdown(),
            round.previousStatuses(),
            round.offendingCiChecks(),
            round.omittedFiles(),
            round.ciUnreadable(),
            round.requiredContextsKnown(),
            round.truncation(),
            round.blockingWithheldByConfidence(),
            open);
    return new CurrentState(result, carried);
  }

  /** Position of the first round finding not yet absorbed that re-raises {@code earlier}, or -1. */
  private static int reRaisedAt(List<Finding> own, boolean[] absorbed, Finding earlier) {
    for (var i = 0; i < own.size(); i++) {
      if (!absorbed[i] && reRaises(own.get(i), earlier)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Whether a round's finding is an earlier finding raised again, by identity: the same file, the
   * same line and the same title (ignoring case and surrounding space). Deliberately not the
   * tolerant {@link FollowUpAnalyzer#isSameFinding}: two distinct findings a line apart, or a
   * critical whose text overlaps a low, read as alike to it, and folding them dropped an open
   * finding from the summary (#934). A reworded or drifted re-raise is listed as its own entry,
   * which is what it is on the pull request: a second open thread. A finding with no title has
   * nothing to match on and is never folded.
   */
  static boolean reRaises(Finding raised, Finding earlier) {
    return raised.line() == earlier.line()
        && FilePaths.same(raised.file(), earlier.file())
        && raised.title() != null
        && earlier.title() != null
        && !raised.title().isBlank()
        && raised.title().strip().equalsIgnoreCase(earlier.title().strip());
  }

  private static Finding withRisk(Finding finding, RiskLevel risk) {
    return new Finding(
        risk,
        finding.confidence(),
        finding.file(),
        finding.line(),
        finding.title(),
        finding.description(),
        finding.suggestionOld(),
        finding.suggestionNew());
  }

  /**
   * The line closing Key Findings when the cap left findings out (#964), so the list does not read
   * as every finding there is.
   */
  static String keyFindingsMoreLine(int leftOut) {
    return "- _…and "
        + leftOut
        + " more finding"
        + (leftOut == 1 ? "" : "s")
        + ", each on its own review thread._\n";
  }

  /** Suffix on a Key Findings or double-check bullet for a finding raised by an earlier review. */
  static final String CARRIED_NOTE = "_(open since an earlier review)_";

  /** The line under the Risk Assessment table saying how many of its findings are carried. */
  static String carriedCountNote(int carried) {
    return "_Counts every finding still open on this pull request, including "
        + carried
        + (carried == 1 ? " finding" : " findings")
        + " raised by an earlier review._\n\n";
  }

  private static void appendPreviousFindings(StringBuilder sb, ReviewResult result) {
    // The record constructor guarantees a non-null status list.
    if (result.previousStatuses().isEmpty()) {
      return;
    }
    var resolved =
        result.previousStatuses().stream()
            .filter(s -> "resolved".equalsIgnoreCase(s.status()))
            .count();
    var unresolved =
        result.previousStatuses().stream()
            .filter(s -> "unresolved".equalsIgnoreCase(s.status()))
            .count();
    var justified =
        result.previousStatuses().stream()
            .filter(s -> "justified".equalsIgnoreCase(s.status()))
            .count();
    var superseded =
        result.previousStatuses().stream()
            .filter(s -> "superseded".equalsIgnoreCase(s.status()))
            .count();
    sb.append("### Previous Findings Status\n");
    sb.append("| Status | Count |\n");
    sb.append("|--------|-------|\n");
    sb.append("| ✅ Resolved | ").append(resolved).append(" |\n");
    sb.append("| ⚠️ Still present | ").append(unresolved).append(" |\n");
    sb.append("| 💬 Justified | ").append(justified).append(" |\n");
    if (superseded > 0) {
      sb.append("| 🗂️ Superseded (targeted code left the diff) | ")
          .append(superseded)
          .append(" |\n");
    }
    sb.append("\n");
  }

  private static void appendFindingsOrCelebration(
      StringBuilder sb, ReviewResult result, Set<Finding> carried) {
    if (result.hasIssues()) {
      appendKeyFindings(sb, result, carried);
    } else if (hasNoUnresolvedPrevious(result)) {
      if (result.ciHoldsApproval()) {
        appendCiHold(sb, result);
      } else if (result.truncated()) {
        sb.append(
            "No new issues found in the reviewed portion of this PR — but the diff was too large to review in full, so this is a partial review.\n\n");
      } else {
        sb.append(ZERO_ISSUES_MESSAGE).append("\n\n");
      }
    }
  }

  /**
   * The Key Findings list: the highest-ranked findings that post inline, then a line counting what
   * the cap left out (#964). Nothing when no finding posts inline.
   */
  private static void appendKeyFindings(
      StringBuilder sb, ReviewResult result, Set<Finding> carried) {
    var keyFindings = result.keyFindings(carried::contains);
    if (keyFindings.isEmpty()) {
      return;
    }
    sb.append("### Key Findings\n");
    for (Finding f : keyFindings) {
      // Model-supplied title/path: flatten and neutralize break-outs (a newline, a "```", or a
      // "</details>" in the title must not escape the bullet or the summary's <details> blocks).
      sb.append("- **")
          .append(f.risk().name())
          .append(":** ")
          .append(MarkdownSafe.inline(f.title()))
          .append(" (`")
          .append(MarkdownSafe.inlineCode(f.file()))
          .append(":")
          .append(f.line())
          .append("`)");
      if (carried.contains(f)) {
        sb.append(" ").append(CARRIED_NOTE);
      }
      sb.append("\n");
    }
    var leftOut = result.keyFindingsLeftOut();
    if (leftOut > 0) {
      sb.append(keyFindingsMoreLine(leftOut));
    }
    sb.append("\n");
  }

  /**
   * Collapsed section for low-confidence medium/low findings that were withheld from inline
   * threads. Keeps the signal visible and clearly non-blocking without opening a review thread that
   * maintainers must triage and resolve.
   *
   * <p>A bullet that says what an inline finding already says is cross-referenced to it. This list
   * never passed through the deduplicator — {@code collapse} weighs the gap bullets and walkthrough
   * rows against the findings and stops there — so one defect reported both inline and here read as
   * two independent defects, which is exactly the repetition-reads-as-severity failure the
   * deduplicator exists to prevent (#639). The bullet is annotated instead of dropped because it is
   * the finding's only rendered surface: it is the one place the {@code path:line} a maintainer
   * needs to clear it from the PR conversation is printed (#548).
   */
  private static void appendDoubleCheckFindings(
      StringBuilder sb, ReviewResult result, Set<Finding> carried) {
    var findings = result.doubleCheckFindings();
    if (findings.isEmpty()) {
      return;
    }
    var inline = result.findings().stream().filter(Finding::postsInline).toList();
    sb.append("### Things to double-check\n");
    sb.append("<details>\n");
    sb.append("<summary>")
        .append(findings.size())
        .append(" lower-confidence finding")
        .append(findings.size() == 1 ? "" : "s")
        .append("</summary>\n\n");
    for (Finding f : findings) {
      // By construction these findings are confidence LOW, so the disclaimer is always present.
      // Model-supplied title/path is flattened and its break-outs neutralized so an embedded
      // newline, "```", or "</details>" cannot escape this bullet or the enclosing <details>.
      sb.append("- **")
          .append(f.risk().name())
          .append(":** ")
          .append(MarkdownSafe.inline(f.title()))
          .append(" (`")
          .append(MarkdownSafe.inlineCode(f.file()))
          .append(":")
          .append(f.line())
          .append("`) ")
          .append(SuggestionFormatter.confidenceDisclaimer(Confidence.LOW));
      if (carried.contains(f)) {
        sb.append(" ").append(CARRIED_NOTE);
      }
      SummarySurfaceDeduplicator.restatedBy(f, inline)
          .ifPresent(published -> sb.append(" ").append(sameIssueNote(published)));
      sb.append("\n");
    }
    sb.append("\n</details>\n\n");
  }

  /** Opening of the cross-reference appended to a double-check bullet that restates a finding. */
  static final String SAME_ISSUE_PREFIX = "_Same issue as the inline finding on ";

  /**
   * The cross-reference naming the inline finding a double-check bullet restates, by the locator
   * that finding's own row and review thread carry, so a reader can see the two are one defect.
   */
  private static String sameIssueNote(Finding published) {
    return SAME_ISSUE_PREFIX
        + "`"
        + MarkdownSafe.inlineCode(published.file())
        + ":"
        + published.line()
        + "`._";
  }

  /**
   * Renders the celebration-replacement line when CI holds approval (optionally alongside a
   * truncated diff). The two CI holds read differently: an offending check is pending/failing, so
   * it is phrased as CI "not confirmed green" — with neutral "CI"/"required CI" wording per whether
   * the required set was resolved. An unreadable source is NOT a not-green result — it could not be
   * read — so it gets "could not be read" wording, matching {@code
   * VerdictBuilder.checkSummaryForResult} and the "CI Status Unavailable" section rather than
   * misreporting an unread status as failing. Reached only via {@link
   * ReviewResult#ciHoldsApproval}, so no offending check implies the hold is an unreadable source.
   */
  private static void appendCiHold(StringBuilder sb, ReviewResult result) {
    boolean offending = !result.offendingCiChecks().isEmpty();
    boolean truncated = result.truncated();
    String ciPhrase = result.requiredContextsKnown() ? "required CI" : "CI";
    if (offending && truncated) {
      sb.append(
              "No new issues found in the reviewed portion of this PR, but it cannot be approved: ")
          .append(ciPhrase)
          .append(
              " is not confirmed green, and the diff was too large to review in full (a partial review).\n\n");
    } else if (offending) {
      sb.append("No new issues found in this PR, but the review cannot be approved until ")
          .append(ciPhrase)
          .append(" is confirmed green.\n\n");
    } else if (truncated) {
      sb.append(
          "No new issues found in the reviewed portion of this PR, but it cannot be approved: the CI status could not be read, and the diff was too large to review in full (a partial review).\n\n");
    } else {
      sb.append(
          "No new issues found in this PR, but the CI status could not be read, so the review cannot be approved until it can be confirmed.\n\n");
    }
  }

  private static void appendCiChecks(StringBuilder sb, ReviewResult result) {
    appendOffendingCiChecks(sb, result);
    appendUnreadableCiStatus(sb, result);
  }

  private static void appendOffendingCiChecks(StringBuilder sb, ReviewResult result) {
    if (result.offendingCiChecks().isEmpty()) {
      return;
    }
    if (result.requiredContextsKnown()) {
      sb.append("### ⚠️ Required CI Checks Status\n");
      sb.append("Some required checks are still pending or have failed:\n\n");
    } else {
      sb.append("### ⚠️ CI Checks Status\n");
      sb.append("Some checks are still pending or have failed:\n\n");
    }
    sb.append("| Check | Type | Status | Detail |\n");
    sb.append("|-------|------|--------|--------|\n");
    for (var check : result.offendingCiChecks()) {
      String statusEmoji = check.isFailing() ? "❌ Failed" : "⏳ Pending";
      String detail = check.conclusion() != null ? check.conclusion() : "-";
      sb.append("| **")
          .append(MarkdownSafe.tableCell(check.name()))
          .append("** | ")
          .append(MarkdownSafe.tableCell(check.type()))
          .append(" | ")
          .append(statusEmoji)
          .append(" | ")
          .append(MarkdownSafe.tableCell(detail))
          .append(" |\n");
    }
    sb.append("\n");
  }

  private static void appendUnreadableCiStatus(StringBuilder sb, ReviewResult result) {
    if (!result.ciUnreadable()) {
      return;
    }
    sb.append("### ⚠️ CI Status Unavailable\n");
    if (result.reviewState() == ReviewState.APPROVE) {
      sb.append(
          """
          The CI status could not be read from GitHub. Approval was still posted because CI \
          gating is not strict — verify CI separately if needed.

          """);
    } else {
      sb.append(
          """
          The CI status could not be read from GitHub, so approval is held until it can be \
          confirmed.

          """);
    }
  }

  /** A clean review celebrates only when nothing from earlier rounds is still unresolved. */
  private static boolean hasNoUnresolvedPrevious(ReviewResult result) {
    // The record constructor guarantees a non-null status list
    return result.previousStatuses().stream()
        .noneMatch(s -> "unresolved".equalsIgnoreCase(s.status()));
  }

  private static void appendPrPurpose(StringBuilder sb, ReviewResponse.Summary aiSummary) {
    if (aiSummary == null || aiSummary.prPurpose() == null || aiSummary.prPurpose().isBlank()) {
      return;
    }
    sb.append("### What this PR does\n");
    sb.append(MarkdownSafe.inline(aiSummary.prPurpose())).append("\n\n");
  }

  /** The model's non-blank description gaps; empty when there is no summary to read them from. */
  private static List<String> descriptionGaps(ReviewResponse.Summary aiSummary) {
    if (aiSummary == null) {
      return List.of();
    }
    // List.copyOf in the Summary constructor guarantees no null elements
    return aiSummary.descriptionGaps().stream().filter(g -> !g.isBlank()).toList();
  }

  /**
   * {@code gaps} less every gap that credits its claim to a review pass or a finding ({@link
   * DescriptionGapScreen#creditsReview}) while none of {@code findings}, the pull request's open
   * set, states it (#1127). On #1124 a bullet said "a review pass flags" a claim no posted finding
   * made, and an approving re-review of the same commit kept it. The next round carries only what
   * is rendered, so a gap dropped here is not carried either.
   */
  static List<String> backedByFindings(List<String> gaps, List<Finding> findings) {
    // The findings' claims are built once, on the first credited gap, for every credited gap.
    List<SummarySurfaceDeduplicator.Claim> claims = null;
    var kept = new ArrayList<String>(gaps.size());
    for (var gap : gaps) {
      if (DescriptionGapScreen.creditsReview(gap)) {
        if (claims == null) {
          claims = SummarySurfaceDeduplicator.claimsOf(findings);
        }
        if (!SummarySurfaceDeduplicator.restatesAny(gap, claims)) {
          continue;
        }
      }
      kept.add(gap);
    }
    return kept.size() == gaps.size() ? gaps : kept;
  }

  /**
   * Renders the Description vs. Implementation section when there is something to list, and omits
   * it when the check ran and found no mismatch (#867).
   *
   * <p>It lists the gaps that survived, then names every finding that reports a mismatch with the
   * description or a linked issue ({@link #descriptionFindings}): the findings #588 collapsed a gap
   * onto, and any other finding saying the change contradicts the description. Before #964 a
   * section whose every gap had collapsed said only that each mismatch "is reported as a finding
   * below", naming none, sometimes over a finding that was not about the description; and a
   * description finding the summary call gave no gap for left the section out entirely, even while
   * that finding was open from an earlier round. Naming the finding keeps the section for as long
   * as the finding is open, because the findings rendered are the pull request's open set (#917).
   *
   * <p>#637 made the clean state explicit because an absent section could mean either "checked,
   * matched" or "never checked". That ambiguity no longer needs a line on every clean review: the
   * check is part of the summary call, so a summary that came back at all means it ran. The only
   * state in which it did not is {@code aiSummary == null}, the counts-only shape: no "What this PR
   * does" paragraph, and every walkthrough row reading "no model summary". That shape is visible on
   * its own, and the degraded paths into it also say why — a summary degradation banner (cut at the
   * length cap, skipped at the token ceiling, failed its retries) or the response-cut coverage note
   * of a salvaged single-call review. So a reader tells the two apart from the rest of the summary
   * rather than from this section: model prose present means the check ran, and no section then
   * means it matched. The README states that rule once instead of every clean review restating it.
   *
   * <p>A PR with an empty body also lands in the omitted state, since the model reports no gaps for
   * a description it never received and the renderer cannot tell the two apart from the response
   * alone. The old clean line was vacuous there; omitting it is the better outcome.
   *
   * <p>The gaps rendered here include the ones an earlier round listed and nothing has resolved
   * since ({@link DescriptionGapCarryover}, #923).
   */
  private static void appendDescriptionGaps(
      StringBuilder sb, List<String> gaps, List<Finding> findings) {
    if (gaps.isEmpty() && findings.isEmpty()) {
      return;
    }
    sb.append(GAPS_HEADING).append("\n");
    var fromIssue = findings.stream().anyMatch(PrSummaryGenerator::mentionsLinkedIssue);
    sb.append(fromIssue ? GAPS_INTRO_WITH_ISSUE : gapsIntro(gaps));
    for (String gap : gaps) {
      // One line per gap: a gap's own line break could otherwise end the section early, and the
      // next round reads these bullets back as the gaps it carries (#923).
      sb.append("- ").append(MarkdownSafe.oneLine(gap)).append("\n");
    }
    for (Finding f : findings) {
      sb.append("- ").append(findingBullet(f)).append("\n");
    }
    sb.append("\n");
  }

  /**
   * The linked-issue criteria the description shows met by code the base branch holds (#1148), each
   * with what it cites. Under its own heading, after Description vs. Implementation: the next round
   * reads that section's bullets back as the gaps it carries, and these are not gaps.
   */
  private static void appendMetByExistingCode(StringBuilder sb, List<BaseCodeCitations.Met> met) {
    if (met.isEmpty()) {
      return;
    }
    sb.append(MET_HEADING).append("\n").append(MET_INTRO);
    for (var criterion : met) {
      var cited =
          criterion.citations().stream()
              .map(c -> "`" + MarkdownSafe.oneLine(c) + "`")
              .collect(Collectors.joining(", "));
      sb.append("- Linked issue")
          .append(criterion.issues().isEmpty() ? "" : " " + criterion.issues())
          .append(": ")
          .append(MarkdownSafe.oneLine(criterion.criterion()))
          .append(" — cited: ")
          .append(cited)
          .append("\n");
    }
    sb.append("\n");
  }

  /**
   * The bullet naming {@code finding} under Description vs. Implementation, without the list
   * marker: what {@link DescriptionSources#previouslyNamed} holds for the previous round.
   */
  static String findingBullet(Finding finding) {
    return GAP_FINDING_PREFIX
        + MarkdownSafe.inline(finding.title())
        + " (`"
        + MarkdownSafe.inlineCode(finding.file())
        + ":"
        + finding.line()
        + "`)";
  }

  /**
   * The findings the Description vs. Implementation section names, once each: the ones a gap
   * collapsed onto, in the gaps' order, then those the previous round named on the same head
   * ({@link DescriptionSources#previouslyNamed}, #1074), then every other finding whose title or
   * description says it reports a mismatch with the PR description or a linked issue ({@link
   * #DESCRIPTION_CLAIMS}), in the order the summary lists findings. Of those, only the findings
   * that quote or paraphrase the PR text or the linked issue are named (#1074): a finding about an
   * SQL injection or a Dockerfile path is not a description gap however the section came to list
   * it.
   */
  static List<Finding> descriptionFindings(
      List<Finding> collapsedOnto, List<Finding> findings, DescriptionSources sources) {
    var named = new LinkedHashSet<Finding>(collapsedOnto);
    findings.stream()
        .filter(f -> sources.previouslyNamed().contains(findingBullet(f)))
        .forEach(named::add);
    findings.stream().filter(PrSummaryGenerator::reportsDescriptionMismatch).forEach(named::add);
    return named.stream().filter(sources::echoedBy).toList();
  }

  /** Whether the finding's title or its description, on its own, holds a description claim. */
  private static boolean reportsDescriptionMismatch(Finding finding) {
    return findingTexts(finding)
        .anyMatch(text -> DESCRIPTION_CLAIMS.stream().anyMatch(p -> p.matcher(text).find()));
  }

  /** Whether the finding's title or its description names a linked issue. */
  private static boolean mentionsLinkedIssue(Finding finding) {
    return findingTexts(finding).anyMatch(text -> LINKED_ISSUE_MENTION.matcher(text).find());
  }

  /** The finding's title and description, each as its own text; a {@code null} one is left out. */
  private static Stream<String> findingTexts(Finding finding) {
    return Stream.of(finding.title(), finding.description()).filter(Objects::nonNull);
  }

  /** The intro line when a linked issue is among what the section lists. */
  static final String GAPS_INTRO_WITH_ISSUE =
      "The PR description or its linked issue does not fully match the change:\n";

  /** The prefix the summary call gives an unmet acceptance criterion of a linked issue (#58). */
  static final String LINKED_ISSUE_GAP_PREFIX = "linked issue #";

  /**
   * The line introducing the gap list: it names the linked issue as well as the description when
   * any gap is an unmet acceptance criterion (#58), so the section does not blame the PR body for
   * scope the issue asked for.
   */
  static String gapsIntro(List<String> gaps) {
    var fromIssue =
        gaps.stream()
            .anyMatch(g -> g.strip().toLowerCase(Locale.ROOT).startsWith(LINKED_ISSUE_GAP_PREFIX));
    return fromIssue
        ? GAPS_INTRO_WITH_ISSUE
        : "The PR description does not fully match the change:\n";
  }

  /**
   * Renders the file-by-file walkthrough as a table of (file, change type, one-line summary). The
   * change type comes from the diff (authoritative); the summary is the model's per-file note,
   * matched by path. Files without a model summary still appear, so the table always mirrors the
   * reviewable change set. Bounded to {@link #MAX_FILE_ROWS} rows to respect the comment-size
   * budget; which files take those rows is {@link #walkthroughRows}'s call.
   *
   * <p>The trailing "…and N more file(s)" rollup is measured against {@code totalFilesChanged}
   * (GitHub's authoritative total), not the reviewable-row count, so it also accounts for files the
   * ignore-glob dropped from the table — matching the "Changes Overview" total above it.
   */
  private static void appendChangedFiles(
      StringBuilder sb,
      int totalFilesChanged,
      List<ChangedFile> changedFiles,
      Map<String, String> summaryByPath) {
    if (changedFiles == null || changedFiles.isEmpty()) {
      return;
    }
    sb.append("### Changed Files\n");
    sb.append("| File | Change | Summary |\n");
    sb.append("|------|--------|---------|\n");
    for (ChangedFile file : walkthroughRows(changedFiles, summaryByPath)) {
      String summary = summaryByPath.getOrDefault(file.path(), "");
      sb.append("| `")
          .append(MarkdownSafe.tableCell(file.path()))
          .append("` | ")
          .append(changeTypeLabel(file.changeType()))
          .append(" | ")
          .append(summaryCell(file, summary))
          .append(" |\n");
    }
    int rowsShown = Math.min(changedFiles.size(), MAX_FILE_ROWS);
    int overflow = totalFilesChanged - rowsShown;
    if (overflow > 0) {
      sb.append("\n_…and ").append(overflow).append(" more file(s)._\n");
    }
    sb.append("\n");
  }

  /**
   * The changed files that get a row: those the model summarized first, then the rest, each group
   * in the order the diff lists it, cut at {@link #MAX_FILE_ROWS}.
   *
   * <p>The rows used to be the first {@code MAX_FILE_ROWS} files in GitHub's order, which is
   * alphabetical, while the prompt asks the model for the {@code MAX_FILE_SUMMARIES} files it
   * judges most impactful. Making the two caps equal (#536) guarantees every row can carry a
   * summary only while the change set fits in the table; on a larger PR the two selections were
   * made independently and intersected, and the overlap was whatever the alphabet granted — one
   * file out of twenty on the reported 148-file PR, with the summarized files hidden behind the
   * rollup note (#804). Selecting by summary first spends the rows on the files that have something
   * to say, and the remaining rows fill from the change set so a small PR's table still lists every
   * file.
   *
   * <p>Within each group the diff's order is kept rather than the model's. The table's identity is
   * the reviewable change set, in the order GitHub's Files tab shows it and the summary prompt's
   * overview lists it (#547); the model's ordering is a hint about impact that the same prompt has
   * to hedge, and the notes reach this method as a map with no order left in it. Only a changed
   * file can be a row, so a summary naming a path that is not in the diff — a file the PR does not
   * touch, or a directory such as {@code billing/} standing in for the files under it — creates no
   * row and lends no summary to any other.
   */
  private static List<ChangedFile> walkthroughRows(
      List<ChangedFile> changedFiles, Map<String, String> summaryByPath) {
    var summarized = changedFiles.stream().filter(f -> hasModelSummary(f, summaryByPath));
    var rest = changedFiles.stream().filter(f -> !hasModelSummary(f, summaryByPath));
    return Stream.concat(summarized, rest).limit(MAX_FILE_ROWS).toList();
  }

  /** True when the model returned a non-blank note for this file's exact path. */
  private static boolean hasModelSummary(ChangedFile file, Map<String, String> summaryByPath) {
    return !summaryByPath.getOrDefault(file.path(), "").isBlank();
  }

  /**
   * The walkthrough's Summary cell. The model's note wins whenever it supplied one; otherwise the
   * row states why it has none — a pure rename was never sent to the model, and any other row
   * simply got no note back. No cell is ever left as a bare dash, which said nothing about which of
   * the two it was (#547).
   */
  private static String summaryCell(ChangedFile file, String summary) {
    if (!summary.isBlank()) {
      return MarkdownSafe.tableCell(summary.strip());
    }
    return file.pureRename() ? PURE_RENAME_SUMMARY : NO_MODEL_SUMMARY;
  }

  /** Indexes the model's per-file notes by path; a duplicate path keeps the first note. */
  private static Map<String, String> summariesByPath(ReviewResponse.Summary aiSummary) {
    if (aiSummary == null) {
      return Map.of();
    }
    // List.copyOf in the Summary constructor guarantees no null elements.
    return aiSummary.fileSummaries().stream()
        .filter(fs -> fs.path() != null && !fs.path().isBlank() && fs.summary() != null)
        .collect(
            Collectors.toMap(
                fs -> fs.path().strip(),
                ReviewResponse.FileSummary::summary,
                (first, dup) -> first));
  }

  /** Maps a GitHub file status to a display label, falling back to the raw value when unknown. */
  private static String changeTypeLabel(String status) {
    if (status == null || status.isBlank()) {
      return "Changed";
    }
    return switch (status.toLowerCase(Locale.ROOT)) {
      case "added" -> "Added";
      case "removed", "deleted" -> "Removed";
      case "renamed" -> "Renamed";
      case "copied" -> "Copied";
      case "changed", "modified" -> "Modified";
      default -> MarkdownSafe.tableCell(status);
    };
  }

  /**
   * Renders the optional Mermaid control-flow diagram as a collapsible block. The model supplies
   * raw Mermaid source (no fences); this validates it is a real diagram, neutralizes any stray code
   * fences so it cannot break out of the block, and drops anything oversized or unrecognized.
   */
  private void appendWalkthroughDiagram(StringBuilder sb, ReviewResponse.Summary aiSummary) {
    if (!diagramEnabled || aiSummary == null) {
      return;
    }
    String diagram = sanitizeDiagram(aiSummary.walkthroughDiagram());
    if (diagram == null) {
      return;
    }
    sb.append("### Control-Flow Diagram\n");
    sb.append("<details>\n");
    sb.append("<summary>🔀 Show diagram</summary>\n\n");
    sb.append("```mermaid\n");
    sb.append(diagram).append("\n");
    sb.append("```\n\n");
    sb.append("</details>\n\n");
  }

  /**
   * Returns render-ready Mermaid source, or {@code null} when there is nothing safe to render.
   * Strips any backtick fences the model may have wrapped around the source (which would otherwise
   * let it escape the ```mermaid block), then accepts the result only if it opens with a known
   * Mermaid diagram keyword and stays within the size bound.
   */
  private static String sanitizeDiagram(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    // Mermaid source never contains a backtick; dropping them all unwraps an accidental
    // ```mermaid fence and keeps a stray ``` from closing our fence early.
    String cleaned = raw.replace("`", "").strip();
    // An unwrapped fence leaves a bare "mermaid" language tag as the first line; drop it.
    if (cleaned.regionMatches(true, 0, "mermaid", 0, "mermaid".length())) {
      int firstBreak = cleaned.indexOf('\n');
      cleaned = firstBreak < 0 ? "" : cleaned.substring(firstBreak + 1).strip();
    }
    if (cleaned.isEmpty() || cleaned.length() > MAX_DIAGRAM_CHARS) {
      return null;
    }
    String firstLine = cleaned.lines().findFirst().orElse("").strip().toLowerCase(Locale.ROOT);
    boolean recognized = MERMAID_PREFIXES.stream().anyMatch(firstLine::startsWith);
    if (!recognized) {
      return null;
    }
    if (firstLine.startsWith("sequencediagram") && hasBracketLabeledParticipant(cleaned)) {
      return null;
    }
    if ((firstLine.startsWith("flowchart") || firstLine.startsWith("graph"))
        && !flowchartParses(cleaned)) {
      return null;
    }
    return cleaned;
  }

  /** A double-quoted label, removed before the syntax checks. */
  private static final Pattern QUOTED_LABEL = Pattern.compile("\"[^\"\\n]*+\"");

  /** A link ending its text: {@code -->}, {@code ==>} or {@code -.->}, then only blanks. */
  private static final Pattern LINK_AT_END = Pattern.compile("(?:--|==|\\.-)>\\s*+$");

  /**
   * What a closed edge label is followed by: one node id, then the end, a link, an {@code &}, a
   * statement end or the node's shape. Two words ({@code y C}) are not a node.
   */
  private static final Pattern NODE_AFTER_LABEL =
      Pattern.compile("^\\s*+\\w++(?:\\s*+$|\\s*+[-=.<&;]|[\\[({>])");

  /**
   * {@code line} with its double-quoted labels and its edge labels between pipes each replaced by
   * {@code L}, for the syntax checks. A pipe whose text up to the next pipe ends in a link ({@code
   * A -->|x B -->|y C}) opens the next label rather than closing this one, so it is left in place
   * and fails the check: two labels left open do not pair across the arrow between them. Label text
   * may still hold a link ({@code |calls a --> b|}, {@code |goes -->|} before a node), {@code --}
   * or {@code ->}.
   */
  private static String withoutLabels(String line) {
    var text = QUOTED_LABEL.matcher(line).replaceAll("L");
    var bare = new StringBuilder();
    var from = 0;
    var open = text.indexOf('|');
    while (open >= 0) {
      var close = text.indexOf('|', open + 1);
      if (close < 0 || opensTheNextLabel(text, open, close)) {
        break;
      }
      bare.append(text, from, open).append('L');
      from = close + 1;
      open = text.indexOf('|', from);
    }
    return bare.append(text, from, text.length()).toString();
  }

  /**
   * Whether the pipe at {@code close} opens the next label instead of closing the one at {@code
   * open}: the text between them ends in a link ({@code A -->|x B -->|y C}) and no single node
   * follows. A closed label may end in a link itself ({@code A -->|goes -->| B}).
   */
  private static boolean opensTheNextLabel(String text, int open, int close) {
    return LINK_AT_END.matcher(text.substring(open + 1, close)).find()
        && !NODE_AFTER_LABEL.matcher(text.substring(close + 1)).find();
  }

  /**
   * An arrowhead that ends no link: {@code ->} not preceded by a dash or by the {@code -.} of a
   * dotted link, which covers the sequence diagram's {@code ->>} and a stray-dot {@code .->} as
   * well; a doubled arrowhead ({@code A-->>B}) is refused beside it. Flowchart arrows are {@code
   * -->}, {@code -.->}, {@code ==>} and the like, so any other {@code ->} is a parse error that
   * drops the whole diagram.
   */
  private static final Pattern ONE_DASH_ARROW = Pattern.compile("(?<!-|-\\.)->");

  /**
   * A flowchart's header: its type, then one of Mermaid's fixed directions or none, then the end of
   * the line or a semicolon before its first statement. A first line that does not open this way
   * ({@code flowchart XY}) does not parse.
   */
  private static final Pattern FLOWCHART_HEADER =
      Pattern.compile("^(?:flowchart|graph)(?:\\s++(?:TB|TD|BT|RL|LR))?+\\s*+(?:;|$)");

  /** Flowchart statements whose syntax allows commas: class lists, styles, link styles, clicks. */
  private static final Pattern COMMA_STATEMENT =
      Pattern.compile("(?i)^(?:class|classdef|style|linkstyle|click)\\b");

  /**
   * Whether a flowchart's body passes the checks GitHub's Mermaid parser has failed on in posted
   * summaries (#952): a one-dash arrow ({@code A -> B}, or {@code ->>} borrowed from a sequence
   * diagram), a stray comma after a node ({@code PE["pending"],}), an odd number of double quotes
   * on a line, an edge label left open ({@code A -->|x B}), or a node shape that does not parse
   * once quoted labels are removed ({@link #shapesParse}). A diagram that would fail to render is
   * omitted rather than posted as a broken block.
   */
  static boolean flowchartParses(String diagram) {
    // The header line's own statements count too: "flowchart TD; A -> B" is one line.
    var lines = new ArrayList<>(diagram.lines().map(String::strip).toList());
    var header = FLOWCHART_HEADER.matcher(lines.getFirst());
    if (!header.find()) {
      return false;
    }
    lines.set(0, lines.getFirst().substring(header.end()));
    for (var line : lines) {
      if (line.isEmpty() || line.startsWith("%%")) {
        continue;
      }
      if (line.chars().filter(c -> c == '"').count() % 2 != 0) {
        return false;
      }
      var bare = withoutLabels(line);
      // Statements joined by semicolons are judged one by one ("A --> B; class A,B hot").
      for (var statement : bare.split(";")) {
        if (!statementParses(statement.strip())) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Whether one flowchart statement, its labels removed, passes the checks of {@link
   * #flowchartParses}.
   */
  private static boolean statementParses(String statement) {
    return !ONE_DASH_ARROW.matcher(statement).find()
        && !statement.contains(">>")
        && (COMMA_STATEMENT.matcher(statement).find() || !hasStrayComma(statement))
        && statement.indexOf('|') < 0
        && shapesParse(statement);
  }

  /**
   * Whether every node shape on {@code line} parses once labels are removed: its brackets close in
   * order, a bracket opens inside another only straight after it (the {@code [(db)]}, {@code
   * ([stadium])} and {@code {{hex}}} shapes), and no unquoted label holds a colon, an ampersand, a
   * semicolon or a slash. GitHub's parser rejects {@code A(f(x))} and {@code A(t: 3)}, which
   * balance. A slash or backslash straight inside a square bracket is the parallelogram and
   * trapezoid shapes' edge ({@code [/in/]}, {@code [/base\]}), not label text. The asymmetric shape
   * {@code A>flag]} opens with a {@code >} straight after the node id and closes with {@code ]}; an
   * arrow's {@code >} follows a dash, an equals sign or a dot instead.
   */
  private static boolean shapesParse(String line) {
    var open = new ArrayDeque<Character>();
    var previous = ' ';
    for (var c : SLANTED_EDGE.matcher(line).replaceAll("$1$2").toCharArray()) {
      if (!acceptsNext(open, previous, c)) {
        return false;
      }
      previous = c;
    }
    return open.isEmpty();
  }

  /**
   * Whether {@code c}, after {@code previous}, keeps the shapes on its line parsing; pushes or pops
   * {@code open} for a bracket.
   */
  private static boolean acceptsNext(ArrayDeque<Character> open, char previous, char c) {
    if (c == '>' && open.isEmpty() && (Character.isLetterOrDigit(previous) || previous == '_')) {
      // The asymmetric shape's opener: it closes like a square bracket.
      open.push('[');
      return true;
    }
    if (OPENING_BRACKETS.indexOf(c) >= 0) {
      var nestedMidLabel = !open.isEmpty() && OPENING_BRACKETS.indexOf(previous) < 0;
      open.push(c);
      return !nestedMidLabel;
    }
    var closing = CLOSING_BRACKETS.indexOf(c);
    if (closing >= 0) {
      return !open.isEmpty() && open.pop() == OPENING_BRACKETS.charAt(closing);
    }
    return open.isEmpty() || LABEL_BREAKERS.indexOf(c) < 0;
  }

  /**
   * Whether {@code line} has a comma outside every node shape: after a node ({@code
   * PE["pending"],}), between two bare node ids ({@code A --> B, C --> D}) or at its end. Only the
   * statements of {@code COMMA_STATEMENT} take one there; a comma inside an unquoted label is the
   * label's.
   */
  private static boolean hasStrayComma(String line) {
    var depth = 0;
    for (var c : line.toCharArray()) {
      if (OPENING_BRACKETS.indexOf(c) >= 0) {
        depth++;
      } else if (CLOSING_BRACKETS.indexOf(c) >= 0) {
        depth--;
      } else if (c == ',' && depth == 0) {
        return true;
      }
    }
    return false;
  }

  /** Characters GitHub's parser rejects inside an unquoted label. */
  private static final String LABEL_BREAKERS = ":&;/";

  /**
   * A slanted shape's edge: a slash or backslash right after an opening square bracket or right
   * before a closing one (the bracket is group 1 or 2), dropped before the label check.
   */
  private static final Pattern SLANTED_EDGE = Pattern.compile("(\\[)[/\\\\]|[/\\\\](])");

  private static final String OPENING_BRACKETS = "([{";

  /** The closing brackets, each at the position of its opening one in {@link #OPENING_BRACKETS}. */
  private static final String CLOSING_BRACKETS = ")]}";

  /**
   * True when any {@code participant}/{@code actor} declaration in the source carries a flowchart
   * bracket label — {@code participant O["Orchestrator"]} rather than the valid {@code participant
   * O as Orchestrator}. The stray {@code [} or &#123; is the flowchart-quoting rule leaking into a
   * sequence diagram, which GitHub cannot render.
   */
  private static boolean hasBracketLabeledParticipant(String diagram) {
    return diagram
        .lines()
        .map(line -> line.strip().toLowerCase(Locale.ROOT))
        .filter(line -> line.startsWith("participant ") || line.startsWith("actor "))
        .anyMatch(PrSummaryGenerator::hasBracketInAliasPosition);
  }

  /**
   * True when the alias of a participant/actor declaration carries a bracket — the leaked flowchart
   * shape {@code participant O["Orchestrator"]}, which never uses the {@code as} form. A valid
   * {@code participant O as Display} may legitimately carry brackets in its display name (e.g.
   * {@code as [User]}), so only the alias — the text before {@code " as "}, or the whole line when
   * there is no {@code " as "} — is inspected, to avoid dropping a renderable diagram.
   */
  private static boolean hasBracketInAliasPosition(String participantLine) {
    int asIndex = participantLine.indexOf(" as ");
    String alias = asIndex >= 0 ? participantLine.substring(0, asIndex) : participantLine;
    return alias.indexOf('[') >= 0 || alias.indexOf('{') >= 0;
  }

  /** Renders a signed line count, avoiding the awkward "+0"/"-0". */
  private static String signed(char sign, int count) {
    return count == 0 ? "0" : sign + Integer.toString(count);
  }
}
