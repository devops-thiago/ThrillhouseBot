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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileContent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Covers the path check of {@link GitHubPullRequestClient#getFileContent}. */
class GitHubPullRequestClientTest {

  /** A mock whose default {@code getFileContent} runs the real path check. */
  private static GitHubPullRequestClient contentClient() {
    var client = mock(GitHubPullRequestClient.class);
    when(client.getFileContent(any(), any(), any(), any(), any(), any())).thenCallRealMethod();
    return client;
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        " ",
        "..",
        "../other-repo/pulls",
        "src/../../../orgs/acme/members",
        "src/..",
        "/etc/passwd",
        "/src/Main.java",
        "src\\..\\..\\secrets",
        "\\src\\Main.java"
      })
  void aFilePathThatLeavesTheRepositoryIsAnsweredAsMissingWithoutARequest(String path) {
    var client = contentClient();

    assertNull(client.getFileContent("auth", "json", "o", "r", path, "sha"));

    verify(client, never()).getFileContentOnce(any(), any(), any(), any(), any(), any());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "pom.xml",
        "src/main/java/App.java",
        "docs/a..b.txt",
        "src/.../odd",
        ".github/workflows/ci.yml",
        "node_modules/@types/node/index.d.ts"
      })
  void anOrdinaryRepositoryPathIsStillRead(String path) {
    var client = contentClient();
    var file = new FileContent("f", path, "Zm9v", "base64", 3);
    when(client.getFileContentOnce("auth", "json", "o", "r", path, "sha")).thenReturn(file);

    assertSame(file, client.getFileContent("auth", "json", "o", "r", path, "sha"));
  }
}
