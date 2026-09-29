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
import java.util.List;
import java.util.regex.Pattern;

/**
 * The new-side lines of one file's unified-diff patch, in order: every added and context line with
 * its right-side line number and the hunk it belongs to. Removed lines are left out, because they
 * are not in the file the pull request produces. The deterministic security scan reads this rather
 * than the raw patch so a rule can look at the lines around an added one without re-parsing hunk
 * headers.
 *
 * @param lines the new-side lines in patch order
 * @param lastHunkReachesEndOfFile whether the patch's last hunk runs to the end of the new file,
 *     read from its trailing context: a unified diff carries three lines of context after the last
 *     change unless the file ends first, so fewer than three means the file ended
 */
record PatchLines(List<Line> lines, boolean lastHunkReachesEndOfFile) {

  /** Context lines a unified diff carries after a change when the file does not end first. */
  private static final int DIFF_CONTEXT_LINES = 3;

  private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)");

  /**
   * One new-side line.
   *
   * @param number the right-side (new file) line number
   * @param added whether the pull request adds it ({@code false} for a context line)
   * @param text the line without its diff marker
   * @param hunk the 0-based index of the hunk it belongs to
   */
  record Line(int number, boolean added, String text, int hunk) {}

  PatchLines {
    lines = List.copyOf(lines);
  }

  /** Parses a GitHub file patch; a {@code null} or blank patch parses to no lines. */
  static PatchLines parse(String patch) {
    var lines = new ArrayList<Line>();
    if (patch == null || patch.isBlank()) {
      return new PatchLines(lines, false);
    }
    int next = 0;
    int hunk = -1;
    int trailingContext = 0;
    for (String raw : patch.split("\n", -1)) {
      var header = HUNK_HEADER.matcher(raw);
      if (header.find()) {
        next = Integer.parseInt(header.group(1));
        hunk++;
        trailingContext = 0;
      } else if (hunk >= 0 && !raw.isEmpty()) {
        char marker = raw.charAt(0);
        if (marker == '+') {
          lines.add(new Line(next++, true, raw.substring(1), hunk));
          trailingContext = 0;
        } else if (marker == ' ') {
          lines.add(new Line(next++, false, raw.substring(1), hunk));
          trailingContext++;
        } else if (marker == '-') {
          trailingContext = 0;
        }
        // "\ No newline at end of file" and anything else carries no new-side line.
      }
    }
    return new PatchLines(lines, hunk >= 0 && trailingContext < DIFF_CONTEXT_LINES);
  }

  /** The index of the last hunk, or {@code -1} for an empty patch. */
  int lastHunk() {
    return lines.isEmpty() ? -1 : lines.get(lines.size() - 1).hunk();
  }
}
