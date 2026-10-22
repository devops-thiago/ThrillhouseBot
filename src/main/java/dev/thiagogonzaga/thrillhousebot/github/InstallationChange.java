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
 * A change to an installation of the App or to the repositories it can access, fired from the
 * signed webhook (#1222). Observers only update in-memory state or the stored rows, so firing it
 * never calls GitHub on the webhook's acknowledgement path.
 *
 * @param kind what changed
 * @param installationId the installation it happened to
 * @param repositories the repositories it concerns; empty for an installation-wide change
 */
public record InstallationChange(Kind kind, long installationId, List<Repository> repositories) {

  public InstallationChange {
    repositories = List.copyOf(repositories);
  }

  /** What changed. */
  public enum Kind {
    /** Uninstalled or suspended: nothing of it is served. */
    GONE,
    /** Unsuspended: served again. */
    BACK,
    /** New permissions were accepted, which can change what a login may see. */
    PERMISSIONS,
    /** Repositories were removed from the installation. */
    REPOSITORIES_REMOVED,
    /** Repositories were added to the installation. */
    REPOSITORIES_ADDED,
    /** A repository was renamed or transferred; it carries the new {@code owner/name}. */
    REPOSITORY_RENAMED
  }

  /** A repository by its numeric id and current {@code owner/name}. */
  public record Repository(long id, String fullName) {}
}
