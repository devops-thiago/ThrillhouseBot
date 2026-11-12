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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** #1321: the head-commit reads behind presence, once per file and within their own cap. */
class HeadFileLinesTest {

  private final GitHubPullRequestClient prClient = mock(GitHubPullRequestClient.class);
  private final HeadFileLines headFiles =
      new HeadFileLines(prClient, "Bearer tok", "owner", "repo", "headsha");

  private static GitHubPullRequestClient.FileContent content(String text) {
    var encoded = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    return new GitHubPullRequestClient.FileContent("A.java", "A.java", encoded, "base64", 10);
  }

  @Test
  void aFileIsReadAtTheHeadOnceAndItsLinesKept() {
    when(prClient.getFileContent(any(), any(), eq("owner"), eq("repo"), eq("src/A.java"), any()))
        .thenReturn(content("a();\nb();\n"));

    assertEquals(Optional.of(List.of("a();", "b();")), headFiles.lines("src/A.java"));
    assertEquals(Optional.of(List.of("a();", "b();")), headFiles.lines("src/A.java"));
    verify(prClient, times(1))
        .getFileContent(any(), any(), eq("owner"), eq("repo"), eq("src/A.java"), eq("headsha"));
  }

  @Test
  void anUnreadableFileAnswersEmpty() {
    when(prClient.getFileContent(any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("boom"));

    assertEquals(Optional.empty(), headFiles.lines("src/A.java"));
  }

  @Test
  void filesPastTheCapAreNotRead() {
    when(prClient.getFileContent(any(), any(), any(), any(), anyString(), any()))
        .thenReturn(content("x();"));
    for (int i = 0; i < HeadFileLines.MAX_FILES; i++) {
      assertEquals(Optional.of(List.of("x();")), headFiles.lines("src/F" + i + ".java"));
    }

    assertEquals(Optional.empty(), headFiles.lines("src/OneTooMany.java"));
    verify(prClient, times(HeadFileLines.MAX_FILES))
        .getFileContent(any(), any(), any(), any(), anyString(), any());
  }
}
