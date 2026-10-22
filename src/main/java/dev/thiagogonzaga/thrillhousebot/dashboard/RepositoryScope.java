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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What one dashboard login may read (#1221): the installations of the App they can access, and in
 * each the repositories they can read, by numeric id with the current {@code owner/name}. Resolved
 * with the login's own GitHub token and cached, so a dashboard request never calls GitHub per row.
 * Dashboard queries match stored rows by installation and repository id (#1220), so a row from a
 * repository of the same name under another installation, or one the backfill never matched, is
 * never served.
 *
 * @param installations the readable installations, by installation id
 */
public record RepositoryScope(Map<Long, Installation> installations) {

  /** A scope that allows nothing: unknown login, rejected token, or no installation. */
  public static final RepositoryScope NONE = new RepositoryScope(Map.of());

  /**
   * One installation in the scope.
   *
   * @param id the installation id
   * @param account the account the App is installed on
   * @param installationWide whether the login may see views that span the whole installation, such
   *     as skip counters: the account itself for a personal account, a member for an organization
   * @param repositories the readable repositories, by numeric id, with their {@code owner/name}
   */
  public record Installation(
      long id, String account, boolean installationWide, Map<Long, String> repositories) {
    public Installation {
      repositories = Map.copyOf(repositories);
    }
  }

  public RepositoryScope {
    installations = Map.copyOf(installations);
  }

  /** A scope of one installation; the single-installation form the tests and callers build. */
  public RepositoryScope(
      boolean installationWide, long installationId, Map<Long, String> repositories) {
    this(
        repositories.isEmpty() && !installationWide
            ? Map.of()
            : Map.of(
                installationId,
                new Installation(installationId, "", installationWide, repositories)));
  }

  /** Whether the login may read anything at all. */
  public boolean isEmpty() {
    return installations.values().stream().allMatch(i -> i.repositories().isEmpty());
  }

  /** Installations whose installation-wide views this login may see. */
  public Set<Long> installationWideIds() {
    return installations.values().stream()
        .filter(Installation::installationWide)
        .map(Installation::id)
        .collect(Collectors.toUnmodifiableSet());
  }

  /** The installations, in id order, as the dashboard lists them. */
  public List<Installation> sortedInstallations() {
    return installations.values().stream()
        .sorted(Comparator.comparingLong(Installation::id))
        .toList();
  }

  /**
   * This scope narrowed to one installation, or unchanged when {@code installationId} is null. An
   * installation outside the scope narrows to {@link #NONE}.
   */
  public RepositoryScope only(Long installationId) {
    if (installationId == null) {
      return this;
    }
    var installation = installations.get(installationId);
    return installation == null ? NONE : new RepositoryScope(Map.of(installationId, installation));
  }

  /** Whether {@code repository} ({@code owner/name}, any casing) is readable in this scope. */
  public boolean allows(String repository) {
    return scopeOf(repository).isPresent();
  }

  /** Whether a row stored under these ids is readable; a row with no ids never is. */
  public boolean allows(Long rowInstallationId, Long rowRepositoryId) {
    if (rowInstallationId == null || rowRepositoryId == null) {
      return false;
    }
    var installation = installations.get(rowInstallationId);
    return installation != null && installation.repositories().containsKey(rowRepositoryId);
  }

  /**
   * The stored-row scope of {@code repository} ({@code owner/name}, any casing), if readable. When
   * the same name is readable under two installations the lowest installation id wins, so the
   * answer never depends on map order; narrow with {@link #only} to pick another.
   */
  public Optional<RepoScope> scopeOf(String repository) {
    if (repository == null) {
      return Optional.empty();
    }
    var wanted = normalize(repository);
    return sortedInstallations().stream()
        .flatMap(
            installation ->
                installation.repositories().entrySet().stream()
                    .filter(entry -> normalize(entry.getValue()).equals(wanted))
                    .map(entry -> new RepoScope(installation.id(), entry.getKey())))
        .findFirst();
  }

  /** The current {@code owner/name} of a readable repository, or null. */
  public String nameOf(long installationId, long repositoryId) {
    var installation = installations.get(installationId);
    return installation == null ? null : installation.repositories().get(repositoryId);
  }

  /**
   * A JPQL condition that holds only for rows of this scope, its fields qualified by {@code alias}
   * (none when null), with its named parameters added to {@code params}: one {@code (installationId
   * = … and repositoryId in …)} term per installation, or a condition that is always false when
   * nothing is readable. Filtering in the query rather than after it keeps paging, totals and
   * aggregates exact.
   */
  public String where(String alias, Map<String, Object> params) {
    var prefix = alias == null ? "" : alias + ".";
    var terms = new ArrayList<String>();
    var index = 0;
    for (var installation : sortedInstallations()) {
      if (installation.repositories().isEmpty()) {
        continue;
      }
      var inst = "scopeInstallation" + index;
      var repos = "scopeRepositories" + index;
      params.put(inst, installation.id());
      params.put(repos, List.copyOf(installation.repositories().keySet()));
      terms.add(
          "("
              + prefix
              + "installationId = :"
              + inst
              + " and "
              + prefix
              + "repositoryId in :"
              + repos
              + ")");
      index++;
    }
    return terms.isEmpty() ? "1 = 0" : "(" + String.join(" or ", terms) + ")";
  }

  static String normalize(String repository) {
    return repository.strip().toLowerCase(Locale.ROOT);
  }
}
