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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
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
  private final Map<String, List<String>> rightSideTextByFile;

  public DiffLineResolver(Map<String, String> patchesByFile) {
    this.rightSideLinesByFile = new HashMap<>();
    this.rightSideTextByFile = new HashMap<>();
    patchesByFile.forEach(
        (file, patch) -> {
          RightSide rightSide = parseRightSide(patch);
          rightSideLinesByFile.put(file, rightSide.lines());
          rightSideTextByFile.put(file, rightSide.text());
        });
  }

  /**
   * Whether the prior finding's flagged code is still present on the right side (additions and
   * surviving context) of the current diff — the predicate the issue #118 approve backstop relies
   * on to tell a still-open finding from one whose code has been changed away.
   *
   * <p>When the finding carries an {@code anchor} (its persisted {@code suggestion_old}, the
   * verbatim code it flagged), presence is judged by <em>content</em>: the anchor's non-blank lines
   * must appear, in order and adjacent, as a contiguous run of right-side lines. This is immune to
   * the two failure modes of a raw line-number test (#129): a force-push/rebase that drifts the
   * still-open code by more than {@code tolerance} lines no longer reads as "gone" (under-block,
   * re-opening #118), and code that was deleted or replaced no longer reads as "present" just
   * because a surviving context line sits near the stale line number (over-block) — the replaced
   * text exists only on the diff's left side. Requiring a contiguous run, rather than mere
   * set-membership of each line, also stops a multi-line block whose lines happen to survive
   * scattered in unrelated places from reading as still-present.
   *
   * <p>One residual the content test cannot resolve: a single generic anchor line (e.g. {@code
   * return null;}) that was changed away at the finding's location but recurs verbatim elsewhere in
   * the same file still reads as present. The stale prior-revision line cannot disambiguate it
   * (that is the drift the check exists to tolerate), so this errs toward holding — a needless
   * APPROVE→COMMENT, the safe direction for a downgrade-only backstop, never the #118 under-block.
   *
   * <p>When the finding has no anchor (a suggestion-less finding, or one whose suggestion was
   * stripped), it falls back to {@link #isLineInDiff}. That fallback is a coarse line proxy that
   * retains the raw-line fragility in <em>both</em> directions (it can under-block on drift and
   * over-block on nearby context); it applies only to the minority of anchorless findings, for
   * which no flagged-code content survives to match against.
   */
  public boolean isFindingPresent(String file, int line, String anchor, int tolerance) {
    if (file == null) {
      return false;
    }
    List<String> anchorLines = normalizedAnchorLines(anchor);
    if (anchorLines.isEmpty()) {
      return isLineInDiff(file, line, tolerance);
    }
    List<String> text = rightSideTextByFile.get(file);
    if (text == null) {
      text = byPathVariant(rightSideTextByFile, file);
    }
    return text != null && containsContiguous(text, anchorLines);
  }

  /** Whether {@code needle} appears as a contiguous, in-order run within {@code haystack}. */
  private static boolean containsContiguous(List<String> haystack, List<String> needle) {
    for (int i = 0; i + needle.size() <= haystack.size(); i++) {
      if (matchesAt(haystack, i, needle)) {
        return true;
      }
    }
    return false;
  }

  private static boolean matchesAt(List<String> haystack, int offset, List<String> needle) {
    for (int j = 0; j < needle.size(); j++) {
      if (!haystack.get(offset + j).equals(needle.get(j))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether {@code line} — or a line within {@code tolerance} of it — is an actual right-side line
   * of the file's diff. Unlike {@link #resolveRightSideLine}, this never snaps to an arbitrarily
   * distant line. It is the anchorless fallback for {@link #isFindingPresent}: a coarse
   * line-membership proxy used only when a finding carries no flagged-code anchor to match by
   * content. The file is matched with {@link FilePaths#same}, so a model path variant still
   * resolves to the diff's filename.
   */
  public boolean isLineInDiff(String file, int line, int tolerance) {
    if (file == null || line <= 0) {
      return false;
    }
    NavigableSet<Integer> lines = rightSideLinesByFile.get(file);
    if (lines == null) {
      lines = byPathVariant(rightSideLinesByFile, file);
    }
    if (lines == null || lines.isEmpty()) {
      return false;
    }
    Integer floor = lines.floor(line);
    Integer ceiling = lines.ceiling(line);
    return (floor != null && line - floor <= tolerance)
        || (ceiling != null && ceiling - line <= tolerance);
  }

  /** The value for the diff filename matching {@code file} as a path variant, or {@code null}. */
  private static <V> V byPathVariant(Map<String, V> byFile, String file) {
    for (var entry : byFile.entrySet()) {
      if (FilePaths.same(file, entry.getKey())) {
        return entry.getValue();
      }
    }
    return null;
  }

  /** Trimmed, non-blank lines of an anchor (a finding's {@code suggestion_old}). */
  private static List<String> normalizedAnchorLines(String anchor) {
    if (anchor == null || anchor.isBlank()) {
      return List.of();
    }
    var lines = new ArrayList<String>();
    for (String raw : anchor.split("\n", -1)) {
      String stripped = raw.strip();
      if (!stripped.isEmpty()) {
        lines.add(stripped);
      }
    }
    return lines;
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
    return parseRightSide(patch).lines();
  }

  /**
   * The right-side line numbers and the right-side code text parsed from one file's patch. The text
   * is the trimmed, non-blank right-side lines in diff order, so {@link #containsContiguous} can
   * test the anchor as a contiguous run; blank lines are dropped from both sides so a blank line
   * inside a block never breaks an otherwise-adjacent match.
   */
  private record RightSide(TreeSet<Integer> lines, List<String> text) {}

  /**
   * Walks a unified-diff patch once, collecting the right-side line numbers and the trimmed text of
   * every right-side line. Additions ({@code '+'}) and context ({@code ' '}) lines exist on the
   * RIGHT side and advance its counter; deletions ({@code '-'}) exist only on the LEFT side and are
   * skipped — which is exactly why content matched against this text distinguishes still-present
   * code from code that was replaced (the replacement's old text survives only as a deletion). The
   * patch is run through {@link PromptTemplateEscaper#neutralizeMarkers} first, because the model's
   * persisted {@code suggestion_old} was quoted from the already-neutralized diff, so the raw side
   * must be neutralized to compare equal (mirrors {@code FindingQuoteValidator}).
   */
  private static RightSide parseRightSide(String patch) {
    var lines = new TreeSet<Integer>();
    var text = new ArrayList<String>();
    if (patch == null || patch.isBlank()) {
      return new RightSide(lines, text);
    }

    var newLine = 0;
    var inHunk = false;
    for (String rawLine : PromptTemplateEscaper.neutralizeMarkers(patch).split("\n", -1)) {
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
        if (marker == '+' || marker == ' ') {
          lines.add(newLine);
          String stripped = rawLine.substring(1).strip();
          if (!stripped.isEmpty()) {
            text.add(stripped);
          }
          newLine++;
        }
      }
    }
    return new RightSide(lines, text);
  }
}
