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

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.review.ai.PrReviewPrompts;
import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Carries the Description vs. Implementation gaps across review rounds (#923), the way #917 carries
 * the findings still open.
 *
 * <p>The summary comment is edited in place every round (#868), and its gap list used to be only
 * what the round's summary call happened to return. A call that did not repeat a linked-issue gap
 * an earlier round had listed erased it; when the gaps it did return all restated findings, the
 * section then said every mismatch was reported as a finding below, over a gap no finding covered.
 *
 * <p>The carried set is what the previous round <em>listed</em>, read back from the bot's own
 * summary comment: gaps that collapsed onto a finding there are carried by that finding and leave
 * with it, so only the bullets a reader actually saw are carried. A carried gap stays until one of:
 *
 * <ul>
 *   <li>the summary call names it in {@code addressed_gaps} with what now shows it resolved. The
 *       call re-evaluates the carried list, since only it reads the current description and file
 *       list, but silence keeps a gap: leaving a gap out of its answer is exactly the failure being
 *       fixed, so it is never read as a resolution;
 *   <li>it is a {@code Linked issue #N} gap and the pull request's linked issues, read this round,
 *       no longer include {@code #N}. With no linked issues read at all (the feature off, or the
 *       read failed) nothing is dropped on that ground, since nothing can be judged;
 *   <li>the round reports the same gap again: the fresh wording replaces the carried one.
 * </ul>
 *
 * <p>The merge itself is deterministic and runs after the summary call, like the finding merge of
 * #917. The call's input grows by at most {@value #MAX_PROMPTED_GAPS} labelled gaps of at most
 * {@value #MAX_PROMPT_GAP_CHARS} characters each, counted in the summary clamps with the rest of
 * its guidance. That bound applies to what the call is asked about, never to what is carried: every
 * listed gap is read back, and one past the first {@value #MAX_PROMPTED_GAPS} stays listed without
 * being put to the call, so it is asked about once gaps ahead of it leave.
 *
 * <p>This class also keeps a {@code Linked issue #N} entry to the issues actually linked: a number
 * the pull request does not link is rewritten to the linked one (#923, where a pull request linked
 * to #114 was reported against "#1").
 */
final class DescriptionGapCarryover {

  private DescriptionGapCarryover() {}

  /** Carried gaps put to the summary call, at most; the rest are carried without being asked. */
  static final int MAX_PROMPTED_GAPS = 10;

  /** Characters of one carried gap in the summary call's prompt. */
  static final int MAX_PROMPT_GAP_CHARS = 400;

  /** The label a carried gap gets in the prompt, followed by its 1-based position. */
  static final String LABEL_PREFIX = "G";

  /**
   * A linked-issue gap as the prompt shapes it: "Linked issue", the issue number(s) or none, a
   * colon, then the rest (group 2). Group 1 holds the numbers; only numbers may sit between the
   * words and the colon, so a description gap that merely starts with the words is not one.
   */
  private static final Pattern LINKED_ISSUE_GAP =
      Pattern.compile(
          "(?is)^\\s*linked\\s+issues?\\s*(#?\\d+(?:\\s*(?:,|/|or|and)\\s*#?\\d+){0,9})?\\s*:(.*)$");

  private static final Pattern ISSUE_NUMBER = Pattern.compile("\\d+");

  /** An addressed-gap entry naming its gap by label: "G2", "[G2]", "G2: the doc now covers it". */
  private static final Pattern LABEL_REFERENCE =
      Pattern.compile("(?i)^\\s*\\[?\\s*g\\s*(\\d{1,3})\\b");

  /** Separators the prompt's "criterion — evidence" shape uses between its two halves. */
  private static final Pattern CRITERION_END = Pattern.compile("\\s[—–]\\s|\\s-\\s");

  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

  /**
   * The gaps carried into one round and the linked-issue section they are checked against.
   *
   * @param gaps the previous round's listed gaps still linked, oldest listing order
   * @param linkedIssues the unfenced linked-issue section of this round, {@code ""} when none
   */
  record Carry(List<String> gaps, String linkedIssues) {

    static final Carry NONE = new Carry(List.of(), "");

    Carry {
      gaps = List.copyOf(gaps);
      linkedIssues = linkedIssues == null ? "" : linkedIssues;
    }

    /**
     * The summary-call guidance asking which carried gaps are now resolved, with the gaps fenced
     * and labelled; {@code ""} when nothing is carried, so the call sees no request without data.
     */
    String promptSection() {
      if (gaps.isEmpty()) {
        return "";
      }
      var list = new StringBuilder();
      for (var i = 0; i < Math.min(gaps.size(), MAX_PROMPTED_GAPS); i++) {
        list.append(LABEL_PREFIX)
            .append(i + 1)
            .append(": ")
            .append(
                CiFailureContextResolver.clip(
                    MarkdownSafe.oneLine(gaps.get(i)), MAX_PROMPT_GAP_CHARS))
            .append('\n');
      }
      return PrReviewPrompts.CARRIED_GAPS_REQUEST
          + "\n\n"
          + PromptTemplateEscaper.fence(list.toString().strip());
    }

    /**
     * The summary this round renders and persists: the call's own gaps, their linked-issue numbers
     * checked, followed by every carried gap the call neither resolved nor reported again. A {@code
     * null} summary (a degraded round, no summary call answer) keeps the carried gaps in a summary
     * of their own, so a round that could not re-check them does not erase them either; with
     * nothing carried it stays {@code null}, the counts-only shape.
     */
    ReviewResponse.Summary apply(ReviewResponse.Summary summary) {
      if (summary == null) {
        return gaps.isEmpty() ? null : new ReviewResponse.Summary(0, 0, 0, 0, 0, null, null, gaps);
      }
      var current = withLinkedIssueNumbers(summary.descriptionGaps(), linkedIssues);
      var merged = merge(current, gaps, summary.addressedGaps());
      // Nothing to change leaves the call's own summary as it came.
      return merged.equals(summary.descriptionGaps()) && summary.addressedGaps().isEmpty()
          ? summary
          : summary.withDescriptionGaps(merged);
    }
  }

  /**
   * The carry for a round: the gaps the bot's latest summary comment on the conversation lists,
   * less the linked-issue gaps whose issue this round's linked set no longer holds.
   */
  static Carry of(
      List<GitHubCommentClient.IssueComment> conversation,
      BotIdentity botIdentity,
      String linkedIssues) {
    var previous = previousGaps(conversation, botIdentity);
    if (previous.isEmpty()) {
      return new Carry(List.of(), linkedIssues);
    }
    return new Carry(
        stillLinked(previous, TicketContextResolver.linkedKeys(linkedIssues)), linkedIssues);
  }

  /** The gaps the newest bot-authored summary comment lists; empty when there is none. */
  static List<String> previousGaps(
      List<GitHubCommentClient.IssueComment> conversation, BotIdentity botIdentity) {
    for (var i = conversation.size() - 1; i >= 0; i--) {
      var comment = conversation.get(i);
      var user = comment.user();
      if (user != null
          && botIdentity.matches(user.login())
          && ReviewContextLoader.isBotSummaryComment(comment.body())) {
        return listedGaps(comment.body());
      }
    }
    return List.of();
  }

  /**
   * The bullets of a rendered summary's Description vs. Implementation section, all of them: a gap
   * cut here would leave the section with no reason given, the failure this class exists for. The
   * section is bounded by the comment it sits in. The intro line and the all-reported-as-findings
   * line are not bullets, so a section that listed nothing carries nothing. A line after a bullet
   * that is neither a bullet, a blank nor a heading continues it (a gap rendered before bullets
   * were flattened to one line).
   */
  static List<String> listedGaps(String body) {
    var gaps = new ArrayList<String>();
    var lines = body.split("\n", -1);
    var start = -1;
    for (var i = 0; i < lines.length && start < 0; i++) {
      if (lines[i].strip().equals(PrSummaryGenerator.GAPS_HEADING)) {
        start = i + 1;
      }
    }
    if (start < 0) {
      return List.of();
    }
    StringBuilder open = null;
    for (var i = start; i < lines.length; i++) {
      var line = lines[i].strip();
      if (line.startsWith("#") || line.startsWith("---") || line.startsWith("<")) {
        break;
      }
      if (line.startsWith("- ")) {
        addGap(gaps, open);
        open = new StringBuilder(line.substring(2).strip());
      } else if (line.isEmpty()) {
        addGap(gaps, open);
        open = null;
      } else if (open != null) {
        open.append(' ').append(line);
      }
    }
    addGap(gaps, open);
    return gaps;
  }

  private static void addGap(List<String> gaps, StringBuilder gap) {
    // A bullet always opens with text: "- " is stripped off a line that has more after it.
    if (gap != null) {
      gaps.add(gap.toString());
    }
  }

  /**
   * The gaps whose linked issue is still linked. A description gap names no issue and always stays;
   * so does every gap when no linked issue was read this round ({@code keys} empty).
   */
  static List<String> stillLinked(List<String> gaps, List<String> keys) {
    if (keys.isEmpty()) {
      return gaps;
    }
    return gaps.stream()
        .filter(
            gap -> {
              var issues = keyOf(gap).issues();
              return issues.isEmpty() || issues.stream().anyMatch(keys::contains);
            })
        .toList();
  }

  /**
   * What makes two gaps the same gap across rounds: the linked issues it names (none for a
   * description gap) and the criterion it states, normalised to lowercase alphanumeric words. For a
   * {@code Linked issue #N: <criterion> — <evidence>} gap the criterion is the text before the
   * dash, so a round that restates the evidence differently still names the same gap; any other gap
   * is its whole text.
   */
  record GapKey(Set<String> issues, String criterion) {
    GapKey {
      issues = Set.copyOf(issues);
    }
  }

  static GapKey keyOf(String gap) {
    var linked = LINKED_ISSUE_GAP.matcher(gap);
    if (!linked.matches()) {
      return new GapKey(Set.of(), normalize(gap));
    }
    var issues = issuesNamed(linked.group(1));
    return new GapKey(issues, normalize(criterionOf(linked.group(2))));
  }

  /** The criterion half of a linked-issue gap's text after the colon: up to the dash, if any. */
  private static String criterionOf(String rest) {
    var end = CRITERION_END.matcher(rest);
    return end.find() ? rest.substring(0, end.start()) : rest;
  }

  /** The issue keys ({@code "#114"}) a linked-issue gap's number group names, in order. */
  private static Set<String> issuesNamed(String numberGroup) {
    var issues = new TreeSet<String>();
    if (numberGroup != null) {
      var numbers = ISSUE_NUMBER.matcher(numberGroup);
      while (numbers.find()) {
        issues.add("#" + numbers.group());
      }
    }
    return issues;
  }

  private static String normalize(String text) {
    return NON_ALPHANUMERIC.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
  }

  /**
   * {@code current} followed by every carried gap that is neither addressed nor the same gap as one
   * of {@code current}. Rewording that the key does not catch is left to the renderer, whose
   * deduplicator collapses a gap restating an earlier one (#588); {@code current} comes first, so
   * the fresh wording is the one kept.
   */
  static List<String> merge(List<String> current, List<String> carried, List<String> addressed) {
    var resolved = addressedPositions(addressed, carried);
    var reported = new HashSet<GapKey>();
    current.forEach(gap -> reported.add(keyOf(gap)));
    var merged = new ArrayList<>(current);
    for (var i = 0; i < carried.size(); i++) {
      var gap = carried.get(i);
      if (!resolved.contains(i) && !reported.contains(keyOf(gap))) {
        merged.add(gap);
      }
    }
    return merged;
  }

  /**
   * The 0-based positions in {@code carried} that {@code addressed} resolves: by label ({@code
   * G<n>}), or, for an entry that copied a gap instead of its label, by that gap's key. An entry
   * that names nothing carried resolves nothing.
   */
  static Set<Integer> addressedPositions(List<String> addressed, List<String> carried) {
    var positions = new HashSet<Integer>();
    for (var entry : addressed) {
      var label = LABEL_REFERENCE.matcher(entry);
      if (label.find()) {
        var position = Integer.parseInt(label.group(1)) - 1;
        if (position >= 0 && position < Math.min(carried.size(), MAX_PROMPTED_GAPS)) {
          positions.add(position);
        }
        continue;
      }
      var key = keyOf(entry);
      for (var i = 0; i < carried.size(); i++) {
        if (keyOf(carried.get(i)).equals(key)) {
          positions.add(i);
        }
      }
    }
    return positions;
  }

  /**
   * {@code gaps} with every {@code Linked issue} entry naming an issue the pull request is linked
   * to. An entry already naming a linked issue is left alone; any other one — a number the pull
   * request does not link, such as a count or list position the model echoed, or no number at all —
   * is rewritten to the only linked issue, or with several to the one whose section shares most
   * words with the entry's criterion, or to all of them when none stands out. With no linked-issue
   * section this round the entries are left as the model wrote them: there is no set to check.
   */
  static List<String> withLinkedIssueNumbers(List<String> gaps, String linkedIssues) {
    var keys = TicketContextResolver.linkedKeys(linkedIssues);
    if (keys.isEmpty()) {
      return gaps;
    }
    var checked = new ArrayList<String>(gaps.size());
    for (var gap : gaps) {
      checked.add(withLinkedIssueNumber(gap, keys, linkedIssues));
    }
    return checked;
  }

  private static String withLinkedIssueNumber(String gap, List<String> keys, String linkedIssues) {
    var linked = LINKED_ISSUE_GAP.matcher(gap);
    if (!linked.matches()) {
      return gap;
    }
    var named = issuesNamed(linked.group(1));
    if (!named.isEmpty() && keys.containsAll(named)) {
      return gap;
    }
    var key =
        keys.size() == 1
            ? keys.getFirst()
            : closestIssue(criterionOf(linked.group(2)), keys, linkedIssues);
    return "Linked issue " + key + ":" + linked.group(2);
  }

  /**
   * The linked issue whose part of the section shares the most content words with {@code
   * criterion}; when several share that most, all of those joined with "or" (every key, when none
   * shares any). An issue scoring below the leaders is never named.
   */
  private static String closestIssue(String criterion, List<String> keys, String linkedIssues) {
    var words = SummarySurfaceDeduplicator.claim(criterion).wordSet();
    var leaders = new ArrayList<String>();
    var bestScore = -1L;
    for (var key : keys) {
      var issueWords = SummarySurfaceDeduplicator.claim(issueSection(key, linkedIssues)).wordSet();
      var score = words.stream().filter(issueWords::contains).count();
      if (score > bestScore) {
        leaders.clear();
        bestScore = score;
      }
      if (score == bestScore) {
        leaders.add(key);
      }
    }
    return String.join(" or ", leaders);
  }

  /** The part of the linked-issue section under {@code key}'s own heading; "" when absent. */
  private static String issueSection(String key, String linkedIssues) {
    // The key must end the heading's key: a titled heading goes on with ':', an untitled one ends
    // the line. A bare prefix search would take "#12" to "#123: …".
    var heading =
        Pattern.compile(
                "\n" + Pattern.quote(TicketContextResolver.ISSUE_HEADING + key) + "(?=[:\n]|$)")
            .matcher(linkedIssues);
    if (!heading.find()) {
      return "";
    }
    var start = heading.start();
    var end = linkedIssues.indexOf("\n" + TicketContextResolver.ISSUE_HEADING, start + 1);
    return linkedIssues.substring(start, end < 0 ? linkedIssues.length() : end);
  }
}
