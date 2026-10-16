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

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Keeps the previous round's overview on a follow-up round of the same head (#944, #965).
 *
 * <p>The summary comment is edited in place every round (#868), so a round whose summary came back
 * with neither a "What this PR does" paragraph nor a single file summary rewrote a comment that had
 * both into one that had neither, every Changed Files row reading "Not summarized". On the head the
 * previous round reviewed nothing the overview describes has changed, so that round's overview and
 * file summaries (and its walkthrough diagram, the same description drawn) still hold and are kept.
 *
 * <p>Three shapes are filled:
 *
 * <ul>
 *   <li>an answer with no overview at all takes the previous round's overview, file summaries and
 *       diagram whole (#944);
 *   <li>an answer with an overview keeps it, and takes the previous round's summary for each file
 *       it has none for, and the previous diagram when it has none (#964: re-reviews dropped real
 *       file summaries row by row, and the Control-Flow Diagram, while the purpose paragraph
 *       survived);
 *   <li>a degraded round — the summary call skipped at the token ceiling or the call cap, refused,
 *       cut with nothing salvaged, or failed — has no answer, and takes the previous overview whole
 *       (#965). Its banner then says the overview is carried from the previous review of the same
 *       commit and only the counts are new ({@link #carried}), instead of saying only counts are
 *       shown.
 * </ul>
 *
 * <p>The previous overview is read from the round's persisted response, not from the rendered
 * comment: it is the model's own text, before the renderer escaped it, and the comment's table is
 * already cut to the row cap. It is taken only when the head is provably the one that round
 * reviewed ({@link ReviewContextLoader.ReviewContext#previousRoundHeadUnchanged()}); on a moved
 * head the paragraph could describe code that is gone, and the degraded shape says so honestly.
 */
final class PriorOverviewCarryover {

  private PriorOverviewCarryover() {}

  /**
   * The words a purpose paragraph about the change's size is made of: "It is a feature-sized change
   * (+555 lines across 6 directories)." A sentence of these alone says nothing of what the change
   * does (#1095).
   */
  private static final Set<String> SIZE_WORDS =
      Set.of(
          "it",
          "it's",
          "is",
          "this",
          "that",
          "the",
          "a",
          "an",
          "pr",
          "pull",
          "request",
          "change",
          "changes",
          "changeset",
          "diff",
          "patch",
          "small",
          "medium",
          "large",
          "big",
          "sizable",
          "sizeable",
          "moderate",
          "moderately",
          "very",
          "fairly",
          "size",
          "sized",
          "line",
          "lines",
          "file",
          "files",
          "directory",
          "directories",
          "folder",
          "folders",
          "module",
          "modules",
          "across",
          "over",
          "in",
          "of",
          "and",
          "with",
          "touching",
          "touches",
          "spanning",
          "spans",
          "adds",
          "adding",
          "added",
          "additions",
          "deletions",
          "removes",
          "removed",
          "changed",
          "net",
          "roughly",
          "about",
          "around",
          "approximately",
          "total",
          "overall",
          "new");

  /** A word of a size sentence: letters and hyphens ("feature-sized"), or a signed number. */
  private static final Pattern SIZE_TOKEN =
      Pattern.compile("[+-]?\\d++(?:[,.]\\d++)*+|\\p{L}[\\p{L}'-]*+");

  /** A one-level parenthetical, which a size statement's figures sit in. */
  private static final Pattern PARENTHETICAL = Pattern.compile("\\([^()]*+\\)");

  /** Where a sentence's leading clause ends. */
  private static final Pattern CLAUSE_BOUNDARY =
      Pattern.compile(
          "[,;:—–]|\\b(?:that|which|and|but|so|while|whereas|to|for|because)\\b",
          Pattern.CASE_INSENSITIVE);

  /** What marks a sentence as being about size: a number, the word "size", or a "-sized" word. */
  private static final Pattern SIZE_MARK = Pattern.compile("\\d|-sized\\b|\\bsized?\\b");

  /**
   * {@code current} with the previous round's overview filled in where it has none, on the head
   * that round reviewed: whole when {@code current} is {@code null} or carries no overview, else
   * file by file and for the diagram. Returned unchanged when the head moved, when the previous
   * round had no overview, or when nothing is missing.
   */
  static ReviewResponse.Summary fill(
      ReviewResponse.Summary current, ReviewContextLoader.ReviewContext ctx) {
    if (!ctx.previousRoundHeadUnchanged()) {
      return current;
    }
    var prior = previousRoundSummary(ctx.priorAiResponses());
    if (!hasOverview(prior)) {
      return current;
    }
    if (hasOverview(current)) {
      return supplemented(current, prior);
    }
    Log.infof(
        "Summary has no overview on the head the previous round reviewed; kept that round's"
            + " overview and %d file summary(ies)",
        prior.fileSummaries().size());
    if (current == null) {
      return new ReviewResponse.Summary(
          0,
          0,
          0,
          0,
          0,
          null,
          prior.prPurpose(),
          List.of(),
          List.of(),
          prior.fileSummaries(),
          prior.walkthroughDiagram(),
          List.of());
    }
    return withOverview(
        current, prior.prPurpose(), prior.fileSummaries(), prior.walkthroughDiagram());
  }

  /**
   * {@code current} plus the previous round's summary for each file {@code current} has no
   * non-blank summary for, the previous purpose paragraph when {@code current}'s is blank or says
   * only how big the change is (the {@link OverviewScreen} may have taken every other sentence out,
   * #1095), and the previous diagram when {@code current} has none; {@code current} itself when it
   * lacks none of them.
   */
  private static ReviewResponse.Summary supplemented(
      ReviewResponse.Summary current, ReviewResponse.Summary prior) {
    // Only the current answer's real summaries stay; a blank one gives way to the earlier entry.
    var files =
        new ArrayList<>(
            current.fileSummaries().stream().filter(f -> !isBlank(f.summary())).toList());
    var covered = new CoveredPaths();
    files.forEach(f -> covered.add(f.path()));
    var earlier =
        prior.fileSummaries().stream().filter(f -> isUsable(f) && covered.add(f.path())).toList();
    files.addAll(earlier);
    var added = earlier.size();
    var keepDiagram = isBlank(current.walkthroughDiagram()) && !isBlank(prior.walkthroughDiagram());
    var keepPurpose =
        (isBlank(current.prPurpose()) || sizeOnly(current.prPurpose()))
            && !isBlank(prior.prPurpose())
            && !sizeOnly(prior.prPurpose());
    if (added == 0 && !keepDiagram && !keepPurpose) {
      return current;
    }
    Log.infof(
        "Summary on the head the previous round reviewed kept %d earlier file summary(ies), %d"
            + " earlier diagram(s) and %d earlier purpose paragraph(s)",
        added, keepDiagram ? 1 : 0, keepPurpose ? 1 : 0);
    return withOverview(
        current,
        keepPurpose ? prior.prPurpose() : current.prPurpose(),
        files,
        keepDiagram ? prior.walkthroughDiagram() : current.walkthroughDiagram());
  }

  /**
   * Whether {@code purpose} says only how big the change is: its first sentence opens with a size
   * statement, as {@link #sizeSentence} reads it, and every later sentence is one. A sentence on
   * the failing CI checks is not counted, since the summary computes it ({@link
   * CiFailureOverview}). Node ThrillhouseBot-test#278 round 2 wrote only "It is a feature-sized
   * change (+555 lines across 6 directories) that also ships the container image and CI pipeline
   * described in issue #262": what the service does was gone, and only a side note followed the
   * size (#1095).
   */
  static boolean sizeOnly(String purpose) {
    if (isBlank(purpose)) {
      return false;
    }
    var stated = CiFailureOverview.withoutCiVerdicts(purpose);
    if (isBlank(stated)) {
      return false;
    }
    var sentences = OverviewScreen.sentences(stated);
    return sizeSentence(leadingClause(sentences.getFirst()))
        && sentences.stream().skip(1).allMatch(PriorOverviewCarryover::sizeSentence);
  }

  /**
   * {@code sentence} up to its first clause boundary outside parentheses: a comma, semicolon, colon
   * or dash, or a word that opens another clause ({@code that}, {@code which}, {@code and}).
   */
  private static String leadingClause(String sentence) {
    var flat = PARENTHETICAL.matcher(sentence).replaceAll(m -> " ".repeat(m.group().length()));
    var boundary = CLAUSE_BOUNDARY.matcher(flat);
    return boundary.find() ? sentence.substring(0, boundary.start()) : sentence;
  }

  /**
   * Whether {@code sentence} is a size statement: it holds a number, the word "size" or a "-sized"
   * word, and every other word is one of {@link #SIZE_WORDS}, the "-sized" adjectives included.
   */
  private static boolean sizeSentence(String sentence) {
    if (!SIZE_MARK.matcher(sentence).find()) {
      return false;
    }
    var words = SIZE_TOKEN.matcher(sentence);
    while (words.find()) {
      var word = words.group().toLowerCase(Locale.ROOT);
      if (!Character.isDigit(word.charAt(word.length() - 1))
          && !word.endsWith("-sized")
          && !SIZE_WORDS.contains(word)) {
        return false;
      }
    }
    return true;
  }

  /**
   * The paths a file list already summarizes, matched as {@link FilePaths#same} matches them (one
   * path a suffix of the other at a directory boundary), indexed by exact path and directory suffix
   * so even distinct files sharing a name need no pairwise comparisons.
   */
  private static final class CoveredPaths {

    private final PathIndex<String> paths = new PathIndex<>();

    /** Records {@code path}; {@code false} when a path it is the same as is already recorded. */
    boolean add(String path) {
      if (paths.contains(path, "")) {
        return false;
      }
      paths.add(path, "", path);
      return true;
    }
  }

  /**
   * The summary a degraded round persists (#965): the previous round's overview on the head that
   * round reviewed, under counts taken from the findings this round keeps, so the stored summary
   * does not say zero findings over a round that has them; {@code null}, the counts-only shape,
   * when there is no overview to carry.
   */
  static ReviewResponse.Summary fillDegraded(
      ReviewContextLoader.ReviewContext ctx, List<ReviewResponse.Finding> findings) {
    var counts = new int[RiskLevel.values().length];
    findings.forEach(f -> counts[RiskLevel.fromString(f.risk()).ordinal()]++);
    var countsOnly =
        new ReviewResponse.Summary(
            findings.size(),
            counts[RiskLevel.CRITICAL.ordinal()],
            counts[RiskLevel.HIGH.ordinal()],
            counts[RiskLevel.MEDIUM.ordinal()],
            counts[RiskLevel.LOW.ordinal()],
            null,
            null,
            List.of());
    var filled = fill(countsOnly, ctx);
    return filled == countsOnly ? null : filled;
  }

  /** Whether an earlier file summary names a path and says something. */
  private static boolean isUsable(ReviewResponse.FileSummary summary) {
    return !isBlank(summary.path()) && !isBlank(summary.summary());
  }

  private static ReviewResponse.Summary withOverview(
      ReviewResponse.Summary current,
      String purpose,
      List<ReviewResponse.FileSummary> files,
      String diagram) {
    return new ReviewResponse.Summary(
        current.totalFindings(),
        current.critical(),
        current.high(),
        current.medium(),
        current.low(),
        current.overallAssessment(),
        purpose,
        current.descriptionGaps(),
        current.suggestedLabels(),
        files,
        diagram,
        current.addressedGaps());
  }

  /**
   * Whether {@code summary}'s overview is the previous round's, carried whole on the head that
   * round reviewed: the same purpose paragraph and the same file summaries. The degraded paths'
   * banner reads this to say the overview is carried rather than that only counts are shown (#965).
   */
  static boolean carried(ReviewResponse.Summary summary, ReviewContextLoader.ReviewContext ctx) {
    if (!ctx.previousRoundHeadUnchanged() || !hasOverview(summary)) {
      return false;
    }
    var prior = previousRoundSummary(ctx.priorAiResponses());
    return hasOverview(prior)
        && Objects.equals(summary.prPurpose(), prior.prPurpose())
        && summary.fileSummaries().equals(prior.fileSummaries());
  }

  private static boolean isBlank(String text) {
    return text == null || text.isBlank();
  }

  /**
   * The summary of the round the head comparison was made against: the same effective previous
   * round {@link ReviewContextLoader} takes the head from, so the overview and the head check name
   * one round. The list holds no null entries (the context copies it); a null list, which the
   * context never passes, reads as no previous round.
   */
  static ReviewResponse.Summary previousRoundSummary(List<ReviewResponse> priorResponses) {
    if (priorResponses == null) {
      return null;
    }
    var index = Math.max(FollowUpAnalyzer.effectivePreviousRoundIndex(priorResponses), 0);
    if (index >= priorResponses.size()) {
      return null;
    }
    return priorResponses.get(index).summary();
  }

  /** Whether a summary carries an overview: a purpose paragraph or at least one file summary. */
  static boolean hasOverview(ReviewResponse.Summary summary) {
    return summary != null
        && ((summary.prPurpose() != null && !summary.prPurpose().isBlank())
            || !summary.fileSummaries().isEmpty());
  }
}
