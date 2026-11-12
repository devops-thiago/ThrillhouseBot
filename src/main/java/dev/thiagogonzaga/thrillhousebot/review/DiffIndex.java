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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every file's {@link PatchIndex}, by path (#1321).
 *
 * <p>The review builds one from the pull request's patches ({@link #ofPatches}), held by its {@link
 * DiffLineResolver}. The quote check builds one from the text a model call was given ({@link
 * #ofFormattedDiff}), since that text can be clipped and a quote is checked against what the call
 * saw; both go through the same parser, so a line has the same numbers in each.
 */
final class DiffIndex {

  private static final String SECTION_HEADER = "### ";

  /** Each file's parsed patch, in the order the diff lists them. */
  private final Map<String, PatchIndex> byFile;

  /**
   * Each file's lines a quote is checked against: its patch lines, plus for a formatted diff the
   * diff-shaped lines no hunk holds ({@link PatchIndex.Builder#looseLines}), in diff order.
   */
  private final Map<String, List<PatchIndex.Line>> quotableByFile;

  /** Every quotable line in diff order, including lines of a section with no file header. */
  private final List<PatchIndex.Line> allLines;

  private DiffIndex(
      Map<String, PatchIndex> byFile,
      Map<String, List<PatchIndex.Line>> quotableByFile,
      List<PatchIndex.Line> allLines) {
    this.byFile = Collections.unmodifiableMap(byFile);
    this.quotableByFile = Collections.unmodifiableMap(quotableByFile);
    this.allLines = List.copyOf(allLines);
  }

  /** The index of a pull request's patches, by the path GitHub lists each under. */
  static DiffIndex ofPatches(Map<String, String> patchesByFile) {
    var byFile = new LinkedHashMap<String, PatchIndex>();
    var quotable = new LinkedHashMap<String, List<PatchIndex.Line>>();
    var all = new ArrayList<PatchIndex.Line>();
    patchesByFile.forEach(
        (file, patch) -> {
          var parsed = PatchIndex.parse(patch);
          byFile.put(file, parsed);
          quotable.put(file, parsed.lines());
          all.addAll(parsed.lines());
        });
    return new DiffIndex(byFile, quotable, all);
  }

  /**
   * The index of a formatted review diff ({@code ReviewDiffFormatter}): a {@code ### path (status,
   * +A -D)} header opens each file's section, whose patch sits in a fenced block. A fence line ends
   * the hunk it closes. Lines before the first header belong to no file and are kept only in the
   * whole-diff list.
   */
  static DiffIndex ofFormattedDiff(String diff) {
    var builders = new LinkedHashMap<String, PatchIndex.Builder>();
    var unscoped = new PatchIndex.Builder();
    var current = unscoped;
    for (String raw : diff.split("\n", -1)) {
      if (raw.startsWith(SECTION_HEADER)) {
        current = builders.computeIfAbsent(sectionFilename(raw), k -> new PatchIndex.Builder());
      } else if (raw.startsWith("```")) {
        current.endHunk();
      } else {
        current.accept(raw);
      }
    }
    var byFile = new LinkedHashMap<String, PatchIndex>();
    var quotable = new LinkedHashMap<String, List<PatchIndex.Line>>();
    var all = new ArrayList<>(quotableLinesOf(unscoped));
    builders.forEach(
        (file, builder) -> {
          byFile.put(file, builder.build());
          var lines = quotableLinesOf(builder);
          quotable.put(file, lines);
          all.addAll(lines);
        });
    return new DiffIndex(byFile, quotable, all);
  }

  /** A builder's hunk lines and loose lines, in the order the diff holds them. */
  private static List<PatchIndex.Line> quotableLinesOf(PatchIndex.Builder builder) {
    var lines = new ArrayList<>(builder.build().lines());
    lines.addAll(builder.looseLines());
    lines.sort(Comparator.comparingInt(PatchIndex.Line::position));
    return lines;
  }

  /** The path in a section header, without the {@code (status, +A -D)} suffix. */
  private static String sectionFilename(String headerLine) {
    String name = headerLine.substring(SECTION_HEADER.length());
    int suffix = name.lastIndexOf(" (");
    return (suffix > 0 ? name.substring(0, suffix) : name).strip();
  }

  /** The files the index holds, by the path the diff lists each under. */
  Map<String, PatchIndex> files() {
    return byFile;
  }

  /**
   * The non-blank lines a quote on {@code file} is checked against, in diff order: that file's
   * section, or the sections its path names when it is shortened or lengthened, or every line when
   * no section matches.
   */
  List<PatchIndex.Line> quotableLines(String file) {
    var sections = sectionKeysFor(file);
    var source = sections.isEmpty() ? allLines : linesOf(sections);
    return source.stream().filter(line -> !line.normalizedText().isEmpty()).toList();
  }

  /** The normalized texts of {@link #quotableLines}. */
  Set<String> quotableText(String file) {
    var texts = new HashSet<String>();
    quotableLines(file).forEach(line -> texts.add(line.normalizedText()));
    return texts;
  }

  private List<PatchIndex.Line> linesOf(List<String> sections) {
    var lines = new ArrayList<PatchIndex.Line>();
    sections.forEach(section -> lines.addAll(quotableByFile.get(section)));
    return lines;
  }

  /**
   * The section keys a finding's file resolves to, or an empty list when it matches none and the
   * caller falls back to the whole diff. An exact match wins; otherwise a path the model shortened
   * or expanded still scopes, and an ambiguous name (Handler.java in several modules) scopes to the
   * union of its candidates, never to the whole diff: a quote from an unrelated file must not
   * validate just because the finding's path was ambiguous.
   */
  private List<String> sectionKeysFor(String file) {
    if (file == null || file.isBlank()) {
      return List.of();
    }
    if (byFile.containsKey(file)) {
      return List.of(file);
    }
    return byFile.keySet().stream()
        .filter(k -> k.endsWith("/" + file) || file.endsWith("/" + k))
        .toList();
  }
}
