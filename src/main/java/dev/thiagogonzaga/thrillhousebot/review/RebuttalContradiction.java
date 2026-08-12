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
          // a fixed pool of more than one thread
          Pattern.compile("newFixedThreadPool\\s{0,16}\\(\\s{0,16}(?!1\\s{0,16}\\))"),
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
   * kept verbatim (including their leading marker, which {@link #clip} removes when quoting),
   * across every file in the patch — cross-file evidence is intentional.
   */
  private static String rightSideCode(String reviewedCode) {
    var kept = new ArrayList<String>();
    for (var line : reviewedCode.split("\n", -1)) {
      // A unified-diff removed line (and the "---" old-file header) starts with '-'.
      if (line.startsWith("-")) {
        continue;
      }
      kept.add(stripLineComment(line));
    }
    return String.join("\n", kept);
  }

  /**
   * Drops a trailing {@code //} line comment, leaving any code before it intact. A {@code ://}
   * sequence (a URL scheme) is not treated as a comment start, and neither is a {@code //} that
   * sits inside a string literal.
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
   * {@code `} and nothing else. It cannot know a language's escaping rules and does not try to; the
   * worst an unbalanced quote costs is that a real trailing comment is kept, which only feeds the
   * scan text of the kind it already sees on every line that carries no {@code //} at all.
   */
  private static String stripLineComment(String line) {
    var quote = '\0';
    for (var i = 0; i < line.length(); i++) {
      var c = line.charAt(i);
      if (c == '\\') {
        i++;
      } else if (quote != '\0') {
        if (c == quote) {
          quote = '\0';
        }
      } else if (c == '"' || c == '\'' || c == '`') {
        quote = c;
      } else if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
        // A URL scheme's "://" is not a comment start; step past it and keep scanning.
        if (i > 0 && line.charAt(i - 1) == ':') {
          i++;
          continue;
        }
        return line.substring(0, i);
      }
    }
    return line;
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
   * The reply with fenced code blocks and blockquoted lines removed, lower-cased boundaries intact
   * — what the maintainer actually asserts, as opposed to what they quote.
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
    return String.join("\n", kept);
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
