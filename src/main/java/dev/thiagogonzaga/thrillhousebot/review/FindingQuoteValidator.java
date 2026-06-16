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

import dev.thiagogonzaga.thrillhousebot.review.ai.FindingVerificationService;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic guard against findings that quote code which is not in the diff. The model
 * occasionally reviews a paraphrase of the change instead of the change itself (historically also a
 * symptom of prompt-pipeline corruption) and then flags defects in code nobody wrote. Dogfooding
 * examples: a critical "broken type inference" finding whose suggested fix was byte-identical to
 * the committed line, and two critical "invalid syntax" findings quoting single-brace expressions
 * where the file has double braces.
 *
 * <p>The check is conservative: a finding is dropped only when none of its quoted lines exist in
 * the diff (pure phantom). When the quote is partially wrong, the unreliable suggestion block is
 * removed and confidence is capped at "low" so the finding can still surface but cannot block.
 *
 * <p>The same demotion applies when a finding's {@code suggestion_old} genuinely matches the diff
 * but its free-text {@code description} cites a chained call expression as existing source that
 * appears nowhere in scope — the fabricated mechanism is treated like a partial quote. Only
 * distinctive {@code receiver.member(...)} chains are checked, so bare method names that simply
 * fall outside the diff window are never penalized. Two further guards keep this from suppressing
 * true positives: a chain found verbatim in the diff grounds the finding and keeps it (diff
 * presence wins, even over a {@code suggestion_new} that wraps that same code); and among chains
 * absent from the diff, one that merely restates the finding's own {@code suggestion_new} is the
 * proposed fix, not a fabricated mechanism. A finding is demoted only when it cites an absent chain
 * that is neither present in the diff nor a restatement of its own fix.
 */
@ApplicationScoped
public class FindingQuoteValidator {

  private static final String LOW_CONFIDENCE = "low";

  /** Shared tail of every log line that strips a suggestion and caps confidence. */
  private static final String DEMOTION_SUFFIX = " dropping its suggestion and capping confidence";

  /**
   * A chained call expression: a receiver followed by one or more {@code .member} segments, with
   * optional argument lists. Single names like {@code installedRepos()} are deliberately excluded —
   * they are too common to flag against the diff's narrow window. A match is only treated as a code
   * citation when it actually contains a call ({@code '('}). Quantifiers are possessive: each class
   * never overlaps the token that follows it, so no backtracking is ever needed and large inputs
   * cannot trigger catastrophic backtracking.
   */
  private static final Pattern CHAINED_CALL =
      Pattern.compile(
          "[A-Za-z_$][A-Za-z0-9_$]*+(?:\\([^()]*+\\))?+(?:\\.[A-Za-z_$][A-Za-z0-9_$]*+(?:\\([^()]*+\\))?+)++");

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  public record DiffLine(String normalizedText, int rightLineNum, char marker) {}

  /** Validates each finding's quoted code against the raw (unescaped) diff text. */
  public ReviewResponse validate(ReviewResponse response, String diff) {
    if (response.findings().isEmpty() || diff == null || diff.isBlank()) {
      return response;
    }
    DiffIndex index = indexDiff(diff);
    var kept = new ArrayList<ReviewResponse.Finding>(response.findings().size());
    var changed = false;
    for (ReviewResponse.Finding finding : response.findings()) {
      List<String> quoted = normalizedLines(finding.suggestionOld());
      QuoteMatch match = matchQuote(quoted, index.linesFor(finding.file()));
      if (match == QuoteMatch.FULL) {
        match = checkAmbiguity(quoted, finding.line(), index.diffLinesFor(finding.file()));
      }
      switch (match) {
        case FULL, NO_QUOTE -> {
          if (descriptionCitesAbsentCode(finding, index)) {
            Log.infof(
                "Finding '%s' (%s:%d) cites code in its description that appears nowhere in the diff —"
                    + DEMOTION_SUFFIX,
                finding.title(),
                finding.file(),
                finding.line());
            kept.add(withoutSuggestion(finding));
            changed = true;
          } else {
            kept.add(finding);
          }
        }
        case PARTIAL -> {
          Log.infof(
              "Finding '%s' (%s:%d) quotes code only partially present in the diff —"
                  + DEMOTION_SUFFIX,
              finding.title(),
              finding.file(),
              finding.line());
          kept.add(withoutSuggestion(finding));
          changed = true;
        }
        case AMBIGUOUS -> {
          Log.infof(
              "Finding '%s' (%s:%d) quotes code that appears in multiple locations, but the finding's line is ambiguous —"
                  + DEMOTION_SUFFIX,
              finding.title(),
              finding.file(),
              finding.line());
          kept.add(withoutSuggestion(finding));
          changed = true;
        }
        case NONE -> {
          Log.infof(
              "Dropping finding '%s' (%s:%d) — the code it quotes does not appear in the diff",
              finding.title(), finding.file(), finding.line());
          changed = true;
        }
      }
    }
    if (!changed) {
      return response;
    }
    return new ReviewResponse(
        kept,
        response.previousFindingsStatus(),
        FindingVerificationService.recount(response.summary(), kept));
  }

