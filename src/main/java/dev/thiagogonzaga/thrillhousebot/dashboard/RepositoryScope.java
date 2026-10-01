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

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The installed repositories one dashboard login may read, resolved once per request from the
 * cached installation snapshot. Names are held as lower-case {@code owner/name} so they match the
 * {@code lower(s.repository)} filter in the dashboard queries regardless of how a webhook spelled
 * the repository.
 *
 * @param accountOwner whether the login is the installation's account owner, who sees every
 *     installed repository and installation-wide counters
 * @param repositories the readable repositories, lower-cased
 */
public record RepositoryScope(boolean accountOwner, Set<String> repositories) {

  /** A scope that allows nothing: unknown login, unresolved owner, or no installation. */
  public static final RepositoryScope NONE = new RepositoryScope(false, Set.of());

  public RepositoryScope {
    repositories = normalizeAll(repositories);
  }

  /** The readable repositories, lower-cased; already unmodifiable, so this copy is free. */
  @Override
  public Set<String> repositories() {
    return Set.copyOf(repositories);
  }

  /** Whether {@code repository} ({@code owner/name}, any casing) is readable in this scope. */
  public boolean allows(String repository) {
    return repository != null && repositories.contains(normalize(repository));
  }

  static String normalize(String repository) {
    return repository.strip().toLowerCase(Locale.ROOT);
  }

  private static Set<String> normalizeAll(Collection<String> repositories) {
    return repositories.stream()
        .map(RepositoryScope::normalize)
        .collect(Collectors.toUnmodifiableSet());
  }
}
