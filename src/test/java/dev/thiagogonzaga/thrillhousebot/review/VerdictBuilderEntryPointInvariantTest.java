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
package dev.thiagogonzaga.thrillhousebot.review;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * A review that posts builds its verdict from the loaded context, which says whether the stored
 * earlier rounds could be read. {@code VerdictBuilder.buildResult} without a context cannot know
 * that and assumes they could, so it stays an entry for tests: production code that called it could
 * approve a round the unreadable-rounds hold exists to stop.
 */
class VerdictBuilderEntryPointInvariantTest {

  private static final Path MAIN = Path.of("src/main/java");
  private static final String VERDICT_BUILDER =
      "dev/thiagogonzaga/thrillhousebot/review/VerdictBuilder.java";
  private static final String CALL = "buildResult(";

  @Test
  void noProductionCodeBuildsAVerdictWithoutTheLoadedContext() throws IOException {
    var callers = new TreeSet<String>();
    List<Path> sources;
    try (var files = Files.walk(MAIN)) {
      sources = files.filter(path -> path.toString().endsWith(".java")).toList();
    }
    for (var file : sources) {
      if (Files.readString(file).contains(CALL)) {
        callers.add(MAIN.relativize(file).toString().replace('\\', '/'));
      }
    }

    assertEquals(new TreeSet<>(List.of(VERDICT_BUILDER)), callers);
  }

  /**
   * Inside the builder, the context-taking entry passes the context's own reading, and the only
   * call that passes a constant is the context-free entry handing over to the shared rules.
   */
  @Test
  void theBuilderPassesTheContextsReadingOfTheStoredRounds() throws IOException {
    var source = Files.readString(MAIN.resolve(VERDICT_BUILDER));
    var lastArguments = new ArrayList<String>();
    var call = "return " + CALL;
    for (int at = source.indexOf(call); at >= 0; at = source.indexOf(call, at + 1)) {
      int end = source.indexOf(");", at);
      lastArguments.add(source.substring(source.lastIndexOf(',', end) + 1, end).strip());
    }

    assertEquals(List.of("ctx.priorRoundsUnreadable()", "false"), lastArguments);
  }
}