  enum QuoteMatch {
    /** Finding has no suggestion_old to check. */
    NO_QUOTE,
    /** Every quoted line is present in the diff. */
    FULL,
    /** Some quoted lines are present, others are not. */
    PARTIAL,
    /** No quoted line is present in the diff. */
    NONE,
    /**
     * Quote is fully present in multiple locations, but finding line doesn't uniquely select one.
     */
    AMBIGUOUS
  }

  /**
   * Whether the finding's description rests on a fabricated mechanism: it cites at least one
   * chained call expression as existing source that is absent from the diff scoped to its file and
   * is not merely a restatement of the finding's own proposed fix. This catches the failure mode
   * where a genuine {@code suggestion_old} quote passes the gate but the supporting prose invents
   * the mechanism (e.g. {@code dashboardConfig.accountOwner().orElse(null)} for a method parameter
   * that is never derived that way).
   *
   * <p>Two guards keep this from suppressing true positives. <b>Diff presence wins:</b> a chain
   * found verbatim in the diff grounds the finding and keeps it immediately, before any
   * fix-restatement is considered — so a {@code suggestion_new} that wraps the existing code it
   * modifies can never strip the grounding chain, and naming an off-diff helper alongside a present
   * one never demotes (#121, mechanism b). <b>The proposed fix is not a citation:</b> among chains
   * <em>absent</em> from the diff, one whose compacted form the compacted {@code suggestion_new}
   * restates is the hypothetical fix — absent by definition — and is not counted as fabrication
   * (#121, mechanism a). The match is whitespace-insensitive so reformatting can't hide a real
   * citation.
   */
  private static boolean descriptionCitesAbsentCode(
      ReviewResponse.Finding finding, DiffIndex index) {
    List<String> cited = citedCallExpressions(finding.description());
    if (cited.isEmpty()) {
      return false;
    }
    List<String> compactedScope = compactedLines(index.diffLinesFor(finding.file()));
    String compactedSuggestion =
        finding.suggestionNew() == null ? "" : compact(finding.suggestionNew());
    boolean fabricated = false;
    for (String expression : cited) {
      String needle = compact(expression);
      if (appearsIn(needle, compactedScope)) {
        // Present verbatim in the diff: the finding is grounded — keep it, whatever else it cites.
        return false;
      }
      // Absent from the diff: a fabricated mechanism unless it merely restates the proposed fix.
      if (!compactedSuggestion.contains(needle)) {
        fabricated = true;
      }
    }
    return fabricated;
  }

  /** The chained call expressions a description presents as code, e.g. {@code a.b().c(null)}. */
  private static List<String> citedCallExpressions(String description) {
    if (description == null || description.isBlank()) {
      return List.of();
    }
    var expressions = new ArrayList<String>();
    var matcher = CHAINED_CALL.matcher(description);
    while (matcher.find()) {
      String expression = matcher.group();
      // Require an actual call: a dotted field chain (e.g. config.value) or a bare name is not
      // distinctive enough to flag against the diff's narrow window.
      if (expression.indexOf('(') >= 0) {
        expressions.add(expression);
      }
    }
    return expressions;
  }

  /**
   * The scoped diff lines with all whitespace removed, compacted once for reuse across citations.
   */
  private static List<String> compactedLines(List<DiffLine> scope) {
    var compacted = new ArrayList<String>(scope.size());
    for (DiffLine line : scope) {
      compacted.add(compact(line.normalizedText()));
    }
    return compacted;
  }

  /**
   * Whether {@code needle} — an already-compacted call chain (see {@link #citedCallExpressions}) —
   * occurs within any already-compacted diff line. Both sides are whitespace-stripped so
   * indentation or reformatting can't hide a citation.
   */
  private static boolean appearsIn(String needle, List<String> compactedScope) {
    for (String line : compactedScope) {
      if (line.contains(needle)) {
        return true;
      }
    }
    return false;
  }

