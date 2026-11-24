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
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "github-api")
@RegisterProvider(GitHubErrorLogger.class)
public interface GitHubInstallationClient {

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

  /**
   * The repository by name. A renamed repository answers its old name with a 301 whose {@code
   * Location} ends in {@code /repositories/{id}}; redirects are not followed, so the caller reads
   * the id from it (#1220).
   */
  @GET
  @Path("/repos/{owner}/{repo}")
  Response getRepository(
      @HeaderParam("Authorization") String authorization,
      @HeaderParam("Accept") String accept,
      @PathParam("owner") String owner,
      @PathParam("repo") String repo);

  /**
   * An installation of the App; {@code suspendedAt} is set while its owner has it suspended, and
   * such an installation is not served (#1222).
   */
  record Installation(long id, Account account, @JsonProperty("suspended_at") String suspendedAt) {
    public record Account(String login, String type) {}
  }

  record InstallationRepositoriesResponse(
      @JsonProperty("total_count") int totalCount, List<Repository> repositories) {
    public InstallationRepositoriesResponse(int totalCount, List<Repository> repositories) {
      this.totalCount = totalCount;
      this.repositories = repositories == null ? List.of() : List.copyOf(repositories);
    }

    /**
     * {@code createdAt} tells a repository from an older one deleted under the same name: the scope
     * backfill fills only rows stored after it.
     */
    public record Repository(
        long id, String name, Owner owner, @JsonProperty("created_at") Instant createdAt) {
      public Repository(long id, String name, Owner owner) {
        this(id, name, owner, null);
      }

      public record Owner(String login) {}
    }
  }
}
