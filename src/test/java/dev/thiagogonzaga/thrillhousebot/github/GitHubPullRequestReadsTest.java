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
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileContent;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.FileDiff;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.PullRequestDetails;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.Ref;
import dev.thiagogonzaga.thrillhousebot.github.GitHubPullRequestClient.TreeEntry;
import jakarta.ws.rs.ProcessingException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jboss.resteasy.reactive.ClientWebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The pull request and label reads made through hub4j, against a loopback GitHub: what is sent, and
 * what the answer is mapped to.
 */
class GitHubPullRequestReadsTest {

  private static final String AUTH = "Bearer token";
  private static final String DEAD = "Bearer expired-token";
  private static final String FRESH = "Bearer minted-token";
  private static final String JSON = "application/vnd.github+json";

  private static final String ROOT = "/api/v3/repos/o/r";
  private static final String REPOSITORY_BODY =
      "{\"id\":1,\"name\":\"r\",\"full_name\":\"o/r\",\"owner\":{\"login\":\"o\"}}";
  private static final String PULL_BODY =
      "{\"number\":7,\"title\":\"T\",\"body\":\"B\",\"state\":\"open\","
          + "\"mergeable_state\":\"clean\",\"changed_files\":27,\"additions\":975,"
          + "\"deletions\":196,\"head\":{\"sha\":\"h1\",\"ref\":\"feature\"},"
          + "\"base\":{\"sha\":\"b1\",\"ref\":\"main\"}}";

  private GitHubStub stub;
  private GitHubTokenReads tokenReads;
  private GitHubPullRequestReads reads;

  @BeforeEach
  void start() {
    stub = GitHubStub.start();
    var config = mock(ThrillhouseConfig.class, RETURNS_DEEP_STUBS);
    when(config.github().apiUrl()).thenReturn(stub.url() + "/api/v3");
    when(config.httpRequestTimeout()).thenReturn(Duration.ofSeconds(10));
    tokenReads = new GitHubTokenReads(HttpClient.newHttpClient(), config);
    reads = new GitHubPullRequestReads(tokenReads);
    GitHubTokenRefresh.SHARED.bind(_ -> Optional.of(FRESH));
    stub.on("GET " + ROOT, json(200, REPOSITORY_BODY));
  }

  @AfterEach
  void stop() {
    GitHubTokenRefresh.SHARED.bind(null);
    stub.close();
  }

  private List<String> sent() {
    return stub.received().stream().map(r -> r.method() + " " + r.uri()).toList();
  }

  private void given(String path, String body, String... headers) {
    stub.on("GET " + ROOT + path, json(200, body, headers));
  }

  private String nextLink(String path) {
    return "<" + stub.url() + ROOT + path + ">; rel=\"next\"";
  }

  private static String array(int count, String element) {
    return IntStream.range(0, count)
        .mapToObj(i -> element.formatted(i))
        .collect(Collectors.joining(",", "[", "]"));
  }

  @Test
  void aPullRequestCarriesItsTextRefsTotalsAndState() {
    given("/pulls/7", PULL_BODY);

    var pr = reads.getPullRequest(AUTH, JSON, "o", "r", 7);

    assertEquals(
        new PullRequestDetails(
            "T", "B", new Ref("h1", "feature"), new Ref("b1", "main"), 27, 975, 196, "open"),
        pr);
    assertEquals(List.of("GET " + ROOT, "GET " + ROOT + "/pulls/7"), sent());
  }

  @Test
  void aDraftPullRequestIsReadAsADraft() {
    given("/pulls/7", PULL_BODY.replace("\"state\":\"open\"", "\"state\":\"open\",\"draft\":true"));

    assertTrue(reads.getPullRequest(AUTH, JSON, "o", "r", 7).draft());
  }

  @Test
  void theRepositoryIsReadOnceForEachCredential() {
    given("/pulls/7", PULL_BODY);

    reads.getPullRequest(AUTH, JSON, "o", "r", 7);
    reads.getPullRequest(AUTH, JSON, "o", "r", 7);
    reads.getPullRequest(FRESH, JSON, "o", "r", 7);

    assertEquals(
        List.of(AUTH, AUTH, AUTH, FRESH, FRESH),
        stub.received().stream().map(GitHubStub.Received::authorization).toList());
  }

