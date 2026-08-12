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
import jakarta.inject.Inject;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class GitHubAuthClient {

  private static final Logger log = LoggerFactory.getLogger(GitHubAuthClient.class);

  private static final String BEARER = "Bearer ";

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

  // Installation access tokens expire after 1 hour.
  private record CachedToken(String token, Instant expiresAt, long installationId) {}

  private final Map<Long, CachedToken> tokenCache = new ConcurrentHashMap<>();

  /**
   * Which installation a token this process minted belongs to, so a write GitHub answered with 401
   * can be traced back to the installation whose token has to be replaced.
   *
   * <p>Bounded to the current and the immediately superseded token per installation. The superseded
   * one has to stay resolvable: a dead token does not fail one write, it fails every write still
   * holding it — five within five seconds in #624 — and each of those after the first is presenting
   * a token the cache has already replaced. Forgetting it would leave every write but the first
   * unable to name its own installation.
   */
  private final Map<String, Long> tokenOwners = new ConcurrentHashMap<>();

  /**
   * The token each installation replaced most recently, the only one {@link #tokenOwners} keeps.
   */
  private final Map<Long, String> supersededTokens = new ConcurrentHashMap<>();

  private final AtomicReference<RSAPrivateKey> cachedPrivateKey = new AtomicReference<>();

  @Inject
  public GitHubAuthClient(ThrillhouseConfig config, @RestClient GitHubTokenApi tokenApi) {
    this.config = config;
    this.tokenApi = tokenApi;
    // Minting is the one thing only this bean can do, and the GitHub writes that need a dead token
    // replaced are interface default methods with nowhere to inject it. See GitHubTokenRefresh.
    GitHubTokenRefresh.SHARED.bind(this::mintedReplacementFor);
  }

  /**
   * Generates a JWT signed with the GitHub App's private key (RS256). JWT expires in 10 minutes
   * (GitHub maximum).
   */
  public String generateAppJwt() {
    try {
      Instant now = Instant.now();
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
    var cached = tokenCache.get(installationId);
    if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
      log.debug("Using cached installation token (expires at {})", cached.expiresAt());
      return cached.token();
    }

    log.info("Generating new installation token for installation {}", installationId);
    var jwt = generateAppJwt();
    var authHeader = "Bearer " + jwt;

    var response =
        tokenApi.createInstallationToken(authHeader, "application/vnd.github+json", installationId);

    var newToken = new CachedToken(response.token(), Instant.now().plus(TOKEN_TTL), installationId);
    var previous = tokenCache.put(installationId, newToken);
    tokenOwners.put(newToken.token(), installationId);
    if (previous != null) {
      // Keep the token just superseded resolvable for the writes still holding it, and forget the
      // one before it so the index cannot grow with the process's uptime.
      var forgotten = supersededTokens.put(installationId, previous.token());
      Optional.ofNullable(forgotten).ifPresent(tokenOwners::remove);
    }

    return newToken.token();
  }

  /** Returns an Authorization header value for GitHub API calls. */
  public String getAuthHeader(long installationId) {
    return BEARER + getInstallationToken(installationId);
  }

  /**
   * The header to repeat a call with after GitHub answered it {@code 401 Bad credentials}, or empty
   * when {@code deadAuthHeader} is not one this process issued and so names no installation.
   *
   * <p>Bound into {@link GitHubTokenRefresh} at construction; see that class for why a 401 is worth
   * repeating at all. The cache entry is dropped only while it still holds the dead token, because
   * a 401 arrives from every in-flight write at once: the first one through mints the replacement,
   * and the rest must be handed <em>that</em> replacement rather than each evicting it and minting
   * another, which would leave every write chasing a token the next one has already thrown away.
   *
   * <p>Kept separate from the method the constructor binds only because a constructor must not hand
   * out a reference to an overridable method — CDI subclasses this bean.
   */
  Optional<String> refreshedAuthHeader(String deadAuthHeader) {
    return mintedReplacementFor(deadAuthHeader);
  }

  private Optional<String> mintedReplacementFor(String deadAuthHeader) {
    if (deadAuthHeader == null || !deadAuthHeader.startsWith(BEARER)) {
      return Optional.empty();
    }
    var dead = deadAuthHeader.substring(BEARER.length());
    var installationId = tokenOwners.get(dead);
    if (installationId == null) {
      return Optional.empty();
    }
    log.info(
        "Installation token for installation {} was rejected — minting a replacement",
        installationId);
    // Expired in place rather than removed, so the replacement travels the same path a token that
    // simply aged out does and supersedes this one through the bookkeeping in the mint above.
    tokenCache.computeIfPresent(
        installationId,
        (id, cached) ->
            cached.token().equals(dead) ? new CachedToken(dead, Instant.EPOCH, id) : cached);
    return Optional.of(getAuthHeader(installationId));
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
