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
import dev.thiagogonzaga.thrillhousebot.github.GitHubUserClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides what a dashboard login may read (#1221). GitHub answers with the login's own GitHub App
 * user access token: the App's installations they can access, and in each the repositories they can
 * read through any collaborator role, team access or organization base permission. An outside
 * contributor, a non-member, or an organization member whose base permission is none on a private
 * repository gets nothing GitHub would not show them.
 *
 * <p>A resolved scope is cached per token for {@link #SCOPE_CACHE_TTL}, so dashboard requests and
 * live events never wait on GitHub. Fails closed: a GitHub error resolves to nothing, and a token
 * GitHub rejects ends the dashboard session. With {@code thrillhousebot.dashboard.github
 * .account-owner} set, only that account's installation is served.
 */
@ApplicationScoped
public class DashboardAccessChecker {

  private static final Logger log = LoggerFactory.getLogger(DashboardAccessChecker.class);
  private static final String ACCEPT = "application/vnd.github+json";
  static final Duration SCOPE_CACHE_TTL = Duration.ofMinutes(5);
  static final int SCOPE_CACHE_SWEEP_THRESHOLD = 1_000;
  private static final int PER_PAGE = 100;

  /**
   * Loud safety valve for the page walk. Pagination ends on GitHub's short final page, so this is
   * only reached if an endpoint never returns one; it throws so the walk fails closed.
   */
  private static final int MAX_PAGES = 1_000;

  /** Outcome of resolving a login's access. */
  public enum AccessDecision {
    /** The login may read at least one repository. */
    ALLOWED,
    /** GitHub answered, and the login may read nothing this dashboard serves. */
    DENIED,
    /** GitHub rejected the login's token: it expired or was revoked, so the session must end. */
    TOKEN_REJECTED,
    /** GitHub could not be asked; access is denied until it can. */
    UNAVAILABLE
  }

  /** A login's access: the decision, and the scope it may read (empty unless allowed). */
  public record Resolution(AccessDecision decision, RepositoryScope scope) {
    static final Resolution DENIED = new Resolution(AccessDecision.DENIED, RepositoryScope.NONE);
  }

  private record CacheEntry(Resolution resolution, Instant cachedAt) {}

  private final ThrillhouseConfig config;
  private final GitHubAuthClient authClient;
  private final GitHubInstallationClient installationClient;
  private final GitHubUserClient userClient;
  private final Supplier<Instant> clock;
  private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
  private final AtomicBoolean membersPermissionWarned = new AtomicBoolean();

  @Inject
  public DashboardAccessChecker(
      ThrillhouseConfig config,
      GitHubAuthClient authClient,
      @RestClient GitHubInstallationClient installationClient,
      @RestClient GitHubUserClient userClient) {
    this(config, authClient, installationClient, userClient, Instant::now);
  }

  /** Visible for tests: allows controlling cache expiry. */
  DashboardAccessChecker(
      ThrillhouseConfig config,
      GitHubAuthClient authClient,
      GitHubInstallationClient installationClient,
      GitHubUserClient userClient,
      Supplier<Instant> clock) {
    this.config = config;
    this.authClient = authClient;
    this.installationClient = installationClient;
    this.userClient = userClient;
    this.clock = clock;
  }

  /** Whether {@code login}, holding {@code userToken}, may use the dashboard. */
  public AccessDecision checkAccess(String login, String userToken) {
    return resolve(login, userToken).decision();
  }

  /** The repositories {@code login} may read; {@link RepositoryScope#NONE} unless allowed. */
  public RepositoryScope repositoryScope(String login, String userToken) {
    return resolve(login, userToken).scope();
  }

  /**
   * Resolves and caches a login's access. Only answers GitHub gave are cached; a rejected token or
   * an unreachable GitHub is asked again on the next request.
   */
  public Resolution resolve(String login, String userToken) {
    if (isBlank(login) || isBlank(userToken)) {
      return Resolution.DENIED;
    }
    var key = cacheKey(login, userToken);
    var cached = cache.get(key);
    if (cached != null && cached.cachedAt().isAfter(clock.get().minus(SCOPE_CACHE_TTL))) {
      return cached.resolution();
    }
    Resolution resolution;
    try {
      var scope = fetchScope(login, "Bearer " + userToken);
      resolution =
          new Resolution(
              scope.isEmpty() ? AccessDecision.DENIED : AccessDecision.ALLOWED,
              scope.isEmpty() ? RepositoryScope.NONE : scope);
    } catch (WebApplicationException e) {
      if (e.getResponse().getStatus() == 401) {
        log.info("GitHub rejected the dashboard token of {}; ending the session", login);
        cache.remove(key);
        return new Resolution(AccessDecision.TOKEN_REJECTED, RepositoryScope.NONE);
      }
      return unavailable(login, e);
    } catch (RuntimeException e) {
      return unavailable(login, e);
    }
    cache.put(key, new CacheEntry(resolution, clock.get()));
    sweepExpiredEntries();
    if (resolution.decision() == AccessDecision.DENIED) {
      log.warn("Dashboard access denied for GitHub user {}", login);
    }
    return resolution;
  }

  private static Resolution unavailable(String login, RuntimeException e) {
    log.warn("Could not resolve dashboard access for {}: {}", login, e.getMessage());
    return new Resolution(AccessDecision.UNAVAILABLE, RepositoryScope.NONE);
  }

  private RepositoryScope fetchScope(String login, String userAuth) {
    var owner = config.dashboard().accountOwner().map(String::strip).filter(s -> !s.isEmpty());
    var installations = new HashMap<Long, RepositoryScope.Installation>();
    for (var installation :
        allPages(
            page ->
                userClient.listInstallations(userAuth, ACCEPT, PER_PAGE, page).installations())) {
      var account = installation.account();
      if (account == null
          || account.login() == null
          || owner.filter(o -> !o.equalsIgnoreCase(account.login())).isPresent()) {
        continue;
      }
      var repositories = repositories(userAuth, installation.id());
      installations.put(
          installation.id(),
          new RepositoryScope.Installation(
              installation.id(),
              account.login(),
              installationWide(installation, login),
              repositories));
    }
    return new RepositoryScope(installations);
  }

  /** The repositories of one installation the user can read, by id, as {@code owner/name}. */
  private Map<Long, String> repositories(String userAuth, long installationId) {
    var repositories = new HashMap<Long, String>();
    for (var repo :
        allPages(
            page ->
                userClient
                    .listRepositories(userAuth, ACCEPT, installationId, PER_PAGE, page)
                    .repositories())) {
      if (repo.id() > 0
          && repo.name() != null
          && repo.owner() != null
          && repo.owner().login() != null) {
        repositories.putIfAbsent(repo.id(), repo.owner().login() + "/" + repo.name());
      }
    }
    return repositories;
  }

  /**
   * Whether the login may see views that span the installation: the account itself on a personal
   * account, and a member of the organization on an organization. Membership is granted only on
   * GitHub's 204; without the App's Organization → Members: read permission GitHub answers 403 and
   * the views stay hidden.
   */
  private boolean installationWide(
      GitHubInstallationClient.Installation installation, String login) {
    var account = installation.account();
    if (!"Organization".equalsIgnoreCase(account.type())) {
      return account.login().equalsIgnoreCase(login);
    }
    try (var response =
        installationClient.checkOrgMember(
            authClient.getAuthHeader(installation.id()), ACCEPT, account.login(), login)) {
      var status = response.getStatus();
      if (status == 403) {
        warnMembersPermission(account.login());
      }
      return status == 204;
    } catch (WebApplicationException e) {
      if (e.getResponse().getStatus() == 403) {
        warnMembersPermission(account.login());
      }
      return false;
    } catch (RuntimeException e) {
      log.warn(
          "Could not check membership of {} in {}: {}", login, account.login(), e.getMessage());
      return false;
    }
  }

  private void warnMembersPermission(String org) {
    if (membersPermissionWarned.compareAndSet(false, true)) {
      log.warn(
          "GitHub refused the organization membership check for {}; approve the App's"
              + " Organization -> Members: read permission to show installation-wide dashboard"
              + " views to its members",
          org);
    }
  }

  /**
   * Walks 1-based pages until GitHub's short final page or an empty one. Exceeding {@link
   * #MAX_PAGES} throws, so a listing that never ends fails closed instead of spinning.
   */
  private static <T> List<T> allPages(IntFunction<List<T>> fetch) {
    var all = new ArrayList<T>();
    for (var page = 1; page <= MAX_PAGES; page++) {
      var items = fetch.apply(page);
      if (items == null || items.isEmpty()) {
        return all;
      }
      all.addAll(items);
      if (items.size() < PER_PAGE) {
        return all;
      }
    }
    throw new IllegalStateException(
        "GitHub pagination exceeded " + MAX_PAGES + " pages; aborting to avoid an unbounded fetch");
  }

  /**
   * Cache key per login and token, hashed so the map never holds a second copy of the token. A new
   * login, with a new token, is resolved afresh.
   */
  private static String cacheKey(String login, String userToken) {
    return UUID.nameUUIDFromBytes(
            (login.toLowerCase(Locale.ROOT) + "\n" + userToken).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  /** Drops expired entries once the cache grows, so logins that never return do not stay. */
  void sweepExpiredEntries() {
    if (cache.size() < SCOPE_CACHE_SWEEP_THRESHOLD) {
      return;
    }
    var cutoff = clock.get().minus(SCOPE_CACHE_TTL);
    cache.entrySet().removeIf(entry -> !entry.getValue().cachedAt().isAfter(cutoff));
  }

  /** Visible for tests that assert the cache stays bounded. */
  int cacheSize() {
    return cache.size();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
