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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

  private static final String HOST = "--host";
  private static final String BOT_HOST = "bot.example.com";
  private static final String GITHUB_URL = "--github-url";
  private static final String NO_ENV = "no .env is written";
  private static final String GITHUB_URL_LINE = "\nGITHUB_URL=https://github.example.com\n";
  private static final String DASHBOARD_URL_LINE = "\nDASHBOARD_URL=https://bot.example.com\n";

  @TempDir Path workDir;

  @Test
  void writesTheGitHubAddressTheAppWasRegisteredOn() throws Exception {
    var env = generate(HOST, BOT_HOST, GITHUB_URL, "https://github.example.com/");

    assertTrue(env.contains(GITHUB_URL_LINE), env);
    assertTrue(env.contains("\nGITHUB_APP_ID=4242\n"), env);
    assertTrue(env.contains(DASHBOARD_URL_LINE), env);
  }

  @Test
  void writesNoGitHubAddressForAnAppOnGitHubDotCom() throws Exception {
    var env = generate(HOST, BOT_HOST);

    assertFalse(env.contains("GITHUB_URL"), env);
    assertTrue(env.contains("\nGITHUB_APP_ID=4242\n"), env);
  }

  // The bot refuses to start on a GITHUB_URL that is not an https address, so the script does
  // not write one into the .env.
  @ParameterizedTest
  @CsvSource({
    "http://github.example.com, the scheme http",
    "github.example.com, no scheme",
    "ftp://github.example.com, a scheme other than https",
    "https:///organizations, no valid host name",
    "https://user:secret@github.example.com, a user name or password in the address",
    "https://github example.com, text that is not a URL",
    "///, no scheme"
  })
  void refusesAGitHubAddressTheBotWouldNotStartOn(String address, String found) throws Exception {
    var run = run(HOST, BOT_HOST, GITHUB_URL, address);

    assertEquals(1, run.exitCode(), run.output());
    assertTrue(run.output().contains("--github-url must be an https address"), run.output());
    assertTrue(run.output().contains("found " + found), run.output());
    assertFalse(run.output().contains("secret"), run.output());
    assertFalse(Files.exists(workDir.resolve(".env")), NO_ENV);
  }

  @Test
  void readsAnOptionGivenLastOrWithAnEqualsSign() throws Exception {
    var env = generate("--github-url=https://github.example.com/", HOST, BOT_HOST);

    assertTrue(env.contains(GITHUB_URL_LINE), env);
    assertTrue(env.contains(DASHBOARD_URL_LINE), env);

    env = generate("--host=bot.example.com", GITHUB_URL, " https://github.example.com ");

    assertTrue(env.contains(GITHUB_URL_LINE), env);
    assertTrue(env.contains(DASHBOARD_URL_LINE), env);
  }

  // #1526 review: an option the loop did not bind was dropped without a word, so a forgotten or
  // mistyped --github-url wrote a .env that has the bot call github.com and reported success.
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "--host bot.example.com --github-url | --github-url needs a value, like --github-url https://github.example.com",
        "--github-url --host bot.example.com | --github-url needs a value, like --github-url https://github.example.com",
        "--host bot.example.com --github-url= | --github-url needs a value, like --github-url https://github.example.com",
        "--github-url https://github.example.com --host | --host needs a value, like --host thrillhousebot.example.com",
        "--host bot.example.com --github-ulr https://user:secret@github.example.com | Unknown option --github-ulr: the options are --host and --github-url",
        "--host bot.example.com --githuburl=https://user:secret@github.example.com | Unknown option --githuburl: the options are --host and --github-url",
        "--host bot.example.com https://user:secret@github.example.com | Unexpected argument: the options are --host and --github-url",
        "--host bot.example.com --https://user:secret@github.example.com | Unexpected argument: the options are --host and --github-url"
      })
  void refusesArgumentsItCannotRead(String arguments, String message) throws Exception {
    var run = run(arguments.split(" "));

    assertEquals(1, run.exitCode(), run.output());
    assertTrue(run.output().contains(message + ". No .env was written."), run.output());
    assertFalse(run.output().contains("secret"), run.output());
    assertFalse(Files.exists(workDir.resolve(".env")), NO_ENV);
  }

  @Test
  void refusesABlankGitHubAddress() throws Exception {
    var run = run(HOST, BOT_HOST, GITHUB_URL, " ");

    assertEquals(1, run.exitCode(), run.output());
    assertTrue(run.output().contains("--github-url needs a value"), run.output());
    assertFalse(Files.exists(workDir.resolve(".env")), NO_ENV);
  }

  private String generate(String... args) throws IOException, InterruptedException {
    var run = run(args);
    assertEquals(0, run.exitCode(), run.output());
    return Files.readString(workDir.resolve(".env"));
  }

  private record Run(int exitCode, String output) {}

  private Run run(String... args) throws IOException, InterruptedException {
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
    return new Run(process.exitValue(), output);
  }
}
