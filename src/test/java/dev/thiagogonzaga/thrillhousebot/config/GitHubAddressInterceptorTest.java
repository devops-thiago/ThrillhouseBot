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

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class GitHubAddressInterceptorTest {

  private static final Path APPLICATION_PROPERTIES =
      Path.of("src", "main", "resources", "application.properties");

  private static final String WEB = "thrillhousebot.github.web-url";

  private static final List<String> REST_KEYS =
      List.of(
          "thrillhousebot.github.api-url",
          "github-api/mp-rest/url",
          "quarkus.github-app.instance-endpoint",
          "quarkus.github-app.rest-api-endpoint");

  private static final List<String> GRAPHQL_KEYS =
      List.of(
          "thrillhousebot.github.graphql-url",
          "github-graphql/mp-rest/url",
          "quarkus.github-app.graphql-api-endpoint");

  /** The shipped application.properties, with {@code variables} set as the environment would. */
  private static SmallRyeConfig configWith(Map<String, String> variables) {
    try {
      return new SmallRyeConfigBuilder()
          .addDefaultInterceptors()
          .addDiscoveredInterceptors()
          .withSources(
              new PropertiesConfigSource(APPLICATION_PROPERTIES.toUri().toURL(), 250),
              new PropertiesConfigSource(variables, "environment", 300))
          .build();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void assertAddresses(
      Map<String, String> variables, String web, String rest, String graphql) {
    var config = configWith(variables);

    assertEquals(web, config.getValue(WEB, String.class), WEB);
    for (var key : REST_KEYS) {
      assertEquals(rest, config.getValue(key, String.class), key);
    }
    for (var key : GRAPHQL_KEYS) {
      assertEquals(graphql, config.getValue(key, String.class), key);
    }
  }

  @Test
  void withNothingSetEveryAddressIsGitHubDotCom() {
    assertAddresses(
        Map.of(), "https://github.com", "https://api.github.com", "https://api.github.com/graphql");
    assertEquals(
        "",
        configWith(Map.of())
            .getOptionalValue("thrillhousebot.dashboard.github.oauth-url", String.class)
            .orElse(""));
  }

  @Test
  void blankVariablesCountAsUnset() {
    assertAddresses(
        Map.of("GITHUB_URL", "", "GITHUB_API_URL", "   ", "GITHUB_GRAPHQL_URL", ""),
        "https://github.com",
        "https://api.github.com",
        "https://api.github.com/graphql");
  }

  @ParameterizedTest
  @CsvSource({
    "https://github.com, https://github.com, https://api.github.com, https://api.github.com/graphql",
    "https://GitHub.com/, https://GitHub.com, https://api.github.com,"
        + " https://api.github.com/graphql",
    "https://github.com., https://github.com., https://api.github.com,"
        + " https://api.github.com/graphql",
    "https://octo.ghe.com, https://octo.ghe.com, https://api.octo.ghe.com,"
        + " https://api.octo.ghe.com/graphql",
    "https://Octo.GHE.com:8443/, https://Octo.GHE.com:8443, https://api.Octo.GHE.com:8443,"
        + " https://api.Octo.GHE.com:8443/graphql",
    "https://ghes.example.com, https://ghes.example.com, https://ghes.example.com/api/v3,"
        + " https://ghes.example.com/api/graphql",
    "'  https://ghes.example.com:8443//  ', https://ghes.example.com:8443,"
        + " https://ghes.example.com:8443/api/v3, https://ghes.example.com:8443/api/graphql",
    "https://notghe.com, https://notghe.com, https://notghe.com/api/v3,"
        + " https://notghe.com/api/graphql",
    "https://github.com.example.com, https://github.com.example.com,"
        + " https://github.com.example.com/api/v3, https://github.com.example.com/api/graphql"
  })
  void restAndGraphqlFollowTheWebAddress(String variable, String web, String rest, String graphql) {
    assertAddresses(Map.of("GITHUB_URL", variable), web, rest, graphql);
  }

  static Stream<Arguments> addressesTheStartupCheckRefuses() {
    return Stream.of(
        Arguments.of("ghes.example.com"),
        Arguments.of("//octo.ghe.com"),
        Arguments.of("https://ghes_1.example.com"),
        Arguments.of("https://git hub.example.com"),
        Arguments.of("http://ghes.example.com"));
  }

  @ParameterizedTest
  @MethodSource("addressesTheStartupCheckRefuses")
  void anAddressThatWillBeRefusedStillDerivesFromItselfAndNotFromGitHubDotCom(String variable) {
    assertAddresses(
        Map.of("GITHUB_URL", variable), variable, variable + "/api/v3", variable + "/api/graphql");
  }

  @ParameterizedTest
  @ValueSource(strings = {"https://octo.ghe.com", "https://ghes.example.com"})
  void withAnotherHostNoAddressNamesGitHubDotCom(String variable) {
    var config = configWith(Map.of("GITHUB_URL", variable));

    for (var key : Stream.concat(REST_KEYS.stream(), GRAPHQL_KEYS.stream()).toList()) {
      assertFalse(config.getValue(key, String.class).contains("github.com"), key);
    }
  }

  @Test
  void explicitApiAddressesWinAndAreTidied() {
    assertAddresses(
        Map.of(
            "GITHUB_URL",
            "https://ghes.example.com",
            "GITHUB_API_URL",
            " https://api.example.com/rest/ ",
            "GITHUB_GRAPHQL_URL",
            "https://api.example.com/graph/"),
        "https://ghes.example.com",
        "https://api.example.com/rest",
        "https://api.example.com/graph");
  }

  @Test
  void oneExplicitApiAddressLeavesTheOtherDerived() {
    assertAddresses(
        Map.of(
            "GITHUB_URL", "https://ghes.example.com", "GITHUB_API_URL", "https://api.example.com"),
        "https://ghes.example.com",
        "https://api.example.com",
        "https://ghes.example.com/api/graphql");
  }

  @Test
  void theWebAddressSetAsAPropertyDrivesTheDerivationToo() {
    assertAddresses(
        Map.of(WEB, "https://ghes.example.com/"),
        "https://ghes.example.com/",
        "https://ghes.example.com/api/v3",
        "https://ghes.example.com/api/graphql");
  }

  @Test
  void anExplicitOauthAddressIsLeftAsWritten() {
    var config =
        configWith(
            Map.of(
                "GITHUB_URL",
                "https://ghes.example.com",
                "GITHUB_OAUTH_URL",
                "https://sso.example.com/login/oauth"));

    assertEquals(
        "https://sso.example.com/login/oauth",
        config.getValue("thrillhousebot.dashboard.github.oauth-url", String.class));
  }

  @Test
  void withoutTheWebAddressPropertyTheApiAddressesAreGitHubDotCom() {
    var config =
        new SmallRyeConfigBuilder()
            .addDefaultInterceptors()
            .addDiscoveredInterceptors()
            .withSources(
                new PropertiesConfigSource(Map.of("api", "${GITHUB_API_URL}"), "test", 100))
            .build();

    assertEquals("https://api.github.com", config.getValue("api", String.class));
  }
}
