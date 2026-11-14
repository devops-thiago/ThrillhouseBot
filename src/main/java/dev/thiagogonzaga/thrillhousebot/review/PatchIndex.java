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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One file's unified-diff patch, parsed once with both sides (#1321): every added, removed and
 * context line with its left-side (base) and right-side (head) line number, its text and the hunk
 * it belongs to. It is the only patch parser: {@link DiffLineResolver}, {@link DiffIndex} (and
 * through it {@link FindingQuoteValidator}), {@link PatchLines}, {@link DiffLineNumbers} and {@link
 * PatchCoverageResolver} all read their lines from here, so they cannot disagree on a number.
 *
 * <p>Inside a hunk a line starting with {@code +}, {@code -} or a space is a diff line, even when
 * it reads {@code +++} or {@code ---} (#1103). An empty line and the {@code \ No newline at end of
 * file} note carry no line. Any other line ends the hunk, so the file headers of a raw {@code git
 * diff} ({@code diff --git}, {@code index}, then {@code ---} and {@code +++} before the next hunk
 * header) are never read as code. Lines before the first hunk header belong to no hunk and are left
 * out.
 *
 * @param lines every diff line in patch order
 * @param lastHunk the 0-based index of the last hunk, or {@code -1} when there is none. It is
 *     counted from the hunk headers, so a last hunk that only removes lines still counts
 * @param lastHunkReachesEndOfFile whether the last hunk runs to the end of the new file, read from
 *     its trailing context: a unified diff carries three lines of context after the last change
 *     unless the file ends first
 */
record PatchIndex(List<Line> lines, int lastHunk, boolean lastHunkReachesEndOfFile) {

  /** A hunk header, with the left-side and the right-side start line. */
  static final Pattern HUNK_HEADER =
      Pattern.compile("@@ -(?<left>\\d+)(?:,\\d+)? \\+(?<right>\\d+)(?:,\\d+)? @@");

  /** Context lines a unified diff carries after a change when the file does not end first. */
  private static final int DIFF_CONTEXT_LINES = 3;

  private static final PatchIndex EMPTY = new PatchIndex(List.of(), -1, false);

  /** What a diff line does. */
  enum Kind {
    /** The pull request adds it: right side only. */
    ADDED('+'),
    /** The pull request removes it: left side only. */
    DELETED('-'),
    /** Unchanged: on both sides. */
    CONTEXT(' ');

    private final char marker;

    Kind(char marker) {
      this.marker = marker;
    }

    static Kind of(char marker) {
      return switch (marker) {
        case '+' -> ADDED;
        case '-' -> DELETED;
        default -> CONTEXT;
      };
    }
  }

  /**
   * One diff line.
   *
   * @param hunk the 0-based index of its hunk
   * @param kind added, deleted or context
   * @param leftLine its base-side line number; for an added line, the number the next left-side
   *     line takes
   * @param rightLine its head-side line number; for a deleted line, the number the next right-side
   *     line takes, which is where the removal sits in the new file
   * @param text the line without its diff marker
   * @param position its 0-based index among the lines the patch was split into, so a caller that
   *     rewrites the patch text can find it again
   */
  record Line(int hunk, Kind kind, int leftLine, int rightLine, String text, int position) {

    boolean added() {
      return kind == Kind.ADDED;
    }

    boolean deleted() {
      return kind == Kind.DELETED;
    }

    /** Whether the line is in the head file: added or context. */
    boolean onRight() {
      return kind != Kind.DELETED;
    }

    /** Whether the line is in the base file: deleted or context. */
    boolean onLeft() {
      return kind != Kind.ADDED;
    }

    char marker() {
      return kind.marker;
    }

    /** The text without surrounding whitespace, the form quotes are compared in. */
    String normalizedText() {
      return text.strip();
    }
  }

  PatchIndex {
    lines = List.copyOf(lines);
  }

