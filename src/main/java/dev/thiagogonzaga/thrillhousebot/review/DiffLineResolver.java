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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.OptionalInt;
import java.util.TreeSet;
import java.util.function.Predicate;
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
  private final Map<String, Map<Integer, String>> rightSideLineTextByFile;

  public DiffLineResolver(Map<String, String> patchesByFile) {
    this.rightSideLinesByFile = new HashMap<>();
    this.rightSideTextByFile = new HashMap<>();
    this.rightSideLineTextByFile = new HashMap<>();
    patchesByFile.forEach(
        (file, patch) -> {
          RightSide rightSide = parseRightSide(patch);
          rightSideLinesByFile.put(file, rightSide.lines());
          rightSideTextByFile.put(file, rightSide.text());
          rightSideLineTextByFile.put(file, rightSide.lineText());
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
   * stripped), it falls back to checking whether the file has any changes in the diff. This leans
   * toward holding (returning true) for the downgrade-only backstop, avoiding the drift-fragile
   * under-blocking behavior of a raw line check.
   */
  public boolean isFindingPresent(String file, String anchor) {
    if (file == null) {
      return false;
    }
    List<String> anchorLines = normalizedAnchorLines(anchor);
    if (anchorLines.isEmpty()) {
      return presentInFileOrVariant(rightSideLinesByFile, file, lines -> true);
    }
    return presentInFileOrVariant(
        rightSideTextByFile, file, text -> containsContiguous(text, anchorLines));
  }

  /**
   * Retrieves the right-side text of a specific line number, or {@code null} if not in the diff.
   */
  public String getLineText(String file, int line) {
    if (file == null || line <= 0) {
      return null;
    }
    Map<Integer, String> exact = rightSideLineTextByFile.get(file);
    if (exact != null && !exact.isEmpty()) {
      return exact.get(line);
    }
    for (var entry : rightSideLineTextByFile.entrySet()) {
      var value = entry.getValue();
      if (!entry.getKey().equals(file)
          && !value.isEmpty()
          && FilePaths.same(file, entry.getKey())) {
        return value.get(line);
      }
    }
    return null;
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
   * distant line. The file is matched with {@link FilePaths#same}, so a model path variant still
   * resolves to the diff's filename.
   */
  public boolean isLineInDiff(String file, int line, int tolerance) {
    if (file == null || line <= 0) {
      return false;
    }
    return presentInFileOrVariant(
        rightSideLinesByFile, file, lines -> lineWithinTolerance(lines, line, tolerance));
  }

  /** Whether {@code line} — or a member within {@code tolerance} of it — is in {@code lines}. */
  private static boolean lineWithinTolerance(NavigableSet<Integer> lines, int line, int tolerance) {
    Integer floor = lines.floor(line);
    Integer ceiling = lines.ceiling(line);
    return (floor != null && line - floor <= tolerance)
        || (ceiling != null && ceiling - line <= tolerance);
  }

  /**
   * Whether {@code predicate} holds for {@code file}'s own diff entry or, failing that, for any
   * other diff file whose path is a {@link FilePaths#same} variant of it.
   *
   * <p>A <em>non-empty</em> exact entry is authoritative: the file is in the diff under exactly
   * that name, so the result is decided from it alone and no variant is consulted — a same-suffix
   * file that merely shares the path must never resurrect a finding whose code changed away in its
   * own file. The variant fallback runs only when the exact entry is absent <em>or empty</em>: a
   * deletion-only patch stores an empty right side under its exact key, which previously
   * short-circuited the fallback and masked a variant that held the real data (#132a).
   *
   * <p>The fallback inspects <em>every</em> matching variant rather than the first the backing
   * {@link HashMap} happens to iterate, so an ambiguous shortened path — two changed files sharing
   * a suffix — yields the same answer regardless of map order (#132b, no more flaky wrong-file
   * binding) and leans toward "present in any candidate". That lean is the safe direction for the
   * downgrade-only #118 approve backstop, which prefers a needless APPROVE→COMMENT over silently
   * approving over a still-open finding.
   */
  private static <V extends Collection<?>> boolean presentInFileOrVariant(
      Map<String, V> byFile, String file, Predicate<V> predicate) {
    V exact = byFile.get(file);
    if (exact != null && !exact.isEmpty()) {
      return predicate.test(exact);
    }
    for (var entry : byFile.entrySet()) {
      V value = entry.getValue();
      if (!entry.getKey().equals(file)
          && !value.isEmpty()
          && FilePaths.same(file, entry.getKey())
          && predicate.test(value)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Resolves {@code file} to its non-empty exact right-side line set or, failing that, to the
   * unique non-empty {@link FilePaths#same} variant set.
   *
   * <p>A non-empty exact entry is authoritative and is returned immediately. The variant fallback
   * runs when the exact entry is absent or empty. If multiple different variant keys match, an
   * empty set is returned to avoid placing a comment on the wrong file on genuine ambiguity — the
   * caller treats empty exactly as "no resolvable line", the safe direction. The result is never
   * {@code null}.
   */
  private TreeSet<Integer> resolveRightSideLinesForFileOrVariant(String file) {
    TreeSet<Integer> exact = rightSideLinesByFile.get(file);
    if (exact != null && !exact.isEmpty()) {
      return exact;
    }
    TreeSet<Integer> resolved = null;
    for (var entry : rightSideLinesByFile.entrySet()) {
      TreeSet<Integer> value = entry.getValue();
      if (!entry.getKey().equals(file)
          && !value.isEmpty()
          && FilePaths.same(file, entry.getKey())) {
        if (resolved != null) {
          // Genuine ambiguity (two suffix-colliding diff keys) - empty rather than guess.
          return new TreeSet<>();
        }
        resolved = value;
      }
    }
    return resolved != null ? resolved : new TreeSet<>();
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
    TreeSet<Integer> lines = resolveRightSideLinesForFileOrVariant(file);
    if (lines.isEmpty()) {
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
  private record RightSide(
      TreeSet<Integer> lines, List<String> text, Map<Integer, String> lineText) {}

  /**
   * Walks a unified-diff patch once, collecting the right-side line numbers and the trimmed text of
   * every right-side line. Additions ({@code '+'}) and context ({@code ' '}) lines exist on the
   * RIGHT side and advance its counter; deletions ({@code '-'}) exist only on the LEFT side and are
   * skipped — which is exactly why content matched against this text distinguishes still-present
   * code from code that was replaced (the replacement's old text survives only as a deletion). The
   * model now quotes {@code suggestion_old} from the byte-exact fenced diff (see {@link
   * PromptTemplateEscaper#fence}), so the raw patch is parsed directly with no neutralization
   * (mirrors {@code FindingQuoteValidator}).
   */
  private static RightSide parseRightSide(String patch) {
    var lines = new TreeSet<Integer>();
    var text = new ArrayList<String>();
    var lineText = new HashMap<Integer, String>();
    if (patch == null || patch.isBlank()) {
      return new RightSide(lines, text, lineText);
    }

    var newLine = 0;
    var inHunk = false;
    for (String rawLine : patch.split("\n", -1)) {
      OptionalInt hunkStart = hunkStart(rawLine);
      if (hunkStart.isPresent()) {
        newLine = hunkStart.getAsInt();
        inHunk = true;
        continue;
      }
      if (inHunk && isRightSideLine(rawLine)) {
        appendRightSide(lines, text, lineText, newLine, rawLine);
        newLine++;
      }
    }
    return new RightSide(lines, text, lineText);
  }

  /** The 1-based right-side start line of a hunk header, or empty if the line is not one. */
  private static OptionalInt hunkStart(String rawLine) {
    if (!rawLine.startsWith("@@")) {
      return OptionalInt.empty();
    }
    var matcher = HUNK_HEADER.matcher(rawLine);
    return matcher.find()
        ? OptionalInt.of(Integer.parseInt(matcher.group(1)))
        : OptionalInt.empty();
  }

  /** Whether the line exists on the diff's right side — an addition or a surviving context line. */
  private static boolean isRightSideLine(String rawLine) {
    if (rawLine.isEmpty()) {
      return false;
    }
    var marker = rawLine.charAt(0);
    return marker == '+' || marker == ' ';
  }

  /** Records one right-side line under {@code newLine}: its number, trimmed text, and raw text. */
  private static void appendRightSide(
      TreeSet<Integer> lines,
      List<String> text,
      Map<Integer, String> lineText,
      int newLine,
      String rawLine) {
    lines.add(newLine);
    String content = rawLine.substring(1);
    String stripped = content.strip();
    if (!stripped.isEmpty()) {
      text.add(stripped);
    }
    lineText.put(newLine, content);
  }
}
