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

import dev.thiagogonzaga.thrillhousebot.dashboard.RepositoryScope;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Installation and repository ids for tests. A repository's id is derived from its name, ignoring
 * case, so rows a test stores for {@code owner/repo} and a dashboard scope it builds for the same
 * name agree without the test spelling out ids.
 */
public final class TestScopes {

  /** The installation every fixture belongs to unless a test says otherwise. */
  public static final long INSTALLATION_ID = 1L;

  /** A repository id for tests that do not care which repository it is. */
  public static final long REPOSITORY_ID = 7L;

  private TestScopes() {}

  /** The fixture scope of {@code owner/repo} under {@link #INSTALLATION_ID}. */
  public static RepoScope scope(String repository) {
    return new RepoScope(INSTALLATION_ID, repositoryId(repository));
  }

  /** A stable positive id for {@code owner/repo}, the same for any casing. */
  public static long repositoryId(String repository) {
    return (repository.toLowerCase(Locale.ROOT).hashCode() & 0x7fffffffL) + 1L;
  }

  /**
   * A dashboard scope under {@link #INSTALLATION_ID} that may read {@code repositories}, each with
   * the id {@link #repositoryId} gives its name.
   */
  public static RepositoryScope readable(boolean accountOwner, String... repositories) {
    return new RepositoryScope(
        accountOwner,
        INSTALLATION_ID,
        Arrays.stream(repositories)
            .collect(
                Collectors.toMap(
                    TestScopes::repositoryId, repository -> repository, (first, second) -> first)));
  }
}