  /** Parses a GitHub file patch; a {@code null} or blank patch parses to no lines. */
  static PatchIndex parse(String patch) {
    if (patch == null || patch.isBlank()) {
      return EMPTY;
    }
    return parse(Arrays.asList(patch.split("\n", -1)));
  }

  /** Parses a patch already split into lines. */
  static PatchIndex parse(List<String> rawLines) {
    var builder = new Builder();
    rawLines.forEach(builder::accept);
    return builder.build();
  }

  /** The lines on the right side (added and context), in order. */
  List<Line> rightSide() {
    return lines.stream().filter(Line::onRight).toList();
  }

  /** The lines of each hunk, in order; a hunk with no line is an empty list. */
  List<List<Line>> hunks() {
    var hunks = new ArrayList<List<Line>>();
    for (int i = 0; i <= lastHunk; i++) {
      hunks.add(new ArrayList<>());
    }
    lines.forEach(line -> hunks.get(line.hunk()).add(line));
    return hunks;
  }

  /**
   * Reads a patch one line at a time. {@link DiffIndex} feeds several files' patches through one
   * builder each and ends a hunk at its own structure lines (fences, section headers), so the
   * formatted review diff is parsed by the same rules as a single patch.
   */
  static final class Builder {

    private final List<Line> lines = new ArrayList<>();
    private final List<Line> looseLines = new ArrayList<>();
    private int position = -1;
    private int hunk = -1;
    private boolean inHunk;
    private int nextLeft;
    private int nextRight;
    private int trailingContext;

    /** Reads the next raw line of the patch. */
    void accept(String raw) {
      position++;
      if (raw.startsWith("@@")) {
        startHunk(raw);
      } else if (inHunk && !raw.isEmpty() && raw.charAt(0) != '\\') {
        readBodyLine(raw);
      } else if (!inHunk && isLooseBodyLine(raw)) {
        looseLines.add(new Line(-1, Kind.of(raw.charAt(0)), 0, 0, raw.substring(1), position));
      }
    }

    /**
     * Lines shaped like diff lines that no hunk holds: under a header that could not be read, or in
     * a section with none. They have no line number and are left out of {@link #build}; only the
     * quote check reads them ({@link DiffIndex#quotableLines}), so a quote of code the call was
     * shown still matches. A {@code +++} or {@code ---} file header outside a hunk is not one.
     */
    List<Line> looseLines() {
      return List.copyOf(looseLines);
    }

    private static boolean isLooseBodyLine(String raw) {
      return !raw.isEmpty()
          && "+- ".indexOf(raw.charAt(0)) >= 0
          && !raw.startsWith("+++")
          && !raw.startsWith("---");
    }

    /** Ends the current hunk: lines up to the next hunk header belong to none. */
    void endHunk() {
      inHunk = false;
    }

    PatchIndex build() {
      return new PatchIndex(lines, hunk, hunk >= 0 && trailingContext < DIFF_CONTEXT_LINES);
    }

    private void startHunk(String raw) {
      Matcher header = HUNK_HEADER.matcher(raw);
      if (!header.lookingAt()) {
        inHunk = false;
        return;
      }
      nextLeft = Integer.parseInt(header.group("left"));
      nextRight = Integer.parseInt(header.group("right"));
      hunk++;
      inHunk = true;
      trailingContext = 0;
    }

    private void readBodyLine(String raw) {
      char marker = raw.charAt(0);
      if (marker != '+' && marker != '-' && marker != ' ') {
        inHunk = false;
        return;
      }
      var kind = Kind.of(marker);
      lines.add(new Line(hunk, kind, nextLeft, nextRight, raw.substring(1), position));
      if (kind != Kind.ADDED) {
        nextLeft++;
      }
      if (kind != Kind.DELETED) {
        nextRight++;
      }
      trailingContext = kind == Kind.CONTEXT ? trailingContext + 1 : 0;
    }
  }
}
