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
package dev.thiagogonzaga.thrillhousebot.webhook;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubInstallationClient;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether a commenter may manually trigger a paid review. Org membership or a bare
 * collaborator relationship is not sufficient: the commenter must actually hold write access to the
 * specific repository — verified authoritatively against the GitHub API — or be named in the
 * operator's explicit allowlist.
 */
@ApplicationScoped
public class ManualReviewAuthorizer {

  private static final Logger log = LoggerFactory.getLogger(ManualReviewAuthorizer.class);
  private static final String ACCEPT = "application/vnd.github+json";

  /**
   * GitHub {@code author_association} values that a write-access holder can present. Any other
   * value ({@code CONTRIBUTOR}, {@code FIRST_TIME_CONTRIBUTOR}, {@code NONE}, ...) provably lacks
   * write access — association precedence guarantees a collaborator/member is never reported as one
   * of those — so it is rejected without an API round-trip, keeping public-repo trigger spam from
   * consuming the app's GitHub rate limit. Write access is then confirmed authoritatively for the
   * remaining cases.
   */
  private static final Set<String> WRITE_CAPABLE_ASSOCIATIONS =
      Set.of("OWNER", "MEMBER", "COLLABORATOR");

  /** GitHub collaborator-permission levels that include push (write) access. */
  private static final Set<String> WRITE_PERMISSIONS = Set.of("admin", "write");

  private final ThrillhouseConfig config;
  private final GitHubAuthClient authClient;
  private final GitHubInstallationClient installationClient;

  /**
   * Runs the write-access check (token mint + permission call) off the webhook acknowledgement
   * thread so it can be abandoned once {@code manual-trigger-auth-timeout} elapses. Virtual threads
   * keep an abandoned-but-still-blocked check cheap if GitHub is slow to respond.
   */
  private final ExecutorService authCheckExecutor =
      Executors.newThreadPerTaskExecutor(
          Thread.ofVirtual().name("manual-review-auth-", 0).factory());

  @Inject
  public ManualReviewAuthorizer(
      ThrillhouseConfig config,
      GitHubAuthClient authClient,
      @RestClient GitHubInstallationClient installationClient) {
    this.config = config;
    this.authClient = authClient;
    this.installationClient = installationClient;
  }

  @PreDestroy
  void shutdown() {
    authCheckExecutor.shutdownNow();
  }

  /**
   * Returns {@code true} only if {@code login} may spend the operator's API budget on a manual
   * review of {@code owner/repo}: either the login is allowlisted, or it holds write access to the
   * repository. {@code authorAssociation} is used solely as a cheap rejection of commenters who
   * cannot possibly have write access; it never grants access on its own.
   */
  public boolean isAuthorized(
      String owner, String repo, long installationId, String login, String authorAssociation) {
    if (login == null || login.isBlank()) {
      return false;
    }
    if (isAllowlisted(login)) {
      return true;
    }
    if (!mayHoldWriteAccess(authorAssociation)) {
      return false;
    }
    return hasWriteAccess(owner, repo, installationId, login);
  }

  private boolean isAllowlisted(String login) {
    for (String allowed : config.review().manualTriggerAllowedLogins().orElse(List.of())) {
      if (allowed.trim().equalsIgnoreCase(login.trim())) {
        return true;
      }
    }
    return false;
  }

  private static boolean mayHoldWriteAccess(String authorAssociation) {
    return authorAssociation != null
        && WRITE_CAPABLE_ASSOCIATIONS.contains(authorAssociation.trim().toUpperCase(Locale.ROOT));
  }

  private boolean hasWriteAccess(String owner, String repo, long installationId, String login) {
    var timeout = config.review().manualTriggerAuthTimeout();
    Future<Boolean> check =
        authCheckExecutor.submit(() -> writeAccessConfirmed(owner, repo, installationId, login));
    try {
      return check.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      check.cancel(true);
      log.warn(
          "Permission check for {}/{} user {} exceeded {} on the ACK path — denying manual review",
          owner,
          repo,
          login,
          timeout);
    } catch (ExecutionException e) {
      var cause = e.getCause() != null ? e.getCause() : e;
      log.warn(
          "Permission check failed for {}/{} user {}: {} — denying manual review",
          owner,
          repo,
          login,
          cause.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      check.cancel(true);
      log.warn(
          "Permission check for {}/{} user {} was interrupted — denying manual review",
          owner,
          repo,
          login);
    }
    return false;
  }

  private boolean writeAccessConfirmed(
      String owner, String repo, long installationId, String login) {
    var permission =
        installationClient.collaboratorPermission(
            authClient.getAuthHeader(installationId), ACCEPT, owner, repo, login);
    var level = permission == null ? null : permission.permission();
    return level != null && WRITE_PERMISSIONS.contains(level.trim().toLowerCase(Locale.ROOT));
  }
}
