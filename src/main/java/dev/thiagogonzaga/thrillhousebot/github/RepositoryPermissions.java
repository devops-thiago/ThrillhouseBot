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

import java.util.Locale;
import java.util.Set;

/**
 * The one rule for who may act on a repository through the bot (#1221): a collaborator whose role
 * is write, maintain or admin. Viewing on the dashboard needs only read access; acting (a paid
 * {@code /review}, a learning taught by a decline, feedback that counts) needs this.
 *
 * <p>GitHub's legacy {@code permission} field reports {@code maintain} as {@code write} and {@code
 * triage} as {@code read}, and a custom role as the base role it extends. {@code role_name} names
 * the role itself. Either one granting write is enough, so maintain and a custom role built on
 * write both count, while triage and read never do.
 */
public final class RepositoryPermissions {

  private static final Set<String> ACTING_PERMISSIONS = Set.of("admin", "write");
  private static final Set<String> ACTING_ROLES = Set.of("admin", "maintain", "write");

  private RepositoryPermissions() {}

  /** Whether a collaborator-permission answer lets its user act on the repository. */
  public static boolean canAct(GitHubInstallationClient.CollaboratorPermission permission) {
    return permission != null
        && (matches(permission.permission(), ACTING_PERMISSIONS)
            || matches(permission.roleName(), ACTING_ROLES));
  }

  private static boolean matches(String value, Set<String> accepted) {
    return value != null && accepted.contains(value.strip().toLowerCase(Locale.ROOT));
  }
}
