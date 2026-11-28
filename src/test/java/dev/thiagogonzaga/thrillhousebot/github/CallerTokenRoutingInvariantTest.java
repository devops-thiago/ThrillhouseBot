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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import jakarta.ws.rs.HeaderParam;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * A REST client method that takes the caller's token is one HTTP attempt, named {@code ...Once},
 * and callers use the default method beside it, which goes through {@link GitHubTokenRefresh} or
 * {@link GitHubWriteRetry}. A new method that sends a token without them is caught here.
 */
class CallerTokenRoutingInvariantTest {

  private static final Path MAIN = Path.of("src/main/java");

  /** Methods that take a token the bot cannot mint again, so there is nothing to repeat with. */
  private static final Set<String> NOT_AN_INSTALLATION_TOKEN =
      Set.of(
          // A dashboard user's own access token.
          "GitHubUserClient.listInstallations");

  @Test
  void everyRestClientMethodThatTakesATokenIsASingleAttemptBehindARoutedDefault()
      throws IOException, ClassNotFoundException {
    var clients = restClients();
    var unrouted = new TreeSet<String>();
    for (var client : clients) {
      for (var method : client.getDeclaredMethods()) {
        if (Modifier.isAbstract(method.getModifiers())
            && takesAToken(method)
            && !method.getName().endsWith("Once")) {
          unrouted.add(client.getSimpleName() + "." + method.getName());
        }
      }
    }

    assertFalse(clients.isEmpty(), "the scan found no REST client");
    assertEquals(new TreeSet<>(NOT_AN_INSTALLATION_TOKEN), unrouted);
  }

  private static List<Class<?>> restClients() throws IOException, ClassNotFoundException {
    List<Path> sources;
    try (var files = Files.walk(MAIN)) {
      sources = files.filter(path -> path.toString().endsWith(".java")).toList();
    }
    var clients = new java.util.ArrayList<Class<?>>();
    for (var file : sources) {
      if (Files.readString(file).contains("@RegisterRestClient(")) {
        var relative = MAIN.relativize(file).toString().replace('\\', '/');
        var name = relative.substring(0, relative.length() - ".java".length()).replace('/', '.');
        clients.add(Class.forName(name));
      }
    }
    return clients;
  }

  private static boolean takesAToken(Method method) {
    return Arrays.stream(method.getParameters())
        .map(parameter -> parameter.getAnnotation(HeaderParam.class))
        .anyMatch(header -> header != null && "Authorization".equals(header.value()));
  }
}
