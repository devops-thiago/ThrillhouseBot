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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "github-api")
@RegisterProvider(GitHubErrorLogger.class)
public interface GitHubTokenApi {

  @POST
  @Path("/app/installations/{installation_id}/access_tokens")
  @Produces(MediaType.APPLICATION_JSON)
  InstallationTokenResponse createInstallationToken(
      @jakarta.ws.rs.HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
      @jakarta.ws.rs.HeaderParam("Accept") String accept,
      @PathParam("installation_id") long installationId);

  /**
   * As {@link #createInstallationToken}, for a token that reaches only the repositories {@code
   * scope} names. GitHub answers 422 when the installation does not cover one of them.
   */
  @POST
  @Path("/app/installations/{installation_id}/access_tokens")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  InstallationTokenResponse createRepositoryToken(
      @jakarta.ws.rs.HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
      @jakarta.ws.rs.HeaderParam("Accept") String accept,
      @PathParam("installation_id") long installationId,
      RepositoryScope scope);

  /** The request body that limits a token to repositories of the installation, by name. */
  record RepositoryScope(List<String> repositories) {
    public RepositoryScope {
      repositories = List.copyOf(repositories);
    }
  }

  record InstallationTokenResponse(String token, @JsonProperty("expires_at") String expiresAt) {}
}
