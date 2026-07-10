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
package dev.thiagogonzaga.thrillhousebot.dashboard;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubInstallationClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Restricts dashboard access to the account owner and repo collaborators. */
@ApplicationScoped
public class DashboardAccessChecker {

  private static final Logger log = LoggerFactory.getLogger(DashboardAccessChecker.class);
  private static final String ACCEPT = "application/vnd.github+json";
  private static final Duration REPO_CACHE_TTL = Duration.ofMinutes(10);
  private static final Duration ACCESS_CACHE_TTL = Duration.ofMinutes(5);
  private static final Duration OWNER_CACHE_TTL = Duration.ofHours(1);
  private static final Duration OWNER_NEGATIVE_CACHE_TTL = Duration.ofMinutes(1);
  static final int ACCESS_CACHE_SWEEP_THRESHOLD = 1_000;
  private static final int PER_PAGE = 100;

  /**
   * Loud safety valve for the page walk. Pagination terminates on GitHub's short final page, so
   * this ceiling is only reached if an endpoint misbehaves and never returns one. It sits far above
   * any real account (100,000 entries); reaching it throws so the walk fails closed and is logged,
   * rather than spinning a request thread forever or silently dropping data.
   */
  private static final int MAX_PAGES = 1_000;

  /** Outcome of an access check, letting callers distinguish denial from a misconfigured owner. */
  public enum AccessDecision {
    /** The login may use the dashboard. */
    ALLOWED,
    /** Access control is active but this login is neither the owner nor a collaborator. */
    DENIED,
    /** No account owner is configured or resolvable, so access control cannot be enforced. */
    NOT_CONFIGURED
  }

  private final ThrillhouseConfig config;
  private final GitHubAuthClient authClient;
  private final GitHubInstallationClient installationClient;
  private final Supplier<Instant> clock;

  private record OwnerCache(String owner, Instant cachedAt) {}

  private final AtomicReference<RepoSnapshot> cachedSnapshot =
      new AtomicReference<>(new RepoSnapshot(null, List.of(), null, Instant.EPOCH));
  private final AtomicReference<OwnerCache> ownerCache =
      new AtomicReference<>(new OwnerCache(null, Instant.EPOCH));

  static record AccessCacheEntry(boolean allowed, Instant cachedAt) {}

  private record RepoSnapshot(
      String owner, List<RepoRef> repos, String installationAuth, Instant cachedAt) {}

  private record RepoRef(String owner, String name) {}

  private final ConcurrentHashMap<String, AccessCacheEntry> accessCache = new ConcurrentHashMap<>();

  @Inject
  public DashboardAccessChecker(
      ThrillhouseConfig config,
      GitHubAuthClient authClient,
      @RestClient GitHubInstallationClient installationClient) {
    this(config, authClient, installationClient, Instant::now);
  }

  /** Visible for tests: allows controlling cache expiry. */
  DashboardAccessChecker(
      ThrillhouseConfig config,
      GitHubAuthClient authClient,
      GitHubInstallationClient installationClient,
      Supplier<Instant> clock) {
    this.config = config;
    this.authClient = authClient;
    this.installationClient = installationClient;
    this.clock = clock;
  }

  public boolean hasAccess(String githubLogin) {
    return checkAccess(githubLogin) == AccessDecision.ALLOWED;
  }

  /**
   * Resolves whether {@code githubLogin} may use the dashboard. Fails closed: when no account owner
   * can be determined the result is {@link AccessDecision#NOT_CONFIGURED} (deny), never an implicit
   * grant. The underlying misconfiguration is logged once per resolution attempt in {@link
   * #accountOwner()}, so this method does not log per call.
   */
  public AccessDecision checkAccess(String githubLogin) {
    if (githubLogin == null || githubLogin.isBlank()) {
      return AccessDecision.DENIED;
    }

    Optional<String> owner = accountOwner();
    if (owner.isEmpty()) {
      return AccessDecision.NOT_CONFIGURED;
    }

    var normalizedLogin = githubLogin.toLowerCase(Locale.ROOT);
    var cached = accessCache.get(normalizedLogin);
    if (cached != null) {
      if (cached.cachedAt().isAfter(clock.get().minus(ACCESS_CACHE_TTL))) {
        return cached.allowed() ? AccessDecision.ALLOWED : AccessDecision.DENIED;
      }
      accessCache.remove(normalizedLogin, cached);
    }

    var allowed = evaluateAccess(githubLogin, owner.get());
    accessCache.put(normalizedLogin, new AccessCacheEntry(allowed, clock.get()));
    sweepExpiredAccessEntries();
    if (!allowed) {
      log.warn("Dashboard access denied for GitHub user {}", githubLogin);
    }
    return allowed ? AccessDecision.ALLOWED : AccessDecision.DENIED;
  }

