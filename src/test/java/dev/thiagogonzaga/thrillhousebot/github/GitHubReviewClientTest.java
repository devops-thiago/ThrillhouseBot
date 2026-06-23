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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient.PullRequestComment;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code listPullRequestComments} default pagination loop: GitHub returns only 30 inline
 * review comments per page by default, so follow-up dedup, {@code /resolve} and the
 * unresolved-status backstop would run on a silently truncated set on a busy PR. Comments are
 * assembled by walking pages of {@value GitHubReviewClient#COMMENTS_PER_PAGE}, bounded by {@value
 * GitHubReviewClient#MAX_COMMENT_PAGES}.
 */
class GitHubReviewClientTest {

  private static List<PullRequestComment> page(int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> new PullRequestComment(i, null, "src/Foo.java", "comment " + i, null))
        .toList();
  }

  /** A mock whose default {@code listPullRequestComments} runs the real pagination loop. */
  private static GitHubReviewClient pagingClient() {
    var client = mock(GitHubReviewClient.class);
    when(client.listPullRequestComments(
            anyString(), anyString(), anyString(), anyString(), anyInt()))
        .thenCallRealMethod();
    return client;
  }

  @Test
  void walksEveryPageUntilAShortPage() {
    var client = pagingClient();
    when(client.listPullRequestCommentsPage("auth", "json", "o", "r", 7, 100, 1))
        .thenReturn(page(100));
    when(client.listPullRequestCommentsPage("auth", "json", "o", "r", 7, 100, 2))
        .thenReturn(page(100));
    when(client.listPullRequestCommentsPage("auth", "json", "o", "r", 7, 100, 3))
        .thenReturn(page(42));

    var all = client.listPullRequestComments("auth", "json", "o", "r", 7);

    // 242 comments across 3 pages — not silently truncated to the first page.
    assertEquals(242, all.size());
    verify(client).listPullRequestCommentsPage("auth", "json", "o", "r", 7, 100, 3);
    verify(client, never()).listPullRequestCommentsPage("auth", "json", "o", "r", 7, 100, 4);
  }

  @Test
  void stopsAfterOnePageWhenNotFull() {
    var client = pagingClient();
    when(client.listPullRequestCommentsPage("auth", "json", "o", "r", 7, 100, 1))
        .thenReturn(page(10));

    var all = client.listPullRequestComments("auth", "json", "o", "r", 7);

    assertEquals(10, all.size());
    verify(client, times(1))
        .listPullRequestCommentsPage(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void isBoundedByMaxPagesSoAFullLastPageCannotLoopForever() {
    var client = pagingClient();
    when(client.listPullRequestCommentsPage(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt()))
        .thenReturn(page(100));

    var all = client.listPullRequestComments("auth", "json", "o", "r", 7);

    assertEquals(100 * GitHubReviewClient.MAX_COMMENT_PAGES, all.size());
    verify(client, times(GitHubReviewClient.MAX_COMMENT_PAGES))
        .listPullRequestCommentsPage(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void toleratesANullPageWithoutFailing() {
    var client = pagingClient();
    when(client.listPullRequestCommentsPage("auth", "json", "o", "r", 7, 100, 1)).thenReturn(null);

    assertEquals(0, client.listPullRequestComments("auth", "json", "o", "r", 7).size());
  }
}
