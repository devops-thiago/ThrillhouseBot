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
import dev.thiagogonzaga.thrillhousebot.github.AllowedAccounts;
import dev.thiagogonzaga.thrillhousebot.github.GitHubApiError;
import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubInstallationClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubUserClient;
import dev.thiagogonzaga.thrillhousebot.github.InstallationChange;
import dev.thiagogonzaga.thrillhousebot.github.RepoScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
 * live events never wait on GitHub. While GitHub cannot be asked, the login keeps the last answer
 * GitHub gave for up to {@link #STALE_SCOPE_LIMIT}, asked again at most every {@link
 * #RETRY_AFTER_ERROR}; with no such answer it is denied, and that too is asked again only after
 * {@link #RETRY_AFTER_ERROR}, so an outage never turns into a GitHub call per request. A token
 * GitHub rejects ends the dashboard session. With {@code thrillhousebot.dashboard.github
 * .account-owner} set, only that account's installation is served.
 */
@ApplicationScoped
public class DashboardAccessChecker {

  private static final Logger log = LoggerFactory.getLogger(DashboardAccessChecker.class);
  private static final String ACCEPT = "application/vnd.github+json";
  static final Duration SCOPE_CACHE_TTL = Duration.ofMinutes(5);

  /** How long the last answer GitHub gave is still served while GitHub cannot be asked. */
  static final Duration STALE_SCOPE_LIMIT = Duration.ofMinutes(30);

  /** How long a GitHub error is remembered before GitHub is asked again. */
  static final Duration RETRY_AFTER_ERROR = Duration.ofMinutes(1);

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

  /**
   * A cached resolution: what to answer until {@code retryAt}, and when GitHub last gave an answer
   * ({@code answeredAt}, null when it never has) so a stale answer is served only within {@link
   * #STALE_SCOPE_LIMIT}. {@code changes} is the installation-change count when the answer was asked
   * for; an entry is used only while no change has landed since, so neither an answer that
   * overlapped a change nor one read in the moment before the change cleared the cache is ever
   * served after it.
   */
  private record CacheEntry(
      Resolution resolution, Instant answeredAt, Instant retryAt, long changes) {}

  /**
   * How long a webhook's removal of an installation or repository overrides GitHub's own answer.
   * GitHub catches up within seconds; the bound keeps a lost {@code unsuspend} or {@code added}
   * delivery from hiding a live installation or repository until a restart.
   */
  static final Duration REMOVAL_LIMIT = Duration.ofMinutes(10);

  private final ThrillhouseConfig config;
  private final GitHubAuthClient authClient;
  private final GitHubInstallationClient installationClient;
  private final GitHubUserClient userClient;
  private final AllowedAccounts allowedAccounts;
  private final Supplier<Instant> clock;
  private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
  private final Map<Long, Instant> goneInstallations = new ConcurrentHashMap<>();
  private final Map<RepoScope, Instant> removedRepositories = new ConcurrentHashMap<>();
  private final AtomicLong changeCount = new AtomicLong();
  private final AtomicBoolean membersPermissionWarned = new AtomicBoolean();

  @Inject
  public DashboardAccessChecker(
      ThrillhouseConfig config,
      GitHubAuthClient authClient,
      @RestClient GitHubInstallationClient installationClient,
      @RestClient GitHubUserClient userClient,
      AllowedAccounts allowedAccounts) {
    this(config, authClient, installationClient, userClient, allowedAccounts, Instant::now);
  }

  /** Visible for tests: allows controlling cache expiry. */
  DashboardAccessChecker(
      ThrillhouseConfig config,
      GitHubAuthClient authClient,
      GitHubInstallationClient installationClient,
      GitHubUserClient userClient,
      AllowedAccounts allowedAccounts,
      Supplier<Instant> clock) {
    this.config = config;
    this.authClient = authClient;
    this.installationClient = installationClient;
    this.userClient = userClient;
    this.allowedAccounts = allowedAccounts;
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
   * Resolves and caches a login's access. A rejected token is never cached, so the session ends; an
   * unreachable GitHub falls back as {@link #unavailable} describes.
   */
  public Resolution resolve(String login, String userToken) {
    if (isBlank(login) || isBlank(userToken)) {
      return Resolution.DENIED;
    }
    var key = cacheKey(login, userToken);
    var now = clock.get();
    // A webhook change that lands while GitHub is being asked must not be undone by this answer.
    var changesBefore = changeCount.get();
    var cached = cache.get(key);
    if (cached != null && cached.changes() != changesBefore) {
      cached = null;
    }
    if (cached != null && now.isBefore(cached.retryAt())) {
      return cached.resolution();
    }
    Resolution resolution;
    try {
      var scope = withoutRemoved(fetchScope(login, "Bearer " + userToken));
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
      return unavailable(key, cached, changesBefore, login, e);
    } catch (RuntimeException e) {
      return unavailable(key, cached, changesBefore, login, e);
    }
    cache.put(key, new CacheEntry(resolution, now, now.plus(SCOPE_CACHE_TTL), changesBefore));
    sweepExpiredEntries();
    if (resolution.decision() == AccessDecision.DENIED) {
      log.warn("Dashboard access denied for GitHub user {}", login);
    }
    return resolution;
  }

  /**
   * GitHub could not be asked: serve the last answer it gave while that is recent enough, or deny,
   * and remember either for {@link #RETRY_AFTER_ERROR} so the next requests do not ask again.
   */
  private Resolution unavailable(
      String key, CacheEntry cached, long changes, String login, RuntimeException e) {
    var now = clock.get();
    var stale =
        cached != null
            && cached.answeredAt() != null
            && cached.answeredAt().isAfter(now.minus(STALE_SCOPE_LIMIT));
    var resolution =
        stale
            ? cached.resolution()
            : new Resolution(AccessDecision.UNAVAILABLE, RepositoryScope.NONE);
    log.warn(
        "Could not resolve dashboard access for {}{}: {}",
        login,
        stale ? "; serving its last known access" : "",
        e.getMessage());
    cache.put(
        key,
        new CacheEntry(
            resolution, stale ? cached.answeredAt() : null, now.plus(RETRY_AFTER_ERROR), changes));
    return resolution;
  }

  private RepositoryScope fetchScope(String login, String userAuth) {
    var owner = config.dashboard().accountOwner().map(String::strip).filter(s -> !s.isEmpty());
    var installations = new HashMap<Long, RepositoryScope.Installation>();
    for (var installation :
        allPages(
            page ->
                userClient.listInstallations(userAuth, ACCEPT, PER_PAGE, page).installations())) {
      if (served(installation, owner)) {
        var repositories = repositories(userAuth, installation.id());
        if (!repositories.isEmpty()) {
          installations.put(
              installation.id(),
              new RepositoryScope.Installation(
                  installation.id(),
                  installation.account().login(),
                  installationWide(installation, login),
                  repositories));
        }
      }
    }
    return new RepositoryScope(installations);
  }

  /**
   * Whether the dashboard serves this installation at all: it names its account, the account is the
   * configured owner (when set) and allowed, and it is neither suspended nor gone (#1222).
   */
  private boolean served(
      GitHubInstallationClient.Installation installation, Optional<String> owner) {
    var account = installation.account();
    return account != null
        && account.login() != null
        && owner.filter(o -> !o.equalsIgnoreCase(account.login())).isEmpty()
        && allowedAccounts.allows(account.login())
        && installation.suspendedAt() == null
        && !removed(goneInstallations, installation.id());
  }

  /**
   * The repositories of one installation the user can read, by id, as {@code owner/name}, without
   * the ones a webhook said were removed (#1222). An installation GitHub refuses to list (404, or a
   * 403 that is not throttling, such as one suspended or uninstalled a moment ago) reads as having
   * none, so it never takes the user's other installations down with it.
   */
  private Map<Long, String> repositories(String userAuth, long installationId) {
    var repositories = new HashMap<Long, String>();
    List<GitHubInstallationClient.InstallationRepositoriesResponse.Repository> listed;
    try {
      listed =
          allPages(
              page ->
                  userClient
                      .listRepositories(userAuth, ACCEPT, installationId, PER_PAGE, page)
                      .repositories());
    } catch (WebApplicationException e) {
      var status = e.getResponse().getStatus();
      // A 403 can also be GitHub throttling the call; that is not a refusal, so it fails the
      // resolution like any other error instead of hiding the installation.
      var refused =
          status == 404
              || (status == 403
                  && GitHubApiError.of(e).map(err -> !err.isThrottled()).orElse(true));
      if (refused) {
        log.info(
            "GitHub refused to list installation {} ({}); skipping it", installationId, status);
        return repositories;
      }
      throw e;
    }
    for (var repo : listed) {
      if (repo.id() > 0
          && repo.name() != null
          && repo.owner() != null
          && repo.owner().login() != null
          && !removed(removedRepositories, new RepoScope(installationId, repo.id()))) {
        repositories.putIfAbsent(repo.id(), repo.owner().login() + "/" + repo.name());
      }
    }
    return repositories;
  }

  /**
   * Applies an installation change from the webhook (#1222): a gone installation and a removed
   * repository are left out of every scope from now on, until unsuspended or added again, and every
   * cached scope is dropped so the change is served at once. The lists are in memory only and each
   * entry lapses after {@link #REMOVAL_LIMIT}: by then, as after a restart, GitHub's own answer has
   * caught up, so a lost {@code unsuspend} or {@code added} delivery hides nothing for long.
   */
  void onInstallationChange(@Observes InstallationChange change) {
    var installationId = change.installationId();
    switch (change.kind()) {
      case GONE -> goneInstallations.put(installationId, clock.get().plus(REMOVAL_LIMIT));
      case BACK -> goneInstallations.remove(installationId);
      case REPOSITORIES_REMOVED ->
          change
              .repositories()
              .forEach(
                  r ->
                      removedRepositories.put(
                          new RepoScope(installationId, r.id()), clock.get().plus(REMOVAL_LIMIT)));
      case REPOSITORIES_ADDED ->
          change
              .repositories()
              .forEach(r -> removedRepositories.remove(new RepoScope(installationId, r.id())));
      case PERMISSIONS, REPOSITORY_RENAMED -> {
        // Nothing to remember: what a login may see, or a name, changed; the cleared cache
        // re-reads it.
      }
    }
    changeCount.incrementAndGet();
    cache.clear();
  }

  /**
   * Whether a webhook removed {@code key} within {@link #REMOVAL_LIMIT}; an expired removal is
   * forgotten, and GitHub's own answer counts again.
   */
  private <K> boolean removed(Map<K, Instant> removals, K key) {
    var until = removals.get(key);
    if (until == null) {
      return false;
    }
    if (clock.get().isBefore(until)) {
      return true;
    }
    removals.remove(key, until);
    return false;
  }

  /**
   * The scope without the installations and repositories a webhook removed, applied to every fresh
   * answer: one fetched while a change landed still leaves out what the change removed, and is not
   * cached, so the next request asks GitHub again.
   */
  private RepositoryScope withoutRemoved(RepositoryScope scope) {
    var kept = new HashMap<Long, RepositoryScope.Installation>();
    for (var installation : scope.installations().values()) {
      var repositories = new HashMap<>(installation.repositories());
      repositories
          .keySet()
          .removeIf(id -> removed(removedRepositories, new RepoScope(installation.id(), id)));
      if (!removed(goneInstallations, installation.id()) && !repositories.isEmpty()) {
        kept.put(
            installation.id(),
            new RepositoryScope.Installation(
                installation.id(),
                installation.account(),
                installation.installationWide(),
                repositories));
      }
    }
    return new RepositoryScope(kept);
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

  /**
   * Drops entries once the cache grows: expired ones that can no longer serve as a stale fallback,
   * so logins that never return do not stay.
   */
  void sweepExpiredEntries() {
    if (cache.size() < SCOPE_CACHE_SWEEP_THRESHOLD) {
      return;
    }
    var now = clock.get();
    var staleCutoff = now.minus(STALE_SCOPE_LIMIT);
    cache
        .entrySet()
        .removeIf(
            entry ->
                !now.isBefore(entry.getValue().retryAt())
                    && (entry.getValue().answeredAt() == null
                        || !entry.getValue().answeredAt().isAfter(staleCutoff)));
  }

  /** Visible for tests that assert the cache stays bounded. */
  int cacheSize() {
    return cache.size();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