  /**
   * The evict-on-read above only replaces entries for logins that come back; without this sweep the
   * cache keeps one entry forever per login that never returns (including denied ones).
   */
  void sweepExpiredAccessEntries() {
    if (accessCache.size() < ACCESS_CACHE_SWEEP_THRESHOLD) {
      return;
    }
    var cutoff = clock.get().minus(ACCESS_CACHE_TTL);
    accessCache.entrySet().removeIf(entry -> !entry.getValue().cachedAt().isAfter(cutoff));
  }

  /** Seeds access cache state for tests without exposing the backing map. */
  void seedAccessCache(String githubLogin, boolean allowed, Instant cachedAt) {
    accessCache.put(githubLogin.toLowerCase(Locale.ROOT), new AccessCacheEntry(allowed, cachedAt));
  }

  /** Visible for tests that assert access cache lifecycle. */
  int accessCacheSize() {
    return accessCache.size();
  }

  /** Visible for tests that assert a specific cached login remains present. */
  boolean hasAccessCacheEntry(String githubLogin) {
    return accessCache.containsKey(githubLogin.toLowerCase(Locale.ROOT));
  }

  private boolean evaluateAccess(String githubLogin, String accountOwner) {
    if (accountOwner.equalsIgnoreCase(githubLogin)) {
      return true;
    }

    var snapshot = installedRepos(accountOwner);
    if (snapshot.installationAuth() == null || snapshot.repos().isEmpty()) {
      return false;
    }

    for (RepoRef repo : snapshot.repos()) {
      if (hasRepoAccess(snapshot.installationAuth(), repo, githubLogin)) {
        return true;
      }
    }
    return false;
  }

  private boolean hasRepoAccess(String auth, RepoRef repo, String githubLogin) {
    try (var response =
        installationClient.checkCollaborator(
            auth, ACCEPT, repo.owner(), repo.name(), githubLogin)) {
      var status = response.getStatus();
      if (status == 204) {
        return true;
      }
      if (status != 404) {
        log.warn(
            "Unexpected collaborator check status {} for {}/{} user {}",
            status,
            repo.owner(),
            repo.name(),
            githubLogin);
      }
      return false;
    } catch (RuntimeException e) {
      log.warn(
          "Collaborator check failed for {}/{} user {}: {}",
          repo.owner(),
          repo.name(),
          githubLogin,
          e.getMessage());
      return false;
    }
  }

  private RepoSnapshot installedRepos(String accountOwner) {
    var snapshot = cachedSnapshot.get();
    if (snapshot.cachedAt().isAfter(clock.get().minus(REPO_CACHE_TTL))
        && snapshot.installationAuth() != null
        && accountOwner.equalsIgnoreCase(snapshot.owner())) {
      return snapshot;
    }

    var repos = new ArrayList<RepoRef>();
    String installationAuth = null;
    try {
      var jwt = "Bearer " + authClient.generateAppJwt();
      var matchingInstallation = findInstallation(jwt, accountOwner);
      if (matchingInstallation.isPresent()) {
        installationAuth = authClient.getAuthHeader(matchingInstallation.get().id());
        collectInstalledRepos(installationAuth, accountOwner, repos);
      }
    } catch (RuntimeException e) {
      log.warn("Failed to list installed repositories for {}: {}", accountOwner, e.getMessage());
      // Fall back to the cached snapshot only if it was resolved for the same owner; a previous
      // owner's snapshot must not be reused.
      var previous = cachedSnapshot.get();
      return accountOwner.equalsIgnoreCase(previous.owner())
          ? previous
          : new RepoSnapshot(accountOwner, List.of(), null, Instant.EPOCH);
    }

    var updated = new RepoSnapshot(accountOwner, List.copyOf(repos), installationAuth, clock.get());
    cachedSnapshot.set(updated);
    log.info("Loaded {} installed repos for dashboard access control", repos.size());
    return updated;
  }

