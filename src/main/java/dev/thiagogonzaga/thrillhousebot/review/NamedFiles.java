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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * The other changed files a finding names in its title or description (#1316). A finding that says
 * a document and the code disagree sits on one of the two files and can be fixed in the other, so
 * its own file being unchanged does not show it is still open.
 */
final class NamedFiles {

  private NamedFiles() {}

  /**
   * Whether a changed file other than {@code finding}'s own, named in its title or description,
   * changed since the prior round at {@code round} (newest first), the round that raised it.
   */
  static boolean changedSince(
      int round, ReviewResponse.Finding finding, DiffLineResolver lineResolver) {
    for (var path : named(text(finding), lineResolver.changedFiles())) {
      if (!path.equals(finding.file())
          && lineResolver.fileChangeSince(round, path) == DiffLineResolver.FileChange.CHANGED) {
        return true;
      }
    }
    return false;
  }

  /**
   * The paths of {@code changedFiles} that {@code text} names: by the whole path, or by the file
   * name when no other changed file has the same name. A path with no directory counts only as a
   * file name. Prose does not keep a file's case, so names are compared ignoring case, and a path
   * or name two changed files share once case is ignored names neither. A name with no extension is
   * too common a word to read as a file.
   */
  static List<String> named(String text, Collection<String> changedFiles) {
    var named = new ArrayList<String>();
    if (text.isBlank()) {
      return named;
    }
    var lowered = lower(text);
    var paths = new HashMap<String, Integer>();
    var names = new HashMap<String, Integer>();
    for (var path : changedFiles) {
      paths.merge(lower(path), 1, Integer::sum);
      names.merge(lower(fileName(path)), 1, Integer::sum);
    }
    for (var path : changedFiles) {
      var lowerPath = lower(path);
      var name = lower(fileName(path));
      if ((path.contains("/") && paths.get(lowerPath) == 1 && mentions(lowered, lowerPath))
          || (name.contains(".") && names.get(name) == 1 && mentions(lowered, name))) {
        named.add(path);
      }
    }
    return named;
  }

  private static String lower(String s) {
    return s.toLowerCase(Locale.ROOT);
  }

  private static String text(ReviewResponse.Finding finding) {
    var title = finding.title() == null ? "" : finding.title();
    var description = finding.description() == null ? "" : finding.description();
    return title + "\n" + description;
  }

  /**
   * Whether {@code text} holds {@code name} as a name of its own: not preceded by a name character
   * (a slash may precede it, as in a longer path), and not followed by one or by a dot that runs on
   * into another name.
   */
  private static boolean mentions(String text, String name) {
    for (var at = text.indexOf(name); at >= 0; at = text.indexOf(name, at + 1)) {
      var end = at + name.length();
      if ((at == 0 || !isNameChar(text.charAt(at - 1)))
          && (end == text.length() || !continuesName(text, end))) {
        return true;
      }
    }
    return false;
  }

  private static boolean continuesName(String text, int at) {
    var c = text.charAt(at);
    if (c == '.') {
      return at + 1 < text.length() && Character.isLetterOrDigit(text.charAt(at + 1));
    }
    return isNameChar(c);
  }

  private static boolean isNameChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
  }

  private static String fileName(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }
}
