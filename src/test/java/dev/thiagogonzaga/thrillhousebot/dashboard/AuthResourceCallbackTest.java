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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthResourceCallbackTest {

  @Mock HttpResponse httpResponse;

  AuthResourceTestFixtures.Context fixtureContext;
  AuthResource resource;

  @BeforeEach
  void setUp() {
    fixtureContext = AuthResourceTestFixtures.newContext();
    resource = fixtureContext.build();
  }

  @Test
  void callbackShouldRejectUserWithoutRepoAccess() throws Exception {
    when(fixtureContext.accessChecker.isAccessControlEnabled()).thenReturn(true);
    when(fixtureContext.accessChecker.hasAccess("outsider")).thenReturn(false);
    when(fixtureContext.httpClient.send(any(HttpRequest.class), any())).thenReturn(httpResponse);
    when(httpResponse.statusCode()).thenReturn(200);
    when(httpResponse.body())
        .thenReturn("{\"access_token\":\"gho_test123\"}")
        .thenReturn("{\"login\":\"outsider\",\"avatar_url\":\"https://a.co\",\"name\":\"Out\"}");

    var response = resource.callback("valid-code", "test-state", "test-state");
    assertEquals(403, response.getStatus());
  }

  @Test
  void callbackShouldReturn503WhenOwnerNotConfigured() throws Exception {
    when(fixtureContext.accessChecker.isAccessControlEnabled()).thenReturn(false);
    when(fixtureContext.httpClient.send(any(HttpRequest.class), any())).thenReturn(httpResponse);
    when(httpResponse.statusCode()).thenReturn(200);
    when(httpResponse.body())
        .thenReturn("{\"access_token\":\"gho_test123\"}")
        .thenReturn("{\"login\":\"someuser\",\"avatar_url\":\"https://a.co\",\"name\":\"Some\"}");

    var response = resource.callback("valid-code", "test-state", "test-state");

    assertEquals(503, response.getStatus());
    assertTrue(
        ((Map<?, ?>) response.getEntity()).get("error").toString().contains("account-owner"));
    // Fail closed: no session is created when access control cannot be enforced.
    verify(fixtureContext.accessChecker, never()).hasAccess(any());
  }

  @Test
  void callbackShouldExchangeCodeAndSetOpaqueSessionCookie() throws Exception {
    when(fixtureContext.httpClient.send(any(HttpRequest.class), any())).thenReturn(httpResponse);
    when(httpResponse.statusCode()).thenReturn(200);
    when(httpResponse.body())
        .thenReturn("{\"access_token\":\"gho_test123\"}")
        .thenReturn("{\"login\":\"testuser\",\"avatar_url\":\"https://a.co\",\"name\":\"Test\"}");

    var response = resource.callback("valid-code", "test-state", "test-state");
    assertEquals(303, response.getStatus());
    assertEquals("/dashboard/overview/", response.getLocation().toString());

    var sessionCookie =
        response.getCookies().values().stream()
            .filter(c -> "thrillhouse_session".equals(c.getName()))
            .findFirst()
            .orElseThrow();
    assertNotEquals("gho_test123", sessionCookie.getValue());
    assertTrue(fixtureContext.sessionStore.findSession(sessionCookie.getValue()).isPresent());
  }

  @Test
  void callbackShouldReturn401WhenGitHubUserVerificationFails() throws Exception {
    when(fixtureContext.httpClient.send(any(HttpRequest.class), any())).thenReturn(httpResponse);
    when(httpResponse.statusCode()).thenReturn(401);
    when(httpResponse.body())
        .thenReturn("{\"access_token\":\"gho_test123\"}")
        .thenReturn("{\"message\":\"Bad credentials\"}");

    var response = resource.callback("valid-code", "test-state", "test-state");
    assertEquals(401, response.getStatus());
    assertEquals(
        "Failed to authenticate with GitHub", ((Map<?, ?>) response.getEntity()).get("error"));
  }

  @Test
  void callbackShouldReturn401WhenGitHubUserHasBlankLogin() throws Exception {
    when(fixtureContext.httpClient.send(any(HttpRequest.class), any())).thenReturn(httpResponse);
    when(httpResponse.statusCode()).thenReturn(200);
    when(httpResponse.body())
        .thenReturn("{\"access_token\":\"gho_test123\"}")
        .thenReturn("{\"login\":\"\",\"avatar_url\":\"https://a.co\"}");

    var response = resource.callback("valid-code", "test-state", "test-state");
    assertEquals(401, response.getStatus());
    assertEquals(
        "Failed to authenticate with GitHub", ((Map<?, ?>) response.getEntity()).get("error"));
  }

  @Test
  void callbackShouldNormalizeNullNameFromGitHub() throws Exception {
    when(fixtureContext.httpClient.send(any(HttpRequest.class), any())).thenReturn(httpResponse);
    when(httpResponse.statusCode()).thenReturn(200);
    when(httpResponse.body())
        .thenReturn("{\"access_token\":\"gho_test123\"}")
        .thenReturn("{\"login\":\"testuser\",\"avatar_url\":null,\"name\":null}");

    var response = resource.callback("valid-code", "test-state", "test-state");
    assertEquals(303, response.getStatus());

    var sessionCookie =
        response.getCookies().values().stream()
            .filter(c -> "thrillhouse_session".equals(c.getName()))
            .findFirst()
            .orElseThrow();
    var session = fixtureContext.sessionStore.findSession(sessionCookie.getValue()).orElseThrow();
    assertEquals("testuser", session.login());
    assertEquals("", session.avatarUrl());
    assertEquals("testuser", session.name());

    var meResponse = resource.me(sessionCookie.getValue());
    assertEquals(200, meResponse.getStatus());
  }

  @Test
  void callbackShouldRejectMismatchedState() {
    var response = resource.callback("valid-code", "test-state", "wrong-state");
    assertEquals(403, response.getStatus());
  }

  @Test
  void callbackShouldRejectNullStateCookie() {
    var response = resource.callback("valid-code", "test-state", null);
    assertEquals(403, response.getStatus());
  }

  @Test
  void meShouldReturn401WhenSessionNull() {
    assertEquals(401, resource.me(null).getStatus());
  }

  @Test
  void loginShouldReturn503WhenClientIdNotConfigured() {
    when(fixtureContext.dashboardConfig.clientId()).thenReturn(Optional.of("   "));

    var response = resource.login();

    assertEquals(503, response.getStatus());
    assertEquals("Dashboard OAuth not configured", ((Map<?, ?>) response.getEntity()).get("error"));
  }

  @Test
  void callbackShouldUseEmptyCredentialsWhenOptionalBlank() throws Exception {
    when(fixtureContext.dashboardConfig.clientId()).thenReturn(Optional.of("   "));
    when(fixtureContext.dashboardConfig.clientSecret()).thenReturn(Optional.of("   "));
    when(fixtureContext.httpClient.send(any(HttpRequest.class), any())).thenReturn(httpResponse);
    when(httpResponse.statusCode()).thenReturn(200);
    when(httpResponse.body())
        .thenReturn("{\"access_token\":\"gho_test123\"}")
        .thenReturn("{\"login\":\"testuser\",\"avatar_url\":\"https://a.co\",\"name\":\"Test\"}");

    var response = resource.callback("valid-code", "test-state", "test-state");

    assertEquals(303, response.getStatus());
    verify(fixtureContext.httpClient, times(2)).send(any(HttpRequest.class), any());
  }

  @Test
  void loginShouldRedirectToGitHub() {
    assertEquals(303, resource.login().getStatus());
  }

  @Test
  void loginShouldUrlEncodeRedirectUri() {
    when(fixtureContext.dashboardConfig.redirectUri())
        .thenReturn("http://localhost:8080/api/auth/callback?foo=bar&baz=qux");

    var location = resource.login().getLocation();
    var query = location.getRawQuery();
    String redirectUri =
        URLDecoder.decode(
            query.substring(query.indexOf("redirect_uri=") + "redirect_uri=".length())
                .split("&")[0],
            StandardCharsets.UTF_8);

    assertEquals("http://localhost:8080/api/auth/callback?foo=bar&baz=qux", redirectUri);
  }

  @Test
  void buildTokenExchangeBodyShouldUrlEncodeValues() {
    assertEquals(
        "client_id=test-client-id&client_secret=secret%26with%3Dspecial&code=valid-code",
        AuthResource.buildTokenExchangeBody("test-client-id", "secret&with=special", "valid-code"));
  }

  @Test
  void callbackShouldRejectCodeWithParameterInjectionCharacters() {
    var response =
        resource.callback("foo&redirect_uri=https://evil.example", "test-state", "test-state");
    assertEquals(400, response.getStatus());
    assertEquals("Invalid authorization code", ((Map<?, ?>) response.getEntity()).get("error"));
  }

  @Test
  void callbackShouldRejectInjectionCodeBeforeFormatCheckWhenStateInvalid() {
    var response =
        resource.callback("foo&redirect_uri=https://evil.example", "test-state", "wrong-state");
    assertEquals(403, response.getStatus());
    assertEquals("Invalid state parameter", ((Map<?, ?>) response.getEntity()).get("error"));
  }

  @Test
  void callbackShouldReturn400WhenCodeIsBlank() {
    var response = resource.callback("   ", "test-state", "test-state");
    assertEquals(400, response.getStatus());
    assertEquals("Missing authorization code", ((Map<?, ?>) response.getEntity()).get("error"));
  }

  @Test
  void callbackShouldReturn401WhenTokenResponseHasNoAccessToken() throws Exception {
    when(fixtureContext.httpClient.send(any(HttpRequest.class), any())).thenReturn(httpResponse);
    when(httpResponse.body()).thenReturn("{\"error\":\"bad_verification_code\"}");

    var response = resource.callback("code123", "valid-state", "valid-state");
    assertEquals(401, response.getStatus());
    assertEquals(
        "Failed to authenticate with GitHub", ((Map<?, ?>) response.getEntity()).get("error"));
  }

  @Test
  void callbackShouldReturn401WhenUserVerifyFails() throws Exception {
    when(fixtureContext.httpClient.send(any(HttpRequest.class), any())).thenReturn(httpResponse);
    when(httpResponse.body()).thenReturn("{\"access_token\":\"gho_test123\"}");
    when(httpResponse.statusCode()).thenReturn(401);

    var response = resource.callback("code123", "valid-state", "valid-state");
    assertEquals(401, response.getStatus());
    assertEquals(
        "Failed to authenticate with GitHub", ((Map<?, ?>) response.getEntity()).get("error"));
  }

  @Test
  void callbackShouldReturn401WhenUserLoginMissing() throws Exception {
    when(fixtureContext.httpClient.send(any(HttpRequest.class), any())).thenReturn(httpResponse);
    when(httpResponse.statusCode()).thenReturn(200);
    when(httpResponse.body())
        .thenReturn("{\"access_token\":\"gho_test123\"}")
        .thenReturn("{\"login\":null,\"avatar_url\":\"https://a.co\"}");

    var response = resource.callback("code123", "valid-state", "valid-state");
    assertEquals(401, response.getStatus());
    assertEquals(
        "Failed to authenticate with GitHub", ((Map<?, ?>) response.getEntity()).get("error"));
  }

  @Test
  void callbackShouldHandleIOExceptionDuringTokenExchange() throws Exception {
    when(fixtureContext.httpClient.send(any(HttpRequest.class), any()))
        .thenThrow(new IOException("Network error"));

    var response = resource.callback("code123", "valid-state", "valid-state");
    assertEquals(500, response.getStatus());
    assertEquals("Authentication failed", ((Map<?, ?>) response.getEntity()).get("error"));
  }

  @Test
  void callbackShouldHandleInterruptedExceptionDuringTokenExchange() throws Exception {
    when(fixtureContext.httpClient.send(any(HttpRequest.class), any()))
        .thenThrow(new InterruptedException("Interrupted"));

    assertThrows(
        AuthException.class,
        () -> {
          resource.callback("code123", "valid-state", "valid-state");
        });
  }

  @Test
  void meShouldReturnUserDataWhenSessionValid() {
    var sessionId =
        fixtureContext.sessionStore.createSession(
            "testuser", "gho_secret", "https://a.co", "Test User");

    var response = resource.me(sessionId);
    assertEquals(200, response.getStatus());
    var entity = (Map<?, ?>) response.getEntity();
    assertEquals("testuser", entity.get("login"));
    assertEquals("https://a.co", entity.get("avatarUrl"));
    assertEquals("Test User", entity.get("name"));
  }

  @Test
  void meShouldReturn401WhenSessionUnknown() {
    var response = resource.me("unknown-session-id");
    assertEquals(401, response.getStatus());
    assertEquals("Session expired", ((Map<?, ?>) response.getEntity()).get("error"));
  }

  @Test
  void meShouldReturn401WhenOpaqueSessionExpired() {
    var now =
        new java.util.concurrent.atomic.AtomicReference<java.time.Instant>(
            java.time.Instant.parse("2026-06-09T12:00:00Z"));
    var timedStore = new DashboardSessionStore(now::get);
    var timedResource =
        new AuthResource(
            fixtureContext.config,
            fixtureContext.objectMapper,
            fixtureContext.accessChecker,
            timedStore,
            fixtureContext.httpClient);

    var sessionId = timedStore.createSession("testuser", "gho_secret", "https://a.co", "Test");
    now.set(now.get().plus(DashboardSessionStore.SESSION_TTL).plusMillis(1));

    var response = timedResource.me(sessionId);
    assertEquals(401, response.getStatus());
    assertEquals("Session expired", ((Map<?, ?>) response.getEntity()).get("error"));
  }

  @Test
  void meShouldHandleNullProfileFieldsFromSessionStore() {
    var sessionId = fixtureContext.sessionStore.createSession("testuser", "gho_secret", null, null);

    var response = resource.me(sessionId);
    assertEquals(200, response.getStatus());
    var entity = (Map<?, ?>) response.getEntity();
    assertEquals("testuser", entity.get("login"));
    assertEquals("", entity.get("avatarUrl"));
    assertEquals("testuser", entity.get("name"));
  }

  @Test
  void meShouldReturnUserWhenAccessControlEnabledAndAllowed() {
    when(fixtureContext.accessChecker.isAccessControlEnabled()).thenReturn(true);
    when(fixtureContext.accessChecker.hasAccess("testuser")).thenReturn(true);
    var sessionId =
        fixtureContext.sessionStore.createSession("testuser", "gho_secret", "https://a.co", "Test");

    var response = resource.me(sessionId);
    assertEquals(200, response.getStatus());
    var entity = (Map<?, ?>) response.getEntity();
    assertEquals("testuser", entity.get("login"));
  }

  @Test
  void meShouldReturn503WhenOwnerNotConfigured() {
    when(fixtureContext.accessChecker.isAccessControlEnabled()).thenReturn(false);
    var sessionId =
        fixtureContext.sessionStore.createSession("testuser", "gho_secret", "https://a.co", "Test");

    var response = resource.me(sessionId);

    assertEquals(503, response.getStatus());
    assertTrue(
        ((Map<?, ?>) response.getEntity()).get("error").toString().contains("account-owner"));
    verify(fixtureContext.accessChecker, never()).hasAccess(any());
  }

  @Test
  void meShouldReturn403WhenAccessControlEnabledAndAccessDenied() {
    when(fixtureContext.accessChecker.isAccessControlEnabled()).thenReturn(true);
    when(fixtureContext.accessChecker.hasAccess("outsider")).thenReturn(false);
    var sessionId =
        fixtureContext.sessionStore.createSession("outsider", "gho_secret", "https://a.co", "Out");

    var response = resource.me(sessionId);
    assertEquals(403, response.getStatus());
  }

  @Test
  void logoutShouldInvalidateServerSideSession() {
    var sessionId =
        fixtureContext.sessionStore.createSession("testuser", "gho_secret", "https://a.co", "Test");

    var response = resource.logout(sessionId);

    assertEquals(204, response.getStatus());
    assertTrue(fixtureContext.sessionStore.findSession(sessionId).isEmpty());
  }

  @Test
  void logoutShouldRevokeGitHubToken() throws Exception {
    when(fixtureContext.httpClient.send(any(HttpRequest.class), any())).thenReturn(httpResponse);
    when(httpResponse.statusCode()).thenReturn(204);
    var sessionId =
        fixtureContext.sessionStore.createSession("testuser", "gho_secret", "https://a.co", "Test");

    var response = resource.logout(sessionId);

    assertEquals(204, response.getStatus());
    var captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(fixtureContext.httpClient).send(captor.capture(), any());
    var request = captor.getValue();
    assertEquals("DELETE", request.method());
    assertEquals(
        "https://api.github.com/applications/test-client-id/token", request.uri().toString());
    assertEquals(Optional.of(Duration.ofSeconds(10)), request.timeout());
    assertTrue(request.headers().firstValue("Authorization").orElse("").startsWith("Basic "));
  }

  @Test
  void logoutShouldSucceedWhenRevocationFails() throws Exception {
    when(fixtureContext.httpClient.send(any(HttpRequest.class), any()))
        .thenThrow(new IOException("github down"));
    var sessionId =
        fixtureContext.sessionStore.createSession("testuser", "gho_secret", "https://a.co", "Test");

    var response = resource.logout(sessionId);

    assertEquals(204, response.getStatus());
    assertTrue(fixtureContext.sessionStore.findSession(sessionId).isEmpty());
  }

  @Test
  void logoutShouldSkipRevocationWithoutSession() throws Exception {
    var response = resource.logout("unknown-session");

    assertEquals(204, response.getStatus());
    verify(fixtureContext.httpClient, never()).send(any(HttpRequest.class), any());
  }

  @Test
  void logoutShouldSkipRevocationWhenOauthNotConfigured() throws Exception {
    when(fixtureContext.dashboardConfig.clientSecret()).thenReturn(Optional.empty());
    var sessionId =
        fixtureContext.sessionStore.createSession("testuser", "gho_secret", "https://a.co", "Test");

    var response = resource.logout(sessionId);

    assertEquals(204, response.getStatus());
    assertTrue(fixtureContext.sessionStore.findSession(sessionId).isEmpty());
    verify(fixtureContext.httpClient, never()).send(any(HttpRequest.class), any());
  }

  @Test
  void logoutShouldSkipRevocationWhenClientIdMissing() throws Exception {
    when(fixtureContext.dashboardConfig.clientId()).thenReturn(Optional.empty());
    var sessionId =
        fixtureContext.sessionStore.createSession("testuser", "gho_secret", "https://a.co", "Test");

    var response = resource.logout(sessionId);

    assertEquals(204, response.getStatus());
    assertTrue(fixtureContext.sessionStore.findSession(sessionId).isEmpty());
    verify(fixtureContext.httpClient, never()).send(any(HttpRequest.class), any());
  }

  @Test
  void logoutShouldSkipRevocationWhenCredentialsAreBlank() throws Exception {
    when(fixtureContext.dashboardConfig.clientId()).thenReturn(Optional.of("   "));
    var blankIdSession =
        fixtureContext.sessionStore.createSession("testuser", "gho_secret", "https://a.co", "Test");
    assertEquals(204, resource.logout(blankIdSession).getStatus());

    when(fixtureContext.dashboardConfig.clientId()).thenReturn(Optional.of("test-client-id"));
    when(fixtureContext.dashboardConfig.clientSecret()).thenReturn(Optional.of("   "));
    var blankSecretSession =
        fixtureContext.sessionStore.createSession("testuser", "gho_secret", "https://a.co", "Test");
    assertEquals(204, resource.logout(blankSecretSession).getStatus());

    verify(fixtureContext.httpClient, never()).send(any(HttpRequest.class), any());
  }

  @Test
  void logoutShouldSkipRevocationWhenStoredTokenIsNullOrBlank() throws Exception {
    var blankTokenSession =
        fixtureContext.sessionStore.createSession("testuser", "   ", "https://a.co", "Test");
    var nullTokenSession =
        fixtureContext.sessionStore.createSession("testuser", null, "https://a.co", "Test");

    assertEquals(204, resource.logout(blankTokenSession).getStatus());
    assertEquals(204, resource.logout(nullTokenSession).getStatus());

    verify(fixtureContext.httpClient, never()).send(any(HttpRequest.class), any());
  }

  @Test
  void logoutShouldSucceedWhenRevocationReturnsUnexpectedStatus() throws Exception {
    when(fixtureContext.httpClient.send(any(HttpRequest.class), any())).thenReturn(httpResponse);
    when(httpResponse.statusCode()).thenReturn(422);
    var sessionId =
        fixtureContext.sessionStore.createSession("testuser", "gho_secret", "https://a.co", "Test");

    var response = resource.logout(sessionId);

    assertEquals(204, response.getStatus());
    assertTrue(fixtureContext.sessionStore.findSession(sessionId).isEmpty());
  }

  @Test
  void logoutShouldRestoreInterruptFlagWhenRevocationInterrupted() throws Exception {
    when(fixtureContext.httpClient.send(any(HttpRequest.class), any()))
        .thenThrow(new InterruptedException("interrupted"));
    var sessionId =
        fixtureContext.sessionStore.createSession("testuser", "gho_secret", "https://a.co", "Test");

    var response = resource.logout(sessionId);

    assertEquals(204, response.getStatus());
    assertTrue(fixtureContext.sessionStore.findSession(sessionId).isEmpty());
    // Thread.interrupted() also clears the flag so it does not leak into other tests
    assertTrue(Thread.interrupted());
  }

  @Test
  void callbackTokenExchangeShouldCarryTimeout() throws Exception {
    when(fixtureContext.httpClient.send(any(HttpRequest.class), any())).thenReturn(httpResponse);
    when(httpResponse.statusCode()).thenReturn(200);
    when(httpResponse.body())
        .thenReturn("{\"access_token\":\"gho_test123\"}")
        .thenReturn("{\"login\":\"testuser\",\"avatar_url\":\"https://a.co\",\"name\":\"Test\"}");

    resource.callback("valid-code", "test-state", "test-state");

    var captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(fixtureContext.httpClient, times(2)).send(captor.capture(), any());
    captor
        .getAllValues()
        .forEach(request -> assertEquals(Optional.of(Duration.ofSeconds(10)), request.timeout()));
  }
}
