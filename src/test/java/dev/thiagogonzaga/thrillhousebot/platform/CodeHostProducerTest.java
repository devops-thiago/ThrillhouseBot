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
package dev.thiagogonzaga.thrillhousebot.platform;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.gitlab.GitLabMergeRequestClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CodeHostProducerTest {

  @ParameterizedTest
  @CsvSource({
    "github, dev.thiagogonzaga.thrillhousebot.github.GitHubCodeHost",
    "GitLab, dev.thiagogonzaga.thrillhousebot.gitlab.GitLabCodeHost"
  })
  void theConfiguredPlatformPicksTheCodeHost(String platform, Class<?> expected) {
    var config = mock(ThrillhouseConfig.class);
    when(config.platform()).thenReturn(platform);

    var host =
        new CodeHostProducer()
            .codeHost(
                config, mock(GitHubPullRequestClient.class), mock(GitLabMergeRequestClient.class));

    assertInstanceOf(expected, host);
  }
}
