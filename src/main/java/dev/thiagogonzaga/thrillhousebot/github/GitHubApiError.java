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
   *
   * <p>Four value characters, not ten (#746). The sigil is the whole discriminator here — {@code
   * ghp_} and {@code github_pat_} do not occur in prose, so the length floor was buying nothing the
   * sigil did not already buy, while costing the one thing that matters: a token the bound before
   * redaction severs below the floor stops matching and reaches the log with its first nine
   * characters intact. This is the same reasoning #740 applied to the JWT alternative below and did
   * not carry across to the shapes that still needed it.
   */
  private static final Pattern CREDENTIAL_SHAPED_PREFIX =
      Pattern.compile("(?i)(gh[pousr]_\\w{4,})|(github_pat_\\w{4,})");

  /**
   * The bearer and JWT shapes — the value half of {@link #CREDENTIAL_SHAPED_PREFIX}'s union, tried
   * second on a position tie exactly as the one-alternation form tried its alternatives in order.
   *
   * <p>Everything after the first dot is optional in both length and count, so a token the bound
   * below cut anywhere past that dot is masked whole — including a cut landing in the first few
   * payload characters, which a {@code {8,}} on each segment would have let through. Requiring all
   * three segments made redaction depend on where the cut happened to land: a token whose second
   * dot fell past the bound went through unmasked, and its header and the payload characters that
   * fit reached the log.
   *
   * <p>The first dot stays mandatory, so the one shape still not masked is a cut before it: the
   * {@code eyJ} header prefix alone, which carries the algorithm and type claims and no secret.
   * Matching a dotless run instead would mask every long unbroken run of word characters and blank
   * the very body this line exists to explain.
   *
   * <p>Which is what the widened tail did anyway until #746, because the {@code (?i)} covered the
   * whole alternation and the header was unanchored: any {@code eyj} in any case, anywhere inside a
   * longer run, followed by a dot at any distance. {@code eyjafjallajokull.internal.example.com}
   * came out as {@code ***.com} and a base64 blob with a filename after it came out as {@code ***}.
   * So the case-insensitivity is scoped to the bearer half — a JWT header is base64url of {@code
   * &#123;"}, which is always literally {@code eyJ} — and {@code (?<![\w-])} pins the header to the
   * start of a token, leaving the cut-token property #740 added intact: a cut anywhere past the
   * first dot still masks the whole run.
   *
   * <p>The bearer value takes the same four-character floor as {@link #CREDENTIAL_SHAPED_PREFIX},
   * for the same reason: {@code Bearer } is the discriminator, and ten characters only meant the
   * bound could sever a header value into something that no longer looked like one.
   */
  private static final Pattern CREDENTIAL_SHAPED_VALUE =
      Pattern.compile(
          "(?i:bearer\\s+[\\w.~+/=-]{4,})" + "|((?<![\\w-])eyJ[\\w-]{8,}(?:\\.[\\w-]*){1,2})");

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

  /**
   * Collapses the whitespace of a body so one failure stays on one log line.
   *
   * <p>Wider than {@code \s}, which java.util.regex reads as the ASCII six ({@code [
   * \t\n\x0B\f\r]}) unless the pattern asks for Unicode character classes. CR and LF being
   * collapsed closes the classic forged-record vector, but NEL (U+0085), LINE SEPARATOR (U+2028),
   * PARAGRAPH SEPARATOR (U+2029), NUL and the ANSI escape all survived it (#731) — and a log
   * viewer, a terminal, or a JSON/ECS shipper may treat any of them as a record boundary or as a
   * screen-control sequence. This class documents a body as attacker-influenced text on its way to
   * a log file and already pays for a collapse pass on that basis; this is that pass covering what
   * it claims to.
   *
   * <p>{@code \p{IsCc}} is the Unicode general category rather than POSIX {@code \p{Cntrl}}, so it
   * reaches the C1 controls (U+0080–U+009F, NEL among them) as well as C0 and DEL.
   *
   * <p>{@code \p{IsCf}} is here for the same harm rather than for line integrity: bidi overrides
   * and isolates (RLO, LRM, LRI) reorder what an operator reads, and the invisible joiners and
   * spaces (ZWJ, ZWNJ, ZWSP, the BOM, the soft hyphen) let two different bodies render identically
   * — both forge a record's meaning as surely as a forged boundary forges its extent. The accepted
   * cost is that an echoed user string loses its grapheme clusters: an emoji ZWJ sequence or an
   * Indic conjunct is split apart. A {@code body=} field is a diagnostic identity rather than a
   * rendering surface, and which characters arrived is the question it exists to answer. Replacing
   * with a space rather than deleting is part of the same bargain — deletion would let {@code
   * admin<ZWSP>istrator} close up into a different real word, a space cannot.
   */
  private static final Pattern WHITESPACE =
      Pattern.compile("[\\s\\p{IsCc}\\p{IsCf}\\u2028\\u2029]+");

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
   * that, a content-creation block carrying {@code x-ratelimit-remaining=4771}. So the delay is
   * floored here, which is what makes {@link GitHubWriteRetry#TOTAL_BUDGET} a floor for this
   * failure instead of only a ceiling.
   *
   * <p>The floor applies however the delay was arrived at, an explicit {@code Retry-After} included
   * (#730). An earlier revision floored only a <em>derived</em> delay, on the reading that a {@code
   * Retry-After} is GitHub naming its own deadline — but that header names a deadline for <em>this
   * request</em>, not the width of the block. A {@code Retry-After: 5} on the measured body left
   * four attempts spread over 15 seconds against a 72-second block: the whole budget spent inside
   * the window, and less waiting than the linear fallback this floor replaced. So the invariant
   * held on one branch of the derivation only. Longer is still GitHub's to ask for: a {@code
   * Retry-After} past the floor wins outright, clamped by {@link
   * GitHubWriteRetry#MAX_DELAY_PER_ATTEMPT} exactly as before.
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
   * <p>One exception, added in #722 and widened in #730: when GitHub is blocking content creation,
   * a wait shorter than {@link #CONTENT_CREATION_BLOCK_MIN_DELAY} is lifted to it. Every way of
   * arriving at a wait undershoots that block badly — the linear fallback by design, a stale reset
   * instant by returning nothing at all, and a short {@code Retry-After} by pacing one call rather
   * than describing the block.
   *
   * <p>The floor applies to <em>every</em> rate-limit header on such a block, including {@code
   * x-ratelimit-remaining: 0}. Those headers describe the <em>primary</em> window, which a
   * content-creation block leaves untouched, so a primary window that is also spent says nothing
   * about when creation reopens: a block carrying {@code remaining=0} and a reset ten seconds out
   * would otherwise wait ten seconds a time and spend the whole budget inside the 72-second window
   * this is sized against. An earlier revision carved that case out and reintroduced exactly the
   * failure the floor exists to prevent; a second one carved out {@code Retry-After} and reopened
   * it again, since three waits of five seconds is a smaller budget still.
   */
  public Duration retryDelay(int attempt, Instant now) {
    var delay =
        retryAfterSeconds()
            .map(seconds -> atLeastZero(Duration.ofSeconds(seconds)))
            .orElseGet(() -> derivedDelay(attempt, now));
    return blocksContentCreation() && delay.compareTo(CONTENT_CREATION_BLOCK_MIN_DELAY) < 0
        ? CONTENT_CREATION_BLOCK_MIN_DELAY
        : delay;
  }

  /**
   * The wait GitHub implied rather than named: the reset instant of the exhausted rate-limit window
   * when one was sent, and otherwise a linear backoff off {@link #FALLBACK_DELAY} so a silent
   * throttle still slows down.
   */
  private Duration derivedDelay(int attempt, Instant now) {
    return parseLong(rateLimitReset)
        .map(reset -> atLeastZero(Duration.between(now, Instant.ofEpochSecond(reset))))
        .orElseGet(() -> FALLBACK_DELAY.multipliedBy(attempt));
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

  /**
   * A response body as one line of loggable text: collapsed, bounded, redacted, then capped at
   * {@link #MAX_BODY_CHARS}.
   *
   * <p>The bound before the redaction is the point (#731). {@code readBody} reads the entity with
   * no size limit of its own, and redaction used to run over all of it, so the cost of explaining a
   * failed write was set by whatever the configured API host chose to send. {@link
   * #CREDENTIAL_SHAPED_VALUE}'s JWT alternative is quadratic on a body of repeated {@code eyJ} —
   * {@code [\w-]} excludes {@code .}, so each greedy run consumes to end-of-input, fails to find
   * its separator and backtracks a character at a time, from one in every three positions.
   * Measured: 20 000 chars cost 331 ms, 40 000 cost 1 213 ms, 80 000 cost 4 843 ms, 160 000 cost 19
   * 404 ms, and a 1.2 MB body cost about eighteen minutes of CPU — spent on the review's own
   * carrier thread, inside the failure path that exists to explain a failed write, before the retry
   * decision it feeds is even reached. GitHub's real error bodies are ~300 characters; a GHES or
   * reverse-proxy error page, a misconfigured base URL or a compromised endpoint is not.
   *
   * <p>The collapse above is one linear pass over the whole body; cutting first bounds the
   * redaction pass at a constant. The cut is twice {@link #MAX_BODY_CHARS} so that redaction, which
   * only ever shortens, still has material to fill the cap with; past that the output was going to
   * be truncated anyway, and what a wider window would pull into view is more of the token-shaped
   * run that is being masked out. The ellipsis marks either cut, so a body shortened here is never
   * mistaken for a body GitHub sent whole.
   */
  private static String clean(String raw) {
    if (raw == null) {
      return "";
    }
    var collapsed = WHITESPACE.matcher(raw).replaceAll(" ").strip();
    var bounded = cutTo(collapsed, MAX_BODY_CHARS * 2);
    var redacted = redactCredentials(bounded);
    var capped = cutTo(redacted, MAX_BODY_CHARS);
    return capped.length() < redacted.length() || bounded.length() < collapsed.length()
        ? capped + "…"
        : capped;
  }

  /**
   * {@code text} cut to at most {@code limit} characters, never through a surrogate pair — a
   * dangling high surrogate at the cut point would corrupt a code point.
   */
  private static String cutTo(String text, int limit) {
    if (text.length() <= limit) {
      return text;
    }
    return text.substring(0, Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit);
  }
}
