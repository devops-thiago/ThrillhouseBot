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
import java.util.BitSet;
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

  private static final Pattern ACCEPTANCE_HEADING =
      Pattern.compile("(?i)\\bacceptance\\b|\\bdefinition\\s+of\\s+done\\b");

  // Sections a GitHub issue form adds that hold checkboxes but no requirement.
  private static final Pattern IGNORED_HEADING =
      Pattern.compile("(?i)\\bcode\\s+of\\s+conduct\\b|\\bcontributing\\b");

  // Both item patterns run on a stripped line, and each whitespace run is followed by a
  // non-space, so no two quantifiers compete for the same characters.
  private static final Pattern TASK_ITEM = Pattern.compile("^[-*+]\\s+\\[([ xX])]\\s+(\\S.*)$");

  private static final Pattern LIST_ITEM = Pattern.compile("^(?:[-*+]|\\d{1,3}[.)])\\s+(\\S.*)$");

  /** The level a bold-line heading gets: below every ATX level. */
  static final int BOLD_LEVEL = 7;

  /** Section names that hold no criteria even when they sit under an acceptance heading. */
  private static final List<String> NON_CRITERIA_SECTIONS =
      List.of(
          "non goal",
          "out of scope",
          "note",
          "alternative",
          "background",
          "context",
          "open question",
          "reference",
          "related");

  /** Whether a heading's text names a section of {@link #NON_CRITERIA_SECTIONS}. */
  static boolean namesNonCriteriaSection(String headingText) {
    var words = " " + headingText.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", " ").strip() + " ";
    return NON_CRITERIA_SECTIONS.stream().anyMatch(name -> words.contains(" " + name));
  }

  /** Leading spaces that make a line after a blank one, outside a list, indented code. */
  private static final int INDENTED_CODE = 4;

  // A fence line: up to three spaces, then a run of three or more backticks or tildes (group 1).
  private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})");

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
   * purposes (a code-of-conduct confirmation). Lines inside fenced or indented code blocks are
   * never criteria. A criterion keeps its checkbox state, since a ticked box is the issue author's
   * claim rather than evidence, and the model is told so.
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
      if (!chosen.used().get(i)) {
        rest.append(lines[i]).append('\n');
      }
    }
    return new Extracted(chosen.criteria(), rest.toString().replaceAll("\n{3,}", "\n\n"));
  }

  private record Collected(List<String> criteria, BitSet used) {}

  /**
   * One pass over the body: with {@code sectioned}, the list items under an acceptance heading;
   * without, every task-list item outside an ignored section.
   */
  private static Collected collect(String[] lines, boolean sectioned) {
    var criteria = new ArrayList<String>();
    var used = new BitSet(lines.length);
    var sections = new SectionTracker();
    for (var i = 0; i < lines.length; i++) {
      if (sections.isContent(lines[i])) {
        var criterion = criterion(lines[i], sections.inScope(sectioned), sectioned);
        if (criterion != null) {
          criteria.add(criterion);
          used.set(i);
        }
      }
    }
    return new Collected(criteria, used);
  }

  /** Where a line sits in the body: inside a fenced block, and under which heading. */
  private static final class SectionTracker {
    private String fence = ""; // the marker run that opened the current fence; "" outside one
    private boolean inIndentedCode;
    private boolean inList;
    private boolean afterBlank = true;
    private boolean inAcceptance;
    private boolean inIgnored;
    private int sectionLevel; // level of the heading that opened the section; 7 for a bold line

    /**
     * Consumes {@code line}; {@code true} when it is body text rather than a fence, a line of code,
     * or a heading.
     */
    boolean isContent(String line) {
      var marker = FENCE.matcher(line);
      if (marker.find()) {
        // As in Markdown, only a run of the opener's character, at least as long, closes a fence.
        var run = marker.group(1);
        if (fence.isEmpty()) {
          fence = run;
        } else if (run.charAt(0) == fence.charAt(0) && run.length() >= fence.length()) {
          fence = "";
        }
        return false;
      }
      if (!fence.isEmpty() || isIndentedCode(line)) {
        return false;
      }
      var heading = Heading.parse(line);
      if (heading == null) {
        return true;
      }
      inList = false;
      // Inside an acceptance section, a deeper ATX heading or any bold line stays part of it (a
      // bold "**Backend**" label groups criteria), unless it names a section that holds none
      // ("**Non-goals:**"); any other heading starts a new section.
      if (!inAcceptance || closesSection(heading) || namesNonCriteriaSection(heading.text())) {
        inAcceptance = ACCEPTANCE_HEADING.matcher(heading.text()).find();
        inIgnored = IGNORED_HEADING.matcher(heading.text()).find();
        sectionLevel = heading.level();
      }
      return false;
    }

    /**
     * Whether {@code line} belongs to an indented code block, as Markdown draws one: four or more
     * spaces after a blank line, outside a list (inside one, the same indent nests an item). Blank
     * lines neither open nor close a block. Tracks list and blank-line state as it goes.
     */
    private boolean isIndentedCode(String line) {
      if (line.isBlank()) {
        afterBlank = true;
        return inIndentedCode;
      }
      var indent = line.length() - line.stripLeading().length();
      var opensCode = indent >= INDENTED_CODE && afterBlank && !inList;
      inIndentedCode = (inIndentedCode && indent >= INDENTED_CODE) || opensCode;
      afterBlank = false;
      if (!inIndentedCode) {
        var stripped = line.strip();
        // A task item is a list item too; an indented line inside a list continues it.
        inList = LIST_ITEM.matcher(stripped).matches() || (inList && indent > 0);
      }
      return inIndentedCode;
    }

    /**
     * Whether {@code heading} ends the open acceptance section by level: an ATX heading at the
     * opener's level or above, or any ATX heading under a bold opener. A bold line never does,
     * whatever opened the section, since bold labels are how criteria get grouped.
     */
    private boolean closesSection(Heading heading) {
      return heading.level() < BOLD_LEVEL && heading.level() <= sectionLevel;
    }

    /** Whether a list item here can be a criterion in the given extraction mode. */
    boolean inScope(boolean sectioned) {
      return sectioned ? inAcceptance : !inIgnored;
    }
  }

  /**
   * A Markdown ATX heading ({@code ## Text}, level 1-6) or a line that is only bold text ({@code
   * **Text**}, how many issue templates head a section; level 7).
   */
  record Heading(int level, String text) {

    /** The heading {@code line} is, or {@code null} when it is not one. */
    static Heading parse(String line) {
      var t = line.strip();
      if (line.length() - line.stripLeading().length() > 3) {
        return null; // indented code, not a heading
      }
      if (t.startsWith("#")) {
        var level = 0;
        while (level < t.length() && t.charAt(level) == '#') {
          level++;
        }
        var rest = t.substring(level);
        if (level > 6 || !(rest.isEmpty() || Character.isWhitespace(rest.charAt(0)))) {
          return null;
        }
        var end = rest.length();
        while (end > 0
            && (rest.charAt(end - 1) == '#' || Character.isWhitespace(rest.charAt(end - 1)))) {
          end--;
        }
        return new Heading(level, rest.substring(0, end).strip());
      }
      var bold = t.endsWith(":") ? t.substring(0, t.length() - 1) : t;
      if (bold.length() > 4 && bold.startsWith("**") && bold.endsWith("**")) {
        return new Heading(BOLD_LEVEL, bold.substring(2, bold.length() - 2));
      }
      return null;
    }
  }

  /** The criterion {@code line} states, or {@code null} when it is not one. */
  private static String criterion(String line, boolean inScope, boolean anyListItem) {
    if (!inScope) {
      return null;
    }
    var stripped = line.strip();
    var task = TASK_ITEM.matcher(stripped);
    if (task.matches()) {
      var mark = task.group(1).isBlank() ? "[ ] " : "[x] ";
      return mark + CiFailureContextResolver.clip(task.group(2).strip(), MAX_LINE_CHARS);
    }
    var item = anyListItem ? LIST_ITEM.matcher(stripped) : null;
    return item != null && item.matches()
        ? CiFailureContextResolver.clip(item.group(1).strip(), MAX_LINE_CHARS)
        : null;
  }

  /** An issue body's acceptance criteria and the body without them. */
  record Extracted(List<String> criteria, String rest) {}
}
