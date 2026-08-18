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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link GitHubApiError} — the reading of a failed GitHub response that #568 found missing:
 * without the body and the rate-limit headers a throttle 403 and a permission 403 are the same line
 * in the log, and the client has no basis on which to decide whether reposting is worth anything.
 *
 * <p>The header names, the throttle wording and the {@code 512}-character body cap are written out
 * as literals here rather than referenced from the production constants, so the tests pin the
 * contract with GitHub independently of the code under test.
 */
class GitHubApiErrorTest {

  private static final String SECONDARY_LIMIT_BODY =
      "{\"message\":\"You have exceeded a secondary rate limit. Please wait a few minutes before"
          + " you try again.\",\"documentation_url\":\"https://docs.github.com/rest\"}";

  /**
   * The body measured in #722: the generic secondary-limit sentence AND the clause naming the
   * block. {@link #SECONDARY_LIMIT_BODY} is the generic wording on its own, which is a milder
   * throttle class and must keep the linear backoff.
   */
  private static final String CONTENT_CREATION_BLOCK_BODY =
      "{\"message\":\"You have exceeded a secondary rate limit and have been temporarily blocked"
          + " from content creation. Please retry your request again later.\"}";

  private static final String PERMISSION_BODY =
      "{\"message\":\"Resource not accessible by integration\",\"status\":\"403\"}";

  /** An outbound response, as a test can build one: its entity is the object, not a stream. */
  private static Response outbound(int status, String body, String... headers) {
    var builder = Response.status(status);
    for (int i = 0; i < headers.length; i += 2) {
      builder.header(headers[i], headers[i + 1]);
    }
    return body == null ? builder.build() : builder.entity(body).build();
  }

  /** An inbound response, as the REST client hands one over: the entity is still a stream. */
  private static Response inbound(int status, String body, String... headers) {
    var response = mock(Response.class);
    when(response.getStatus()).thenReturn(status);
    when(response.getEntity())
        .thenReturn(
            new ByteArrayInputStream(String.valueOf(body).getBytes(StandardCharsets.UTF_8)));
    when(response.readEntity(String.class)).thenReturn(body);
    for (int i = 0; i < headers.length; i += 2) {
      when(response.getHeaderString(headers[i])).thenReturn(headers[i + 1]);
    }
    return response;
  }

  /** The body as it reaches the log — everything {@code diagnostics} prints after {@code body=}. */
  private static String loggedBody(Response response) {
    return GitHubApiError.from(response).diagnostics().split(" body=", 2)[1];
  }

  @Nested
  class ThrottleDetection {

    @Test
    void aRetryAfterOnA403IsGitHubThrottlingTheWrite() {
      assertTrue(
          GitHubApiError.from(outbound(403, SECONDARY_LIMIT_BODY, "Retry-After", "60"))
              .isThrottled());
    }

    @Test
    void anExhaustedRateLimitHeaderOnA403IsAThrottleEvenWithNoRetryAfter() {
      assertTrue(
          GitHubApiError.from(outbound(403, "{}", "x-ratelimit-remaining", "0")).isThrottled());
    }

    @Test
    void theSecondaryRateLimitWordingAloneIsEnough() {
      // The burst in #568 is the case with no headers at all — only the message says what happened.
      assertTrue(GitHubApiError.from(outbound(403, SECONDARY_LIMIT_BODY)).isThrottled());
    }

    @Test
    void aPermissionRefusalIsNotAThrottleAndMustNotBeRepeated() {
      var error =
          GitHubApiError.from(outbound(403, PERMISSION_BODY, "x-ratelimit-remaining", "42"));
      assertFalse(error.isThrottled(), "a permission 403 will fail identically forever");
    }

    @Test
    void a429IsAThrottleWhateverItCarries() {
      assertTrue(GitHubApiError.from(outbound(429, "{}")).isThrottled());
    }

    @Test
    void otherFailuresAreNotThrottles() {
      // 5xx and connection-level failures may have written the comment already — never repeated.
      assertFalse(GitHubApiError.from(outbound(500, "boom", "Retry-After", "5")).isThrottled());
      assertFalse(GitHubApiError.from(outbound(404, "{}")).isThrottled());
      assertFalse(GitHubApiError.from(outbound(422, SECONDARY_LIMIT_BODY)).isThrottled());
    }

    @Test
    void anUnparsableRetryAfterIsIgnoredRatherThanGuessedAt() {
      // GitHub sends whole seconds; an HTTP-date is not a number, so it cannot vouch for a
      // throttle.
      assertFalse(
          GitHubApiError.from(outbound(403, PERMISSION_BODY, "Retry-After", "Wed, 21 Oct 2026 GMT"))
              .isThrottled());
    }
  }

