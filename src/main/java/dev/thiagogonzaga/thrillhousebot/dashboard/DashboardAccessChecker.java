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
  static final int ACCESS_CACHE_SWEEP_THRESHOLD = 1_000;
  private static final int PER_PAGE = 100;

  /**
   * Safety cap on paginated GitHub calls so a misbehaving API that keeps returning full pages can
   * never spin forever. At 100 entries per page this covers up to 10,000 installations/repos, well
   * beyond any realistic account.
   */
  private static final int MAX_PAGES = 100;

  private final ThrillhouseConfig config;
  private final GitHubAuthClient authClient;
  private final GitHubInstallationClient installationClient;
  private final Supplier<Instant> clock;

  private record OwnerCache(String owner, Instant cachedAt) {}

  private final AtomicReference<RepoSnapshot> cachedSnapshot =
      new AtomicReference<>(new RepoSnapshot(List.of(), null, Instant.EPOCH));
  private final AtomicReference<OwnerCache> ownerCache =
      new AtomicReference<>(new OwnerCache(null, Instant.EPOCH));

  static record AccessCacheEntry(boolean allowed, Instant cachedAt) {}

  private record RepoSnapshot(List<RepoRef> repos, String installationAuth, Instant cachedAt) {}

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

  public boolean isAccessControlEnabled() {
    return accountOwner().isPresent();
  }

  public boolean hasAccess(String githubLogin) {
    if (githubLogin == null || githubLogin.isBlank()) {
      return false;
    }

    Optional<String> owner = accountOwner();
    if (owner.isEmpty()) {
      return true;
    }

    var normalizedLogin = githubLogin.toLowerCase(Locale.ROOT);
    var cached = accessCache.get(normalizedLogin);
    if (cached != null) {
      if (cached.cachedAt().isAfter(clock.get().minus(ACCESS_CACHE_TTL))) {
        return cached.allowed();
      }
      accessCache.remove(normalizedLogin, cached);
    }

    var allowed = evaluateAccess(githubLogin, owner.get());
    accessCache.put(normalizedLogin, new AccessCacheEntry(allowed, clock.get()));
    sweepExpiredAccessEntries();
    if (!allowed) {
      log.warn("Dashboard access denied for GitHub user {}", githubLogin);
    }
    return allowed;
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
        && snapshot.installationAuth() != null) {
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
      return cachedSnapshot.get();
    }

    var updated = new RepoSnapshot(List.copyOf(repos), installationAuth, clock.get());
    cachedSnapshot.set(updated);
    log.info("Loaded {} installed repos for dashboard access control", repos.size());
    return updated;
  }

  /**
   * Pages through the app's installations until the one matching {@code accountOwner} is found.
   * Stops early on the matching installation or the first short page so we never fetch more than
   * necessary.
   */
  private Optional<GitHubInstallationClient.Installation> findInstallation(
      String jwt, String accountOwner) {
    for (var page = 1; page <= MAX_PAGES; page++) {
      var installations = installationClient.listInstallations(jwt, ACCEPT, PER_PAGE, page);
      if (installations == null || installations.isEmpty()) {
        return Optional.empty();
      }
      var match =
          installations.stream()
              .filter(
                  i -> i.account() != null && accountOwner.equalsIgnoreCase(i.account().login()))
              .findFirst();
      if (match.isPresent()) {
        return match;
      }
      if (installations.size() < PER_PAGE) {
        return Optional.empty();
      }
    }
    log.warn(
        "Reached max installation pages ({}) without resolving owner {}", MAX_PAGES, accountOwner);
    return Optional.empty();
  }

  /** Pages through every repository the installation can access, collecting owner-owned repos. */
  private void collectInstalledRepos(
      String installationAuth, String accountOwner, List<RepoRef> repos) {
    for (var page = 1; page <= MAX_PAGES; page++) {
      var response =
          installationClient.listInstallationRepositories(installationAuth, ACCEPT, PER_PAGE, page);
      var pageRepos = response.repositories();
      if (pageRepos == null || pageRepos.isEmpty()) {
        return;
      }
      for (GitHubInstallationClient.InstallationRepositoriesResponse.Repository repo : pageRepos) {
        if (repo.owner() != null
            && accountOwner.equalsIgnoreCase(repo.owner().login())
            && repo.name() != null) {
          repos.add(new RepoRef(repo.owner().login(), repo.name()));
        }
      }
      if (pageRepos.size() < PER_PAGE) {
        return;
      }
    }
    log.warn("Reached max repository pages ({}) for owner {}", MAX_PAGES, accountOwner);
  }

  private Optional<String> accountOwner() {
    var configured = config.dashboard().accountOwner().filter(s -> !s.isBlank());
    if (configured.isPresent()) {
      return configured;
    }

    var cached = ownerCache.get();
    if (cached.owner() != null && cached.cachedAt().isAfter(clock.get().minus(OWNER_CACHE_TTL))) {
      return Optional.of(cached.owner());
    }

    try {
      var jwt = "Bearer " + authClient.generateAppJwt();
      var app = installationClient.getApp(jwt, ACCEPT);
      if (app.owner() != null && app.owner().login() != null && !app.owner().login().isBlank()) {
        var owner = app.owner().login();
        ownerCache.set(new OwnerCache(owner, clock.get()));
        log.info("Resolved dashboard account owner from GitHub App: {}", owner);
        return Optional.of(owner);
      }
    } catch (RuntimeException e) {
      log.warn("Failed to resolve GitHub App owner: {}", e.getMessage());
    }

    return Optional.empty();
  }
}
