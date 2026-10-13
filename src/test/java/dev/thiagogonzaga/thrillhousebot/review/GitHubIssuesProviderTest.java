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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubGraphQLClient;
import jakarta.ws.rs.WebApplicationException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GitHubIssuesProviderTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private GitHubCommentClient commentClient;
  private GitHubGraphQLClient graphQLClient;
  private GitHubIssuesProvider provider;

  @BeforeEach
  void setUp() {
    commentClient = mock(GitHubCommentClient.class);
    graphQLClient = mock(GitHubGraphQLClient.class);
    provider = new GitHubIssuesProvider(commentClient, graphQLClient);
    // Every number reads as an issue titled after itself unless a test says otherwise.
    when(commentClient.getIssue(anyString(), anyString(), eq("o"), eq("r"), anyInt()))
        .thenAnswer(
            inv -> {
              int n = inv.getArgument(4);
              return new GitHubCommentClient.IssueDetails(n, "issue " + n, "body " + n);
            });
  }

  private static IssueTrackerProvider.TicketLookup lookup(String body, boolean fromBranch) {
    return new IssueTrackerProvider.TicketLookup("auth", "o", "r", 42, body, fromBranch);
  }

  private void graphQl(String json) throws Exception {
    doReturn(JSON.readTree(json)).when(graphQLClient).execute(anyString(), any());
  }

  private static String links(String headRef, String... nameWithOwnerAndNumber) {
    var nodes = new StringBuilder();
    for (var i = 0; i < nameWithOwnerAndNumber.length; i += 2) {
      if (!nodes.isEmpty()) {
        nodes.append(',');
      }
      nodes
          .append("{\"number\":")
          .append(nameWithOwnerAndNumber[i + 1])
          .append(",\"repository\":{\"nameWithOwner\":\"")
          .append(nameWithOwnerAndNumber[i])
          .append("\"}}");
    }
    return "{\"data\":{\"repository\":{\"pullRequest\":{\"headRefName\":\""
        + headRef
        + "\",\"closingIssuesReferences\":{\"nodes\":["
        + nodes
        + "]}}}}}";
  }

  private static List<String> keys(List<IssueTrackerProvider.LinkedTicket> tickets) {
    return tickets.stream().map(IssueTrackerProvider.LinkedTicket::key).toList();
  }

  @Test
  void isNamedGithub() {
    assertEquals("github", provider.name());
  }

  @Nested
  class BodyReferences {

    @Test
    void readsEveryClosingKeywordFormInOrder() {
      var body =
          "Closes #1, fixes: #2. Resolved #3\n"
              + "close #4 and FIXED #5 and resolves #6 and fix #7 and closed #8 and resolve #9";
      assertEquals(
          List.of(1, 2, 3, 4, 5, 6, 7, 8, 9), GitHubIssuesProvider.bodyReferences(body, "o", "r"));
    }

    @Test
    void acceptsSameRepositoryQualifiedAndUrlReferencesIgnoringCase() {
      var body = "Closes O/R#11\nFixes https://github.com/o/R/issues/12";
      assertEquals(List.of(11, 12), GitHubIssuesProvider.bodyReferences(body, "o", "r"));
      assertEquals(
          List.of(13),
          GitHubIssuesProvider.bodyReferences("FIXES HTTPS://GitHub.COM/O/r/issues/13", "o", "r"));
    }

    @Test
    void ignoresCrossRepositoryReferences() {
      var body = "Closes other/repo#1\nFixes https://github.com/o/other/issues/2\nResolves #3";
      assertEquals(List.of(3), GitHubIssuesProvider.bodyReferences(body, "o", "r"));
    }

    @Test
    void ignoresMentionsWithoutAClosingKeyword() {
      assertEquals(
          List.of(),
          GitHubIssuesProvider.bodyReferences("Related to #1, see #2, prefix#3", "o", "r"));
      assertEquals(List.of(), GitHubIssuesProvider.bodyReferences("prefixes #4", "o", "r"));
      assertEquals(
          List.of(),
          GitHubIssuesProvider.bodyReferences("This fixes the bug and closes a gap", "o", "r"));
      assertEquals(List.of(), GitHubIssuesProvider.bodyReferences("Fixes #12345678901", "o", "r"));
    }

    @Test
    void skipsCommentsFencedBlocksAndCodeSpans() {
      var body =
          """
          <!-- Closes #1 -->
          ```
          Fixes #2
          ```
          ~~~
          fixes #5
          ~~~
          Use `closes #3` to link. Resolves #4""";
      assertEquals(List.of(4), GitHubIssuesProvider.bodyReferences(body, "o", "r"));
    }

    @Test
    void skipsCodeSpansOfAnyWidthAndSpansThatWrapALine() {
      assertEquals(
          List.of(4),
          GitHubIssuesProvider.bodyReferences(
              "``Closes #1`` and ``a ` Fixes #2`` and `cmd closes #3\nflag` Resolves #4",
              "o",
              "r"));
    }

    @Test
    void anUnclosedCommentHidesTheRestOfTheBodyLikeHtml() {
      assertEquals(
          List.of(1),
          GitHubIssuesProvider.bodyReferences("Closes #1\n<!-- Closes #2\nFixes #3", "o", "r"));
    }

    @Test
    void aBacktickWithoutACloserInItsParagraphIsText() {
      // The span would have to cross a blank line, so neither backtick opens one.
      assertEquals(
          List.of(1, 2, 3),
          GitHubIssuesProvider.bodyReferences(
              "a ` Closes #1\n\nFixes #2 ` b ` c ``\n\n~ Resolves #3 ~~", "o", "r"));
    }

    @Test
    void aBracketedReferenceAfterTheKeywordStillLinks() {
      assertEquals(
          List.of(12, 13, 14),
          GitHubIssuesProvider.bodyReferences(
              "resolves (https://github.com/o/r/issues/12) fixes [#13] (closes #14) fixes (x)",
              "o",
              "r"));
    }

    @Test
    void aCrlfBlankLineEndsAParagraphLikeAnLfOne() {
      assertEquals(
          List.of(2), GitHubIssuesProvider.bodyReferences("x ` y\r\n\r\nFixes #2 ` b", "o", "r"));
      assertEquals(
          List.of(3), GitHubIssuesProvider.bodyReferences("x ` y\r\rCloses #3 ` b", "o", "r"));
    }

    @Test
    void stripsLongRunsOfSpansInOnePass() {
      var body = "` ".repeat(50_000) + "Closes #7";
      assertEquals(List.of(7), GitHubIssuesProvider.bodyReferences(body, "o", "r"));
      assertEquals("  x  ", GitHubIssuesProvider.withoutNonLinkRegions("`a` x ``b``"));
      assertEquals("x ` y", GitHubIssuesProvider.withoutNonLinkRegions("x ` y"));
    }

    @Test
    void anUnclosedFenceHidesTheRestOfTheBody() {
      assertEquals(
          List.of(1), GitHubIssuesProvider.bodyReferences("Closes #1\n```\nFixes #2", "o", "r"));
    }

    @Test
    void aMidLineMarkerRunWithoutACloserIsText() {
      // #1032: a run of three or more in the middle of a line is no fence.
      for (var marker : List.of("```", "~~~", "````", "~~~~")) {
        var body = "Prose mentioning " + marker + " mid-line.\n\nCloses #17";
        assertEquals(List.of(17), GitHubIssuesProvider.bodyReferences(body, "owner", "repo"));
      }
      assertEquals(List.of(3), GitHubIssuesProvider.bodyReferences("a ``` b Closes #3", "o", "r"));
    }

    @Test
    void aMidLineBacktickRunWithAMatchingCloserIsACodeSpan() {
      assertEquals(
          List.of(2),
          GitHubIssuesProvider.bodyReferences("Prose ```Closes #1``` here. Fixes #2", "o", "r"));
      // A closer of another width does not close it, so the run stays literal.
      assertEquals(
          List.of(1, 2),
          GitHubIssuesProvider.bodyReferences("Prose ```Closes #1`` here. Fixes #2", "o", "r"));
      // At a line start, a backtick in the would-be info string makes it a span, not a fence.
      assertEquals(
          List.of(2), GitHubIssuesProvider.bodyReferences("```Closes #1``` Fixes #2", "o", "r"));
    }

    @Test
    void aFenceAtAValidLineStartStillHidesItsContents() {
      for (var opener :
          List.of(
              "```",
              "   ```",
              ">    ```",
              "> ```",
              ">```",
              "- ```",
              "* ~~~",
              "+ ```",
              "12. ```",
              "3) ```",
              "> - ```")) {
        var close = opener.contains("~") ? "~~~" : "```";
        var body = "Intro.\n" + opener + "\nCloses #1\n" + close + "\nFixes #2";
        assertEquals(List.of(2), GitHubIssuesProvider.bodyReferences(body, "o", "r"), opener);
      }
      // A tilde fence's info string may hold backticks.
      assertEquals(
          List.of(2),
          GitHubIssuesProvider.bodyReferences("~~~ `x`\nCloses #1\n~~~\nFixes #2", "o", "r"));
    }

    @Test
    void notEveryLineStartRunOpensOrClosesAFence() {
      // Text before the marker, or a marker glued to it, opens no fence.
      for (var opener : List.of("x ```", "-```", "a. ```", "1234567890. ```", "x - ```")) {
        var body = opener + " Closes #1\n\nFixes #2";
        assertEquals(List.of(1, 2), GitHubIssuesProvider.bodyReferences(body, "o", "r"), opener);
      }
      // Only a line holding a run at least as long as the opener, and nothing else, closes it.
      var body = "````\nFixes #1\n```\n```` trailing text\nFixes #2\n`````  \nCloses #3";
      assertEquals(List.of(3), GitHubIssuesProvider.bodyReferences(body, "o", "r"));
      assertEquals(
          List.of(4),
          GitHubIssuesProvider.bodyReferences("~~~\nFixes #1\n```\n~~~\nCloses #4", "o", "r"));
    }

    @Test
    void anIndentedCodeBlockLineOpensNoFence() {
      // Four columns of indentation make an indented code block, where a run is literal text;
      // so do five after a list or blockquote marker, and a list number in non-ASCII digits.
      for (var opener : List.of("    ```", "\t```", ">     ```", "-     ```", "\u0661. ```")) {
        var body = "Intro.\n" + opener + "\nCloses #1\n\nFixes #2";
        assertEquals(List.of(1, 2), GitHubIssuesProvider.bodyReferences(body, "o", "r"), opener);
      }
    }

    @Test
    void anOverIndentedMarkerDoesNotCloseAFence() {
      for (var marker : List.of("```", "~~~", "````", "~~~~")) {
        for (var indent : List.of("    ", "\t", "     ")) {
          var body = marker + "\n" + indent + marker + "\nCloses #1\n" + marker + "\nFixes #2";
          assertEquals(List.of(2), GitHubIssuesProvider.bodyReferences(body, "o", "r"), body);
        }
        for (var indent : List.of("", " ", "  ", "   ")) {
          var body = marker + "\nCloses #1\n" + indent + marker + "\nFixes #2";
          assertEquals(List.of(2), GitHubIssuesProvider.bodyReferences(body, "o", "r"), body);
        }
      }
    }

    @Test
    void containerIndentationStillAllowsItsFenceToClose() {
      for (var pair :
          List.of(
              List.of("- ~~~", "    ~~~"),
              List.of("-   ~~~", "       ~~~"),
              List.of("-    ~~~", "        ~~~"),
              List.of("12. ```", "    ```"),
              List.of("> ~~~", ">    ~~~"),
              List.of("> > ~~~", ">    >    ~~~"))) {
        var body = pair.getFirst() + "\nCloses #1\n" + pair.getLast() + "\nFixes #2";
        assertEquals(List.of(2), GitHubIssuesProvider.bodyReferences(body, "o", "r"), body);
      }
    }

    @Test
    void anOverIndentedCloserInsideAContainerStaysCode() {
      for (var pair :
          List.of(
              List.of("> ~~~", "    > ~~~"),
              List.of("> ~~~", ">     ~~~"),
              List.of("- ~~~", "      ~~~"))) {
        var body = pair.getFirst() + "\n" + pair.getLast() + "\nCloses #1\n~~~\nFixes #2";
        assertEquals(List.of(2), GitHubIssuesProvider.bodyReferences(body, "o", "r"), body);
      }
    }

    @Test
    void fencesAtTheEdgesOfTheBodyOpenAndCloseLikeAnyOther() {
      // Opened on the body's first line, with or without a container marker before it.
      for (var opener : List.of("```", "  ```", "> ```", "1. ```")) {
        var body = opener + "\nCloses #1\n```\nFixes #2";
        assertEquals(List.of(2), GitHubIssuesProvider.bodyReferences(body, "o", "r"), opener);
      }
      // Opened on the last line: it runs to the end, and so does one closed by nothing.
      assertEquals(List.of(1), GitHubIssuesProvider.bodyReferences("Closes #1\n```", "o", "r"));
      assertEquals(
          List.of(2), GitHubIssuesProvider.bodyReferences("Fixes #2\n```\nCloses #1\n", "o", "r"));
      // Closed on the last line, by an indented closer, or by one with trailing blanks.
      assertEquals(
          List.of(2),
          GitHubIssuesProvider.bodyReferences("Fixes #2\n```\nCloses #1\n```", "o", "r"));
      assertEquals(
          List.of(2),
          GitHubIssuesProvider.bodyReferences("```\nCloses #1\n  > ```\t \nFixes #2", "o", "r"));
    }

    @Test
    void stripsLongRunsOfMidLineMarkersInOnePass() {
      var body = "x ``` y ~~~ ".repeat(20_000) + "\n```x`\n".repeat(20_000) + "Closes #7";
      assertEquals(List.of(7), GitHubIssuesProvider.bodyReferences(body, "o", "r"));
    }

    @Test
    void deduplicatesAndHandlesAnEmptyBody() {
      assertEquals(
          List.of(5), GitHubIssuesProvider.bodyReferences("Fixes #5. Closes #5", "o", "r"));
      assertEquals(List.of(), GitHubIssuesProvider.bodyReferences(null, "o", "r"));
      assertEquals(List.of(), GitHubIssuesProvider.bodyReferences("  ", "o", "r"));
    }
  }

  @Nested
  class BranchNames {

    @Test
    void readsTheIssueNumberFromConventionalBranchNames() {
      assertEquals(57, GitHubIssuesProvider.branchIssueNumber("issue-57"));
      assertEquals(57, GitHubIssuesProvider.branchIssueNumber("issues/57"));
      assertEquals(57, GitHubIssuesProvider.branchIssueNumber("fix/57-short-name"));
      assertEquals(58, GitHubIssuesProvider.branchIssueNumber("feat/58-issue-tracker-context"));
      assertEquals(57, GitHubIssuesProvider.branchIssueNumber("user/GH-57"));
      assertEquals(57, GitHubIssuesProvider.branchIssueNumber("57"));
      assertEquals(57, GitHubIssuesProvider.branchIssueNumber("fix_57_thing"));
    }

    @Test
    void findsNoNumberInOtherBranchNames() {
      assertEquals(0, GitHubIssuesProvider.branchIssueNumber("release/v0.7.0"));
      assertEquals(0, GitHubIssuesProvider.branchIssueNumber("dependabot/maven/foo-1.2.3"));
      assertEquals(0, GitHubIssuesProvider.branchIssueNumber("main"));
      assertEquals(0, GitHubIssuesProvider.branchIssueNumber("feature/login-57"));
      assertEquals(0, GitHubIssuesProvider.branchIssueNumber(""));
      assertEquals(0, GitHubIssuesProvider.branchIssueNumber(null));
    }
  }

  @Nested
  class Precedence {

    @Test
    void bodyLinksComeFirstThenClosingReferencesAreAdded() throws Exception {
      graphQl(links("feat/9-x", "o/r", "3", "o/r", "1"));

      var tickets = provider.linkedTickets(lookup("Closes #1", true), 3);

      assertEquals(List.of("#1", "#3"), keys(tickets));
      assertEquals(GitHubIssuesProvider.VIA_BODY, tickets.get(0).via());
      assertEquals(GitHubIssuesProvider.VIA_CLOSING_REFERENCE, tickets.get(1).via());
      assertEquals("issue 1", tickets.get(0).title());
      assertEquals("body 1", tickets.get(0).body());
    }

    @Test
    void closingReferencesToOtherRepositoriesAreIgnored() throws Exception {
      graphQl(links("main", "someone/else", "3", "O/R", "4"));

      assertEquals(List.of("#4"), keys(provider.linkedTickets(lookup("", false), 3)));
      verify(commentClient, never()).getIssue(any(), any(), any(), any(), eq(3));
    }

    @Test
    void theBranchNameIsAFallbackOnlyWhenNothingElseLinks() throws Exception {
      graphQl(links("fix/9-thing"));

      var tickets = provider.linkedTickets(lookup("no links here", true), 3);

      assertEquals(List.of("#9"), keys(tickets));
      assertEquals(GitHubIssuesProvider.VIA_BRANCH, tickets.get(0).via());
    }

    @Test
    void aReferenceAfterAMidLineMarkerLinksWhenTheFallbackHasNothing() throws Exception {
      // #1032: the body is the only source when GitHub records no closing reference.
      graphQl(links("main"));
      for (var marker : List.of("```", "~~~")) {
        var body = "Prose mentioning " + marker + " mid-line.\n\nCloses #17";
        assertEquals(List.of("#17"), keys(provider.linkedTickets(lookup(body, false), 3)));
      }
      doThrow(new IllegalStateException("down")).when(graphQLClient).execute(anyString(), any());
      assertEquals(
          List.of("#17"),
          keys(
              provider.linkedTickets(
                  lookup("Prose mentioning ``` mid-line.\n\nCloses #17", false), 3)));
    }

    @Test
    void theBranchNameNeverAddsToAnExplicitLink() throws Exception {
      graphQl(links("fix/9-thing"));

      assertEquals(List.of("#1"), keys(provider.linkedTickets(lookup("Fixes #1", true), 3)));
    }

    @Test
    void theBranchNameIsIgnoredWhileTheSwitchIsOff() throws Exception {
      graphQl(links("fix/9-thing"));

      assertEquals(List.of(), provider.linkedTickets(lookup("", false), 3));
      verifyNoInteractions(commentClient);
    }

    @Test
    void aBranchWithoutANumberLinksNothing() throws Exception {
      graphQl(links("release/v0.7.0"));

      assertEquals(List.of(), provider.linkedTickets(lookup("", true), 3));
    }

    @Test
    void issueZeroIsNeverACandidate() throws Exception {
      graphQl(links("main", "o/r", "0"));

      assertEquals(List.of(), provider.linkedTickets(lookup("Closes #0", false), 3));
      verifyNoInteractions(commentClient);
    }

    @Test
    void thePullRequestsOwnNumberIsNeverItsTicket() throws Exception {
      graphQl(links("fix/42-self", "o/r", "42"));

      assertEquals(
          List.of("#7"), keys(provider.linkedTickets(lookup("Closes #42 Fixes #7", true), 3)));
      verify(commentClient, never()).getIssue(any(), any(), any(), any(), eq(42));
    }

    @Test
    void theClosingReferenceReadIsSkippedWhenTheBodyFillsTheCap() {
      var tickets = provider.linkedTickets(lookup("Closes #1 Closes #2", false), 2);

      assertEquals(List.of("#1", "#2"), keys(tickets));
      verifyNoInteractions(graphQLClient);
    }

    @Test
    void theQueryAsksForThisPullRequest() throws Exception {
      graphQl(links("main"));

      provider.linkedTickets(lookup("", false), 3);

      var request = ArgumentCaptor.forClass(GitHubGraphQLClient.GraphQLRequest.class);
      verify(graphQLClient).execute(eq("auth"), request.capture());
      assertEquals(GitHubIssuesProvider.LINKS_QUERY, request.getValue().query());
      assertEquals("o", request.getValue().variables().get("owner"));
      assertEquals("r", request.getValue().variables().get("name"));
      assertEquals(42, request.getValue().variables().get("number"));
      assertTrue(GitHubIssuesProvider.LINKS_QUERY.contains("closingIssuesReferences(first: 10)"));
    }
  }

  @Nested
  class Caps {

    @Test
    void stopsAtTheLimit() throws Exception {
      graphQl(links("main"));

      var tickets =
          provider.linkedTickets(lookup("Closes #1 Closes #2 Closes #3 Closes #4", false), 3);

      assertEquals(List.of("#1", "#2", "#3"), keys(tickets));
      verify(commentClient, never()).getIssue(any(), any(), any(), any(), eq(4));
    }

    @Test
    void anUnreadableCandidateLetsTheNextOneIn() throws Exception {
      graphQl(links("main"));
      when(commentClient.getIssue(anyString(), anyString(), eq("o"), eq("r"), eq(2)))
          .thenThrow(new WebApplicationException(404));

      var tickets = provider.linkedTickets(lookup("Closes #1 Closes #2 Closes #3", false), 2);

      assertEquals(List.of("#1", "#3"), keys(tickets));
    }

    @Test
    void triesAtMostTheCandidateCap() throws Exception {
      graphQl(links("main"));
      when(commentClient.getIssue(anyString(), anyString(), eq("o"), eq("r"), anyInt()))
          .thenThrow(new WebApplicationException(404));
      var body = new StringBuilder();
      for (var i = 1; i <= 15; i++) {
        body.append("Closes #").append(i).append('\n');
      }

      assertEquals(List.of(), provider.linkedTickets(lookup(body.toString(), false), 5));
      verify(commentClient, times(GitHubIssuesProvider.MAX_CANDIDATES))
          .getIssue(any(), any(), any(), any(), anyInt());
    }
  }

  @Nested
  class UnreadableIssues {

    @Test
    void aMissingDeletedOrPrivateIssueIsSkipped() throws Exception {
      graphQl(links("main"));
      when(commentClient.getIssue(anyString(), anyString(), eq("o"), eq("r"), eq(1)))
          .thenThrow(new WebApplicationException(404));
      when(commentClient.getIssue(anyString(), anyString(), eq("o"), eq("r"), eq(2)))
          .thenThrow(new WebApplicationException(410));
      when(commentClient.getIssue(anyString(), anyString(), eq("o"), eq("r"), eq(3)))
          .thenThrow(new WebApplicationException(403));

      assertEquals(
          List.of(), provider.linkedTickets(lookup("Closes #1 Closes #2 Closes #3", false), 3));
    }

    @Test
    void aPullRequestNumberIsNotAnIssue() throws Exception {
      graphQl(links("main"));
      when(commentClient.getIssue(anyString(), anyString(), eq("o"), eq("r"), eq(5)))
          .thenReturn(
              new GitHubCommentClient.IssueDetails(
                  5, "a PR", "", new GitHubCommentClient.IssueDetails.PullRequestRef("u")));
      when(commentClient.getIssue(anyString(), anyString(), eq("o"), eq("r"), eq(6)))
          .thenReturn(null);

      assertEquals(List.of(), provider.linkedTickets(lookup("Closes #5 Closes #6", false), 3));
    }

    @Test
    void aFailedOrPartialClosingReferenceReadKeepsTheBodyLinks() throws Exception {
      when(graphQLClient.execute(anyString(), any())).thenThrow(new WebApplicationException(502));
      assertEquals(List.of("#1"), keys(provider.linkedTickets(lookup("Closes #1", true), 3)));

      graphQl("{\"data\":null,\"errors\":[{\"message\":\"Resource not accessible\"}]}");
      assertEquals(List.of("#1"), keys(provider.linkedTickets(lookup("Closes #1", true), 3)));

      doReturn(null).when(graphQLClient).execute(anyString(), any());
      assertEquals(List.of("#1"), keys(provider.linkedTickets(lookup("Closes #1", true), 3)));
    }

    @Test
    void malformedClosingReferencesAreIgnored() throws Exception {
      JsonNode node =
          JSON.readTree(
              "{\"closingIssuesReferences\":{\"nodes\":[{\"number\":\"x\","
                  + "\"repository\":{\"nameWithOwner\":\"o/r\"}},{\"number\":8}]}}");
      assertEquals(List.of(), GitHubIssuesProvider.closingReferences(node, "o", "r"));
    }
  }

  @Test
  void onlyReadsNeverWrites() throws Exception {
    graphQl(links("fix/9-x", "o/r", "3"));

    provider.linkedTickets(lookup("Closes #1", true), 3);

    verify(commentClient, times(2)).getIssue(any(), any(), any(), any(), anyInt());
    verifyNoMoreInteractions(commentClient);
    verify(graphQLClient).execute(any(), any());
    verifyNoMoreInteractions(graphQLClient);
  }
}