  /**
   * #732 and #747. The body does two unrelated jobs — it is a line an operator reads, and it is the
   * evidence on which a completed generation is either repeated or thrown away. Every narrowing the
   * first job asks for used to narrow the second: the 512-character cap (#732), and then the
   * 1024-character bound on the redaction input (#747), which closed the one path by which deeper
   * wording still reached the classifier. The consequence is not a shorter wait but no retry at all
   * — the retry returns empty on {@code !isThrottled()} and rethrows on the first attempt, which is
   * the pre-#495 behaviour this area exists to prevent.
   */
  @Nested
  class ThrottleWordingGitHubDidNotPutFirst {

    private static final Instant NOW = Instant.ofEpochSecond(1_800_000_000L);

    @Test
    void isStillReadWhenItSitsPastTheLengthCapTheLogLineUses() {
      // #732. Nothing here is credential-shaped and nothing is long enough to be bounded — the cap
      // alone hid it. GitHub puts the message first, but a body with a long documentation_url or a
      // set of echoed headers ahead of it does not.
      var body =
          "{\"documentation_url\":\""
              + "x".repeat(600)
              + "\",\"message\":\"You have exceeded a secondary rate limit and have been temporarily"
              + " blocked from content creation.\"}";

      var error = GitHubApiError.from(outbound(403, body));

      assertTrue(error.isThrottled(), "body=" + loggedBody(outbound(403, body)));
      assertEquals(Duration.ofSeconds(30), error.retryDelay(1, NOW));
    }

    @Test
    void isStillReadWhenMoreCredentialShapedMaterialPrecedesItThanTheRedactionBoundHolds() {
      // #747. v0.6.3 redacted the whole collapsed body first, so a prefix like this compressed to
      // "***" and carried the wording forward into the classified string; bounding the redaction
      // input at 1024 dropped it before the mask could. The audit's sweep puts the threshold
      // between 900 characters of prefix (still classified) and 1010 (no longer).
      var body = "Bearer " + "a".repeat(1_100) + " " + CONTENT_CREATION_BLOCK_BODY;

      var error = GitHubApiError.from(outbound(403, body));

      assertTrue(error.isThrottled(), "body=" + loggedBody(outbound(403, body)));
      assertEquals(Duration.ofSeconds(30), error.retryDelay(1, NOW));
    }

    @Test
    void isNotSomethingTheCredentialMaskCanDeleteBeforeItIsRead() {
      // The classified window is the collapsed body, taken before redaction, so the retry decision
      // can no longer be changed by a mask: a JWT-shaped run whose tail happens to be the word
      // "blocked" used to swallow it and turn this block into a permission refusal.
      var body = "{\"message\":\"prefix eyJabcdefgh.blocked from content creation\"}";

      var error = GitHubApiError.from(outbound(403, body));

      assertTrue(error.isThrottled(), "body=" + loggedBody(outbound(403, body)));
      assertEquals(Duration.ofSeconds(30), error.retryDelay(1, NOW));
    }

    /** Control: the log line keeps its own cap, whatever the classifier is allowed to see. */
    @Test
    void doesNotWidenWhatReachesTheLog() {
      var logged = loggedBody(outbound(403, "y".repeat(600) + " blocked from content creation"));

      assertEquals(513, logged.length(), logged);
      assertTrue(logged.endsWith("…"), logged);
    }

    /**
     * Control, and the honest edge of the fix: the window is wide, not unbounded. The entity is
     * read with no size limit of its own, and #731 is about not letting the configured host set the
     * cost of explaining a failed write, so classification gets a fixed prefix — several times any
     * body GitHub sends, and orders of magnitude past where the cap and the redaction bound used to
     * stop it.
     */
    @Test
    void isReadFromABoundedWindowRatherThanFromAnUnboundedBody() {
      var withinTheWindow = "z".repeat(4_000) + " blocked from content creation";
      var pastTheWindow = "z".repeat(64_000) + " blocked from content creation";

      assertTrue(GitHubApiError.from(outbound(403, withinTheWindow)).isThrottled());
      assertFalse(GitHubApiError.from(outbound(403, pastTheWindow)).isThrottled());
    }

    /** Control: a body carrying none of the wording is still a refusal, however deep it is read. */
    @Test
    void doesNotMakeAPermissionRefusalLookLikeAThrottle() {
      var body =
          "{\"documentation_url\":\""
              + "x".repeat(4_000)
              + "\",\"message\":\"Resource not"
              + " accessible by integration\"}";

      assertFalse(GitHubApiError.from(outbound(403, body)).isThrottled());
    }
  }

  @Nested
  class Severity {

