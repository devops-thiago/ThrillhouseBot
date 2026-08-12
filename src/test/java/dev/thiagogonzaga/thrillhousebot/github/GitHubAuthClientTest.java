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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class GitHubAuthClientTest {

  // Real PKCS#1 RSA 2048-bit test key with \n escape sequences (as from env var)
  private static final String TEST_PRIVATE_KEY =
      "-----BEGIN RSA PRIVATE KEY-----\\n"
          + "MIIEowIBAAKCAQEAqEcn0Wmzxjw+MTxPpYpetynJEC+u5dV87wWx2m6Xs8TJB3dn\\n"
          + "GRemwG/x1/DZMLk5WzMOIxYBBYUQ3HQlB+tobP3EyD9TwQDsR9/CMhvMbZ4IQlBD\\n"
          + "rbV4cyvcQkEPGP4ojqr4TU7+GQh8ZlcA6QxGgQrgyAEdGaiOPkBtrbw+oOUdvAMF\\n"
          + "kU0+lvd0BuhJ0vEkyLz66reMwfhRMoNZcGaLogVwE8aEAnabQhYw5Xgt5A9o/SOt\\n"
          + "1i+PqvjzUDP/XcJzuKS2Wak0LAjp/HuU2JnpPIjwE2qwZ9WcJC2YYbJWDBYP6rbh\\n"
          + "uFghflQxXOjmRQL8RAclma5AyjQvUk0VLOfMnwIDAQABAoIBAA3i0UZWkp8pGXi2\\n"
          + "oVvnrykuKYlMZgJRO539uk9sENikxHH5SGipqvj2Q96t4T5ECpeb/u6mQi9Sa7HF\\n"
          + "Y8jjhBp6VmKv1xl2GglUTZIU+SmSgNv4A52x+96FIAfXlodZcb9UHGlEu3MVYC6R\\n"
          + "v2F7wdWkMChQ7zXO0u4QIvzTK9fa7eVffnK99PgMAtPBHMwMhuPgRc+Ur0OWE+Xd\\n"
          + "7CppB+szpJAGmvuWyuoulfjp7ynPJGLCOds2r+FWvPwXFi80RCvoppaQ9oKUWxiB\\n"
          + "dQAtAwr0myjvsQJBsWVlav75tlayHDo1PdnPbTlp7n7hBlZBL5TYziOqK6V+TYsE\\n"
          + "/9uruAECgYEA3Yhqe0PcrGttn7OIWPqlZpjqocE0H4taZnd90mA/m9sM9Ans1pJ9\\n"
          + "3eAxzluqDZCCYFrVhAR82UeB8FMOt1w8wMvLGMOOa586FYNI16UzuJp5t7xWasoc\\n"
          + "zpnohKlfmDxYYGMzXjcU3KWhTeIkoJO/sIwVZ67s++XQwlqbHFzlOL8CgYEAwnWc\\n"
          + "51s+N8F03Wzwk9HLmpHxs0ydO5dPjQFfj8Hk7tHIx5q94921x/CXH4hLeMO5zjl4\\n"
          + "vOAQGr4KBqRWZ4TQhXkea6Kfd1dGm9E4KKx8wm7zqhDikgVbeGBKXDjblCKnlTHb\\n"
          + "v2JWoNSStBqZnHLM3ZxH8ZZKY/YYTLcyNuMnhCECgYBRCsGhfG7zKI2++aesnWz6\\n"
          + "voA/UnWmAI2+pIID/y/l7VmswSDCUm73RzgRPNlWAwKfCzvHAvlFZ3Jyn3/ntjeH\\n"
          + "dEZFNe0ZE/PkwNVaBlaIdwKGI8Edafjl38n/FhMhlxnhkQjOs6nPGkyLOGqbz9E1\\n"
          + "XdnKx2RstmMLZqgN1TIJ8wKBgC8dnhm0UtvhhLZNufCm6WUXLW/bBVG19LFefs/v\\n"
          + "E9AFhldOl+nJA01hbsxWEqs9CRz9cdKZm21PVFCNqt3EIV3lnchIi8i3ncUNKUU5\\n"
          + "nbTieylek/b7U1FUS1AS+qjmyKHuhabWZdTsDGuU8lkku5yKTCgt2PJlYzfbP1Br\\n"
          + "M1zhAoGBAMHT2Y3JiLkfUifKEytraOpIwjcKxX5x6qkVjZ0W8MqGUn0+B9IY9jpo\\n"
          + "yQIy96VtO8p+PIXh6FAVrGzDGU/FUd+vlKqo7u73bF6hdWps/t6CuZKG9vIQNjPs\\n"
          + "qK75ejcwlPmFYeQJscpQ2c8KNqcewsA53bJWYmroAc/na7JCfpf7\\n"
          + "-----END RSA PRIVATE KEY-----";

  @Mock private GitHubTokenApi tokenApi;

  @Mock private ThrillhouseConfig config;

  @Mock private ThrillhouseConfig.GitHubConfig githubConfig;

  private GitHubAuthClient authClient;

  /** A clock the tests move by hand; retention is measured in hours and cannot be outwaited. */
  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-08-12T13:32:03Z"));

  private void advance(Duration by) {
    now.updateAndGet(instant -> instant.plus(by));
  }

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(config.github()).thenReturn(githubConfig);
    when(githubConfig.appId()).thenReturn("123456");
    when(githubConfig.privateKey()).thenReturn(TEST_PRIVATE_KEY);
    authClient = new GitHubAuthClient(config, tokenApi, now::get);
  }

  @Test
  void shouldGenerateAppJwt() {
    var jwt = authClient.generateAppJwt();

    assertNotNull(jwt);
    assertFalse(jwt.isEmpty());

    // JWT has 3 parts separated by dots
    var parts = jwt.split("\\.");
    assertEquals(3, parts.length);

    // Header should decode to valid JSON with RS256 alg
    var header = new String(java.util.Base64.getUrlDecoder().decode(parts[0]));
    assertTrue(header.contains("RS256"));
    assertTrue(header.contains("JWT"));

    // Payload should contain the app ID as issuer
    var payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
    assertTrue(payload.contains("123456"));
    assertTrue(payload.contains("iat"));
    assertTrue(payload.contains("exp"));
  }

  @Test
  void shouldStripWhitespaceFromAppIdInIssuer() throws Exception {
    // StartupConfigValidator accepts a padded-but-numeric app id; the minted JWT must carry the
    // stripped value as "iss" so GitHub does not reject it on the first call.
    when(githubConfig.appId()).thenReturn("  123456  ");

    var jwt = authClient.generateAppJwt();

    var issuer = com.nimbusds.jwt.SignedJWT.parse(jwt).getJWTClaimsSet().getIssuer();
    assertEquals("123456", issuer);
  }

  @Test
  void shouldParsePrivateKeyWithNewlineEscapes() {
    // The private key contains \\n escape sequences — generateAppJwt() must parse them
    var jwt = authClient.generateAppJwt();
    assertNotNull(jwt);
    assertFalse(jwt.isEmpty());
  }

  @Test
  void shouldCacheInstallationToken() {
    var installationId = 42L;
    var response =
        new GitHubTokenApi.InstallationTokenResponse("token-abc", "2025-01-01T00:00:00Z");

    when(tokenApi.createInstallationToken(anyString(), anyString(), eq(installationId)))
        .thenReturn(response);

    var firstToken = authClient.getInstallationToken(installationId);
    assertEquals("token-abc", firstToken);

    // Second call should use cache — API should NOT be called again
    var secondToken = authClient.getInstallationToken(installationId);
    assertEquals("token-abc", secondToken);

    // Verify API was only called once
    verify(tokenApi, times(1))
        .createInstallationToken(anyString(), anyString(), eq(installationId));
  }

  @Test
  void shouldGetSeparateTokensForDifferentInstallations() {
    var installId1 = 10L;
    var installId2 = 20L;

    var token1 = new GitHubTokenApi.InstallationTokenResponse("token-one", "2025-01-01T00:00:00Z");
    var token2 = new GitHubTokenApi.InstallationTokenResponse("token-two", "2025-01-01T00:00:00Z");

    when(tokenApi.createInstallationToken(anyString(), anyString(), eq(installId1)))
        .thenReturn(token1);
    when(tokenApi.createInstallationToken(anyString(), anyString(), eq(installId2)))
        .thenReturn(token2);

    var t1 = authClient.getInstallationToken(installId1);
    var t2 = authClient.getInstallationToken(installId2);

    assertEquals("token-one", t1);
    assertEquals("token-two", t2);

    // Each installation should have its own cache entry
    verify(tokenApi, times(1)).createInstallationToken(anyString(), anyString(), eq(installId1));
    verify(tokenApi, times(1)).createInstallationToken(anyString(), anyString(), eq(installId2));
  }

  @Test
  void shouldReturnBearerAuthHeader() {
    var installationId = 7L;
    var response =
        new GitHubTokenApi.InstallationTokenResponse("bearer-token", "2025-01-01T00:00:00Z");

    when(tokenApi.createInstallationToken(anyString(), anyString(), eq(installationId)))
        .thenReturn(response);

    var header = authClient.getAuthHeader(installationId);
    assertEquals("Bearer bearer-token", header);
  }

  @Test
  void shouldReuseParsedPrivateKeyAcrossJwtGenerations() {
    var first = authClient.generateAppJwt();

    // If the parsed key were not cached, this unparseable value would make the next call fail
    when(githubConfig.privateKey()).thenReturn("not-a-valid-key");
    var second = authClient.generateAppJwt();

    assertNotNull(first);
    assertNotNull(second);
  }

  @Test
  void shouldHandleNullPrivateKeyGracefully() {
    when(githubConfig.privateKey()).thenReturn(null);

    assertThrows(RuntimeException.class, () -> authClient.generateAppJwt());
  }

  @Test
  void shouldHandleEmptyPrivateKeyGracefully() {
    when(githubConfig.privateKey()).thenReturn("");

    assertThrows(RuntimeException.class, () -> authClient.generateAppJwt());
  }

  /**
   * #624: the margin the TTL leaves against GitHub's 60-minute expiry is the whole budget a review
   * has between reading its header and its last write. With {@code AI_TIMEOUT=900s} a single model
   * call may take 15 minutes on its own, and a review makes several — the old 50-minute TTL left 10
   * minutes, so a review acquiring a token late in the window could not finish one call inside it.
   */
  @Test
  void theCacheMustLeaveRoomForAWholeModelCallBeforeTheTokenExpires() {
    var githubExpiry = java.time.Duration.ofMinutes(60);
    var longestSingleModelCall = java.time.Duration.ofMinutes(15);

    var margin = githubExpiry.minus(GitHubAuthClient.TOKEN_TTL);

    assertTrue(
        margin.compareTo(longestSingleModelCall) >= 0,
        "a cached token must outlive one full-length model call, but only " + margin + " is left");
  }

  private static GitHubTokenApi.InstallationTokenResponse token(String value) {
    return new GitHubTokenApi.InstallationTokenResponse(value, "2026-01-01T00:00:00Z");
  }

  /** What GitHub answers a write with once the installation token behind it has expired. */
  private static jakarta.ws.rs.WebApplicationException badCredentials() {
    return new jakarta.ws.rs.WebApplicationException(
        jakarta.ws.rs.core.Response.status(401)
            .entity("{\"message\":\"Bad credentials\",\"status\":\"401\"}")
            .build());
  }

  /**
   * The regression #624 is about, end to end and with nothing stubbed between the two halves: a
   * review reads its {@code Authorization} header when it starts, spends fourteen minutes on the
   * model, and posts with a token GitHub has since stopped accepting. Before the fix the 401
   * propagated and the finished, paid-for review was discarded; now the rejection mints a
   * replacement and the same generation is posted with it.
   */
  @Test
  void aReviewPostThatOutlivedItsInstallationTokenIsRepostedWithAFreshToken() {
    var installationId = 532L;
    when(tokenApi.createInstallationToken(anyString(), anyString(), eq(installationId)))
        .thenReturn(token("expired-token"), token("minted-token"));
    var auth = authClient.getAuthHeader(installationId);
    assertEquals("Bearer expired-token", auth);

    var reviewClient = mock(GitHubReviewClient.class);
    when(reviewClient.createReview(
            anyString(), anyString(), anyString(), anyString(), anyInt(), any()))
        .thenCallRealMethod();
    var request =
        new GitHubReviewClient.CreateReviewRequest(
            "a532aff", "the generated review", "COMMENT", List.of());
    var posted =
        new GitHubReviewClient.ReviewResponse(
            94131141478L, "the generated review", "COMMENTED", "a532aff", null);
    when(reviewClient.createReviewOnce("Bearer expired-token", "json", "o", "r", 532, request))
        .thenThrow(badCredentials());
    when(reviewClient.createReviewOnce("Bearer minted-token", "json", "o", "r", 532, request))
        .thenReturn(posted);

    var result = reviewClient.createReview(auth, "json", "o", "r", 532, request);

    assertSame(posted, result, "the review the model already produced must survive the 401");
    verify(reviewClient).createReviewOnce("Bearer minted-token", "json", "o", "r", 532, request);
  }

  @Test
  void refreshingReplacesTheRejectedTokenSoLaterCallersGetTheNewOne() {
    var installationId = 42L;
    when(tokenApi.createInstallationToken(anyString(), anyString(), eq(installationId)))
        .thenReturn(token("expired-token"), token("minted-token"));
    var dead = authClient.getAuthHeader(installationId);

    var fresh = authClient.refreshedAuthHeader(dead);

    assertEquals(Optional.of("Bearer minted-token"), fresh);
    assertEquals(
        "Bearer minted-token",
        authClient.getAuthHeader(installationId),
        "the replacement must be cached, not minted again for every later call");
    verify(tokenApi, times(2))
        .createInstallationToken(anyString(), anyString(), eq(installationId));
  }

  /**
   * A dead token does not fail one write, it fails every write still holding it — the #624 log has
   * five 401s in five seconds. The second one through must be handed the replacement the first one
   * minted rather than evicting it and minting a third.
   */
  @Test
  void aSecondWriteHoldingTheSameDeadTokenGetsTheReplacementAlreadyMinted() {
    var installationId = 42L;
    when(tokenApi.createInstallationToken(anyString(), anyString(), eq(installationId)))
        .thenReturn(token("expired-token"), token("minted-token"));
    var dead = authClient.getAuthHeader(installationId);
    authClient.refreshedAuthHeader(dead);

    var second = authClient.refreshedAuthHeader(dead);

    assertEquals(Optional.of("Bearer minted-token"), second);
    verify(tokenApi, times(2))
        .createInstallationToken(anyString(), anyString(), eq(installationId));
  }

  /**
   * Every token stays resolvable while GitHub would still accept it, however many refreshes have
   * happened since — retention is by age, so no number of newer tokens can strand a holder of an
   * older one.
   */
  @Test
  void everyTokenStillWithinGitHubsExpiryKeepsNamingItsInstallation() {
    var installationId = 42L;
    when(tokenApi.createInstallationToken(anyString(), anyString(), eq(installationId)))
        .thenReturn(token("first"), token("second"), token("third"));
    var first = authClient.getAuthHeader(installationId);
    var second = authClient.refreshedAuthHeader(first).orElseThrow();

    var third = authClient.refreshedAuthHeader(second).orElseThrow();

    assertEquals("Bearer third", third);
    assertEquals(
        Optional.of("Bearer third"),
        authClient.refreshedAuthHeader(second),
        "the token just superseded still names its installation");
    assertEquals(
        Optional.of("Bearer third"),
        authClient.refreshedAuthHeader(first),
        "and so does the generation before that, which GitHub would still have accepted");
  }

  /**
   * The bug this replaced: retention was set to GitHub's 60-minute token lifetime, which is exactly
   * the age at which a token starts drawing 401s. The refresh's own sweep therefore deleted the
   * entry whose 401 provoked it, and every later write in the same cascade — the second of the five
   * in five seconds — looked up a token the first refresh had just erased and got nothing back.
   */
  @Test
  void aRefreshDoesNotSweepAwayTheEntryTheRestOfItsCascadeStillNeeds() {
    var installationId = 42L;
    when(tokenApi.createInstallationToken(anyString(), anyString(), eq(installationId)))
        .thenReturn(token("first"), token("second"));
    var dead = authClient.getAuthHeader(installationId);

    // The only reason a write holding this token draws 401 at all: GitHub has expired it.
    advance(GitHubAuthClient.GITHUB_TOKEN_LIFETIME.plusMinutes(1));
    assertEquals(Optional.of("Bearer second"), authClient.refreshedAuthHeader(dead));

    // The straggler, a few seconds behind the first write of the cascade.
    advance(Duration.ofSeconds(4));
    assertEquals(
        Optional.of("Bearer second"),
        authClient.refreshedAuthHeader(dead),
        "the refresh must not retire the entry its own cascade is still resolving against");
  }

  /** The constructor CDI uses: same behaviour, on the real clock rather than a movable one. */
  @Test
  void theInjectedConstructorMintsOnTheSystemClock() {
    var installationId = 7L;
    when(tokenApi.createInstallationToken(anyString(), anyString(), eq(installationId)))
        .thenReturn(token("wall-clock"));
    var client = new GitHubAuthClient(config, tokenApi);

    assertEquals("Bearer wall-clock", client.getAuthHeader(installationId));
    assertEquals(
        "Bearer wall-clock",
        client.getAuthHeader(installationId),
        "and caches it, so the TTL is measured against a clock that actually advances");
    verify(tokenApi, times(1))
        .createInstallationToken(anyString(), anyString(), eq(installationId));
  }

  /**
   * The invariant the constant has to satisfy, stated so it cannot silently drift back onto the
   * trigger condition: an entry is only ever consulted after GitHub has stopped honouring the token
   * it names, by a write that may have been holding it since the cache last handed it out.
   */
  @Test
  void theOwnerIndexMustOutliveEveryTokenItNames() {
    var latestPossibleLookup =
        GitHubAuthClient.TOKEN_TTL.plus(GitHubAuthClient.LONGEST_WRITE_IN_FLIGHT);

    assertTrue(
        GitHubAuthClient.OWNER_RETENTION.compareTo(GitHubAuthClient.GITHUB_TOKEN_LIFETIME) > 0,
        "retention that only matches the token lifetime sweeps on exactly the 401 it must survive");
    assertTrue(
        GitHubAuthClient.OWNER_RETENTION.compareTo(latestPossibleLookup) > 0,
        "retention must cover a write that read the token at the end of the cache window");
  }

  /** Past retention the entry really is dead weight, and the next mint sweeps it. */
  @Test
  void aTokenPastRetentionIsForgottenSoTheIndexCannotGrowWithUptime() {
    var installationId = 42L;
    when(tokenApi.createInstallationToken(anyString(), anyString(), eq(installationId)))
        .thenReturn(token("first"), token("second"));
    var first = authClient.getAuthHeader(installationId);

    advance(GitHubAuthClient.OWNER_RETENTION.plusMinutes(1));
    authClient.getAuthHeader(installationId);

    assertEquals(Optional.empty(), authClient.refreshedAuthHeader(first));
  }

  /**
   * A hand-written token API rather than a mock: this test drives two threads through the mint at
   * once, and answer sequencing under concurrent invocation is exactly what a mock does not
   * promise. The gate holds each mint until the other has arrived, or a bounded wait elapses — so
   * the test still passes if minting is ever serialized, while today it makes the overlap
   * deterministic.
   */
  private static final class GatedTokenApi implements GitHubTokenApi {

    private final AtomicInteger minted = new AtomicInteger();
    private final CountDownLatch overlap;

    GatedTokenApi(int gatedMints) {
      this.overlap = new CountDownLatch(gatedMints);
    }

    @Override
    public InstallationTokenResponse createInstallationToken(
        String authorization, String accept, long installationId) {
      var n = minted.incrementAndGet();
      if (n > 1) {
        overlap.countDown();
        try {
          overlap.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
      return token("token-" + n);
    }
  }

  /**
   * #624's cascade with the threads it actually has. {@code ReviewDispatcher} serializes per pull
   * request, not per installation, and runs each review on its own virtual thread — so two reviews
   * sharing an installation hold the same cached token, cross its expiry together, and refresh at
   * the same moment.
   *
   * <p>With a one-deep superseded slot their two mints rolled it forward twice and evicted the
   * original token, so a third write still holding it could name no installation, got no
   * replacement, and was lost exactly as in the incident. No sequential test reaches that
   * interleaving.
   */
  @Test
  void concurrentRefreshesOfOneDeadTokenLeaveALaterHolderAbleToResolveIt() throws Exception {
    var installationId = 42L;
    var client = new GitHubAuthClient(config, new GatedTokenApi(2), now::get);
    var dead = client.getAuthHeader(installationId);
    assertEquals("Bearer token-1", dead);

    // Aged past GitHub's expiry first, because that is the only state a refresh ever runs in — and
    // it is what makes each mint's retention sweep a real sweep rather than a no-op on a token
    // minted microseconds ago.
    advance(GitHubAuthClient.GITHUB_TOKEN_LIFETIME.plusMinutes(1));

    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var both =
          Stream.of(1, 2)
              .map(
                  _ ->
                      CompletableFuture.supplyAsync(
                          () -> client.refreshedAuthHeader(dead), threads))
              .toList();
      for (var refresh : both) {
        var replacement = refresh.get(30, TimeUnit.SECONDS);
        assertTrue(replacement.isPresent(), "both concurrent refreshes must get a live token");
        assertNotEquals(dead, replacement.orElseThrow(), "and never the token GitHub just refused");
      }
    }

    // The straggler: a third write that read the same header before any of this and 401s now.
    assertTrue(
        client.refreshedAuthHeader(dead).isPresent(),
        "a write still holding the dead token must not be stranded by a concurrent refresh");
  }

  @Test
  void aHeaderThisProcessNeverIssuedNamesNoInstallationAndIsNotReplaced() {
    assertEquals(Optional.empty(), authClient.refreshedAuthHeader("Bearer someone-elses"));
    assertEquals(Optional.empty(), authClient.refreshedAuthHeader("Basic dXNlcjpwdw=="));
    assertEquals(Optional.empty(), authClient.refreshedAuthHeader(null));
    verify(tokenApi, never()).createInstallationToken(anyString(), anyString(), anyLong());
  }
}
