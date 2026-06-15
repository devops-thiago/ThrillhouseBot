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

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Maps AI-reported file line numbers to lines that exist in the PR diff — required for GitHub
 * inline review comments (422 when the line is outside the diff).
 */
public final class DiffLineResolver {

  // Shared with FindingQuoteValidator, which tracks the same right-side line numbers.
  static final Pattern HUNK_HEADER = Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

  private final Map<String, TreeSet<Integer>> rightSideLinesByFile;

  public DiffLineResolver(Map<String, String> patchesByFile) {
    this.rightSideLinesByFile = new HashMap<>();
    patchesByFile.forEach(
        (file, patch) -> rightSideLinesByFile.put(file, parseRightSideLines(patch)));
  }

  /**
   * Whether {@code line} — or a line within {@code tolerance} of it — is an actual right-side line
   * of the file's diff. Unlike {@link #resolveRightSideLine}, this never snaps to an arbitrarily
   * distant line: it answers "is the flagged line genuinely still in this diff", which the approve
   * backstop relies on to tell a still-open finding from one whose code has been changed away. The
   * file is matched with {@link FilePaths#same}, so a model path variant still resolves to the
   * diff's filename.
   */
  public boolean isLineInDiff(String file, int line, int tolerance) {
    if (file == null || line <= 0) {
      return false;
    }
    TreeSet<Integer> lines = rightSideLinesByFile.get(file);
    if (lines == null) {
      lines = lookupByPathVariant(file);
    }
    if (lines == null || lines.isEmpty()) {
      return false;
    }
    Integer floor = lines.floor(line);
    Integer ceiling = lines.ceiling(line);
    return (floor != null && line - floor <= tolerance)
        || (ceiling != null && ceiling - line <= tolerance);
  }

  private TreeSet<Integer> lookupByPathVariant(String file) {
    for (var entry : rightSideLinesByFile.entrySet()) {
      if (FilePaths.same(file, entry.getKey())) {
        return entry.getValue();
      }
    }
    return null;
  }

  /** Returns the requested line or the nearest commentable line in the same file diff. */
  public OptionalInt resolveRightSideLine(String file, int requestedLine) {
    if (file == null || requestedLine <= 0) {
      return OptionalInt.empty();
    }
    TreeSet<Integer> lines = rightSideLinesByFile.get(file);
    if (lines == null || lines.isEmpty()) {
      return OptionalInt.empty();
    }
    if (lines.contains(requestedLine)) {
      return OptionalInt.of(requestedLine);
    }
    var floor = lines.floor(requestedLine);
    var ceiling = lines.ceiling(requestedLine);
    if (floor == null) {
      return ceiling != null ? OptionalInt.of(ceiling) : OptionalInt.empty();
    }
    if (ceiling == null) {
      return OptionalInt.of(floor);
    }
    var floorDistance = requestedLine - floor;
    var ceilingDistance = ceiling - requestedLine;
    return OptionalInt.of(floorDistance <= ceilingDistance ? floor : ceiling);
  }

  static TreeSet<Integer> parseRightSideLines(String patch) {
    var lines = new TreeSet<Integer>();
    if (patch == null || patch.isBlank()) {
      return lines;
    }

    var newLine = 0;
    var inHunk = false;
    for (String rawLine : patch.split("\n", -1)) {
      if (rawLine.startsWith("@@")) {
        var matcher = HUNK_HEADER.matcher(rawLine);
        if (matcher.find()) {
          newLine = Integer.parseInt(matcher.group(1));
          inHunk = true;
        }
        continue;
      }
      if (inHunk && !rawLine.isEmpty()) {
        var marker = rawLine.charAt(0);
        // Additions and context lines are commentable on the RIGHT side and advance its
        // counter; deletions ('-') exist only on the LEFT side.
        if (marker == '+' || marker == ' ') {
          lines.add(newLine);
          newLine++;
        }
      }
    }
    return lines;
  }
}