    @Test
    void authThrottleAndServerFailuresAreWorthWarningAbout() {
      assertTrue(GitHubApiError.from(outbound(401, "{}")).isSevere());
      assertTrue(GitHubApiError.from(outbound(403, "{}")).isSevere());
      assertTrue(GitHubApiError.from(outbound(429, "{}")).isSevere());
      assertTrue(GitHubApiError.from(outbound(503, "{}")).isSevere());
    }

    @Test
    void anAbsentOptionalFileIsOrdinaryTraffic() {
      // The bot probes for .github/instructions and the settings file; 404 means "absent".
      assertFalse(GitHubApiError.from(outbound(404, "{}")).isSevere());
      assertFalse(GitHubApiError.from(outbound(422, "{}")).isSevere());
    }
  }

  @Nested
  class RetryDelay {

    private static final Instant NOW = Instant.ofEpochSecond(1_800_000_000L);

    @Test
    void retryAfterWins() {
      var error = GitHubApiError.from(outbound(403, "{}", "Retry-After", "47"));
      assertEquals(Duration.ofSeconds(47), error.retryDelay(1, NOW));
    }

    @Test
    void aRateLimitResetIsHonouredWhenNoRetryAfterIsSent() {
      var error =
          GitHubApiError.from(
              outbound(
                  403,
                  "{}",
                  "x-ratelimit-remaining",
                  "0",
                  "x-ratelimit-reset",
                  String.valueOf(NOW.getEpochSecond() + 12)));
      assertEquals(Duration.ofSeconds(12), error.retryDelay(1, NOW));
    }

    @Test
    void aResetAlreadyInThePastMeansTheWindowHasReopened() {
      var error =
          GitHubApiError.from(
              outbound(
                  403,
                  "{}",
                  "x-ratelimit-remaining",
                  "0",
                  "x-ratelimit-reset",
                  String.valueOf(NOW.getEpochSecond() - 90)));
      assertEquals(Duration.ZERO, error.retryDelay(1, NOW));
    }

    /**
     * #732's second half. {@code Long.parseLong} accepts values {@code Instant.ofEpochSecond}
     * rejects, and the {@link java.time.DateTimeException} it throws is not the {@code
     * WebApplicationException} the write path is built around: it escaped {@code
     * GitHubWriteRetry.call} past every catch between here and the caller, losing the write and the
     * record of its loss together. A header that cannot name an instant says nothing about when the
     * window reopens, which is what a non-numeric header already means here.
     */
    @Test
    void aRateLimitResetTooLargeToBeAnInstantIsTreatedAsUnspecified() {
      var error =
          GitHubApiError.from(
              outbound(
                  403,
                  SECONDARY_LIMIT_BODY,
                  "x-ratelimit-remaining",
                  "0",
                  "x-ratelimit-reset",
                  String.valueOf(Long.MAX_VALUE)));

      assertTrue(error.isThrottled());
      assertEquals(Duration.ofSeconds(5), error.retryDelay(1, NOW));
      assertEquals(Duration.ofSeconds(10), error.retryDelay(2, NOW));
    }

    /** The same at the other end of the range: an intermediary's negative overflow. */
    @Test
    void aRateLimitResetTooSmallToBeAnInstantIsTreatedAsUnspecified() {
      var error =
          GitHubApiError.from(
              outbound(
                  403,
                  SECONDARY_LIMIT_BODY,
                  "x-ratelimit-remaining",
                  "0",
                  "x-ratelimit-reset",
                  String.valueOf(Long.MIN_VALUE)));

      assertEquals(Duration.ofSeconds(5), error.retryDelay(1, NOW));
    }

    @Test
    void aNegativeRetryAfterIsFlooredAtZero() {
      var error = GitHubApiError.from(outbound(403, "{}", "Retry-After", "-3"));
      assertEquals(Duration.ZERO, error.retryDelay(1, NOW));
    }

    @Test
    void aSilentThrottleBacksOffLinearlyByAttempt() {
      // A throttle that is not a content-creation block keeps the linear backoff: it is the mild
      // case, and slowing down a little is all it asks for.
      var error = GitHubApiError.from(outbound(403, "{\"message\":\"rate limit exceeded\"}"));
      assertEquals(Duration.ofSeconds(5), error.retryDelay(1, NOW));
      assertEquals(Duration.ofSeconds(10), error.retryDelay(2, NOW));
    }

    @Test
    void theGenericSecondaryLimitIsNotTreatedAsTheContentCreationBlock() {
      // GitHub sends this one for any endpoint, and it is a milder class than the block the floor
      // is sized against. Flooring it would hold a PR's dispatcher slot for up to the whole budget
      // over a throttle that asks only for a short pause.
      var error = GitHubApiError.from(outbound(403, SECONDARY_LIMIT_BODY));
      assertEquals(Duration.ofSeconds(5), error.retryDelay(1, NOW));
      assertEquals(Duration.ofSeconds(10), error.retryDelay(2, NOW));
    }

