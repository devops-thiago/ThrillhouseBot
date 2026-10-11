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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 *   <li><b>A placeholder is not a summary.</b> "placeholder", "TBD", "n/a", "…" and the like are
 *       dropped, so the row says it has no summary instead of printing one.
 *   <li><b>A file summary talks about the file, not the review.</b> A clause about findings ("hosts
 *       the two confirmed findings", "no findings in this batch") is review bookkeeping read as the
 *       file's role, and was wrong besides; such a clause is cut, and a summary whose first clause
 *       is one is dropped whole, since the first clause is the file's role.
 *   <li><b>A setting named in a file summary is in that file's change.</b> An {@code UPPER_SNAKE}
 *       name that neither the file's patch nor the findings and PR text contain is one the summary
 *       invented; the clause naming it is cut (the whole summary when it is the first). A file with
 *       no patch to read is not judged.
 *   <li><b>A ticket key in the purpose paragraph is one the PR text or linked issues name.</b> An
 *       invented key such as "THRILLHOUSEBOT-122" takes its sentence out of the paragraph.
 * </ul>
 */
final class OverviewScreen {

  private OverviewScreen() {}

  /** Text that holds a summary's place without saying anything, once punctuation is removed. */
  private static final Set<String> PLACEHOLDERS =
      Set.of(
          "",
          "placeholder",
          "todo",
          "tbd",
          "tba",
          "na",
          "n a",
          "none",
          "null",
          "summary",
          "lorem ipsum");

  /**
   * Words that, a word or two before "finding(s)", make a clause about the review: a count, or the
   * verifier's verdict. A word that also names a file's role ("Loads open findings from the store",
   * "Posts reported findings") is not one of them.
   */
  private static final Set<String> FINDING_QUALIFIERS =
      Set.of(
          "confirmed",
          "verified",
          "no",
          "one",
          "two",
          "three",
          "four",
          "five",
          "several",
          "multiple");

  /**
   * Words that, a word or two after a clause-opening "Findings", place the review's findings
   * ("Findings are reported inline"). A file's role opens with what the file does, not with
   * "Findings", so after it these read as bookkeeping; the {@link #FINDING_QUALIFIERS} do too.
   */
  private static final Set<String> FINDING_PLACEMENTS =
      Set.of("reported", "open", "posted", "raised", "tracked", "inline");

  private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

  /** A setting or environment variable name: two or more upper-case words joined by underscores. */
  private static final Pattern SETTING_NAME =
      Pattern.compile("(?<![\\w$])[A-Z][A-Z0-9]*+(?:_[A-Z0-9]++)++(?![\\w$])");

  /**
   * A tracker key such as {@code PROJ-123}. Four or more letters, so "SHA-256", "UTF-8" and "CVE-…"
   * are not taken for one.
   */
  private static final Pattern TICKET_KEY =
      Pattern.compile("(?<![\\w-])[A-Z]{4,15}-\\d{1,7}(?![\\w-])");

  /**
   * Standards and protocol names that take the tracker-key shape with a number: "HTTP-404",
   * "FIPS-140". A sentence naming one is not taken for one naming an invented ticket.
   */
  private static final Set<String> STANDARD_PREFIXES =
      Set.of("HTTP", "HTTPS", "FIPS", "IEEE", "ECMA", "ANSI", "MPEG", "SMTP", "OAUTH");

  /** A sentence end: terminal punctuation followed by a blank. */
  private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s++");

  /**
   * {@code summary} with its file summaries mapped, screened and deduplicated, and its purpose
   * paragraph without sentences naming a ticket key {@code known} does not hold; {@code summary}
   * itself when nothing changed. A {@code null} summary stays {@code null}.
   *
   * @param files the pull request's changed files, with their patches
   * @param known the text the call read that may name settings and keys: the PR title and
   *     description, the linked issues, and the findings
   */
  static ReviewResponse.Summary screen(
      ReviewResponse.Summary summary, List<GitHubPullRequestClient.FileDiff> files, String known) {
    if (summary == null) {
      return null;
    }
    var patches = new HashMap<String, String>();
    files.forEach(f -> patches.put(f.filename(), f.patch() == null ? "" : f.patch()));
    var resolver = new DiffLineResolver(Map.of(), patches.keySet());
    var source = known == null ? "" : known;
    var fileSummaries = screenedFileSummaries(summary.fileSummaries(), resolver, patches, source);
    var purpose = withoutInventedTickets(summary.prPurpose(), source);
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
      Map<String, String> patches,
      String known) {
    var kept = new ArrayList<ReviewResponse.FileSummary>(entries.size());
    var placed = new HashSet<String>();
    // Entries naming a changed file exactly go first, so a near miss never displaces one.
    var ordered = new ArrayList<ReviewResponse.FileSummary>(entries.size());
    entries.stream().filter(e -> resolver.isChangedFile(e.path())).forEach(ordered::add);
    entries.stream().filter(e -> !resolver.isChangedFile(e.path())).forEach(ordered::add);
    for (var entry : ordered) {
      var screened = screenedEntry(entry, resolver, patches, known);
      if (screened != null && placed.add(screened.path())) {
        kept.add(screened.equals(entry) ? entry : screened);
      }
    }
    var dropped = entries.size() - kept.size();
    if (!entries.isEmpty() && kept.isEmpty()) {
      Log.warnf(
          "Dropped all %d file summary(ies) at the overview screen; the Changed Files rows will"
              + " read \"Not summarized\"",
          entries.size());
    } else if (!kept.isEmpty() && kept.stream().noneMatch(e -> patches.containsKey(e.path()))) {
      Log.warnf(
          "Summary call returned %d file summary(ies) and none is left on a changed file (%d"
              + " failed the overview screen, the rest name no changed file); the Changed Files"
              + " rows will read \"Not summarized\"",
          entries.size(), dropped);
    } else if (dropped > 0) {
      Log.infof("Dropped %d file summary(ies) that failed the overview screen", dropped);
    }
    // The list is new on any change; equal content keeps the caller's no-op check honest.
    return kept.equals(entries) ? entries : List.copyOf(kept);
  }

  /** {@code entry} on the changed file it means with its text screened, or {@code null}. */
  private static ReviewResponse.FileSummary screenedEntry(
      ReviewResponse.FileSummary entry,
      DiffLineResolver resolver,
      Map<String, String> patches,
      String known) {
    if (entry.path() == null || entry.summary() == null) {
      return null;
    }
    var path = resolver.diffPathFor(entry.path().strip());
    var text = screenedText(entry.summary(), patches.get(path), known);
    return text.isEmpty() ? null : new ReviewResponse.FileSummary(path, text);
  }

  /**
   * A file summary's text with its findings talk and invented setting names cut, or {@code ""} when
   * nothing true is left. The first clause is the file's role: when it fails, the summary goes. A
   * setting name {@code known} holds (a finding or the PR text named it) is not invented, even when
   * it sits on a line the patch does not show.
   */
  static String screenedText(String summary, String patch, String known) {
    var text = summary.strip();
    if (isPlaceholder(text)) {
      return "";
    }
    // A placeholder clause ("TBD; renders the dashboard") is dropped like a placeholder summary.
    var clauses =
        Arrays.stream(text.split(";"))
            .map(String::strip)
            .filter(c -> !c.isEmpty() && !isPlaceholder(c))
            .toList();
    if (clauses.isEmpty() || !trueToTheFile(clauses.getFirst(), patch, known)) {
      return "";
    }
    return String.join("; ", clauses.stream().filter(c -> trueToTheFile(c, patch, known)).toList());
  }

  private static boolean isPlaceholder(String text) {
    return PLACEHOLDERS.contains(
        NON_WORD.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip());
  }

  /** Whether a clause neither talks about the findings nor names a setting nothing shows. */
  private static boolean trueToTheFile(String clause, String patch, String known) {
    return !talksAboutFindings(clause) && !namesAbsentSetting(clause, patch, known);
  }

  /**
   * Whether {@code clause} is about the review's findings: "finding(s)" with a count or a review
   * word one or two words before it ("the two confirmed findings", "no findings"), or "finding(s)"
   * anywhere in a clause that names "this batch" ("no findings in this batch", the batch planner's
   * split). "Refines findings before they are posted" and "Processes this batch of rows" describe a
   * file and are not.
   */
  static boolean talksAboutFindings(String clause) {
    var lower = clause.toLowerCase(Locale.ROOT);
    var words = NON_WORD.split(lower);
    var aboutTheBatch = lower.contains("this batch");
    for (var i = 0; i < words.length; i++) {
      if (words[i].startsWith("finding") && (aboutTheBatch || qualified(words, i))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether one of the two words before position {@code at} counts or qualifies findings; when the
   * finding word opens the clause ("Findings are reported inline"), one of the two words after it.
   */
  private static boolean qualified(String[] words, int at) {
    var opensClause = Arrays.stream(words, 0, at).allMatch(String::isEmpty);
    var from = opensClause ? at + 1 : Math.max(0, at - 2);
    var to = opensClause ? Math.min(words.length, at + 3) : at;
    for (var j = from; j < to; j++) {
      if (FINDING_QUALIFIERS.contains(words[j])
          || isCount(words[j])
          || (opensClause && FINDING_PLACEMENTS.contains(words[j]))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether {@code word} is a number. The empty word a clause opening with punctuation splits into
   * is not one.
   */
  private static boolean isCount(String word) {
    return !word.isEmpty() && word.chars().allMatch(Character::isDigit);
  }

  /**
   * Whether {@code clause} names an {@code UPPER_SNAKE} setting that neither {@code patch} nor
   * {@code known} contains. A file with no patch to read is not judged.
   */
  private static boolean namesAbsentSetting(String clause, String patch, String known) {
    if (patch == null || patch.isBlank()) {
      return false;
    }
    return SETTING_NAME
        .matcher(clause)
        .results()
        .map(MatchResult::group)
        .anyMatch(name -> !patch.contains(name) && !known.contains(name));
  }

  /** Whether a tracker-shaped {@code key} is a standard's name and number ("HTTP-404"). */
  private static boolean isStandardCode(String key) {
    return STANDARD_PREFIXES.contains(key.substring(0, key.indexOf('-')));
  }

  /** {@code purpose} without the sentences naming a ticket key {@code known} does not hold. */
  static String withoutInventedTickets(String purpose, String known) {
    if (purpose == null || !TICKET_KEY.matcher(purpose).find()) {
      return purpose;
    }
    var source = known.toUpperCase(Locale.ROOT);
    return Arrays.stream(SENTENCE_END.split(purpose.strip()))
        .filter(
            sentence ->
                TICKET_KEY
                    .matcher(sentence)
                    .results()
                    .map(MatchResult::group)
                    .allMatch(key -> source.contains(key) || isStandardCode(key)))
        .collect(Collectors.joining(" "));
  }
}
