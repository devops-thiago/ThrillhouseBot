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

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHFileNotFoundException;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class InstructionsResolverTest {

  @Mock private io.quarkiverse.githubapp.GitHubClientProvider clientProvider;

  @Mock private org.kohsuke.github.GitHub gitHub;

  @Mock private org.kohsuke.github.GHRepository repository;

  @Mock private ThrillhouseConfig config;

  @Mock private ThrillhouseConfig.ReviewConfig reviewConfig;

  private final AtomicLong currentTimeMs = new AtomicLong(1_000_000L);

  private InstructionsResolver resolver;

  private static final String OWNER = "test-owner";
  private static final String REPO = "test-repo";
  private static final String DEFAULT_BRANCH = "main";
  private static final long INSTALLATION_ID = 42L;
  private static final String AUTH_HEADER = "Bearer test-jwt";

  @BeforeEach
  void setUp() throws Exception {
    MockitoAnnotations.openMocks(this);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.instructionsFile()).thenReturn(".github/thrillhousebot.md");
    when(clientProvider.getInstallationClient(INSTALLATION_ID)).thenReturn(gitHub);
    when(gitHub.getRepository(anyString())).thenReturn(repository);
    resolver = new InstructionsResolver(config, clientProvider, currentTimeMs::get);
  }

  private org.kohsuke.github.GHContent makeFileContent(String content) throws Exception {
    var mock = org.mockito.Mockito.mock(org.kohsuke.github.GHContent.class);
    org.mockito.Mockito.doReturn(content).when(mock).getContent();
    org.mockito.Mockito.doReturn(true).when(mock).isFile();
    return mock;
  }

  @Test
  void shouldResolveFirstFileInFallbackChain() throws Exception {
    // thrillhousebot.md (first in chain) returns 404, copilot-instructions.md (second) succeeds
    doThrow(new GHFileNotFoundException("not found"))
        .when(repository)
        .getFileContent(".github/thrillhousebot.md", DEFAULT_BRANCH);

    doReturn(makeFileContent("# Copilot Instructions"))
        .when(repository)
        .getFileContent(".github/copilot-instructions.md", DEFAULT_BRANCH);

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(result.isPresent());
    assertEquals("# Copilot Instructions", result.content());
    assertEquals(".github/copilot-instructions.md", result.source());
  }

  @Test
  void shouldSkipNullFileContent() throws Exception {
    doReturn(null).when(repository).getFileContent(".github/thrillhousebot.md", DEFAULT_BRANCH);
    doReturn(makeFileContent("# Copilot Instructions"))
        .when(repository)
        .getFileContent(".github/copilot-instructions.md", DEFAULT_BRANCH);

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(result.isPresent());
    assertEquals("# Copilot Instructions", result.content());
  }

  @Test
  void shouldSkipNonFileContent() throws Exception {
    var nonFile = org.mockito.Mockito.mock(org.kohsuke.github.GHContent.class);
    org.mockito.Mockito.doReturn(false).when(nonFile).isFile();
    doReturn(nonFile).when(repository).getFileContent(".github/thrillhousebot.md", DEFAULT_BRANCH);
    doReturn(makeFileContent("# Copilot Instructions"))
        .when(repository)
        .getFileContent(".github/copilot-instructions.md", DEFAULT_BRANCH);

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(result.isPresent());
    assertEquals("# Copilot Instructions", result.content());
  }

  @Test
  void shouldResolveThrillhousebotMd() throws Exception {
    doReturn(makeFileContent("Custom bot instructions"))
        .when(repository)
        .getFileContent(".github/thrillhousebot.md", DEFAULT_BRANCH);

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(result.isPresent());
    assertEquals("Custom bot instructions", result.content());
    assertEquals(".github/thrillhousebot.md", result.source());
  }

  @Test
  void shouldTryClaudeMdAsFallback() throws Exception {
    // First two in chain fail (404), CLAUDE.md succeeds
    doThrow(new GHFileNotFoundException("not found"))
        .when(repository)
        .getFileContent(".github/thrillhousebot.md", DEFAULT_BRANCH);

    doThrow(new GHFileNotFoundException("not found"))
        .when(repository)
        .getFileContent(".github/copilot-instructions.md", DEFAULT_BRANCH);

    doReturn(makeFileContent("# CLAUDE.md content"))
        .when(repository)
        .getFileContent("CLAUDE.md", DEFAULT_BRANCH);

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(result.isPresent());
    assertEquals("# CLAUDE.md content", result.content());
    assertEquals("CLAUDE.md", result.source());
  }

  @Test
  void shouldReturnEmptyWhenNoFilesFound() throws Exception {
    // All files throw 404
    when(repository.getFileContent(anyString(), anyString()))
        .thenThrow(new GHFileNotFoundException("not found"));

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertFalse(result.isPresent());
    assertEquals("", result.content());
    assertEquals("none", result.source());
    assertEquals(InstructionsResolver.ResolvedInstructions.EMPTY, result);
  }

  @Test
  void shouldCacheResultAndNotHitApiOnSecondCall() throws Exception {
    doReturn(makeFileContent("Cached content"))
        .when(repository)
        .getFileContent(".github/thrillhousebot.md", DEFAULT_BRANCH);

    // First call — hits API
    var first = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);
    assertEquals("Cached content", first.content());

    // Second call — should use cache, not hit API
    var second = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);
    assertEquals("Cached content", second.content());

    // API should only be called once per file path (only the first one since it succeeded)
    verify(repository, times(1)).getFileContent(anyString(), anyString());
  }

  @Test
  void shouldRemoveExpiredCacheEntryBeforeRefetch() throws Exception {
    doReturn(makeFileContent("Cached content"))
        .when(repository)
        .getFileContent(".github/thrillhousebot.md", DEFAULT_BRANCH);

    resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);
    assertEquals(1, resolver.cache.size());

    currentTimeMs.addAndGet(InstructionsResolver.CACHE_TTL_MS + 1);

    doReturn(makeFileContent("Refreshed content"))
        .when(repository)
        .getFileContent(".github/thrillhousebot.md", DEFAULT_BRANCH);

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertEquals("Refreshed content", result.content());
    assertEquals(1, resolver.cache.size());
    verify(repository, times(2)).getFileContent(anyString(), anyString());
  }

  @Test
  void shouldRefetchAfterCacheExpiry() throws Exception {
    doReturn(makeFileContent("Fresh content"))
        .when(repository)
        .getFileContent(".github/thrillhousebot.md", DEFAULT_BRANCH);

    // First call — populate cache
    resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    // Advance the clock just past the cache TTL so the entry expires
    currentTimeMs.addAndGet(InstructionsResolver.CACHE_TTL_MS + 1);

    // Second call — should refetch since the cached entry is expired
    doReturn(makeFileContent("Refreshed content"))
        .when(repository)
        .getFileContent(".github/thrillhousebot.md", DEFAULT_BRANCH);

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertEquals("Refreshed content", result.content());

    // API should have been called twice total
    verify(repository, times(2)).getFileContent(anyString(), anyString());
  }

  @Test
  void shouldCacheNegativeResultForSeparateRepos() throws Exception {
    var repositoryA = mock(org.kohsuke.github.GHRepository.class);
    var repositoryB = mock(org.kohsuke.github.GHRepository.class);
    doReturn(repositoryA).when(gitHub).getRepository("owner-a/repo-a");
    doReturn(repositoryB).when(gitHub).getRepository("owner-a/repo-b");
    doThrow(new GHFileNotFoundException("not found"))
        .when(repositoryA)
        .getFileContent(anyString(), anyString());
    doReturn(makeFileContent("Repo B instructions"))
        .when(repositoryB)
        .getFileContent(".github/thrillhousebot.md", DEFAULT_BRANCH);

    var resultA = resolver.resolve("owner-a", "repo-a", DEFAULT_BRANCH, INSTALLATION_ID);
    assertFalse(resultA.isPresent());

    var resultB = resolver.resolve("owner-a", "repo-b", DEFAULT_BRANCH, INSTALLATION_ID);
    assertTrue(resultB.isPresent());
    assertEquals("Repo B instructions", resultB.content());
  }

  @Test
  void shouldReturnHub4jDecodedContentAsIs() throws Exception {
    var content = "# Instructions\n".repeat(20);
    doReturn(makeFileContent(content))
        .when(repository)
        .getFileContent(".github/thrillhousebot.md", DEFAULT_BRANCH);

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertEquals(content, result.content());
  }

  @Test
  void aServerErrorIsAFailedReadAndIsNotCached() throws Exception {
    doThrow(
            new org.kohsuke.github.HttpException(
                "server error", 503, "Service Unavailable", "https://api.github.com"))
        .when(repository)
        .getFileContent(anyString(), anyString());

    assertEquals(
        InstructionsResolver.ResolvedInstructions.EMPTY,
        resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID));
    assertEquals(
        InstructionsResolver.ResolvedInstructions.EMPTY,
        resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID));

    assertEquals(0, resolver.cache.size(), "a 503 said nothing about the repository");
    verify(repository, times(2 * resolver.fallbackChain().size()))
        .getFileContent(anyString(), anyString());
  }

  @Test
  void shouldRefetchAfterNegativeCacheExpiry() throws Exception {
    when(repository.getFileContent(anyString(), anyString()))
        .thenThrow(new GHFileNotFoundException("not found"));

    // First call — every fallback path 404s, negative result is cached
    assertFalse(resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID).isPresent());
    verify(repository, times(5)).getFileContent(anyString(), anyString());

    // Still inside the 60s negative-cache window — no new API calls
    currentTimeMs.addAndGet(59_999);
    assertFalse(resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID).isPresent());
    verify(repository, times(5)).getFileContent(anyString(), anyString());

    // Past the negative-cache TTL — the resolver retries and finds a newly added file
    currentTimeMs.addAndGet(2);
    doReturn(makeFileContent("Added later"))
        .when(repository)
        .getFileContent(".github/thrillhousebot.md", DEFAULT_BRANCH);

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertEquals("Added later", result.content());
  }

  @Test
  void shouldExposeFallbackChainWithConfiguredFileFirst() throws Exception {
    assertEquals(".github/thrillhousebot.md", resolver.fallbackChain().get(0));
  }

  @Test
  void shouldUseDefaultFallbackChainWhenInstructionsFileIsNull() throws Exception {
    when(reviewConfig.instructionsFile()).thenReturn(null);
    resolver = new InstructionsResolver(config, clientProvider, currentTimeMs::get);

    assertEquals(".github/thrillhousebot.md", resolver.fallbackChain().get(0));
    assertEquals(5, resolver.fallbackChain().size());
  }

  @Test
  void shouldOmitBlankConfiguredInstructionsFileFromFallbackChain() throws Exception {
    when(reviewConfig.instructionsFile()).thenReturn("   ");
    resolver = new InstructionsResolver(config, clientProvider, currentTimeMs::get);

    assertEquals(".github/thrillhousebot.md", resolver.fallbackChain().get(0));
    assertEquals(5, resolver.fallbackChain().size());
  }

  @Test
  void shouldResolveConfiguredInstructionsFileFirst() throws Exception {
    when(reviewConfig.instructionsFile()).thenReturn("docs/CUSTOM.md");
    resolver = new InstructionsResolver(config, clientProvider, currentTimeMs::get);

    doReturn(makeFileContent("# Custom instructions"))
        .when(repository)
        .getFileContent("docs/CUSTOM.md", DEFAULT_BRANCH);

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertEquals("# Custom instructions", result.content());
    assertEquals("docs/CUSTOM.md", result.source());
    verify(repository, times(1)).getFileContent("docs/CUSTOM.md", DEFAULT_BRANCH);
    verify(repository, never())
        .getFileContent(argThat(path -> !"docs/CUSTOM.md".equals(path)), anyString());
  }

  @Test
  void productionConstructorShouldResolveWithSystemClock() throws Exception {
    var productionResolver = new InstructionsResolver(config, clientProvider);

    doReturn(makeFileContent("Prod instructions"))
        .when(repository)
        .getFileContent(".github/thrillhousebot.md", DEFAULT_BRANCH);

    var result = productionResolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertEquals("Prod instructions", result.content());

    // The freshly cached entry is served on the next call (System clock is within TTL)
    productionResolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);
    verify(repository, times(1)).getFileContent(anyString(), anyString());
  }

  @Test
  void sweepShouldEvictExpiredEntriesAboveThreshold() throws Exception {
    doReturn(makeFileContent("instructions"))
        .when(repository)
        .getFileContent(anyString(), anyString());
    for (var i = 0; i < InstructionsResolver.CACHE_SWEEP_THRESHOLD; i++) {
      resolver.resolve(OWNER, "repo-" + i, DEFAULT_BRANCH, INSTALLATION_ID);
    }
    assertEquals(InstructionsResolver.CACHE_SWEEP_THRESHOLD, resolver.cache.size());

    // Expire everything; the put for a new repo crosses the threshold and triggers the sweep
    currentTimeMs.addAndGet(InstructionsResolver.CACHE_TTL_MS + 1);
    resolver.resolve(OWNER, "fresh-repo", DEFAULT_BRANCH, INSTALLATION_ID);

    assertEquals(1, resolver.cache.size());
  }

  @Test
  void sweepShouldNotRunBelowThreshold() throws Exception {
    doReturn(makeFileContent("instructions"))
        .when(repository)
        .getFileContent(anyString(), anyString());
    resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);
    currentTimeMs.addAndGet(InstructionsResolver.CACHE_TTL_MS + 1);

    resolver.sweepExpiredEntries();

    // Below the threshold the expired entry is left for evict-on-read to replace
    assertEquals(1, resolver.cache.size());
  }

  @Test
  void repositoryResolutionFailureReturnsEmpty() throws Exception {
    org.mockito.Mockito.doThrow(new java.io.IOException("boom"))
        .when(gitHub)
        .getRepository(org.mockito.ArgumentMatchers.anyString());

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertFalse(result.isPresent());
  }

  @Test
  void installationClientFailureReturnsEmptyWithoutThrowing() throws Exception {
    doThrow(new IllegalStateException("installation client unavailable"))
        .when(clientProvider)
        .getInstallationClient(INSTALLATION_ID);

    var result =
        assertDoesNotThrow(() -> resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID));

    assertEquals(InstructionsResolver.ResolvedInstructions.EMPTY, result);
  }

  @Test
  void nullContentIsSkipped() throws Exception {
    var file = org.mockito.Mockito.mock(org.kohsuke.github.GHContent.class);
    org.mockito.Mockito.doReturn(null).when(file).getContent();
    org.mockito.Mockito.doReturn(true).when(file).isFile();
    org.mockito.Mockito.doReturn(file).when(repository).getFileContent(anyString(), anyString());

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertFalse(result.isPresent());
  }
}
