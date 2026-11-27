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

import dev.thiagogonzaga.thrillhousebot.github.GitHubUrls;
import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.Priorities;
import jakarta.annotation.Priority;
import java.io.Serial;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Derives the GitHub REST and GraphQL addresses from {@code GITHUB_URL}, so one setting moves every
 * API client to another GitHub.
 *
 * <p>{@code application.properties} builds every endpoint (the bot's REST and GraphQL clients, the
 * GitHub App extension, dashboard sign-in) from {@code GITHUB_URL}, {@code GITHUB_API_URL} and
 * {@code GITHUB_GRAPHQL_URL}. A property expression cannot choose a path from a host name, so an
 * unset or blank {@code GITHUB_API_URL} or {@code GITHUB_GRAPHQL_URL} is answered here. A value
 * that is set wins. All three are read without surrounding whitespace or trailing slashes, and a
 * blank {@code GITHUB_URL} counts as unset.
 *
 * <table>
 * <caption>Derived addresses</caption>
 * <tr><th>Host of {@code GITHUB_URL}</th><th>REST</th><th>GraphQL</th></tr>
 * <tr><td>{@code github.com}</td><td>{@code https://api.github.com}</td>
 *     <td>{@code https://api.github.com/graphql}</td></tr>
 * <tr><td>{@code SUB.ghe.com}</td><td>{@code https://api.SUB.ghe.com}</td>
 *     <td>{@code https://api.SUB.ghe.com/graphql}</td></tr>
 * <tr><td>any other (GitHub Enterprise Server)</td><td>{@code <GITHUB_URL>/api/v3}</td>
 *     <td>{@code <GITHUB_URL>/api/graphql}</td></tr>
 * </table>
 *
 * <p>The priority places this interceptor inside SmallRye's expression interceptor, which resolves
 * a {@code ${...}} reference only through the interceptors below it.
 */
@Priority(Priorities.LIBRARY + 270)
public class GitHubAddressInterceptor implements ConfigSourceInterceptor {

  private static final String WEB_URL_VARIABLE = "GITHUB_URL";
  private static final String API_URL_VARIABLE = "GITHUB_API_URL";
  private static final String GRAPHQL_URL_VARIABLE = "GITHUB_GRAPHQL_URL";
  private static final String WEB_URL_PROPERTY = "thrillhousebot.github.web-url";

  private static final String DOTCOM_HOST = "github.com";
  private static final String DOTCOM_API_URL = "https://api.github.com";
  private static final String GHE_COM_SUFFIX = ".ghe.com";

  @Serial private static final long serialVersionUID = 1L;

  @Override
  public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
    return switch (name) {
      case WEB_URL_VARIABLE -> configured(context, name);
      case API_URL_VARIABLE ->
          configuredOrDerived(context, name, GitHubAddressInterceptor::restApiUrl);
      case GRAPHQL_URL_VARIABLE ->
          configuredOrDerived(context, name, GitHubAddressInterceptor::graphqlUrl);
      default -> context.proceed(name);
    };
  }

  /** The value of {@code name} tidied up, or {@code null} when it is unset or blank. */
  private static ConfigValue configured(ConfigSourceInterceptorContext context, String name) {
    var value = context.proceed(name);
    return text(value).map(found -> value.withValue(tidy(found))).orElse(null);
  }

  private static ConfigValue configuredOrDerived(
      ConfigSourceInterceptorContext context, String name, UnaryOperator<String> derivation) {
    var configured = configured(context, name);
    if (configured != null) {
      return configured;
    }
    var webUrl =
        text(context.restart(WEB_URL_PROPERTY))
            .map(GitHubAddressInterceptor::tidy)
            .orElse(GitHubUrls.DEFAULT_WEB_URL);
    var derived = derivation.apply(webUrl);
    return ConfigValue.builder()
        .withName(name)
        .withValue(derived)
        .withRawValue(derived)
        .withConfigSourceName(GitHubAddressInterceptor.class.getSimpleName())
        .build();
  }

  /** The text of {@code value}, empty when there is none or it is blank. */
  private static Optional<String> text(ConfigValue value) {
    return Optional.ofNullable(value)
        .map(ConfigValue::getValue)
        .filter(Predicate.not(String::isBlank));
  }

  private static String tidy(String url) {
    return GitHubUrls.withoutTrailingSlash(url.strip());
  }

  private static String restApiUrl(String webUrl) {
    var web = parse(webUrl);
    var host = host(web);
    if (host.equals(DOTCOM_HOST)) {
      return DOTCOM_API_URL;
    }
    return host.endsWith(GHE_COM_SUFFIX) ? apiSubdomain(web) : webUrl + "/api/v3";
  }

  private static String graphqlUrl(String webUrl) {
    var web = parse(webUrl);
    var host = host(web);
    if (host.equals(DOTCOM_HOST)) {
      return DOTCOM_API_URL + "/graphql";
    }
    return host.endsWith(GHE_COM_SUFFIX) ? apiSubdomain(web) + "/graphql" : webUrl + "/api/graphql";
  }

  private static String apiSubdomain(URI web) {
    var port = web.getPort() < 0 ? "" : ":" + web.getPort();
    return web.getScheme() + "://api." + web.getHost() + port;
  }

  /** The host to compare: lower case, without the dot a fully qualified name may end with. */
  private static String host(URI web) {
    if (web == null || web.getHost() == null) {
      return "";
    }
    var host = web.getHost().toLowerCase(Locale.ROOT);
    return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
  }

  /**
   * {@code null} for text that is not an absolute URI. The GitHub Enterprise Server rule then
   * applies, and the startup validator is left to refuse the address.
   */
  private static URI parse(String url) {
    try {
      var uri = new URI(url);
      return uri.isAbsolute() ? uri : null;
    } catch (URISyntaxException _) {
      return null;
    }
  }
}
