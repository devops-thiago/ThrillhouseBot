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
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class InstructionsResolver {

  private static final Logger log = LoggerFactory.getLogger(InstructionsResolver.class);

  /** Default fallback chain when the configured instructions file is not found. */
  private static final List<String> DEFAULT_FALLBACK_CHAIN =
      List.of(
          ".github/thrillhousebot.md",
          ".github/copilot-instructions.md",
          "CLAUDE.md",
          "AGENTS.md",
          "AGENT.md");

  private final List<String> fallbackChain;
  private final GitHubClientProvider clientProvider;

  private record CachedInstructions(String content, String source, long expiresAt) {}

  // Package-private for tests.
  final ConcurrentHashMap<String, CachedInstructions> cache = new ConcurrentHashMap<>();
  static final long CACHE_TTL_MS = 5L * 60 * 1000; // 5 minutes
  static final int CACHE_SWEEP_THRESHOLD = 1_000;

  private final LongSupplier clock;

  @Inject
  public InstructionsResolver(ThrillhouseConfig config, GitHubClientProvider clientProvider) {
    this(config, clientProvider, System::currentTimeMillis);
  }

  /** Visible for tests: allows controlling the cache clock. */
  InstructionsResolver(
      ThrillhouseConfig config, GitHubClientProvider clientProvider, LongSupplier clock) {
    this.fallbackChain = buildFallbackChain(config);
    this.clientProvider = clientProvider;
    this.clock = clock;
  }

  List<String> fallbackChain() {
    return fallbackChain;
  }

  private static List<String> buildFallbackChain(ThrillhouseConfig config) {
    var configured = config.review().instructionsFile();
    var first =
        configured != null && !configured.isBlank()
            ? Stream.of(configured.trim())
            : Stream.<String>empty();
    return Stream.concat(first, DEFAULT_FALLBACK_CHAIN.stream()).distinct().toList();
  }

  /**
   * Resolves the instructions file for a repository using the priority fallback chain.
   *
   * <p>A path that could not be read (a 5xx, the 403 secondary rate limit, a transport error) is
   * not a 404, so when no later path answers the empty result is not cached and the next review
   * asks GitHub again.
   *
   * @return ResolvedInstructions containing the content and source filename, or empty if none found
   */
  public ResolvedInstructions resolve(
      String owner, String repo, String defaultBranch, long installationId) {
    // Keyed by installation too (#1220): a repository of the same name under another installation
    // has its own configuration.
    var cacheKey =
        installationId + ":" + owner + "/" + repo + "|" + String.join(";", fallbackChain);

    var cached = cache.get(cacheKey);
    if (cached != null) {
      if (clock.getAsLong() < cached.expiresAt()) {
        log.debug("Using cached instructions from {}", cached.source());
        return new ResolvedInstructions(cached.content(), cached.source());
      }
      cache.remove(cacheKey, cached);
    }

    GHRepository repository;
    try {
      repository =
          clientProvider.getInstallationClient(installationId).getRepository(owner + "/" + repo);
    } catch (GHFileNotFoundException _) {
      log.debug("Repository {}/{} not found", owner, repo);
      return ResolvedInstructions.EMPTY;
    } catch (RuntimeException | IOException e) {
      log.warn("Could not resolve repository {}/{}: {}", owner, repo, e.toString());
      return ResolvedInstructions.EMPTY;
    }

    var readFailed = false;
    for (String path : fallbackChain) {
      try {
        var file = repository.getFileContent(path, defaultBranch);
        if (file == null || !file.isFile()) {
          continue;
        }
        // hub4j's getContent() already decodes the base64 payload.
        var content = file.getContent();
        if (content == null) {
          continue;
        }

        cache.put(
            cacheKey, new CachedInstructions(content, path, clock.getAsLong() + CACHE_TTL_MS));
        sweepExpiredEntries();

        log.info("Using instructions file: {} ({} bytes)", path, content.length());
        return new ResolvedInstructions(content, path);
      } catch (GHFileNotFoundException _) {
        log.debug("Instructions file not found: {}", path);
      } catch (RuntimeException | IOException e) {
        log.warn(
            "Could not read instructions file {} for {}/{}: {}", path, owner, repo, e.toString());
        readFailed = true;
      }
    }

    if (readFailed) {
      return ResolvedInstructions.EMPTY;
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
