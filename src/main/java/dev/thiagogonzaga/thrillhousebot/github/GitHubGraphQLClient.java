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

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Minimal GitHub GraphQL access for the operations the REST API does not expose — resolving review
 * threads in particular.
 */
@RegisterRestClient(configKey = "github-api")
@RegisterProvider(GitHubErrorLogger.class)
public interface GitHubGraphQLClient {

  /** One HTTP attempt at a GraphQL operation. Callers want {@link #execute} instead. */
  @POST
  @Path("/graphql")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  JsonNode executeOnce(@HeaderParam("Authorization") String auth, GraphQLRequest request);

  /**
   * Runs a GraphQL operation, minting a fresh installation token and repeating the call once when
   * GitHub rejects the credential (#626). Thread resolution runs at the very end of a review —
   * exactly where a token read at its start has expired — and a 401 is decided before the operation
   * runs, so repeating it can neither resolve a thread twice nor skip one.
   */
  default JsonNode execute(String auth, GraphQLRequest request) {
    return GitHubTokenRefresh.SHARED.retrying(
        "GraphQL operation", auth, credential -> executeOnce(credential, request));
  }

  @RegisterForReflection
  record GraphQLRequest(String query, Map<String, Object> variables) {
    public GraphQLRequest {
      variables = Map.copyOf(variables != null ? variables : Map.of());
    }
  }
}
