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
import jakarta.ws.rs.NotFoundException;
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

    var unreadable = false;
    for (String path : CONFIG_FILE_CHAIN) {
      var fetched = fetchAndParse(auth, owner, repo, defaultBranch, path);
      unreadable |= fetched.unreadable();
      var settings = fetched.settings();
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

    // A failure we could not read is not an answer, so it must not be cached as one (#481). The
    // review still runs on the global list — but the next one re-asks GitHub instead of spending
    // the negative TTL certain this repository has no config.
    if (unreadable) {
      log.warn(
          "Could not read a repository config for {}; running on the global settings for this"
              + " review only, without caching the result",
          cacheKey);
      return RepoSettings.EMPTY;
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
   * One candidate path's outcome. The three cases decide two different things — whether the chain
   * continues, and whether the run may be remembered:
   *
   * <ul>
   *   <li>{@link #found} — the file was read. A parse that found no usable settings still answers
   *       {@link RepoSettings#EMPTY} here, because the repository has spoken, it just said nothing
   *       usable. That ends the chain, and resolve() caches it inside the loop for the full {@link
   *       RepoSettingsResolver#CACHE_TTL_MS} exactly like a config that did parse — deliberately,
   *       so a repository whose YAML is broken is not re-fetched on every single review. The cost
   *       is that fixing a broken config takes effect on the same TTL as fixing a working one.
   *   <li>{@link #ABSENT} — GitHub said the file is not there (404), or answered without an inline
   *       payload. The caller moves on to the next name in the chain, and "this repository has no
   *       config" is a fact worth caching.
   *   <li>{@link #READ_FAILED} — the request itself failed (5xx, the 403 secondary rate limit, a
   *       transport error) or the payload could not be decoded. Nothing was learned about the
   *       repository, so the caller must not write that non-answer into the cache (#481).
   * </ul>
   *
   * <p>Every case runs the review on the global list, so this only decides how many candidates are
   * tried and what is remembered — never whether a review proceeds.
   */
  private record Fetched(RepoSettings settings, boolean unreadable) {

    static final Fetched ABSENT = new Fetched(null, false);
    static final Fetched READ_FAILED = new Fetched(null, true);

    static Fetched found(RepoSettings settings) {
      return new Fetched(settings, false);
    }
  }

  /** Fetches and parses one candidate path; see {@link Fetched} for what each outcome means. */
  private Fetched fetchAndParse(
      String auth, String owner, String repo, String defaultBranch, String path) {
    try {
      var file = prClient.getFileContent(auth, ACCEPT_HEADER, owner, repo, path, defaultBranch);
      if (file == null || file.content() == null) {
        return Fetched.ABSENT;
      }
      // GitHub wraps base64 content in newlines; only the MIME decoder tolerates them.
      var content =
          new String(Base64.getMimeDecoder().decode(file.content()), StandardCharsets.UTF_8);
      return Fetched.found(RepoSettingsParser.parse(content, path));
    } catch (NotFoundException _) {
      log.debug("Repository config file not found: {}", path);
      return Fetched.ABSENT;
    } catch (WebApplicationException | ProcessingException e) {
      // NotFoundException's siblings are not "no config": ServerErrorException (500/502/503) and
      // ClientErrorException (the 403 secondary rate limit) are transient, and treating them as a
      // 404 pinned "this repo has no config" in the cache for a minute of reviews (#481).
      log.warn(
          "Could not read repository config {} for {}/{}: {}", path, owner, repo, e.toString());
      return Fetched.READ_FAILED;
    } catch (RuntimeException e) {
      log.warn(
          "Failed to read repository config {} for {}/{}; continuing with the global settings only",
          path,
          owner,
          repo,
          e);
      return Fetched.READ_FAILED;
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
