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
package dev.thiagogonzaga.thrillhousebot.review.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The statements of one summary that the summary verification call judges (#1078), each with the id
 * the call answers by, and the deterministic edit that removes the ones it reports contradicted.
 *
 * <ul>
 *   <li>{@code S<n>}: a sentence of the purpose paragraph. A contradicted one is cut from the
 *       paragraph; the rest of the text is left as it was.
 *   <li>{@code R<n>}: a walkthrough row's file summary. A contradicted one is removed, so the row
 *       shows the renderer's own text for a file with no summary.
 *   <li>{@code D<n>}: a line of the diagram that draws a node or an edge. One contradicted line
 *       drops the whole diagram, since a diagram with a line taken out no longer shows the flow it
 *       claims to.
 * </ul>
 *
 * The call only names ids; which text an id covers, and what happens to it, is decided here.
 */
final class SummaryStatements {

  /** The most statements one call judges; the rest are kept as they are. */
  static final int MAX_STATEMENTS = 150;

  /** The longest statement text sent; a longer one is sent cut and judged on what is shown. */
  static final int MAX_STATEMENT_CHARS = 600;

  /**
   * Where one sentence of the purpose paragraph ends: a line break, or a space after a full stop,
   * question or exclamation mark (and any closing quote or bracket) when the next word does not
   * start in lower case or with a digit. "e.g. the cache" and "version 1.5" stay one sentence.
   */
  private static final Pattern SENTENCE_BREAK =
      Pattern.compile("[ \\t]*\\R\\s*|(?<=[.!?][\"'”’)\\]]?)[ \\t]+(?=[^\\p{Ll}\\p{N}\\s])");

  /**
   * First words of diagram lines that only name the diagram type or declare layout or style: they
   * state nothing about the code. A type such as {@code sequenceDiagram} is matched by its ending.
   */
  private static final Set<String> SCAFFOLD_WORDS =
      Set.of(
          "graph",
          "flowchart",
          "direction",
          "subgraph",
          "classdef",
          "class",
          "style",
          "linkstyle",
          "click");

  /** Diagram lines that are only these words: a block's end and an unwrapped fence's tag. */
  private static final Set<String> SCAFFOLD_LINES = Set.of("end", "mermaid");

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /** One statement: its id, what kind of text it is, and where that text sits. */
  record Statement(String id, Kind kind, int index, int start, int end, String text) {}

  /** The three kinds of summary text the call judges. */
  enum Kind {
    SENTENCE("S"),
    ROW("R"),
    DIAGRAM("D");

    private final String prefix;

    Kind(String prefix) {
      this.prefix = prefix;
    }
  }

  private final ReviewResponse.Summary summary;
  private final List<Statement> statements;

  /** Every sentence of the purpose paragraph, judged or past the cap, to rebuild it from. */
  private final List<Statement> sentences;

  private SummaryStatements(ReviewResponse.Summary summary, List<Statement> found) {
    this.summary = summary;
    this.statements =
        List.copyOf(found.size() > MAX_STATEMENTS ? found.subList(0, MAX_STATEMENTS) : found);
    this.sentences = found.stream().filter(s -> s.kind() == Kind.SENTENCE).toList();
  }

  /**
   * The statements of {@code summary}: every sentence of its purpose paragraph, every non-blank
   * file summary, and, when {@code withDiagram}, every diagram line that draws something. At most
   * {@link #MAX_STATEMENTS}, sentences first, then rows, then diagram lines.
   */
  static SummaryStatements of(ReviewResponse.Summary summary, boolean withDiagram) {
    var found = new ArrayList<Statement>();
    if (summary == null) {
      return new SummaryStatements(null, found);
    }
    addSentences(found, summary.prPurpose());
    var files = summary.fileSummaries();
    for (int i = 0; i < files.size(); i++) {
      var entry = files.get(i);
      if (entry.summary() != null && !entry.summary().isBlank()) {
        var path = entry.path() == null ? "" : entry.path().strip();
        add(found, Kind.ROW, i, 0, 0, path + ": " + entry.summary().strip());
      }
    }
    if (withDiagram) {
      addDiagramLines(found, summary.walkthroughDiagram());
    }
    return new SummaryStatements(summary, found);
  }

  private static void addSentences(List<Statement> found, String purpose) {
    if (purpose == null || purpose.isBlank()) {
      return;
    }
    var breaks = SENTENCE_BREAK.matcher(purpose);
    int start = 0;
    while (start < purpose.length()) {
      int end = breaks.find(start) ? breaks.start() : purpose.length();
      int next = end < purpose.length() ? Math.max(breaks.end(), end + 1) : end;
      // The sentence's own text, without the whitespace at either end: it starts with the first
      // character that is not whitespace, so its first occurrence in the span is where it sits.
      var span = purpose.substring(start, end);
      var text = span.strip();
      if (!text.isEmpty()) {
        int from = start + span.indexOf(text);
        add(found, Kind.SENTENCE, 0, from, from + text.length(), text);
      }
      start = next;
    }
  }

  private static void addDiagramLines(List<Statement> found, String diagram) {
    if (diagram == null || diagram.isBlank()) {
      return;
    }
    var lines = diagram.strip().lines().toList();
    // The type line ("flowchart TD") and any fence around the source state nothing.
    for (int i = 0; i < lines.size(); i++) {
      var line = lines.get(i).strip();
      if (!line.isEmpty() && !isScaffold(line)) {
        add(found, Kind.DIAGRAM, i, 0, 0, line);
      }
    }
  }

  /** Whether a diagram line only fences the source, names its type or sets layout or style. */
  private static boolean isScaffold(String line) {
    var lower = line.toLowerCase(Locale.ROOT);
    if (lower.startsWith("%%") || lower.startsWith("```") || SCAFFOLD_LINES.contains(lower)) {
      return true;
    }
    var first = WHITESPACE.split(lower, 2)[0];
    return SCAFFOLD_WORDS.contains(first)
        || first.endsWith("diagram")
        || first.endsWith("diagram-v2");
  }

  private static void add(
      List<Statement> found, Kind kind, int index, int start, int end, String text) {
    var count = found.stream().filter(s -> s.kind() == kind).count();
    var shown = text.length() > MAX_STATEMENT_CHARS ? text.substring(0, MAX_STATEMENT_CHARS) : text;
    found.add(new Statement(kind.prefix + (count + 1), kind, index, start, end, shown));
  }

  /** The statements, in id order within each kind. */
  List<Statement> statements() {
    return statements;
  }

  boolean isEmpty() {
    return statements.isEmpty();
  }

  /** The statements as the call reads them: one {@code [id] text} line each. */
  String render() {
    var sb = new StringBuilder();
    for (var statement : statements) {
      sb.append('[')
          .append(statement.id())
          .append("] ")
          .append(statement.text().replace('\n', ' '))
          .append('\n');
    }
    return sb.toString();
  }

  /**
   * The summary with every statement {@code contradicted} names removed: the sentences cut from the
   * purpose paragraph, the rows' file summaries dropped, and the diagram dropped when any of its
   * lines is named. An id that names no statement is ignored, and with nothing to remove the
   * summary is returned as it is.
   */
  ReviewResponse.Summary without(Set<String> contradicted) {
    if (summary == null || contradicted.isEmpty()) {
      return summary;
    }
    var named =
        statements.stream()
            .filter(s -> contradicted.contains(s.id().toUpperCase(Locale.ROOT)))
            .toList();
    if (named.isEmpty()) {
      return summary;
    }
    var purpose = summary.prPurpose();
    var cutSentences =
        named.stream().filter(s -> s.kind() == Kind.SENTENCE).collect(Collectors.toSet());
    if (!cutSentences.isEmpty()) {
      purpose = withoutSentences(purpose, sentences, cutSentences);
    }
    var cutRows =
        named.stream()
            .filter(s -> s.kind() == Kind.ROW)
            .map(Statement::index)
            .collect(Collectors.toSet());
    var files = new ArrayList<ReviewResponse.FileSummary>();
    for (int i = 0; i < summary.fileSummaries().size(); i++) {
      if (!cutRows.contains(i)) {
        files.add(summary.fileSummaries().get(i));
      }
    }
    var diagram =
        named.stream().anyMatch(s -> s.kind() == Kind.DIAGRAM)
            ? null
            : summary.walkthroughDiagram();
    return new ReviewResponse.Summary(
        summary.totalFindings(),
        summary.critical(),
        summary.high(),
        summary.medium(),
        summary.low(),
        summary.overallAssessment(),
        purpose,
        summary.descriptionGaps(),
        summary.suggestedLabels(),
        files,
        diagram,
        summary.addressedGaps());
  }

  /**
   * {@code purpose} with the {@code cut} sentences taken out. The sentences left keep their own
   * text, and two that were apart are joined by the widest break that stood between them, so a line
   * or paragraph break around a cut sentence stays; {@code null} when no sentence is left.
   */
  private static String withoutSentences(
      String purpose, List<Statement> sentences, Set<Statement> cut) {
    var kept = new StringBuilder();
    int previous = -1;
    for (int i = 0; i < sentences.size(); i++) {
      var sentence = sentences.get(i);
      if (cut.contains(sentence)) {
        continue;
      }
      if (previous >= 0) {
        kept.append(widestBreak(purpose, sentences, previous, i));
      }
      kept.append(purpose, sentence.start(), sentence.end());
      previous = i;
    }
    return previous < 0 ? null : kept.toString();
  }

  /**
   * The break with the most line breaks among those between sentence {@code from} and sentence
   * {@code to}; of equals, the last.
   */
  private static String widestBreak(String purpose, List<Statement> sentences, int from, int to) {
    String widest = null;
    for (int j = from; j < to; j++) {
      var gap = purpose.substring(sentences.get(j).end(), sentences.get(j + 1).start());
      if (widest == null || lineBreaks(gap) >= lineBreaks(widest)) {
        widest = gap;
      }
    }
    return widest;
  }

  private static long lineBreaks(String text) {
    return text.chars().filter(c -> c == '\n').count();
  }
}