    /**
     * #722. The budget is sized against a measured 72-second content-creation block, but sizing the
     * attempts only bounds one call's wait from above. These pin the floor that makes the budget a
     * floor as well, which is what the sizing was for.
     */
    @Nested
    class AContentCreationBlockGitHubGaveNoDeadlineFor {

      @Test
      void isNotLeftToTheLinearFallback() {
        // 5s, 10s then 15s spreads thirty seconds of waiting across the whole budget and gives up
        // well inside a block of the measured width.
        var error = GitHubApiError.from(outbound(403, CONTENT_CREATION_BLOCK_BODY));
        assertEquals(Duration.ofSeconds(30), error.retryDelay(1, NOW));
        assertEquals(Duration.ofSeconds(30), error.retryDelay(2, NOW));
      }

      @Test
      void isNotRepeatedInstantlyOnAStaleResetInstant() {
        // The reset belongs to the primary window, which a secondary limit leaves untouched — the
        // run behind #722 carried remaining=4771 while content creation was blocked. Taken
        // literally a past reset says "go now", spending every attempt in milliseconds while GitHub
        // is still refusing, which is worse than not repeating at all.
        var error =
            GitHubApiError.from(
                outbound(
                    403,
                    CONTENT_CREATION_BLOCK_BODY,
                    "x-ratelimit-remaining",
                    "4771",
                    "x-ratelimit-reset",
                    String.valueOf(NOW.getEpochSecond() - 90)));
        assertEquals(Duration.ofSeconds(30), error.retryDelay(1, NOW));
      }

      @Test
      void spansTheMeasuredBlockOnceTheWaitsAreClamped() {
        var error = GitHubApiError.from(outbound(403, CONTENT_CREATION_BLOCK_BODY));
        var total = Duration.ZERO;
        for (var attempt = 1; attempt < GitHubWriteRetry.MAX_ATTEMPTS; attempt++) {
          var wait = error.retryDelay(attempt, NOW);
          total =
              total.plus(
                  wait.compareTo(GitHubWriteRetry.MAX_DELAY_PER_ATTEMPT) > 0
                      ? GitHubWriteRetry.MAX_DELAY_PER_ATTEMPT
                      : wait);
        }
        assertTrue(
            total.compareTo(Duration.ofSeconds(72)) >= 0,
            "the clamped waits have to span the measured block, got " + total);
      }

      @Test
      void keepsADerivedWaitThatIsAlreadyLongerThanTheFloor() {
        // The floor lifts a wait that undershoots; it must not shorten one. A reset further out
        // than the floor is the longer of the two and stays, with the retry's own ceiling left to
        // clamp it.
        var error =
            GitHubApiError.from(
                outbound(
                    403,
                    CONTENT_CREATION_BLOCK_BODY,
                    "x-ratelimit-remaining",
                    "4771",
                    "x-ratelimit-reset",
                    String.valueOf(NOW.getEpochSecond() + 120)));
        assertEquals(Duration.ofSeconds(120), error.retryDelay(1, NOW));
      }

      @Test
      void isFlooredEvenWhenThePrimaryWindowAlsoReadsExhausted() {
        // The rate-limit headers describe the PRIMARY window, which this block leaves untouched, so
        // remaining=0 alongside a near reset says nothing about when creation reopens. An earlier
        // revision carved this case out and let it return 10s a time — three waits inside the very
        // window the budget is sized against, which is the #722 failure all over again.
        var error =
            GitHubApiError.from(
                outbound(
                    403,
                    CONTENT_CREATION_BLOCK_BODY,
                    "x-ratelimit-remaining",
                    "0",
                    "x-ratelimit-reset",
                    String.valueOf(NOW.getEpochSecond() + 10)));
        assertEquals(Duration.ofSeconds(30), error.retryDelay(1, NOW));
      }

      @Test
      void isRecognisedFromTheBlockWordingAlone() {
        // A body naming the block without "secondary rate limit" or "abuse detection" is still the
        // same failure, and must be both retried at all and floored.
        var body =
            "{\"message\":\"You have been temporarily blocked from content creation. Please try"
                + " again later.\"}";
        var error = GitHubApiError.from(outbound(403, body));
        assertTrue(error.isThrottled(), "a blocked-creation 403 is a throttle, not a refusal");
        assertEquals(Duration.ofSeconds(30), error.retryDelay(1, NOW));
      }

