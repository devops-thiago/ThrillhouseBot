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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link GitHubLostWrites} — what the pull request is told when the backoff budget runs out
 * and a finished reply is thrown away, which #578 says is today's silent case.
 *
 * <p>The carrier is driven directly rather than through a REST client: what matters here is which
 * post gets the notice, when it stops being repeated, and when it stops being said at all.
 */
class GitHubLostWritesTest {

  private static final GitHubLostWrites.Target PR = new GitHubLostWrites.Target("owner", "repo", 7);
  private static final GitHubLostWrites.Target OTHER_PR =
      new GitHubLostWrites.Target("owner", "repo", 8);

  private static final String SECONDARY_LIMIT_BODY =
      "{\"message\":\"You have exceeded a secondary rate limit.\"}";

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.ofEpochSecond(1_800_000_000L));

  private final GitHubLostWrites lost = new GitHubLostWrites(now::get, 2, Duration.ofHours(6));

  private static WebApplicationException throttled() {
    return new WebApplicationException(
        Response.status(403).header("Retry-After", "1").entity(SECONDARY_LIMIT_BODY).build());
  }

  private static WebApplicationException refusal() {
    return new WebApplicationException(
        Response.status(403)
            .entity("{\"message\":\"Resource not accessible by integration\"}")
            .build());
  }

  /** Posts on {@code target}, failing with {@code failure} when one is given. */
  private String post(
      GitHubLostWrites.Target target, List<String> carried, WebApplicationException failure) {
    return lost.carrying(
        target,
        notice -> {
          carried.add(notice);
          if (failure != null) {
            throw failure;
          }
          return "posted";
        });
  }

  @Test
  void aPostOnAPullRequestThatLostNothingCarriesNoNotice() {
    var carried = new ArrayList<String>();

    assertEquals("posted", post(PR, carried, null));

    assertEquals(List.of(""), carried);
  }

  @Test
  void aDroppedReplyIsAnnouncedOnTheNextCommentThatLandsOnThatPullRequest() {
    var carried = new ArrayList<String>();

    assertThrows(WebApplicationException.class, () -> post(PR, carried, throttled()));
    post(PR, carried, null);

    // The notice cannot be posted on its own — it would be the very call being throttled — so the
    // next successful post carries it.
    assertEquals("", carried.get(0));
    assertTrue(carried.get(1).startsWith("> [!WARNING]"), carried.get(1));
    assertTrue(carried.get(1).contains("An earlier reply"), carried.get(1));
    assertTrue(carried.get(1).contains("run the command again"), carried.get(1));
  }

  @Test
  void theNoticeIsSaidOnceAndNotOnEveryCommentAfterwards() {
    var carried = new ArrayList<String>();

    assertThrows(WebApplicationException.class, () -> post(PR, carried, throttled()));
    post(PR, carried, null);
    post(PR, carried, null);

    assertEquals("", carried.get(2));
  }

  @Test
  void aNoticeThatCouldNotBeDeliveredIsKeptForTheNextTry() {
    var carried = new ArrayList<String>();

    assertThrows(WebApplicationException.class, () -> post(PR, carried, throttled()));
    // The post that should have carried the notice is itself thrown away.
    assertThrows(WebApplicationException.class, () -> post(PR, carried, throttled()));
    post(PR, carried, null);

    // Two losses now, and the notice was never cleared by the post that failed to deliver it.
    assertTrue(carried.get(2).contains("2 earlier replies"), carried.get(2));
  }

  @Test
  void aLossThatArrivesWhileTheNoticeIsInFlightIsStillAnnouncedNext() {
    var carried = new ArrayList<String>();
    assertThrows(WebApplicationException.class, () -> post(PR, carried, throttled()));

    lost.carrying(
        PR,
        notice -> {
          carried.add(notice);
          // A concurrent post on the same PR is dropped while this one is on the wire.
          assertThrows(
              WebApplicationException.class, () -> lost.recording(PR, () -> throwIt(throttled())));
          return "posted";
        });
    post(PR, carried, null);

    assertTrue(carried.get(1).contains("An earlier reply"), carried.get(1));
    assertTrue(carried.get(2).contains("An earlier reply"), carried.get(2));
  }

  @Test
  void aLossThatLandsWhileTwoPostsCarryTheSameNoticeIsStillAnnouncedAfterwards() {
    var carried = new ArrayList<String>();
    assertThrows(WebApplicationException.class, () -> post(PR, carried, throttled()));

    // Two posts on the same pull request overlap — the inner one runs entirely between the outer
    // one's read of the pending notice and its completion, the interleaving a PR under a burst of
    // commands produces — and a third post is thrown away while both are on the wire.
    lost.carrying(
        PR,
        outerNotice -> {
          carried.add(outerNotice);
          lost.carrying(
              PR,
              innerNotice -> {
                carried.add(innerNotice);
                assertThrows(
                    WebApplicationException.class,
                    () -> lost.recording(PR, () -> throwIt(throttled())));
                return "posted";
              });
          return "posted";
        });
    post(PR, carried, null);

    // Both overlapping posts carried the first loss and neither carried the one that landed
    // between them, so it has to still be waiting. Retiring it here — which subtracting each
    // carrier's snapshot from one shared count does — is a user never hearing that their content
    // was dropped, the exact silence #578 exists to remove.
    assertTrue(
        carried.get(1).contains("An earlier reply"), () -> "outer post carried: " + carried.get(1));
    assertTrue(
        carried.get(2).contains("An earlier reply"), () -> "inner post carried: " + carried.get(2));
    assertTrue(
        carried.get(3).contains("An earlier reply"),
        () ->
            "the loss recorded between the two overlapping posts was retired without ever being"
                + " announced; the next comment carried: \""
                + carried.get(3)
                + "\"");
  }

  @Test
  void aNoticeIsScopedToThePullRequestThatLostThePost() {
    var carried = new ArrayList<String>();

    assertThrows(WebApplicationException.class, () -> post(PR, carried, throttled()));
    post(OTHER_PR, carried, null);

    assertEquals("", carried.get(1));
  }

  @Test
  void aRefusalThatWillNeverWorkIsNotAnnouncedOnThePullRequest() {
    var carried = new ArrayList<String>();

    assertThrows(WebApplicationException.class, () -> post(PR, carried, refusal()));
    post(PR, carried, null);

    // A missing permission is a defect to fix, not a command to re-run.
    assertEquals("", carried.get(1));
  }

  @Test
  void aNoticeGoesStaleRatherThanBeingGluedOntoAMuchLaterComment() {
    var carried = new ArrayList<String>();

    assertThrows(WebApplicationException.class, () -> post(PR, carried, throttled()));
    now.set(now.get().plus(Duration.ofHours(7)));
    post(PR, carried, null);

    assertEquals("", carried.get(1));
  }

  @Test
  void aRegistryFullOfAlreadyDeliveredNoticesStillHasRoomForANewLoss() {
    var carried = new ArrayList<String>();

    // Both of the two slots this fixture allows are used and then settled: each pull request lost
    // a post and each was told about it on its next comment.
    assertThrows(WebApplicationException.class, () -> post(PR, carried, throttled()));
    post(PR, carried, null);
    assertThrows(WebApplicationException.class, () -> post(OTHER_PR, carried, throttled()));
    post(OTHER_PR, carried, null);

    var third = new GitHubLostWrites.Target("owner", "repo", 9);
    assertThrows(WebApplicationException.class, () -> post(third, carried, throttled()));
    post(third, carried, null);

    // Nothing is owed to either of the first two any more, so holding their slots until the TTL
    // sweeps them would spend the cap on settled bookkeeping and silence a pull request that has
    // genuinely lost a reply — the same silence, reached from the other end.
    assertTrue(
        carried.getLast().contains("An earlier reply"),
        () ->
            "the registry was full of already-delivered notices, so the new loss was only logged;"
                + " the next comment carried: \""
                + carried.getLast()
                + "\"");
  }

  @Test
  void aCarrierLeftOverFromASettledEntryCannotRetireALaterLoss() {
    var carried = new ArrayList<String>();
    assertThrows(WebApplicationException.class, () -> post(PR, carried, throttled()));

    lost.carrying(
        PR,
        staleNotice -> {
          carried.add(staleNotice);
          // A second post delivers the same notice and settles the entry, then a later loss starts
          // a fresh one. Both runs count one loss, so only the entry's identity separates them.
          post(PR, carried, null);
          assertThrows(
              WebApplicationException.class, () -> lost.recording(PR, () -> throwIt(throttled())));
          return "posted";
        });
    post(PR, carried, null);

    // The stale carrier never carried the later loss, so completing must not retire it.
    assertTrue(carried.get(3).contains("An earlier reply"), carried.get(3));
  }

  @Test
  void aFloodOfLosingPullRequestsCannotGrowTheRegistryWithoutEnd() {
    var carried = new ArrayList<String>();
    assertThrows(WebApplicationException.class, () -> post(PR, carried, throttled()));
    assertThrows(WebApplicationException.class, () -> post(OTHER_PR, carried, throttled()));

    var third = new GitHubLostWrites.Target("owner", "repo", 9);
    assertThrows(WebApplicationException.class, () -> post(third, carried, throttled()));
    // A PR already holding a notice still counts its second loss even at capacity.
    assertThrows(WebApplicationException.class, () -> post(PR, carried, throttled()));

    post(third, carried, null);
    post(PR, carried, null);
    assertEquals("", carried.get(4), "the PR past the cap is only recorded in the log");
    assertTrue(carried.get(5).contains("2 earlier replies"), carried.get(5));
  }

  @Test
  void anExpiredNoticeMakesRoomForANewOne() {
    var carried = new ArrayList<String>();
    assertThrows(WebApplicationException.class, () -> post(PR, carried, throttled()));
    assertThrows(WebApplicationException.class, () -> post(OTHER_PR, carried, throttled()));
    now.set(now.get().plus(Duration.ofHours(7)));

    var third = new GitHubLostWrites.Target("owner", "repo", 9);
    assertThrows(WebApplicationException.class, () -> post(third, carried, throttled()));
    post(third, carried, null);

    assertTrue(carried.get(3).contains("An earlier reply"), carried.get(3));
  }

  @Test
  void aFailureThatIsNotAThrottleLeavesTheCallersExceptionAlone() {
    var boom = new IllegalStateException("serialization failed");

    var thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                lost.recording(
                    PR,
                    () -> {
                      throw boom;
                    }));

    assertSame(boom, thrown);
  }

  @Test
  void theNoticeReadsAsOneOrManyAndIsEmptyWhenNothingWasLost() {
    assertEquals("", GitHubLostWrites.notice(0));
    assertTrue(GitHubLostWrites.notice(1).contains("An earlier reply"));
    assertTrue(GitHubLostWrites.notice(3).contains("3 earlier replies"));
  }

  @Test
  void theNoticeIsPutAboveTheBodyAndNeverInventsOne() {
    assertEquals("the reply", GitHubLostWrites.prepend("", "the reply"));
    assertNull(GitHubLostWrites.prepend("", null));
    assertEquals("notice\n\nthe reply", GitHubLostWrites.prepend("notice", "the reply"));
    assertEquals("notice", GitHubLostWrites.prepend("notice", null));
    assertEquals("notice", GitHubLostWrites.prepend("notice", "   "));
  }

  @Test
  void aTargetNamesThePullRequestItLostThePostOn() {
    assertEquals("owner/repo #7", PR.toString());
  }

  private static String throwIt(WebApplicationException failure) {
    throw failure;
  }
}
