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

import static dev.thiagogonzaga.thrillhousebot.github.GitHubStub.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import jakarta.ws.rs.ProcessingException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.resteasy.reactive.ClientWebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The reads made with a caller's token, against a loopback GitHub: what is sent, to which address
 * and with which credential, and how much of an answer is read.
 */
class GitHubTokenReadsTest {

  private static final String DEAD = "Bearer expired-token";
  private static final String FRESH = "Bearer minted-token";
  private static final String REPOSITORIES = "GET /api/v3/installation/repositories";
  private static final String REPOSITORY = "GET /api/v3/repos/o/r";
  private static final String RUNS = "GET /api/v3/repos/o/r/actions/runs";
  private static final String RUN = "GET /api/v3/repos/o/r/actions/runs/42";
  private static final String ARTIFACTS = "GET /api/v3/repos/o/r/actions/runs/42/artifacts";
  private static final String ARTIFACT = "GET /api/v3/repos/o/r/actions/artifacts/99";
  private static final String ZIP = ARTIFACT + "/zip";
  private static final String JOB = "GET /api/v3/repos/o/r/actions/jobs/7";
  private static final String LOGS = JOB + "/logs";
  private static final String SIGNED = "https://blob.example/signed?sig=s3cret";

  private static final String REPOSITORY_BODY =
      "{\"id\":1,\"name\":\"r\",\"full_name\":\"o/r\",\"owner\":{\"login\":\"o\"}}";
  private static final String RUN_BODY =
      "{\"id\":42,\"head_sha\":\"abc\",\"status\":\"completed\",\"conclusion\":\"success\"}";
  private static final String ARTIFACTS_BODY =
      "{\"total_count\":1,\"artifacts\":[{\"id\":99,\"name\":\"coverage\",\"expired\":true}]}";

  private GitHubStub stub;
  private GitHubTokenReads reads;

  @BeforeEach
  void start() {
    stub = GitHubStub.start();
    var config = mock(ThrillhouseConfig.class, RETURNS_DEEP_STUBS);
    when(config.github().apiUrl()).thenReturn(stub.url() + "/api/v3");
    when(config.httpRequestTimeout()).thenReturn(Duration.ofSeconds(10));
    reads = new GitHubTokenReads(HttpClient.newHttpClient(), config);
    GitHubTokenRefresh.SHARED.bind(_ -> Optional.of(FRESH));
  }

  @AfterEach
  void stop() {
    GitHubTokenRefresh.SHARED.bind(null);
    stub.close();
  }

  private List<String> sent() {
    return stub.received().stream().map(r -> r.method() + " " + r.uri()).toList();
  }

  private List<String> credentials() {
    return stub.received().stream().map(GitHubStub.Received::authorization).toList();
  }

  private String nextLink(String path) {
    return "<" + stub.url() + path + ">; rel=\"next\"";
  }

  private void givenRepositoryAndRuns(String runs) {
    stub.on(REPOSITORY, json(200, REPOSITORY_BODY))
        .on(RUNS, json(200, "{\"total_count\":1,\"workflow_runs\":[" + runs + "]}"));
  }

  @Test
  void everyPageOfAnInstallationsRepositoriesIsReadWithTheCallersCredential() {
    var second = "/api/v3/installation/repositories?per_page=100&page=2";
    var first =
        json(
            200,
            "{\"total_count\":2,\"repositories\":[{\"id\":1,\"full_name\":\"o/a\","
                + "\"created_at\":\"2020-01-01T00:00:00Z\"}]}",
            "Link",
            nextLink(second));
    var last = json(200, "{\"total_count\":2,\"repositories\":[{\"id\":2,\"full_name\":\"o/b\"}]}");
    stub.on(
        REPOSITORIES,
        exchange -> {
          var query = exchange.getRequestURI().getQuery();
          (query.contains("page=2&") || query.endsWith("page=2") ? last : first).to(exchange);
        });

    var repositories = reads.installationRepositories(DEAD);

    assertEquals(
        List.of(
            new GitHubTokenReads.InstallationRepository(
                1L, "o/a", Instant.parse("2020-01-01T00:00:00Z")),
            new GitHubTokenReads.InstallationRepository(2L, "o/b", null)),
        repositories);
    assertEquals(
        List.of("GET /api/v3/installation/repositories?per_page=100", "GET " + second), sent());
    assertEquals(List.of(DEAD, DEAD), credentials());
  }

