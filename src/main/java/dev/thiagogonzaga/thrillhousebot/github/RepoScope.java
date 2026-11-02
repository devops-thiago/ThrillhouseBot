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

/**
 * The GitHub App installation and numeric repository id a stored row belongs to (#1220). Both come
 * from the signed webhook payload. Every read of stored reviews, pauses, feedback and learnings
 * matches both, so a repository of the same name under another installation, or one deleted and
 * re-created, never sees another's rows, and a renamed repository keeps its own.
 *
 * @param installationId the GitHub App installation id
 * @param repositoryId GitHub's numeric repository id, which survives a rename
 */
public record RepoScope(long installationId, long repositoryId) {

  public RepoScope {
    if (installationId <= 0 || repositoryId <= 0) {
      throw new IllegalArgumentException(
          "installation and repository ids must be positive: "
              + installationId
              + "/"
              + repositoryId);
    }
  }
}
