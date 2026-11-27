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
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The repository lookup that has to see a redirect, and the shapes of the installation listing
 * {@link GitHubUserClient} reads.
 *
 * <p>The lookup stays on a REST client because of a hub4j limitation: hub4j follows a redirect
 * itself and never gives out the {@code 301} or its {@code Location}, which carries the repository
 * id the scope backfill matches on, and it drops the token when the redirect changes host. This
 * goes away once hub4j reports the redirect. An installation's own repositories are listed through
 * {@link GitHubTokenReads}.
 */
@RegisterRestClient(configKey = "github-api")
@RegisterProvider(GitHubErrorLogger.class)
public interface GitHubInstallationClient {

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
}
