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

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.thiagogonzaga.thrillhousebot.config.RsaPrivateKeyParser;
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class GitHubAuthClient {

  private static final Logger log = LoggerFactory.getLogger(GitHubAuthClient.class);

  private static final String BEARER = "Bearer ";

  private static final String ACCEPT = "application/vnd.github+json";

  /**
   * How long GitHub itself honours an installation token. Not ours to choose — every duration below
   * is reasoned against it, so it is written down once rather than repeated as a literal.
   */
  static final Duration GITHUB_TOKEN_LIFETIME = Duration.ofMinutes(60);

  /**
   * How long a minted installation token is reused before a new one is fetched.
   *
   * <p>GitHub expires it at 60 minutes, so whatever is left over is the longest a piece of work may
   * take between reading the header and its final write. The 10 minutes this used to leave were
   * chosen when reviews were short; with {@code AI_TIMEOUT=900s} a <em>single</em> model call is
   * allowed 15 minutes and a multi-call review takes considerably more, so a review starting late
   * in the window routinely outlived its credential (#624). Halving the token's life doubles the
   * worst-case mint rate to two per installation per hour, which is nothing against the App's
   * limits, and it only narrows the window — {@link GitHubTokenRefresh} is what closes it.
   */
  static final Duration TOKEN_TTL = Duration.ofMinutes(30);

  private final ThrillhouseConfig config;
  private final GitHubTokenApi tokenApi;

  /**
   * What a token may reach: one repository of an installation, by its name as the caller has it
   * (GitHub is sent that spelling), or the whole installation when {@code repository} is {@code
   * null}.
   *
   * <p>A token is minted for the repository the work is about, so one that leaks through a log, a
   * dependency or a request sent to the wrong place opens that repository and not every other one
   * the App is installed on. Only work about the installation itself, such as listing its
   * repositories, takes the unscoped token.
   */
  private record TokenScope(long installationId, String repository) {

    static TokenScope installation(long installationId) {
      return new TokenScope(installationId, null);
    }

    static TokenScope repository(long installationId, String repository) {
      if (repository == null || repository.isBlank()) {
        // Never widened to the installation: a caller that lost the name must not get more.
        throw new IllegalArgumentException(
            "A repository name is required for a repository-scoped installation token");
      }
      return new TokenScope(installationId, repository.strip());
    }
  }

  // Installation access tokens expire after 1 hour.
  private record CachedToken(String token, Instant expiresAt) {}

  private final Map<TokenScope, CachedToken> tokenCache = new ConcurrentHashMap<>();

  /**
   * The longest a write can still be holding a token it read from the cache. The cache hands one
   * out for up to {@link #TOKEN_TTL}, and the holder then runs for as long as its work takes — a
   * review's model calls, its retries and its backoff. Two hours is far past any review this bot
   * has run; it is the term that decides how long the index below has to remember a token, so it is
   * stated rather than folded into a single opaque number.
   */
  static final Duration LONGEST_WRITE_IN_FLIGHT = Duration.ofHours(2);

  /**
   * How long a token this process minted stays traceable back to its installation.
   *
   * <p>This has to <em>outlive</em> the token, not match it, and getting that wrong is subtle
   * enough to be worth spelling out. An entry is consulted only when a write is holding a token
   * GitHub has just refused, and the ordinary reason for that refusal is that the token has passed
   * {@link #GITHUB_TOKEN_LIFETIME}. Every lookup that matters therefore happens when the entry is
   * <em>already older than the token's whole life</em>. Retaining for exactly that life puts the
   * sweep boundary precisely on the trigger condition: the refresh mints at {@code issued + 60min +
   * something}, sweeps everything older than {@code now - 60min}, and deletes the entry for the
   * very token whose 401 provoked it — so the first write of a cascade is rescued and every later
   * holder of the same token finds nothing and is stranded, which is #624 again.
   *
   * <p>Retention is therefore the token's whole life plus how late the last write holding it can
   * still fail ({@link #TOKEN_TTL} + {@link #LONGEST_WRITE_IN_FLIGHT}), with room to spare. At two
   * mints per installation per hour the index holds a dozen entries per installation, which is
   * nothing; the sweep is the cheaper side of this trade by a wide margin.
   */
  static final Duration OWNER_RETENTION = Duration.ofHours(6);

  /** A token this process minted: what it was minted for, and when it was issued. */
  private record TokenOwner(TokenScope scope, Instant issuedAt) {}

  /**
   * Which installation a token this process minted belongs to, so a write GitHub answered with 401
   * can be traced back to the installation whose token has to be replaced.
   *
   * <p>Entries are retired by <em>age</em>, not by generation, and that distinction is the whole
   * correctness argument. Refreshes of one installation are not serialized: {@code
   * ReviewDispatcher} serializes per pull request, not per installation, and runs each PR's review
   * on its own virtual thread — so two reviews in the same installation share one cached token,
   * cross its expiry together, and refresh concurrently. Both mint, which costs an extra token
   * request and no more, because GitHub does not invalidate one installation token by issuing
   * another.
   *
   * <p>Keeping only "the current and the one it replaced" would not survive that. Two concurrent
   * mints roll a one-deep superseded slot forward twice, and the second one's bookkeeping evicts
   * the <em>original</em> token while a third write is still about to present it. That write's 401
   * then finds no owner, gets no replacement, and is lost — precisely the failure this class exists
   * to prevent, reintroduced by the bookkeeping meant to bound the index. An age cannot be raced: a
   * token minted seconds ago is resolvable no matter how many refreshes have happened since.
   */
  private final Map<String, TokenOwner> tokenOwners = new ConcurrentHashMap<>();

  private final AtomicReference<RSAPrivateKey> cachedPrivateKey = new AtomicReference<>();

  private final Supplier<Instant> clock;

  @Inject
  public GitHubAuthClient(ThrillhouseConfig config, @RestClient GitHubTokenApi tokenApi) {
    this(config, tokenApi, Instant::now);
  }

  /**
   * Everything here turns on durations measured in hours — how long a token is cached, how long its
   * owner stays resolvable — so the tests need a clock they can move rather than one they must
   * outwait. Nothing else in this class reads the time directly.
   */
  GitHubAuthClient(ThrillhouseConfig config, GitHubTokenApi tokenApi, Supplier<Instant> clock) {
    this.config = config;
    this.tokenApi = tokenApi;
    this.clock = clock;
    // Minting is the one thing only this bean can do, and the GitHub writes that need a dead token
    // replaced are interface default methods with nowhere to inject it. See GitHubTokenRefresh.
    GitHubTokenRefresh.SHARED.bind(this::mintedReplacementFor);
  }

  /**
   * Generates a JWT signed with the GitHub App's private key (RS256). JWT expires in 10 minutes
   * (GitHub maximum).
   */
  public String generateAppJwt() {
    return signedAppJwt();
  }

  private String signedAppJwt() {
    try {
      Instant now = clock.get();
      var privateKey = privateKey();

      var claims =
          new JWTClaimsSet.Builder()
              // GitHub rejects an "iss" with padding, but boot validation accepts a padded app id.
              .issuer(config.github().appId().strip())
              .claim("iat", now.getEpochSecond())
              .claim("exp", now.plus(10, ChronoUnit.MINUTES).getEpochSecond())
              .build();

      var header = new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build();

      var jwt = new SignedJWT(header, claims);
      jwt.sign(new RSASSASigner(privateKey));

      return jwt.serialize();
    } catch (JOSEException | IllegalArgumentException e) {
      log.error("Failed to generate GitHub App JWT", e);
      throw new GitHubAuthException("JWT generation failed", e);
    }
  }

  /**
   * Returns an installation access token, reusing a cached one if still valid. Tokens expire after
   * 1 hour per GitHub.
   */
  public String getInstallationToken(long installationId) {
    return token(TokenScope.installation(installationId));
  }

  private String token(TokenScope scope) {
    var cached = tokenCache.get(scope);
    if (cached != null && clock.get().isBefore(cached.expiresAt())) {
      log.debug("Using cached installation token (expires at {})", cached.expiresAt());
      return cached.token();
    }

    var installationId = scope.installationId();
    var jwt = signedAppJwt();
    var authHeader = BEARER + jwt;

    GitHubTokenApi.InstallationTokenResponse response;
    if (scope.repository() == null) {
      log.info("Generating new installation token for installation {}", installationId);
      response = tokenApi.createInstallationToken(authHeader, ACCEPT, installationId);
    } else {
      log.info(
          "Generating new installation token for installation {}, limited to one repository",
          installationId);
      response =
          tokenApi.createRepositoryToken(
              authHeader,
              ACCEPT,
              installationId,
              new GitHubTokenApi.RepositoryScope(List.of(scope.repository())));
    }

    // Read after the round trip, so the recorded instant is no earlier than GitHub's own issue
    // time and the entry below is never retired sooner than the token it names.
    var issuedAt = clock.get();
    var newToken = new CachedToken(response.token(), issuedAt.plus(TOKEN_TTL));
    // One entry per repository reviewed would otherwise stay for as long as the process runs.
    tokenCache.values().removeIf(entry -> !issuedAt.isBefore(entry.expiresAt()));
    tokenCache.put(scope, newToken);
    // Every token this process hands out stays traceable well past GitHub's expiry, so a write
    // still holding an older one can always name the installation, and the repository, that has
    // to replace it.
    tokenOwners.put(newToken.token(), new TokenOwner(scope, issuedAt));
    forgetOwnersOlderThanRetention(issuedAt);

    return newToken.token();
  }

  /**
   * Drops index entries for tokens issued more than {@link #OWNER_RETENTION} before {@code now} —
   * long past the point where any write could still be presenting one. Swept on every mint, which
   * bounds the index at the tokens issued in that window.
   *
   * <p>The cutoff is absolute, so sweeping on behalf of one installation only ever retires entries
   * that are genuinely that old, whichever installation they belong to.
   */
  private void forgetOwnersOlderThanRetention(Instant now) {
    var cutoff = now.minus(OWNER_RETENTION);
    tokenOwners.values().removeIf(owner -> owner.issuedAt().isBefore(cutoff));
  }

  /**
   * An Authorization header value whose token reaches every repository of the installation. Only
   * for calls about the installation itself, such as listing its repositories; work on one
   * repository takes {@link #getAuthHeader(long, String)}.
   */
  public String getAuthHeader(long installationId) {
    return BEARER + token(TokenScope.installation(installationId));
  }

  /**
   * An Authorization header value for work on one repository: its token reaches {@code repository}
   * (the name, without the owner) and no other repository of the installation, with the same
   * permissions. Minting fails, and nothing wider is handed out in its place, when the installation
   * does not cover that repository.
   */
  public String getAuthHeader(long installationId, String repository) {
    return BEARER + token(TokenScope.repository(installationId, repository));
  }

  /**
   * Drops a gone installation's cached tokens (#1222), so nothing keeps calling GitHub with them
   * after the installation was uninstalled or suspended.
   */
  void onInstallationChange(@Observes InstallationChange change) {
    if (change.kind() == InstallationChange.Kind.GONE) {
      tokenCache.keySet().removeIf(scope -> scope.installationId() == change.installationId());
    }
  }

  /**
   * The header to repeat a call with after GitHub answered it {@code 401 Bad credentials}, or empty
   * when {@code deadAuthHeader} is not one this process issued and so names no installation.
   *
   * <p>Bound into {@link GitHubTokenRefresh} at construction; see that class for why a 401 is worth
   * repeating at all.
   *
   * <p>A dead token does not fail one write, it fails every write still holding it — five within
   * five seconds in #624 — and those writes are on different threads. Nothing here coordinates
   * them, and nothing needs to: the cache entry is expired only while it still holds the dead
   * token, so a refresher arriving after another has already replaced it reads the replacement
   * instead of minting again; two that arrive together both mint, and two valid installation tokens
   * are as good as one, since GitHub does not invalidate either. What must not happen is a holder
   * losing the ability to name its installation, and {@link #tokenOwners} retires by age precisely
   * so that cannot be raced. A lock across the token endpoint's round trip would buy only the saved
   * request.
   *
   * <p>Kept separate from the method the constructor binds only because a constructor must not hand
   * out a reference to an overridable method — CDI subclasses this bean. For the same reason
   * everything it reaches is private, and the public token methods only delegate to it.
   */
  Optional<String> refreshedAuthHeader(String deadAuthHeader) {
    return mintedReplacementFor(deadAuthHeader);
  }

  private Optional<String> mintedReplacementFor(String deadAuthHeader) {
    if (deadAuthHeader == null || !deadAuthHeader.startsWith(BEARER)) {
      return Optional.empty();
    }
    var dead = deadAuthHeader.substring(BEARER.length());
    var owner = tokenOwners.get(dead);
    if (owner == null) {
      return Optional.empty();
    }
    var scope = owner.scope();
    log.info(
        "Installation token for installation {} was rejected — minting a replacement",
        scope.installationId());
    // Expired in place rather than removed, so the replacement travels the same path a token that
    // simply aged out does and supersedes this one through the bookkeeping in the mint above. The
    // replacement is minted for what the dead token was: a refresh never widens a token.
    tokenCache.computeIfPresent(
        scope,
        (key, cached) ->
            cached.token().equals(dead) ? new CachedToken(dead, Instant.EPOCH) : cached);
    return Optional.of(BEARER + token(scope));
  }

  private RSAPrivateKey privateKey() {
    var key = cachedPrivateKey.get();
    if (key == null) {
      key = RsaPrivateKeyParser.parse(config.github().privateKey());
      cachedPrivateKey.set(key);
    }
    return key;
  }
}
