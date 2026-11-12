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

import dev.thiagogonzaga.thrillhousebot.LogSafe;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Maps AI-reported file line numbers to lines that exist in the PR diff — required for GitHub
 * inline review comments (422 when the line is outside the diff).
 */
public final class DiffLineResolver {

  /**
   * Package-visible construction counter so tests can assert one build per review (and none when a
   * lazy supplier is never touched).
   */
  static final AtomicInteger CONSTRUCTION_COUNT = new AtomicInteger();

  /**
   * How far from the cited line {@link #locateQuote} still takes a quote's location as the one the
   * finding means, matching {@code FindingQuoteValidator}'s disambiguation tolerance.
   */
  static final int NEAR_CITED_LINE = 3;

  /** Which side of the diff a finding's anchor quotes. */
  public enum Side {
    /** The head file: added and context lines. */
    RIGHT,
    /** The base file: the lines the pull request removes, with the context around them. */
    LEFT
  }

  /**
   * Reads a changed file's lines at the review's head commit, so presence can look past the few
   * context lines a patch carries (#1321). Empty when the file cannot be read.
   */
  @FunctionalInterface
  public interface HeadFiles {

    /** Reads nothing: presence is judged from the patches alone. */
    HeadFiles NONE = path -> Optional.empty();

    Optional<List<String>> lines(String path);
  }

  /** The review's one parse of every patch (#1321). */
  private final DiffIndex index;

  private final Map<String, FileSide> byFile;
  private final Set<String> changedFiles;
  private final HeadFiles headFiles;

  public DiffLineResolver(Map<String, String> patchesByFile) {
    this(patchesByFile, List.of());
  }

  /**
   * A resolver over {@code patchesByFile} that also knows every file the pull request changes,
   * including those with no patch to parse (binary, too large to diff), so {@link #diffPathFor} and
   * {@link #isChangedFile} answer for the whole pull request rather than for the patched files
   * only.
   */
  public DiffLineResolver(Map<String, String> patchesByFile, Collection<String> changedFiles) {
    this(patchesByFile, changedFiles, HeadFiles.NONE);
  }

  /**
   * A resolver that also reads a changed file at the head commit through {@code headFiles} when a
   * finding's anchor is not in that file's patch, so an anchor running past the patch's context is
   * not read as gone (#1318, #1321).
   */
  public DiffLineResolver(
      Map<String, String> patchesByFile, Collection<String> changedFiles, HeadFiles headFiles) {
    CONSTRUCTION_COUNT.incrementAndGet();
    this.changedFiles = new HashSet<>(changedFiles);
    this.changedFiles.addAll(patchesByFile.keySet());
    this.headFiles = headFiles;
    this.index = DiffIndex.ofPatches(patchesByFile);
    this.byFile = new HashMap<>();
    index.files().forEach((file, patch) -> byFile.put(file, FileSide.of(patch)));
  }

  /** Every patch of the review, parsed once with both sides. */
  DiffIndex index() {
    return index;
  }

  /**
   * Whether the prior finding's flagged code is still present on the right side (additions and
   * surviving context) of the current diff, or in the head file outside the patch: the predicate
   * the approve backstop relies on to tell a still-open finding from one whose code has been
   * changed away. The same as {@link #isFindingPresent(String, String, Side)} on the {@link
   * Side#RIGHT right side}.
   */
  public boolean isFindingPresent(String file, String anchor) {
    return isFindingPresent(file, anchor, Side.RIGHT);
  }

