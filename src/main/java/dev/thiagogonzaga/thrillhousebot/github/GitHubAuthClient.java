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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class GitHubAuthClient {

  private static final Logger log = LoggerFactory.getLogger(GitHubAuthClient.class);

  private final ThrillhouseConfig config;
  private final GitHubTokenApi tokenApi;

  // Cached tokens — installation access tokens expire after 1 hour
  private record CachedToken(String token, Instant expiresAt, long installationId) {}

  private final Map<Long, CachedToken> tokenCache = new ConcurrentHashMap<>();

  // The configured private key never changes at runtime — parse it once.
  private final AtomicReference<RSAPrivateKey> cachedPrivateKey = new AtomicReference<>();

  @Inject
  public GitHubAuthClient(ThrillhouseConfig config, @RestClient GitHubTokenApi tokenApi) {
    this.config = config;
    this.tokenApi = tokenApi;
  }

  /**
   * Generates a JWT signed with the GitHub App's private key (RS256). JWT expires in 10 minutes
   * (GitHub maximum).
   */
  public String generateAppJwt() {
    try {
      Instant now = Instant.now();
      var privateKey = privateKey();

      // The issued-at and expiry claims are plain epoch seconds, which Nimbus serializes
      // the same way as its Date-based builder methods — this keeps java.util.Date out
      var claims =
          new JWTClaimsSet.Builder()
              .issuer(config.github().appId())
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

    // Cache with 50-minute TTL (tokens expire at 60 min — safety margin)
    var newToken =
        new CachedToken(
            response.token(), Instant.now().plus(50, ChronoUnit.MINUTES), installationId);
    tokenCache.put(installationId, newToken);

    return newToken.token();
  }

  /** Returns an Authorization header value for GitHub API calls. */
  public String getAuthHeader(long installationId) {
    return "Bearer " + getInstallationToken(installationId);
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
