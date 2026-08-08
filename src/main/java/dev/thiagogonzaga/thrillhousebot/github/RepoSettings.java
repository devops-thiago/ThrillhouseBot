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

import java.util.List;

/**
 * The structured settings a repository declares for itself, read from {@code
 * .github/thrillhousebot.yml} by {@link RepoSettingsResolver}.
 *
 * <p>This is the one place per-repo <em>structured</em> settings live — deliberately separate from
 * the prose instructions file ({@link InstructionsResolver}), whose fallback chain reaches into
 * files owned by other tools and whose content is fed to the model as untrusted prose. New
 * structured settings are added as components here and parsed in {@link RepoSettingsParser}; every
 * one of them must degrade to its {@link #EMPTY} value rather than fail a review.
 *
 * @param ignoredFiles extra ignore globs, unioned with (never replacing) the deployment-wide {@code
 *     thrillhousebot.review.ignored-files} list
 * @param source the repo-relative path the settings were read from, or {@code "none"}
 */
public record RepoSettings(List<String> ignoredFiles, String source) {

  /** No per-repo settings — the deployment defaults apply unchanged. */
  public static final RepoSettings EMPTY = new RepoSettings(List.of(), "none");

  public RepoSettings {
    ignoredFiles = List.copyOf(ignoredFiles);
  }
}