  /**
   * Whether the prior finding's flagged code is still present.
   *
   * <p>When the finding carries an {@code anchor} (its persisted {@code suggestion_old}, the
   * verbatim code it flagged), presence is judged by <em>content</em>: the anchor's non-blank lines
   * must appear, in order and adjacent, as a contiguous run of lines of <em>one hunk</em> (#1321).
   * This is immune to the two failure modes of a raw line-number test: a force-push/rebase that
   * drifts the still-open code no longer reads as "gone" (under-block), and code that was deleted
   * or replaced no longer reads as "present" just because a surviving context line sits near the
   * stale line number (over-block). Requiring a contiguous run, rather than mere set-membership of
   * each line, also stops a multi-line block whose lines happen to survive scattered in unrelated
   * places, or at the end of one hunk and the start of the next, from reading as still-present.
   *
   * <p>On the {@link Side#RIGHT right side} (code the head holds), a run of added or context lines
   * is present. A patch shows only three lines of context around a change, so an anchor that runs
   * past them (#1318: a quote starting one line before its hunk) is then looked for in the whole
   * head file, read through {@link HeadFiles} for a file the pull request changes, with up to
   * {@link CitedLocationResolver#MAX_BLANK_GAP} blank lines between two quoted lines. An unreadable
   * head file adds nothing, so presence is then what the patch shows.
   *
   * <p>On the {@link Side#LEFT left side} (a finding about code the pull request removes), the
   * finding stands while its lines are still removed: a run of base-side lines of one hunk holding
   * at least one removed line, or, once the patch no longer shows the removal, a head file that
   * does not hold the anchor. A head file holding it again means the removal was undone.
   *
   * <p>One residual the content test cannot resolve: a single generic anchor line (e.g. {@code
   * return null;}) that was changed away at the finding's location but recurs verbatim elsewhere in
   * the same file still reads as present. The stale prior-revision line cannot disambiguate it
   * (that is the drift the check exists to tolerate), so this errs toward holding — a finding kept
   * open that a maintainer can clear by replying on its thread, never the under-block.
   *
   * <p>An anchor the security scan redacted ({@code [redacted: zKXq…, 40 chars]} where a quoted
   * credential stood) is compared in that redacted form ({@link RedactedAnchor}): the diff still
   * carries the literal, and comparing the stored text verbatim read every finding that quoted a
   * secret as gone on the very next round, even on an unchanged head (#932).
   *
   * <p>When the finding has no anchor (a suggestion-less finding, or one whose suggestion was
   * stripped), it falls back to checking whether the file has any changes in the diff. This leans
   * toward holding (returning true) for the backstop, avoiding the drift-fragile under-blocking
   * behavior of a raw line check.
   */
  public boolean isFindingPresent(String file, String anchor, Side side) {
    if (file == null) {
      return false;
    }
    List<Predicate<String>> anchorLines = normalizedAnchorLines(anchor);
    if (anchorLines.isEmpty()) {
      return presentInFileOrVariant(file, FileSide::hasLines, patch -> true);
    }
    return side == Side.LEFT
        ? isRemovalPresent(file, anchorLines)
        : isRightSidePresent(file, anchorLines);
  }

  private boolean isRightSidePresent(String file, List<Predicate<String>> anchorLines) {
    if (presentInFileOrVariant(
        file, FileSide::hasRightText, patch -> !patch.rightRuns(anchorLines).isEmpty())) {
      return true;
    }
    boolean inHeadFile =
        headFileLines(file).filter(lines -> holdsRun(lines, anchorLines)).isPresent();
    if (inHeadFile) {
      Log.infof(
          "A finding's anchor on %s is outside the patch's context but in the head file; it is"
              + " still present",
          LogSafe.oneLine(file));
    }
    return inHeadFile;
  }

  private boolean isRemovalPresent(String file, List<Predicate<String>> anchorLines) {
    return presentInFileOrVariant(
            file, FileSide::hasLeftText, patch -> !patch.removedRuns(anchorLines).isEmpty())
        || headFileLines(file).filter(lines -> !holdsRun(lines, anchorLines)).isPresent();
  }

  /**
   * {@code file}'s lines at the head commit, read only for a file the pull request changes and
   * whose patch leaves it in place; empty for a removed file, a path no patch resolves, or a file
   * that cannot be read.
   */
  private Optional<List<String>> headFileLines(String file) {
    String key = byFile.containsKey(file) ? file : uniqueVariant(file, patch -> true);
    if (key == null || !byFile.get(key).hasLines()) {
      return Optional.empty();
    }
    return headFiles.lines(key);
  }

  /**
   * Whether {@code fileLines} hold the anchor as a run in order, stepping over at most {@link
   * CitedLocationResolver#MAX_BLANK_GAP} blank lines between two of its lines (its own blank lines
   * were dropped), the matching {@link CitedLocationResolver} reads a whole file with.
   */
  private static boolean holdsRun(List<String> fileLines, List<Predicate<String>> anchorLines) {
    for (int start = 0; start < fileLines.size(); start++) {
      if (runStartsAt(fileLines, start, anchorLines)) {
        return true;
      }
    }
    return false;
  }

