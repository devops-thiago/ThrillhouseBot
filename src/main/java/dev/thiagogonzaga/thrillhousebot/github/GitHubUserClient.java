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
import java.util.List;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Calls made with a dashboard user's own GitHub App user access token (#1221), so GitHub answers
 * with what that user can see: the App's installations they can access, and in each one the
 * repositories they can read (any collaborator role, team access or organization base permission).
 */
@RegisterRestClient(configKey = "github-api")
@RegisterProvider(GitHubErrorLogger.class)
public interface GitHubUserClient {

  @GET
  @Path("/user/installations")
  @Produces(MediaType.APPLICATION_JSON)
  UserInstallations listInstallations(
      @HeaderParam("Authorization") String authorization,
      @HeaderParam("Accept") String accept,
      @QueryParam("per_page") @DefaultValue("100") int perPage,
      @QueryParam("page") @DefaultValue("1") int page);

  @GET
  @Path("/user/installations/{installationId}/repositories")
  @Produces(MediaType.APPLICATION_JSON)
  GitHubInstallationClient.InstallationRepositoriesResponse listRepositories(
      @HeaderParam("Authorization") String authorization,
      @HeaderParam("Accept") String accept,
      @PathParam("installationId") long installationId,
      @QueryParam("per_page") @DefaultValue("100") int perPage,
      @QueryParam("page") @DefaultValue("1") int page);

  /** One page of the user's installations of this App. */
  record UserInstallations(
      @JsonProperty("total_count") int totalCount,
      List<GitHubInstallationClient.Installation> installations) {
    public UserInstallations {
      installations = installations == null ? List.of() : List.copyOf(installations);
    }
  }
}
