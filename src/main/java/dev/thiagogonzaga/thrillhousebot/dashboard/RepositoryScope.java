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
package dev.thiagogonzaga.thrillhousebot.dashboard;

import dev.thiagogonzaga.thrillhousebot.github.RepoScope;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The installed repositories one dashboard login may read, resolved once per request from the
 * cached installation snapshot: the installation they belong to and each repository's numeric id
 * with its current {@code owner/name}. Dashboard queries match stored rows by installation and
 * repository id (#1220), so a row from a repository of the same name under another installation, or
 * from before #1220 and never matched by the backfill, is never served.
 *
 * @param accountOwner whether the login is the installation's account owner, who sees every
 *     installed repository and installation-wide counters
 * @param installationId the installation the repositories belong to; 0 when there is none
 * @param repositories the readable repositories, by numeric id, with their {@code owner/name}
 */
public record RepositoryScope(
    boolean accountOwner, long installationId, Map<Long, String> repositories) {

  /** A scope that allows nothing: unknown login, unresolved owner, or no installation. */
  public static final RepositoryScope NONE = new RepositoryScope(false, 0L, Map.of());

  public RepositoryScope {
    repositories = Map.copyOf(repositories);
  }

  /** Whether {@code repository} ({@code owner/name}, any casing) is readable in this scope. */
  public boolean allows(String repository) {
    return scopeOf(repository).isPresent();
  }

  /** Whether a row stored under these ids is readable; a row with no ids never is. */
  public boolean allows(Long rowInstallationId, Long rowRepositoryId) {
    return rowInstallationId != null
        && rowRepositoryId != null
        && rowInstallationId == installationId
        && repositories.containsKey(rowRepositoryId);
  }

  /** The stored-row scope of {@code repository} ({@code owner/name}, any casing), if readable. */
  public Optional<RepoScope> scopeOf(String repository) {
    if (repository == null) {
      return Optional.empty();
    }
    var wanted = normalize(repository);
    return repositories.entrySet().stream()
        .filter(entry -> normalize(entry.getValue()).equals(wanted))
        .map(entry -> new RepoScope(installationId, entry.getKey()))
        .findFirst();
  }

  /** The readable repository ids, as a query parameter. */
  public List<Long> repositoryIds() {
    return List.copyOf(repositories.keySet());
  }

  static String normalize(String repository) {
    return repository.strip().toLowerCase(Locale.ROOT);
  }
}
