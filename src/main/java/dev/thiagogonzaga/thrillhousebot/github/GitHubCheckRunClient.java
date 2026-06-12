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
