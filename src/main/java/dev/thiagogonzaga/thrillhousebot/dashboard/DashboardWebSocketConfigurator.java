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

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import java.util.List;
import java.util.Map;

/** Copies the HTTP Cookie header into WebSocket session user properties during the handshake. */
@RegisterForReflection
public class DashboardWebSocketConfigurator extends ServerEndpointConfig.Configurator {

  static final String COOKIE_USER_PROPERTY = "Cookie";

  @Override
  public void modifyHandshake(
      ServerEndpointConfig config, HandshakeRequest request, HandshakeResponse response) {
    Map<String, List<String>> headers = request.getHeaders();
    for (String headerName : List.of(COOKIE_USER_PROPERTY, "cookie")) {
      var values = headers.get(headerName);
      if (values != null && !values.isEmpty()) {
        config.getUserProperties().put(COOKIE_USER_PROPERTY, values.getFirst());
        return;
      }
    }
  }
}
