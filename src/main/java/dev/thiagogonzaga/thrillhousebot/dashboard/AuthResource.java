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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/api/auth")
public class AuthResource {

  private static final Logger log = LoggerFactory.getLogger(AuthResource.class);

  private static final String GITHUB_API_URL = "https://api.github.com";
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  // Cookie / path constants
  private static final String COOKIE_OAUTH_STATE = "thrillhouse_oauth_state";
  private static final String COOKIE_SESSION = "thrillhouse_session";
  private static final String PATH_AUTH = "/api/auth";

  // Header name constants
  private static final String HEADER_ACCEPT = "Accept";
  private static final String HEADER_AUTHORIZATION = "Authorization";

  // GitHub API path / header value constants
  private static final String GITHUB_PATH_USER = "/user";
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String GITHUB_ACCEPT_VALUE = "application/vnd.github+json";

  // JSON key constants
  private static final String KEY_LOGIN = "login";

  // Error message constants
  private static final String KEY_ERROR = "error";
  private static final String MSG_NOT_AUTHENTICATED = "Not authenticated";
  private static final String MSG_SESSION_EXPIRED = "Session expired";
  private static final String MSG_DASHBOARD_NOT_CONFIGURED = "Dashboard OAuth not configured";
  private static final String MSG_MISSING_CODE = "Missing authorization code";
  private static final String MSG_INVALID_CODE = "Invalid authorization code";
  private static final Pattern OAUTH_CODE_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");
  private static final String MSG_INVALID_STATE = "Invalid state parameter";
  private static final String MSG_AUTH_FAILED = "Authentication failed";
  private static final String MSG_AUTH_FAILED_GITHUB = "Failed to authenticate with GitHub";
  private static final String MSG_ACCESS_DENIED =
      "Access denied — you must be a collaborator on an installed repository";

  private final ThrillhouseConfig config;
  private final DashboardAccessChecker accessChecker;
  private final DashboardSessionStore sessionStore;

  private final ObjectMapper mapper;

  private final HttpClient httpClient;

  public AuthResource(
      ThrillhouseConfig config,
      ObjectMapper mapper,
      DashboardAccessChecker accessChecker,
      DashboardSessionStore sessionStore,
      HttpClient httpClient) {
    this.config = config;
    this.mapper = mapper;
    this.accessChecker = accessChecker;
    this.sessionStore = sessionStore;
    this.httpClient = httpClient;
  }

  @GET
  @Path("/login")
  public Response login() {
    var clientId = config.dashboard().clientId().filter(s -> !s.isBlank()).orElse(null);
    if (clientId == null) {
      return Response.status(Response.Status.SERVICE_UNAVAILABLE)
          .entity(Map.of(KEY_ERROR, MSG_DASHBOARD_NOT_CONFIGURED))
          .build();
    }

    // Generate random state for CSRF protection
    byte[] stateBytes = new byte[32];
    SECURE_RANDOM.nextBytes(stateBytes);
    var state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes);

    var stateCookie =
        new NewCookie.Builder(COOKIE_OAUTH_STATE)
            .value(state)
            .path(PATH_AUTH)
            .httpOnly(true)
            .secure(true)
            .sameSite(NewCookie.SameSite.LAX)
            .maxAge(300)
            .build();

    var redirectUri = config.dashboard().redirectUri();
    var url =
        config.dashboard().oauthUrl()
            + "/authorize"
            + "?client_id="
            + urlEncode(clientId)
            + "&redirect_uri="
            + urlEncode(redirectUri)
            + "&scope=read:user,read:org"
            + "&state="
            + urlEncode(state);

