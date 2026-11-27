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

import java.util.Optional;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Links to pages on the GitHub web UI the bot serves: {@code https://github.com} by default, or a
 * GitHub Enterprise Server host set through {@code GITHUB_URL} ({@code
 * thrillhousebot.github.web-url}). Read from the configuration on each call, so the record types
 * that build links need no injected bean.
 */
public final class GitHubUrls {

  /** The web UI of github.com, used when no other host is configured. */
  public static final String DEFAULT_WEB_URL = "https://github.com";

  private GitHubUrls() {}

  /** The configured GitHub web UI base URL, without a trailing slash. */
  public static String webUrl() {
    return webUrl(
        ConfigProvider.getConfig().getOptionalValue("thrillhousebot.github.web-url", String.class));
  }

  static String webUrl(Optional<String> configured) {
    return withoutTrailingSlash(
        configured.filter(value -> !value.isBlank()).orElse(DEFAULT_WEB_URL));
  }

  /** The page of pull request {@code prNumber} in {@code owner/repo}. */
  public static String pullRequest(String owner, String repo, int prNumber) {
    return pullRequest(owner + "/" + repo, prNumber);
  }

  /**
   * The page of pull request {@code prNumber} in {@code repository}, written {@code owner/repo}.
   */
  public static String pullRequest(String repository, int prNumber) {
    return webUrl() + "/" + repository + "/pull/" + prNumber;
  }

  /** {@code url} without its scheme: the host, with any port and path. */
  public static String withoutScheme(String url) {
    var separator = url.indexOf("://");
    return separator < 0 ? url : url.substring(separator + 3);
  }

  /** {@code url} without trailing slashes, so a path can be appended to it. */
  public static String withoutTrailingSlash(String url) {
    var end = url.length();
    while (end > 0 && url.charAt(end - 1) == '/') {
      end--;
    }
    return url.substring(0, end);
  }
}
