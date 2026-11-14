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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thiagogonzaga.thrillhousebot.CapturedLog;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.HttpException;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ProjectStackResolverTest {

  @Mock private io.quarkiverse.githubapp.GitHubClientProvider clientProvider;
  @Mock private org.kohsuke.github.GitHub gitHub;
  @Mock private org.kohsuke.github.GHRepository repository;

  private final AtomicLong currentTimeMs = new AtomicLong(1_000_000L);

  private ProjectStackResolver resolver;

  private static final String OWNER = "test-owner";
  private static final String REPO = "test-repo";
  private static final String DEFAULT_BRANCH = "main";
  private static final long INSTALLATION_ID = 42L;
  private static final String AUTH_HEADER = "Bearer test-jwt";
  private static final String ACCEPT = "application/vnd.github+json";

  @BeforeEach
  void setUp() throws Exception {
    MockitoAnnotations.openMocks(this);
    when(clientProvider.getInstallationClient(INSTALLATION_ID)).thenReturn(gitHub);
    when(gitHub.getRepository(anyString())).thenReturn(repository);
    when(repository.getFileContent(anyString(), anyString()))
        .thenThrow(new GHFileNotFoundException("not found"));
    resolver = new ProjectStackResolver(clientProvider, new ObjectMapper(), currentTimeMs::get);
  }

  private org.kohsuke.github.GHContent makeFileContent(String name, String content)
      throws Exception {
    var mock = org.mockito.Mockito.mock(org.kohsuke.github.GHContent.class);
    org.mockito.Mockito.doReturn(content).when(mock).getContent();
    return mock;
  }

  private void stubManifest(String path, org.kohsuke.github.GHContent content) throws Exception {
    doReturn(content).when(repository).getFileContent(path, DEFAULT_BRANCH);
  }

  @Test
  void shouldSummarizePomArtifacts() throws Exception {
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
  void shouldSummarizePackageJsonDependencies() throws Exception {
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
  void shouldSummarizeGradleDependencyLines() throws Exception {
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
  void shouldIgnoreUnterminatedAndEmptyMavenArtifactTags() throws Exception {
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
  void shouldNotTreatGradlePrefixWordsAsDependencyLines() throws Exception {
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
  void shouldFallBackToRawExcerptForOtherManifests() throws Exception {
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
  void shouldFallBackToRawExcerptForUnparseablePackageJson() throws Exception {
    stubManifest("package.json", makeFileContent("package.json", "not json {"));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(stack.contains("### package.json"));
    assertTrue(stack.contains("not json {"));
  }

  @Test
  void shouldFallBackToRawExcerptForPomWithoutArtifactIds() throws Exception {
    stubManifest("pom.xml", makeFileContent("pom.xml", "<project><groupId>g</groupId></project>"));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertFalse(stack.contains("Maven artifacts:"));
    assertTrue(stack.contains("<project><groupId>g</groupId></project>"));
  }

  @Test
  void shouldFallBackToRawExcerptForPackageJsonWithoutDependencies() throws Exception {
    stubManifest("package.json", makeFileContent("package.json", "{\"name\": \"frontend\"}"));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertFalse(stack.contains("npm packages:"));
    assertTrue(stack.contains("{\"name\": \"frontend\"}"));
  }

  @Test
  void shouldTruncateOversizedSections() throws Exception {
    stubManifest(
        "go.mod",
        makeFileContent(
            "go.mod", "x".repeat(ProjectStackResolver.MAX_SECTION_CHARS + 100) + "\nend"));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(stack.contains("… (truncated)"));
    assertFalse(stack.contains("end"));
  }

  @Test
  void shouldCapManifestCount() throws Exception {
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
  void shouldReturnEmptyWhenNoManifestFound() throws Exception {
    assertEquals("", resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID));
  }

  @Test
  void shouldReturnHub4jDecodedContentAsIs() throws Exception {
    var content = "<artifactId>quarkus-arc</artifactId>\n".repeat(10);
    stubManifest("pom.xml", makeFileContent("pom.xml", content));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(stack.contains("Maven artifacts: quarkus-arc"));
  }

  @Test
  void shouldSkipManifestWithNullContentAndContinueProbing() throws Exception {
    doReturn(null).when(repository).getFileContent("pom.xml", DEFAULT_BRANCH);
    stubManifest("build.gradle", makeFileContent("build.gradle", "implementation 'x:y:1'"));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertFalse(stack.contains("### pom.xml"));
    assertTrue(stack.contains("### build.gradle"));
  }

  @Test
  void shouldSkipManifestWithNullDecodedContent() throws Exception {
    var nullContent = org.mockito.Mockito.mock(org.kohsuke.github.GHContent.class);
    org.mockito.Mockito.doReturn(null).when(nullContent).getContent();
    doReturn(nullContent).when(repository).getFileContent("pom.xml", DEFAULT_BRANCH);
    stubManifest("build.gradle", makeFileContent("build.gradle", "implementation 'x:y:1'"));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertFalse(stack.contains("### pom.xml"));
    assertTrue(stack.contains("### build.gradle"));
  }

  @Test
  void shouldSkipManifestWithBlankDecodedContent() throws Exception {
    stubManifest("pom.xml", makeFileContent("pom.xml", "   "));
    stubManifest("build.gradle", makeFileContent("build.gradle", "implementation 'x:y:1'"));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertFalse(stack.contains("### pom.xml"));
    assertTrue(stack.contains("### build.gradle"));
  }

  @Test
  void aManifestWhoseContentCannotBeReadIsSkippedAndNotCached() throws Exception {
    var bad = org.mockito.Mockito.mock(org.kohsuke.github.GHContent.class);
    org.mockito.Mockito.doThrow(new java.io.IOException("bad")).when(bad).getContent();
    doReturn(bad).when(repository).getFileContent("pom.xml", DEFAULT_BRANCH);

    assertEquals("", resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID));
    assertEquals("", resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID));

    verify(repository, times(2)).getFileContent("pom.xml", DEFAULT_BRANCH);
  }

  @Test
  void aTransientFailureOnOneManifestIsNotCachedAndSkipsOnlyThatManifest() throws Exception {
    doThrow(
            new org.kohsuke.github.HttpException(
                "server error", 503, "Service Unavailable", "https://api.github.com"))
        .when(repository)
        .getFileContent("pom.xml", DEFAULT_BRANCH);
    stubManifest("build.gradle", makeFileContent("build.gradle", "implementation 'x:y:1'"));

    var first = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);
    assertFalse(first.contains("### pom.xml"));
    assertTrue(first.contains("### build.gradle"));

    var second = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertEquals(first, second);
    verify(repository, times(2)).getFileContent("pom.xml", DEFAULT_BRANCH);
  }

  @Test
  void shouldCacheStackAndNotHitApiOnSecondCall() throws Exception {
    stubManifest("pom.xml", makeFileContent("pom.xml", "<artifactId>a</artifactId>"));

    var first = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);
    var second = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertEquals(first, second);
    verify(repository, times(ProjectStackResolver.MANIFEST_PATHS.size()))
        .getFileContent(anyString(), anyString());
  }

  @Test
  void shouldRefetchAfterCacheExpiry() throws Exception {
    stubManifest("pom.xml", makeFileContent("pom.xml", "<artifactId>a</artifactId>"));
    resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    currentTimeMs.addAndGet(ProjectStackResolver.CACHE_TTL_MS + 1);
    stubManifest("pom.xml", makeFileContent("pom.xml", "<artifactId>b</artifactId>"));

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(stack.contains("Maven artifacts: b"));
  }

  @Test
  void sweepShouldEvictExpiredEntriesAboveThreshold() throws Exception {
    for (var i = 0; i < ProjectStackResolver.CACHE_SWEEP_THRESHOLD; i++) {
      resolver.resolve(OWNER, "repo-" + i, DEFAULT_BRANCH, INSTALLATION_ID);
    }
    assertEquals(ProjectStackResolver.CACHE_SWEEP_THRESHOLD, resolver.cache.size());

    currentTimeMs.addAndGet(ProjectStackResolver.CACHE_TTL_MS + 1);
    resolver.resolve(OWNER, "fresh-repo", DEFAULT_BRANCH, INSTALLATION_ID);

    assertEquals(1, resolver.cache.size());
  }

  @Test
  void productionConstructorShouldResolveWithSystemClock() throws Exception {
    var productionResolver = new ProjectStackResolver(clientProvider, new ObjectMapper());
    stubManifest("pom.xml", makeFileContent("pom.xml", "<artifactId>a</artifactId>"));

    var stack = productionResolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(stack.contains("Maven artifacts: a"));
    // The freshly cached entry is served on the next call (System clock is within TTL)
    productionResolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);
    verify(repository, times(ProjectStackResolver.MANIFEST_PATHS.size()))
        .getFileContent(anyString(), anyString());
  }

  @Test
  void sweepShouldNotRunBelowThreshold() throws Exception {
    resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);
    currentTimeMs.addAndGet(ProjectStackResolver.CACHE_TTL_MS + 1);

    resolver.sweepExpiredEntries();

    assertEquals(1, resolver.cache.size());
  }

  @Test
  void aFailedRepositoryLookupIsLoggedAtWarnWithItsError() throws Exception {
    // #1355: a 5xx or rate limit on the lookup drops this context from the review, so it must show.
    doThrow(new HttpException("unavailable", 503, "Service Unavailable", "https://api.github.com"))
        .when(gitHub)
        .getRepository(anyString());

    try (var log = CapturedLog.of(ProjectStackResolver.class)) {
      assertEquals("", resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID));

      var warnings = log.at(Level.WARNING);
      assertEquals(1, warnings.size(), warnings.toString());
      assertTrue(
          warnings.getFirst().contains(OWNER)
              && warnings.getFirst().contains(REPO)
              && warnings.getFirst().contains("unavailable"),
          warnings.getFirst());
    }
  }

  @Test
  void aRepositoryThatIsNotFoundIsLoggedAtDebugOnly() throws Exception {
    doThrow(new GHFileNotFoundException("Not Found")).when(gitHub).getRepository(anyString());

    try (var log = CapturedLog.of(ProjectStackResolver.class)) {
      assertEquals("", resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID));

      assertEquals(List.of(), log.at(Level.WARNING));
    }
  }

  @Test
  void repositoryResolutionFailureReturnsEmpty() throws Exception {
    org.mockito.Mockito.doThrow(new java.io.IOException("boom"))
        .when(gitHub)
        .getRepository(org.mockito.ArgumentMatchers.anyString());

    var stack = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertEquals("", stack);
  }

  @Test
  void installationClientFailureReturnsEmptyWithoutThrowing() throws Exception {
    doThrow(new IllegalStateException("installation client unavailable"))
        .when(clientProvider)
        .getInstallationClient(INSTALLATION_ID);

    assertEquals(
        "",
        assertDoesNotThrow(() -> resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID)));
  }
}
