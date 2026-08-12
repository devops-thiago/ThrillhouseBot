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

    @Test
    void aNegativeRetryAfterIsFlooredAtZero() {
      var error = GitHubApiError.from(outbound(403, "{}", "Retry-After", "-3"));
      assertEquals(Duration.ZERO, error.retryDelay(1, NOW));
    }

    @Test
    void aSilentThrottleBacksOffLinearlyByAttempt() {
      var error = GitHubApiError.from(outbound(403, SECONDARY_LIMIT_BODY));
      assertEquals(Duration.ofSeconds(5), error.retryDelay(1, NOW));
      assertEquals(Duration.ofSeconds(10), error.retryDelay(2, NOW));
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
