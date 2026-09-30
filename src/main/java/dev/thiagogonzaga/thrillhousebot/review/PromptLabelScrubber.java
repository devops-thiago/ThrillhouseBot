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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes the review prompt's own vocabulary from text the model wrote before it is posted (#918).
 * The prompt organizes its guidance into numbered and named blocks, and a model citing them writes
 * "Dimension 7 artifact-name mismatch", "Comment-contradiction (dimension 4)" or "Parsing-rule
 * probe (heuristic section)" into a finding a maintainer reads — a reference to instructions the
 * maintainer has never seen. The prompt now asks the model not to (see {@code
 * PrReviewPrompts.CORE_FINDING_FIELDS_AND_SEVERITY}); this is the deterministic guard behind that
 * request.
 *
 * <p>Every rule matches only a shape the prompt itself produces, because a false positive rewrites
 * a maintainer-facing sentence about their code. So the word "dimension" on its own is never
 * touched, nor a number after it that reads as a tensor or array axis ("dimension 1 of the
 * output"); a numbered label is removed only as a parenthetical, as the label that opens a
 * sentence, or after the few verbs that cite it ("this is dimension 5", "under dimension 10"), and
 * only for the numbers the prompt used, 1 through 10. Code — a fenced block, an inline code span —
 * and HTML comments (the bot's own markers) are left exactly as written; the one exception is the
 * prompt's example locator, which is rewritten to a placeholder wherever it appears unless the pull
 * request really has a file at that path.
 */
public final class PromptLabelScrubber {

  /** The example locator the bot's instructions used, and its placeholder-shaped matcher. */
  private static final String EXAMPLE_PATH = "path/to/File.java";

  private static final Pattern EXAMPLE_LOCATOR =
      Pattern.compile("(?<![\\w/.-])" + Pattern.quote(EXAMPLE_PATH) + "(:\\d+)?(?![\\w/])");

  /** Code and markers the prose rules must not touch: fenced blocks, code spans, HTML comments. */
  private static final Pattern PROTECTED =
      Pattern.compile("(?s)```.*?(?:```|\\z)|~~~.*?(?:~~~|\\z)|`[^`\\n]*`|<!--.*?(?:-->|\\z)");

  private static final String NUMBER = "(?:10|[1-9])(?!\\d)";

  private static final String NUMBERS = NUMBER + "(?:\\s*(?:,|/|&|and|or)\\s*" + NUMBER + ")*";

  /** The block names the prompt prints as headings, cited in parentheses. */
  private static final String HEADING_NAMES =
      "FUNCTIONAL CORRECTNESS|SECURITY|REGRESSIONS|COMMENT CONTRADICTS CODE"
          + "|CODE QUALITY AND ALGORITHMIC COMPLEXITY|PAGINATION / TRUNCATION"
          + "|CONFIG / IaC CORRECTNESS|MOCK FIDELITY|PRODUCER → CONSUMER CONTRACT"
          + "|CONFIG KEY DOCUMENTATION COMPLETENESS";

  private static final String LABEL =
      "\\(\\s*(?:(?:see|per|under)\\s+)?(?:(?:review\\s+)?dimensions?\\s+"
          + NUMBERS
          + "|(?:the\\s+)?heuristic(?:\\s+failure[- ]mode)?(?:\\s+characterization)?\\s+"
          + "section|(?:"
          + HEADING_NAMES
          + "))\\s*\\)";

  /** A label opening a line, "(dimension 9) The trace ...": the space after it goes too. */
  private static final Pattern LEADING_PARENTHETICAL =
      Pattern.compile(
          "(?m)^([ \\t]*(?:[-*>][ \\t]+)?)" + LABEL + "[ \\t]*", Pattern.CASE_INSENSITIVE);

  /** "(dimension 4)", "(dimensions 4 and 5)", "(heuristic section)", "(MOCK FIDELITY)". */
  private static final Pattern PARENTHETICAL =
      Pattern.compile("[ \\t]*" + LABEL, Pattern.CASE_INSENSITIVE);

  /**
   * "Dimension 7 artifact-name mismatch." opening a text, a line, a sentence, a clause after a
   * colon or dash, or a table cell: the label goes and the next word is capitalized. A following
   * function word ("of", "is", "size") reads as an axis, not a label, and is left alone.
   */
  private static final Pattern LEADING_LABEL =
      Pattern.compile(
          "(^|[.!?:;—–|][ \\t]+|\\n[ \\t]*(?:[-*>][ \\t]+)?)(?:review\\s+)?dimension\\s+"
              + NUMBER
              + "(?:\\s*[:,—–-]\\s*|\\s+)"
              + "(?!(?:of|is|was|has|in|at|to|and|or|for|with|along|size|index|axis)\\b)"
              + "(\\p{L})",
          Pattern.CASE_INSENSITIVE);

  /** "This is dimension 5, class (c)" — the verb stays, the label goes. */
  private static final Pattern CITED_AS =
      Pattern.compile(
          "\\b(this is|that is|it is|falls under)\\s+dimension\\s+"
              + NUMBER
              + "(?!\\s+of\\b)\\s*,?\\s+",
          Pattern.CASE_INSENSITIVE);

  /** "a documentation gap under dimension 10." — the whole trailing citation goes. */
  private static final Pattern CITED_UNDER =
      Pattern.compile(
          "\\s*,?\\s+(?:under|per)\\s+(?:review\\s+)?dimensions?\\s+" + NUMBERS + "(?!\\s+of\\b)",
          Pattern.CASE_INSENSITIVE);

  /** "but, as class guidance notes, does not pin images" — an aside quoting the prompt. */
  private static final Pattern GUIDANCE_ASIDE =
      Pattern.compile(
          ",\\s*as\\s+(?:the\\s+)?(?:anchored\\s+)?(?:infrastructure\\s+)?(?:class\\s+)?guidance"
              + "\\s+(?:notes|says|states),\\s*",
          Pattern.CASE_INSENSITIVE);

  /** "acceptance criterion, which this PR must quote: ..." — the prompt's rule, restated. */
  private static final Pattern RESTATED_RULE =
      Pattern.compile(
          ",?\\s*which\\s+(?:this|the)\\s+(?:PR|pull request|finding|description|change)"
              + "\\s+must\\s+quote(?=\\s*[:.;,]|\\s*$)",
          Pattern.CASE_INSENSITIVE);

  private PromptLabelScrubber() {}

  /** {@link #scrub(String, Collection)} for text that names no file of the pull request. */
  public static String scrub(String text) {
    return scrub(text, Set.of());
  }

  /**
   * The text without the prompt's labels, section names, restated rules and example locators.
   * Idempotent, and the identity on text that carries none of them.
   *
   * @param realPaths files the pull request really has; an example locator equal to one of them is
   *     a real path and is kept
   */
  public static String scrub(String text, Collection<String> realPaths) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    var out = new StringBuilder(text.length());
    var code = PROTECTED.matcher(text);
    int at = 0;
    while (code.find()) {
      out.append(scrubProse(text.substring(at, code.start())));
      out.append(code.group());
      at = code.end();
    }
    out.append(scrubProse(text.substring(at)));
    return replaceExamplePaths(out.toString(), realPaths);
  }

  /** A finding with its title and description scrubbed; the same instance when neither changed. */
  public static Finding scrub(Finding finding, Collection<String> realPaths) {
    var title = scrub(finding.title(), realPaths);
    var description = scrub(finding.description(), realPaths);
    if (Objects.equals(title, finding.title())
        && Objects.equals(description, finding.description())) {
      return finding;
    }
    return new Finding(
        finding.risk(),
        finding.confidence(),
        finding.file(),
        finding.line(),
        title,
        description,
        finding.suggestionOld(),
        finding.suggestionNew());
  }

  /** The findings scrubbed, against the files they are filed on as the real paths. */
  public static List<Finding> scrubFindings(List<Finding> findings) {
    var paths = new HashSet<String>();
    findings.forEach(f -> addPath(paths, f.file()));
    return findings.stream().map(f -> scrub(f, paths)).toList();
  }

  /**
   * A review result with its findings and its rendered summary scrubbed — the publisher's guard for
   * whatever reached it without passing the pipeline's (a round carried over from storage written
   * before #918). The same instance when nothing changed.
   */
  public static ReviewResult scrub(ReviewResult result) {
    var findings = scrubFindings(result.findings());
    var summary = scrubMarkdown(result.summaryMarkdown(), result.findings());
    if (findings.equals(result.findings()) && Objects.equals(summary, result.summaryMarkdown())) {
      return result;
    }
    return new ReviewResult(
        findings,
        result.criticalCount(),
        result.highCount(),
        result.mediumCount(),
        result.lowCount(),
        result.highestRisk(),
        result.reviewState(),
        result.isFirstReview(),
        summary,
        result.previousStatuses(),
        result.offendingCiChecks(),
        result.omittedFiles(),
        result.ciUnreadable(),
        result.requiredContextsKnown(),
        result.truncation(),
        result.blockingWithheldByConfidence());
  }

  /** Rendered markdown scrubbed against the files {@code findings} are filed on. */
  public static String scrubMarkdown(String markdown, List<Finding> findings) {
    var paths = new HashSet<String>();
    findings.forEach(f -> addPath(paths, f.file()));
    return scrub(markdown, paths);
  }

  /**
   * A model response with every prose field a reader sees scrubbed: each finding's title and
   * description, each previous-finding note, and the summary's assessment, purpose, description
   * gaps and file summaries. Code fields — suggestions, paths, labels, the diagram — are untouched.
   * The same instance when nothing leaked.
   */
  public static ReviewResponse scrub(ReviewResponse response) {
    var paths = new HashSet<String>();
    response.findings().forEach(f -> addPath(paths, f.file()));
    var summary = response.summary();
    if (summary != null) {
      summary.fileSummaries().forEach(s -> addPath(paths, s.path()));
    }
    var findings = new ArrayList<ReviewResponse.Finding>(response.findings().size());
    for (var f : response.findings()) {
      findings.add(
          new ReviewResponse.Finding(
              f.risk(),
              f.confidence(),
              f.file(),
              f.line(),
              scrub(f.title(), paths),
              scrub(f.description(), paths),
              f.suggestionOld(),
              f.suggestionNew()));
    }
    var statuses =
        response.previousFindingsStatus().stream()
            .map(s -> new ReviewResponse.PreviousFindingStatus(s.id(), s.status(), scrub(s.note())))
            .toList();
    var scrubbed =
        new ReviewResponse(findings, statuses, summary == null ? null : scrub(summary, paths));
    return scrubbed.equals(response) ? response : scrubbed;
  }

  private static ReviewResponse.Summary scrub(ReviewResponse.Summary s, Set<String> paths) {
    var scrubbed =
        new ReviewResponse.Summary(
            s.totalFindings(),
            s.critical(),
            s.high(),
            s.medium(),
            s.low(),
            scrub(s.overallAssessment(), paths),
            scrub(s.prPurpose(), paths),
            s.descriptionGaps().stream().map(g -> scrub(g, paths)).toList(),
            s.suggestedLabels(),
            s.fileSummaries().stream()
                .map(f -> new ReviewResponse.FileSummary(f.path(), scrub(f.summary(), paths)))
                .toList(),
            s.walkthroughDiagram());
    // The same instance when nothing leaked, so a caller holding the model's summary still does.
    return scrubbed.equals(s) ? s : scrubbed;
  }

  private static void addPath(Set<String> paths, String path) {
    if (path != null) {
      paths.add(path);
    }
  }

  private static String scrubProse(String prose) {
    if (prose.isEmpty()) {
      return prose;
    }
    var s = LEADING_PARENTHETICAL.matcher(prose).replaceAll("$1");
    s = PARENTHETICAL.matcher(s).replaceAll("");
    s = capitalizeAfterLeadingLabel(s);
    s = CITED_AS.matcher(s).replaceAll("$1 ");
    s = CITED_UNDER.matcher(s).replaceAll("");
    s = GUIDANCE_ASIDE.matcher(s).replaceAll(" ");
    return RESTATED_RULE.matcher(s).replaceAll("");
  }

  private static String capitalizeAfterLeadingLabel(String s) {
    Matcher m = LEADING_LABEL.matcher(s);
    var out = new StringBuilder(s.length());
    while (m.find()) {
      m.appendReplacement(
          out, Matcher.quoteReplacement(m.group(1) + m.group(2).toUpperCase(Locale.ROOT)));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static String replaceExamplePaths(String text, Collection<String> realPaths) {
    if (!text.contains(EXAMPLE_PATH) || realPaths.contains(EXAMPLE_PATH)) {
      return text;
    }
    return EXAMPLE_LOCATOR
        .matcher(text)
        .replaceAll(m -> m.group(1) == null ? "<path>" : "<path>:<line>");
  }
}