      @Test
      void isRecognisedFromTheGerundWordingToo() {
        // Same block, named "blocked from creating content" rather than "content creation". The
        // rule's contract is that it recognises a body naming the block, so it must not turn on
        // which word order GitHub happened to use.
        var body =
            "{\"message\":\"You have been temporarily blocked from creating content. Please retry"
                + " your request again later.\"}";
        var error = GitHubApiError.from(outbound(403, body));
        assertTrue(error.isThrottled(), "a blocked-creation 403 is a throttle, not a refusal");
        assertEquals(Duration.ofSeconds(30), error.retryDelay(1, NOW));
      }

      @Test
      void isFlooredEvenWhenGitHubNamedAShorterDeadline() {
        // #730. A Retry-After names a deadline for THIS request; it does not describe how wide the
        // block is. Taken literally, three seconds a time spends all four attempts in nine seconds
        // against a block measured at 72 — a smaller budget than the linear fallback this floor
        // replaced, which is the #722 failure with a header on it.
        var error =
            GitHubApiError.from(outbound(403, CONTENT_CREATION_BLOCK_BODY, "Retry-After", "3"));
        assertEquals(Duration.ofSeconds(30), error.retryDelay(1, NOW));
      }

      @Test
      void stillYieldsToALongerDeadlineGitHubNamed() {
        // The floor lifts a wait that undershoots; it must not shorten one. A Retry-After past the
        // floor is GitHub asking for longer, and it stays — the retry's own ceiling clamps it.
        var error =
            GitHubApiError.from(outbound(403, CONTENT_CREATION_BLOCK_BODY, "Retry-After", "60"));
        assertEquals(Duration.ofSeconds(60), error.retryDelay(1, NOW));
      }

      @Test
      void doesNotFloorARetryAfterOnAThrottleThatIsNotThisBlock() {
        // The floor is sized against this block alone. A milder throttle that names a short
        // deadline
        // keeps it, or every secondary limit would hold a PR's dispatcher slot for 30 seconds.
        var error = GitHubApiError.from(outbound(403, SECONDARY_LIMIT_BODY, "Retry-After", "3"));
        assertEquals(Duration.ofSeconds(3), error.retryDelay(1, NOW));
      }
    }
  }

  @Nested
  class Diagnostics {

    @Test
    void namesTheStatusEveryThrottlingHeaderAndTheBody() {
      var diagnostics =
          GitHubApiError.from(
                  outbound(
                      403,
                      SECONDARY_LIMIT_BODY,
                      "Retry-After",
                      "60",
                      "x-ratelimit-remaining",
                      "0",
                      "x-ratelimit-reset",
                      "1800000060",
                      "x-ratelimit-resource",
                      "core"))
              .diagnostics();

      assertEquals(
          "status=403 retry-after=60 x-ratelimit-remaining=0 x-ratelimit-reset=1800000060"
              + " x-ratelimit-resource=core body="
              + SECONDARY_LIMIT_BODY,
          diagnostics);
    }

    @Test
    void omitsHeadersGitHubDidNotSend() {
      var diagnostics = GitHubApiError.from(outbound(403, PERMISSION_BODY)).diagnostics();

      // The line that finally separates a permission 403 from a throttle 403 in the log.
      assertEquals("status=403 body=" + PERMISSION_BODY, diagnostics);
    }

    @Test
    void saysSoWhenTheBodyCouldNotBeRead() {
      assertEquals(
          "status=403 body=<unavailable>", GitHubApiError.from(outbound(403, null)).diagnostics());
    }

    @Test
    void omitsAHeaderThatArrivedEmpty() {
      // A present-but-blank header states nothing; printing "retry-after=" would only mislead.
      assertEquals(
          "status=403 body={}",
          GitHubApiError.from(outbound(403, "{}", "Retry-After", "  ")).diagnostics());
    }
  }

  @Nested
  class BodyHandling {

    @Test
    void masksAJwtTheBoundCutWithinTheFirstPayloadCharacters() {
      // The narrow sibling of the case below: when the cut leaves fewer than a segment's worth of
      // payload visible, a per-segment length floor would let the token through. A masked bearer
      // run ahead of it shortens the redacted text, so the cap no longer drops the tail and the
      // leak lands inside the logged line rather than past its end.
      var body =
          "Bearer "
              + "a".repeat(500)
              + "x".repeat(455)
              + " eyJ"
              + "h".repeat(50)
              + "."
              + "p".repeat(7)
              + "P".repeat(200)
              + "."
              + "s".repeat(50);

      var cleaned = loggedBody(outbound(403, body));

      assertFalse(cleaned.contains("h".repeat(50)), cleaned);
      assertFalse(cleaned.contains("ppppppp"), cleaned);
    }

