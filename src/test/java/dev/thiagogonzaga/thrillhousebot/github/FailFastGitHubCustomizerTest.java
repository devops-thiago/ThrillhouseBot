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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.kohsuke.github.GitHubAbuseLimitHandler;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.GitHubRateLimitHandler;

class FailFastGitHubCustomizerTest {

  @Test
  void installationAndApplicationClientsFailOnALimitInsteadOfWaiting() {
    var customizer = new FailFastGitHubCustomizer();
    for (var forApplication : new boolean[] {false, true}) {
      var builder = mock(GitHubBuilder.class);
      when(builder.withRateLimitHandler(any(GitHubRateLimitHandler.class))).thenReturn(builder);
      when(builder.withAbuseLimitHandler(any(GitHubAbuseLimitHandler.class))).thenReturn(builder);

      if (forApplication) {
        customizer.customizeApplicationClient(builder);
      } else {
        customizer.customize(builder);
      }

      verify(builder).withRateLimitHandler(GitHubRateLimitHandler.FAIL);
      verify(builder).withAbuseLimitHandler(GitHubAbuseLimitHandler.FAIL);
    }
  }
}
