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
 *       #NO_CONCURRENCY_CLAIM}) — "single-threaded", "runs serially", "only ever called from …";
 *   <li>the reviewed code contains a concurrent-dispatch construct ({@link #CONCURRENT_DISPATCH}) —
 *       an unbounded/pooled executor, {@code executor.submit/execute}, {@code CompletableFuture
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
   * The maintainer asserting that the flagged path cannot run concurrently. "Only ever called from
   * X" belongs here: a single call site is the usual way a decline argues away a race, and it is
   * exactly the premise an asynchronous dispatch at that single call site refutes.
   */
  private static final Pattern NO_CONCURRENCY_CLAIM =
      Pattern.compile(
          "single[- ]threaded|\\bsingle thread\\b|runs? serially|executed? serially|serialized"
              + "|sequentially|one at a time|never (?:runs?|executes?|happens) concurrently"
              + "|(?:cannot|can't|can not|never) (?:run|be|happen|occur)s? concurrent(?:ly)?"
              + "|no concurrency|not concurrent|there is no race|no race|isn'?t a race"
              + "|not a race|only ever (?:called|invoked|triggered|reached) from"
              + "|only (?:called|invoked|triggered|reached) from|the only caller",
          Pattern.CASE_INSENSITIVE);

  /**
   * Code that dispatches work concurrently. A single call site that hands the work to any of these
   * does not serialize it: two events each dispatch, and both run.
   */
  private static final Pattern CONCURRENT_DISPATCH =
      Pattern.compile(
          "newVirtualThreadPerTaskExecutor|newCachedThreadPool|newWorkStealingPool"
              + "|newFixedThreadPool\\s*\\(\\s*(?!1\\s*\\))|newScheduledThreadPool"
              + "|\\.submit\\s*\\(|\\.execute\\s*\\(|CompletableFuture\\s*\\.\\s*(?:runAsync|supplyAsync)"
              + "|new\\s+Thread\\s*\\(|@Async\\b|\\.parallelStream\\s*\\(");

  /** Fenced code blocks in a markdown reply — quoted material, never the maintainer's assertion. */
  private static final Pattern FENCED_BLOCK =
      Pattern.compile("```.*?```", Pattern.DOTALL | Pattern.MULTILINE);

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
    if (!CONCURRENCY_FINDING.matcher(findingText(finding)).find()) {
      return Optional.empty();
    }
    Matcher claim = NO_CONCURRENCY_CLAIM.matcher(assertedText(rebuttal));
    if (!claim.find()) {
      return Optional.empty();
    }
    Matcher evidence = CONCURRENT_DISPATCH.matcher(reviewedCode);
    if (!evidence.find()) {
      return Optional.empty();
    }
    return Optional.of(
        new Contradiction(
            sentenceAround(assertedText(rebuttal), claim.start()),
            lineAround(reviewedCode, evidence.start())));
  }

  /** The quoted claim and the quoted code line that refutes it, both already trimmed for a note. */
  record Contradiction(String claim, String evidence) {

    /** One-line status note naming the contradiction, for {@code previous_findings_status}. */
    String note() {
      return "Decline re-checked against the code and not accepted: the reply argues \""
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
