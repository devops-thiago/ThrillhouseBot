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

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.regex.Pattern;

/**
 * Resolves the short session links posted on GitHub (check runs, comments) to the dashboard
 * sessions page. Links use the session's random public id — never the sequential database id — so
 * they cannot be enumerated; the dashboard is a static export, so dynamic paths need a server-side
 * redirect. Unknown or malformed ids fall back to the sessions list.
 */
@Path("/session")
public class SessionLinkResource {

  private static final Pattern PUBLIC_ID_PATTERN =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final URI SESSIONS_PAGE = URI.create("/dashboard/sessions/");

  private final ReviewSessionRepository reviewSessionRepository;

  public SessionLinkResource(ReviewSessionRepository reviewSessionRepository) {
    this.reviewSessionRepository = reviewSessionRepository;
  }

  @GET
  @Path("/{publicId}")
  public Response redirectToSession(@PathParam("publicId") String publicId) {
    // A matched path segment is never null.
    if (!PUBLIC_ID_PATTERN.matcher(publicId).matches()) {
      return Response.seeOther(SESSIONS_PAGE).build();
    }
    return reviewSessionRepository
        .find("publicId", publicId)
        .firstResultOptional()
        .map(session -> Response.seeOther(URI.create("/dashboard/sessions/?id=" + session.id)))
        .orElseGet(() -> Response.seeOther(SESSIONS_PAGE))
        .build();
  }
}
