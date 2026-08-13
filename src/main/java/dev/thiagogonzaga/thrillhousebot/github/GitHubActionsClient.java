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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Read-only slice of the GitHub Actions API used to locate the coverage report a repository's CI
 * uploaded for the commit under review. Needs only the {@code Actions: Read} permission the App
 * already declares.
 */
@RegisterRestClient(configKey = "github-api")
@RegisterProvider(GitHubErrorLogger.class)
public interface GitHubActionsClient {

  /**
   * Completed runs read for one head SHA. This is a page size, not a page walk: the request is
   * filtered server-side by {@code head_sha}, so it bounds the workflows and re-runs on a SINGLE
   * commit rather than recent repository activity. See {@code PatchCoverageResolver.findArtifactId}
   * for why one page is enough and what exceeding it costs.
   */
  int RUNS_PER_PAGE = 30;

  /** Artifacts listed per run; GitHub's maximum page size, so one call covers any real run. */
  int ARTIFACTS_PER_PAGE = 100;

  /**
   * Completed workflow runs for exactly one commit. Filtering server-side by {@code head_sha} is
   * what makes the artifact's provenance checkable: the report belongs to the revision being
   * reviewed, not to whatever ran most recently on the branch.
   */
  @GET
  @Path("/repos/{owner}/{repo}/actions/runs")
  @Produces(MediaType.APPLICATION_JSON)
  WorkflowRuns listWorkflowRunsOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @QueryParam("head_sha") String headSha,
      @QueryParam("status") String status,
      @QueryParam("per_page") int perPage);

  /** Lists a commit's completed workflow runs, healing a rejected credential once (#626). */
  default WorkflowRuns listWorkflowRuns(
      String auth,
      String accept,
      String owner,
      String repo,
      String headSha,
      String status,
      int perPage) {
    return GitHubTokenRefresh.SHARED.retrying(
        "workflow runs of " + headSha + " on " + owner + "/" + repo,
        auth,
        credential ->
            listWorkflowRunsOnce(credential, accept, owner, repo, headSha, status, perPage));
  }

  /** The artifacts one workflow run uploaded. */
  @GET
  @Path("/repos/{owner}/{repo}/actions/runs/{runId}/artifacts")
  @Produces(MediaType.APPLICATION_JSON)
  RunArtifacts listRunArtifactsOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("runId") long runId,
      @QueryParam("per_page") int perPage);

  /** Lists a run's artifacts, healing a rejected credential once (#626). */
  default RunArtifacts listRunArtifacts(
      String auth, String accept, String owner, String repo, long runId, int perPage) {
    return GitHubTokenRefresh.SHARED.retrying(
        "artifacts of run " + runId + " on " + owner + "/" + repo,
        auth,
        credential -> listRunArtifactsOnce(credential, accept, owner, repo, runId, perPage));
  }

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

  @JsonIgnoreProperties(ignoreUnknown = true)
  record WorkflowRuns(
      @JsonProperty("total_count") int totalCount,
      @JsonProperty("workflow_runs") List<WorkflowRun> workflowRuns) {
    public List<WorkflowRun> workflowRuns() {
      return workflowRuns == null ? List.of() : List.copyOf(workflowRuns);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record WorkflowRun(
      long id,
      String name,
      @JsonProperty("head_sha") String headSha,
      String status,
      String conclusion) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record RunArtifacts(@JsonProperty("total_count") int totalCount, List<Artifact> artifacts) {
    public List<Artifact> artifacts() {
      return artifacts == null ? List.of() : List.copyOf(artifacts);
    }
  }

  /** One uploaded artifact; {@code expired} artifacts have had their bytes deleted by GitHub. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Artifact(
      long id, String name, @JsonProperty("size_in_bytes") long sizeInBytes, boolean expired) {}
}
