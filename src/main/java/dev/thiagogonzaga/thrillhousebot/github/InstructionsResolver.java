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
import jakarta.ws.rs.WebApplicationException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class InstructionsResolver {

  private static final Logger log = LoggerFactory.getLogger(InstructionsResolver.class);
  private static final String ACCEPT_HEADER = "application/vnd.github+json";

  /** Default fallback chain when the configured instructions file is not found. */
  private static final List<String> DEFAULT_FALLBACK_CHAIN =
      List.of(
          ".github/thrillhousebot.md",
          ".github/copilot-instructions.md",
          "CLAUDE.md",
          "AGENTS.md",
          "AGENT.md");

  private final List<String> fallbackChain;
  private final GitHubAuthClient authClient;
  private final GitHubPullRequestClient prClient;

  private record CachedInstructions(String content, String source, long expiresAt) {}

  // Package-private for tests.
  final ConcurrentHashMap<String, CachedInstructions> cache = new ConcurrentHashMap<>();
  static final long CACHE_TTL_MS = 5L * 60 * 1000; // 5 minutes
  static final int CACHE_SWEEP_THRESHOLD = 1_000;

  private final LongSupplier clock;

  @Inject
  public InstructionsResolver(
      ThrillhouseConfig config,
      GitHubAuthClient authClient,
      @RestClient GitHubPullRequestClient prClient) {
    this(config, authClient, prClient, System::currentTimeMillis);
  }

  /** Visible for tests: allows controlling the cache clock. */
  InstructionsResolver(
      ThrillhouseConfig config,
      GitHubAuthClient authClient,
      GitHubPullRequestClient prClient,
      LongSupplier clock) {
    this.fallbackChain = buildFallbackChain(config);
    this.authClient = authClient;
    this.prClient = prClient;
    this.clock = clock;
  }

  List<String> fallbackChain() {
    return fallbackChain;
  }

  private static List<String> buildFallbackChain(ThrillhouseConfig config) {
    var chain = new ArrayList<String>();
    var configured = config.review().instructionsFile();
    if (configured != null && !configured.isBlank()) {
      chain.add(configured.trim());
    }
    for (String fallback : DEFAULT_FALLBACK_CHAIN) {
      if (!chain.contains(fallback)) {
        chain.add(fallback);
      }
    }
    return List.copyOf(chain);
  }

  /**
   * Resolves the instructions file for a repository using the priority fallback chain.
   *
   * @return ResolvedInstructions containing the content and source filename, or empty if none found
   */
  public ResolvedInstructions resolve(
      String owner, String repo, String defaultBranch, long installationId) {
    var cacheKey = owner + "/" + repo + "|" + String.join(";", fallbackChain);

    var cached = cache.get(cacheKey);
    if (cached != null) {
      if (clock.getAsLong() < cached.expiresAt()) {
        log.debug("Using cached instructions from {}", cached.source());
        return new ResolvedInstructions(cached.content(), cached.source());
      }
      cache.remove(cacheKey, cached);
    }

    var auth = authClient.getAuthHeader(installationId);

    for (String path : fallbackChain) {
      try {
        var file = prClient.getFileContent(auth, ACCEPT_HEADER, owner, repo, path, defaultBranch);
        // GitHub wraps base64 content in newlines; only the MIME decoder tolerates them.
        var content =
            new String(Base64.getMimeDecoder().decode(file.content()), StandardCharsets.UTF_8);

        cache.put(
            cacheKey, new CachedInstructions(content, path, clock.getAsLong() + CACHE_TTL_MS));
        sweepExpiredEntries();

        log.info("Using instructions file: {} ({} bytes)", path, content.length());
        return new ResolvedInstructions(content, path);
      } catch (WebApplicationException | ProcessingException _) {
        log.debug("Instructions file not found: {}", path);
      }
    }

    // Cache the negative result briefly (1 min).
    cache.put(cacheKey, new CachedInstructions("", "none", clock.getAsLong() + 60_000));
    sweepExpiredEntries();
    log.debug("No instructions file found for {}/{}", owner, repo);
    return ResolvedInstructions.EMPTY;
  }

  /**
   * The evict-on-read in resolve() only replaces entries whose key is requested again; without this
   * sweep the cache keeps one entry forever per repo/chain that is never reviewed again.
   */
  void sweepExpiredEntries() {
    if (cache.size() < CACHE_SWEEP_THRESHOLD) {
      return;
    }
    var now = clock.getAsLong();
    cache.entrySet().removeIf(entry -> now >= entry.getValue().expiresAt());
  }

  public record ResolvedInstructions(String content, String source) {
    public static final ResolvedInstructions EMPTY = new ResolvedInstructions("", "none");

    public boolean isPresent() {
      return !content.isEmpty();
    }
  }
}
