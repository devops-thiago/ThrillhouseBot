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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

@ApplicationScoped
public class DashboardSessionValidator {

  private final DashboardSessionStore sessionStore;
  private final DashboardAccessChecker accessChecker;

  @Inject
  public DashboardSessionValidator(
      DashboardSessionStore sessionStore, DashboardAccessChecker accessChecker) {
    this.sessionStore = sessionStore;
    this.accessChecker = accessChecker;
  }

  public boolean isValidSession(String sessionId) {
    return resolveLogin(sessionId).isPresent();
  }

  /**
   * Whether the session still exists (not logged out or expired), without re-running the GitHub
   * access check; cheap enough for a per-event check on an open connection.
   */
  public boolean isSessionOpen(String sessionId) {
    return sessionStore.findSession(sessionId).isPresent();
  }

  /**
   * The session a WebSocket ticket was issued for, consuming the ticket; empty for an unknown,
   * reused or expired ticket (#1223).
   */
  public Optional<String> redeemSocketTicket(String ticket) {
    return sessionStore.redeemSocketTicket(ticket);
  }

  public Optional<String> resolveLogin(String sessionId) {
    return resolve(sessionId)
        .filter(r -> r.resolution().decision() == DashboardAccessChecker.AccessDecision.ALLOWED)
        .map(Resolved::login);
  }

  /**
   * The repositories this session's login may read, resolved once so a request can filter its
   * queries by them; {@link RepositoryScope#NONE} for an unknown or expired session.
   */
  public RepositoryScope repositoryScope(String sessionId) {
    return resolve(sessionId).map(r -> r.resolution().scope()).orElse(RepositoryScope.NONE);
  }

  /**
   * The session's current scope for an open live connection: {@link RepositoryScope#NONE} once the
   * session is gone, its login may read nothing, or GitHub rejected its token; empty when GitHub
   * could not be asked, so the caller keeps the scope it already had rather than dropping a
   * connection over a transient outage, the same way a REST request answers 503 and keeps the
   * session.
   */
  public Optional<RepositoryScope> refreshedScope(String sessionId) {
    var resolved = resolve(sessionId);
    if (resolved.isPresent()
        && resolved.get().resolution().decision()
            == DashboardAccessChecker.AccessDecision.UNAVAILABLE) {
      return Optional.empty();
    }
    return Optional.of(resolved.map(r -> r.resolution().scope()).orElse(RepositoryScope.NONE));
  }

  /** A session's login and what it may read. */
  private record Resolved(String login, DashboardAccessChecker.Resolution resolution) {}

  /**
   * Resolves the session's access with its login's own GitHub token. A token GitHub rejects
   * (expired or revoked) ends the session, so the dashboard asks the user to sign in again instead
   * of showing an empty page.
   */
  private Optional<Resolved> resolve(String sessionId) {
    return sessionStore
        .findSession(sessionId)
        .map(
            session -> {
              var resolution = accessChecker.resolve(session.login(), session.accessToken());
              if (resolution.decision() == DashboardAccessChecker.AccessDecision.TOKEN_REJECTED) {
                sessionStore.invalidate(sessionId);
              }
              return new Resolved(session.login(), resolution);
            });
  }
}
