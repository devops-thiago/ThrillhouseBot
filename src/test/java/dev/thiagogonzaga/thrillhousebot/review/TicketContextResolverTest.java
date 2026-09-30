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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TicketContextResolverTest {

  /** A provider that returns fixed tickets and records what it was asked. */
  private static final class FixedProvider implements IssueTrackerProvider {
    private final String name;
    private final List<LinkedTicket> tickets;
    private final List<TicketLookup> lookups = new ArrayList<>();
    private final List<Integer> limits = new ArrayList<>();

    FixedProvider(String name, List<LinkedTicket> tickets) {
      this.name = name;
      this.tickets = tickets;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public List<LinkedTicket> linkedTickets(TicketLookup lookup, int limit) {
      lookups.add(lookup);
      limits.add(limit);
      return tickets;
    }
  }

  private static IssueTrackerProvider.LinkedTicket ticket(int n, String title, String body) {
    return new IssueTrackerProvider.LinkedTicket(
        "#" + n, "a closing keyword in the PR body", title, body);
  }

  private static TicketContextResolver resolver(IssueTrackerProvider provider, int maxChars) {
    return new TicketContextResolver(List.of(provider), true, "github", 3, maxChars, false);
  }

  @Nested
  class Switches {

    @Test
    void offReadsNothing() {
      var provider = new FixedProvider("github", List.of(ticket(1, "t", "b")));
      var resolver = new TicketContextResolver(List.of(provider), false, "github", 3, 6000, true);

      assertFalse(resolver.enabled());
      assertEquals("", resolver.resolve("a", "o", "r", 1, "Closes #1"));
      assertTrue(provider.lookups.isEmpty());
    }

    @Test
    void anUnknownProviderLeavesTheFeatureOff() {
      var provider = new FixedProvider("github", List.of(ticket(1, "t", "b")));
      var resolver = new TicketContextResolver(List.of(provider), true, "jira", 3, 6000, false);

      assertFalse(resolver.enabled());
      assertEquals("", resolver.resolve("a", "o", "r", 1, "Closes #1"));
      assertFalse(
          new TicketContextResolver(List.of(provider), true, null, 3, 6000, false).enabled());
    }

    @Test
    void theProviderIsChosenByNameIgnoringCaseAndWhitespace() {
      var other = new FixedProvider("other", List.of(ticket(9, "wrong", "")));
      var github = new FixedProvider("github", List.of(ticket(1, "right", "")));
      var resolver =
          new TicketContextResolver(List.of(other, github), true, " GitHub ", 2, 6000, true);

      assertTrue(resolver.enabled());
      var section = resolver.resolve("auth", "o", "r", 42, "body");

      assertTrue(section.contains("### Issue #1: right"), section);
      assertTrue(other.lookups.isEmpty());
      var lookup = github.lookups.get(0);
      assertEquals(
          new IssueTrackerProvider.TicketLookup("auth", "o", "r", 42, "body", true), lookup);
      assertEquals(List.of(2), github.limits);
    }

    @Test
    void theConfigConstructorReadsTheTicketContextSettings() {
      var config = mock(ThrillhouseConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
      when(config.review().ticketContext().enabled()).thenReturn(true);
      when(config.review().ticketContext().provider()).thenReturn("github");
      when(config.review().ticketContext().maxIssues()).thenReturn(1);
      when(config.review().ticketContext().maxChars()).thenReturn(6000);
      when(config.review().ticketContext().fromBranch()).thenReturn(true);
      var provider = new FixedProvider("github", List.of(ticket(1, "a", ""), ticket(2, "b", "")));

      var section =
          new TicketContextResolver(List.of(provider), config).resolve("x", "o", "r", 3, "");

      assertTrue(provider.lookups.get(0).fromBranch());
      assertTrue(section.contains("### Issue #1: a"), section);
      assertFalse(section.contains("### Issue #2"), "a provider over the cap is trimmed");
    }
  }

  @Nested
  class NothingToShow {

    @Test
    void noLinkedIssueGivesNoSection() {
      assertEquals(
          "", resolver(new FixedProvider("github", List.of()), 6000).resolve("a", "o", "r", 1, ""));
      assertEquals(
          "", resolver(new FixedProvider("github", null), 6000).resolve("a", "o", "r", 1, ""));
    }

    @Test
    void aFailingProviderNeverFailsTheReview() {
      var failing =
          new IssueTrackerProvider() {
            @Override
            public String name() {
              return "github";
            }

            @Override
            public List<LinkedTicket> linkedTickets(TicketLookup lookup, int limit) {
              throw new IllegalStateException("tracker down");
            }
          };

      assertEquals("", resolver(failing, 6000).resolve("a", "o", "r", 1, "Closes #1"));
    }
  }

  @Nested
  class Rendering {

    @Test
    void rendersTitleLinkCriteriaAndBody() {
      var body =
          """
          ### Problem Statement
          Reviews ignore the ticket.

          ### Acceptance criteria
          - [ ] resolve the linked issue
          - [x] fence the issue text
          1. document the setting

          ### Notes
          - not a criterion
          """;
      var section =
          resolver(
                  new FixedProvider("github", List.of(ticket(58, "Pull ticket context", body))),
                  6000)
              .resolve("a", "o", "r", 1, "");

      assertTrue(section.startsWith("Issues this pull request is linked to: 1\n"), section);
      assertTrue(section.contains("### Issue #58: Pull ticket context\n"), section);
      assertTrue(section.contains("Linked by: a closing keyword in the PR body\n"), section);
      assertTrue(
          section.contains(
              """
              Acceptance criteria (from the issue):
              - [ ] resolve the linked issue
              - [x] fence the issue text
              - document the setting
              """),
          section);
      assertTrue(section.contains("Issue body:\n### Problem Statement"), section);
      assertTrue(section.contains("- not a criterion"), section);
      assertFalse(section.contains("Issue body:\n- [ ] resolve"), "criteria are not repeated");
    }

    @Test
    void anIssueWithoutCriteriaSaysSo() {
      var section =
          resolver(
                  new FixedProvider(
                      "github", List.of(ticket(3, "", "just prose"), ticket(4, null, null))),
                  6000)
              .resolve("a", "o", "r", 1, "");

      assertTrue(section.contains("### Issue #3\n"), section);
      assertTrue(section.contains(TicketContextResolver.NO_CRITERIA), section);
      assertTrue(section.contains("Issue body:\njust prose"), section);
      assertTrue(section.contains("### Issue #4\n"), section);
    }

    @Test
    void controlAnsiAndBidiCharactersAreStripped() {
      var title = "Add\u202E retries\u0007 \u001B[31mnow\u001B[0m";
      var body = "- [ ] keep\u200B going\r\nline\u2066 two";
      var section =
          resolver(new FixedProvider("github", List.of(ticket(1, title, body))), 6000)
              .resolve("a", "o", "r", 1, "");

      assertTrue(section.contains("### Issue #1: Add retries now\n"), section);
      assertTrue(section.contains("- [ ] keep going\n"), section);
      assertTrue(section.contains("line two"), section);
      for (var forbidden : List.of("\u202E", "\u0007", "\u001B", "\u200B", "\r", "\u2066")) {
        assertFalse(section.contains(forbidden), "stripped: " + forbidden.codePointAt(0));
      }
    }

    @Test
    void longTitlesAndCriteriaAreClippedAndExtraCriteriaCounted() {
      var body = new StringBuilder();
      for (var i = 0; i < TicketContextResolver.MAX_CRITERIA + 3; i++) {
        body.append("- [ ] criterion ").append(i).append(' ').append("x".repeat(400)).append('\n');
      }
      var section =
          resolver(
                  new FixedProvider("github", List.of(ticket(1, "t".repeat(400), body.toString()))),
                  20_000)
              .resolve("a", "o", "r", 1, "");

      assertTrue(section.contains("### Issue #1: " + "t".repeat(299) + "…\n"), section);
      assertTrue(section.contains("- (3 more not shown)\n"), section);
      assertFalse(section.contains("criterion 20 "), section);
      for (var line : section.split("\n")) {
        assertTrue(line.length() <= TicketContextResolver.MAX_LINE_CHARS + 20, line);
      }
    }

    @Test
    void eachIssueGetsAnEqualShareSoOneCannotCrowdOutTheOthers() {
      var huge = "word ".repeat(5_000);
      var section =
          resolver(
                  new FixedProvider(
                      "github", List.of(ticket(1, "first", huge), ticket(2, "second", "short"))),
                  4000)
              .resolve("a", "o", "r", 1, "");

      assertTrue(section.contains("### Issue #2: second"), section);
      assertTrue(section.contains("Issue body:\nshort"), section);
      assertTrue(section.length() <= 4000, "length " + section.length());
    }

    @Test
    void longCriteriaListsStayWithinTheIssuesShare() {
      var body = new StringBuilder();
      for (var i = 0; i < 15; i++) {
        body.append("- [ ] criterion ").append(i).append(' ').append("z".repeat(250)).append('\n');
      }
      var section =
          resolver(
                  new FixedProvider(
                      "github",
                      List.of(ticket(1, "first", body.toString()), ticket(2, "second", "- [ ] b"))),
                  2000)
              .resolve("a", "o", "r", 1, "");

      assertTrue(section.contains("### Issue #2: second"), section);
      assertTrue(section.contains("- [ ] b\n"), section);
      assertTrue(section.contains(" more not shown)\n"), section);
      assertFalse(section.contains(TicketContextResolver.TRUNCATION_NOTE), section);
      assertTrue(section.length() <= 2000, "length " + section.length());
    }

    @Test
    void aShareTooSmallForTheBodyKeepsTheHeaderAndCriteria() {
      var rendered =
          TicketContextResolver.renderTicket(ticket(1, "t", "- [ ] one\nsome prose"), 10);

      assertTrue(rendered.contains("- [ ] one\n"), rendered);
      assertFalse(rendered.contains("Issue body:"), rendered);
    }

    @Test
    void theWholeSectionIsCappedAtALineBoundary() {
      // Five issues in 1000 characters: each share is too small for a long title and the first
      // criterion, which are always kept, so only the section cap bounds the whole.
      var tickets = new ArrayList<IssueTrackerProvider.LinkedTicket>();
      for (var i = 1; i <= 5; i++) {
        tickets.add(ticket(i, "t".repeat(250), "- [ ] criterion " + "y".repeat(280)));
      }
      var section =
          new TicketContextResolver(
                  List.of(new FixedProvider("github", tickets)), true, "github", 5, 1000, false)
              .resolve("a", "o", "r", 1, "");

      assertTrue(section.length() <= 1000, "length " + section.length());
      assertTrue(section.endsWith("\n" + TicketContextResolver.TRUNCATION_NOTE), section);
      assertTrue(section.contains("- [ ] criterion "), "criteria come before anything is cut");
    }
  }

  @Nested
  class AcceptanceCriteria {

    @Test
    void theAcceptanceSectionWinsOverTaskListsElsewhere() {
      var extracted =
          TicketContextResolver.extract(
              """
              - [ ] a stray task
              ## Acceptance Criteria
              - first
              * [X] second
              #### Detail
              + third
              ## Other
              - [ ] not this
              """);

      assertEquals(List.of("first", "[x] second", "third"), extracted.criteria());
      assertTrue(extracted.rest().contains("- [ ] a stray task"));
      assertTrue(extracted.rest().contains("- [ ] not this"));
    }

    @Test
    void aBoldDefinitionOfDoneLineOpensASection() {
      var extracted =
          TicketContextResolver.extract(
              "**Definition of Done:**\n- tests pass\n- docs updated\n### Next\n- nope\n");

      assertEquals(List.of("tests pass", "docs updated"), extracted.criteria());
    }

    @Test
    void withoutASectionTaskListItemsAreTheCriteriaExceptAnIssueFormsOwn() {
      var extracted =
          TicketContextResolver.extract(
              """
              ### Proposed Solution
              - [ ] resolve links
              - plain bullet
              ### Code of Conduct
              - [x] I have searched for existing issues
              """);

      assertEquals(List.of("[ ] resolve links"), extracted.criteria());
      assertTrue(extracted.rest().contains("I have searched"));
      assertTrue(extracted.rest().contains("- plain bullet"));
    }

    @Test
    void linesInFencedCodeAreNeverCriteria() {
      var extracted =
          TicketContextResolver.extract(
              "## Acceptance criteria\n```\n- [ ] in code\n## Not a heading\n```\n- real\n");

      assertEquals(List.of("real"), extracted.criteria());
    }

    @Test
    void headingsAreRecognizedTheWayMarkdownDrawsThem() {
      assertEquals(
          new TicketContextResolver.Heading(2, "Acceptance"),
          TicketContextResolver.Heading.parse("  ## Acceptance ##  "));
      assertEquals(
          new TicketContextResolver.Heading(1, ""), TicketContextResolver.Heading.parse("#"));
      assertEquals(
          new TicketContextResolver.Heading(7, "Done when"),
          TicketContextResolver.Heading.parse("**Done when**:"));
      assertEquals(null, TicketContextResolver.Heading.parse("####### seven"));
      assertEquals(null, TicketContextResolver.Heading.parse("#hashtag"));
      assertEquals(null, TicketContextResolver.Heading.parse("    ## indented code"));
      assertEquals(null, TicketContextResolver.Heading.parse("****"));
      assertEquals(null, TicketContextResolver.Heading.parse("**bold** and more"));
      assertEquals(null, TicketContextResolver.Heading.parse("plain"));
    }

    @Test
    void indentedCodeIsNeverACriterionButANestedItemIs() {
      var extracted =
          TicketContextResolver.extract(
              """
              ## Acceptance criteria
              Example:

                  - [ ] build with make

              - [ ] top item
                that wraps onto a second line
                  - [ ] nested item

                  - [ ] continued after a blank line
              text
              lazy
                  - [ ] lazy continuation
              """);

      assertEquals(
          List.of(
              "[ ] top item",
              "[ ] nested item",
              "[ ] continued after a blank line",
              "[ ] lazy continuation"),
          extracted.criteria());
      assertTrue(extracted.rest().contains("build with make"), extracted.rest());
    }

    @Test
    void aBodyThatOpensWithIndentedCodeKeepsItOutOfTheCriteria() {
      var extracted =
          TicketContextResolver.extract("    - [ ] code\n\n    - [ ] still code\n- [ ] real\n");

      assertEquals(List.of("[ ] real"), extracted.criteria());
    }

    @Test
    void aSubHeadingNamingANonCriteriaSectionClosesTheAcceptanceSection() {
      var extracted =
          TicketContextResolver.extract(
              """
              ## Acceptance criteria
              **Backend**
              - a
              **Non-goals:**
              - b
              ### Out of scope
              - c
              """);

      assertEquals(List.of("a"), extracted.criteria());
      assertTrue(extracted.rest().contains("- b"), extracted.rest());
    }

    @Test
    void aBoldLabelUnderABoldOpenerStaysInTheSectionUntilAnAtxHeading() {
      var extracted =
          TicketContextResolver.extract(
              """
              **Acceptance criteria:**
              - [ ] a
              **Details**
              - [ ] b
              **Additional context**
              - [ ] c
              ###### Anything
              - [ ] d
              """);

      assertEquals(List.of("[ ] a", "[ ] b"), extracted.criteria());
    }

    @Test
    void nonCriteriaSectionNamesMatchWholeWordStarts() {
      assertTrue(TicketContextResolver.namesNonCriteriaSection("Non-goals:"));
      assertTrue(TicketContextResolver.namesNonCriteriaSection("Notes"));
      assertTrue(TicketContextResolver.namesNonCriteriaSection("Open questions"));
      assertFalse(TicketContextResolver.namesNonCriteriaSection("Backend"));
      assertFalse(TicketContextResolver.namesNonCriteriaSection("Denotes x"));
    }

    @Test
    void anEmptyBodyHasNoCriteria() {
      var extracted = TicketContextResolver.extract("  ");
      assertEquals(List.of(), extracted.criteria());
      assertEquals("", extracted.rest());
    }

    @Test
    void anEmptyAcceptanceSectionFallsBackToTaskLists() {
      var extracted =
          TicketContextResolver.extract("## Acceptance criteria\nTBD\n## Tasks\n- [ ] do it\n");

      assertEquals(List.of("[ ] do it"), extracted.criteria());
    }
  }
}
