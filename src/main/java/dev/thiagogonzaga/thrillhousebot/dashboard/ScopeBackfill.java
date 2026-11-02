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

import dev.thiagogonzaga.thrillhousebot.github.GitHubAuthClient;
import dev.thiagogonzaga.thrillhousebot.github.GitHubInstallationClient;
import dev.thiagogonzaga.thrillhousebot.github.RepoScope;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.regex.Pattern;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fills the installation and repository id of rows stored before #1220, which were keyed by the
 * {@code owner/repo} name alone. Readers match both ids, so until a row is filled it is never
 * served.
 *
 * <p>Runs once at startup, and only when some row still lacks its ids. It lists the App's
 * installations and the repositories each can access, and matches every unscoped name (ignoring
 * case) to one of them. A name no installation lists may be a repository renamed since: GitHub
 * answers the old name with a redirect to the repository's id, which is matched when the
 * installation of that owner still has it. Everything else stays unmatched, and its rows keep a
 * null scope: a repository deleted, transferred to another account, or no longer installed.
 *
 * <p>Fails closed. When the installations cannot be listed nothing is written, and an installation
 * whose repositories cannot be listed matches nothing; the next start tries again, since unfilled
 * rows are looked up on every start.
 */
@ApplicationScoped
public class ScopeBackfill {

  private static final Logger log = LoggerFactory.getLogger(ScopeBackfill.class);
  private static final String ACCEPT = "application/vnd.github+json";
  private static final int PER_PAGE = 100;
  private static final int MAX_PAGES = 100;
  private static final Pattern REPOSITORY_ID_LOCATION = Pattern.compile("/repositories/(\\d+)$");

  private final ScopeBackfillStore store;
  private final GitHubAuthClient authClient;
  private final GitHubInstallationClient installationClient;

  @Inject
  public ScopeBackfill(
      ScopeBackfillStore store,
      GitHubAuthClient authClient,
      @RestClient GitHubInstallationClient installationClient) {
    this.store = store;
    this.authClient = authClient;
    this.installationClient = installationClient;
  }

  /** How many repository names were matched and filled, and how many stay unmatched. */
  public record Result(int matched, int unmatched, int rows) {}

  void onStart(@Observes StartupEvent event) {
    try {
      var result = backfill();
      if (result.matched() + result.unmatched() > 0) {
        log.info(
            "Scope backfill: matched {} repository name(s) and filled {} row(s); {} name(s)"
                + " unmatched stay hidden",
            result.matched(),
            result.rows(),
            result.unmatched());
      }
    } catch (RuntimeException e) {
      log.warn(
          "Scope backfill failed; rows stored before installation scoping stay hidden until the"
              + " next start",
          e);
    }
  }

  public Result backfill() {
    var names = store.unscopedRepositories();
    if (names.isEmpty()) {
      return new Result(0, 0, 0);
    }
    var jwt = "Bearer " + authClient.generateAppJwt();
    var installations =
        allPages(page -> installationClient.listInstallations(jwt, ACCEPT, PER_PAGE, page));
    var byName = new HashMap<String, RepoScope>();
    var byOwner = new HashMap<String, Installed>();
    for (var installation : installations) {
      installed(installation).ifPresent(i -> index(i, byName, byOwner));
    }
    int matched = 0;
    int rows = 0;
    for (var name : names) {
      var scope = Optional.ofNullable(byName.get(name)).or(() -> renamed(name, byOwner));
      if (scope.isPresent()) {
        matched++;
        rows += store.apply(name, scope.get());
      }
    }
    return new Result(matched, names.size() - matched, rows);
  }

  /** One installation's token and repositories, as {@code owner/repo} (lower-cased) to id. */
  private record Installed(long installationId, String auth, Map<String, Long> repositories) {}

  private Optional<Installed> installed(GitHubInstallationClient.Installation installation) {
    try {
      var auth = authClient.getAuthHeader(installation.id());
      var repositories = new HashMap<String, Long>();
      for (var repo :
          allPages(
              page ->
                  installationClient
                      .listInstallationRepositories(auth, ACCEPT, PER_PAGE, page)
                      .repositories())) {
        if (repo.id() > 0
            && repo.owner() != null
            && repo.owner().login() != null
            && repo.name() != null) {
          repositories.put(lower(repo.owner().login() + "/" + repo.name()), repo.id());
        }
      }
      return Optional.of(new Installed(installation.id(), auth, repositories));
    } catch (RuntimeException e) {
      log.warn(
          "Scope backfill could not list the repositories of installation {}; its rows stay"
              + " hidden until the next start: {}",
          installation.id(),
          e.getMessage());
      return Optional.empty();
    }
  }

  private static void index(
      Installed installed, Map<String, RepoScope> byName, Map<String, Installed> byOwner) {
    installed
        .repositories()
        .forEach(
            (name, id) -> {
              byName.put(name, new RepoScope(installed.installationId(), id));
              byOwner.put(name.substring(0, name.indexOf('/')), installed);
            });
  }

  /**
   * The scope of a repository renamed since its rows were stored: the old name redirects to the
   * repository's id, which must still be one the owner's installation can access.
   */
  private Optional<RepoScope> renamed(String name, Map<String, Installed> byOwner) {
    var slash = name.indexOf('/');
    var installed = byOwner.get(name.substring(0, Math.max(slash, 0)));
    if (installed == null) {
      return Optional.empty();
    }
    Response response = null;
    try {
      response =
          installationClient.getRepository(
              installed.auth(), ACCEPT, name.substring(0, slash), name.substring(slash + 1));
      return movedTo(response)
          .filter(id -> installed.repositories().containsValue(id))
          .map(id -> new RepoScope(installed.installationId(), id));
    } catch (RuntimeException e) {
      log.warn(
          "Scope backfill could not look up {}; its rows stay hidden until the next start: {}",
          name,
          e.getMessage());
      return Optional.empty();
    } finally {
      if (response != null) {
        response.close();
      }
    }
  }

  /** The repository id a 301 for a renamed repository points to, if this is one. */
  private static Optional<Long> movedTo(Response response) {
    if (response.getStatus() != 301) {
      return Optional.empty();
    }
    var matcher =
        REPOSITORY_ID_LOCATION.matcher(String.valueOf(response.getHeaderString("Location")));
    return matcher.find() ? Optional.of(Long.parseLong(matcher.group(1))) : Optional.empty();
  }

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
    throw new IllegalStateException("GitHub listing exceeded " + MAX_PAGES + " pages");
  }

  private static String lower(String value) {
    return value.toLowerCase(Locale.ROOT);
  }
}
