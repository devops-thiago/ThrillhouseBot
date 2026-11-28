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
package dev.thiagogonzaga.thrillhousebot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import java.io.IOException;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The start-up validator runs before every other start-up observer, so none of them calls GitHub
 * before the addresses were checked. CDI runs observers in ascending priority, and one without a
 * priority gets the default.
 */
class StartupObserverOrderTest {

  private static final Path MAIN = Path.of("src/main/java");

  /** The priority CDI gives an observer that declares none. */
  private static final int DEFAULT_PRIORITY = Interceptor.Priority.APPLICATION + 500;

  @Test
  void theValidatorRunsBeforeEveryOtherStartupObserver()
      throws IOException, ClassNotFoundException {
    var priorities = startupObserverPriorities();
    var validator = priorities.remove(StartupConfigValidator.class.getName());

    assertNotNull(validator, "the scan did not find the validator's observer");
    assertFalse(priorities.isEmpty(), "the scan found no other start-up observer");
    assertEquals(
        Map.of(),
        priorities.entrySet().stream()
            .filter(other -> other.getValue() <= validator)
            .collect(TreeMap::new, (map, e) -> map.put(e.getKey(), e.getValue()), Map::putAll),
        "start-up observers that run no later than the validator, whose priority is " + validator);
  }

  /** The priority of each start-up observer under {@code src/main/java}, by class name. */
  private static Map<String, Integer> startupObserverPriorities()
      throws IOException, ClassNotFoundException {
    List<Path> sources;
    try (var files = Files.walk(MAIN)) {
      sources = files.filter(path -> path.toString().endsWith(".java")).toList();
    }
    var priorities = new TreeMap<String, Integer>();
    for (var file : sources) {
      if (!Files.readString(file).contains("StartupEvent")) {
        continue;
      }
      var relative = MAIN.relativize(file).toString().replace('\\', '/');
      var name = relative.substring(0, relative.length() - ".java".length()).replace('/', '.');
      for (var method : Class.forName(name).getDeclaredMethods()) {
        for (var parameter : method.getParameters()) {
          if (parameter.getType() == StartupEvent.class
              && parameter.isAnnotationPresent(Observes.class)) {
            priorities.put(name, priority(parameter));
          }
        }
      }
    }
    return priorities;
  }

  private static int priority(Parameter observed) {
    var declared = observed.getAnnotation(Priority.class);
    return declared == null ? DEFAULT_PRIORITY : declared.value();
  }
}