  /**
   * Pages through the app's installations until the one matching {@code accountOwner} is found,
   * stopping as soon as it matches or the listing ends.
   */
  private Optional<GitHubInstallationClient.Installation> findInstallation(
      String jwt, String accountOwner) {
    var match = new AtomicReference<GitHubInstallationClient.Installation>();
    paginate(
        page -> installationClient.listInstallations(jwt, ACCEPT, PER_PAGE, page),
        installations -> {
          var found =
              installations.stream()
                  .filter(
                      i ->
                          i.account() != null && accountOwner.equalsIgnoreCase(i.account().login()))
                  .findFirst();
          found.ifPresent(match::set);
          return found.isPresent();
        });
    return Optional.ofNullable(match.get());
  }

  /** Collects every repository owned by {@code accountOwner} that the installation can access. */
  private void collectInstalledRepos(
      String installationAuth, String accountOwner, List<RepoRef> repos) {
    paginate(
        page ->
            installationClient
                .listInstallationRepositories(installationAuth, ACCEPT, PER_PAGE, page)
                .repositories(),
        pageRepos -> {
          collectOwnerRepos(pageRepos, accountOwner, repos);
          return false;
        });
  }

  /**
   * Walks 1-based pages from {@code fetchPage}, handing each non-empty page to {@code consumePage}.
   * The walk ends when a page is shorter than {@code PER_PAGE} (GitHub's marker for the final
   * page), when a page is empty or null, or when {@code consumePage} returns {@code true} to stop
   * early. Termination follows the rows actually returned rather than any separately reported
   * total, so it can never under-fetch. {@code MAX_PAGES} guards against an endpoint that never
   * serves a short page; exceeding it throws rather than looping forever or returning a partial
   * result.
   */
  private <T> void paginate(IntFunction<List<T>> fetchPage, Predicate<List<T>> consumePage) {
    for (var page = 1; page <= MAX_PAGES; page++) {
      var items = fetchPage.apply(page);
      if (items == null || items.isEmpty()) {
        return;
      }
      if (consumePage.test(items)) {
        return;
      }
      if (items.size() < PER_PAGE) {
        return;
      }
    }
    throw new IllegalStateException(
        "GitHub pagination exceeded " + MAX_PAGES + " pages; aborting to avoid an unbounded fetch");
  }

  private void collectOwnerRepos(
      List<GitHubInstallationClient.InstallationRepositoriesResponse.Repository> pageRepos,
      String accountOwner,
      List<RepoRef> repos) {
    for (var repo : pageRepos) {
      if (repo.owner() != null
          && accountOwner.equalsIgnoreCase(repo.owner().login())
          && repo.name() != null) {
        repos.add(new RepoRef(repo.owner().login(), repo.name()));
      }
    }
  }

  private Optional<String> accountOwner() {
    var configured = config.dashboard().accountOwner().filter(s -> !s.isBlank());
    if (configured.isPresent()) {
      return configured;
    }

    var cached = ownerCache.get();
    var ttl = cached.owner() != null ? OWNER_CACHE_TTL : OWNER_NEGATIVE_CACHE_TTL;
    if (cached.cachedAt().isAfter(clock.get().minus(ttl))) {
      return Optional.ofNullable(cached.owner());
    }

    String resolved = null;
    try {
      var jwt = "Bearer " + authClient.generateAppJwt();
      var app = installationClient.getApp(jwt, ACCEPT);
      if (app.owner() != null && app.owner().login() != null && !app.owner().login().isBlank()) {
        resolved = app.owner().login();
        log.info("Resolved dashboard account owner from GitHub App: {}", resolved);
      } else {
        log.warn(
            "GitHub App returned no owner login; dashboard access stays denied until "
                + "thrillhousebot.dashboard.github.account-owner is set");
      }
    } catch (RuntimeException e) {
      log.warn(
          "Failed to resolve dashboard account owner from GitHub App ({}); access stays denied "
              + "until thrillhousebot.dashboard.github.account-owner is set",
          e.getMessage());
    }

    // Failures are cached too, briefly (OWNER_NEGATIVE_CACHE_TTL).
    ownerCache.set(new OwnerCache(resolved, clock.get()));
    return Optional.ofNullable(resolved);
  }
}