  private static String compact(String text) {
    return WHITESPACE.matcher(text).replaceAll("");
  }

  static QuoteMatch matchQuote(List<String> quoted, Set<String> diffLines) {
    if (quoted.isEmpty()) {
      return QuoteMatch.NO_QUOTE;
    }
    long present = quoted.stream().filter(diffLines::contains).count();
    if (present == quoted.size()) {
      return QuoteMatch.FULL;
    }
    return present == 0 ? QuoteMatch.NONE : QuoteMatch.PARTIAL;
  }

  /**
   * Disambiguates a quote the {@link #matchQuote} gate already accepted as FULL. Only quotes that
   * appear as a contiguous run over the retained (context/deleted) lines are scrutinized: additions
   * are dropped because {@code suggestion_old} is original code, and a quote found in two or more
   * such locations is AMBIGUOUS unless the finding's line uniquely selects one (within a 3-line
   * tolerance). Anything not matched contiguously here stays FULL — the conservative direction.
   */
  private static QuoteMatch checkAmbiguity(
      List<String> quoted, int findingLine, List<DiffLine> diffLines) {
    List<DiffLine> filtered = retainedLines(diffLines);
    List<Integer> matchIndices = contiguousMatchStarts(filtered, quoted);
    if (matchIndices.size() <= 1) {
      return QuoteMatch.FULL;
    }

    int k = quoted.size();
    int selectedCount = 0;
    for (int idx : matchIndices) {
      int startLine = filtered.get(idx).rightLineNum();
      int endLine = filtered.get(idx + k - 1).rightLineNum();
      if (findingLine >= startLine - 3 && findingLine <= endLine + 3) {
        selectedCount++;
      }
    }
    return selectedCount == 1 ? QuoteMatch.FULL : QuoteMatch.AMBIGUOUS;
  }

  /**
   * The diff lines that {@code suggestion_old} can match: context and deletions, never additions.
   */
  private static List<DiffLine> retainedLines(List<DiffLine> diffLines) {
    var filtered = new ArrayList<DiffLine>();
    for (DiffLine dl : diffLines) {
      if (dl.marker() != '+') {
        filtered.add(dl);
      }
    }
    return filtered;
  }

  /** Start indices where {@code quoted} appears as a contiguous run in {@code lines}. */
  private static List<Integer> contiguousMatchStarts(List<DiffLine> lines, List<String> quoted) {
    var starts = new ArrayList<Integer>();
    int k = quoted.size();
    for (int i = 0; i <= lines.size() - k; i++) {
      if (matchesAt(lines, i, quoted)) {
        starts.add(i);
      }
    }
    return starts;
  }

  private static boolean matchesAt(List<DiffLine> lines, int offset, List<String> quoted) {
    for (int j = 0; j < quoted.size(); j++) {
      if (!lines.get(offset + j).normalizedText().equals(quoted.get(j))) {
        return false;
      }
    }
    return true;
  }

  /**
   * The diff's normalized body lines, both as one union and scoped per file. A quote is checked
   * against its finding's own file when that file is identifiable in the diff — the same text
   * appearing in some other file's hunk must not validate a misattributed finding. The union is the
   * conservative fallback for findings whose file cannot be matched to a diff section.
   */
  record DiffIndex(
      Map<String, List<DiffLine>> fileLines,
      List<DiffLine> allLines,
      Map<String, Set<String>> byFile,
      Set<String> all) {

    Set<String> linesFor(String file) {
      var sections = sectionKeysFor(file);
      if (sections.isEmpty()) {
        return all;
      }
      var scoped = new HashSet<String>();
      for (String section : sections) {
        scoped.addAll(byFile.get(section));
      }
      return scoped;
    }

    List<DiffLine> diffLinesFor(String file) {
      var sections = sectionKeysFor(file);
      if (sections.isEmpty()) {
        return allLines;
      }
      var scoped = new ArrayList<DiffLine>();
      for (String section : sections) {
        scoped.addAll(fileLines.get(section));
      }
      return scoped;
    }

    /**
     * The diff-section keys a finding's file resolves to, or an empty list when the file cannot be
     * matched and the caller must fall back to the whole-diff union. An exact section match wins;
     * otherwise the model sometimes shortens or expands paths, so suffix matches still scope. An
     * ambiguous name (Handler.java in several modules) scopes to the union of its candidates, never
     * to the whole diff — a quote from an unrelated file must not validate just because the
     * finding's path was ambiguous.
     */
    private List<String> sectionKeysFor(String file) {
      if (file == null || file.isBlank()) {
        return List.of();
      }
      if (fileLines.containsKey(file)) {
        return List.of(file);
      }
      return fileLines.keySet().stream()
          .filter(k -> k.endsWith("/" + file) || file.endsWith("/" + k))
          .toList();
    }
  }

