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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import io.quarkiverse.githubapp.GitHubClientProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import java.io.IOException;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import org.kohsuke.github.GHException;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.connector.GitHubConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The calls made as the GitHub App itself, through the Quarkiverse GitHub App extension's
 * application client, which signs the App JWT. A refused call and a transport failure surface as
 * described in {@link GitHubAppConnector}.
 */
@ApplicationScoped
public class GitHubAppApi {

  private static final Logger log = LoggerFactory.getLogger(GitHubAppApi.class);

  private static final int PER_PAGE = 100;
  static final int MAX_PAGES = 100;

  /** Bound while the extension builds the application client this class asked for. */
  static final ScopedValue<GitHubConnector> CONNECTOR = ScopedValue.newInstance();

  private final GitHubClientProvider clientProvider;
  private final GitHubConnector connector;

  @Inject
  public GitHubAppApi(
      GitHubClientProvider clientProvider, HttpClient httpClient, ThrillhouseConfig config) {
    this.clientProvider = clientProvider;
    this.connector = new GitHubAppConnector(httpClient, config.httpRequestTimeout());
  }

  /**
   * Mints an installation access token. Three requests: the App, the installation, then the token.
   */
  String createInstallationToken(long installationId) {
    var client = applicationClient();
    try {
      return client.getApp().getInstallationById(installationId).createToken().create().getToken();
    } catch (IOException e) {
      throw unreadable(e);
    }
  }

  /**
   * Mints an installation access token that reaches {@code repository} (the name, without the
   * owner) and no other repository of the installation. GitHub refuses the request when the
   * installation does not cover that repository.
   */
  String createRepositoryToken(long installationId, String repository) {
    var client = applicationClient();
    try {
      return client
          .getApp()
          .getInstallationById(installationId)
          .createToken()
          .repositories(List.of(repository))
          .create()
          .getToken();
    } catch (IOException e) {
      throw unreadable(e);
    }
  }

  /** The ids of every installation of the App, suspended ones included. */
  List<Long> listInstallationIds() {
    var client = applicationClient();
    try {
      var pages = client.getApp().listInstallations().withPageSize(PER_PAGE).iterator();
      var ids = new ArrayList<Long>();
      for (var page = 0; pages.hasNext(); page++) {
        if (page == MAX_PAGES) {
          throw new IllegalStateException("GitHub listing exceeded " + MAX_PAGES + " pages");
        }
        pages.nextPage().forEach(installation -> ids.add(installation.getId()));
      }
      return ids;
    } catch (IOException | GHException e) {
      throw unreadable(e);
    }
  }

  private GitHub applicationClient() {
    try {
      return ScopedValue.where(CONNECTOR, connector).call(clientProvider::getApplicationClient);
    } catch (RuntimeException e) {
      log.error("Failed to generate GitHub App JWT", e);
      throw new GitHubAuthException("JWT generation failed", e);
    }
  }

  /**
   * An answer hub4j could not read. The cause is left behind on purpose: its message can quote the
   * response, and the response to a token request holds the token.
   */
  private static ProcessingException unreadable(Exception e) {
    return new ProcessingException(
        "GitHub App request returned an unreadable answer (" + e.getClass().getSimpleName() + ")");
  }
}
