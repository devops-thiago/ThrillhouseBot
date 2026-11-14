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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCodeHost;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient;
import dev.thiagogonzaga.thrillhousebot.gitlab.GitLabCodeHost;
import dev.thiagogonzaga.thrillhousebot.gitlab.GitLabMergeRequestClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Hands out the {@link CodeHost} of the platform {@code GIT_PLATFORM} names. A deployment serves
 * one platform, so the choice is made once here and no caller carries a platform with its request.
 */
@ApplicationScoped
public class CodeHostProducer {

  @Produces
  @ApplicationScoped
  CodeHost codeHost(
      ThrillhouseConfig config,
      @RestClient GitHubPullRequestClient github,
      @RestClient GitLabMergeRequestClient gitlab) {
    // StartupConfigValidator has already refused a value that names no platform.
    return switch (Platform.parse(config.platform()).orElse(Platform.GITHUB)) {
      case GITHUB -> new GitHubCodeHost(github);
      case GITLAB -> new GitLabCodeHost(gitlab);
    };
  }
}
