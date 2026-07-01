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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.PullRequestDetails;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code getPullRequestFiles} default pagination loop: GitHub returns only 30 files per
 * page by default, so the diff must be assembled by walking pages of {@value
 * GitHubPullRequestClient#FILES_PER_PAGE}, bounded by {@value
 * GitHubPullRequestClient#MAX_FILE_PAGES}.
 */
class GitHubPullRequestClientTest {

  private static List<FileDiff> page(int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> new FileDiff("f" + i + ".java", "modified", 1, 0, 1, "@@ patch"))
        .toList();
  }

  /** A mock whose default {@code getPullRequestFiles} runs the real pagination loop. */
  private static GitHubPullRequestClient pagingClient() {
    var client = mock(GitHubPullRequestClient.class);
    when(client.getPullRequestFiles(anyString(), anyString(), anyString(), anyString(), anyInt()))
        .thenCallRealMethod();
    return client;
  }

  @Test
  void walksEveryPageUntilAShortPage() {
    var client = pagingClient();
    when(client.getPullRequestFilesPage("auth", "json", "o", "r", 7, 100, 1)).thenReturn(page(100));
    when(client.getPullRequestFilesPage("auth", "json", "o", "r", 7, 100, 2)).thenReturn(page(100));
    when(client.getPullRequestFilesPage("auth", "json", "o", "r", 7, 100, 3)).thenReturn(page(42));

    var all = client.getPullRequestFiles("auth", "json", "o", "r", 7);

    // 242 files across 3 pages — not silently truncated to the first 30.
    assertEquals(242, all.size());
    verify(client).getPullRequestFilesPage("auth", "json", "o", "r", 7, 100, 3);
    verify(client, never()).getPullRequestFilesPage("auth", "json", "o", "r", 7, 100, 4);
  }

  @Test
  void stopsAfterOnePageWhenNotFull() {
    var client = pagingClient();
    when(client.getPullRequestFilesPage("auth", "json", "o", "r", 7, 100, 1)).thenReturn(page(10));

    var all = client.getPullRequestFiles("auth", "json", "o", "r", 7);

    assertEquals(10, all.size());
    verify(client, times(1))
        .getPullRequestFilesPage(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void isBoundedByMaxPagesSoAFullLastPageCannotLoopForever() {
    var client = pagingClient();
    when(client.getPullRequestFilesPage(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt()))
        .thenReturn(page(100));

    var all = client.getPullRequestFiles("auth", "json", "o", "r", 7);

    assertEquals(100 * GitHubPullRequestClient.MAX_FILE_PAGES, all.size());
    verify(client, times(GitHubPullRequestClient.MAX_FILE_PAGES))
        .getPullRequestFilesPage(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void toleratesANullPageWithoutFailing() {
    var client = pagingClient();
    when(client.getPullRequestFilesPage("auth", "json", "o", "r", 7, 100, 1)).thenReturn(null);

    assertEquals(0, client.getPullRequestFiles("auth", "json", "o", "r", 7).size());
  }

  @Test
  void deserializesAuthoritativePrTotalsFromTheGitHubSnakeCaseFields() throws Exception {
    // GitHub's pulls payload uses changed_files (snake_case); the @JsonProperty mapping must hold
    // so
    // the summary's "Changes Overview" reads the authoritative totals rather than 0 (#298).
    var json =
        "{\"title\":\"T\",\"body\":\"B\",\"changed_files\":27,\"additions\":975,\"deletions\":196}";

    var pr = new ObjectMapper().readValue(json, PullRequestDetails.class);

    assertEquals(27, pr.changedFiles());
    assertEquals(975, pr.additions());
    assertEquals(196, pr.deletions());
  }
}