    return Response.seeOther(URI.create(url)).cookie(stateCookie).build();
  }

  @GET
  @Path("/callback")
  public Response callback(
      @QueryParam("code") String code,
      @QueryParam("state") String state,
      @CookieParam(COOKIE_OAUTH_STATE) String stateCookie) {
    if (code == null || code.isBlank()) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity(Map.of(KEY_ERROR, MSG_MISSING_CODE))
          .build();
    }

    // Validate OAuth state parameter to prevent CSRF
    if (state == null || stateCookie == null || !state.equals(stateCookie)) {
      log.warn("OAuth state mismatch — CSRF attempt blocked");
      var clearedCookie =
          new NewCookie.Builder(COOKIE_OAUTH_STATE)
              .value("")
              .path(PATH_AUTH)
              .httpOnly(true)
              .secure(true)
              .sameSite(NewCookie.SameSite.LAX)
              .maxAge(0)
              .build();
      return Response.status(Response.Status.FORBIDDEN)
          .entity(Map.of(KEY_ERROR, MSG_INVALID_STATE))
          .cookie(clearedCookie)
          .build();
    }

    if (!OAUTH_CODE_PATTERN.matcher(code).matches()) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity(Map.of(KEY_ERROR, MSG_INVALID_CODE))
          .build();
    }

    var clearedStateCookie =
        new NewCookie.Builder(COOKIE_OAUTH_STATE)
            .value("")
            .path(PATH_AUTH)
            .httpOnly(true)
            .secure(true)
            .sameSite(NewCookie.SameSite.LAX)
            .maxAge(0)
            .build();

    var clientId = config.dashboard().clientId().filter(s -> !s.isBlank()).orElse("");
    var clientSecret = config.dashboard().clientSecret().filter(s -> !s.isBlank()).orElse("");

    try {
      var tokenUrl = config.dashboard().oauthUrl() + "/access_token";
      String body = buildTokenExchangeBody(clientId, clientSecret, code);

      var request =
          HttpRequest.newBuilder()
              .uri(URI.create(tokenUrl))
              .header(HEADER_ACCEPT, "application/json")
              .header("Content-Type", "application/x-www-form-urlencoded")
              .timeout(config.httpRequestTimeout())
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();

      var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      var tokenData = mapper.readValue(response.body(), Map.class);
      var accessToken = (String) tokenData.get("access_token");

      if (accessToken == null) {
        log.error("Failed to get access token");
        return Response.status(Response.Status.UNAUTHORIZED)
            .entity(Map.of(KEY_ERROR, MSG_AUTH_FAILED_GITHUB))
            .cookie(clearedStateCookie)
            .build();
      }

      var userRequest =
          HttpRequest.newBuilder()
              .uri(URI.create(GITHUB_API_URL + GITHUB_PATH_USER))
              .header(HEADER_AUTHORIZATION, BEARER_PREFIX + accessToken)
              .header(HEADER_ACCEPT, GITHUB_ACCEPT_VALUE)
              .timeout(config.httpRequestTimeout())
              .GET()
              .build();

      var userResponse = httpClient.send(userRequest, HttpResponse.BodyHandlers.ofString());
      if (userResponse.statusCode() != 200) {
        log.error("Failed to verify user token: HTTP {}", userResponse.statusCode());
        return Response.status(Response.Status.UNAUTHORIZED)
            .entity(Map.of(KEY_ERROR, MSG_AUTH_FAILED_GITHUB))
            .cookie(clearedStateCookie)
            .build();
      }
      var userData = mapper.readValue(userResponse.body(), Map.class);
      var login = (String) userData.get(KEY_LOGIN);
      if (login == null || login.isBlank()) {
        return Response.status(Response.Status.UNAUTHORIZED)
            .entity(Map.of(KEY_ERROR, MSG_AUTH_FAILED_GITHUB))
            .cookie(clearedStateCookie)
            .build();
      }

      if (!accessChecker.hasAccess(login)) {
        return Response.status(Response.Status.FORBIDDEN)
            .entity(Map.of(KEY_ERROR, MSG_ACCESS_DENIED))
            .cookie(clearedStateCookie)
            .build();
      }

      String avatarUrl = userData.get("avatar_url") instanceof String url ? url : null;
      var nameValue = userData.get("name");
      String displayName = nameValue instanceof String name && !name.isBlank() ? name : login;
      var sessionId = sessionStore.createSession(login, accessToken, avatarUrl, displayName);

      var cookie =
          new NewCookie.Builder(COOKIE_SESSION)
              .value(sessionId)
              .path("/")
              .httpOnly(true)
              .secure(true)
              .sameSite(NewCookie.SameSite.LAX)
              .maxAge(DashboardSessionStore.SESSION_MAX_AGE_SECONDS)
              .build();

      return Response.seeOther(URI.create("/dashboard/overview/"))
          .cookie(cookie)
          .cookie(clearedStateCookie)
          .build();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.error("OAuth callback interrupted", e);
      throw new AuthException("OAuth callback interrupted", e);
    } catch (IOException e) {
      log.error("OAuth callback failed", e);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity(Map.of(KEY_ERROR, MSG_AUTH_FAILED))
          .cookie(clearedStateCookie)
          .build();
    }
  }

  @GET
  @Path("/me")
  @Produces(MediaType.APPLICATION_JSON)
  public Response me(@CookieParam(COOKIE_SESSION) String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return Response.status(Response.Status.UNAUTHORIZED)
          .entity(Map.of(KEY_ERROR, MSG_NOT_AUTHENTICATED))
          .build();
    }

    var session = sessionStore.findSession(sessionId);
    if (session.isEmpty()) {
      return Response.status(Response.Status.UNAUTHORIZED)
          .entity(Map.of(KEY_ERROR, MSG_SESSION_EXPIRED))
          .build();
    }

    var user = session.get();
    if (!accessChecker.hasAccess(user.login())) {
      return Response.status(Response.Status.FORBIDDEN)
          .entity(Map.of(KEY_ERROR, MSG_ACCESS_DENIED))
          .build();
    }

    return Response.ok(
            Map.of(KEY_LOGIN, user.login(), "avatarUrl", user.avatarUrl(), "name", user.name()))
        .build();
  }

  @POST
  @Path("/logout")
  public Response logout(@CookieParam(COOKIE_SESSION) String sessionId) {
    sessionStore
        .findSession(sessionId)
        .ifPresent(session -> revokeGitHubToken(session.accessToken()));
    sessionStore.invalidate(sessionId);
    return Response.noContent().cookie(clearedSessionCookie()).build();
  }

  /**
   * Best-effort revocation of the GitHub OAuth token, so a token captured before logout cannot
   * outlive the dashboard session. Logout must succeed even when GitHub is unreachable.
   */
  private void revokeGitHubToken(String accessToken) {
    var clientId = config.dashboard().clientId().filter(s -> !s.isBlank()).orElse(null);
    var clientSecret = config.dashboard().clientSecret().filter(s -> !s.isBlank()).orElse(null);
    if (clientId == null || clientSecret == null || accessToken == null || accessToken.isBlank()) {
      return;
    }
    try {
      var credentials =
          Base64.getEncoder()
              .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
      var body = mapper.writeValueAsString(Map.of("access_token", accessToken));
      var request =
          HttpRequest.newBuilder()
              .uri(URI.create(GITHUB_API_URL + "/applications/" + urlEncode(clientId) + "/token"))
              .header(HEADER_AUTHORIZATION, "Basic " + credentials)
              .header(HEADER_ACCEPT, GITHUB_ACCEPT_VALUE)
              .header("Content-Type", "application/json")
              .timeout(config.httpRequestTimeout())
              .method("DELETE", HttpRequest.BodyPublishers.ofString(body))
              .build();
      var response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() != 204) {
        log.warn("GitHub token revocation returned HTTP {}", response.statusCode());
      }
    } catch (IOException | RuntimeException e) {
      log.warn("GitHub token revocation failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("GitHub token revocation interrupted", e);
    }
  }

  static String buildTokenExchangeBody(String clientId, String clientSecret, String code) {
    return formField("client_id", clientId)
        + "&"
        + formField("client_secret", clientSecret)
        + "&"
        + formField("code", code);
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String formField(String name, String value) {
    return urlEncode(name) + "=" + urlEncode(value);
  }

  private NewCookie clearedSessionCookie() {
    return new NewCookie.Builder(COOKIE_SESSION)
        .value("")
        .path("/")
        .httpOnly(true)
        .secure(true)
        .sameSite(NewCookie.SameSite.LAX)
        .maxAge(0)
        .build();
  }
}