  /**
   * Diff body lines normalized for comparison: the unified-diff marker column (+, -, space) is
   * removed and surrounding whitespace trimmed, so quoted code matches regardless of indentation
   * shifts or which side of the change it sits on. The lines also get the same marker
   * neutralization the prompt pipeline applies, since the model quotes from the neutralized text.
   * File scoping follows the formatter's "### filename (status, +A -D)" section headers.
   */
  static DiffIndex indexDiff(String diff) {
    var fileLines = new HashMap<String, List<DiffLine>>();
    var allLines = new ArrayList<DiffLine>();
    List<DiffLine> current = null;
    int rightLine = 0;

    for (String raw : PromptTemplateEscaper.neutralizeMarkers(diff).split("\n", -1)) {
      if (raw.startsWith("### ")) {
        current = fileLines.computeIfAbsent(sectionFilename(raw), k -> new ArrayList<>());
        // A new file section restarts right-side numbering; don't carry the previous file's
        // counter forward in case its first hunk header is missing or unparseable.
        rightLine = 0;
      } else if (raw.startsWith("@@")) {
        rightLine = hunkStartLine(raw, rightLine);
      } else if (!isDiffStructureLine(raw)) {
        rightLine = appendBodyLine(raw, rightLine, allLines, current);
      }
    }

    var byFile = new HashMap<String, Set<String>>();
    fileLines.forEach((file, lines) -> byFile.put(file, textSet(lines)));
    return new DiffIndex(fileLines, allLines, byFile, textSet(allLines));
  }

  /** The right-side start line of a hunk header, or {@code fallback} when it can't be parsed. */
  private static int hunkStartLine(String raw, int fallback) {
    var matcher = DiffLineResolver.HUNK_HEADER.matcher(raw);
    return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
  }

  /**
   * Records a normalized body line into the union and (when scoped) its file, then returns the
   * advanced right-side line counter — additions and context advance it; deletions do not.
   */
  private static int appendBodyLine(
      String raw, int rightLine, List<DiffLine> allLines, List<DiffLine> current) {
    String normalized = bodyOf(raw).strip();
    if (normalized.isEmpty()) {
      return rightLine;
    }
    // A non-empty normalized body implies a non-empty raw line, so charAt(0) is safe.
    char marker = raw.charAt(0);
    var diffLine = new DiffLine(normalized, rightLine, marker);
    allLines.add(diffLine);
    if (current != null) {
      current.add(diffLine);
    }
    return marker == '+' || marker == ' ' ? rightLine + 1 : rightLine;
  }

  private static Set<String> textSet(List<DiffLine> lines) {
    var set = new HashSet<String>();
    for (var dl : lines) {
      set.add(dl.normalizedText());
    }
    return set;
  }

  /**
   * File headers, code fences, and the {@code "\ No newline at end of file"} indicator are diff
   * plumbing, never quotable content. Hunk headers ({@code @@}) are intercepted earlier for line
   * tracking, so they never reach this check.
   */
  private static boolean isDiffStructureLine(String raw) {
    return raw.startsWith("+++")
        || raw.startsWith("---")
        || raw.startsWith("```")
        || raw.startsWith("\\ ");
  }

  /** The line without its unified-diff marker column (+, -, space). */
  private static String bodyOf(String raw) {
    if (!raw.isEmpty() && (raw.charAt(0) == '+' || raw.charAt(0) == '-' || raw.charAt(0) == ' ')) {
      return raw.substring(1);
    }
    return raw;
  }

  private static String sectionFilename(String headerLine) {
    String name = headerLine.substring(4);
    int suffix = name.lastIndexOf(" (");
    return (suffix > 0 ? name.substring(0, suffix) : name).strip();
  }

  private static List<String> normalizedLines(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    var lines = new ArrayList<String>();
    for (String raw : text.split("\n", -1)) {
      String normalized = raw.strip();
      if (!normalized.isEmpty()) {
        lines.add(normalized);
      }
    }
    return lines;
  }

  private static ReviewResponse.Finding withoutSuggestion(ReviewResponse.Finding finding) {
    return new ReviewResponse.Finding(
        finding.risk(),
        LOW_CONFIDENCE,
        finding.file(),
        finding.line(),
        finding.title(),
        finding.description(),
        null,
        null);
  }
}