  @Test
  void aRepositoryListingThatNeverEndsIsCutOff() {
    stub.on(
        REPOSITORIES,
        json(
            200,
            "{\"total_count\":1,\"repositories\":[{\"id\":1,\"full_name\":\"o/a\"}]}",
            "Link",
            nextLink("/api/v3/installation/repositories?page=2")));

    var failure =
        assertThrows(IllegalStateException.class, () -> reads.installationRepositories(DEAD));

    assertEquals("GitHub listing exceeded 100 pages", failure.getMessage());
    assertEquals(GitHubTokenReads.MAX_REPOSITORY_PAGES + 1, stub.received().size());
  }

  @Test
  void onlyTheFirstPageOfACommitsCompletedRunsAndOfARunsArtifactsIsRead() {
    stub.on(REPOSITORY, json(200, REPOSITORY_BODY))
        .on(
            RUNS,
            json(
                200,
                "{\"total_count\":2,\"workflow_runs\":["
                    + RUN_BODY
                    + ",{\"id\":43,\"head_sha\":\"abc\",\"status\":\"completed\"}]}",
                "Link",
                nextLink("/api/v3/repos/o/r/actions/runs?page=2")))
        .on(
            ARTIFACTS,
            json(
                200,
                ARTIFACTS_BODY,
                "Link",
                nextLink("/api/v3/repos/o/r/actions/runs/42/artifacts?page=2")));

    var runs = reads.completedWorkflowRuns(DEAD, "o", "r", "abc");

    assertEquals(List.of(42L, 43L), runs.stream().map(GitHubTokenReads.WorkflowRun::id).toList());
    assertEquals("abc", runs.getFirst().headSha());
    assertEquals("success", runs.getFirst().conclusion());
    assertNull(runs.getLast().conclusion());
    assertArtifact(runs.getFirst().artifacts());
    assertEquals(3, stub.received().size());
    assertEquals("GET /api/v3/repos/o/r", sent().get(0));
    var runsQuery = sent().get(1);
    assertEquals(
        List.of("head_sha=abc", "per_page=30", "status=completed"),
        List.of(runsQuery.substring(runsQuery.indexOf('?') + 1).split("&")).stream()
            .sorted()
            .toList());
    assertEquals("GET /api/v3/repos/o/r/actions/runs/42/artifacts?per_page=100", sent().get(2));
    assertEquals(List.of(DEAD, DEAD, DEAD), credentials());
  }

  @Test
  void aCommitWithNoCompletedRunHasNone() {
    givenRepositoryAndRuns("");

    assertEquals(List.of(), reads.completedWorkflowRuns(DEAD, "o", "r", "abc"));
  }

  @Test
  void aRunListingRejectedForItsCredentialIsRepeatedWithAFreshOne() {
    var repository = json(200, REPOSITORY_BODY);
    var rejected = json(401, "{\"message\":\"Bad credentials\"}");
    stub.on(
            REPOSITORY,
            exchange -> {
              var dead = DEAD.equals(exchange.getRequestHeaders().getFirst("Authorization"));
              (dead ? rejected : repository).to(exchange);
            })
        .on(RUNS, json(200, "{\"total_count\":1,\"workflow_runs\":[" + RUN_BODY + "]}"))
        .on(ARTIFACTS, json(200, ARTIFACTS_BODY));

    var runs = reads.completedWorkflowRuns(DEAD, "o", "r", "abc");
    runs.getFirst().artifacts();

    assertEquals(List.of(DEAD, FRESH, FRESH, FRESH), credentials());
  }

  @Test
  void anArtifactListingRejectedForItsCredentialReadsTheRunAgainWithAFreshOne() {
    var expired = new AtomicBoolean();
    var listing = json(200, ARTIFACTS_BODY);
    var rejected = json(401, "{\"message\":\"Bad credentials\"}");
    givenRepositoryAndRuns(RUN_BODY);
    stub.on(RUN, json(200, RUN_BODY))
        .on(
            ARTIFACTS,
            exchange -> {
              var answer = expired.getAndSet(true) ? listing : rejected;
              answer.to(exchange);
            });

    var artifacts = reads.completedWorkflowRuns(DEAD, "o", "r", "abc").getFirst().artifacts();

    assertArtifact(artifacts);
    assertEquals(
        List.of(REPOSITORY, RUNS, ARTIFACTS, REPOSITORY, RUN, ARTIFACTS),
        sent().stream().map(r -> r.contains("?") ? r.substring(0, r.indexOf('?')) : r).toList());
    assertEquals(List.of(DEAD, DEAD, DEAD, FRESH, FRESH, FRESH), credentials());
  }