    @Test
    void collapsesBidiOverridesThatCouldReorderTheLoggedLine() {
      // Cf format controls are not Cc: a right-to-left override reaching a terminal that honours
      // the bidi algorithm reorders what an operator reads, which is the same family of harm as
      // the record-splitting this collapse exists to stop.
      var cleaned = loggedBody(outbound(500, "before\u202Eafter\u2066isolated"));

      assertEquals("before after isolated", cleaned);
    }

    @Test
    void masksAJwtTheBoundCutMidPayload() {
      // #731 follow-up: bounding before redaction must not narrow what redaction covers. A token
      // whose second dot falls past the bound used to go through unmasked, and the cap then logged
      // its header and the payload chars that fit — the bound's own doing, on the body of a
      // credential the redaction exists to remove.
      var prefix = "x".repeat(100);
      var header = "a".repeat(50);
      var payload = "P".repeat(1200);
      var body = prefix + " eyJ" + header + "." + payload + "." + "s".repeat(50);

      var cleaned = loggedBody(outbound(403, body));

      assertFalse(cleaned.contains("PPPPPPPP"), cleaned);
      assertFalse(cleaned.contains(header), cleaned);
      assertTrue(cleaned.contains("***"), cleaned);
    }

    @Test
    void readsAnInboundResponseWithoutConsumingItForTheCaller() {
      var response = inbound(403, SECONDARY_LIMIT_BODY, "Retry-After", "60");

      assertEquals(SECONDARY_LIMIT_BODY, loggedBody(response));
      // Buffered first, so the exception the caller receives can still be read.
      verify(response).bufferEntity();
    }

    @Test
    void anUnreadableResponseDegradesToAnEmptyBodyRatherThanBreakingTheFailurePath() {
      var closed = mock(Response.class);
      when(closed.getStatus()).thenReturn(403);
      when(closed.getEntity()).thenThrow(new IllegalStateException("response is closed"));

      assertEquals("<unavailable>", loggedBody(closed));
    }

    @Test
    void anEmptyResponseBodyIsReportedAsUnavailableRatherThanAsBlank() {
      assertEquals("<unavailable>", loggedBody(inbound(500, null)));
    }

    @Test
    void collapsesWhitespaceSoOneFailureStaysOnOneLine() {
      assertEquals("line one line two", loggedBody(outbound(500, "  line one\n\tline two  ")));
    }

    @Test
    void masksAnythingShapedLikeACredential() {
      var body =
          loggedBody(
              outbound(
                  401,
                  "{\"token\":\"ghs_abcdefghij0123456789\","
                      + "\"pat\":\"github_pat_abcdefghij0123456789\","
                      + "\"header\":\"Bearer abcdefghij0123456789\","
                      + "\"jwt\":\"eyJhbGciOiJI.eyJpc3MiOiJ4.c2lnbmF0dXJl\"}"));
      assertFalse(body.contains("ghs_abcdefghij0123456789"), body);
      assertFalse(body.contains("github_pat_abcdefghij0123456789"), body);
      assertFalse(body.contains("abcdefghij0123456789"), body);
      assertFalse(body.contains("eyJhbGciOiJI"), body);
      assertEquals(4, body.split("\\*\\*\\*", -1).length - 1, body);
    }

    /**
     * The overlap the credential javadoc pins: a token prefix whose word-character run swallows a
     * following "Bearer" is masked as ONE leftmost match, so the bearer wording never survives as
     * an unmasked shape of its own.
     */
    @Test
    void masksAnOverlappingTokenAndBearerAsTheLeftmostMatch() {
      var body = loggedBody(outbound(401, "ghp_abcdefgBearer abcdefghij0123456789"));

      assertEquals("*** abcdefghij0123456789", body);
    }

    /**
     * A bearer BEFORE a token: the leftmost-match walk must pick the value shape although the
     * prefix shape also matched further right, and the trailing token's mask ends the text, so the
     * scan exits at the end rather than on a failed search.
     */
    @Test
    void masksABearerFollowedByATokenLeftmostFirst() {
      var body = loggedBody(outbound(401, "Bearer abcdefghij0123456789 ghp_abcdefghij0123456789"));

      assertEquals("*** ***", body);
    }

    /** The other direction: a bearer value that is itself a token keeps nothing past the mask. */
    @Test
    void masksABearerCarryingATokenValueAsOneLeftmostMatch() {
      var body = loggedBody(outbound(401, "Bearer ghp_abcdefghij.tail and more"));

      assertEquals("*** and more", body);
    }

    /** A body that is nothing but a token prefix shape, masked to its very last character. */
    @Test
    void masksATokenStandingAlone() {
      assertEquals("only ***", loggedBody(outbound(401, "only ghp_0123456789abcd")));
    }

