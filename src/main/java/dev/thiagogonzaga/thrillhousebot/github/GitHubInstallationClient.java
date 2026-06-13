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

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "github-api")
public interface GitHubInstallationClient {

  @GET
  @Path("/app")
  @Produces(MediaType.APPLICATION_JSON)
  AppInfo getApp(
      @HeaderParam("Authorization") String authorization, @HeaderParam("Accept") String accept);

  @GET
  @Path("/app/installations")
  @Produces(MediaType.APPLICATION_JSON)
  List<Installation> listInstallations(
      @HeaderParam("Authorization") String authorization,
      @HeaderParam("Accept") String accept,
      @QueryParam("per_page") @DefaultValue("100") int perPage,
      @QueryParam("page") @DefaultValue("1") int page);

  @GET
  @Path("/installation/repositories")
  @Produces(MediaType.APPLICATION_JSON)
  InstallationRepositoriesResponse listInstallationRepositories(
      @HeaderParam("Authorization") String authorization,
      @HeaderParam("Accept") String accept,
      @QueryParam("per_page") @DefaultValue("100") int perPage,
      @QueryParam("page") @DefaultValue("1") int page);

  @GET
  @Path("/repos/{owner}/{repo}/collaborators/{username}")
  Response checkCollaborator(
      @HeaderParam("Authorization") String authorization,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo,
      @PathParam("username") String username);

  record Installation(long id, Account account) {
    public record Account(String login, String type) {}
  }

  record AppInfo(Owner owner) {
    public record Owner(String login, String type) {}
  }

  record InstallationRepositoriesResponse(
      @JsonProperty("total_count") int totalCount, List<Repository> repositories) {
    public InstallationRepositoriesResponse(int totalCount, List<Repository> repositories) {
      this.totalCount = totalCount;
      this.repositories = repositories == null ? List.of() : List.copyOf(repositories);
    }

    public record Repository(String name, Owner owner) {
      public record Owner(String login) {}
    }
  }
}
