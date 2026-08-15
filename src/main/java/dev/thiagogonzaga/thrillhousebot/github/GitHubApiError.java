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
import java.util.regex.Matcher;
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
   * <p>Deliberately ONE alternation, read as a union across this pattern and {@link
   * #CREDENTIAL_SHAPED_VALUE}, rather than one masking pass per shape: the shapes overlap, and a
   * single pass masks each overlap as the leftmost match, whereas masking in passes decides the
   * overlap by pass order and leaves material the one-pass form masks. Whichever order is chosen,
   * {@code "…ghp_<7 chars>Bearer <20 chars>"} keeps its token prefix unmasked if the bearer pass
   * runs first, and {@code "Bearer ghp_<10 chars>.<tail>"} keeps the tail of the bearer value if
   * the token pass does. So {@link #redactCredentials} still scans once, taking the leftmost match
   * across both patterns — split in two, prefixes here and value shapes there, only because one
   * alternation of all four shapes is more than the regex complexity budget allows.
   */
  private static final Pattern CREDENTIAL_SHAPED_PREFIX =
      Pattern.compile("(?i)(gh[pousr]_\\w{10,})|(github_pat_\\w{10,})");

  /**
   * The bearer and JWT shapes — the value half of {@link #CREDENTIAL_SHAPED_PREFIX}'s union, tried
   * second on a position tie exactly as the one-alternation form tried its alternatives in order.
   */
  private static final Pattern CREDENTIAL_SHAPED_VALUE =
      Pattern.compile(
          "(?i)(bearer\\s+[\\w.~+/=-]{10,})" + "|(eyJ[\\w-]{8,}\\.[\\w-]{8,}\\.[\\w-]{8,})");

  /**
   * The wording GitHub uses when it is throttling rather than refusing. A secondary rate limit and
   * a primary rate limit both arrive as 403; only the message distinguishes them from a permission
   * refusal when the headers are absent.
   *
   * <p>The blocked-creation wording is carried here as well as in {@link #CONTENT_CREATION_BLOCK},
   * in the same two word orders, because broadening only the latter would be inert: a body that
   * named the block without one of the other phrases would not be read as a throttle at all, so the
   * call would fail fast and the floor below it would never be consulted (#722). A 403 that says
   * creation is blocked is a throttle by definition, never a permission refusal.
   */
  private static final Pattern THROTTLE_WORDING =
      Pattern.compile(
          "(?i)secondary rate limit|abuse detection|rate limit exceeded"
              + "|blocked from (?:content creation|creating content)");

  /** Collapses the whitespace of a body so one failure stays on one log line. */
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /** Backoff used when GitHub throttles without saying for how long. */
  static final Duration FALLBACK_DELAY = Duration.ofSeconds(5);

  /**
   * The wording of the block that stops content creation specifically, rather than any other
   * throttle. It is the one that lasts long enough for the shape of the backoff to matter (#722).
   *
   * <p>Deliberately narrower than {@link #THROTTLE_WORDING}, and in particular it does NOT match
   * the bare phrase "secondary rate limit". That is GitHub's generic secondary-limit wording, sent
   * for any endpoint, and it is a milder class than the block this floor is sized against: matching
   * it would floor every such throttle at 30s and hold a PR's dispatcher slot for up to the whole
   * budget, where the linear backoff's 5s, 10s then 15s is all that class asks for.
   *
   * <p>What is matched is wording that names the block itself. The measured body said "You have
   * exceeded a secondary rate limit and have been temporarily blocked from content creation", so it
   * matches on its second half; GitHub's older wording for the same block says "abuse detection";
   * and both word orders of the creation phrase are taken, since the rule must not turn on which
   * one GitHub happened to use.
   *
   * <p>It is deliberately the whole phrase rather than "content creation" alone: this runs over an
   * error body that is attacker-influenced text, and the bare noun phrase is loose enough to appear
   * in a message that is not this block.
   */
  private static final Pattern CONTENT_CREATION_BLOCK =
      Pattern.compile("(?i)abuse detection|blocked from (?:content creation|creating content)");

  /**
   * Floor on one wait while GitHub is blocking content creation and named no deadline of its own
   * (#722).
   *
   * <p>The measured block ran 72 seconds, and both ways of deriving a delay without a {@code
   * Retry-After} undershoot it badly. The linear fallback gives 5s, 10s then 15s — thirty seconds
   * of waiting spread across the whole budget. An {@code x-ratelimit-reset} already in the past
   * gives zero, so every attempt fires at once and the budget is gone in milliseconds, which is
   * worse than not repeating at all: it spends the repeats the generated content depends on while
   * GitHub is still refusing.
   *
   * <p>Neither number is GitHub speaking about this block. The reset instant belongs to the
   * <em>primary</em> window, which a secondary limit leaves alone — the run behind #722 is exactly
   * that, a content-creation block carrying {@code x-ratelimit-remaining=4771}. So a delay that was
   * derived rather than given is floored here, which is what makes {@link
   * GitHubWriteRetry#TOTAL_BUDGET} a floor for this failure instead of only a ceiling. An explicit
   * {@code Retry-After} still wins outright: there GitHub is naming its own deadline.
   */
  static final Duration CONTENT_CREATION_BLOCK_MIN_DELAY = Duration.ofSeconds(30);

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
   * so a silent throttle still slows down. Never negative — for everything but the block below, a
   * reset already in the past means the window has reopened and the call can go straight back out.
   *
   * <p>One exception, added in #722: when GitHub is blocking content creation and named no deadline
   * of its own, a derived delay is floored at {@link #CONTENT_CREATION_BLOCK_MIN_DELAY}. Both
   * derivations undershoot that block badly — the linear fallback by design, a stale reset instant
   * by returning nothing at all.
   *
   * <p>The floor applies to <em>every</em> rate-limit header on such a block, including {@code
   * x-ratelimit-remaining: 0}. Those headers describe the <em>primary</em> window, which a
   * content-creation block leaves untouched, so a primary window that is also spent says nothing
   * about when creation reopens: a block carrying {@code remaining=0} and a reset ten seconds out
   * would otherwise wait ten seconds a time and spend the whole budget inside the 72-second window
   * this is sized against. An earlier revision carved that case out and reintroduced exactly the
   * failure the floor exists to prevent.
   */
  public Duration retryDelay(int attempt, Instant now) {
    var fromHeader = retryAfterSeconds();
    if (fromHeader.isPresent()) {
      return atLeastZero(Duration.ofSeconds(fromHeader.get()));
    }
    var reset = parseLong(rateLimitReset);
    var derived =
        reset.isPresent()
            ? atLeastZero(Duration.between(now, Instant.ofEpochSecond(reset.get())))
            : FALLBACK_DELAY.multipliedBy(attempt);
    return blocksContentCreation() && derived.compareTo(CONTENT_CREATION_BLOCK_MIN_DELAY) < 0
        ? CONTENT_CREATION_BLOCK_MIN_DELAY
        : derived;
  }

  /**
   * Whether GitHub is blocking content creation rather than throttling in some milder way — the
   * failure whose measured width the budget is sized against (#722).
   */
  private boolean blocksContentCreation() {
    return CONTENT_CREATION_BLOCK.matcher(body).find();
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
   * One left-to-right masking pass over both credential patterns: at each step the leftmost match
   * wins, a position tie goes to the prefix shapes, and scanning resumes after the mask — the
   * verbatim {@code replaceAll} semantics of the four shapes as one alternation, kept even though
   * the alternation itself had to be split to fit the regex complexity budget. No shape matches an
   * empty string, so every step advances.
   */
  private static String redactCredentials(String text) {
    Matcher[] shapes = {
      CREDENTIAL_SHAPED_PREFIX.matcher(text), CREDENTIAL_SHAPED_VALUE.matcher(text)
    };
    var starts = new int[] {-1, -1};
    var ends = new int[shapes.length];
    var out = new StringBuilder(text.length());
    var from = 0;
    while (true) {
      var leftmost = leftmostShape(shapes, starts, ends, from);
      if (leftmost == -1) {
        break;
      }
      out.append(text, from, starts[leftmost]).append(REDACTED);
      from = ends[leftmost];
      starts[leftmost] = -1;
    }
    return out.append(text, from, text.length()).toString();
  }

  /** A shape with no further matches; loses every comparison for the leftmost slot. */
  private static final int NO_MORE_MATCHES = Integer.MAX_VALUE;

  /**
   * The index of the shape holding the leftmost live candidate at or past {@code from}, or -1 when
   * both shapes are out of matches; a position tie keeps the lowest index, the one-alternation
   * form's alternative order. Only a consumed or overtaken candidate is re-found — one at or past
   * {@code from} is still that shape's next match, since the find that produced it proved nothing
   * of that shape starts before it — so each shape is scanned once end to end rather than from
   * every resume point.
   */
  private static int leftmostShape(Matcher[] shapes, int[] starts, int[] ends, int from) {
    var leftmost = -1;
    var best = NO_MORE_MATCHES;
    for (var i = 0; i < shapes.length; i++) {
      if (starts[i] < from) {
        if (shapes[i].find(from)) {
          starts[i] = shapes[i].start();
          ends[i] = shapes[i].end();
        } else {
          starts[i] = NO_MORE_MATCHES;
        }
      }
      if (starts[i] < best) {
        best = starts[i];
        leftmost = i;
      }
    }
    return leftmost;
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
    var redacted = redactCredentials(collapsed);
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
