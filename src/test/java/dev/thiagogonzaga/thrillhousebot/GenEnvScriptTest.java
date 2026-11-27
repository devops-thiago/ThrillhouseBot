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
package dev.thiagogonzaga.thrillhousebot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs {@code scripts/GenEnv.java} as the setup guide does, on a made-up manifest conversion
 * response, and reads the {@code .env} it writes.
 */
class GenEnvScriptTest {

  private static final String CONVERSION =
      """
      {"id": 4242, "slug": "my-bot", "client_id": "Iv1.abc", "client_secret": "shh",
       "webhook_secret": "hook", "pem": "-----BEGIN KEY-----\\nline\\n-----END KEY-----\\n"}
      """;

  @TempDir Path workDir;

  @Test
  void writesTheGitHubAddressTheAppWasRegisteredOn() throws Exception {
    var env = generate("--host", "bot.example.com", "--github-url", "https://github.example.com/");

    assertTrue(env.contains("\nGITHUB_URL=https://github.example.com\n"), env);
    assertTrue(env.contains("\nGITHUB_APP_ID=4242\n"), env);
    assertTrue(env.contains("\nDASHBOARD_URL=https://bot.example.com\n"), env);
  }

  @Test
  void writesNoGitHubAddressForAnAppOnGitHubDotCom() throws Exception {
    var env = generate("--host", "bot.example.com");

    assertFalse(env.contains("GITHUB_URL"), env);
    assertTrue(env.contains("\nGITHUB_APP_ID=4242\n"), env);
  }

  private String generate(String... args) throws IOException, InterruptedException {
    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add(Path.of("scripts", "GenEnv.java").toAbsolutePath().toString());
    command.addAll(List.of(args));
    var process =
        new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true).start();
    try (var stdin = process.getOutputStream()) {
      stdin.write(CONVERSION.getBytes(StandardCharsets.UTF_8));
    }
    var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), output);
    assertEquals(0, process.exitValue(), output);
    return Files.readString(workDir.resolve(".env"));
  }
}
