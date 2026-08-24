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
package dev.thiagogonzaga.thrillhousebot.review;

import dev.thiagogonzaga.thrillhousebot.review.ai.ReviewResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic re-check of a maintainer's decline against the code the review actually saw.
 *
 * <p>A decline is a <em>claim</em>, not ground truth: a correct finding can be closed by an
 * incorrect rebuttal, and the rebuttal often names the very mechanism that makes the bug real. This
 * class answers one narrow question with high precision — does the reviewed code plainly contradict
 * the rebuttal's premise? — and says nothing at all otherwise, so the default stays "trust the
 * maintainer".
 *
 * <p>Exactly one contradiction family is detected, the one that is demonstrable from code text
 * alone: a <b>concurrency</b> finding declined on a <b>"this cannot run concurrently"</b> premise
 * while the reviewed code shows the path being <b>dispatched onto a shared executor / new
 * thread</b>. All three legs must hold:
 *
 * <ol>
 *   <li>the prior finding is about concurrency ({@link #CONCURRENCY_FINDING}) — a race, a
 *       check-then-act, thread-safety, atomicity;
 *   <li>the maintainer's reply asserts that concurrency is impossible ({@link
 *       #NO_CONCURRENCY_CLAIMS}) — "single-threaded", "runs serially", "only ever called from …";
 *   <li>the reviewed code contains a concurrent-dispatch construct ({@link #CONCURRENT_DISPATCHES})
 *       — an unbounded/pooled executor, {@code executor.submit/execute}, {@code CompletableFuture
 *       .runAsync}, {@code new Thread(...)}, {@code @Async}, {@code parallelStream()}.
 * </ol>
 *
 * <p>Leg 2 is deliberately evaluated on the reply with fenced code blocks and blockquotes removed:
 * a reply that merely quotes the bot's own finding, or pastes code, is not the maintainer asserting
 * anything (the same "never act on quoted markdown" rule the comment-command parsers follow).
 *
 * <p>Everything else — house style, intent, accepted risk, priority, "we'll do it later", or any
 * premise that is not refutable from code text — matches nothing here and keeps the decline.
 *
 * <p><b>Known limitation.</b> The evidence must be inside the material the review call saw (the
 * reviewed diff). When the contradicting mechanism lives in an unchanged file — the executor
 * producer of the dogfood PR, say — this check cannot see it and stays silent; only the
 * prompt-level rule in {@code PrReviewPrompts} can catch that case, and only when the model has
 * that context.
 */
final class RebuttalContradiction {

  /** Max characters of the reply sentence / code line quoted back in the status note. */
  private static final int QUOTE_LIMIT = 120;

  /** The prior finding must itself be about concurrency for this family to apply. */
  private static final Pattern CONCURRENCY_FINDING =
      Pattern.compile(
          "race condition|data race|\\brace\\b|concurrent|concurrency|thread[- ]saf"
              + "|time[- ]of[- ]check|toctou|check[- ]then[- ](?:act|insert|update|write)"
              + "|atomicity|not atomic|interleav",
          Pattern.CASE_INSENSITIVE);

  /**
   * The ways a maintainer asserts the flagged path cannot run concurrently, one small pattern per
   * argument rather than a single mega-alternation. Kept separate on purpose: this matcher decides
   * when the bot is allowed to overrule a human, so each argument has to be readable and testable
   * on its own, and every quantifier is bounded because the input is untrusted reply prose.
   *
   * <p>"Only ever called from X" is one of them: a single call site is the usual way a decline
   * argues a race away, and it is exactly the premise an asynchronous dispatch at that call site
   * refutes.
   */
  private static final List<Pattern> NO_CONCURRENCY_CLAIMS =
      List.of(
          // "the handler is single-threaded"
          ci("single[- ]threaded|\\bsingle thread\\b"),
          // "they run serially / one at a time"
          ci("(?:runs?|executed?) serially|serialized|sequentially|one at a time"),
          // "it cannot run concurrently"
          ci("(?:can ?not|can'?t) (?:run|be|happen|occur|execute)s? concurrent(?:ly)?"),
          // "it never runs concurrently"
          ci("never (?:run|be|happen|occur|execute)s? concurrent(?:ly)?"),
          // "there is no race here"
          ci("no concurrency|not concurrent|no race|(?:isn'?t|not) a race"),
          // "it is only ever called from one place"
          ci("only (?:ever )?(?:called|invoked|triggered|reached) from|the only caller"));

  /**
   * Code that dispatches work concurrently, again one small pattern per construct. A single call
   * site that hands the work to any of these does not serialize it: two events each dispatch, and
   * both run.
   */
  private static final List<Pattern> CONCURRENT_DISPATCHES =
      List.of(
          // an unbounded or pooled executor
          Pattern.compile(
              "newVirtualThreadPerTaskExecutor|newCachedThreadPool|newScheduledThreadPool"
                  + "|newWorkStealingPool"),
          // A fixed pool of more than one thread. A literal count of 1 is excluded, along with the
          // whitespace and block comments around it, up to the end of the argument — a closing
          // paren, or the comma of the ThreadFactory overload, which is the standard way to name a
          // single worker. Every spelling this misses reports a serial pool as concurrent dispatch
          // and overrules a decline that was right, so the exclusion is written to be hard to spell
          // around: the paren alone let newFixedThreadPool(1, factory) through, tolerating only
          // whitespace let a /* one worker */ next to the count through, and a dot that stops at a
          // line break let that same comment through again once it wrapped. The evidence text is
          // one string of rejoined lines, so a comment does span line breaks here.
          //
          // The skip before the count is possessive because a greedy run backtracks to zero width
          // and re-matches past its own lookahead, which let newFixedThreadPool( 1 ) escape.
          //
          // Both bounds are real: a comment longer than 1024 characters, or a seventeenth run of
          // them, reads as concurrent — a false positive, since the pool is serial. They exist to
          // keep the scan over untrusted diff text linear, and nothing more should be read into
          // them. Successive rounds found a justification comment that wrapped, then one that ran
          // past 64 characters, then one carrying a long URL past 256, each time because the bound
          // was asserted to sit above "any real" annotation. It does not: a comment is prose and
          // prose has no bound. Raising this constant again is not the fix — parsing the argument
          // list instead of pattern-matching it is (#651).
          //
          // A count that is not a literal 1 — a variable, or an expression like 1 + 0 — reads as
          // concurrent. That is deliberate and matches newFixedThreadPool(workers): the class may
          // only overrule a decline on what the code plainly shows, and it cannot evaluate.
          Pattern.compile(
              "newFixedThreadPool\\s{0,16}\\((?:\\s|/\\*[\\s\\S]{0,1024}?\\*/){0,16}+"
                  + "(?!1(?:\\s|/\\*[\\s\\S]{0,1024}?\\*/){0,16}[,)])"),
          // handing the work to an executor
          Pattern.compile("\\.(?:submit|execute)\\s{0,16}\\("),
          // an asynchronous future
          Pattern.compile("CompletableFuture\\s{0,16}\\.\\s{0,16}(?:runAsync|supplyAsync)"),
          // a raw thread, an async annotation, or a parallel stream
          Pattern.compile(
              "new\\s{1,16}Thread\\s{0,16}\\(|@Async\\b|\\.parallelStream\\s{0,16}\\("));

  /**
   * Fenced code blocks in a markdown reply — quoted material, never the maintainer's assertion. The
   * body is bounded: an unbounded lazy match would rescan from every opening fence in a reply that
   * never closes one.
   */
  private static final Pattern FENCED_BLOCK =
      Pattern.compile("```.{0,10000}?```", Pattern.DOTALL | Pattern.MULTILINE);

  /**
   * Upper bound on how far {@link #stripInlineSpans} scans past an opening backtick run for its
   * closer. An opener whose closer sits beyond the bound is treated as unclosed — literal text — so
   * untrusted prose with a stray backtick cannot swallow the rest of the reply into one "span".
   * Text the bound leaves in place behaves exactly as it did before this scanner existed, so the
   * bound can narrow the fix, never widen the exposure; stripping only ever removes matches.
   */
  private static final int SPAN_BODY_BOUND = 1000;

  /**
   * Replies longer than this are not analyzed at all. It keeps the markdown-stripping scan over
   * untrusted prose bounded, and skipping is the conservative outcome: an unread reply keeps its
   * decline.
   */
  private static final int MAX_REBUTTAL_CHARS = 20_000;

  private static Pattern ci(String regex) {
    return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
  }

  private RebuttalContradiction() {}

  /**
   * The contradiction between {@code rebuttal} and {@code reviewedCode} for {@code finding}, or
   * empty when there is none — which is the overwhelmingly common case and means the decline
   * stands.
   */
  static Optional<Contradiction> find(
      ReviewResponse.Finding finding, String rebuttal, String reviewedCode) {
    if (finding == null || rebuttal == null || reviewedCode == null || reviewedCode.isBlank()) {
      return Optional.empty();
    }
    if (rebuttal.length() > MAX_REBUTTAL_CHARS) {
      return Optional.empty();
    }
    if (!CONCURRENCY_FINDING.matcher(findingText(finding)).find()) {
      return Optional.empty();
    }
    var asserted = assertedText(rebuttal);
    Matcher claim = earliestMatch(NO_CONCURRENCY_CLAIMS, asserted);
    if (claim == null) {
      return Optional.empty();
    }
    // Match evidence only against right-side (added/context) code with line comments stripped: a
    // dispatch the maintainer deleted this revision, or one that survives only in a comment, is not
    // live code and must not reopen the finding by quoting a line the code no longer runs (F3).
    var rightSide = rightSideCode(reviewedCode);
    Matcher evidence = earliestMatch(CONCURRENT_DISPATCHES, rightSide);
    if (evidence == null) {
      return Optional.empty();
    }
    return Optional.of(
        new Contradiction(
            sentenceAround(asserted, claim.start()), lineAround(rightSide, evidence.start())));
  }

  /**
   * The reviewed patch reduced to the code the revision actually runs: removed ({@code -}) lines
   * are dropped and line comments are stripped, so a dispatch construct that was deleted or only
   * mentioned in a comment cannot pose as live evidence. Added ({@code +}) and context lines are
   * kept across every file in the patch — cross-file evidence is intentional — with the leading
   * {@code +} marker dropped: the marker exists for display, not for matching, and a construct
   * whose argument wraps onto a following added line otherwise carries a {@code \n+} in the middle
   * of its argument gap. That broke the serial-pool exclusion of {@link #CONCURRENT_DISPATCHES} —
   * {@code newFixedThreadPool(} with the {@code 1)} on the next added line read as concurrent
   * dispatch, because the {@code +} is neither whitespace nor a comment and hid the count from the
   * lookahead — precisely on the code a pull request introduces (#761). Only the one marker column
   * is dropped; the line's own text (indentation included) stays verbatim, and {@link #clip} still
   * tolerates a marker when quoting.
   */
  private static String rightSideCode(String reviewedCode) {
    var kept = new ArrayList<String>();
    for (var line : reviewedCode.split("\n", -1)) {
      // A unified-diff removed line (and the "---" old-file header) starts with '-'.
      if (line.startsWith("-")) {
        continue;
      }
      // An added line (and the "+++" new-file header) starts with '+'; drop the marker column.
      if (line.startsWith("+")) {
        line = line.substring(1);
      }
      kept.add(stripLineComment(line));
    }
    return String.join("\n", kept);
  }

  /**
   * Drops a trailing {@code //} line comment, leaving any code before it intact, and blanks the
   * contents of every closed string literal to spaces (keeping the delimiters, so offsets are
   * unchanged). A {@code ://} sequence (a URL scheme) is not treated as a comment start, and
   * neither is a {@code //} that sits inside a string literal.
   *
   * <p>The blanking is the over-fire mirror of the carve-out below: keeping the whole line when the
   * only {@code //} is quoted also kept the literal's contents as scan text, so dispatch text
   * quoted in a string — {@code "hand work to .submit( here"} — reached {@link
   * #CONCURRENT_DISPATCHES} and overruled a decline over code that dispatches nothing. Quoted prose
   * is never live code, so it is erased rather than matched.
   *
   * <p>The string-literal carve-out matters because cutting at the wrong {@code //} silently throws
   * away the rest of the line — including any dispatch construct on it. A line such as {@code
   * String base = "//cdn.example.com"; executor.submit(task);}, a Go or C++ raw string ({@code
   * `a//b`}, {@code R"(a//b)"}), or any quoted path holding a doubled slash used to be truncated at
   * the quoted slashes, so the {@code executor.submit(} after them never reached {@link
   * #CONCURRENT_DISPATCHES}. A maintainer's "it runs serially" decline then stood unchallenged over
   * code that does dispatch concurrently — the false-negative direction this class exists to close.
   *
   * <p>Quote tracking is deliberately shallow: it toggles on an unescaped {@code "}, {@code '} or
   * {@code `} and nothing else. It cannot know a language's escaping rules and does not try to.
   *
   * <p>An opener with no closer on the line is therefore assumed to have been something else — a
   * Rust lifetime ({@code &'a ctx}), an apostrophe in prose — and the scan resumes past it,
   * treating the opener as ordinary text. Without that a single stray apostrophe kept the whole
   * trailing comment as scan text, and a dispatch named only inside a comment could overrule a
   * decline — the false-positive direction, which the plain first-{@code //} cut this carve-out
   * replaced did not have. Resuming quote-aware (rather than cutting at the first bare {@code //}
   * the opener swallowed) keeps later literals stepped over whole, so {@code &'a ctx; var s =
   * "//cdn.example.com"; executor.submit(...)} is not truncated inside the closed string.
   *
   * <p>The one escaping rule it does keep is where the escape applies: inside a {@code "} or {@code
   * '} literal, never inside a backtick one and never outside a literal at all. A Go raw string is
   * backtick-delimited and holds a backslash literally, so honouring the escape there stepped over
   * the closing backtick of a Windows path ({@code `C:\`}), left the state open for the rest of the
   * line, and kept a real trailing comment as live code. That is the false positive — a decline
   * overruled by text the code does not run — which is the worse direction, and the plain
   * first-{@code //} cut this carve-out replaced did not have it.
   *
   * <p>A <em>multi-character</em> delimiter is the case this shallowness does not cover, and it
   * fails the other way. A Java text block or a C++ raw string whose content holds an interior
   * quote ({@code """<a href="//host">x</a>"""}, {@code R"(a"b//c)"}) closes the scanner's
   * single-quote state early, so a {@code //} still inside the literal truncates the line and drops
   * any dispatch after it — the same false negative the carve-out above exists to close, one legal
   * interior quote away from the shapes that are covered. Closing it needs delimiter-aware openers
   * rather than a toggle; tracked in #651.
   *
   * <p>Two limits of the blanking are accepted for the same reason — each needs knowledge a
   * line-scoped toggle cannot have, and both are the tokenizer #651 asks for. A literal spanning
   * lines (a Go raw string, a Java text block) is not blanked: its opener has no closer on the
   * line, so it is rescanned as ordinary text, and the content lines carry no delimiter at all —
   * dispatch text quoted there still over-fires. Carrying quote state across lines is not the fix,
   * because the scan text is a whole multi-file patch rejoined: one stray delimiter would blank
   * unrelated files' code and silently drop real dispatches — trading a narrow over-fire for a
   * broad false negative. And a backtick literal is always blanked whole, including a JavaScript
   * template literal's {@code ${...}} interpolation, which is live code: a dispatch that appears
   * only inside an interpolation is missed (under-fire, the decline stands). Telling a JS template
   * apart from a Go raw string quoting literal {@code ${...}} text needs a language hint; erasing
   * is the conservative side, since over-fire argues with a maintainer who is right.
   */
  private static String stripLineComment(String line) {
    var out = new StringBuilder(line.length());
    var i = 0;
    while (i < line.length()) {
      var c = line.charAt(i);
      if (c == '"' || c == '\'' || c == '`') {
        i = appendBlankedLiteral(line, i, out);
      } else if (isDoubleSlash(line, i)) {
        if (i > 0 && line.charAt(i - 1) == ':') {
          out.append("//");
          i += 2;
        } else {
          return out.toString();
        }
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }

  /**
   * Index of the quote closing the literal opened at {@code open}, or {@code -1} when the line ends
   * first. A backslash escapes the next character in a {@code "} or {@code '} literal and not in a
   * backtick one, which is a Go raw string and holds the backslash literally.
   */
  private static int endOfLiteral(String line, int open) {
    var quote = line.charAt(open);
    var i = open + 1;
    while (i < line.length()) {
      var c = line.charAt(i);
      if (c == '\\' && quote != '`') {
        i += 2;
      } else if (c == quote) {
        return i;
      } else {
        i++;
      }
    }
    return -1;
  }

  /**
   * Handles the quote opener at {@code open}: appends its rendering to {@code out} and returns the
   * index the scan resumes at. A closed literal is stepped over whole — and blanked. Its delimiters
   * stay (space for space, so every offset after it is unchanged and lineAround still quotes the
   * real line shape), but its contents are prose, not code the revision runs: a log message, an
   * error string or a fixture that mentions ".submit(" must not read as live dispatch and overrule
   * a decline that was right. An opener that never closes — or a lone apostrophe (a Rust lifetime)
   * paired with a later char literal's opener, whose "span" is really live code — was not one; it
   * is appended verbatim and rescanned as ordinary text.
   */
  private static int appendBlankedLiteral(String line, int open, StringBuilder out) {
    var quote = line.charAt(open);
    var close = endOfLiteral(line, open);
    out.append(quote);
    if (close < 0 || isMisreadApostrophePair(line, quote, open, close)) {
      return open + 1;
    }
    out.append(" ".repeat(close - open - 1));
    out.append(line.charAt(close));
    return close + 1;
  }

  /**
   * Whether a {@code '}-quoted span is really two unrelated apostrophes with live code between
   * them, rather than a literal. A lone apostrophe in non-string context — a Rust lifetime ({@code
   * &'a ctx}, {@code foo::<'a>}) — followed later on the line by a real char literal ({@code '\n'})
   * or another lifetime pairs with that later apostrophe, and blanking the span would erase any
   * dispatch between them: a false negative the pre-blanking scanner did not have. Three tells mark
   * a span as misread, each shaped so genuine quoted prose (which may hold {@code .submit(} text —
   * the very thing the blanking exists to erase) keeps blanking: the opener sits directly after
   * {@code &} or {@code <}, which is Rust lifetime syntax and never a string opener; the span holds
   * a {@code ;}, which is statement shape, not prose; or the pairing closer itself opens a
   * char-literal-shaped span, meaning the "closer" was really the next literal's opener. The
   * residual miss — quoted prose that defeats every tell and also names a dispatch construct — is
   * far narrower than erasing arbitrary real code. Double-quote and backtick openers have no
   * lifetime-style bare use, so the guard applies to {@code '} alone.
   */
  private static boolean isMisreadApostrophePair(String line, char quote, int open, int close) {
    if (quote != '\'') {
      return false;
    }
    // Lifetime position: an apostrophe directly after & or < ( &'a ctx, foo::<'a> ) is Rust
    // syntax, never a string opener, whatever its span holds.
    if (open > 0 && (line.charAt(open - 1) == '&' || line.charAt(open - 1) == '<')) {
      return true;
    }
    // Statement separator: a ; inside the span is code shape, not quoted prose.
    if (line.substring(open + 1, close).indexOf(';') >= 0) {
      return true;
    }
    // The pairing closer itself opens a char-literal-shaped span — '\n', ',', or a longer escape
    // like a Rust unicode char (backslash-u{1F600}): the "closer" was really the next literal's
    // opener, and everything between the two apostrophes is live code. Char-shaped means at most
    // two characters — one character or a simple escape — or an escape sequence: backslash-led
    // and short enough for the longest real spelling, Rust's backslash-u{10FFFF} at 9 characters.
    // Ten leaves a margin without reading a backslash-led prose span as a char.
    var closerAsOpener = endOfLiteral(line, close);
    if (closerAsOpener < 0) {
      return false;
    }
    var charSpan = line.substring(close + 1, closerAsOpener);
    return charSpan.length() <= 2 || (charSpan.length() <= 10 && charSpan.charAt(0) == '\\');
  }

  /** Whether a doubled slash sits at {@code at}. */
  private static boolean isDoubleSlash(String line, int at) {
    return line.charAt(at) == '/' && at + 1 < line.length() && line.charAt(at + 1) == '/';
  }

  /**
   * The leftmost match of any pattern in {@code patterns}, or {@code null} when none matches. Every
   * pattern is tried and the earliest wins, so splitting one alternation into several keeps the
   * leftmost-match semantics the single pattern had and leaves the result independent of list
   * order.
   */
  private static Matcher earliestMatch(List<Pattern> patterns, String text) {
    Matcher earliest = null;
    for (var pattern : patterns) {
      var candidate = pattern.matcher(text);
      if (candidate.find() && (earliest == null || candidate.start() < earliest.start())) {
        earliest = candidate;
      }
    }
    return earliest;
  }

  /**
   * Lead-in of {@link Contradiction#note()}, shared with the review body surface ({@code
   * ReviewPublisher.noIssuesBody}) so a reopened decline can be told apart from an ordinary
   * unresolved note and rendered next to the "previous finding(s) remain unresolved" line (F6).
   */
  static final String NOTE_LEAD_IN = "Decline re-checked against the code and not accepted:";

  /** The quoted claim and the quoted code line that refutes it, both already trimmed for a note. */
  record Contradiction(String claim, String evidence) {

    /** One-line status note naming the contradiction, for {@code previous_findings_status}. */
    String note() {
      return NOTE_LEAD_IN
          + " the reply argues \""
          + claim
          + "\", but the reviewed code dispatches this path concurrently — \""
          + evidence
          + "\". A single call site does not serialize work handed to an executor or a new"
          + " thread, so the premise does not refute the finding. Reply again to keep the"
          + " decline.";
    }
  }

  private static String findingText(ReviewResponse.Finding finding) {
    return (finding.title() == null ? "" : finding.title())
        + "\n"
        + (finding.description() == null ? "" : finding.description());
  }

  /**
   * The reply with fenced code blocks, blockquoted lines and inline code spans removed, lower-cased
   * boundaries intact — what the maintainer actually asserts, as opposed to what they quote.
   *
   * <p>Spans are stripped only after the blockquote filter: a span's newline replacement splits its
   * line, and splitting a {@code >} line before the filter would hand the fragment after the span
   * to the filter without its {@code >} prefix — quoted material surviving as an assertion, the
   * over-fire this method exists to prevent. In the other direction the order can change what the
   * span scan sees both ways, and neither outcome is new exposure: an opener whose closer lived on
   * a dropped blockquote line no longer closes and its run stays literal (text the reply carried as
   * visible prose all along, exactly what the scan yields for any unclosed run), and a drop that
   * pulls an opener and closer within the newline bound strips more, which only removes claim
   * matches — the keep-the-decline direction.
   */
  private static String assertedText(String rebuttal) {
    var withoutFences = FENCED_BLOCK.matcher(rebuttal).replaceAll(" ");
    var kept = new ArrayList<String>();
    for (var line : withoutFences.split("\n", -1)) {
      if (!line.stripLeading().startsWith(">")) {
        kept.add(line);
      }
    }
    // Joined, not terminated: a reply that ends mid-sentence must stay unterminated, so
    // sentenceAround's end-of-text bound is a live case rather than an unreachable guard.
    return stripInlineSpans(String.join("\n", kept));
  }

  /**
   * Removes inline code spans — quoted constructs, never the maintainer's assertion — with a
   * delimiter-aware scan instead of a regex. A backticked quotation in a decline ("the bot's text
   * says {@code `it never runs concurrently`}") must not be matched as the maintainer's own
   * assertion about the code.
   *
   * <p>Per CommonMark, an opening run of N backticks closes at the next run of <em>exactly</em> N
   * backticks, so a body may carry any backtick run of a different length ({@code `a``b`} is one
   * single-backtick span, {@code ``x`y``} one double-backtick span). A run that never closes is
   * literal text, scanned past rather than matched — the delimiter-aware replacement for the regex
   * passes this scanner supersedes, which stopped at any interior backtick and left such spans in
   * place (#697). Two bounds keep untrusted prose from turning one stray backtick into a span that
   * swallows the reply: the closer must sit within {@link #SPAN_BODY_BOUND} characters of the
   * opener, and the body may contain at most one line ending (CommonMark allows a span to cross a
   * line break; a multi-paragraph "span" here is far more likely an unclosed backtick). Anything
   * the scan cannot classify stays in place, which is not new exposure: unstripped text behaves
   * exactly as every reply did before span stripping existed, while stripping only ever removes
   * claim matches.
   *
   * <p>Each span is replaced with a newline, not a space: a space would bridge the words abutting
   * the backticks into a phrase that was never contiguous in the original reply ({@code one at
   * a`beat`time} must not become "one at a time"), turning stripping into a way to <em>add</em> a
   * claim match. The stripped text is matched with the newlines still in it — {@link #assertedText}
   * joins lines with {@code \n}, never a space — and a newline appears inside no claim pattern
   * while being a sentence boundary for the quoted note, so stripping can only remove matches.
   *
   * <p>Runs of three or more backticks are handled like any other length. {@link #FENCED_BLOCK}
   * pairs triple-backtick runs only within its 10000-character bound, and the blockquote filter can
   * re-join runs that were farther apart in the raw reply, so a paired triple run can reach this
   * scan and be closed here even though CommonMark would read the region as a fenced block — an
   * over-strip that only removes claim matches.
   */
  private static String stripInlineSpans(String text) {
    var out = new StringBuilder(text.length());
    var i = 0;
    while (i < text.length()) {
      var c = text.charAt(i);
      if (c != '`') {
        out.append(c);
        i++;
        continue;
      }
      var openerEnd = endOfBacktickRun(text, i);
      var closer = closingRunStart(text, openerEnd, openerEnd - i);
      if (closer < 0) {
        // Unclosed within the bounds: the run is literal text, kept in place.
        out.append(text, i, openerEnd);
        i = openerEnd;
      } else {
        out.append('\n');
        i = closer + (openerEnd - i);
      }
    }
    return out.toString();
  }

  /**
   * Start index of the run of exactly {@code delimiter} backticks closing a span whose body begins
   * at {@code from}, or {@code -1} when no such run sits within {@link #SPAN_BODY_BOUND} characters
   * or the body would contain more than one line ending.
   */
  private static int closingRunStart(String text, int from, int delimiter) {
    var newlines = 0;
    var i = from;
    // +1 so a body of exactly SPAN_BODY_BOUND characters still closes, matching the upper bound
    // carried by the regex passes this scanner replaced.
    var bound = Math.min(text.length(), from + SPAN_BODY_BOUND + 1);
    while (i < bound) {
      var c = text.charAt(i);
      if (c == '`') {
        var runEnd = endOfBacktickRun(text, i);
        if (runEnd - i == delimiter) {
          return i;
        }
        i = runEnd;
      } else {
        if (c == '\n' && ++newlines > 1) {
          return -1;
        }
        i++;
      }
    }
    return -1;
  }

  /** Index just past the maximal run of backticks starting at {@code at}. */
  private static int endOfBacktickRun(String text, int at) {
    var i = at;
    while (i < text.length() && text.charAt(i) == '`') {
      i++;
    }
    return i;
  }

  /** The sentence containing {@code index}, collapsed to one line and clipped for a note. */
  private static String sentenceAround(String text, int index) {
    var start = index;
    while (start > 0 && !isSentenceEnd(text.charAt(start - 1))) {
      start--;
    }
    var end = index;
    while (end < text.length() && !isSentenceEnd(text.charAt(end))) {
      end++;
    }
    return clip(text.substring(start, Math.min(end + 1, text.length())));
  }

  private static boolean isSentenceEnd(char c) {
    return c == '.' || c == '\n' || c == '!' || c == '?' || c == ';';
  }

  /** The source line containing {@code index}, clipped for a note. */
  private static String lineAround(String text, int index) {
    var start = text.lastIndexOf('\n', index) + 1;
    var end = text.indexOf('\n', index);
    return clip(text.substring(start, end < 0 ? text.length() : end));
  }

  /**
   * Collapses whitespace, drops a leading unified-diff marker, and clips to {@link #QUOTE_LIMIT}.
   */
  private static String clip(String raw) {
    var collapsed = raw.replaceAll("\\s+", " ").strip();
    // startsWith, not charAt, so no emptiness guard is needed for a blank quoted line.
    if (collapsed.startsWith("+") || collapsed.startsWith("-")) {
      collapsed = collapsed.substring(1).strip();
    }
    if (collapsed.length() <= QUOTE_LIMIT) {
      return collapsed;
    }
    return collapsed.substring(0, QUOTE_LIMIT).stripTrailing() + "…";
  }
}
