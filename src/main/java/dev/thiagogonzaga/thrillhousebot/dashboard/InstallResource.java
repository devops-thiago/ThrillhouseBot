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
package dev.thiagogonzaga.thrillhousebot.dashboard;

import dev.thiagogonzaga.thrillhousebot.github.GitHubUrls;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

/**
 * What the static App install page ({@code /install.html}) needs from the configuration: the GitHub
 * host to create the App on, github.com or a GitHub Enterprise Server. Public, like the page: the
 * host is no secret.
 */
@Path("/api/install")
public class InstallResource {

  @GET
  @Path("/github")
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, String> github() {
    return Map.of("webUrl", GitHubUrls.webUrl());
  }
}