  @Test
  void theCachedRepositoriesAreDroppedWhenThereAreTooMany() throws Exception {
    for (var i = 0; i <= GitHubTokenReads.MAX_CACHED_REPOSITORIES; i++) {
      tokenReads.repository("Bearer " + i, "o/r");
    }
    tokenReads.repository("Bearer " + GitHubTokenReads.MAX_CACHED_REPOSITORIES, "o/r");
    tokenReads.repository("Bearer 0", "o/r");

    assertEquals(GitHubTokenReads.MAX_CACHED_REPOSITORIES + 2, stub.received().size());
  }

  @Test
  void everyPageOfAPullRequestsFilesIsRead() {
    given("/pulls/7", PULL_BODY);
    var second = ROOT + "/pulls/7/files?per_page=100&page=2";
    var first =
        json(
            200,
            "[{\"filename\":\"new.java\",\"status\":\"renamed\",\"additions\":2,\"deletions\":1,"
                + "\"changes\":3,\"patch\":\"@@\",\"previous_filename\":\"old.java\","
                + "\"sha\":\"s1\"}]",
            "Link",
            nextLink("/pulls/7/files?per_page=100&page=2"));
    var last = json(200, "[{\"filename\":\"b.bin\",\"status\":\"added\"}]");
    stub.on(
        "GET " + ROOT + "/pulls/7/files",
        exchange ->
            (exchange.getRequestURI().getQuery().contains("page=2") ? last : first).to(exchange));

    var files = reads.getPullRequestFiles(AUTH, JSON, "o", "r", 7);

    assertEquals(
        List.of(
            new FileDiff("new.java", "renamed", 2, 1, 3, "@@", "old.java", "s1"),
            new FileDiff("b.bin", "added", 0, 0, 0, null)),
        files);
    assertEquals("GET " + second, sent().getLast());
  }

  @Test
  void aFileListingStopsAtThePageCap() {
    given("/pulls/7", PULL_BODY);
    given(
        "/pulls/7/files",
        "[{\"filename\":\"a\",\"status\":\"added\"}]",
        "Link",
        nextLink("/pulls/7/files?page=2"));

    var files = reads.getPullRequestFiles(AUTH, JSON, "o", "r", 7);

    assertEquals(GitHubPullRequestClient.MAX_FILE_PAGES, files.size());
    assertEquals(GitHubPullRequestClient.MAX_FILE_PAGES + 2, stub.received().size());
  }

  @Test
  void aComparisonCarriesItsStatusTotalAndFiles() {
    var commit = "{\"sha\":\"c1\"}";
    given(
        "/compare/b1...h1",
        "{\"status\":\"ahead\",\"total_commits\":2,\"commits\":["
            + commit
            + "],\"base_commit\":"
            + commit
            + ",\"merge_base_commit\":"
            + commit
            + ",\"files\":[{\"filename\":\"new.java\",\"status\":\"renamed\",\"additions\":1,"
            + "\"deletions\":2,\"changes\":3,\"patch\":\"@@\","
            + "\"previous_filename\":\"old.java\",\"sha\":\"s1\"}]}");

    var comparison = reads.compareCommits(AUTH, JSON, "o", "r", "b1", "h1");

    assertEquals("ahead", comparison.status());
    assertEquals(2, comparison.totalCommits());
    assertEquals(
        List.of(new FileDiff("new.java", "renamed", 1, 2, 3, "@@", "old.java", "s1")),
        comparison.files());
  }

  @Test
  void aComparisonWithoutAStatusHasNone() {
    var commit = "{\"sha\":\"c1\"}";
    given(
        "/compare/b1...h1",
        "{\"total_commits\":0,\"commits\":[],\"files\":[],\"base_commit\":"
            + commit
            + ",\"merge_base_commit\":"
            + commit
            + "}");

    var comparison = reads.compareCommits(AUTH, JSON, "o", "r", "b1", "h1");

    assertNull(comparison.status());
    assertEquals(List.of(), comparison.files());
  }

  @Test
  void aFileIsReadAtItsRefWithItsPathEncoded() {
    stub.on(
        "GET " + ROOT + "/contents/docs/a b#c?d.md",
        json(
            200,
            "{\"name\":\"a b#c?d.md\",\"path\":\"docs/a b#c?d.md\",\"content\":\"Zm9v\\n\","
                + "\"encoding\":\"base64\",\"size\":3,\"type\":\"file\"}"));

    var file = reads.getFileContent(AUTH, JSON, "o", "r", "docs/a b#c?d.md", "h1");

    assertEquals(new FileContent("a b#c?d.md", "docs/a b#c?d.md", "Zm9v\n", "base64", 3), file);
    assertEquals("GET " + ROOT + "/contents/docs/a%20b%23c%3Fd.md?ref=h1", sent().getLast());
  }

