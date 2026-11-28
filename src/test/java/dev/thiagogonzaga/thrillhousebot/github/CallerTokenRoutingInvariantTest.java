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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import jakarta.ws.rs.HeaderParam;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A REST client method that takes the caller's token is one HTTP attempt, named {@code ...Once},
 * and callers use the default method beside it, which goes through {@link GitHubTokenRefresh} or
 * {@link GitHubWriteRetry}. Three things are checked for every such method:
 *
 * <ul>
 *   <li>its name ends in {@code Once};
 *   <li>the interface has a default method of the same name without {@code Once}, and every call of
 *       the single attempt in the interface passes {@code credential}, the name the routing lambdas
 *       give the token they hand out;
 *   <li>no other class under {@code src/main/java} calls the single attempt, except the callers
 *       listed in {@link #ROUTED_ELSEWHERE}.
 * </ul>
 *
 * The calls are found by reading the sources, so a call that hides the method name (a method
 * reference, reflection) or a lambda that names another value {@code credential} is not caught.
 */
class CallerTokenRoutingInvariantTest {

  private static final Path MAIN = Path.of("src/main/java");
  private static final String ONCE = "Once";

  /** Methods that take a token the bot cannot mint again, so there is nothing to repeat with. */
  private static final Set<String> NOT_AN_INSTALLATION_TOKEN =
      Set.of(
          // A dashboard user's own access token.
          "GitHubUserClient.listInstallations");

  /** Callers outside the interface that route a single attempt themselves. */
  private static final Set<String> ROUTED_ELSEWHERE =
      Set.of(
          // Creates a pending review thread inside its own GitHubWriteRetry call, which also
          // records a write that was lost.
          "ReviewThreadService.java calls GitHubGraphQLClient.executeOnce");

  @Test
  void everyRestClientMethodThatTakesATokenIsNamedAsASingleAttempt()
      throws IOException, ClassNotFoundException {
    var clients = restClients();
    var unrouted = new TreeSet<String>();
    for (var client : clients.values()) {
      for (var method : tokenMethods(client)) {
        if (!method.getName().endsWith(ONCE)) {
          unrouted.add(name(method));
        }
      }
    }

    assertFalse(clients.isEmpty(), "the scan found no REST client");
    assertEquals(new TreeSet<>(NOT_AN_INSTALLATION_TOKEN), unrouted);
  }

  @Test
  void everySingleAttemptIsCalledOnlyByTheDefaultMethodBesideIt()
      throws IOException, ClassNotFoundException {
    var sources = mainSources();
    var problems = new TreeSet<String>();
    var routedElsewhere = new TreeSet<String>();
    int checked = 0;
    for (var entry : restClients().entrySet()) {
      var client = entry.getValue();
      for (var method : tokenMethods(client)) {
        var attempt = method.getName();
        if (!attempt.endsWith(ONCE)) {
          continue;
        }
        checked++;
        var routed = attempt.substring(0, attempt.length() - ONCE.length());
        if (Arrays.stream(client.getDeclaredMethods())
            .noneMatch(other -> other.isDefault() && other.getName().equals(routed))) {
          problems.add(name(method) + " has no default method " + routed + " beside it");
        }
        var own = sources.get(entry.getKey());
        int mentions = count(own, "\\b" + attempt + "\\(");
        int routedCalls = count(own, "\\b" + attempt + "\\(\\s*credential\\b");
        if (routedCalls == 0) {
          problems.add(name(method) + " is not called with a routed credential by its interface");
        }
        // The one mention that is not a routed call is the declaration.
        if (mentions - routedCalls != 1) {
          problems.add(name(method) + " is called in its interface without a routed credential");
        }
        for (var source : sources.entrySet()) {
          if (!source.getKey().equals(entry.getKey())
              && count(source.getValue(), "\\." + attempt + "\\(") > 0) {
            routedElsewhere.add(source.getKey().getFileName() + " calls " + name(method));
          }
        }
      }
    }

    assertNotEquals(0, checked, "the scan found no single attempt");
    assertEquals(new TreeSet<String>(), problems);
    assertEquals(new TreeSet<>(ROUTED_ELSEWHERE), routedElsewhere);
  }

  private static String name(Method method) {
    return method.getDeclaringClass().getSimpleName() + "." + method.getName();
  }

  private static int count(String source, String regex) {
    return (int) Pattern.compile(regex).matcher(source).results().count();
  }

  /** The abstract methods of {@code client} that take the {@code Authorization} header. */
  private static List<Method> tokenMethods(Class<?> client) {
    return Arrays.stream(client.getDeclaredMethods())
        .filter(method -> Modifier.isAbstract(method.getModifiers()) && takesAToken(method))
        .toList();
  }

  private static Map<Path, String> mainSources() throws IOException {
    List<Path> files;
    try (var walk = Files.walk(MAIN)) {
      files = walk.filter(path -> path.toString().endsWith(".java")).toList();
    }
    var sources = new TreeMap<Path, String>();
    for (var file : files) {
      sources.put(file, Files.readString(file));
    }
    return sources;
  }

  /** The REST client interfaces, by source file. */
  private static Map<Path, Class<?>> restClients() throws IOException, ClassNotFoundException {
    var clients = new TreeMap<Path, Class<?>>();
    for (var source : mainSources().entrySet()) {
      if (source.getValue().contains("@RegisterRestClient(")) {
        var relative = MAIN.relativize(source.getKey()).toString().replace('\\', '/');
        var name = relative.substring(0, relative.length() - ".java".length()).replace('/', '.');
        clients.put(source.getKey(), Class.forName(name));
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
