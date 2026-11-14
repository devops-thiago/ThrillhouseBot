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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One review's related context (#55): the repository files outside the diff that were picked and
 * read, each clipped to the regions the changed code uses. The review call gets all of it as one
 * fenced section ({@link #section()}); the verifier gets, per finding, the excerpt of a file that
 * finding names ({@link #noteFor}).
 *
 * @param files the files read, in the order they were picked
 * @param selectionCalls the model calls the selection made (0 or 1), counted in {@code
 *     REVIEW_MAX_AI_CALLS}
 */
public record RelatedContext(List<RelatedFile> files, int selectionCalls) {

  /** No related context: the feature is off, nothing was found, or the selection failed. */
  public static final RelatedContext NONE = new RelatedContext(List.of(), 0);

  /** Characters of excerpt one finding's note carries, before the evidence budget's own cap. */
  static final int MAX_NOTE_EXCERPT_CHARS = 900;

  /** A file name without its extension shorter than this is too common a word to match on. */
  static final int MIN_DISTINCT_STEM = 4;

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{3,}");

  public RelatedContext {
    files = List.copyOf(files);
  }

  /**
   * A run of consecutive lines of a file.
   *
   * @param firstLine the 1-based number of the first line
   * @param lines the lines, each already clipped to its character cap
   */
  public record Region(int firstLine, List<String> lines) {
    public Region {
      lines = List.copyOf(lines);
    }

    int lastLine() {
      return firstLine + lines.size() - 1;
    }

    String render() {
      var sb = new StringBuilder("Lines ").append(firstLine).append('-').append(lastLine());
      sb.append(":\n");
      for (var i = 0; i < lines.size(); i++) {
        sb.append(firstLine + i).append(" | ").append(lines.get(i)).append('\n');
      }
      return sb.toString();
    }
  }

  /**
   * One file that was read.
   *
   * @param path its repository path
   * @param reason why the selection picked it
   * @param referencedBy the changed files that refer to it
   * @param totalLines how many lines the file has at the head commit
   * @param regions the parts shown, in file order
   */
  public record RelatedFile(
      String path, String reason, List<String> referencedBy, int totalLines, List<Region> regions) {
    public RelatedFile {
      referencedBy = List.copyOf(referencedBy);
      regions = List.copyOf(regions);
    }

    String heading() {
      var sb = new StringBuilder("File: ").append(path).append(" (").append(reason);
      if (!referencedBy.isEmpty()) {
        sb.append("; referenced by ").append(String.join(", ", referencedBy));
      }
      return sb.append("; ").append(totalLines).append(" lines in all)\n").toString();
    }

    String render() {
      var sb = new StringBuilder(heading());
      regions.forEach(region -> sb.append(region.render()));
      return sb.toString().stripTrailing();
    }

    RelatedFile withRegions(List<Region> kept) {
      return new RelatedFile(path, reason, referencedBy, totalLines, kept);
    }
  }

  boolean isEmpty() {
    return files.isEmpty();
  }

  /** The section the review call reads, unfenced; {@code ""} when no file was read. */
  public String section() {
    return String.join("\n\n", files.stream().map(RelatedFile::render).toList());
  }

  /**
   * What the verifier is told for one finding: the excerpt of the first related file the finding
   * names, with the regions that share the most identifiers with the finding first; {@code null}
   * when the finding names none. A file is named by its path, its file name, or, when it is
   * distinctive enough, its file name without the extension as a whole word.
   */
  String noteFor(ReviewResponse.Finding finding) {
    if (files.isEmpty() || finding == null) {
      return null;
    }
    var text =
        String.join(
            "\n",
            nullToEmpty(finding.title()),
            nullToEmpty(finding.description()),
            nullToEmpty(finding.suggestionNew()));
    for (var file : files) {
      if (names(text, file.path())) {
        return note(file, identifiersOf(text));
      }
    }
    return null;
  }

  private static String note(RelatedFile file, Set<String> identifiers) {
    var ranked = new ArrayList<>(file.regions());
    ranked.sort(
        Comparator.comparingInt((Region region) -> -shared(region, identifiers))
            .thenComparingInt(Region::firstLine));
    var sb =
        new StringBuilder("Related context this review supplied: `")
            .append(file.path())
            .append("` is repository code the pull request does not change, read at its head")
            .append(" commit and shown to the review pass as read-only context (")
            .append(file.reason())
            .append("). Only part of the file is shown.\n");
    var room = MAX_NOTE_EXCERPT_CHARS;
    for (var region : ranked) {
      var rendered = region.render();
      if (rendered.length() > room) {
        if (room == MAX_NOTE_EXCERPT_CHARS) {
          sb.append(rendered, 0, room).append("…\n");
        }
        break;
      }
      sb.append(rendered);
      room -= rendered.length();
    }
    return sb.toString().stripTrailing();
  }

  private static int shared(Region region, Set<String> identifiers) {
    var count = 0;
    var seen = new HashSet<String>();
    for (var line : region.lines()) {
      var matcher = IDENTIFIER.matcher(line);
      while (matcher.find()) {
        var word = matcher.group();
        if (identifiers.contains(word) && seen.add(word)) {
          count++;
        }
      }
    }
    return count;
  }

  private static Set<String> identifiersOf(String text) {
    var identifiers = new HashSet<String>();
    var matcher = IDENTIFIER.matcher(text);
    while (matcher.find()) {
      identifiers.add(matcher.group());
    }
    return identifiers;
  }

  /** Whether {@code text} names the file at {@code path}. */
  static boolean names(String text, String path) {
    if (text.contains(path)) {
      return true;
    }
    var name = path.substring(path.lastIndexOf('/') + 1);
    if (wholeWord(text, name)) {
      return true;
    }
    var dot = name.lastIndexOf('.');
    var stem = dot <= 0 ? name : name.substring(0, dot);
    var distinctive =
        stem.length() >= MIN_DISTINCT_STEM
            && (stem.indexOf('_') >= 0 || !stem.equals(stem.toLowerCase(java.util.Locale.ROOT)));
    return distinctive && wholeWord(text, stem);
  }

  private static boolean wholeWord(String text, String word) {
    var from = 0;
    while (true) {
      var at = text.indexOf(word, from);
      if (at < 0) {
        return false;
      }
      var end = at + word.length();
      var startsWord = at == 0 || !isWordChar(text.charAt(at - 1));
      var endsWord = end == text.length() || !isWordChar(text.charAt(end));
      if (startsWord && endsWord) {
        return true;
      }
      from = at + 1;
    }
  }

  private static boolean isWordChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  private static String nullToEmpty(String text) {
    return text == null ? "" : text;
  }
}
