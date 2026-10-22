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

import jakarta.ws.rs.CookieParam;
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
 * redirect. The redirect reveals the session's database id, so it is given only to a dashboard
 * login that may read the session's repository (#1220). Anyone else, like an unknown or malformed
 * id, falls back to the sessions list, so the response does not tell whether the session exists.
 */
@Path("/session")
public class SessionLinkResource {

  private static final Pattern PUBLIC_ID_PATTERN =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final URI SESSIONS_PAGE = URI.create("/dashboard/sessions/");

  private final ReviewSessionRepository reviewSessionRepository;
  private final DashboardSessionValidator sessionValidator;

  public SessionLinkResource(
      ReviewSessionRepository reviewSessionRepository, DashboardSessionValidator sessionValidator) {
    this.reviewSessionRepository = reviewSessionRepository;
    this.sessionValidator = sessionValidator;
  }

  @GET
  @Path("/{publicId}")
  public Response redirectToSession(
      @PathParam("publicId") String publicId,
      @CookieParam("thrillhouse_session") String sessionToken) {
    // A matched path segment is never null.
    if (!PUBLIC_ID_PATTERN.matcher(publicId).matches()
        || !sessionValidator.isValidSession(sessionToken)) {
      return Response.seeOther(SESSIONS_PAGE).build();
    }
    var scope = sessionValidator.repositoryScope(sessionToken);
    return reviewSessionRepository
        .find("publicId", publicId)
        .firstResultOptional()
        .filter(session -> scope.allows(session.getInstallationId(), session.getRepositoryId()))
        .map(session -> Response.seeOther(URI.create("/dashboard/sessions/?id=" + session.id)))
        .orElseGet(() -> Response.seeOther(SESSIONS_PAGE))
        .build();
  }
}
