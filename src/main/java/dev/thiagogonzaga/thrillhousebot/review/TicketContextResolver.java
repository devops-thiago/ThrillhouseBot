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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import io.quarkus.arc.All;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Builds the opt-in linked-issue context (#58): the title, acceptance criteria and body of the
 * issue(s) a pull request says it implements, as one bounded, plain-text section. The assembler
 * fences it and hands it to two calls with different guidance: the review call reads it as intent,
 * and the summary call checks the change against the acceptance criteria and lists the ones it does
 * not address under Description vs. Implementation.
 *
 * <p>Which issues, and how they are read, is the configured {@link IssueTrackerProvider}'s job;
 * this class only renders. Issue text is written by anyone who can edit the issue, so it gets the
 * treatment #59 gives CI output: control, ANSI and bidi characters are stripped ({@link
 * CiFailureContextResolver#clean}), every line and body is clipped, and the whole section is capped
 * at {@code max-chars}. The acceptance criteria are rendered before the body, so a cut drops prose
 * before it drops a criterion. Best-effort throughout: nothing here can fail a review.
 */
@ApplicationScoped
public class TicketContextResolver {

  /** Acceptance criteria rendered per issue; the rest are counted. */
  static final int MAX_CRITERIA = 20;

  /** Room kept in an issue's share for the "(N more not shown)" line. */
  static final int MORE_RESERVE = 32;

  /** Characters of any single line — a title or a criterion. */
  static final int MAX_LINE_CHARS = 300;

  /** Appended when the whole section had to be cut to {@code max-chars}. */
  static final String TRUNCATION_NOTE = "[linked-issue context truncated to its character budget]";

  static final String NO_CRITERIA =
      "Acceptance criteria: none marked in the issue (no task list and no \"Acceptance criteria\""
          + " section); its requirements, if any, are in the body.";

  // A Markdown ATX heading, or a line that is only bold text (how many issue templates head a
  // section). Group 1 is the ATX level marker, absent for a bold line; group 2 is the text.
  private static final Pattern HEADING =
      Pattern.compile("^\\s{0,3}(?:(#{1,6})\\s+(.*?)[\\s#]*|\\*\\*(.+?)\\*\\*:?\\s*)$");

  private static final Pattern ACCEPTANCE_HEADING =
      Pattern.compile("(?i)\\bacceptance\\b|\\bdefinition\\s+of\\s+done\\b");

  // Sections a GitHub issue form adds that hold checkboxes but no requirement.
  private static final Pattern IGNORED_HEADING =
      Pattern.compile("(?i)\\bcode\\s+of\\s+conduct\\b|\\bcontributing\\b");

  private static final Pattern TASK_ITEM = Pattern.compile("^\\s*[-*+]\\s+\\[([ xX])]\\s+(.+)$");

  private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d{1,3}[.)])\\s+(.+)$");

  private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(?:```|~~~)");

  private final IssueTrackerProvider provider;
  private final int maxIssues;
  private final int maxChars;
  private final boolean fromBranch;

  @Inject
  public TicketContextResolver(
      @All List<IssueTrackerProvider> providers, ThrillhouseConfig config) {
    this(
        providers,
        config.review().ticketContext().enabled(),
        config.review().ticketContext().provider(),
        config.review().ticketContext().maxIssues(),
        config.review().ticketContext().maxChars(),
        config.review().ticketContext().fromBranch());
  }

  /** Visible for tests: the switches and caps are passed directly. */
  TicketContextResolver(
      List<IssueTrackerProvider> providers,
      boolean enabled,
      String providerName,
      int maxIssues,
      int maxChars,
      boolean fromBranch) {
    this.provider = enabled ? select(providers, providerName) : null;
    this.maxIssues = maxIssues;
    this.maxChars = maxChars;
    this.fromBranch = fromBranch;
  }

  private static IssueTrackerProvider select(
      List<IssueTrackerProvider> providers, String providerName) {
    var wanted = providerName == null ? "" : providerName.strip().toLowerCase(Locale.ROOT);
    for (var candidate : providers) {
      if (candidate.name().equals(wanted)) {
        return candidate;
      }
    }
    // The startup validator rejects an unknown name, so this is a wiring fault, not a typo.
    Log.warnf("No issue-tracker provider named '%s'; linked-issue context is off", wanted);
    return null;
  }

  /** Whether the linked-issue context is switched on and has a provider to read from. */
  boolean enabled() {
    return provider != null;
  }

  /**
   * The linked-issue section for the review and summary prompts, unfenced, or {@code ""} when the
   * feature is off, the pull request links no readable issue, or the section could not be built.
   */
  String resolve(String auth, String owner, String repo, int prNumber, String prBody) {
    if (provider == null) {
      return "";
    }
    try {
      var tickets =
          provider.linkedTickets(
              new IssueTrackerProvider.TicketLookup(
                  auth, owner, repo, prNumber, prBody, fromBranch),
              maxIssues);
      if (tickets == null || tickets.isEmpty()) {
        return "";
      }
      return CiFailureContextResolver.cap(
          render(tickets.subList(0, Math.min(tickets.size(), maxIssues))),
          maxChars,
          TRUNCATION_NOTE);
    } catch (RuntimeException e) {
      Log.warn("Linked-issue context could not be built, continuing without it", e);
      return "";
    }
  }

  private String render(List<IssueTrackerProvider.LinkedTicket> tickets) {
    var sb = new StringBuilder();
    sb.append("Issues this pull request is linked to: ").append(tickets.size()).append('\n');
    // Each issue gets an equal share, so one sprawling issue cannot crowd out the others.
    var share = Math.max(0, (maxChars - sb.length()) / tickets.size());
    for (var ticket : tickets) {
      sb.append('\n').append(renderTicket(ticket, share));
    }
    return sb.toString();
  }

  /**
   * One issue within {@code share} characters: header and criteria first, then the body. Only the
   * header and the first criterion can run past a share too small to hold them; the section cap
   * still bounds the whole.
   */
  static String renderTicket(IssueTrackerProvider.LinkedTicket ticket, int share) {
    var sb = new StringBuilder("### Issue ").append(CiFailureContextResolver.oneLine(ticket.key()));
    var title = CiFailureContextResolver.oneLine(ticket.title());
    if (!title.isEmpty()) {
      sb.append(": ").append(title);
    }
    sb.append("\nLinked by: ").append(CiFailureContextResolver.oneLine(ticket.via())).append('\n');
    var extracted = extract(CiFailureContextResolver.clean(ticket.body()));
    var criteria = extracted.criteria();
    if (criteria.isEmpty()) {
      sb.append(NO_CRITERIA).append('\n');
    } else {
      sb.append("Acceptance criteria (from the issue):\n");
      // Criteria stay within the issue's share too, so one issue's long list cannot push the
      // issues after it past the section cap. The first criterion is always shown.
      var limit = Math.min(criteria.size(), MAX_CRITERIA);
      var shown = 0;
      while (shown < limit
          && (shown == 0
              || sb.length() + criteria.get(shown).length() + 3 <= share - MORE_RESERVE)) {
        sb.append("- ").append(criteria.get(shown)).append('\n');
        shown++;
      }
      if (criteria.size() > shown) {
        sb.append("- (").append(criteria.size() - shown).append(" more not shown)\n");
      }
    }
    var body = extracted.rest().strip();
    var room = share - sb.length() - "Issue body:\n".length() - 1;
    if (!body.isEmpty() && room > 0) {
      sb.append("Issue body:\n").append(CiFailureContextResolver.clip(body, room)).append('\n');
    }
    return sb.toString();
  }

  /**
   * An issue body split into its acceptance criteria and the rest. When the body has a section
   * headed "Acceptance criteria" (or "Definition of done"), its list items are the criteria.
   * Otherwise its task-list items are, except those under a heading an issue form adds for its own
   * purposes (a code-of-conduct confirmation). Lines inside fenced code blocks are never criteria.
   * A criterion keeps its checkbox state, since a ticked box is the issue author's claim rather
   * than evidence, and the model is told so.
   */
  static Extracted extract(String body) {
    if (body.isBlank()) {
      return new Extracted(List.of(), "");
    }
    var lines = body.split("\n", -1);
    var sectioned = collect(lines, true);
    var chosen = sectioned.criteria().isEmpty() ? collect(lines, false) : sectioned;
    var rest = new StringBuilder();
    for (var i = 0; i < lines.length; i++) {
      if (!chosen.used()[i]) {
        rest.append(lines[i]).append('\n');
      }
    }
    return new Extracted(chosen.criteria(), rest.toString().replaceAll("\n{3,}", "\n\n"));
  }

  private record Collected(List<String> criteria, boolean[] used) {}

  /**
   * One pass over the body: with {@code sectioned}, the list items under an acceptance heading;
   * without, every task-list item outside an ignored section.
   */
  private static Collected collect(String[] lines, boolean sectioned) {
    var criteria = new ArrayList<String>();
    var used = new boolean[lines.length];
    var inFence = false;
    var sectionLevel = 0; // level of the heading that opened the current section; 7 for bold
    var inAcceptance = false;
    var inIgnored = false;
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      if (FENCE.matcher(line).find()) {
        inFence = !inFence;
        continue;
      }
      if (inFence) {
        continue;
      }
      var heading = HEADING.matcher(line);
      if (heading.matches()) {
        var level = heading.group(1) == null ? 7 : heading.group(1).length();
        var text = heading.group(2) == null ? heading.group(3) : heading.group(2);
        if (!inAcceptance || level <= sectionLevel) {
          inAcceptance = ACCEPTANCE_HEADING.matcher(text).find();
          inIgnored = IGNORED_HEADING.matcher(text).find();
          sectionLevel = level;
        }
        continue;
      }
      var criterion = criterion(line, sectioned ? inAcceptance : !inIgnored, sectioned);
      if (criterion != null) {
        criteria.add(criterion);
        used[i] = true;
      }
    }
    return new Collected(criteria, used);
  }

  /** The criterion {@code line} states, or {@code null} when it is not one. */
  private static String criterion(String line, boolean inScope, boolean anyListItem) {
    if (!inScope) {
      return null;
    }
    var task = TASK_ITEM.matcher(line);
    if (task.matches()) {
      var mark = task.group(1).isBlank() ? "[ ] " : "[x] ";
      return mark + CiFailureContextResolver.clip(task.group(2).strip(), MAX_LINE_CHARS);
    }
    if (!anyListItem) {
      return null;
    }
    var item = LIST_ITEM.matcher(line);
    return item.matches()
        ? CiFailureContextResolver.clip(item.group(1).strip(), MAX_LINE_CHARS)
        : null;
  }

  /** An issue body's acceptance criteria and the body without them. */
  record Extracted(List<String> criteria, String rest) {}
}
