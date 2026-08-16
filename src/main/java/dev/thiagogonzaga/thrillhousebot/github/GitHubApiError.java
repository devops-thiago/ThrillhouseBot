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
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
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

  /**
   * How much of a response body the throttle classification reads (#732, #747).
   *
   * <p>Sixteen times the log cap, because the two jobs the body does have nothing to do with each
   * other. The log line is capped so one failure cannot flood a log file, and that cap is about the
   * operator's screen; classification is a yes/no about whether a completed generation gets another
   * attempt, and reading it off the same 512 characters made it depend on where GitHub put its
   * message. #740 then bounded the input to redaction at {@code MAX_BODY_CHARS * 2}, which closed
   * the one path — redaction compressing a long credential-shaped prefix away — by which deeper
   * wording still reached the classified string at all.
   *
   * <p>A bound is still wanted, because the entity is read with no size limit of its own and the
   * point of #731 was to keep the cost of explaining a failed write off the review's carrier
   * thread. Both patterns are flat literal alternations with no backtracking, so a pass over this
   * many characters is a linear scan measured in microseconds — the quadratic shape #731 found is
   * in the credential redaction, which still sees only its own bound.
   */
  static final int MAX_CLASSIFIED_CHARS = 8 * 1024;

  /** Replacement for anything in a response body shaped like a credential. */
  static final String REDACTED = "***";

  /**
   * GitHub App/PAT/OAuth token prefixes and bearer/JWT shapes. GitHub does not echo credentials in
   * an error body, but a body is untrusted text on its way to a log file, so a token-shaped run of
   * characters is masked rather than trusted to be harmless.
   *
   * <p>Deliberately read as a union across this pattern, {@link #BEARER_SHAPED_VALUE} and {@link
   * #JWT_SHAPED_VALUE}, rather than one masking pass per shape: the shapes overlap, and a single
   * pass masks each overlap as the leftmost match, whereas masking in passes decides the overlap by
   * pass order and leaves material the one-pass form masks. Whichever order is chosen, {@code
   * "…ghp_<7 chars>Bearer <20 chars>"} keeps its token prefix unmasked if the bearer pass runs
   * first, and {@code "Bearer ghp_<10 chars>.<tail>"} keeps the tail of the bearer value if the
   * token pass does. So {@link #redactCredentials} still scans once, taking the leftmost match
   * across every pattern — split apart, prefixes here and the value shapes beside it, only because
   * one alternation of all four shapes is more than the regex complexity budget allows.
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
   * So the case-insensitivity is scoped here, to the bearer shape. {@link #JWT_SHAPED_VALUE} is
   * case-sensitive instead, because its {@code eyJ} is not a spelling but arithmetic: base64url of
   * a header opening {@code &#123;"} gives {@code ey} plus a third character fixed by the first
   * key's leading byte, which is {@code J} for every key beginning with a letter. RFC 7515 requires
   * {@code alg}, so a real header encodes to {@code eyJ}; a nonstandard first key that is a digit
   * or empty encodes to {@code eyI} and is deliberately not matched, since widening the anchor
   * would mask ordinary prose beginning {@code ey} for a shape no issuer emits.
   *
   * <p>The value takes the same four-character floor as {@link #CREDENTIAL_SHAPED_PREFIX}, for the
   * same reason: the {@code Bearer } that precedes it is the discriminator, and ten characters only
   * meant the bound could sever a header value into something that no longer looked like one.
   *
   * <p>The word {@code bearer} is consumed with the value rather than left in the line, which does
   * mask the noun in prose such as {@code missing bearer token}. Masking only the value was tried
   * and is worse: the match then starts after {@code bearer }, so on {@code Bearer ghp_….tail} the
   * token shape wins the tie at that position and the tail escapes — the overlap that the one-pass,
   * leftmost-match design above exists to swallow.
   */
  private static final Pattern BEARER_SHAPED_VALUE =
      Pattern.compile("(?i:bearer\\s+[\\w.~+/=-]{4,})");

  /**
   * The JWT shape, kept apart from {@link #BEARER_SHAPED_VALUE} rather than alternated with it: one
   * pattern carrying both ran past the regex-complexity budget, and {@link #redactCredentials}
   * already scans any number of shapes in one pass, taking the leftmost match and preferring the
   * earlier shape on a tie — the order these are declared in.
   */
  private static final Pattern JWT_SHAPED_VALUE =
      Pattern.compile("(?<![\\w-])eyJ[\\w-]{8,}(?:\\.[\\w-]*){1,2}");

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
  private final Body body;

  private GitHubApiError(
      int status,
      String retryAfter,
      String rateLimitRemaining,
      String rateLimitReset,
      String rateLimitResource,
      Body body) {
    this.status = status;
    this.retryAfter = retryAfter;
    this.rateLimitRemaining = rateLimitRemaining;
    this.rateLimitReset = rateLimitReset;
    this.rateLimitResource = rateLimitResource;
    this.body = body;
  }

  /**
   * The two readings of one response body, kept apart because the two callers want opposite things
   * (#732, #747).
   *
   * @param logged the line an operator reads: collapsed, bounded, redacted, capped at {@link
   *     #MAX_BODY_CHARS}, and marked with an ellipsis when anything was dropped
   * @param classified what {@link #isThrottled()} and {@link #blocksContentCreation()} match
   *     against: the collapsed body bounded at {@link #MAX_CLASSIFIED_CHARS} and nothing else.
   *     Reading the logged form instead made the retry decision turn on where in the body GitHub
   *     happened to put its message — wording past the cap, or behind enough credential-shaped
   *     material to fill the redaction bound, classified as "not a throttle" and the write was not
   *     repeated at all. It is deliberately the <em>unredacted</em> text: masking runs before
   *     classification would read it, and a mask that swallowed the word {@code blocked} turned a
   *     content-creation block into a permission refusal. Nothing in here is ever logged or
   *     returned — the two patterns answer yes or no and the string is dropped.
   */
  private record Body(String logged, String classified) {

    /** A body that could not be read at all — no line to log, nothing to classify. */
    private static final Body UNREADABLE = new Body("", "");
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
   *
   * <p>The wording is looked for in {@link Body#classified}, not in the line that goes to the log:
   * the log's cap and the redaction bound are about what an operator should be shown, and letting
   * them decide whether a completed generation is repeated turned this into a question about where
   * GitHub put its message (#732, #747).
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
        || THROTTLE_WORDING.matcher(body.classified()).find();
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
    return rateLimitResetInstant()
        .map(reset -> atLeastZero(Duration.between(now, reset)))
        .orElseGet(() -> FALLBACK_DELAY.multipliedBy(attempt));
  }

  /**
   * The instant {@code x-ratelimit-reset} names, or empty when it does not name one.
   *
   * <p>{@code Long.parseLong} accepts values {@link Instant#ofEpochSecond(long)} rejects: past ±31
   * 556 889 864 403 199 seconds it throws {@link DateTimeException}, which is neither the {@link
   * WebApplicationException} the whole write path is built around nor anything {@link
   * GitHubWriteRetry#call} converts. It escaped that loop past every {@code catch
   * (WebApplicationException)} between here and the caller, so {@link GitHubLostWrites} did not
   * record the write as lost either — the write and the record of its loss went together, over a
   * header from an intermediary. A value that cannot be an instant says nothing about when the
   * window reopens, which is exactly what a non-numeric header already means here, so it gets the
   * same answer: unspecified, and the linear fallback takes over.
   */
  private Optional<Instant> rateLimitResetInstant() {
    try {
      return parseLong(rateLimitReset).map(Instant::ofEpochSecond);
    } catch (DateTimeException _) {
      return Optional.empty();
    }
  }

  /**
   * Whether GitHub is blocking content creation rather than throttling in some milder way — the
   * failure whose measured width the budget is sized against (#722). Read off {@link
   * Body#classified} for the reasons {@link #isThrottled()} gives.
   */
  private boolean blocksContentCreation() {
    return CONTENT_CREATION_BLOCK.matcher(body.classified()).find();
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
    var logged = body.logged();
    return text.append(" body=").append(logged.isEmpty() ? "<unavailable>" : logged).toString();
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
      CREDENTIAL_SHAPED_PREFIX.matcher(text),
      BEARER_SHAPED_VALUE.matcher(text),
      JWT_SHAPED_VALUE.matcher(text)
    };
    var starts = new int[shapes.length];
    Arrays.fill(starts, -1);
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
   * The response body, in both of the readings {@link Body} names. An inbound response is buffered
   * first so reading it here does not consume it for the caller that later inspects the same
   * exception; a response built in-process holds its entity as an object instead, and one that is
   * closed or has no readable entity yields {@link Body#UNREADABLE} rather than breaking the
   * failure path it exists to explain.
   */
  private static Body readBody(Response response) {
    try {
      if (response.getEntity() instanceof String text) {
        return clean(text);
      }
      response.bufferEntity();
      return clean(response.readEntity(String.class));
    } catch (RuntimeException _) {
      return Body.UNREADABLE;
    }
  }

  /**
   * A response body read both ways: as one line of loggable text — collapsed, bounded, redacted,
   * then capped at {@link #MAX_BODY_CHARS} — and as the wider, unredacted window the throttle
   * classification matches against.
   *
   * <p>One collapse pass feeds both, and the two readings diverge after it. Everything the log line
   * has done to it from that point on is for the log's sake: the bound is #731's cost control on a
   * quadratic redaction, the cap is so one failure cannot flood a log file, and the mask is because
   * a body is untrusted text on its way to an operator's screen. None of those is a statement about
   * whether GitHub is throttling, and every one of them used to be able to decide it (#732, #747).
   *
   * <p>Ordering is the whole of it, so it is worth naming what each cut can still do. The
   * classified window is cut once, from the collapsed body, so nothing between the two ever
   * shortens it. The logged line keeps the {@code bounded → redacted → capped} order #740
   * established, and the ellipsis still marks either cut.
   *
   * <p>The bound before the redaction is the point (#731). {@code readBody} reads the entity with
   * no size limit of its own, and redaction used to run over all of it, so the cost of explaining a
   * failed write was set by whatever the configured API host chose to send. {@link
   * #JWT_SHAPED_VALUE} is quadratic on a body of repeated {@code eyJ} — {@code [\w-]} excludes
   * {@code .}, so each greedy run consumes to end-of-input, fails to find its separator and
   * backtracks a character at a time, from one in every three positions. Measured: 20 000 chars
   * cost 331 ms, 40 000 cost 1 213 ms, 80 000 cost 4 843 ms, 160 000 cost 19 404 ms, and a 1.2 MB
   * body cost about eighteen minutes of CPU — spent on the review's own carrier thread, inside the
   * failure path that exists to explain a failed write, before the retry decision it feeds is even
   * reached. GitHub's real error bodies are ~300 characters; a GHES or reverse-proxy error page, a
   * misconfigured base URL or a compromised endpoint is not.
   *
   * <p>The collapse above is one linear pass over the whole body; cutting first bounds the
   * redaction pass at a constant. The cut is twice {@link #MAX_BODY_CHARS} so that redaction, which
   * only ever shortens, still has material to fill the cap with; past that the output was going to
   * be truncated anyway, and what a wider window would pull into view is more of the token-shaped
   * run that is being masked out. The ellipsis marks either cut, so a body shortened here is never
   * mistaken for a body GitHub sent whole.
   */
  private static Body clean(String raw) {
    if (raw == null) {
      return Body.UNREADABLE;
    }
    var collapsed = WHITESPACE.matcher(raw).replaceAll(" ").strip();
    var bounded = cutTo(collapsed, MAX_BODY_CHARS * 2);
    var redacted = redactCredentials(bounded);
    var capped = cutTo(redacted, MAX_BODY_CHARS);
    var logged =
        capped.length() < redacted.length() || bounded.length() < collapsed.length()
            ? capped + "…"
            : capped;
    return new Body(logged, cutTo(collapsed, MAX_CLASSIFIED_CHARS));
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
