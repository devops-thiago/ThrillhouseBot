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
package dev.thiagogonzaga.thrillhousebot.review;

import dev.thiagogonzaga.thrillhousebot.config.BotIdentity;
import dev.thiagogonzaga.thrillhousebot.github.GitHubCommentClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubReviewClient;
import dev.thiagogonzaga.thrillhousebot.github.InstallationRepository;
import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Asks GitHub which comment authors on a pull request hold write access, once per review ({@link
 * VerifiedWriters}). Only authors whose {@code author_association} could hold it are asked about,
 * each distinct login once, through the installation's own client and the same rule every other
 * acting path uses ({@link InstallationRepository#mayAct}).
 *
 * <p>Every failure leaves the author out: an installation that cannot be read, a repository that
 * does not resolve, a permission call that throws or times out. Nothing is retried within the
 * review; the next review asks again.
 */
@ApplicationScoped
public class VerifiedWritersResolver {

  private final GitHubClientProvider clientProvider;
  private final BotIdentity botIdentity;

  @Inject
  public VerifiedWritersResolver(GitHubClientProvider clientProvider, BotIdentity botIdentity) {
    this.clientProvider = clientProvider;
    this.botIdentity = botIdentity;
  }

  /**
   * The authors of {@code inlineComments} and {@code conversationComments} confirmed to hold write
   * access to {@code owner/repo}. No call is made when no author passes the association check.
   */
  public VerifiedWriters resolve(
      long installationId,
      String owner,
      String repo,
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      List<GitHubCommentClient.IssueComment> conversationComments) {
    var candidates = candidates(inlineComments, conversationComments);
    if (candidates.isEmpty()) {
      return VerifiedWriters.none();
    }
    if (installationId <= 0) {
      Log.warnf(
          "No installation to confirm write access through on %s/%s; no reply or directive on this"
              + " pull request is treated as a maintainer's",
          owner, repo);
      return VerifiedWriters.none();
    }
    var repository =
        InstallationRepository.resolve(
            clientProvider,
            installationId,
            owner,
            repo,
            e ->
                Log.warnf(
                    e,
                    "Could not read %s/%s to confirm write access; no reply or directive on this"
                        + " pull request is treated as a maintainer's",
                    owner,
                    repo));
    if (repository.isEmpty()) {
      return VerifiedWriters.none();
    }
    var confirmed = new ArrayList<String>();
    for (var login : candidates.values()) {
      if (Thread.currentThread().isInterrupted()) {
        break;
      }
      if (InstallationRepository.mayAct(
          repository.get(),
          login,
          e ->
              Log.warnf(
                  e,
                  "Could not confirm @%s's access on %s/%s; their replies and directives are not"
                      + " treated as a maintainer's in this review",
                  login,
                  owner,
                  repo))) {
        confirmed.add(login);
      }
    }
    return VerifiedWriters.of(confirmed);
  }

  /** Each distinct non-bot login whose association could hold write access, in comment order. */
  private Map<String, String> candidates(
      List<GitHubReviewClient.PullRequestComment> inlineComments,
      List<GitHubCommentClient.IssueComment> conversationComments) {
    var candidates = new LinkedHashMap<String, String>();
    for (var comment : inlineComments) {
      if (comment.user() != null) {
        addCandidate(candidates, comment.user().login(), comment.authorAssociation());
      }
    }
    for (var comment : conversationComments) {
      if (comment.user() != null) {
        addCandidate(candidates, comment.user().login(), comment.authorAssociation());
      }
    }
    return candidates;
  }

  private void addCandidate(Map<String, String> candidates, String login, String association) {
    if (login == null
        || login.isBlank()
        || botIdentity.matches(login)
        || !VerifiedWriters.mayHoldWriteAccess(association)) {
      return;
    }
    candidates.putIfAbsent(login.strip().toLowerCase(Locale.ROOT), login.strip());
  }
}
