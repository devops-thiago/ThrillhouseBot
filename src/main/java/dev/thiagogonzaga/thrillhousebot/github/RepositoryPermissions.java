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

import org.kohsuke.github.GHPermissionType;

/**
 * The one rule for who may act on a repository through the bot (#1221): a collaborator whose role
 * is write, maintain or admin. Viewing on the dashboard needs only read access; acting (a paid
 * {@code /review}, a learning taught by a decline, feedback that counts) needs this.
 *
 * <p>Only the collaborator-permission {@code permission} field is read. It reports {@code maintain}
 * as {@code write} and {@code triage} as {@code read}, and a custom role as the base role it
 * extends, so maintain and a custom role built on write both count, while triage and read never do.
 */
public final class RepositoryPermissions {

  private RepositoryPermissions() {}

  /**
   * Whether a collaborator-permission answer lets its user act on the repository.
   *
   * <p>GitHub's collaborator-permission endpoint reports the base permission level in its {@code
   * permission} field (admin, write, read, or none) and the effective role in a separate {@code
   * role_name} field (maintain, triage, or a custom role name). hub4j's {@code
   * GHRepository.getPermission(user)} fetches that endpoint and decodes only the {@code permission}
   * field via {@code GHPermission.getPermissionType()}, which calls {@code
   * EnumUtils.getEnumOrDefault(GHPermissionType.class, permission, UNKNOWN)}; an unrecognized value
   * maps to {@code UNKNOWN} and is denied here. Because GitHub reports a maintainer's base level as
   * {@code write}, {@code GHPermissionType#WRITE} covers maintain and any custom role built on
   * write. A {@code null} answer grants nothing.
   */
  public static boolean canAct(GHPermissionType permission) {
    return permission != null
        && (permission == GHPermissionType.ADMIN || permission == GHPermissionType.WRITE);
  }
}
