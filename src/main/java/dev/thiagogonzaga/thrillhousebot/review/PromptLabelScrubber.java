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
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
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
 * prompt's example locator, which is rewritten to a placeholder wherever it appears unless it is
 * one of the real paths the caller passes — the files the findings are filed on and the summary
 * describes, which are the paths of this pull request the text can be about.
 */
public final class PromptLabelScrubber {

  /** The example locator the bot's instructions used, and its placeholder-shaped matcher. */
  private static final String EXAMPLE_PATH = "path/to/File.java";

  private static final Pattern EXAMPLE_LOCATOR =
      Pattern.compile("(?<![\\w/.-])" + Pattern.quote(EXAMPLE_PATH) + "(:\\d+)?(?![\\w/])");

  /** A one-line parenthetical; whether its content is a label is decided by {@link #isLabel}. */
  private static final Pattern PARENTHETICAL = Pattern.compile("\\(([^()\\n]{1,80})\\)");

  /** A numbered dimension citation; whether its context makes it a label is decided in code. */
  private static final Pattern NUMBERED =
      Pattern.compile("\\b(?:review )?dimension (\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);

  /** Separates the numbers of "(dimensions 4, 5 and 8)". */
  private static final Pattern NUMBER_SEPARATOR = Pattern.compile(" ?(?:,|/|&) ?| and | or ");

  /** The block names the prompt prints as headings, lower-cased, as cited in parentheses. */
  private static final Set<String> HEADING_NAMES =
      Set.of(
          "functional correctness",
          "security",
          "regressions",
          "comment contradicts code",
          "code quality and algorithmic complexity",
          "pagination / truncation",
          "config / iac correctness",
          "mock fidelity",
          "producer → consumer contract",
          "config key documentation completeness");

  /** The heuristic block's name, as cited in parentheses. */
  private static final Set<String> SECTION_NAMES =
      Set.of(
          "heuristic section",
          "the heuristic section",
          "heuristic failure-mode section",
          "heuristic failure mode section");

  /** A word after "dimension N" that reads it as a tensor or array axis, not a label. */
  private static final Set<String> AXIS_WORDS =
      Set.of(
          "of", "is", "was", "has", "in", "at", "to", "and", "or", "for", "with", "along", "size",
          "index", "axis");

  /** Verbs that cite a label mid-sentence and stay: "This is dimension 5, class (c)". */
  private static final List<String> CITING_VERBS =
      List.of("falls under", "this is", "that is", "it is");

  /** Prepositions whose whole citation goes: "a documentation gap under dimension 10." */
  private static final List<String> CITING_PREPOSITIONS = List.of("under", "per");

  /** Characters after which a label opens a clause: sentence ends, colons, dashes, table pipes. */
  private static final String CLAUSE_OPENERS = ".!?:;—–|";

  /** "but, as class guidance notes, does not pin images" — an aside quoting the prompt. */
  private static final Pattern GUIDANCE_ASIDE =
      Pattern.compile(
          ", as (?:the )?(?:class )?guidance (?:notes|says|states), ", Pattern.CASE_INSENSITIVE);

  /** "acceptance criterion, which this PR must quote: ..." — the prompt's rule, restated. */
  private static final Pattern RESTATED_RULE =
      Pattern.compile(
          ",? which (?:this|the) (?:PR|pull request|finding) must quote(?![^:.;,])",
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
   * @param realPaths paths known to be real — the callers here pass the files the findings are
   *     filed on and the summary describes, not the pull request's whole file list; an example
   *     locator equal to one of them is kept
   */
  public static String scrub(String text, Collection<String> realPaths) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    var out = new StringBuilder(text.length());
    int prose = 0;
    int i = 0;
    while (i < text.length()) {
      int end = protectedEnd(text, i);
      if (end < 0) {
        i++;
        continue;
      }
      out.append(scrubProse(text.substring(prose, i))).append(text, i, end);
      prose = end;
      i = end;
    }
    out.append(scrubProse(text.substring(prose)));
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
   * A review result with its findings, its previous-finding notes and its rendered summary scrubbed
   * — the publisher's guard for whatever reached it without passing the pipeline's (a round carried
   * over from storage written before #918). The same instance when nothing changed.
   */
  public static ReviewResult scrub(ReviewResult result) {
    var findings = scrubFindings(result.findings());
    var summary = scrubMarkdown(result.summaryMarkdown(), result.findings());
    // A status note is posted too: a reopened decline's note goes into the review body.
    var statuses =
        result.previousStatuses().stream()
            .map(s -> new ReviewResult.PreviousFindingStatus(s.id(), s.status(), scrub(s.note())))
            .toList();
    if (findings.equals(result.findings())
        && Objects.equals(summary, result.summaryMarkdown())
        && statuses.equals(result.previousStatuses())) {
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
        statuses,
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
    var findings =
        response.findings().stream()
            .map(
                f ->
                    new ReviewResponse.Finding(
                        f.risk(),
                        f.confidence(),
                        f.file(),
                        f.line(),
                        scrub(f.title(), paths),
                        scrub(f.description(), paths),
                        f.suggestionOld(),
                        f.suggestionNew()))
            .toList();
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

  /**
   * Where the code or marker starting at {@code i} ends — a fenced block, an HTML comment or a
   * one-line code span — or -1 when none starts there. An unclosed fence or comment runs to the
   * end.
   */
  private static int protectedEnd(String text, int i) {
    for (var fence : List.of("```", "~~~")) {
      if (text.startsWith(fence, i)) {
        return closeAt(text, i + fence.length(), fence);
      }
    }
    if (text.startsWith("<!--", i)) {
      return closeAt(text, i + 4, "-->");
    }
    if (text.charAt(i) == '`') {
      int close = text.indexOf('`', i + 1);
      int newline = text.indexOf('\n', i + 1);
      if (close > 0 && (newline < 0 || close < newline)) {
        return close + 1;
      }
    }
    return -1;
  }

  private static int closeAt(String text, int from, String closer) {
    int close = text.indexOf(closer, from);
    return close < 0 ? text.length() : close + closer.length();
  }

  private static String scrubProse(String prose) {
    if (prose.isEmpty()) {
      return prose;
    }
    var s = removeLabelParentheticals(prose);
    s = removeNumberedLabels(s);
    s = GUIDANCE_ASIDE.matcher(s).replaceAll(" ");
    return RESTATED_RULE.matcher(s).replaceAll("");
  }

  /**
   * Drops "(dimension 4)", "(heuristic section)" and the like with the space before them — or, when
   * one opens a line, with the space after it, so the line does not start with a blank.
   */
  private static String removeLabelParentheticals(String s) {
    var m = PARENTHETICAL.matcher(s);
    var out = new StringBuilder(s.length());
    int at = 0;
    while (m.find()) {
      if (!isLabel(m.group(1))) {
        continue;
      }
      int start = m.start();
      int end = m.end();
      int blank = skipBlanksBack(s, start, at);
      if (opensLine(s, blank)) {
        end = skipBlanks(s, end);
      } else {
        start = blank;
      }
      out.append(s, at, start);
      at = end;
    }
    return out.append(s, at, s.length()).toString();
  }

  /** Whether a parenthetical's content is one of the prompt's labels. */
  private static boolean isLabel(String content) {
    var c = content.strip().toLowerCase(Locale.ROOT);
    for (var lead : List.of("see ", "per ", "under ")) {
      if (c.startsWith(lead)) {
        c = c.substring(lead.length()).strip();
        break;
      }
    }
    if (c.startsWith("review ")) {
      c = c.substring("review ".length()).strip();
    }
    if (HEADING_NAMES.contains(c) || SECTION_NAMES.contains(c)) {
      return true;
    }
    String numbers;
    if (c.startsWith("dimensions ")) {
      numbers = c.substring("dimensions ".length());
    } else if (c.startsWith("dimension ")) {
      numbers = c.substring("dimension ".length());
    } else {
      return false;
    }
    for (var number : NUMBER_SEPARATOR.split(numbers.strip(), -1)) {
      if (!isPromptNumber(number)) {
        return false;
      }
    }
    return true;
  }

  /** 1 through 10: the numbers the prompt gave its dimensions. */
  private static boolean isPromptNumber(String s) {
    if (s.isEmpty() || s.length() > 2 || !s.chars().allMatch(Character::isDigit)) {
      return false;
    }
    int n = Integer.parseInt(s);
    return n >= 1 && n <= 10;
  }

  /**
   * Drops "dimension N" where its context makes it the prompt's label: opening a text, a line, a
   * sentence, a clause after a colon or dash, or a table cell (the next word is capitalized); after
   * a citing verb ("this is dimension 5, class (c)"); or after "under"/"per", which go with it.
   * Followed by a word that reads it as an axis ("dimension 1 of the output"), it stays.
   */
  private static String removeNumberedLabels(String s) {
    var m = NUMBERED.matcher(s);
    var out = new StringBuilder(s.length());
    int at = 0;
    while (m.find()) {
      int start = m.start();
      int end = m.end();
      if (!isPromptNumber(m.group(1)) || AXIS_WORDS.contains(wordAt(s, skipBlanks(s, end)))) {
        continue;
      }
      var before = s.substring(Math.max(at, start - 40), start).toLowerCase(Locale.ROOT).strip();
      int after = skipBlanks(s, end);
      if (after < s.length() && ":,—–-".indexOf(s.charAt(after)) >= 0) {
        after = skipBlanks(s, after + 1);
      }
      if (opensClause(s, start, at)) {
        if (after >= s.length() || !Character.isLetter(s.charAt(after))) {
          continue;
        }
        out.append(s, at, start).append(Character.toUpperCase(s.charAt(after)));
        at = after + 1;
      } else if (endingWord(before, CITING_VERBS) != null) {
        out.append(s, at, start);
        at = after;
      } else {
        var preposition = endingWord(before, CITING_PREPOSITIONS);
        if (preposition == null) {
          continue;
        }
        int cut = skipBlanksBack(s, skipBlanksBack(s, start, at) - preposition.length(), at);
        if (cut > at && s.charAt(cut - 1) == ',') {
          cut--;
        }
        out.append(s, at, cut);
        at = end;
      }
    }
    return out.append(s, at, s.length()).toString();
  }

  /** The one of {@code words} that {@code before} ends with as a whole word, else null. */
  private static String endingWord(String before, List<String> words) {
    for (var word : words) {
      if (before.endsWith(word)
          && (before.length() == word.length()
              || !Character.isLetter(before.charAt(before.length() - word.length() - 1)))) {
        return word;
      }
    }
    return null;
  }

  /** The lower-cased word starting at {@code i}, empty when none does. */
  private static String wordAt(String s, int i) {
    int end = i;
    while (end < s.length() && Character.isLetter(s.charAt(end))) {
      end++;
    }
    return s.substring(i, end).toLowerCase(Locale.ROOT);
  }

  /** Whether {@code start} opens a line, or a clause after a sentence end, colon, dash or pipe. */
  private static boolean opensClause(String s, int start, int floor) {
    int blank = skipBlanksBack(s, start, floor);
    if (opensLine(s, blank)) {
      return true;
    }
    return blank < start && CLAUSE_OPENERS.indexOf(s.charAt(blank - 1)) >= 0;
  }

  /** Whether only blanks and at most one list or quote marker precede {@code i} on its line. */
  private static boolean opensLine(String s, int i) {
    var prefix = s.substring(s.lastIndexOf('\n', i - 1) + 1, i).strip();
    return prefix.isEmpty() || "-".equals(prefix) || "*".equals(prefix) || ">".equals(prefix);
  }

  private static int skipBlanks(String s, int i) {
    int j = i;
    while (j < s.length() && isBlank(s.charAt(j))) {
      j++;
    }
    return j;
  }

  private static int skipBlanksBack(String s, int i, int floor) {
    int j = i;
    while (j > floor && isBlank(s.charAt(j - 1))) {
      j--;
    }
    return j;
  }

  private static boolean isBlank(char c) {
    return c == ' ' || c == '\t';
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