  @Test
  void aTreeIsListedRecursivelyOnlyWhenAskedFor() {
    given(
        "/git/trees/h1",
        "{\"sha\":\"t1\",\"truncated\":true,\"tree\":[{\"path\":\"src\",\"type\":\"tree\"},"
            + "{\"path\":\"pom.xml\",\"type\":\"blob\",\"size\":12}]}");

    var flat = reads.getTree(AUTH, JSON, "o", "r", "h1", null);
    var recursive = reads.getTree(AUTH, JSON, "o", "r", "h1", "1");

    assertEquals("t1", flat.sha());
    assertTrue(flat.truncated());
    assertEquals(
        List.of(new TreeEntry("src", "tree", 0), new TreeEntry("pom.xml", "blob", 12)),
        recursive.tree());
    assertEquals(
        List.of(
            "GET " + ROOT,
            "GET " + ROOT + "/git/trees/h1",
            "GET " + ROOT + "/git/trees/h1?recursive=1"),
        sent());
  }

  @Test
  void aReadRejectedForItsCredentialIsRepeatedWithAFreshOne() {
    var pull = json(200, PULL_BODY);
    var rejected = json(401, "{\"message\":\"Bad credentials\"}");
    stub.on(
        "GET " + ROOT + "/pulls/7",
        exchange -> {
          var dead = DEAD.equals(exchange.getRequestHeaders().getFirst("Authorization"));
          (dead ? rejected : pull).to(exchange);
        });

    assertEquals("T", reads.getPullRequest(DEAD, JSON, "o", "r", 7).title());
    assertEquals(FRESH, stub.received().getLast().authorization());
  }

  @Test
  void aMissingFileIsARefusalWithItsStatus() {
    var failure =
        assertThrows(
            ClientWebApplicationException.class,
            () -> reads.getFileContent(AUTH, JSON, "o", "r", "gone.txt", "h1"));

    assertEquals(404, failure.getResponse().getStatus());
  }

  @Test
  void anUnreadableAnswerCarriesNoPartOfIt() {
    given("/pulls/7", "secret-in-the-answer");

    var failure =
        assertThrows(
            ProcessingException.class, () -> reads.getPullRequest(AUTH, JSON, "o", "r", 7));

    assertNull(failure.getCause());
    assertTrue(failure.getMessage().startsWith("GitHub App request returned an unreadable"));
  }

  @Test
  void repositoryLabelsAreReadPageByPageUpToTheCap() {
    given(
        "/labels",
        array(1, "{\"name\":\"bug\",\"color\":\"f00\",\"description\":\"d\"}"),
        "Link",
        nextLink("/labels?page=2"));

    var labels = tokenReads.repositoryLabels(AUTH, "o", "r", 3);

    assertEquals(3, labels.size());
    assertEquals(new GitHubLabelClient.Label("bug", "d", "f00"), labels.getFirst());
    assertEquals("GET " + ROOT + "/labels?per_page=100", sent().get(1));
    assertEquals(4, stub.received().size());
  }

  @Test
  void aRepositoryWithNoLabelsHasNone() {
    given("/labels", "[]");

    assertEquals(List.of(), tokenReads.repositoryLabels(AUTH, "o", "r", 3));
  }

  @Test
  void theLabelsOfAPullRequestComeWithItsIssue() {
    given(
        "/issues/42",
        "{\"number\":42,\"labels\":[{\"name\":\"java\",\"color\":\"0f0\"},{\"name\":\"deps\",\"color\":\"00f\"}]}");

    var labels = tokenReads.issueLabels(AUTH, "o", "r", 42);

    assertEquals(
        List.of(
            new GitHubLabelClient.Label("java", null, "0f0"),
            new GitHubLabelClient.Label("deps", null, "00f")),
        labels);
    assertEquals(List.of("GET " + ROOT, "GET " + ROOT + "/issues/42"), sent());
  }

  @Test
  void unreadableLabelsAreAFailureWithoutTheAnswer() {
    given("/issues/42", "nope");
    given("/labels", "nope");

    assertThrows(ProcessingException.class, () -> tokenReads.issueLabels(AUTH, "o", "r", 42));
    assertThrows(ProcessingException.class, () -> tokenReads.repositoryLabels(AUTH, "o", "r", 3));
  }
}