    @Test
    void capsAnOverLongBodySoOneFailureCannotFloodTheLog() {
      var body = loggedBody(outbound(500, "x".repeat(4_000)));

      assertEquals(513, body.length());
      assertTrue(body.endsWith("…"));
    }

    @Test
    void neverCutsAnOverLongBodyThroughASurrogatePair() {
      // 511 plain characters then an astral code point: the cut lands inside the pair.
      var body = loggedBody(outbound(500, "x".repeat(511) + "𝍢".repeat(20)));

      assertEquals(511, body.length() - 1);
      assertTrue(body.endsWith("…"));
      assertEquals(-1, body.indexOf('\uD834'), "no dangling high surrogate");
    }

    /**
     * #731. Redaction used to run over the whole body and the cap only afterwards, so the cost of
     * explaining one failed write was set by whatever the configured host chose to send: the JWT
     * shape backtracks from one position in three, which is quadratic, and the measured curve ran
     * 20 000 chars → 331 ms, 40 000 → 1 213 ms, 80 000 → 4 843 ms, 160 000 → 19 404 ms. The bound
     * below is enormously slack against the ~1 ms this costs once the body is cut first; it is
     * sized to fail only on the quadratic, not on a slow machine.
     */
    @Test
    void doesNotScanAWholeOversizedBodyLookingForCredentials() {
      var body = "eyJ".repeat(66_666); // ~200 KB, as a proxy's error page could be

      var start = System.nanoTime();
      var logged = loggedBody(outbound(403, body));
      var millis = (System.nanoTime() - start) / 1_000_000;

      assertTrue(millis < 2_000, "cleaning a 200 KB body took " + millis + "ms");
      assertEquals(513, logged.length(), logged);
    }

    /**
     * #731. {@code \s} is the ASCII six in java.util.regex, so the collapse caught CR and LF and
     * let every other record separator through — enough for an attacker-influenced body to forge
     * what reads as a second log line, or to carry an ANSI sequence into an operator's terminal.
     */
    @Test
    void collapsesTheLineTerminatorsAndControlsThatAreNotAsciiWhitespace() {
      var body =
          "a\u0085WARN forged-by-NEL \u2028WARN forged-by-LS \u2029WARN forged-by-PS"
              + " \u0000NUL \u001b[2J ansi";

      var logged = loggedBody(outbound(403, body));

      assertEquals("a WARN forged-by-NEL WARN forged-by-LS WARN forged-by-PS NUL [2J ansi", logged);
    }

    /** The same collapse must not leave a terminator at either end behind as a stray space. */
    @Test
    void stripsALineTerminatorAtEitherEndRatherThanLeavingASpace() {
      assertEquals("boom", loggedBody(outbound(500, "\u2028 boom \u0085")));
    }

    /**
     * A body cut before redaction still says it was cut, even when the mask leaves the result well
     * under the cap — otherwise a heavily redacted 200 KB page would read as something GitHub sent
     * whole.
     */
    @Test
    void marksABodyCutBeforeRedactionAsTruncatedEvenWhenTheMaskFitsUnderTheCap() {
      var body = loggedBody(outbound(401, "Bearer " + "a".repeat(4_000)));

      assertEquals("***…", body);
    }

    /**
     * #746, #757. The bound before redaction is the only cut that can sever a token — the cap runs
     * after the mask — and a shape with a length floor stops matching once it has been severed
     * below it. A run of credential-shaped material ahead of the token compresses to {@code ***},
     * so what the bound left of the secret survives the cap and reaches the warn line whole.
     *
     * <p>Each shape is pinned at nine surviving value characters and at one, because a floor is
     * only closed at its own boundary: #746's {@code {4,}} masks nine and still strands one, two or
     * three.
     */
    @Nested
    class ACredentialTheBoundCutJustShortOfItsLengthFloor {

      /** Enough credential-shaped material to redact to {@code ***} and clear the cap for us. */
      private static final String COMPRESSIBLE = "Bearer " + "a".repeat(900);

      /**
       * A body whose {@code sigil} lands so the 1024-char bound leaves {@code visible} value chars.
       */
      private static String cutLeaving(int visible, String sigil, String value) {
        var padding = 1_024 - sigil.length() - visible - COMPRESSIBLE.length();
        return COMPRESSIBLE + ",".repeat(padding) + sigil + value;
      }

      @Test
      void isStillMaskedForATokenPrefix() {
        var logged = loggedBody(outbound(403, cutLeaving(9, "ghp_", "A1b2C3d4E5f6G7h8I9j0K1")));

        assertFalse(logged.contains("ghp_A1b2C3d4E"), logged);
      }

