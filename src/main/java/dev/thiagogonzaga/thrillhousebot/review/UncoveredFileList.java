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

/**
 * The collapsed list under the partial-review banner naming every file not fully reviewed (#1472).
 * Rendered only when the banner itself had to roll names up as "+N more". It then lists up to
 * {@link #MAX_ENTRIES} entries, those the banner already names included, and ends in a count when
 * entries remain past the cap.
 */
final class UncoveredFileList {

  /** Entries listed before the rest become a count, well inside GitHub's comment size limit. */
  static final int MAX_ENTRIES = 200;

  private UncoveredFileList() {}

  static String section(ReviewResult.TruncationDetail detail) {
    if (!ReviewResult.elidesNames(detail)) {
      return "";
    }
    var entries = entries(detail);
    var section =
        new StringBuilder("<details>\n<summary>All ")
            .append(entries.size())
            .append(" files not fully reviewed</summary>\n\n");
    entries.stream().limit(MAX_ENTRIES).forEach(entry -> section.append(entry).append('\n'));
    if (entries.size() > MAX_ENTRIES) {
      section.append("- and ").append(entries.size() - MAX_ENTRIES).append(" more\n");
    }
    return section.append("\n</details>\n\n").toString();
  }

  private static List<String> entries(ReviewResult.TruncationDetail detail) {
    var entries = new ArrayList<String>();
    detail
        .callFailedFileNames()
        .forEach(
            name -> {
              var cause = detail.callFailureCauses().get(name);
              entries.add(
                  entry(name, "not reviewed, " + (cause == null ? "the call failed" : cause)));
            });
    add(entries, detail.spendCeilingSkippedFileNames(), "not reviewed, token spend ceiling");
    add(entries, detail.omittedFileNames(), "omitted, review budget");
    add(entries, detail.patchlessFileNames(), "no diff content from GitHub");
    add(entries, detail.responseCutFileNames(), "partially reviewed, response cut");
    add(entries, detail.clippedFileNames(), "partially analyzed, review budget");
    return entries;
  }

  private static void add(List<String> entries, List<String> names, String reason) {
    names.forEach(name -> entries.add(entry(name, reason)));
  }

  private static String entry(String name, String reason) {
    return "- `" + MarkdownSafe.inlineCode(name) + "`: " + reason;
  }
}
