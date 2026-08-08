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
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a repository's own structured settings from {@code .github/thrillhousebot.yml}, cached
 * per repository with the same TTL scheme {@link InstructionsResolver} uses for the prose
 * instructions file.
 *
 * <p>A dedicated file — rather than frontmatter in the instructions file — because the instructions
 * fallback chain deliberately reaches into files owned by other tools ({@code
 * .github/copilot-instructions.md}, {@code CLAUDE.md}, {@code AGENTS.md}), and because that file's
 * whole content is handed to the model as untrusted prose; structured settings belong somewhere
 * that neither depends on which of those files won nor perturbs the prompt.
 *
 * <p>Every failure mode degrades to {@link RepoSettings#EMPTY}: the file is absent, unreadable,
 * unparseable, or the feature is switched off — in all cases the deployment defaults apply and the
 * review proceeds.
 */
@ApplicationScoped
public class RepoSettingsResolver {

  private static final Logger log = LoggerFactory.getLogger(RepoSettingsResolver.class);
  private static final String ACCEPT_HEADER = "application/vnd.github+json";

  /** Config-file names tried in order; the first that exists wins. */
  static final List<String> CONFIG_FILE_CHAIN =
      List.of(".github/thrillhousebot.yml", ".github/thrillhousebot.yaml");

  private record CachedSettings(RepoSettings settings, long expiresAt) {}

  // Package-private for tests.
  final ConcurrentHashMap<String, CachedSettings> cache = new ConcurrentHashMap<>();
  static final long CACHE_TTL_MS = 5L * 60 * 1000; // 5 minutes
  static final long NEGATIVE_CACHE_TTL_MS = 60_000; // 1 minute
  static final int CACHE_SWEEP_THRESHOLD = 1_000;

  private final boolean enabled;
  private final GitHubAuthClient authClient;
  private final GitHubPullRequestClient prClient;
  private final LongSupplier clock;

  @Inject
  public RepoSettingsResolver(
      ThrillhouseConfig config,
      GitHubAuthClient authClient,
      @RestClient GitHubPullRequestClient prClient) {
    this(config, authClient, prClient, System::currentTimeMillis);
  }

  /** Visible for tests: allows controlling the cache clock. */
  RepoSettingsResolver(
      ThrillhouseConfig config,
      GitHubAuthClient authClient,
      GitHubPullRequestClient prClient,
      LongSupplier clock) {
    this.enabled = config.review().repoConfigEnabled();
    this.authClient = authClient;
    this.prClient = prClient;
    this.clock = clock;
  }

  /**
   * The repository's declared settings, or {@link RepoSettings#EMPTY} when it declares none (or the
   * feature is disabled). Never throws.
   */
  public RepoSettings resolve(
      String owner, String repo, String defaultBranch, long installationId) {
    if (!enabled) {
      return RepoSettings.EMPTY;
    }
    var cacheKey = owner + "/" + repo;

    var cached = cache.get(cacheKey);
    if (cached != null) {
      if (clock.getAsLong() < cached.expiresAt()) {
        log.debug("Using cached repository config for {}", cacheKey);
        return cached.settings();
      }
      cache.remove(cacheKey, cached);
    }

    var auth = authClient.getAuthHeader(installationId);

    for (String path : CONFIG_FILE_CHAIN) {
      var settings = fetchAndParse(auth, owner, repo, defaultBranch, path);
      if (settings == null) {
        continue;
      }
      cache.put(cacheKey, new CachedSettings(settings, clock.getAsLong() + CACHE_TTL_MS));
      sweepExpiredEntries();
      log.info(
          "Using repository config {} for {} ({} extra ignore pattern(s), {} path-scoped rule"
              + " block(s))",
          path,
          cacheKey,
          settings.ignoredFiles().size(),
          settings.pathInstructions().size());
      return settings;
    }

    // Cache the negative result briefly so an unconfigured repo is not re-fetched every review.
    cache.put(
        cacheKey,
        new CachedSettings(RepoSettings.EMPTY, clock.getAsLong() + NEGATIVE_CACHE_TTL_MS));
    sweepExpiredEntries();
    log.debug("No repository config file found for {}", cacheKey);
    return RepoSettings.EMPTY;
  }

  /**
   * Fetches and parses one candidate path. The two outcomes are deliberately different:
   *
   * <ul>
   *   <li>{@code null} — nothing readable came back at all: the file is absent (404), the request
   *       failed, or the payload could not be decoded into text. The caller moves on to the next
   *       name in the chain, because a second candidate may still hold a usable config.
   *   <li>{@link RepoSettings#EMPTY} — the file was read, and the parse found no usable settings
   *       (malformed YAML, wrong shape, no {@code review.ignored-files}). That is a real, cacheable
   *       answer that ends the chain: the repository has spoken, it just said nothing usable.
   * </ul>
   *
   * <p>Either way the effective result is the global list, so the distinction only decides how many
   * candidates are tried — never whether a review proceeds.
   */
  private RepoSettings fetchAndParse(
      String auth, String owner, String repo, String defaultBranch, String path) {
    try {
      var file = prClient.getFileContent(auth, ACCEPT_HEADER, owner, repo, path, defaultBranch);
      if (file == null || file.content() == null) {
        return null;
      }
      // GitHub wraps base64 content in newlines; only the MIME decoder tolerates them.
      var content =
          new String(Base64.getMimeDecoder().decode(file.content()), StandardCharsets.UTF_8);
      return RepoSettingsParser.parse(content, path);
    } catch (WebApplicationException | ProcessingException _) {
      log.debug("Repository config file not found: {}", path);
      return null;
    } catch (RuntimeException e) {
      log.warn(
          "Failed to read repository config {} for {}/{}; continuing with the global settings only",
          path,
          owner,
          repo,
          e);
      return null;
    }
  }

  /**
   * The evict-on-read in resolve() only replaces entries whose key is requested again; without this
   * sweep the cache keeps one entry forever per repo that is never reviewed again.
   */
  void sweepExpiredEntries() {
    if (cache.size() < CACHE_SWEEP_THRESHOLD) {
      return;
    }
    var now = clock.getAsLong();
    cache.entrySet().removeIf(entry -> now >= entry.getValue().expiresAt());
  }
}