      @Test
      void isStillMaskedForAFineGrainedPersonalAccessToken() {
        var logged =
            loggedBody(outbound(403, cutLeaving(9, "github_pat_", "A1b2C3d4E5f6G7h8I9j0K1")));

        assertFalse(logged.contains("github_pat_A1b2C3d4E"), logged);
      }

      @Test
      void isStillMaskedForABearerValue() {
        var logged = loggedBody(outbound(403, cutLeaving(9, "Bearer ", "S3cr3tV4lu3W1thM0re")));

        assertFalse(logged.contains("Bearer S3cr3tV4l"), logged);
      }

      @Test
      void isStillMaskedForATokenPrefixCutToASingleCharacter() {
        var logged = loggedBody(outbound(403, cutLeaving(1, "ghp_", "A1b2C3d4E5f6G7h8I9j0K1")));

        assertFalse(logged.contains("ghp_A"), logged);
      }

      @Test
      void isStillMaskedForAFineGrainedPersonalAccessTokenCutToASingleCharacter() {
        var logged =
            loggedBody(outbound(403, cutLeaving(1, "github_pat_", "A1b2C3d4E5f6G7h8I9j0K1")));

        assertFalse(logged.contains("github_pat_A"), logged);
      }

      @Test
      void isStillMaskedForABearerValueCutToASingleCharacter() {
        var logged = loggedBody(outbound(403, cutLeaving(1, "Bearer ", "S3cr3tV4lu3W1thM0re")));

        assertFalse(logged.contains("Bearer S"), logged);
      }

      /**
       * The floor cannot go below one, so this is the residual the javadoc names rather than a
       * proof of the fix: a cut leaving no value characters at all strands the sigil, which carries
       * no secret. Green before and after — it pins the boundary, it does not demonstrate it moved.
       */
      @Test
      void leavesTheBareSigilBehindWhenTheCutLandsBeforeTheFirstValueCharacter() {
        var logged = loggedBody(outbound(403, cutLeaving(0, "ghp_", "A1b2C3d4E5f6G7h8I9j0K1")));

        assertTrue(logged.endsWith("ghp_…"), logged);
      }
    }

    /**
     * #746. The JWT header is base64url of {@code &#123;"} and is therefore always literally {@code
     * eyJ}. Reading it case-insensitively made an Icelandic volcano a credential and blanked the
     * hostname the operator needed — the exact outcome the shape's own javadoc says it was narrowed
     * to avoid.
     */
    @Test
    void doesNotMaskOrdinaryTextThatMerelyBeginsLikeAJwtHeaderInSomeOtherCase() {
      var body = "{\"message\":\"cannot resolve host eyjafjallajokull.internal.example.com\"}";

      assertEquals(body, loggedBody(outbound(502, body)));
    }

    /**
     * #746. Nor is the header a credential when it turns up in the middle of a longer run: an
     * unanchored {@code eyJ} let one request id blank four hundred characters of the body around
     * it. A JWT starts at a token boundary, so the shape is pinned to one.
     */
    @Test
    void doesNotMaskAJwtHeaderFoundInsideALongerRunOfWordCharacters() {
      var body = "{\"message\":\"request id 7f3aeyJQm9keVRleHRIZXJl.log not found\"}";

      assertEquals(body, loggedBody(outbound(404, body)));
    }

    /**
     * Control, not proof — green before the fix as well. It pins the half of the {@code (?i)} that
     * has to survive being scoped to one alternative: GitHub sends {@code Bearer}, but the header
     * name is case-insensitive by RFC 7235 and an echoed one may arrive in any case.
     */
    @Test
    void masksABearerHeaderWhateverCaseItArrivedIn() {
      assertEquals(
          "*** and more", loggedBody(outbound(401, "BEARER abcdefghij0123456789 and more")));
      assertEquals(
          "*** and more", loggedBody(outbound(401, "bearer abcdefghij0123456789 and more")));
    }
  }

  @Nested
  class FromAnException {

    @Test
    void readsTheResponseTheRestClientAttached() {
      var failure =
          new WebApplicationException(outbound(403, SECONDARY_LIMIT_BODY, "Retry-After", "9"));

      var error = GitHubApiError.of(failure).orElseThrow();

      assertTrue(error.isThrottled());
      assertEquals(Duration.ofSeconds(9), error.retryDelay(1, Instant.EPOCH));
    }

    @Test
    void isEmptyWhenTheFailureCarriedNoResponseAtAll() {
      var noResponse =
          new WebApplicationException("connection reset") {
            @Override
            public Response getResponse() {
              return null;
            }
          };

      assertTrue(GitHubApiError.of(noResponse).isEmpty());
    }
  }
}
