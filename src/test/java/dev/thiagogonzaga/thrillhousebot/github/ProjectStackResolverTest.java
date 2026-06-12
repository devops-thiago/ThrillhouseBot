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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ProjectStackResolverTest {

  @Mock private GitHubAuthClient authClient;

  @Mock private GitHubPullRequestClient prClient;

  private final AtomicLong currentTimeMs = new AtomicLong(1_000_000L);

  private ProjectStackResolver resolver;

  private static final String OWNER = "test-owner";
  private static final String REPO = "test-repo";
  private static final String DEFAULT_BRANCH = "main";
  private static final long INSTALLATION_ID = 42L;
  private static final String AUTH_HEADER = "Bearer test-jwt";
  private static final String ACCEPT = "application/vnd.github+json";

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(authClient.getAuthHeader(INSTALLATION_ID)).thenReturn(AUTH_HEADER);
    when(prClient.getFileContent(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new NotFoundException(Response.status(404).build()));
    resolver =
        new ProjectStackResolver(authClient, prClient, new ObjectMapper(), currentTimeMs::get);
  }

  private GitHubPullRequestClient.FileContent makeFileContent(String name, String content) {
    var encoded = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    return new GitHubPullRequestClient.FileContent(name, name, encoded, "base64", content.length());
  }

  private void stubManifest(String path, GitHubPullRequestClient.FileContent content) {
    doReturn(content)
        .when(prClient)
        .getFileContent(AUTH_HEADER, ACCEPT, OWNER, REPO, path, DEFAULT_BRANCH);
  }

  @Test
  void shouldSummarizePomArtifacts() {
    stubManifest(
        "pom.xml",
        makeFileContent(
            "pom.xml",
            """
            <project>
              <artifactId>my-app</artifactId>
              <dependencies>
                <dependency>
                  <groupId>io.quarkus</groupId>
                  <artifactId>quarkus-hibernate-orm-panache</artifactId>
                </dependency>
                <dependency>
                  <groupId>io.quarkus</groupId>
                  <artifactId>quarkus-rest</artifactId>
                </dependency>
              </dependencies>
            </project>
            """));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(stack.contains("### pom.xml"));
    assertTrue(
        stack.contains("Maven artifacts: my-app, quarkus-hibernate-orm-panache, quarkus-rest"));
  }

  @Test
  void shouldSummarizePackageJsonDependencies() {
    stubManifest(
        "package.json",
        makeFileContent(
            "package.json",
            """
            {
              "name": "frontend",
              "dependencies": {"react": "^19.0.0"},
              "devDependencies": {"vite": "^6.0.0"}
            }
            """));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(stack.contains("### package.json"));
    assertTrue(stack.contains("npm packages: react ^19.0.0, vite ^6.0.0"));
  }

  @Test
  void shouldSummarizeGradleDependencyLines() {
    stubManifest(
        "build.gradle",
        makeFileContent(
            "build.gradle",
            """
            plugins { id 'java' }
            dependencies {
                implementation 'io.quarkus:quarkus-core:3.0.0'
                testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'
            }
            """));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(stack.contains("### build.gradle"));
    assertTrue(stack.contains("implementation 'io.quarkus:quarkus-core:3.0.0'"));
    assertTrue(stack.contains("testImplementation 'org.junit.jupiter:junit-jupiter:5.10.0'"));
    assertFalse(stack.contains("plugins"));
  }

  @Test
  void shouldIgnoreUnterminatedAndEmptyMavenArtifactTags() {
    stubManifest(
        "pom.xml",
        makeFileContent(
            "pom.xml",
            "<artifactId>  </artifactId><artifactId>real</artifactId><artifactId>cut-off"));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(stack.contains("Maven artifacts: real"));
    assertFalse(stack.contains("cut-off"));
  }

  @Test
  void shouldNotTreatGradlePrefixWordsAsDependencyLines() {
    stubManifest(
        "build.gradle",
        makeFileContent(
            "build.gradle",
            """
            apiVersion = 5
            implementations.forEach { }
            api\t"x:tabbed:1"
            """));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(stack.contains("api\t\"x:tabbed:1\""));
    assertFalse(stack.contains("apiVersion"));
    assertFalse(stack.contains("implementations.forEach"));
  }

  @Test
  void shouldFallBackToRawExcerptForOtherManifests() {
    stubManifest(
        "go.mod",
        makeFileContent(
            "go.mod",
            """
            module example.com/app

            require github.com/stretchr/testify v1.9.0
            """));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(stack.contains("### go.mod"));
    assertTrue(stack.contains("module example.com/app"));
    assertTrue(stack.contains("require github.com/stretchr/testify v1.9.0"));
  }

  @Test
  void shouldFallBackToRawExcerptForUnparseablePackageJson() {
    stubManifest("package.json", makeFileContent("package.json", "not json {"));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(stack.contains("### package.json"));
    assertTrue(stack.contains("not json {"));
  }

  @Test
  void shouldFallBackToRawExcerptForPomWithoutArtifactIds() {
    stubManifest("pom.xml", makeFileContent("pom.xml", "<project><groupId>g</groupId></project>"));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertFalse(stack.contains("Maven artifacts:"));
    assertTrue(stack.contains("<project><groupId>g</groupId></project>"));
  }

  @Test
  void shouldFallBackToRawExcerptForPackageJsonWithoutDependencies() {
    stubManifest("package.json", makeFileContent("package.json", "{\"name\": \"frontend\"}"));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertFalse(stack.contains("npm packages:"));
    assertTrue(stack.contains("{\"name\": \"frontend\"}"));
  }

  @Test
  void shouldTruncateOversizedSections() {
    stubManifest(
        "go.mod",
        makeFileContent(
            "go.mod", "x".repeat(ProjectStackResolver.MAX_SECTION_CHARS + 100) + "\nend"));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(stack.contains("… (truncated)"));
    assertFalse(stack.contains("end"));
  }

  @Test
  void shouldCapManifestCount() {
    stubManifest("pom.xml", makeFileContent("pom.xml", "<artifactId>a</artifactId>"));
    stubManifest("build.gradle", makeFileContent("build.gradle", "implementation 'x:y:1'"));
    stubManifest("build.gradle.kts", makeFileContent("build.gradle.kts", "api(\"x:z:1\")"));
    stubManifest("package.json", makeFileContent("package.json", "{\"dependencies\":{}}"));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(stack.contains("### pom.xml"));
    assertTrue(stack.contains("### build.gradle"));
    assertTrue(stack.contains("### build.gradle.kts"));
    assertFalse(stack.contains("### package.json"));
  }

  @Test
  void shouldReturnEmptyWhenNoManifestFound() {
    assertEquals("", resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID));
  }

  @Test
  void shouldDecodeMimeWrappedBase64() {
    var content = "<artifactId>quarkus-arc</artifactId>\n".repeat(10);
    // GitHub's contents API returns base64 with embedded newlines
    var wrapped =
        Base64.getMimeEncoder(60, "\n".getBytes(StandardCharsets.UTF_8))
            .encodeToString(content.getBytes(StandardCharsets.UTF_8));
    assertTrue(wrapped.contains("\n"));
    stubManifest(
        "pom.xml",
        new GitHubPullRequestClient.FileContent(
            "pom.xml", "pom.xml", wrapped, "base64", content.length()));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(stack.contains("Maven artifacts: quarkus-arc"));
  }

  @Test
  void shouldSkipManifestWithNullContentAndContinueProbing() {
    // Submodules, directories, and oversized files come back without base64 content
    stubManifest(
        "pom.xml", new GitHubPullRequestClient.FileContent("pom.xml", "pom.xml", null, "none", 0));
    stubManifest("build.gradle", makeFileContent("build.gradle", "implementation 'x:y:1'"));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertFalse(stack.contains("### pom.xml"));
    assertTrue(stack.contains("### build.gradle"));
  }

  @Test
  void shouldSkipManifestsThatDecodeToBlankContent() {
    // The MIME decoder skips non-alphabet characters, so "!!!" decodes to empty content
    stubManifest(
        "pom.xml",
        new GitHubPullRequestClient.FileContent("pom.xml", "pom.xml", "!!!", "base64", 3));

    assertEquals("", resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID));
  }

  @Test
  void shouldCacheStackAndNotHitApiOnSecondCall() {
    stubManifest("pom.xml", makeFileContent("pom.xml", "<artifactId>a</artifactId>"));

    var first = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);
    var second = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertEquals(first, second);
    // One probe per manifest path on the first call only
    verify(prClient, times(ProjectStackResolver.MANIFEST_PATHS.size()))
        .getFileContent(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void shouldRefetchAfterCacheExpiry() {
    stubManifest("pom.xml", makeFileContent("pom.xml", "<artifactId>a</artifactId>"));
    resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    currentTimeMs.addAndGet(ProjectStackResolver.CACHE_TTL_MS + 1);
    stubManifest("pom.xml", makeFileContent("pom.xml", "<artifactId>b</artifactId>"));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(stack.contains("Maven artifacts: b"));
  }

  @Test
  void sweepShouldEvictExpiredEntriesAboveThreshold() {
    for (var i = 0; i < ProjectStackResolver.CACHE_SWEEP_THRESHOLD; i++) {
      resolver.resolve(OWNER, "repo-" + i, DEFAULT_BRANCH, INSTALLATION_ID);
    }
    assertEquals(ProjectStackResolver.CACHE_SWEEP_THRESHOLD, resolver.cache.size());

    currentTimeMs.addAndGet(ProjectStackResolver.CACHE_TTL_MS + 1);
    resolver.resolve(OWNER, "fresh-repo", DEFAULT_BRANCH, INSTALLATION_ID);

    assertEquals(1, resolver.cache.size());
  }

  @Test
  void productionConstructorShouldResolveWithSystemClock() {
    var productionResolver = new ProjectStackResolver(authClient, prClient, new ObjectMapper());
    stubManifest("pom.xml", makeFileContent("pom.xml", "<artifactId>a</artifactId>"));

    var stack = productionResolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(stack.contains("Maven artifacts: a"));
    // The freshly cached entry is served on the next call (System clock is within TTL)
    productionResolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);
    verify(prClient, times(ProjectStackResolver.MANIFEST_PATHS.size()))
        .getFileContent(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void sweepShouldNotRunBelowThreshold() {
    resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);
    currentTimeMs.addAndGet(ProjectStackResolver.CACHE_TTL_MS + 1);

    resolver.sweepExpiredEntries();

    assertEquals(1, resolver.cache.size());
  }
}
