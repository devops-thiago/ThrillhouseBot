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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;
import org.kohsuke.github.GHException;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHWorkflowRun;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.PagedIterator;
import org.kohsuke.github.connector.GitHubConnector;

/**
 * Reads made through hub4j with an installation token the caller already holds, so each one has
 * exactly the permissions and repositories of that token.
 *
 * <p>The client is built here with hub4j's own builder: the Quarkiverse GitHub App extension's
 * {@code GitHubClientProvider} only hands out clients for the App and for tokens it mints itself,
 * which reach the whole installation. This goes away once the extension can build a client for a
 * caller's token.
 *
 * <p>Calls go to {@code thrillhousebot.github.api-url} through {@link GitHubAppConnector}, which
 * gives them the REST clients' failures, one attempt and {@code HTTP_REQUEST_TIMEOUT}. hub4j
 * follows a redirect itself, and takes the next page of a listing from the address GitHub names.
 */
@ApplicationScoped
public class GitHubTokenReads {

  static final int REPOSITORIES_PER_PAGE = 100;

  static final int MAX_REPOSITORY_PAGES = 100;

  /**
   * Completed runs read for one head SHA. This is a page size, not a page walk: the request is
   * filtered server-side by {@code head_sha}, so it bounds the workflows and re-runs on a SINGLE
   * commit rather than recent repository activity. See {@code PatchCoverageResolver.findArtifactId}
   * for why one page is enough and what exceeding it costs.
   */
  static final int RUNS_PER_PAGE = 30;

  /** Artifacts listed per run; GitHub's maximum page size, so one call covers any real run. */
  static final int ARTIFACTS_PER_PAGE = 100;

  private final String apiUrl;
  private final GitHubConnector connector;

  @Inject
  public GitHubTokenReads(HttpClient httpClient, ThrillhouseConfig config) {
    this.apiUrl = config.github().apiUrl();
    this.connector = new GitHubAppConnector(httpClient, config.httpRequestTimeout());
  }

  /** A repository an installation can access; {@code createdAt} is null when GitHub omits it. */
  public record InstallationRepository(long id, String fullName, Instant createdAt) {}

  /** A completed workflow run of one commit; listing its artifacts is one more request. */
  public record WorkflowRun(
      long id, String headSha, String conclusion, Supplier<List<Artifact>> artifactListing) {

    public List<Artifact> artifacts() {
      return artifactListing.get();
    }
  }

  /** One uploaded artifact; {@code expired} artifacts have had their bytes deleted by GitHub. */
  public record Artifact(long id, String name, boolean expired) {}

  /**
   * Every repository the installation token can access.
   *
   * @throws IllegalStateException past {@value #MAX_REPOSITORY_PAGES} pages
   */
  public List<InstallationRepository> installationRepositories(String auth) {
    try {
      var pages =
          client(auth)
              .getInstallation()
              .listRepositories()
              .withPageSize(REPOSITORIES_PER_PAGE)
              .iterator();
      var repositories = new ArrayList<InstallationRepository>();
      for (var page = 0; pages.hasNext(); page++) {
        if (page == MAX_REPOSITORY_PAGES) {
          throw new IllegalStateException(
              "GitHub listing exceeded " + MAX_REPOSITORY_PAGES + " pages");
        }
        for (var repository : pages.nextPage()) {
          repositories.add(installationRepository(repository));
        }
      }
      return repositories;
    } catch (IOException | GHException e) {
      throw unreadable(e);
    }
  }

  /**
   * The first page of the completed workflow runs of exactly one commit, healing a rejected
   * credential once (#626). Filtering server-side by {@code head_sha} is what makes an artifact's
   * provenance checkable: it belongs to the revision being reviewed, not to whatever ran most
   * recently on the branch. Two requests: hub4j has to read the repository before it can list its
   * runs.
   */
  public List<WorkflowRun> completedWorkflowRuns(
      String auth, String owner, String repo, String headSha) {
    var slug = owner + "/" + repo;
    return GitHubTokenRefresh.SHARED.retrying(
        "workflow runs of " + headSha + " on " + slug,
        auth,
        credential -> completedRuns(credential, slug, headSha));
  }

  private List<WorkflowRun> completedRuns(String auth, String slug, String headSha) {
    try {
      var runs =
          client(auth)
              .getRepository(slug)
              .queryWorkflowRuns()
              .headSha(headSha)
              .status(GHWorkflowRun.Status.COMPLETED)
              .list()
              .withPageSize(RUNS_PER_PAGE)
              .iterator();
      return firstPage(runs).stream().map(run -> workflowRun(auth, slug, run)).toList();
    } catch (IOException | GHException e) {
      throw unreadable(e);
    }
  }

  private WorkflowRun workflowRun(String auth, String slug, GHWorkflowRun run) {
    var conclusion = run.getConclusion();
    return new WorkflowRun(
        run.getId(),
        run.getHeadSha(),
        conclusion == null ? null : conclusion.toString(),
        () ->
            GitHubTokenRefresh.SHARED.retrying(
                "artifacts of run " + run.getId() + " on " + slug,
                auth,
                credential -> artifacts(auth, credential, slug, run)));
  }

  /** A replacement credential needs the run read again: hub4j binds a run to its client. */
  private List<Artifact> artifacts(
      String listedWith, String credential, String slug, GHWorkflowRun run) {
    try {
      var current =
          credential.equals(listedWith)
              ? run
              : client(credential).getRepository(slug).getWorkflowRun(run.getId());
      return firstPage(current.listArtifacts().withPageSize(ARTIFACTS_PER_PAGE).iterator()).stream()
          .map(artifact -> new Artifact(artifact.getId(), artifact.getName(), artifact.isExpired()))
          .toList();
    } catch (IOException | GHException e) {
      throw unreadable(e);
    }
  }

  private GitHub client(String auth) throws IOException {
    var builder =
        new GitHubBuilder()
            .withEndpoint(apiUrl)
            .withConnector(connector)
            .withAuthorizationProvider(() -> auth);
    FailFastGitHubCustomizer.failFast(builder);
    return builder.build();
  }

  private static InstallationRepository installationRepository(GHRepository repository)
      throws IOException {
    Date createdAt = repository.getCreatedAt();
    return new InstallationRepository(
        repository.getId(),
        repository.getFullName(),
        createdAt == null ? null : createdAt.toInstant());
  }

  private static <T> List<T> firstPage(PagedIterator<T> pages) {
    return pages.hasNext() ? pages.nextPage() : List.of();
  }

  /** An answer hub4j could not read, without the cause: its message can quote the response. */
  private static ProcessingException unreadable(Exception e) {
    return new ProcessingException(
        "GitHub App request returned an unreadable answer (" + e.getClass().getSimpleName() + ")");
  }
}
