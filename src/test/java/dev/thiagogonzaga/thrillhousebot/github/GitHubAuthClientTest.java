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
import static org.mockito.Mockito.*;

import dev.thiagogonzaga.thrillhousebot.config.ThrillhouseConfig;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
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

  @Mock private GitHubAppApi appApi;

  private GitHubAuthClient authClient;

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-08-12T13:32:03Z"));

  private void advance(Duration by) {
    now.updateAndGet(instant -> instant.plus(by));
  }

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    authClient = new GitHubAuthClient(appApi, now::get);
  }

  @Test
  void shouldCacheInstallationToken() {
    var installationId = 42L;
    when(appApi.createInstallationToken(installationId)).thenReturn("token-abc");

    var firstToken = authClient.getInstallationToken(installationId);
    assertEquals("token-abc", firstToken);

    // Second call should use cache — API should NOT be called again
    var secondToken = authClient.getInstallationToken(installationId);
    assertEquals("token-abc", secondToken);

    // Verify API was only called once
    verify(appApi, times(1)).createInstallationToken(installationId);
  }

  @Test
  void aGoneInstallationsTokenIsDroppedAndOthersAreKept() {
    when(appApi.createInstallationToken(anyLong())).thenReturn("t");
    authClient.getInstallationToken(42L);
    authClient.getInstallationToken(43L);

    authClient.onInstallationChange(
        new InstallationChange(InstallationChange.Kind.GONE, 42L, java.util.List.of()));
    authClient.onInstallationChange(
        new InstallationChange(InstallationChange.Kind.PERMISSIONS, 43L, java.util.List.of()));
    authClient.getInstallationToken(42L);
    authClient.getInstallationToken(43L);

    verify(appApi, times(2)).createInstallationToken(42L);
    verify(appApi, times(1)).createInstallationToken(43L);
  }

  @Test
  void shouldGetSeparateTokensForDifferentInstallations() {
    var installId1 = 10L;
    var installId2 = 20L;

    when(appApi.createInstallationToken(installId1)).thenReturn("token-one");
    when(appApi.createInstallationToken(installId2)).thenReturn("token-two");

    var t1 = authClient.getInstallationToken(installId1);
    var t2 = authClient.getInstallationToken(installId2);

    assertEquals("token-one", t1);
    assertEquals("token-two", t2);

    // Each installation should have its own cache entry
    verify(appApi, times(1)).createInstallationToken(installId1);
    verify(appApi, times(1)).createInstallationToken(installId2);
  }

  @Test
  void shouldReturnBearerAuthHeader() {
    var installationId = 7L;
    when(appApi.createInstallationToken(installationId)).thenReturn("bearer-token");

    var header = authClient.getAuthHeader(installationId);
    assertEquals("Bearer bearer-token", header);
  }

  @Test
  void aRefusedMintIsPassedOnAndNothingIsCached() {
    var refused = new WebApplicationException(Response.status(404).build());
    when(appApi.createInstallationToken(7L)).thenThrow(refused).thenReturn("later");

    assertSame(
        refused, assertThrows(WebApplicationException.class, () -> authClient.getAuthHeader(7L)));
    assertEquals("Bearer later", authClient.getAuthHeader(7L));
  }

  @Test
  void theInstallationsAreListedWithTheAppsOwnCredential() {
    when(appApi.listInstallationIds()).thenReturn(List.of(1L, 2L));

    assertEquals(List.of(1L, 2L), authClient.listInstallationIds());
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
    when(appApi.createInstallationToken(installationId))
        .thenReturn("expired-token", "minted-token");
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
    when(appApi.createInstallationToken(installationId))
        .thenReturn("expired-token", "minted-token");
    var dead = authClient.getAuthHeader(installationId);

    var fresh = authClient.refreshedAuthHeader(dead);

    assertEquals(Optional.of("Bearer minted-token"), fresh);
    assertEquals(
        "Bearer minted-token",
        authClient.getAuthHeader(installationId),
        "the replacement must be cached, not minted again for every later call");
    verify(appApi, times(2)).createInstallationToken(installationId);
  }

  /**
   * A dead token does not fail one write, it fails every write still holding it — the #624 log has
   * five 401s in five seconds. The second one through must be handed the replacement the first one
   * minted rather than evicting it and minting a third.
   */
  @Test
  void aSecondWriteHoldingTheSameDeadTokenGetsTheReplacementAlreadyMinted() {
    var installationId = 42L;
    when(appApi.createInstallationToken(installationId))
        .thenReturn("expired-token", "minted-token");
    var dead = authClient.getAuthHeader(installationId);
    authClient.refreshedAuthHeader(dead);

    var second = authClient.refreshedAuthHeader(dead);

    assertEquals(Optional.of("Bearer minted-token"), second);
    verify(appApi, times(2)).createInstallationToken(installationId);
  }

  /**
   * Every token stays resolvable while GitHub would still accept it, however many refreshes have
   * happened since — retention is by age, so no number of newer tokens can strand a holder of an
   * older one.
   */
  @Test
  void everyTokenStillWithinGitHubsExpiryKeepsNamingItsInstallation() {
    var installationId = 42L;
    when(appApi.createInstallationToken(installationId)).thenReturn("first", "second", "third");
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
   * A refresh must not retire the entry the rest of its own cascade is still resolving against.
   *
   * <p>This is the boundary {@link GitHubAuthClient#OWNER_RETENTION} has to clear, and it fails in
   * the direction that looks safe. An entry is consulted only because a write is holding a token
   * GitHub has just refused, and the ordinary reason for that refusal is that the token has passed
   * {@link GitHubAuthClient#GITHUB_TOKEN_LIFETIME} — so every lookup that matters happens when the
   * entry is <em>already older than the token's whole life</em>. Size the window at that lifetime,
   * which reads as generous, and the refresh mints at {@code issued + 60min + ε}, sweeps everything
   * older than {@code now - 60min}, and deletes the entry for the very token whose 401 provoked it.
   * The first write of the cascade is rescued and every later holder of that token is stranded.
   *
   * <p>Hence a window of {@link GitHubAuthClient#TOKEN_TTL} plus {@link
   * GitHubAuthClient#LONGEST_WRITE_IN_FLIGHT} rather than the token lifetime, and hence {@link
   * #theOwnerIndexMustOutliveEveryTokenItNames} pinning the relationship. Anything that shortens
   * the window back towards the lifetime brings this failure back, and brings it back quietly: the
   * refresh that triggers the sweep still succeeds, so only the writes behind it are lost.
   */
  @Test
  void aRefreshDoesNotSweepAwayTheEntryTheRestOfItsCascadeStillNeeds() {
    var installationId = 42L;
    when(appApi.createInstallationToken(installationId)).thenReturn("first", "second");
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
    when(appApi.createInstallationToken(installationId)).thenReturn("wall-clock");
    var client = new GitHubAuthClient(appApi);

    assertEquals("Bearer wall-clock", client.getAuthHeader(installationId));
    assertEquals(
        "Bearer wall-clock",
        client.getAuthHeader(installationId),
        "and caches it, so the TTL is measured against a clock that actually advances");
    verify(appApi, times(1)).createInstallationToken(installationId);
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
    when(appApi.createInstallationToken(installationId)).thenReturn("first", "second");
    var first = authClient.getAuthHeader(installationId);

    advance(GitHubAuthClient.OWNER_RETENTION.plusMinutes(1));
    authClient.getAuthHeader(installationId);

    assertEquals(Optional.empty(), authClient.refreshedAuthHeader(first));
  }

  /**
   * A hand-written app API rather than a mock: this test drives two threads through the mint at
   * once, and answer sequencing under concurrent invocation is exactly what a mock does not
   * promise. The gate holds each mint until the other has arrived, or a bounded wait elapses — so
   * the test still passes if minting is ever serialized, while today it makes the overlap
   * deterministic.
   */
  private static final class GatedAppApi extends GitHubAppApi {

    private final AtomicInteger minted = new AtomicInteger();
    private final CountDownLatch overlap;

    GatedAppApi(int gatedMints) {
      super(null, null, mock(ThrillhouseConfig.class));
      this.overlap = new CountDownLatch(gatedMints);
    }

    @Override
    String createInstallationToken(long installationId) {
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
      return "token-" + n;
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
    var client = new GitHubAuthClient(new GatedAppApi(2), now::get);
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
    verify(appApi, never()).createInstallationToken(anyLong());
  }
}
