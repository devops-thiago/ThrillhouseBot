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
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class InstructionsResolverTest {

  @Mock private GitHubAuthClient authClient;

  @Mock private GitHubPullRequestClient prClient;

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
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(config.review()).thenReturn(reviewConfig);
    when(reviewConfig.instructionsFile()).thenReturn(".github/thrillhousebot.md");
    when(authClient.getAuthHeader(INSTALLATION_ID)).thenReturn(AUTH_HEADER);
    resolver = new InstructionsResolver(config, authClient, prClient, currentTimeMs::get);
  }

  private GitHubPullRequestClient.FileContent makeFileContent(String content) {
    var encoded =
        Base64.getEncoder()
            .encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return new GitHubPullRequestClient.FileContent(
        "file.md", "path/to/file.md", encoded, "base64", content.length());
  }

  @Test
  void shouldResolveFirstFileInFallbackChain() {
    // thrillhousebot.md (first in chain) returns 404, copilot-instructions.md (second) succeeds
    when(prClient.getFileContent(
            AUTH_HEADER,
            "application/vnd.github+json",
            OWNER,
            REPO,
            ".github/thrillhousebot.md",
            DEFAULT_BRANCH))
        .thenThrow(new NotFoundException(Response.status(404).build()));

    when(prClient.getFileContent(
            AUTH_HEADER,
            "application/vnd.github+json",
            OWNER,
            REPO,
            ".github/copilot-instructions.md",
            DEFAULT_BRANCH))
        .thenReturn(makeFileContent("# Copilot Instructions"));

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(result.isPresent());
    assertEquals("# Copilot Instructions", result.content());
    assertEquals(".github/copilot-instructions.md", result.source());
  }

  @Test
  void shouldResolveThrillhousebotMd() {
    when(prClient.getFileContent(
            AUTH_HEADER,
            "application/vnd.github+json",
            OWNER,
            REPO,
            ".github/thrillhousebot.md",
            DEFAULT_BRANCH))
        .thenReturn(makeFileContent("Custom bot instructions"));

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(result.isPresent());
    assertEquals("Custom bot instructions", result.content());
    assertEquals(".github/thrillhousebot.md", result.source());
  }

  @Test
  void shouldTryClaudeMdAsFallback() {
    // First two in chain fail (404), CLAUDE.md succeeds
    when(prClient.getFileContent(
            AUTH_HEADER,
            "application/vnd.github+json",
            OWNER,
            REPO,
            ".github/thrillhousebot.md",
            DEFAULT_BRANCH))
        .thenThrow(new NotFoundException(Response.status(404).build()));

    when(prClient.getFileContent(
            AUTH_HEADER,
            "application/vnd.github+json",
            OWNER,
            REPO,
            ".github/copilot-instructions.md",
            DEFAULT_BRANCH))
        .thenThrow(new NotFoundException(Response.status(404).build()));

    when(prClient.getFileContent(
            AUTH_HEADER, "application/vnd.github+json", OWNER, REPO, "CLAUDE.md", DEFAULT_BRANCH))
        .thenReturn(makeFileContent("# CLAUDE.md content"));

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertTrue(result.isPresent());
    assertEquals("# CLAUDE.md content", result.content());
    assertEquals("CLAUDE.md", result.source());
  }

  @Test
  void shouldReturnEmptyWhenNoFilesFound() {
    // All files throw 404
    when(prClient.getFileContent(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new NotFoundException(Response.status(404).build()));

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertFalse(result.isPresent());
    assertEquals("", result.content());
    assertEquals("none", result.source());
    assertEquals(InstructionsResolver.ResolvedInstructions.EMPTY, result);
  }

  @Test
  void shouldCacheResultAndNotHitApiOnSecondCall() {
    when(prClient.getFileContent(
            AUTH_HEADER,
            "application/vnd.github+json",
            OWNER,
            REPO,
            ".github/thrillhousebot.md",
            DEFAULT_BRANCH))
        .thenReturn(makeFileContent("Cached content"));

    // First call — hits API
    var first = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);
    assertEquals("Cached content", first.content());

    // Second call — should use cache, not hit API
    var second = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);
    assertEquals("Cached content", second.content());

    // API should only be called once per file path (only the first one since it succeeded)
    verify(prClient, times(1))
        .getFileContent(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void shouldRemoveExpiredCacheEntryBeforeRefetch() {
    when(prClient.getFileContent(
            AUTH_HEADER,
            "application/vnd.github+json",
            OWNER,
            REPO,
            ".github/thrillhousebot.md",
            DEFAULT_BRANCH))
        .thenReturn(makeFileContent("Cached content"));

    resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);
    assertEquals(1, resolver.cache.size());

    currentTimeMs.addAndGet(InstructionsResolver.CACHE_TTL_MS + 1);

    when(prClient.getFileContent(
            AUTH_HEADER,
            "application/vnd.github+json",
            OWNER,
            REPO,
            ".github/thrillhousebot.md",
            DEFAULT_BRANCH))
        .thenReturn(makeFileContent("Refreshed content"));

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertEquals("Refreshed content", result.content());
    assertEquals(1, resolver.cache.size());
    verify(prClient, times(2))
        .getFileContent(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void shouldRefetchAfterCacheExpiry() {
    when(prClient.getFileContent(
            AUTH_HEADER,
            "application/vnd.github+json",
            OWNER,
            REPO,
            ".github/thrillhousebot.md",
            DEFAULT_BRANCH))
        .thenReturn(makeFileContent("Fresh content"));

    // First call — populate cache
    resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    // Advance the clock just past the cache TTL so the entry expires
    currentTimeMs.addAndGet(InstructionsResolver.CACHE_TTL_MS + 1);

    // Second call — should refetch since the cached entry is expired
    when(prClient.getFileContent(
            AUTH_HEADER,
            "application/vnd.github+json",
            OWNER,
            REPO,
            ".github/thrillhousebot.md",
            DEFAULT_BRANCH))
        .thenReturn(makeFileContent("Refreshed content"));

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertEquals("Refreshed content", result.content());

    // API should have been called twice total
    verify(prClient, times(2))
        .getFileContent(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void shouldCacheNegativeResultForSeparateRepos() {
    // No files found for repo A
    when(prClient.getFileContent(
            eq(AUTH_HEADER),
            eq("application/vnd.github+json"),
            eq("owner-a"),
            eq("repo-a"),
            anyString(),
            eq(DEFAULT_BRANCH)))
        .thenThrow(new NotFoundException(Response.status(404).build()));

    // thrillhousebot.md found for repo B
    when(prClient.getFileContent(
            AUTH_HEADER,
            "application/vnd.github+json",
            "owner-b",
            "repo-b",
            ".github/thrillhousebot.md",
            DEFAULT_BRANCH))
        .thenReturn(makeFileContent("Repo B instructions"));

    var resultA = resolver.resolve("owner-a", "repo-a", DEFAULT_BRANCH, INSTALLATION_ID);
    assertFalse(resultA.isPresent());

    var resultB = resolver.resolve("owner-b", "repo-b", DEFAULT_BRANCH, INSTALLATION_ID);
    assertTrue(resultB.isPresent());
    assertEquals("Repo B instructions", resultB.content());
  }

  @Test
  void shouldDecodeMimeWrappedBase64() {
    // GitHub's contents API wraps base64 in newlines for any file beyond one base64 line
    var content = "# Instructions\n".repeat(20);
    var wrapped =
        Base64.getMimeEncoder(60, "\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertTrue(wrapped.contains("\n"));
    when(prClient.getFileContent(
            AUTH_HEADER,
            "application/vnd.github+json",
            OWNER,
            REPO,
            ".github/thrillhousebot.md",
            DEFAULT_BRANCH))
        .thenReturn(
            new GitHubPullRequestClient.FileContent(
                "thrillhousebot.md",
                ".github/thrillhousebot.md",
                wrapped,
                "base64",
                content.length()));

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertEquals(content, result.content());
  }

  @Test
  void shouldHandleWebApplicationException() {
    when(prClient.getFileContent(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new WebApplicationException(Response.status(500).build()));

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertFalse(result.isPresent());
  }

  @Test
  void shouldRefetchAfterNegativeCacheExpiry() {
    when(prClient.getFileContent(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new NotFoundException(Response.status(404).build()));

    // First call — every fallback path 404s, negative result is cached
    assertFalse(resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID).isPresent());
    verify(prClient, times(5))
        .getFileContent(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

    // Still inside the 60s negative-cache window — no new API calls
    currentTimeMs.addAndGet(59_999);
    assertFalse(resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID).isPresent());
    verify(prClient, times(5))
        .getFileContent(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

    // Past the negative-cache TTL — the resolver retries and finds a newly added file
    currentTimeMs.addAndGet(2);
    doReturn(makeFileContent("Added later"))
        .when(prClient)
        .getFileContent(
            AUTH_HEADER,
            "application/vnd.github+json",
            OWNER,
            REPO,
            ".github/thrillhousebot.md",
            DEFAULT_BRANCH);

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertEquals("Added later", result.content());
  }

  @Test
  void shouldExposeFallbackChainWithConfiguredFileFirst() {
    assertEquals(".github/thrillhousebot.md", resolver.fallbackChain().get(0));
  }

  @Test
  void shouldUseDefaultFallbackChainWhenInstructionsFileIsNull() {
    when(reviewConfig.instructionsFile()).thenReturn(null);
    resolver = new InstructionsResolver(config, authClient, prClient, currentTimeMs::get);

    assertEquals(".github/thrillhousebot.md", resolver.fallbackChain().get(0));
    assertEquals(5, resolver.fallbackChain().size());
  }

  @Test
  void shouldOmitBlankConfiguredInstructionsFileFromFallbackChain() {
    when(reviewConfig.instructionsFile()).thenReturn("   ");
    resolver = new InstructionsResolver(config, authClient, prClient, currentTimeMs::get);

    assertEquals(".github/thrillhousebot.md", resolver.fallbackChain().get(0));
    assertEquals(5, resolver.fallbackChain().size());
  }

  @Test
  void shouldResolveConfiguredInstructionsFileFirst() {
    when(reviewConfig.instructionsFile()).thenReturn("docs/CUSTOM.md");
    resolver = new InstructionsResolver(config, authClient, prClient, currentTimeMs::get);

    when(prClient.getFileContent(
            AUTH_HEADER,
            "application/vnd.github+json",
            OWNER,
            REPO,
            "docs/CUSTOM.md",
            DEFAULT_BRANCH))
        .thenReturn(makeFileContent("# Custom instructions"));

    var result = resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertEquals("# Custom instructions", result.content());
    assertEquals("docs/CUSTOM.md", result.source());
    verify(prClient, never())
        .getFileContent(
            AUTH_HEADER,
            "application/vnd.github+json",
            OWNER,
            REPO,
            ".github/thrillhousebot.md",
            DEFAULT_BRANCH);
  }

  @Test
  void productionConstructorShouldResolveWithSystemClock() {
    var productionResolver = new InstructionsResolver(config, authClient, prClient);

    when(prClient.getFileContent(
            AUTH_HEADER,
            "application/vnd.github+json",
            OWNER,
            REPO,
            ".github/thrillhousebot.md",
            DEFAULT_BRANCH))
        .thenReturn(makeFileContent("Prod instructions"));

    var result = productionResolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);

    assertEquals("Prod instructions", result.content());

    // The freshly cached entry is served on the next call (System clock is within TTL)
    productionResolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);
    verify(prClient, times(1))
        .getFileContent(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void sweepShouldEvictExpiredEntriesAboveThreshold() {
    when(prClient.getFileContent(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(makeFileContent("instructions"));
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
  void sweepShouldNotRunBelowThreshold() {
    when(prClient.getFileContent(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(makeFileContent("instructions"));
    resolver.resolve(OWNER, REPO, DEFAULT_BRANCH, INSTALLATION_ID);
    currentTimeMs.addAndGet(InstructionsResolver.CACHE_TTL_MS + 1);

    resolver.sweepExpiredEntries();

    // Below the threshold the expired entry is left for evict-on-read to replace
    assertEquals(1, resolver.cache.size());
  }
}
