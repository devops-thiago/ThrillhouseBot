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
import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
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
    } catch (NoSuchAlgorithmException | InvalidKeySpecException | JOSEException e) {
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

  private RSAPrivateKey privateKey() throws NoSuchAlgorithmException, InvalidKeySpecException {
    var key = cachedPrivateKey.get();
    if (key == null) {
      key = parsePrivateKey(config.github().privateKey());
      cachedPrivateKey.set(key);
    }
    return key;
  }

  /**
   * Parses a PEM-encoded PKCS#1 RSA private key by converting it to PKCS#8 format. Handles keys
   * with \n escape sequences from environment variables.
   */
  private RSAPrivateKey parsePrivateKey(String key)
      throws NoSuchAlgorithmException, InvalidKeySpecException {
    var pem =
        key.replace("\\n", "\n")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replaceAll("\\s", "");

    var pkcs1Bytes = Base64.getDecoder().decode(pem);
    byte[] pkcs8Bytes = pkcs1ToPkcs8(pkcs1Bytes);
    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
    return (RSAPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8Bytes));
  }

  /**
   * Converts PKCS#1 RSA private key bytes to PKCS#8 format by wrapping with the PKCS#8 DER envelope
   * (version + AlgorithmIdentifier for rsaEncryption + OCTET STRING).
   */
  private static byte[] pkcs1ToPkcs8(byte[] pkcs1Key) {
    // AlgorithmIdentifier for rsaEncryption (DER-encoded OID 1.2.840.113549.1.1.1 + NULL)
    byte[] algId = {
      0x30,
      0x0d,
      0x06,
      0x09,
      0x2a,
      (byte) 0x86,
      0x48,
      (byte) 0x86,
      (byte) 0xf7,
      0x0d,
      0x01,
      0x01,
      0x01,
      0x05,
      0x00,
    };

    // OCTET STRING wrapping the PKCS#1 key (tag 0x04, long-form length 0x82 + 2 length bytes)
    var keyLen = pkcs1Key.length;
    var octetLen = keyLen + 4; // tag + 0x82 + 2-byte length + key bytes

    // Total content length = version(3) + algId + octetString
    var versionLen = 3; // INTEGER 0 -> 0x02 0x01 0x00
    var contentLen = versionLen + algId.length + octetLen;

    byte[] pkcs8 = new byte[4 + contentLen]; // SEQUENCE header(4) + content

    // SEQUENCE (tag 0x30, long-form length 0x82 + 2-byte length)
    pkcs8[0] = 0x30;
    pkcs8[1] = (byte) 0x82;
    pkcs8[2] = (byte) ((contentLen >> 8) & 0xFF);
    pkcs8[3] = (byte) (contentLen & 0xFF);

    // Version: INTEGER 0
    pkcs8[4] = 0x02;
    pkcs8[5] = 0x01;
    pkcs8[6] = 0x00;

    // AlgorithmIdentifier
    System.arraycopy(algId, 0, pkcs8, 7, algId.length);

    // OCTET STRING header
    var off = 7 + algId.length;
    pkcs8[off] = 0x04;
    pkcs8[off + 1] = (byte) 0x82;
    pkcs8[off + 2] = (byte) ((keyLen >> 8) & 0xFF);
    pkcs8[off + 3] = (byte) (keyLen & 0xFF);

    // PKCS#1 key bytes
    System.arraycopy(pkcs1Key, 0, pkcs8, off + 4, keyLen);

    return pkcs8;
  }
}
