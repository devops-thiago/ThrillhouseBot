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

import io.quarkiverse.githubapp.GitHubCustomizer;
import jakarta.inject.Singleton;
import org.kohsuke.github.GitHubAbuseLimitHandler;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.GitHubRateLimitHandler;

/**
 * Makes the GitHub App extension's clients fail on a rate or secondary limit instead of waiting.
 * hub4j's default handlers sleep until the limit resets, up to an hour, which would hold a review
 * or a dashboard request open; every caller already treats a failed read as unavailable.
 */
@Singleton
public class FailFastGitHubCustomizer implements GitHubCustomizer {

  @Override
  public void customize(GitHubBuilder builder) {
    failFast(builder);
  }

  @Override
  public void customizeApplicationClient(GitHubBuilder builder) {
    failFast(builder);
  }

  private static void failFast(GitHubBuilder builder) {
    builder
        .withRateLimitHandler(GitHubRateLimitHandler.FAIL)
        .withAbuseLimitHandler(GitHubAbuseLimitHandler.FAIL);
  }
}
