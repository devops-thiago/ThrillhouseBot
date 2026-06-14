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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "github-api")
public interface GitHubCheckRunClient {
  @POST
  @Path("/repos/{owner}/{repo}/check-runs")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  CheckRunResponse createCheckRun(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      CreateCheckRunRequest request);

  @PATCH
  @Path("/repos/{owner}/{repo}/check-runs/{checkRunId}")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  void updateCheckRun(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("checkRunId") long checkRunId,
      UpdateCheckRunRequest request);

  @GET
  @Path("/repos/{owner}/{repo}/branches/{branch}/protection/required_status_checks")
  @Produces(MediaType.APPLICATION_JSON)
  RequiredStatusChecks getRequiredStatusChecks(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("branch") String branch);

  @GET
  @Path("/repos/{owner}/{repo}/commits/{ref}/check-runs")
  @Produces(MediaType.APPLICATION_JSON)
  CheckRunsResponse getCheckRuns(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("ref") String ref);

  @GET
  @Path("/repos/{owner}/{repo}/commits/{ref}/status")
  @Produces(MediaType.APPLICATION_JSON)
  CombinedStatus getCombinedStatus(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("ref") String ref);

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record RequiredStatusChecks(List<String> contexts, List<Check> checks) {
    public List<String> contexts() {
      return contexts == null ? List.of() : List.copyOf(contexts);
    }

    public List<Check> checks() {
      return checks == null ? List.of() : List.copyOf(checks);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Check(String context, @JsonProperty("app_id") Long appId) {}
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record CheckRunsResponse(
      @JsonProperty("total_count") int totalCount,
      @JsonProperty("check_runs") List<CheckRun> checkRuns) {
    public List<CheckRun> checkRuns() {
      return checkRuns == null ? List.of() : List.copyOf(checkRuns);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CheckRun(long id, String name, String status, String conclusion, App app) {
      @JsonInclude(JsonInclude.Include.NON_NULL)
      public record App(@JsonProperty("id") Long id, String slug, String name) {}
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record CombinedStatus(
      String state, @JsonProperty("total_count") int totalCount, List<StatusDetail> statuses) {
    public List<StatusDetail> statuses() {
      return statuses == null ? List.of() : List.copyOf(statuses);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StatusDetail(long id, String state, String context, String description) {}
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record CreateCheckRunRequest(
      String name,
      @JsonProperty("head_sha") String headSha,
      String status,
      @JsonProperty("details_url") String detailsUrl) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record UpdateCheckRunRequest(
      String status,
      String conclusion,
      @JsonProperty("completed_at") String completedAt,
      @JsonProperty("details_url") String detailsUrl,
      Output output) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Output(String title, String summary, String text) {}
  }

  record CheckRunResponse(long id, @JsonProperty("html_url") String htmlUrl) {}
}
