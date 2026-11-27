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

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class GitHubAddressCheckTest {

  private static final String REST = "github-api/mp-rest/url";
  private static final String REFUSAL =
      " must be an https address, because credentials are sent to it";

  private static List<String> problems(Map<String, String> set, boolean loopbackHttpAllowed) {
    return GitHubAddressCheck.problems(
        name -> Optional.ofNullable(set.get(name)), loopbackHttpAllowed);
  }

  private static List<String> problems(String value, boolean loopbackHttpAllowed) {
    return problems(Map.of(REST, value), loopbackHttpAllowed);
  }

  /** What the shipped application.properties makes of {@code variables}. */
  private static List<String> problemsWith(Map<String, String> variables) throws IOException {
    var shipped = Path.of("src", "main", "resources", "application.properties");
    var config =
        new SmallRyeConfigBuilder()
            .addDefaultInterceptors()
            .addDiscoveredInterceptors()
            .withSources(
                new PropertiesConfigSource(shipped.toUri().toURL(), 250),
                new PropertiesConfigSource(variables, "environment", 300))
            .build();
    return GitHubAddressCheck.problems(name -> config.getOptionalValue(name, String.class), false);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://github.com",
        "HTTPS://ghes.example.com:8443/api/v3",
        "https://localhost/api/v3",
        "  https://ghes.example.com  ",
        "",
        "   "
      })
  void httpsAndBlankPass(String value) {
    assertEquals(List.of(), problems(value, false));
  }

  @ParameterizedTest
  @CsvSource({
    "http://ghes.example.com, the scheme http",
    "HTTP://ghes.example.com, the scheme http",
    "ghes.example.com, no scheme",
    "//ghes.example.com/api/v3, no scheme",
    "ghes.example.com:8443, a scheme other than http or https",
    "ftp://ghes.example.com, a scheme other than http or https",
    "https:///api/v3, no valid host name",
    "https://ghes_internal/api/v3, no valid host name",
    "https://git hub.example.com, text that is not a URL"
  })
  void anythingElseIsRefusedWithTheReason(String value, String found) {
    assertEquals(List.of(REST + REFUSAL + ": found " + found), problems(value, false));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://localhost:8080",
        "http://LocalHost",
        "http://127.0.0.1:1/api/v3",
        "http://[::1]:9999"
      })
  void httpOnALoopbackHostPassesOnlyWhereTheTestStubsAreAllowed(String value) {
    assertEquals(List.of(), problems(value, true));
    assertEquals(List.of(REST + REFUSAL + ": found the scheme http"), problems(value, false));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://ghes.example.com",
        "http://localhost.example.com",
        "http://127.0.0.2",
        "http://127.0.0.1.example.com",
        "http://localhost@ghes.example.com",
        "http://10.0.0.1",
        "http://[::2]"
      })
  void theExceptionCoversNoOtherHost(String value) {
    assertEquals(List.of(REST + REFUSAL + ": found the scheme http"), problems(value, true));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "thrillhousebot.github.web-url | GITHUB_URL | (thrillhousebot.github.web-url)",
        "thrillhousebot.github.api-url | GITHUB_API_URL"
            + " | (thrillhousebot.github.api-url, derived from GITHUB_URL when unset)",
        "thrillhousebot.github.graphql-url | GITHUB_GRAPHQL_URL"
            + " | (thrillhousebot.github.graphql-url, derived from GITHUB_URL when unset)",
        "thrillhousebot.dashboard.github.oauth-url | GITHUB_OAUTH_URL"
            + " | (thrillhousebot.dashboard.github.oauth-url)"
      })
  void aRefusalNamesTheVariableAndItsProperty(String property, String variable, String named) {
    assertEquals(
        List.of(variable + REFUSAL + " " + named + ": found the scheme http"),
        problems(Map.of(property, "http://ghes.example.com"), false));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "quarkus.github-app.instance-endpoint",
        "quarkus.github-app.rest-api-endpoint",
        "quarkus.github-app.graphql-api-endpoint",
        "github-api/mp-rest/url",
        "github-api/mp-rest/uri",
        "quarkus.rest-client.github-api.url",
        "quarkus.rest-client.github-api.uri",
        "github-graphql/mp-rest/url",
        "github-graphql/mp-rest/uri",
        "quarkus.rest-client.github-graphql.url",
        "quarkus.rest-client.github-graphql.uri"
      })
  void anAddressSetDirectlyOnAClientIsCheckedToo(String property) {
    assertEquals(
        List.of(property + REFUSAL + ": found the scheme http"),
        problems(Map.of(property, "http://ghes.example.com"), false));
  }

  @Test
  void aValueSharedBySeveralPropertiesIsReportedOnce() {
    var shared = "http://ghes.example.com/api/v3";

    var found =
        problems(
            Map.of(
                "thrillhousebot.github.api-url",
                shared,
                REST,
                shared,
                "quarkus.github-app.rest-api-endpoint",
                shared),
            false);

    assertEquals(1, found.size(), found.toString());
    assertTrue(found.getFirst().startsWith("GITHUB_API_URL "), found.getFirst());
  }

  @Test
  void aRefusalNeverRepeatsTheValue() {
    var found = problems("http://bot:s3cret@ghes.example.com", false).getFirst();

    assertFalse(found.contains("s3cret"), found);
    assertFalse(found.contains("ghes.example.com"), found);
  }

  @Test
  void theShippedPropertiesPassUnsetAndForAnotherHttpsHost() throws IOException {
    assertEquals(List.of(), problemsWith(Map.of()));
    assertEquals(List.of(), problemsWith(Map.of("GITHUB_URL", "https://ghes.example.com")));
    assertEquals(List.of(), problemsWith(Map.of("GITHUB_URL", "https://octo.ghe.com")));
  }

  @ParameterizedTest
  @CsvSource({
    "GITHUB_URL, http://ghes.example.com, 3",
    "GITHUB_URL, ghes.example.com, 3",
    "GITHUB_API_URL, http://ghes.example.com/api/v3, 1",
    "GITHUB_GRAPHQL_URL, http://ghes.example.com/api/graphql, 1",
    "GITHUB_OAUTH_URL, http://ghes.example.com/login/oauth, 1"
  })
  void theShippedPropertiesRefuseAVariableThatIsNotHttps(String variable, String value, int count)
      throws IOException {
    var found = problemsWith(Map.of(variable, value));

    assertEquals(count, found.size(), found.toString());
    assertTrue(found.getFirst().startsWith(variable + " "), found.getFirst());
  }
}
