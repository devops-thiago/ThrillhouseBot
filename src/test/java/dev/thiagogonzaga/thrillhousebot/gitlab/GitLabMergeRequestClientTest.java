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
package dev.thiagogonzaga.thrillhousebot.gitlab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.WebApplicationException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The GitLab client through a real REST client against a loopback GitLab: what only the wire shows
 * is that a project path and a file path each travel as one encoded segment, and that GitLab's
 * snake_case answers bind.
 */
@QuarkusTest
class GitLabMergeRequestClientTest {

  private static final String AUTH = "Bearer token";

  private HttpServer server;
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final List<String> authorizations = new CopyOnWriteArrayList<>();

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  private GitLabMergeRequestClient clientAnswering(int status, String body) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          var uri = exchange.getRequestURI();
          requests.add(
              uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
          authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
          var bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    return QuarkusRestClientBuilder.newBuilder()
        .baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v4"))
        .build(GitLabMergeRequestClient.class);
  }

  @Test
  void aMergeRequestIsReadByItsEncodedProjectPath() throws IOException {
    var client =
        clientAnswering(
            200,
            """
            {"title":"Add retries","description":"Why","sha":"head1",
             "source_branch":"feature","target_branch":"main","changes_count":"1000+",
             "diff_refs":{"base_sha":"base1","head_sha":"head1","start_sha":"start1"},
             "unknown_field":true}
            """);

    var mr = client.getMergeRequest(AUTH, "group/sub/app", 7);

    assertEquals(List.of("/api/v4/projects/group%2Fsub%2Fapp/merge_requests/7"), requests);
    assertEquals(List.of(AUTH), authorizations);
    assertEquals("Add retries", mr.title());
    assertEquals("Why", mr.description());
    assertEquals("head1", mr.sha());
    assertEquals("feature", mr.sourceBranch());
    assertEquals("main", mr.targetBranch());
    assertEquals("1000+", mr.changesCount());
    assertEquals("base1", mr.diffRefs().baseSha());
    assertEquals("head1", mr.diffRefs().headSha());
    assertEquals("start1", mr.diffRefs().startSha());
  }

  @Test
  void aDiffsPageBindsItsFlags() throws IOException {
    var client =
        clientAnswering(
            200,
            """
            [{"old_path":"old/A.java","new_path":"new/A.java","diff":"@@ -1 +1 @@\\n-a\\n+b\\n",
              "new_file":false,"renamed_file":true,"deleted_file":false}]
            """);

    var diffs = client.getMergeRequestDiffsPage(AUTH, "group/app", 7, 100, 2);

    assertEquals(
        List.of("/api/v4/projects/group%2Fapp/merge_requests/7/diffs?per_page=100&page=2"),
        requests);
    var diff = diffs.getFirst();
    assertEquals("old/A.java", diff.oldPath());
    assertEquals("new/A.java", diff.newPath());
    assertEquals("@@ -1 +1 @@\n-a\n+b\n", diff.diff());
    assertTrue(diff.renamedFile());
  }

  @Test
  void aComparisonNamesBothRevisions() throws IOException {
    var client =
        clientAnswering(
            200,
            """
            {"commits":[{"id":"c1"}],"diffs":[{"old_path":"a","new_path":"a","diff":"",
              "new_file":true,"renamed_file":false,"deleted_file":false}]}
            """);

    var comparison = client.compare(AUTH, "group/app", "base1", "head1");

    assertEquals(
        List.of("/api/v4/projects/group%2Fapp/repository/compare?from=base1&to=head1"), requests);
    assertEquals("c1", comparison.commits().getFirst().id());
    assertTrue(comparison.diffs().getFirst().newFile());
  }

  @Test
  void aFileIsReadByItsEncodedPath() throws IOException {
    var client =
        clientAnswering(
            200,
            """
            {"file_name":"README.md","file_path":"docs/README.md","size":5,
             "encoding":"base64","content":"aGVsbG8="}
            """);

    var file = client.getFile(AUTH, "group/app", "docs/README.md", "main");

    assertEquals(
        List.of("/api/v4/projects/group%2Fapp/repository/files/docs%2FREADME.md?ref=main"),
        requests);
    assertEquals("README.md", file.fileName());
    assertEquals("docs/README.md", file.filePath());
    assertEquals(5, file.size());
    assertEquals("base64", file.encoding());
    assertEquals("aGVsbG8=", file.content());
  }

  @Test
  void aTreePageAsksForEveryDirectory() throws IOException {
    var client = clientAnswering(200, "[{\"path\":\"src/A.java\",\"type\":\"blob\"}]");

    var page = client.getTreePage(AUTH, "group/app", "main", true, 100, 1);

    assertEquals(
        List.of(
            "/api/v4/projects/group%2Fapp/repository/tree?ref=main&recursive=true&per_page=100&page=1"),
        requests);
    assertEquals("src/A.java", page.getFirst().path());
    assertEquals("blob", page.getFirst().type());
  }

  @Test
  void aMissingFileSurfacesAsAWebApplicationException() throws IOException {
    var client = clientAnswering(404, "{\"message\":\"404 File Not Found\"}");

    var thrown =
        assertThrows(
            WebApplicationException.class,
            () -> client.getFile(AUTH, "group/app", "missing.md", "main"));

    assertEquals(404, thrown.getResponse().getStatus());
  }
}