  @Test
  void aRefusalKeepsItsStatusAndARateLimitIsNotWaitedOut() {
    stub.on(
        REPOSITORIES,
        json(
            403,
            "{\"message\":\"API rate limit exceeded\"}",
            "x-ratelimit-remaining",
            "0",
            "x-ratelimit-reset",
            Long.toString(Instant.now().plusSeconds(3600).getEpochSecond())));

    var limited =
        assertThrows(
            ClientWebApplicationException.class, () -> reads.installationRepositories(DEAD));
    var missing =
        assertThrows(
            ClientWebApplicationException.class,
            () -> reads.completedWorkflowRuns(DEAD, "o", "r", "abc"));

    assertEquals(403, limited.getResponse().getStatus());
    assertEquals(404, missing.getResponse().getStatus());
    assertEquals(2, stub.received().size());
  }

  @Test
  void anUnreadableAnswerIsReportedWithoutWhatItSaid() {
    var garbage = json(200, "not json");
    givenRepositoryAndRuns(RUN_BODY);
    stub.on(REPOSITORIES, garbage).on(ARTIFACTS, garbage);
    var run = reads.completedWorkflowRuns(DEAD, "o", "r", "abc").getFirst();

    assertUnreadable(assertThrows(ProcessingException.class, run::artifacts));
    assertUnreadable(
        assertThrows(ProcessingException.class, () -> reads.installationRepositories(DEAD)));
    stub.on(RUNS, garbage);
    assertUnreadable(
        assertThrows(
            ProcessingException.class, () -> reads.completedWorkflowRuns(DEAD, "o", "r", "abc")));
  }

  private static void assertArtifact(List<GitHubTokenReads.Artifact> artifacts) {
    assertEquals(1, artifacts.size());
    assertEquals(99L, artifacts.getFirst().id());
    assertEquals("coverage", artifacts.getFirst().name());
    assertTrue(artifacts.getFirst().expired());
  }

  private static GitHubStub.Answer redirect(String location) {
    return json(302, "", "Location", location);
  }

  private GitHubTokenReads.Artifact listedArtifact() {
    givenRepositoryAndRuns(RUN_BODY);
    stub.on(ARTIFACTS, json(200, ARTIFACTS_BODY));
    return reads.completedWorkflowRuns(DEAD, "o", "r", "abc").getFirst().artifacts().getFirst();
  }

  private List<String> sentAfter(int count) {
    return sent().subList(count, sent().size());
  }

  @Test
  void anArtifactDownloadGivesOutTheRedirectAfterOneRequestAndNeverFollowsIt() {
    var artifact = listedArtifact();
    stub.on(ZIP, redirect(SIGNED));
    var before = sent().size();

    assertEquals(Optional.of(URI.create(SIGNED)), artifact.downloadLocation());

    assertEquals(List.of(ZIP), sentAfter(before));
    assertEquals(DEAD, credentials().getLast());
  }

  @Test
  void aRelativeRedirectIsResolvedAgainstTheDownloadAddressAndNotRequested() {
    var artifact = listedArtifact();
    stub.on(ZIP, redirect("/signed-storage/artifact"));
    var before = sent().size();

    assertEquals(
        Optional.of(URI.create(stub.url() + "/signed-storage/artifact")),
        artifact.downloadLocation());

    assertEquals(List.of(ZIP), sentAfter(before));
  }

  @Test
  void anArtifactDownloadRejectedForItsCredentialReadsTheArtifactAgainWithAFreshOne() {
    var artifact = listedArtifact();
    var rejected = json(401, "{\"message\":\"Bad credentials\"}");
    var signed = redirect(SIGNED);
    stub.on(
            ZIP,
            exchange -> {
              var dead = DEAD.equals(exchange.getRequestHeaders().getFirst("Authorization"));
              (dead ? rejected : signed).to(exchange);
            })
        .on(ARTIFACT, json(200, "{\"id\":99,\"name\":\"coverage\"}"));
    var before = sent().size();

    assertEquals(Optional.of(URI.create(SIGNED)), artifact.downloadLocation());

    assertEquals(List.of(ZIP, REPOSITORY, ARTIFACT, ZIP), sentAfter(before));
    assertEquals(
        List.of(DEAD, FRESH, FRESH, FRESH), credentials().subList(before, credentials().size()));
  }

  @Test
  void aJobLogDownloadGivesOutTheRedirectAfterReadingTheRepositoryAndTheJob() {
    stub.on(REPOSITORY, json(200, REPOSITORY_BODY))
        .on(JOB, json(200, "{\"id\":7}"))
        .on(LOGS, redirect(SIGNED));

    assertEquals(Optional.of(URI.create(SIGNED)), reads.jobLogLocation(DEAD, "o", "r", 7L));

    assertEquals(List.of(REPOSITORY, JOB, LOGS), sent());
  }