  private static boolean runStartsAt(
      List<String> fileLines, int start, List<Predicate<String>> anchorLines) {
    int at = start;
    for (int i = 0; i < anchorLines.size(); i++) {
      if (i > 0) {
        at = nextLineAfterBlanks(fileLines, at + 1);
      }
      if (at >= fileLines.size() || !anchorLines.get(i).test(fileLines.get(at).strip())) {
        return false;
      }
    }
    return true;
  }

  private static int nextLineAfterBlanks(List<String> fileLines, int from) {
    int at = from;
    int skipped = 0;
    while (at < fileLines.size()
        && fileLines.get(at).isBlank()
        && skipped < CitedLocationResolver.MAX_BLANK_GAP) {
      at++;
      skipped++;
    }
    return at;
  }

  /**
   * Whether every one of {@code lines} (each already stripped) is an added line of {@code file}
   * within {@code margin} lines of where {@code quote} sits ({@link #locateQuote} for {@code
   * citedLine}) in the current diff (#1066). The same text added elsewhere in the file is not
   * evidence about this location. With the quote not located (absent, ambiguous, or the file in no
   * single diff entry) or no lines to look for, the answer is {@code false}, which keeps a finding
   * held rather than closing it on a guess.
   */
  public boolean addsEveryLineNear(
      String file, String quote, int citedLine, Collection<String> lines, int margin) {
    var at = locateQuote(file, quote, citedLine);
    if (at.isEmpty() || lines.isEmpty()) {
      return false;
    }
    var added = byFile.get(resolveKey(file, FileSide::hasRightText)).added();
    var from = at.get().startLine() - margin;
    var to = at.get().endLine() + margin;
    return lines.stream()
        .allMatch(
            line ->
                added.entrySet().stream()
                    .anyMatch(
                        e -> e.getKey() >= from && e.getKey() <= to && e.getValue().equals(line)));
  }

  /**
   * Retrieves the right-side text of a specific line number, or {@code null} if not in the diff. A
   * model path variant resolves to the one diff file it names; on a suffix collision (two diff
   * files both matching) the answer is {@code null} rather than a guess.
   */
  public String getLineText(String file, int line) {
    if (file == null || line <= 0) {
      return null;
    }
    String key = resolveKey(file, FileSide::hasLines);
    return key == null ? null : byFile.get(key).lineText().get(line);
  }

  /**
   * Whether {@code file} is, exactly, a path this pull request changes: the only paths GitHub opens
   * a review thread on, on a line or on the file.
   */
  public boolean isChangedFile(String file) {
    return file != null && changedFiles.contains(file);
  }

  /**
   * The changed file {@code file} names, or {@code file} unchanged when it is a changed file
   * already or names none of them unambiguously (#1022, #945).
   *
   * <p>GitHub resolves a review thread's path exactly, so a finding citing a path the pull request
   * does not change is refused on its line and on its file alike, and keeps no thread. The model
   * gets the path wrong in three shapes, tried in this order, each only when exactly one changed
   * file fits:
   *
   * <ol>
   *   <li>letter case: {@code external/container} for {@code External/container};
   *   <li>a lost or invented leading directory, one path a suffix of the other: {@code
   *       csharp/docs/CONFIG-CSHARP.md} for {@code docs/CONFIG-CSHARP.md};
   *   <li>a wrong directory with the right file name: {@code
   *       kotlin/src/main/kotlin/dev/tbtest/rooms/RoomsTest.kt} for {@code
   *       kotlin/src/test/kotlin/dev/tbtest/rooms/RoomsTest.kt}.
   * </ol>
   *
   * <p>A shape that fits two or more changed files falls through to the next, and the path is
   * returned as is when none settles it: picking one of several candidates would put the finding on
   * a file it may not mean.
   */
  public String diffPathFor(String file) {
    if (file == null || changedFiles.contains(file)) {
      return file;
    }
    String fileName = fileName(file);
    List<Predicate<String>> shapes =
        List.of(
            candidate -> candidate.equalsIgnoreCase(file),
            candidate -> CitedLocationResolver.sharesPathSuffix(candidate, file),
            candidate -> fileName(candidate).equals(fileName));
    for (Predicate<String> shape : shapes) {
      String match = uniqueChangedFile(shape);
      if (match != null) {
        return match;
      }
    }
    return file;
  }

