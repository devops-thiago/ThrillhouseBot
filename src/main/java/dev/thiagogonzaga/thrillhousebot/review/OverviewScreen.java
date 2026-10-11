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

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import io.quarkus.logging.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Checks the summary call's overview against the material it describes before it is persisted and
 * posted (#964). The call does not see the diff, so what it writes about a file can only be as true
 * as the file list, the findings and the PR text it reads; these are the deterministic checks that
 * catch what it made up:
 *
 * <ul>
 *   <li><b>A file summary's path is mapped onto the changed file it means.</b> One entry citing
 *       {@code src/main/java/dev/thiagosonzaga-placeholder/SecretScanner.java} blanked every row of
 *       a table, and a table whose every row read "Not summarized" had paths the renderer could not
 *       match. A path that is not a changed file goes through {@link DiffLineResolver#diffPathFor}:
 *       the one changed file differing only in case, one path a suffix of the other, or sharing its
 *       file name. Two entries landing on one file keep the one that named it exactly.
 *   <li><b>A placeholder is not a summary.</b> "placeholder", "TODO", "n/a", "…" and the like are
 *       dropped, so the row says it has no summary instead of printing one.
 *   <li><b>A file summary talks about the file, not the review.</b> A clause about findings ("hosts
 *       the two confirmed findings", "no findings in this batch") is review bookkeeping read as the
 *       file's role, and was wrong besides; it is cut, and a summary that is nothing but such a
 *       clause is dropped.
 *   <li><b>A setting named in a file summary is in that file's change.</b> An {@code UPPER_SNAKE}
 *       name the file's patch does not contain is one the summary invented; the clause naming it is
 *       cut (the whole summary when it is the first). A file with no patch to read is not judged.
 *   <li><b>A ticket key in the purpose paragraph is one the PR text or linked issues name.</b> An
 *       invented key such as "THRILLHOUSEBOT-122" takes its sentence out of the paragraph.
 * </ul>
 */
final class OverviewScreen {

  private OverviewScreen() {}

  /** Text that holds a summary's place without saying anything. */
  private static final Pattern PLACEHOLDER =
      Pattern.compile(
          "(?i)^[\\s\\p{Punct}…]*+(?:placeholder|todo|tbd|tba|n/?a|none|null|summary"
              + "|lorem ipsum)?[\\s\\p{Punct}…]*+$");

  /** A clause about the review's findings rather than the file. */
  private static final Pattern FINDINGS_TALK =
      Pattern.compile(
          "(?i)\\b(?:confirmed|verified|reported|open|posted|inline|no|\\d++|one|two|three|four"
              + "|five|several|multiple)\\s++(?:\\w++\\s++)?findings?\\b|\\bthis batch\\b");

  /** A setting or environment variable name: two or more upper-case words joined by underscores. */
  private static final Pattern SETTING_NAME =
      Pattern.compile("(?<![\\w$])[A-Z][A-Z0-9]*+(?:_[A-Z0-9]++)++(?![\\w$])");

  /**
   * A tracker key such as {@code PROJ-123}. Four or more letters, so "SHA-256", "UTF-8" and "CVE-…"
   * are not taken for one.
   */
  private static final Pattern TICKET_KEY =
      Pattern.compile("(?<![\\w-])[A-Z]{4,15}-\\d{1,7}(?![\\w-])");

  /** A sentence end: terminal punctuation followed by a blank. */
  private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s++");

  /**
   * {@code summary} with its file summaries mapped, screened and deduplicated, and its purpose
   * paragraph without sentences naming a ticket key {@code prText} does not hold; {@code summary}
   * itself when nothing changed. A {@code null} summary stays {@code null}.
   *
   * @param files the pull request's changed files, with their patches
   * @param prText the PR title, description and linked-issue text the call read
   */
  static ReviewResponse.Summary screen(
      ReviewResponse.Summary summary, List<GitHubPullRequestClient.FileDiff> files, String prText) {
    if (summary == null) {
      return null;
    }
    var patches = new HashMap<String, String>();
    files.forEach(f -> patches.put(f.filename(), f.patch() == null ? "" : f.patch()));
    var resolver = new DiffLineResolver(Map.of(), patches.keySet());
    var fileSummaries = screenedFileSummaries(summary.fileSummaries(), resolver, patches);
    var purpose = withoutInventedTickets(summary.prPurpose(), prText);
    if (fileSummaries.equals(summary.fileSummaries())
        && Objects.equals(purpose, summary.prPurpose())) {
      return summary;
    }
    return new ReviewResponse.Summary(
        summary.totalFindings(),
        summary.critical(),
        summary.high(),
        summary.medium(),
        summary.low(),
        summary.overallAssessment(),
        purpose,
        summary.descriptionGaps(),
        summary.suggestedLabels(),
        fileSummaries,
        summary.walkthroughDiagram(),
        summary.addressedGaps());
  }

  private static List<ReviewResponse.FileSummary> screenedFileSummaries(
      List<ReviewResponse.FileSummary> entries,
      DiffLineResolver resolver,
      Map<String, String> patches) {
    var kept = new ArrayList<ReviewResponse.FileSummary>(entries.size());
    var exact = new HashSet<String>();
    var dropped = 0;
    // Entries naming a changed file exactly go first, so a near miss never displaces one.
    var ordered = new ArrayList<ReviewResponse.FileSummary>(entries.size());
    entries.stream().filter(e -> resolver.isChangedFile(e.path())).forEach(ordered::add);
    entries.stream().filter(e -> !resolver.isChangedFile(e.path())).forEach(ordered::add);
    for (var entry : ordered) {
      if (entry.path() == null || entry.summary() == null) {
        dropped++;
        continue;
      }
      var path = resolver.diffPathFor(entry.path().strip());
      var text = screenedText(entry.summary(), patches.get(path));
      if (text.isEmpty() || !exact.add(path)) {
        dropped++;
        continue;
      }
      kept.add(
          path.equals(entry.path()) && text.equals(entry.summary())
              ? entry
              : new ReviewResponse.FileSummary(path, text));
    }
    if (!entries.isEmpty() && kept.stream().noneMatch(e -> patches.containsKey(e.path()))) {
      Log.warnf(
          "Summary call returned %d file summary(ies) and none names a changed file; the Changed"
              + " Files rows will read \"Not summarized\"",
          entries.size());
    } else if (dropped > 0) {
      Log.infof("Dropped %d file summary(ies) that failed the overview screen", dropped);
    }
    // The list is new on any change; equal content keeps the caller's no-op check honest.
    return kept.equals(entries) ? entries : List.copyOf(kept);
  }

  /**
   * A file summary's text with its findings talk and invented setting names cut, or {@code ""} when
   * nothing true is left. The first clause is the file's role: when it fails, the summary goes.
   */
  static String screenedText(String summary, String patch) {
    var text = summary.strip();
    if (PLACEHOLDER.matcher(text).matches()) {
      return "";
    }
    var clauses = text.split(";", -1);
    var kept = new ArrayList<String>(clauses.length);
    for (var i = 0; i < clauses.length; i++) {
      var clause = clauses[i].strip();
      if (clause.isEmpty()) {
        continue;
      }
      if (FINDINGS_TALK.matcher(clause).find() || namesAbsentSetting(clause, patch)) {
        if (i == 0) {
          return "";
        }
        continue;
      }
      kept.add(clause);
    }
    return String.join("; ", kept);
  }

  /** Whether {@code clause} names an {@code UPPER_SNAKE} setting {@code patch} does not contain. */
  private static boolean namesAbsentSetting(String clause, String patch) {
    if (patch == null || patch.isBlank()) {
      return false;
    }
    var names = SETTING_NAME.matcher(clause);
    while (names.find()) {
      if (!patch.contains(names.group())) {
        return true;
      }
    }
    return false;
  }

  /** {@code purpose} without the sentences naming a ticket key {@code prText} does not hold. */
  static String withoutInventedTickets(String purpose, String prText) {
    if (purpose == null || !TICKET_KEY.matcher(purpose).find()) {
      return purpose;
    }
    var source = prText == null ? "" : prText.toUpperCase(Locale.ROOT);
    var kept = new ArrayList<String>();
    for (var sentence : SENTENCE_END.split(purpose.strip())) {
      var keys = TICKET_KEY.matcher(sentence);
      var invented = false;
      while (keys.find() && !invented) {
        invented = !source.contains(keys.group());
      }
      if (!invented) {
        kept.add(sentence);
      }
    }
    return String.join(" ", kept);
  }
}
