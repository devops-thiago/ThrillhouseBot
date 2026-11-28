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
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * GitHub REST endpoints for creating a repository's labels and applying them to a PR. The labels
 * are read through {@link GitHubTokenReads}.
 */
@RegisterRestClient(configKey = "github-api")
@RegisterProvider(GitHubErrorLogger.class)
public interface GitHubLabelClient {

  /** One HTTP attempt at adding labels to a PR. Callers want {@link #addLabels} instead. */
  @POST
  @Path("/repos/{owner}/{repo}/issues/{issueNumber}/labels")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  List<Label> addLabelsOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("issueNumber") int issueNumber,
      AddLabelsRequest request);

  /**
   * Adds labels to a PR (PRs are issues for the labels API). Idempotent — labels already present
   * are left untouched. Names must already exist in the repo or GitHub rejects the request.
   *
   * <p>The labels are written at the end of a review, with the token the review read when it
   * started, so the call goes through {@link GitHubWriteRetry} like the review's other writes: a
   * token GitHub rejects is replaced and the call repeated once, and a throttled call is repeated
   * after the wait GitHub asks for.
   */
  default List<Label> addLabels(
      String auth,
      String accept,
      String owner,
      String repo,
      int issueNumber,
      AddLabelsRequest request) {
    return GitHubWriteRetry.DEFAULT.call(
        "labels on " + owner + "/" + repo + " #" + issueNumber,
        auth,
        credential -> addLabelsOnce(credential, accept, owner, repo, issueNumber, request));
  }

  /** One HTTP attempt at creating a repository label. Callers want {@link #createLabel} instead. */
  @POST
  @Path("/repos/{owner}/{repo}/labels")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  Label createLabelOnce(
      @HeaderParam("Authorization") String auth,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      CreateLabelRequest request);

  /**
   * Creates a repository label, through {@link GitHubWriteRetry} for the same reason as {@link
   * #addLabels}. Only a rejected token or a throttle is repeated, and GitHub applied neither, so a
   * label is never created twice. Any other refusal, a name that already exists included, is thrown
   * to the caller.
   */
  default Label createLabel(
      String auth, String accept, String owner, String repo, CreateLabelRequest request) {
    return GitHubWriteRetry.DEFAULT.call(
        "label creation on " + owner + "/" + repo,
        auth,
        credential -> createLabelOnce(credential, accept, owner, repo, request));
  }

  @RegisterForReflection
  record Label(String name, String description, String color) {}

  record AddLabelsRequest(List<String> labels) {
    public AddLabelsRequest {
      labels = labels == null ? List.of() : List.copyOf(labels);
    }
  }

  record CreateLabelRequest(String name, String color, String description) {}
}