  /** The one changed file {@code shape} accepts, or {@code null} for none or several. */
  private String uniqueChangedFile(Predicate<String> shape) {
    String match = null;
    for (String candidate : changedFiles) {
      if (shape.test(candidate)) {
        if (match != null) {
          return null;
        }
        match = candidate;
      }
    }
    return match;
  }

  /** The last segment of a slash-separated path. */
  private static String fileName(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }

  /**
   * {@code finding} moved onto {@link #diffPathFor the changed file its path names}, or the same
   * instance when the path needs no change. The change is logged at INFO, so a posted path that
   * differs from the model's can be traced to it.
   */
  public ReviewResponse.Finding withDiffPath(ReviewResponse.Finding finding) {
    String diffPath = diffPathFor(finding.file());
    if (Objects.equals(diffPath, finding.file())) {
      return finding;
    }
    Log.infof(
        "Finding '%s' cites %s, which this pull request does not change; the one changed file that"
            + " path names is %s, so the finding is posted there",
        LogSafe.oneLine(finding.title()),
        LogSafe.oneLine(finding.file()),
        LogSafe.oneLine(diffPath));
    return new ReviewResponse.Finding(
        finding.risk(),
        finding.confidence(),
        diffPath,
        finding.line(),
        finding.title(),
        finding.description(),
        finding.suggestionOld(),
        finding.suggestionNew());
  }

