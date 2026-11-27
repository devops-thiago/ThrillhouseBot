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

import java.util.Optional;
import org.junit.jupiter.api.Test;

class GitHubUrlsTest {

  @Test
  void defaultsToGitHubDotCom() {
    assertEquals("https://github.com", GitHubUrls.webUrl(Optional.empty()));
    assertEquals("https://github.com", GitHubUrls.webUrl(Optional.of("  ")));
    assertEquals("https://github.com/o/r/pull/7", GitHubUrls.pullRequest("o", "r", 7));
  }

  @Test
  void takesAnEnterpriseHostWithoutItsTrailingSlashes() {
    assertEquals(
        "https://github.example.com",
        GitHubUrls.webUrl(Optional.of("https://github.example.com//")));
  }

  @Test
  void buildsAPullRequestPageFromTheFullRepositoryName() {
    assertEquals("https://github.com/o/r/pull/7", GitHubUrls.pullRequest("o/r", 7));
  }

  @Test
  void dropsOnlyTheScheme() {
    assertEquals("h.example.com:8443", GitHubUrls.withoutScheme("https://h.example.com:8443"));
    assertEquals("h.example.com/gh", GitHubUrls.withoutScheme("http://h.example.com/gh"));
    assertEquals("h.example.com", GitHubUrls.withoutScheme("h.example.com"));
  }

  @Test
  void trimsOnlyTrailingSlashes() {
    assertEquals("https://h/api/v3", GitHubUrls.withoutTrailingSlash("https://h/api/v3/"));
    assertEquals("https://h", GitHubUrls.withoutTrailingSlash("https://h"));
    assertEquals("", GitHubUrls.withoutTrailingSlash("/"));
  }
}
