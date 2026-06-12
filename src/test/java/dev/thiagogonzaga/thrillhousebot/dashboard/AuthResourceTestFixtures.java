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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

/** Shared mock wiring and {@link AuthResource} construction for dashboard auth tests. */
final class AuthResourceTestFixtures {

  private AuthResourceTestFixtures() {}

  static final class Context {
    final ThrillhouseConfig config = mock(ThrillhouseConfig.class);
    final ThrillhouseConfig.DashboardConfig dashboardConfig =
        mock(ThrillhouseConfig.DashboardConfig.class);
    final DashboardAccessChecker accessChecker = mock(DashboardAccessChecker.class);
    final DashboardSessionStore sessionStore = new DashboardSessionStore();
    final HttpClient httpClient = mock(HttpClient.class);
    final ObjectMapper objectMapper = new ObjectMapper();

    AuthResource build() {
      return new AuthResource(config, objectMapper, accessChecker, sessionStore, httpClient);
    }

    Context withClientId(String clientId) {
      doReturn(Optional.of(clientId)).when(dashboardConfig).clientId();
      return this;
    }
  }

  static Context newContext() {
    var context = new Context();
    wireDefaultOAuth(context.config, context.dashboardConfig);
    wirePermissiveAccess(context.accessChecker);
    return context;
  }

  static void wireDefaultOAuth(
      ThrillhouseConfig config, ThrillhouseConfig.DashboardConfig dashboardConfig) {
    doReturn(dashboardConfig).when(config).dashboard();
    when(dashboardConfig.clientId()).thenReturn(Optional.of("test-client-id"));
    when(dashboardConfig.clientSecret()).thenReturn(Optional.of("test-secret"));
    when(dashboardConfig.redirectUri()).thenReturn("http://localhost:8080/api/auth/callback");
    when(dashboardConfig.oauthUrl()).thenReturn("https://github.com/login/oauth");
    when(config.github()).thenReturn(mock(ThrillhouseConfig.GitHubConfig.class));
    when(config.review()).thenReturn(mock(ThrillhouseConfig.ReviewConfig.class));
    when(config.ai()).thenReturn(mock(ThrillhouseConfig.AiPricingConfig.class));
    when(config.httpRequestTimeout()).thenReturn(Duration.ofSeconds(10));
  }

  static void wirePermissiveAccess(DashboardAccessChecker accessChecker) {
    when(accessChecker.hasAccess(anyString())).thenReturn(true);
    when(accessChecker.isAccessControlEnabled()).thenReturn(false);
  }
}
