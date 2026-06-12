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

  public Optional<String> resolveLogin(String sessionId) {
    return sessionStore
        .findSession(sessionId)
        .filter(session -> accessChecker.hasAccess(session.login()))
        .map(DashboardSessionStore.DashboardSession::login);
  }
}
