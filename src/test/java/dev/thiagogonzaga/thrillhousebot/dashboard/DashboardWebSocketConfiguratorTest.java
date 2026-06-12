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

import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DashboardWebSocketConfiguratorTest {

  private final DashboardWebSocketConfigurator configurator = new DashboardWebSocketConfigurator();

  @Test
  void shouldCopyCookieHeaderIntoUserProperties() {
    var config = Mockito.mock(ServerEndpointConfig.class);
    var request = Mockito.mock(HandshakeRequest.class);
    var response = Mockito.mock(HandshakeResponse.class);
    var userProperties = new java.util.HashMap<String, Object>();
    Mockito.when(config.getUserProperties()).thenReturn(userProperties);
    Mockito.when(request.getHeaders())
        .thenReturn(Map.of("Cookie", List.of("thrillhouse_session=abc123; path=/")));

    configurator.modifyHandshake(config, request, response);

    assertEquals("thrillhouse_session=abc123; path=/", userProperties.get("Cookie"));
  }

  @Test
  void shouldAcceptLowercaseCookieHeader() {
    var config = Mockito.mock(ServerEndpointConfig.class);
    var request = Mockito.mock(HandshakeRequest.class);
    var response = Mockito.mock(HandshakeResponse.class);
    var userProperties = new java.util.HashMap<String, Object>();
    Mockito.when(config.getUserProperties()).thenReturn(userProperties);
    Mockito.when(request.getHeaders())
        .thenReturn(Map.of("cookie", List.of("thrillhouse_session=token")));

    configurator.modifyHandshake(config, request, response);

    assertEquals("thrillhouse_session=token", userProperties.get("Cookie"));
  }

  @Test
  void shouldLeaveUserPropertiesEmptyWhenNoCookieHeader() {
    var config = Mockito.mock(ServerEndpointConfig.class);
    var request = Mockito.mock(HandshakeRequest.class);
    var response = Mockito.mock(HandshakeResponse.class);
    var userProperties = new HashMap<String, Object>();
    Mockito.when(config.getUserProperties()).thenReturn(userProperties);
    Mockito.when(request.getHeaders()).thenReturn(Map.of());

    configurator.modifyHandshake(config, request, response);

    assertTrue(userProperties.isEmpty());
  }

  @Test
  void shouldSkipEmptyCookieHeaderAndUseLowercaseFallback() {
    var config = Mockito.mock(ServerEndpointConfig.class);
    var request = Mockito.mock(HandshakeRequest.class);
    var response = Mockito.mock(HandshakeResponse.class);
    var userProperties = new HashMap<String, Object>();
    var headers = new HashMap<String, List<String>>();
    headers.put("Cookie", List.of());
    headers.put("cookie", List.of("thrillhouse_session=fallback-token"));
    Mockito.when(config.getUserProperties()).thenReturn(userProperties);
    Mockito.when(request.getHeaders()).thenReturn(headers);

    configurator.modifyHandshake(config, request, response);

    assertEquals("thrillhouse_session=fallback-token", userProperties.get("Cookie"));
  }

  @Test
  void shouldIgnoreNullCookieHeaderValues() {
    var config = Mockito.mock(ServerEndpointConfig.class);
    var request = Mockito.mock(HandshakeRequest.class);
    var response = Mockito.mock(HandshakeResponse.class);
    var userProperties = new HashMap<String, Object>();
    var headers = new HashMap<String, List<String>>();
    headers.put("Cookie", null);
    headers.put("cookie", List.of("thrillhouse_session=from-lowercase"));
    Mockito.when(config.getUserProperties()).thenReturn(userProperties);
    Mockito.when(request.getHeaders()).thenReturn(headers);

    configurator.modifyHandshake(config, request, response);

    assertEquals("thrillhouse_session=from-lowercase", userProperties.get("Cookie"));
  }
}
