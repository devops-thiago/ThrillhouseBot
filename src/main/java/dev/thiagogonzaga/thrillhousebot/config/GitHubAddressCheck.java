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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Refuses a configured GitHub address that is not {@code https} or that carries a user name or
 * password: the App JWT, installation tokens, the OAuth client secret and user tokens are all sent
 * to these addresses.
 *
 * <p>It reads the properties of the extension and of the {@code github-api} and {@code
 * github-graphql} REST client keys, so an address set there directly is checked like one set
 * through {@code GITHUB_URL}. An override written for a single client interface under its fully
 * qualified name is not read. A blank or unset property is skipped. A refusal names the setting and
 * never repeats the value, which can hold a password.
 */
final class GitHubAddressCheck {

  private static final String DERIVED = ", derived from GITHUB_URL when unset";

  /** In the order they are reported, so a refusal names the variable before what it feeds. */
  private static final List<Address> ADDRESSES =
      List.of(
          new Address("thrillhousebot.github.web-url", "GITHUB_URL", ""),
          new Address("thrillhousebot.github.api-url", "GITHUB_API_URL", DERIVED),
          new Address("thrillhousebot.github.graphql-url", "GITHUB_GRAPHQL_URL", DERIVED),
          new Address("thrillhousebot.dashboard.github.oauth-url", "GITHUB_OAUTH_URL", ""),
          new Address("quarkus.github-app.instance-endpoint"),
          new Address("quarkus.github-app.rest-api-endpoint"),
          new Address("quarkus.github-app.graphql-api-endpoint"),
          new Address("github-api/mp-rest/url"),
          new Address("github-api/mp-rest/uri"),
          new Address("quarkus.rest-client.github-api.url"),
          new Address("quarkus.rest-client.github-api.uri"),
          new Address("github-graphql/mp-rest/url"),
          new Address("github-graphql/mp-rest/uri"),
          new Address("quarkus.rest-client.github-graphql.url"),
          new Address("quarkus.rest-client.github-graphql.uri"));

  private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

  /**
   * A property holding a GitHub address.
   *
   * @param setting what an operator sets: the environment variable, or the property itself
   * @param origin the rest of the parenthesis naming the property, empty when there is none to name
   */
  private record Address(String property, String setting, String origin) {

    private Address(String property) {
      this(property, property, "");
    }

    private String problem(String found) {
      var named = setting.equals(property) ? "" : " (" + property + origin + ")";
      return setting
          + " must be an https address, because credentials are sent to it"
          + named
          + ": found "
          + found;
    }
  }

  private GitHubAddressCheck() {}

  /**
   * One problem for each distinct address that must not be sent credentials.
   *
   * @param property reads a configuration property
   * @param loopbackHttpAllowed accepts {@code http} on a loopback host, for the test stubs
   */
  static List<String> problems(
      Function<String, Optional<String>> property, boolean loopbackHttpAllowed) {
    var problems = new ArrayList<String>();
    var reported = new HashSet<String>();
    for (var address : ADDRESSES) {
      property
          .apply(address.property())
          .filter(value -> !value.isBlank())
          .filter(reported::add)
          .flatMap(value -> refusal(value, loopbackHttpAllowed))
          .ifPresent(found -> problems.add(address.problem(found)));
    }
    return problems;
  }

  /** What is wrong with {@code value}, or empty when credentials may be sent to it. */
  private static Optional<String> refusal(String value, boolean loopbackHttpAllowed) {
    URI uri;
    try {
      uri = new URI(value.strip());
    } catch (URISyntaxException _) {
      return Optional.of("text that is not a URL");
    }
    if (uri.getScheme() == null) {
      return Optional.of("no scheme");
    }
    var scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    var secure = scheme.equals("https");
    if (!secure && !scheme.equals("http")) {
      return Optional.of("a scheme other than http or https");
    }
    if (uri.getHost() == null) {
      return Optional.of("no valid host name");
    }
    var loopback =
        loopbackHttpAllowed && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
    if (!secure && !loopback) {
      return Optional.of("the scheme http");
    }
    return uri.getRawUserInfo() == null
        ? Optional.empty()
        : Optional.of("a user name or password in the address");
  }
}
