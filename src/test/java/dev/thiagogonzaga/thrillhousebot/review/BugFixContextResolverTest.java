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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BugFixContextResolver} — bug-fix detection from the PR body, closing
 * reference extraction, and the best-effort linked-issue fetch feeding the efficacy check (#110).
 */
class BugFixContextResolverTest {

  private final GitHubCommentClient commentClient = mock(GitHubCommentClient.class);
  private final BugFixContextResolver resolver = new BugFixContextResolver(commentClient);

  @Nested
  class IsBugFix {

    @Test
    void shouldDetectCheckedBugFixTemplateCheckbox() {
      assertTrue(BugFixContextResolver.isBugFix("- [x] 🐛 Bug fix\n- [ ] ✨ Feature"));
      assertTrue(BugFixContextResolver.isBugFix("* [X] Bug fix"));
      assertTrue(BugFixContextResolver.isBugFix("- [x] Bugfix"));
      assertTrue(BugFixContextResolver.isBugFix("- [x] Bug-fix"));
    }

    @Test
    void shouldDetectClosingReferences() {
      assertTrue(BugFixContextResolver.isBugFix("Fixes #89"));
      assertTrue(BugFixContextResolver.isBugFix("this closes #12 finally"));
      assertTrue(BugFixContextResolver.isBugFix("Resolves: #7"));
      assertTrue(BugFixContextResolver.isBugFix("fixed #3 and closed #4"));
    }

    @Test
    void shouldNotDetectUncheckedCheckboxOrPlainIssueMention() {
      assertFalse(BugFixContextResolver.isBugFix("- [ ] 🐛 Bug fix\n- [x] ✨ Feature"));
      assertFalse(BugFixContextResolver.isBugFix("- [x] Debug fixes"));
      assertFalse(BugFixContextResolver.isBugFix("Related to #89, see discussion"));
      assertFalse(BugFixContextResolver.isBugFix("prefix#12 is not a reference"));
      assertFalse(BugFixContextResolver.isBugFix(""));
      assertFalse(BugFixContextResolver.isBugFix(null));
    }
  }

  @Nested
  class LinkedIssueNumbers {

    @Test
    void shouldExtractDeduplicateAndCapReferences() {
      assertEquals(List.of(89), BugFixContextResolver.linkedIssueNumbers("Fixes #89, fixes #89"));
      assertEquals(
          List.of(1, 2, 3),
          BugFixContextResolver.linkedIssueNumbers("fixes #1 closes #2 resolves #3 fixes #4"));
      assertEquals(List.of(), BugFixContextResolver.linkedIssueNumbers("- [x] Bug fix, no ref"));
      assertEquals(List.of(), BugFixContextResolver.linkedIssueNumbers(null));
      assertEquals(List.of(), BugFixContextResolver.linkedIssueNumbers("  "));
    }
  }

  @Nested
  class LoadLinkedIssueContext {

    @Test
    void shouldRenderLinkedIssueTitleAndBody() {
      when(commentClient.getIssue(any(), any(), eq("o"), eq("r"), eq(89)))
          .thenReturn(new GitHubCommentClient.IssueDetails(89, "dedup slot burned", "the trigger"));

      var context = resolver.loadLinkedIssueContext("auth", "o", "r", "Fixes #89");

      assertEquals("### Linked issue #89: dedup slot burned\nthe trigger", context);
    }

    @Test
    void shouldReturnEmptyWhenNotABugFix() {
      assertEquals("", resolver.loadLinkedIssueContext("auth", "o", "r", "docs update"));
      verifyNoInteractions(commentClient);
    }

    @Test
    void shouldReturnEmptyWhenBugFixDeclaresNoIssueReference() {
      assertEquals("", resolver.loadLinkedIssueContext("auth", "o", "r", "- [x] 🐛 Bug fix"));
      verifyNoInteractions(commentClient);
    }

    @Test
    void shouldContinueWithoutIssueWhenFetchFails() {
      when(commentClient.getIssue(any(), any(), eq("o"), eq("r"), eq(1)))
          .thenThrow(new RuntimeException("boom"));
      when(commentClient.getIssue(any(), any(), eq("o"), eq("r"), eq(2)))
          .thenReturn(new GitHubCommentClient.IssueDetails(2, "still fetched", null));

      var context = resolver.loadLinkedIssueContext("auth", "o", "r", "fixes #1 and fixes #2");

      assertEquals("### Linked issue #2: still fetched", context);
    }

    @Test
    void shouldRenderHeaderOnlyWhenIssueHasNoTitleOrBody() {
      when(commentClient.getIssue(any(), any(), eq("o"), eq("r"), eq(9)))
          .thenReturn(new GitHubCommentClient.IssueDetails(9, null, "  "));

      assertEquals("### Linked issue #9", resolver.loadLinkedIssueContext("a", "o", "r", "fix #9"));
    }

    @Test
    void shouldRenderBodyWithoutTitle() {
      when(commentClient.getIssue(any(), any(), eq("o"), eq("r"), eq(8)))
          .thenReturn(new GitHubCommentClient.IssueDetails(8, "", "just a body"));

      assertEquals(
          "### Linked issue #8\njust a body",
          resolver.loadLinkedIssueContext("a", "o", "r", "fix #8"));
    }

    @Test
    void shouldNotSplitSurrogatePairAtTruncationBoundary() {
      // Body char at the exact cut boundary is the high surrogate of an emoji.
      var body =
          "x".repeat(BugFixContextResolver.MAX_ISSUE_BODY_CHARS - 1) + "\uD83D\uDC1B" + "tail";
      when(commentClient.getIssue(any(), any(), eq("o"), eq("r"), eq(6)))
          .thenReturn(new GitHubCommentClient.IssueDetails(6, "emoji", body));

      var context = resolver.loadLinkedIssueContext("auth", "o", "r", "closes #6");

      assertFalse(context.contains("\uD83D\n"));
      assertTrue(context.endsWith("[... issue body truncated]"));
    }

    @Test
    void shouldTruncateOversizedIssueBody() {
      var hugeBody = "x".repeat(BugFixContextResolver.MAX_ISSUE_BODY_CHARS + 100);
      when(commentClient.getIssue(any(), any(), eq("o"), eq("r"), eq(5)))
          .thenReturn(new GitHubCommentClient.IssueDetails(5, "big", hugeBody));

      var context = resolver.loadLinkedIssueContext("auth", "o", "r", "closes #5");

      assertTrue(context.endsWith("[... issue body truncated]"));
      assertTrue(
          context.length()
              < BugFixContextResolver.MAX_ISSUE_BODY_CHARS + 100 /* header + marker slack */);
    }

    @Test
    void shouldJoinMultipleIssuesWithBlankLine() {
      when(commentClient.getIssue(any(), any(), eq("o"), eq("r"), anyInt()))
          .thenAnswer(
              inv ->
                  new GitHubCommentClient.IssueDetails(
                      inv.getArgument(4), "t" + inv.getArgument(4, Integer.class), "b"));

      var context = resolver.loadLinkedIssueContext("auth", "o", "r", "fixes #1, closes #2");

      assertEquals("### Linked issue #1: t1\nb\n\n### Linked issue #2: t2\nb", context);
    }
  }
}
