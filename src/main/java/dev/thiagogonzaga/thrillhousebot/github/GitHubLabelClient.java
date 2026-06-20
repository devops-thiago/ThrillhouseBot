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

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** GitHub REST endpoints for reading a repository's labels and applying them to a PR. */
@RegisterRestClient(configKey = "github-api")
public interface GitHubLabelClient {

  @GET
  @Path("/repos/{owner}/{repo}/labels")
  @Produces(MediaType.APPLICATION_JSON)
  List<Label> listLabels(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @QueryParam("per_page") int perPage,
      @QueryParam("page") int page);

  /** Lists the labels currently on a PR (PRs are issues for the labels API). */
  @GET
  @Path("/repos/{owner}/{repo}/issues/{issueNumber}/labels")
  @Produces(MediaType.APPLICATION_JSON)
  List<Label> listIssueLabels(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("issueNumber") int issueNumber,
      @QueryParam("per_page") int perPage,
      @QueryParam("page") int page);

  /**
   * Adds labels to a PR (PRs are issues for the labels API). Idempotent — labels already present
   * are left untouched. Names must already exist in the repo or GitHub rejects the request.
   */
  @POST
  @Path("/repos/{owner}/{repo}/issues/{issueNumber}/labels")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  List<Label> addLabels(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("issueNumber") int issueNumber,
      AddLabelsRequest request);

  @POST
  @Path("/repos/{owner}/{repo}/labels")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  Label createLabel(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      CreateLabelRequest request);

  @RegisterForReflection
  record Label(String name, String description, String color) {}

  record AddLabelsRequest(List<String> labels) {
    public AddLabelsRequest {
      labels = labels == null ? List.of() : List.copyOf(labels);
    }
  }

  record CreateLabelRequest(String name, String color, String description) {}
}
