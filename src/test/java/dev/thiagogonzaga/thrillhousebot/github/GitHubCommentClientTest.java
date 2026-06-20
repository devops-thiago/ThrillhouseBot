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

import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient.IssueComment;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code listComments} default pagination loop (#183): GitHub returns only 30 issue
 * comments per page by default, so the bot's summary can fall past page 1 on a busy PR and a
 * single-page fetch would miss it and re-post a duplicate. Comments are assembled by walking pages
 * of {@value GitHubCommentClient#COMMENTS_PER_PAGE}, bounded by {@value
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
}
