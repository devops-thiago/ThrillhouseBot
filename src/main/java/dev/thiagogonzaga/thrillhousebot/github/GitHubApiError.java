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

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What a failed GitHub REST call actually said — the pieces of the response an operator needs to
 * tell one 4xx from another, and the pieces {@link GitHubWriteRetry} needs to decide whether the
 * call is worth repeating.
 *
 * <p>Motivated by #568: a burst of on-demand commands drew 29 × HTTP 403 on {@code
 * GitHubCommentClient#createComment} and the logs carried nothing but the status, so a secondary
 * rate limit and a missing App permission were indistinguishable from the instance's own output.
 * Both defects trace back to the same missing information — the response body and the rate-limit
 * headers — so both are read here, once, and shared by the logger and the retry.
 *
 * <p>The body is redacted and truncated before it is exposed: GitHub's error bodies are short JSON
 * messages, but a body is attacker-influenced text that ends up in an operator's log, so anything
 * shaped like a token is masked and the rest is capped at {@value #MAX_BODY_CHARS} characters.
 */
public final class GitHubApiError {

  /** How much of a response body is kept for the log. GitHub's error bodies are far shorter. */
  static final int MAX_BODY_CHARS = 512;

  /** Replacement for anything in a response body shaped like a credential. */
  static final String REDACTED = "***";

  /**
   * GitHub App/PAT/OAuth token prefixes and bearer/JWT shapes. GitHub does not echo credentials in
   * an error body, but a body is untrusted text on its way to a log file, so a token-shaped run of
   * characters is masked rather than trusted to be harmless.
   *
   * <p>Deliberately ONE alternation rather than one pattern per shape: the shapes overlap, and a
   * single pass masks each overlap as the leftmost match, whereas masking in passes decides the
   * overlap by pass order and leaves material the one-pass form masks. Whichever order is chosen,
   * {@code "…ghp_<7 chars>Bearer <20 chars>"} keeps its token prefix unmasked if the bearer pass
   * runs first, and {@code "Bearer ghp_<10 chars>.<tail>"} keeps the tail of the bearer value if
   * the token pass does. So this stays one pattern even though it reads as four.
   */
  private static final Pattern CREDENTIAL_SHAPED =
      Pattern.compile(
          "(?i)(gh[pousr]_\\w{10,})"
              + "|(github_pat_\\w{10,})"
              + "|(bearer\\s+[\\w.~+/=-]{10,})"
              + "|(eyJ[\\w-]{8,}\\.[\\w-]{8,}\\.[\\w-]{8,})");

  /**
   * The wording GitHub uses when it is throttling rather than refusing. A secondary rate limit and
   * a primary rate limit both arrive as 403; only the message distinguishes them from a permission
   * refusal when the headers are absent.
   */
  private static final Pattern THROTTLE_WORDING =
      Pattern.compile("(?i)secondary rate limit|abuse detection|rate limit exceeded");

  /** Collapses the whitespace of a body so one failure stays on one log line. */
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /** Backoff used when GitHub throttles without saying for how long. */
  static final Duration FALLBACK_DELAY = Duration.ofSeconds(5);

  private final int status;
  private final String retryAfter;
  private final String rateLimitRemaining;
  private final String rateLimitReset;
  private final String rateLimitResource;
  private final String body;

  private GitHubApiError(
      int status,
      String retryAfter,
      String rateLimitRemaining,
      String rateLimitReset,
      String rateLimitResource,
      String body) {
    this.status = status;
    this.retryAfter = retryAfter;
    this.rateLimitRemaining = rateLimitRemaining;
    this.rateLimitReset = rateLimitReset;
    this.rateLimitResource = rateLimitResource;
    this.body = body;
  }

  /** Reads the status, the throttling headers and the (redacted) body off a failed response. */
  public static GitHubApiError from(Response response) {
    return new GitHubApiError(
        response.getStatus(),
        response.getHeaderString("Retry-After"),
        response.getHeaderString("x-ratelimit-remaining"),
        response.getHeaderString("x-ratelimit-reset"),
        response.getHeaderString("x-ratelimit-resource"),
        readBody(response));
  }

  /**
   * The same, for the exception the REST client raises. Empty when the failure carried no response
   * at all (a connection-level failure), which is deliberately <em>not</em> treated as a throttle:
   * see {@link GitHubWriteRetry}.
   */
  public static Optional<GitHubApiError> of(WebApplicationException e) {
    return Optional.ofNullable(e.getResponse()).map(GitHubApiError::from);
  }

  /**
   * Whether GitHub is throttling this call rather than refusing it, which is the whole difference
   * between "post it again in a moment" and "this will never work". 429 says so outright; a 403
   * says so only through a {@code Retry-After}, an exhausted {@code x-ratelimit-remaining}, or the
   * rate-limit wording in the body. A permission 403 carries none of the three and so fails fast.
   */
  public boolean isThrottled() {
    if (status == 429) {
      return true;
    }
    if (status != 403) {
      return false;
    }
    return retryAfterSeconds().isPresent()
        || "0".equals(rateLimitRemaining)
        || THROTTLE_WORDING.matcher(body).find();
  }

  /**
   * Whether GitHub rejected the credential rather than the request. 401 is the only status an
   * installation token that has expired, been revoked or been replaced can draw, and GitHub decides
   * it before routing the request — so the call never reached the handler that would have written
   * anything, and it is worth repeating once a fresh token has been minted. See {@link
   * GitHubTokenRefresh}.
   */
  public boolean isExpiredCredential() {
    return status == 401;
  }

  /**
   * Whether this failure is worth a warning in the log. Reads probe for optional files ({@code
   * .github/instructions}, the repo settings file) and treat 404 as "absent", so a 404 is expected
   * traffic and only clutters the log at warning level; an auth, throttle or server failure is not.
   */
  public boolean isSevere() {
    return status == 401 || status == 403 || status == 429 || status >= 500;
  }

  /**
   * How long to wait before repeating a throttled call, given the attempt just made and the current
   * time. {@code Retry-After} wins when GitHub sent one; otherwise the reset instant of the
   * exhausted rate-limit window is honoured; otherwise a linear backoff off {@link #FALLBACK_DELAY}
   * so a silent throttle still slows down. Never negative — a reset already in the past means the
   * window has reopened and the call can go straight back out.
   */
  public Duration retryDelay(int attempt, Instant now) {
    var fromHeader = retryAfterSeconds();
    if (fromHeader.isPresent()) {
      return atLeastZero(Duration.ofSeconds(fromHeader.get()));
    }
    var reset = parseLong(rateLimitReset);
    if (reset.isPresent()) {
      return atLeastZero(Duration.between(now, Instant.ofEpochSecond(reset.get())));
    }
    return FALLBACK_DELAY.multipliedBy(attempt);
  }

  /**
   * One line naming everything that separates one GitHub failure from another: the status, the
   * throttling headers when present, and the body. This is the line that was missing in #568.
   */
  public String diagnostics() {
    var text = new StringBuilder("status=").append(status);
    append(text, "retry-after", retryAfter);
    append(text, "x-ratelimit-remaining", rateLimitRemaining);
    append(text, "x-ratelimit-reset", rateLimitReset);
    append(text, "x-ratelimit-resource", rateLimitResource);
    return text.append(" body=").append(body.isEmpty() ? "<unavailable>" : body).toString();
  }

  private static void append(StringBuilder text, String name, String value) {
    if (value != null && !value.isBlank()) {
      text.append(' ').append(name).append('=').append(value);
    }
  }

  private Optional<Long> retryAfterSeconds() {
    return parseLong(retryAfter);
  }

  private static Optional<Long> parseLong(String value) {
    if (value == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(value.trim()));
    } catch (NumberFormatException _) {
      // GitHub sends whole seconds; an HTTP-date or anything else is treated as "unspecified".
      return Optional.empty();
    }
  }

  private static Duration atLeastZero(Duration delay) {
    return delay.isNegative() ? Duration.ZERO : delay;
  }

  /**
   * The response body as loggable text. An inbound response is buffered first so reading it here
   * does not consume it for the caller that later inspects the same exception; a response built
   * in-process holds its entity as an object instead, and one that is closed or has no readable
   * entity yields an empty body rather than breaking the failure path it exists to explain.
   */
  private static String readBody(Response response) {
    try {
      if (response.getEntity() instanceof String text) {
        return clean(text);
      }
      response.bufferEntity();
      return clean(response.readEntity(String.class));
    } catch (RuntimeException _) {
      return "";
    }
  }

  private static String clean(String raw) {
    if (raw == null) {
      return "";
    }
    var collapsed = WHITESPACE.matcher(raw.strip()).replaceAll(" ");
    var redacted = CREDENTIAL_SHAPED.matcher(collapsed).replaceAll(REDACTED);
    if (redacted.length() <= MAX_BODY_CHARS) {
      return redacted;
    }
    // Never leave a dangling high surrogate at the cut point — that would corrupt a code point.
    int keep =
        Character.isHighSurrogate(redacted.charAt(MAX_BODY_CHARS - 1))
            ? MAX_BODY_CHARS - 1
            : MAX_BODY_CHARS;
    return redacted.substring(0, keep) + "…";
  }
}