  @Test
  void aRefusedDownloadOrOneThatNamesNoAddressHasNoLocation() {
    stub.on(REPOSITORY, json(200, REPOSITORY_BODY)).on(JOB, json(200, "{\"id\":7}"));

    assertEquals(Optional.empty(), reads.jobLogLocation(DEAD, "o", "r", 7L));
    stub.on(LOGS, json(410, "{\"message\":\"Gone\"}"));
    assertEquals(Optional.empty(), reads.jobLogLocation(DEAD, "o", "r", 7L));
    stub.on(LOGS, json(302, ""));
    assertEquals(Optional.empty(), reads.jobLogLocation(DEAD, "o", "r", 7L));
    stub.on(LOGS, json(200, "the log itself"));
    assertEquals(Optional.empty(), reads.jobLogLocation(DEAD, "o", "r", 7L));
  }

  @Test
  void anArtifactDownloadAnsweredWithoutARedirectHasNoLocation() {
    var artifact = listedArtifact();
    stub.on(ZIP, json(200, "the archive itself"));

    assertEquals(Optional.empty(), artifact.downloadLocation());
  }

  @Test
  void anUnreadableAnswerOnTheWayToADownloadIsReportedWithoutWhatItSaid() {
    stub.on(REPOSITORY, json(200, "not json"));

    var failure =
        assertThrows(ProcessingException.class, () -> reads.jobLogLocation(DEAD, "o", "r", 7L));

    assertNull(failure.getCause());
    assertEquals(
        "GitHub App request returned an unreadable answer (HttpException)", failure.getMessage());
  }

  @Test
  void aRenamedRepositoryIsStillFollowed() {
    stub.on(REPOSITORY, json(301, "", "Location", stub.url() + "/api/v3/repositories/1"))
        .on("GET /api/v3/repositories/1", json(200, REPOSITORY_BODY))
        .on(JOB, json(200, "{\"id\":7}"))
        .on(LOGS, redirect(SIGNED));

    assertEquals(Optional.of(URI.create(SIGNED)), reads.jobLogLocation(DEAD, "o", "r", 7L));
  }

  @Test
  void aUsersRepositoriesOfOneInstallationAreReadFromTheUserEndpointPageByPage() {
    var path = "/api/v3/user/installations/42/repositories";
    var second = path + "?per_page=100&page=2";
    var first =
        json(
            200,
            "{\"total_count\":2,\"repositories\":[{\"id\":1,\"full_name\":\"o/a\"}]}",
            "Link",
            nextLink(second));
    var last = json(200, "{\"total_count\":2,\"repositories\":[{\"id\":2,\"full_name\":\"o/b\"}]}");
    stub.on(
        "GET " + path,
        exchange -> {
          var query = exchange.getRequestURI().getQuery();
          (query.contains("page=2&") || query.endsWith("page=2") ? last : first).to(exchange);
        });

    var repositories = reads.userInstallationRepositories(DEAD, 42L);

    assertEquals(
        List.of("o/a", "o/b"),
        repositories.stream().map(GitHubTokenReads.InstallationRepository::fullName).toList());
    assertEquals(List.of("GET " + path + "?per_page=100", "GET " + second), sent());
    assertEquals(List.of(DEAD, DEAD), credentials());
  }

  @Test
  void aUsersRepositoryListingIsCutOffOnlyAfterAThousandPages() {
    var path = "/api/v3/user/installations/42/repositories";
    stub.on(
        "GET " + path,
        json(
            200,
            "{\"total_count\":1,\"repositories\":[{\"id\":1,\"full_name\":\"o/a\"}]}",
            "Link",
            nextLink(path + "?page=2")));

    var cutOff =
        assertThrows(
            IllegalStateException.class, () -> reads.userInstallationRepositories(DEAD, 42L));

    assertEquals("GitHub listing exceeded 1000 pages", cutOff.getMessage());
    assertEquals(1_001, stub.received().size());
  }

  @Test
  void aRefusedUserListingKeepsItsStatus() {
    stub.on(
        "GET /api/v3/user/installations/42/repositories",
        json(401, "{\"message\":\"Bad credentials\"}"));

    var rejected =
        assertThrows(
            ClientWebApplicationException.class,
            () -> reads.userInstallationRepositories(DEAD, 42L));

    assertEquals(401, rejected.getResponse().getStatus());
    assertEquals(1, stub.received().size());
  }

  private static void assertUnreadable(ProcessingException failure) {
    assertNull(failure.getCause());
    assertEquals(
        "GitHub App request returned an unreadable answer (GHException)", failure.getMessage());
  }
}