  /**
   * Whether {@code predicate} holds for {@code file}'s own diff entry or, failing that, for any
   * other diff file whose path is a {@link FilePaths#same} variant of it.
   *
   * <p>A <em>non-empty</em> exact entry is authoritative: the file is in the diff under exactly
   * that name, so the result is decided from it alone and no variant is consulted — a same-suffix
   * file that merely shares the path must never resurrect a finding whose code changed away in its
   * own file. The variant fallback runs only when the exact entry is absent <em>or empty</em>: a
   * deletion-only patch has an empty right side under its exact key, so the fallback must still
   * consult the variants that may hold the real data. {@code nonEmpty} says what empty means for
   * the question asked.
   *
   * <p>The fallback inspects <em>every</em> matching variant rather than the first the backing
   * {@link HashMap} happens to iterate, so an ambiguous shortened path — two changed files sharing
   * a suffix — yields the same answer regardless of map order (no more flaky wrong-file binding)
   * and leans toward "present in any candidate". That lean is the safe direction for the approve
   * backstop, which prefers a finding kept open (and clearable by a reply on its thread) over
   * silently approving over a still-open finding.
   */
  private boolean presentInFileOrVariant(
      String file, Predicate<FileSide> nonEmpty, Predicate<FileSide> predicate) {
    FileSide exact = byFile.get(file);
    if (exact != null && nonEmpty.test(exact)) {
      return predicate.test(exact);
    }
    for (var entry : byFile.entrySet()) {
      FileSide value = entry.getValue();
      if (!entry.getKey().equals(file)
          && nonEmpty.test(value)
          && FilePaths.same(file, entry.getKey())
          && predicate.test(value)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Resolves {@code file} to the diff key of its entry: the exact key when that entry is non-empty
   * by {@code nonEmpty}, else the unique non-empty {@link FilePaths#same} variant. Returns {@code
   * null} on absence or genuine ambiguity (two suffix-colliding keys), so a comment, a range or a
   * line text is never bound to the wrong file; the caller treats {@code null} as "no resolvable
   * line", the safe direction.
   */
  private String resolveKey(String file, Predicate<FileSide> nonEmpty) {
    FileSide exact = byFile.get(file);
    if (exact != null && nonEmpty.test(exact)) {
      return file;
    }
    return uniqueVariant(file, nonEmpty);
  }

  /** The one other diff key that is a {@link FilePaths#same} variant of {@code file}, or null. */
  private String uniqueVariant(String file, Predicate<FileSide> nonEmpty) {
    String resolved = null;
    for (var entry : byFile.entrySet()) {
      if (!entry.getKey().equals(file)
          && nonEmpty.test(entry.getValue())
          && FilePaths.same(file, entry.getKey())) {
        if (resolved != null) {
          return null;
        }
        resolved = entry.getKey();
      }
    }
    return resolved;
  }

  /**
   * Trimmed, non-blank lines of an anchor (a finding's {@code suggestion_old}), each as a matcher
   * for a trimmed diff line: plain equality, or — for a line the security scan redacted — the
   * redacted form ({@link RedactedAnchor}), so a finding that quoted a secret is still located
   * (#932).
   */
  private static List<Predicate<String>> normalizedAnchorLines(String anchor) {
    if (anchor == null || anchor.isBlank()) {
      return List.of();
    }
    var lines = new ArrayList<Predicate<String>>();
    for (String raw : anchor.split("\n", -1)) {
      String stripped = raw.strip();
      if (!stripped.isEmpty()) {
        lines.add(RedactedAnchor.lineMatcher(stripped));
      }
    }
    return lines;
  }

  /**
   * The requested line when the diff can take a comment on it, else the nearest commentable line no
   * more than {@link #NEAR_CITED_LINE} lines away (#1320). A line further from every hunk is empty
   * rather than moved into some other hunk: the caller files the finding on the file, where it
   * cannot read as a remark about code it never described.
   */
  public OptionalInt resolveRightSideLine(String file, int requestedLine) {
    if (file == null || requestedLine <= 0) {
      return OptionalInt.empty();
    }
    String key = resolveKey(file, FileSide::hasLines);
    if (key == null) {
      return OptionalInt.empty();
    }
    TreeSet<Integer> lines = byFile.get(key).lines();
    if (lines.contains(requestedLine)) {
      return OptionalInt.of(requestedLine);
    }
    var floor = lines.floor(requestedLine);
    var ceiling = lines.ceiling(requestedLine);
    int floorDistance = floor == null ? Integer.MAX_VALUE : requestedLine - floor;
    int ceilingDistance = ceiling == null ? Integer.MAX_VALUE : ceiling - requestedLine;
    int nearest = floorDistance <= ceilingDistance ? floorDistance : ceilingDistance;
    if (nearest > NEAR_CITED_LINE) {
      return OptionalInt.empty();
    }
    return OptionalInt.of(floorDistance <= ceilingDistance ? floor : ceiling);
  }

  /** An inclusive right-side line range whose endpoints are both lines that exist in the diff. */
  public record LineRange(int startLine, int endLine) {}

  /**
   * Where a quote of removed code sits (#1321): its first and last line on the base (left) side,
   * the lines a {@code side: LEFT} review comment is posted on, and the right-side lines the run
   * stands at, where the removal sits in the new file.
   */
  public record RemovedRange(int startLine, int endLine, int rightStartLine, int rightEndLine) {}

  /**
   * Resolves the right-side line range a finding's {@code suggestion_old} (the verbatim code it
   * replaces) occupies in the diff, so a multi-line replacement can be posted as a multi-line
   * GitHub suggestion that overwrites the whole range rather than a single anchor line.
   *
   * <p>The anchor's trimmed, non-blank lines must appear as a contiguous, in-order run of
   * right-side lines of one hunk (the same matching {@link #isFindingPresent} uses), so the range
   * never straddles two hunks, which GitHub rejects (422); the range spans the first to the last
   * matched line. Returns empty — so the caller falls back to a single-line comment — when the
   * anchor is blank or single-line, the file is not in the diff, the run cannot be located, or the
   * same run appears more than once (ambiguous, so guessing a range is unsafe).
   */
  public Optional<LineRange> resolveSuggestionRange(String file, String suggestionOld) {
    if (file == null) {
      return Optional.empty();
    }
    List<Predicate<String>> anchorLines = normalizedAnchorLines(suggestionOld);
    String key = resolveKey(file, FileSide::hasRightText);
    if (anchorLines.size() < 2 || key == null) {
      return Optional.empty();
    }
    List<LineRange> runs = byFile.get(key).rightRuns(anchorLines);
    return runs.size() == 1 ? Optional.of(runs.getFirst()) : Optional.empty();
  }

  /**
   * Where {@code quote} (a finding's {@code suggestion_old}) sits on the right side of {@code
   * file}'s diff, as the first to the last matched line, chosen for the line the finding cites
   * (#924).
   *
   * <p>The quote's trimmed, non-blank lines must appear as a contiguous, in-order run of right-side
   * lines of one hunk, as in {@link #isFindingPresent}. Of several such runs, the one holding
   * {@code citedLine} wins, then the one within {@link #NEAR_CITED_LINE} lines of it (the quote
   * validator's own tolerance), then the only one. Empty when the quote is blank, the file has no
   * right side, the quote is not there (a removed line is not: see {@link #locateRemovedQuote}), or
   * the runs leave the choice open: a suggestion posted on a guessed location replaces code it does
   * not quote.
   */
  public Optional<LineRange> locateQuote(String file, String quote, int citedLine) {
    if (file == null) {
      return Optional.empty();
    }
    List<Predicate<String>> quoteLines = normalizedAnchorLines(quote);
    String key = resolveKey(file, FileSide::hasRightText);
    if (quoteLines.isEmpty() || key == null) {
      return Optional.empty();
    }
    List<LineRange> runs = byFile.get(key).rightRuns(quoteLines);
    List<LineRange> near = nearCitedLine(runs, LineRange::startLine, LineRange::endLine, citedLine);
    return onlyOne(near.isEmpty() ? runs : near);
  }

  /**
   * Where {@code quote} sits among the lines {@code file}'s patch removes (#1321): a contiguous,
   * in-order run of base-side lines of one hunk, holding at least one removed line, whose
   * right-side position is {@code citedLine} or within {@link #NEAR_CITED_LINE} lines of it.
   *
   * <p>Unlike {@link #locateQuote}, a run the cited line does not select is never taken just
   * because it is the only one: removed code is often restated nearby (a replaced line's old and
   * new form share most of their text), and a finding posted on removed lines it does not describe
   * reads as a remark about code it never meant.
   */
  public Optional<RemovedRange> locateRemovedQuote(String file, String quote, int citedLine) {
    if (file == null) {
      return Optional.empty();
    }
    List<Predicate<String>> quoteLines = normalizedAnchorLines(quote);
    String key = resolveKey(file, FileSide::hasLeftText);
    if (quoteLines.isEmpty() || key == null) {
      return Optional.empty();
    }
    List<RemovedRange> runs = byFile.get(key).removedRuns(quoteLines);
    return onlyOne(
        nearCitedLine(runs, RemovedRange::rightStartLine, RemovedRange::rightEndLine, citedLine));
  }

  /**
   * The runs {@code citedLine} selects: those holding it, else those within {@link
   * #NEAR_CITED_LINE} lines of it, else none.
   */
  private static <R> List<R> nearCitedLine(
      List<R> runs, ToIntFunction<R> start, ToIntFunction<R> end, int citedLine) {
    for (int tolerance : new int[] {0, NEAR_CITED_LINE}) {
      List<R> near =
          runs.stream()
              .filter(
                  run ->
                      citedLine >= start.applyAsInt(run) - tolerance
                          && citedLine <= end.applyAsInt(run) + tolerance)
              .toList();
      if (!near.isEmpty()) {
        return near;
      }
    }
    return List.of();
  }

  /** The one run, or empty when there is none or the choice is open. */
  private static <R> Optional<R> onlyOne(List<R> runs) {
    return runs.size() == 1 ? Optional.of(runs.getFirst()) : Optional.empty();
  }

  /**
   * The non-blank right-side lines of {@code file} from {@code range}'s start to its end as the
   * diff holds them, indentation included (#1197): the lines a suggestion posted on that range
   * replaces. Empty when the file has no right side.
   */
  public List<String> rightSideLines(String file, LineRange range) {
    String key = file == null ? null : resolveKey(file, FileSide::hasRightText);
    if (key == null) {
      return List.of();
    }
    Map<Integer, String> lineText = byFile.get(key).lineText();
    var lines = new ArrayList<String>();
    for (int line = range.startLine(); line <= range.endLine(); line++) {
      String text = lineText.get(line);
      if (text != null && !text.isBlank()) {
        lines.add(text);
      }
    }
    return lines;
  }

  /**
   * One file's patch in the shapes the lookups read, taken from its {@link PatchIndex}.
   *
   * @param lines the right-side line numbers, the lines a comment can be posted on
   * @param lineText each right-side line's text by number, as the diff holds it
   * @param added each added line's stripped text by number
   * @param rightHunks each hunk's non-blank right-side lines; blank lines are dropped so a blank
   *     line inside a block never breaks an otherwise adjacent match, and a run never continues
   *     from one hunk into the next
   * @param leftHunks each hunk's non-blank left-side lines, the same way
   */
  private record FileSide(
      TreeSet<Integer> lines,
      Map<Integer, String> lineText,
      Map<Integer, String> added,
      List<List<PatchIndex.Line>> rightHunks,
      List<List<PatchIndex.Line>> leftHunks) {

    static FileSide of(PatchIndex patch) {
      var lines = new TreeSet<Integer>();
      var lineText = new HashMap<Integer, String>();
      var added = new HashMap<Integer, String>();
      for (PatchIndex.Line line : patch.rightSide()) {
        lines.add(line.rightLine());
        lineText.put(line.rightLine(), line.text());
        if (line.added()) {
          added.put(line.rightLine(), line.normalizedText());
        }
      }
      var rightHunks = new ArrayList<List<PatchIndex.Line>>();
      var leftHunks = new ArrayList<List<PatchIndex.Line>>();
      for (List<PatchIndex.Line> hunk : patch.hunks()) {
        rightHunks.add(nonBlank(hunk, PatchIndex.Line::onRight));
        leftHunks.add(nonBlank(hunk, PatchIndex.Line::onLeft));
      }
      return new FileSide(lines, lineText, added, rightHunks, leftHunks);
    }

    private static List<PatchIndex.Line> nonBlank(
        List<PatchIndex.Line> hunk, Predicate<PatchIndex.Line> side) {
      return hunk.stream().filter(side).filter(line -> !line.normalizedText().isEmpty()).toList();
    }

    boolean hasLines() {
      return !lines.isEmpty();
    }

    boolean hasRightText() {
      return rightHunks.stream().anyMatch(hunk -> !hunk.isEmpty());
    }

    boolean hasLeftText() {
      return leftHunks.stream().anyMatch(hunk -> !hunk.isEmpty());
    }

    /** Every right-side run of one hunk matching {@code needle}, as its line range. */
    List<LineRange> rightRuns(List<Predicate<String>> needle) {
      var runs = new ArrayList<LineRange>();
      for (List<PatchIndex.Line> hunk : rightHunks) {
        for (int start : runStarts(hunk, needle)) {
          runs.add(
              new LineRange(
                  hunk.get(start).rightLine(), hunk.get(start + needle.size() - 1).rightLine()));
        }
      }
      return runs;
    }

    /** Every left-side run of one hunk matching {@code needle} and holding a removed line. */
    List<RemovedRange> removedRuns(List<Predicate<String>> needle) {
      var runs = new ArrayList<RemovedRange>();
      for (List<PatchIndex.Line> hunk : leftHunks) {
        for (int start : runStarts(hunk, needle)) {
          List<PatchIndex.Line> run = hunk.subList(start, start + needle.size());
          if (run.stream().anyMatch(PatchIndex.Line::deleted)) {
            runs.add(
                new RemovedRange(
                    run.getFirst().leftLine(),
                    run.getLast().leftLine(),
                    run.getFirst().rightLine(),
                    run.getLast().rightLine()));
          }
        }
      }
      return runs;
    }

    private static List<Integer> runStarts(
        List<PatchIndex.Line> hunk, List<Predicate<String>> needle) {
      var starts = new ArrayList<Integer>();
      for (int i = 0; i + needle.size() <= hunk.size(); i++) {
        if (matchesAt(hunk, i, needle)) {
          starts.add(i);
        }
      }
      return starts;
    }

    private static boolean matchesAt(
        List<PatchIndex.Line> hunk, int offset, List<Predicate<String>> needle) {
      for (int j = 0; j < needle.size(); j++) {
        if (!needle.get(j).test(hunk.get(offset + j).normalizedText())) {
          return false;
        }
      }
      return true;
    }
  }
}
