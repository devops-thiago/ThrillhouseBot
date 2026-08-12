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
package dev.thiagogonzaga.thrillhousebot.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient.CommentResponse;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient.CreateCommentRequest;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient.IssueComment;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code listComments} default pagination loop: GitHub returns only 30 issue comments
 * per page by default, so the bot's summary can fall past page 1 on a busy PR and a single-page
 * fetch would miss it and re-post a duplicate. Comments are assembled by walking pages of {@value
 * GitHubCommentClient#COMMENTS_PER_PAGE}, bounded by {@value
 * GitHubCommentClient#MAX_COMMENT_PAGES}.
 */
class GitHubCommentClientTest {

  private static List<IssueComment> page(int count) {
    return IntStream.range(0, count).mapToObj(i -> new IssueComment("comment " + i, null)).toList();
  }

  /** A mock whose default {@code listComments} runs the real pagination loop. */
  private static GitHubCommentClient pagingClient() {
    var client = mock(GitHubCommentClient.class);
    when(client.listComments(anyString(), anyString(), anyString(), anyString(), anyInt()))
        .thenCallRealMethod();
    return client;
  }

  @Test
  void walksEveryPageUntilAShortPage() {
    var client = pagingClient();
    when(client.listCommentsPage("auth", "json", "o", "r", 7, 100, 1)).thenReturn(page(100));
    when(client.listCommentsPage("auth", "json", "o", "r", 7, 100, 2)).thenReturn(page(100));
    when(client.listCommentsPage("auth", "json", "o", "r", 7, 100, 3)).thenReturn(page(42));

    var all = client.listComments("auth", "json", "o", "r", 7);

    // 242 comments across 3 pages — not silently truncated to the first page.
    assertEquals(242, all.size());
    verify(client).listCommentsPage("auth", "json", "o", "r", 7, 100, 3);
    verify(client, never()).listCommentsPage("auth", "json", "o", "r", 7, 100, 4);
  }

  @Test
  void stopsAfterOnePageWhenNotFull() {
    var client = pagingClient();
    when(client.listCommentsPage("auth", "json", "o", "r", 7, 100, 1)).thenReturn(page(10));

    var all = client.listComments("auth", "json", "o", "r", 7);

    assertEquals(10, all.size());
    verify(client, times(1))
        .listCommentsPage(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void isBoundedByMaxPagesSoAFullLastPageCannotLoopForever() {
    var client = pagingClient();
    when(client.listCommentsPage(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt()))
        .thenReturn(page(100));

    var all = client.listComments("auth", "json", "o", "r", 7);

    assertEquals(100 * GitHubCommentClient.MAX_COMMENT_PAGES, all.size());
    verify(client, times(GitHubCommentClient.MAX_COMMENT_PAGES))
        .listCommentsPage(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void toleratesANullPageWithoutFailing() {
    var client = pagingClient();
    when(client.listCommentsPage("auth", "json", "o", "r", 7, 100, 1)).thenReturn(null);

    assertEquals(0, client.listComments("auth", "json", "o", "r", 7).size());
  }

  // GitHub's hard comment-body limit and the notice the client appends when it truncates. Kept as
  // literals here (not a reference to the production constants) so these tests pin the boundary and
  // wording independently of the code under test.
  private static final int MAX = 65_536;
  private static final String NOTICE = "\n\n… (truncated at GitHub's 65,536-character limit)";

  private static String repeat(char c, int n) {
    return String.valueOf(c).repeat(n);
  }

  @Test
  void createCommentBodyOverTheLimitIsTruncatedWithAnHonestNotice() {
    var body = repeat('a', MAX + 5_000);

    var capped = new CreateCommentRequest(body).body();

    // The request must never carry more than GitHub's limit — otherwise the POST 422s and the
    // fail-soft wrapper posts nothing.
    assertEquals(MAX, capped.length(), "over-long body must be capped to exactly the limit");
    assertTrue(capped.endsWith(NOTICE), "a truncated body must end with the truncation notice");
    assertTrue(capped.startsWith("aaaa"), "the surviving prefix is the original content");
  }

  @Test
  void createCommentBodyAtTheLimitIsNotTruncated() {
    var body = repeat('a', MAX);

    var capped = new CreateCommentRequest(body).body();

    assertEquals(MAX, capped.length());
    assertEquals(body, capped, "a body exactly at the limit passes through byte-identical");
  }

  @Test
  void createCommentBodyOneOverTheLimitIsTruncated() {
    var body = repeat('a', MAX + 1);

    var capped = new CreateCommentRequest(body).body();

    assertEquals(MAX, capped.length());
    assertTrue(capped.endsWith(NOTICE));
  }

  @Test
  void createCommentBodyUnderTheLimitPassesThroughUnchanged() {
    var body = "a normal comment body";

    var capped = new CreateCommentRequest(body).body();

    assertSame(body, capped, "a body within the limit is returned untouched");
  }

  /**
   * A 403 that GitHub sends while throttling a write, carrying a {@code Retry-After} of zero so the
   * backoff is real but instant. {@link GitHubWriteRetryTest} pins the waiting itself.
   */
  private static WebApplicationException throttledNow() {
    return new WebApplicationException(
        Response.status(403)
            .header("Retry-After", "0")
            .entity("{\"message\":\"You have exceeded a secondary rate limit.\"}")
            .build());
  }

  @Test
  void createCommentRepostsAThrottledCommentRatherThanLosingIt() {
    var client = mock(GitHubCommentClient.class);
    when(client.createComment(anyString(), anyString(), anyString(), anyString(), anyInt(), any()))
        .thenCallRealMethod();
    var request = new CreateCommentRequest("the generated reply");
    var posted = new CommentResponse(42, "https://github.test/c/42");
    when(client.createCommentOnce("auth", "json", "o", "r", 7, request))
        .thenThrow(throttledNow())
        .thenReturn(posted);

    assertSame(posted, client.createComment("auth", "json", "o", "r", 7, request));
    verify(client, times(2)).createCommentOnce("auth", "json", "o", "r", 7, request);
  }

  @Test
  void updateCommentRepostsAThrottledEditRatherThanLeavingAStaleSummary() {
    var client = mock(GitHubCommentClient.class);
    when(client.updateComment(anyString(), anyString(), anyString(), anyString(), anyLong(), any()))
        .thenCallRealMethod();
    var request = new CreateCommentRequest("the regenerated summary");
    var edited = new CommentResponse(99, "https://github.test/c/99");
    when(client.updateCommentOnce("auth", "json", "o", "r", 99L, request))
        .thenThrow(throttledNow())
        .thenReturn(edited);

    assertSame(edited, client.updateComment("auth", "json", "o", "r", 99L, request));
    verify(client, times(2)).updateCommentOnce("auth", "json", "o", "r", 99L, request);
  }
}
