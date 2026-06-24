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

import dev.thiagogonzaga.thrillhousebot.github.GitHubCheckRunClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Creates and updates ThrillhouseBot's GitHub check run — the merge-gate signal for a review.
 * Extracted from {@code ReviewOrchestrator}; owns the in-progress create, the status/conclusion
 * update, and the completion-only retry that survives a 422 on the full completion payload.
 */
@ApplicationScoped
public class CheckRunManager {

  private static final String ACCEPT = "application/vnd.github+json";
  private static final String CHECK_NAME = "ThrillhouseBot Review";
  private static final String CHECK_STATUS_IN_PROGRESS = "in_progress";
  private static final String CHECK_STATUS_COMPLETED = "completed";

  private final GitHubCheckRunClient checkRunClient;

  @Inject
  public CheckRunManager(@RestClient GitHubCheckRunClient checkRunClient) {
    this.checkRunClient = checkRunClient;
  }

  long createCheckRun(String auth, String owner, String repo, String headSha, String detailsUrl) {
    var req =
        new GitHubCheckRunClient.CreateCheckRunRequest(
            CHECK_NAME, headSha, CHECK_STATUS_IN_PROGRESS, detailsUrl);
    var response = checkRunClient.createCheckRun(auth, ACCEPT, owner, repo, req);
    Log.debugf("Created check run: %d", response.id());
    return response.id();
  }

  void updateCheckRun(CheckRunUpdate u) {
    var output =
        u.title() != null
            ? new GitHubCheckRunClient.UpdateCheckRunRequest.Output(u.title(), u.summary(), null)
            : null;
    var completed = CHECK_STATUS_COMPLETED.equals(u.status());
    var req =
        new GitHubCheckRunClient.UpdateCheckRunRequest(
            completed ? null : u.status(),
            completed ? u.conclusion() : null,
            completed ? githubTimestamp() : null,
            u.detailsUrl(),
            output);
    if (!completed) {
      checkRunClient.updateCheckRun(u.auth(), ACCEPT, u.owner(), u.repo(), u.checkRunId(), req);
      return;
    }
    try {
      checkRunClient.updateCheckRun(u.auth(), ACCEPT, u.owner(), u.repo(), u.checkRunId(), req);
    } catch (RuntimeException e) {
      Log.warnf(
          e,
          "Check run %d completion update failed, retrying with conclusion only",
          u.checkRunId());
      checkRunClient.updateCheckRun(
          u.auth(),
          ACCEPT,
          u.owner(),
          u.repo(),
          u.checkRunId(),
          new GitHubCheckRunClient.UpdateCheckRunRequest(null, u.conclusion(), null, null, null));
    }
  }

  private static String githubTimestamp() {
    return DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
  }

  record CheckRunUpdate(
      String auth,
      String owner,
      String repo,
      long checkRunId,
      String status,
      String conclusion,
      String title,
      String summary,
      String detailsUrl) {}
}
