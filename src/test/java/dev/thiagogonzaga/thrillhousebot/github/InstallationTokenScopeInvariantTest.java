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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Work on one repository asks for a token that reaches only that repository. The installation-wide
 * token is for the callers listed here, each of which reads something about the installation
 * itself; a new caller that needs it is added with the reason.
 */
class InstallationTokenScopeInvariantTest {

  private static final Path MAIN = Path.of("src/main/java");

  /** Callers that may ask for a token reaching every repository of the installation. */
  private static final Set<String> INSTALLATION_WIDE =
      Set.of(
          // Lists the installation's repositories; a repository token would list only its own.
          "dev/thiagogonzaga/thrillhousebot/dashboard/ScopeBackfill.java");

  @Test
  void onlyTheListedCallersAskForATokenThatReachesTheWholeInstallation() throws IOException {
    var callers = new TreeSet<String>();
    int scoped = 0;
    try (var files = Files.walk(MAIN)) {
      for (var file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        var relative = MAIN.relativize(file).toString().replace('\\', '/');
        if (relative.endsWith("github/GitHubAuthClient.java")) {
          continue;
        }
        var source = Files.readString(file);
        for (var call : new String[] {".getAuthHeader(", ".getInstallationToken("}) {
          for (int at = source.indexOf(call); at >= 0; at = source.indexOf(call, at + 1)) {
            if (call.equals(".getAuthHeader(") && arguments(source, at + call.length()) == 2) {
              scoped++;
            } else {
              callers.add(relative);
            }
          }
        }
      }
    }

    assertEquals(new TreeSet<>(INSTALLATION_WIDE), callers);
    assertFalse(scoped == 0, "no repository-scoped caller was found; the scan reads nothing");
  }

  /** How many arguments the call whose list opens at {@code from} passes. */
  private static int arguments(String source, int from) {
    int depth = 0;
    int commas = 0;
    boolean any = false;
    for (int i = from; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == ')' && depth == 0) {
        break;
      }
      any |= !Character.isWhitespace(c);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == ',' && depth == 0) {
        commas++;
      }
    }
    return any ? commas + 1 : 0;
  }
}
