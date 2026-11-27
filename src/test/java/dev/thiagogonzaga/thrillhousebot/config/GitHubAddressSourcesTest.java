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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Keeps the next client from bypassing the configured GitHub address: the wiring test can only
 * exercise the clients that exist.
 */
class GitHubAddressSourcesTest {

  private static final Pattern REST_CLIENT = Pattern.compile("@RegisterRestClient(\\([^)]*\\))?");

  /** A quoted github.com address, other than the target of a Javadoc {@code <a href>} link. */
  private static final Pattern DOTCOM_LITERAL =
      Pattern.compile("(?<!href=)\"https?://(api\\.|uploads\\.)?github\\.com(?![\\w-]|\\.\\w)");

  /** Where github.com is the documented default, or a link to this project's own pages. */
  private static final Set<String> DEFAULT_HOLDERS =
      Set.of("GitHubAddressInterceptor.java", "GitHubUrls.java", "ThrillhouseConfig.java");

  private static Map<Path, String> mainSources() throws IOException {
    try (var files = Files.walk(Path.of("src", "main", "java"))) {
      return files
          .filter(file -> file.toString().endsWith(".java"))
          .collect(Collectors.toMap(file -> file, GitHubAddressSourcesTest::read));
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void everyRestClientTakesOneOfTheTwoConfiguredAddresses() throws IOException {
    var declarations =
        mainSources().values().stream()
            .flatMap(source -> REST_CLIENT.matcher(source).results())
            .map(match -> match.group(1))
            .toList();

    assertFalse(declarations.isEmpty(), "the scan found no REST client");
    assertEquals(
        List.of(),
        declarations.stream()
            .filter(
                arguments ->
                    !"(configKey = \"github-api\")".equals(arguments)
                        && !"(configKey = \"github-graphql\")".equals(arguments))
            .toList());
  }

  @Test
  void aJavadocLinkIsNotAnAddressTheCodeCalls() {
    assertTrue(DOTCOM_LITERAL.matcher("var url = \"https://api.github.com\";").find());
    assertFalse(
        DOTCOM_LITERAL.matcher("<a href=\"https://github.com/owner/repo/issues/1\">").find());
  }

  @Test
  void aHostThatOnlyStartsWithGitHubDotComIsAnotherHost() {
    assertFalse(DOTCOM_LITERAL.matcher("\"https://github.company.com\"").find());
    assertFalse(DOTCOM_LITERAL.matcher("\"https://github.com.example.com\"").find());
    assertTrue(DOTCOM_LITERAL.matcher("\"https://github.com\"").find());
    assertTrue(DOTCOM_LITERAL.matcher("\"https://github.com./owner\"").find());
  }

  @Test
  void noOtherSourceSpellsOutAGitHubDotComAddress() throws IOException {
    assertEquals(
        List.of(),
        mainSources().entrySet().stream()
            .filter(source -> DOTCOM_LITERAL.matcher(source.getValue()).find())
            .map(source -> source.getKey().getFileName().toString())
            .filter(name -> !DEFAULT_HOLDERS.contains(name))
            .toList());
  }
}
