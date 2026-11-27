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

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The two GitHub Actions downloads: the artifact holding the coverage report a repository's CI
 * uploaded, and a failing job's log for the opt-in CI-failure review context. Needs only the {@code
 * Actions: Read} permission the App already declares.
 *
 * <p>These stay on a REST client because of a hub4j limitation: {@code GHArtifact.download} and
 * {@code GHWorkflowJob.downloadLogs} follow GitHub's redirect themselves and do not give out the
 * pre-signed URL, so the https-only, size-bounded fetch without the installation token that the
 * callers make would be lost. This goes away once hub4j gives out that URL (<a
 * href="https://github.com/hub4j/github-api/issues/2351">hub4j/github-api#2351</a>). The listings
 * are read through {@link GitHubTokenReads}.
 */
@RegisterRestClient(configKey = "github-api")
@RegisterProvider(GitHubErrorLogger.class)
public interface GitHubActionsClient {

  /**
   * Starts an artifact download. GitHub answers with a {@code 302} to a short-lived, pre-signed
   * blob-storage URL rather than the bytes themselves, so this returns the raw {@link Response}:
   * the caller reads {@code Location} and fetches it separately, deliberately WITHOUT the
   * installation token, which must never leave {@code api.github.com}.
   */
  @GET
  @Path("/repos/{owner}/{repo}/actions/artifacts/{artifactId}/zip")
  Response downloadArtifactOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("artifactId") long artifactId);

  /**
   * Starts an artifact download, healing a rejected credential once (#626). Because the raw {@link
   * Response} is returned rather than unmarshalled, a 401 arrives as a status instead of a {@link
   * jakarta.ws.rs.WebApplicationException}, so the refresh is asked for directly here rather than
   * through {@link GitHubTokenRefresh#retrying}.
   */
  default Response downloadArtifact(
      String auth, String accept, String owner, String repo, long artifactId) {
    var response = downloadArtifactOnce(auth, accept, owner, repo, artifactId);
    if (response.getStatus() != 401) {
      return response;
    }
    var fresh =
        GitHubTokenRefresh.SHARED.replacementFor(
            "artifact " + artifactId + " download on " + owner + "/" + repo,
            auth,
            new WebApplicationException(response));
    if (fresh.isEmpty()) {
      return response;
    }
    response.close();
    return downloadArtifactOnce(fresh.get(), accept, owner, repo, artifactId);
  }

  /**
   * Starts a job-log download. Like {@link #downloadArtifactOnce} GitHub answers with a {@code 302}
   * to a short-lived, pre-signed URL, so the raw {@link Response} is returned and the caller
   * fetches {@code Location} without the installation token. For a GitHub Actions check run the job
   * id is the check run's id. Read only by the opt-in CI-failure review context when job logs are
   * enabled (#59).
   */
  @GET
  @Path("/repos/{owner}/{repo}/actions/jobs/{jobId}/logs")
  Response downloadJobLogsOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("jobId") long jobId);

  /**
   * Starts a job-log download, asking for a fresh credential once on a 401 the way {@link
   * #downloadArtifact} does, since the raw {@link Response} carries a rejection as a status.
   */
  default Response downloadJobLogs(
      String auth, String accept, String owner, String repo, long jobId) {
    var response = downloadJobLogsOnce(auth, accept, owner, repo, jobId);
    if (response.getStatus() != 401) {
      return response;
    }
    var fresh =
        GitHubTokenRefresh.SHARED.replacementFor(
            "job " + jobId + " log download on " + owner + "/" + repo,
            auth,
            new WebApplicationException(response));
    if (fresh.isEmpty()) {
      return response;
    }
    response.close();
    return downloadJobLogsOnce(fresh.get(), accept, owner, repo, jobId);
  }
}
